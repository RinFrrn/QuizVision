package com.virin.visionquiz.vision.questiondetector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.lifecycle.LiveData
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.virin.visionquiz.vision.graphic.GraphicOverlay
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizManager
import com.virin.visionquiz.vision.VisionProcessorBase
import com.virin.visionquiz.preference.PreferenceUtils
import com.virin.visionquiz.util.AnswerOptionTextMatcher
import com.virin.visionquiz.util.QuizGraphicItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import com.virin.visionquiz.vision.ocr.OcrDocument
import com.virin.visionquiz.vision.ocr.OcrEngine
import com.virin.visionquiz.vision.ocr.OcrEngineFactory
import com.virin.visionquiz.vision.ocr.OcrEngineType

class QuizRecognitionProcessor(
    private val context: Context,
    private val quizzes: LiveData<List<Quiz>>,
    private val onMatchesDetected: ((List<QuizGraphicItem>) -> Unit)? = null,
    private val minMatchScore: Double = QuizManager.DEFAULT_MIN_MATCH_SCORE,
    private val locateScreenAnswerRects: Boolean = false,
    private val confirmEmptyResults: Boolean = true
) : VisionProcessorBase<OcrDocument>(context) {

    private val ocrEngine: OcrEngine = OcrEngineFactory.create(
        context,
        OcrEngineType.fromStableValue(PreferenceUtils.getOcrEngine(context))
    )
    private val shouldGroupRecognizedTextInBlocks: Boolean =
        PreferenceUtils.shouldGroupRecognizedTextInBlocks(context)
    private val shouldMergeOcrTextAcrossBlocks: Boolean =
        PreferenceUtils.shouldMergeOcrTextAcrossBlocks(context)
    private val shouldCleanOcrQuestionText: Boolean =
        PreferenceUtils.shouldCleanOcrQuestionText(context)
    private val shouldUseShortOcrOptionSupport: Boolean =
        PreferenceUtils.shouldUseShortOcrOptionSupport(context)
    private val showConfidence: Boolean = PreferenceUtils.shouldShowTextConfidence(context)
    private val useBriefAnswerDisplay: Boolean = PreferenceUtils.shouldUseBriefAnswerDisplay(context)
    private val overlayTextSizeSp: Float = PreferenceUtils.getQuizOverlayTextSizeSp(context)
    private val matchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val matchEpoch = AtomicInteger()
    private val matchRequests = Channel<MatchRequest>(capacity = Channel.CONFLATED)
    private val matchingJob: Job = matchScope.launch {
        for (request in matchRequests) {
            processMatchRequest(request)
        }
    }
    @Volatile
    private var cachedQuizSnapshot: List<Quiz>? = null
    @Volatile
    private var cachedQuizIndex: QuizManager.QuizMatchIndex? = null
    @Volatile
    private var displayedMatches: List<QuizGraphicItem> = emptyList()

    private data class RecognizedTextItem(
        val text: String,
        val rect: Rect,
        val startOrder: Int,
        val endOrder: Int
    )

    private data class RecognizedLineItem(
        val text: String,
        val rect: Rect,
        val order: Int
    )

    private data class MatchedTextItem(
        val item: QuizGraphicItem,
        val source: RecognizedTextItem
    )

    private data class RankedQuizMatch(
        val quiz: Quiz,
        val originalScore: Double,
        val adjustedScore: Double,
        val optionSupportScore: Double,
        val runnerUpMargin: Double?
    )

    private data class SelectedQuizMatch(
        val quiz: Quiz,
        val score: Double,
        val debugLines: List<String>
    )

    private data class MatchRequest(
        val epoch: Int,
        val quizSnapshot: List<Quiz>,
        val recognizedTextItems: List<RecognizedTextItem>,
        val lineCandidates: List<OcrOptionLocator.TextCandidate>,
        val imageWidth: Int,
        val imageHeight: Int,
        val graphicOverlay: GraphicOverlay
    )

    private val stableResultGate = StableResultGate(
        requiredStableResults = REQUIRED_STABLE_MATCH_FRAMES,
        fingerprintOf = ::buildStableMatchesFingerprint,
        isEmpty = List<QuizGraphicItem>::isEmpty,
        confirmEmptyResults = confirmEmptyResults
    )

    private fun createRecognizedTextItem(
        text: String,
        boundingBox: Rect,
        startOrder: Int,
        endOrder: Int = startOrder
    ): RecognizedTextItem? {
        val matchText = cleanOcrQuestionTextIfEnabled(text)
        val rect = Rect(boundingBox)
        if (!isValidRecognizedTextItem(matchText, rect)) {
            return null
        }
        return RecognizedTextItem(matchText, rect, startOrder, endOrder)
    }

    private fun isValidRecognizedTextItem(text: String, rect: Rect): Boolean {
        if (text.isBlank()) {
            return false
        }
        if (rect.width() <= MIN_TEXT_RECT_SIZE || rect.height() <= MIN_TEXT_RECT_SIZE) {
            return false
        }
        if (rect.width() * rect.height() <= MIN_TEXT_RECT_AREA) {
            return false
        }
        return QuizManager.normalizeQuestionText(text).length >= MIN_NORMALIZED_TEXT_LENGTH
    }

    private fun createRecognizedLineItem(
        text: String,
        boundingBox: Rect,
        order: Int
    ): RecognizedLineItem? {
        val validationText = cleanOcrQuestionTextIfEnabled(text)
        val rect = Rect(boundingBox)
        if (!isValidRecognizedTextItem(validationText, rect)) {
            return null
        }
        return RecognizedLineItem(text, rect, order)
    }

    private fun cleanOcrQuestionTextIfEnabled(text: String): String {
        return if (shouldCleanOcrQuestionText) {
            OcrQuestionTextCleaner.clean(text)
        } else {
            text
        }
    }

    private fun getMatchedQuizGraphicItem(
        recognizedTextItem: RecognizedTextItem,
        quizIndex: QuizManager.QuizMatchIndex,
        lineCandidates: List<OcrOptionLocator.TextCandidate>
    ): MatchedTextItem? {
        val matches = QuizManager.matchQuiz(
            recognizedTextItem.text,
            quizIndex,
            minScore = minMatchScore,
            maxResults = OPTION_RERANK_CANDIDATE_COUNT
        )
        val bestMatch = selectBestMatch(
            matches = matches,
            source = recognizedTextItem,
            lineCandidates = lineCandidates
        )
        if (bestMatch != null) {
            return MatchedTextItem(
                item = QuizGraphicItem(
                    bestMatch.quiz,
                    bestMatch.score,
                    recognizedTextItem.rect,
                    debugLines = bestMatch.debugLines
                ),
                source = recognizedTextItem
            )
        }
        return null
    }

    private fun selectBestMatch(
        matches: List<Pair<Quiz, Double>>,
        source: RecognizedTextItem,
        lineCandidates: List<OcrOptionLocator.TextCandidate>
    ): SelectedQuizMatch? {
        if (matches.isEmpty()) {
            return null
        }
        if (matches.size == 1) {
            val only = matches.first()
            return SelectedQuizMatch(
                quiz = only.first,
                score = only.second,
                debugLines = buildMatchDebugLines(
                    originalScore = only.second,
                    adjustedScore = only.second,
                    optionSupportScore = 0.0,
                    runnerUpMargin = null,
                    source = source
                )
            )
        }
        val rankedMatches = matches
            .map { match ->
                val nearbyOptionTexts = collectNearbyOptionTexts(
                    source = source,
                    quiz = match.first,
                    lineCandidates = lineCandidates
                )
                val optionSupport = if (nearbyOptionTexts.isEmpty()) {
                    0.0
                } else {
                    computeOptionSupportScore(match.first, nearbyOptionTexts)
                }
                RankedQuizMatch(
                    quiz = match.first,
                    originalScore = match.second,
                    adjustedScore = minOf(1.0, match.second + optionSupport),
                    optionSupportScore = optionSupport,
                    runnerUpMargin = null
                )
            }
            .sortedWith(
                compareByDescending<RankedQuizMatch> { it.adjustedScore }
                    .thenByDescending { it.originalScore }
            )
        val best = rankedMatches.firstOrNull() ?: return null
        val runnerUp = rankedMatches.getOrNull(1)
        val margin = runnerUp?.let { best.adjustedScore - it.adjustedScore }
        if (
            runnerUp != null &&
            best.optionSupportScore == 0.0 &&
            best.originalScore < STRONG_QUESTION_MATCH_SCORE &&
            (margin ?: 0.0) < MIN_AMBIGUOUS_MATCH_MARGIN
        ) {
            return null
        }
        return SelectedQuizMatch(
            quiz = best.quiz,
            score = best.adjustedScore,
            debugLines = buildMatchDebugLines(
                originalScore = best.originalScore,
                adjustedScore = best.adjustedScore,
                optionSupportScore = best.optionSupportScore,
                runnerUpMargin = margin,
                source = source
            )
        )
    }

    private fun buildMatchDebugLines(
        originalScore: Double,
        adjustedScore: Double,
        optionSupportScore: Double,
        runnerUpMargin: Double?,
        source: RecognizedTextItem
    ): List<String> {
        return buildList {
            add("题干 ${formatScore(originalScore)} -> ${formatScore(adjustedScore)}")
            if (optionSupportScore > 0.0) {
                add("选项支持 +${formatScore(optionSupportScore)}")
            }
            runnerUpMargin?.let {
                add("候选差 ${formatScore(it)}")
            }
            add("OCR ${resolveSourceLineCount(source)} 行")
        }
    }

    private fun resolveSourceLineCount(source: RecognizedTextItem): Int {
        return ((source.endOrder - source.startOrder) / ORDER_SCALE + 1).coerceAtLeast(1)
    }

    private fun formatScore(value: Double): String {
        return String.format("%.2f", value)
    }

    private fun collectNearbyOptionTexts(
        source: RecognizedTextItem,
        quiz: Quiz,
        lineCandidates: List<OcrOptionLocator.TextCandidate>
    ): List<String> {
        val questionEndOrder = resolveQuestionEndOrder(
            source = source,
            prompt = quiz.prompt,
            lineCandidates = lineCandidates
        )
        val maxOrder = questionEndOrder + OPTION_CONTEXT_LINE_COUNT * ORDER_SCALE
        val nearbyLineCandidates = lineCandidates
            .asSequence()
            .filter { it.order % ORDER_SCALE == LINE_CANDIDATE_ORDER_OFFSET }
            .filter { it.order > questionEndOrder }
            .filter { it.order <= maxOrder }
            .filter { it.bounds.top >= source.rect.top }
            .sortedBy { it.order }
            .takeWhile {
                !OcrQuestionCandidateBuilder.isQuestionStartLine(it.text)
            }
            .take(MAX_OPTION_SUPPORT_TEXTS * MAX_OPTION_LINE_PARTS)
            .toList()
        return OcrOptionLineMerger.merge(nearbyLineCandidates)
            .asSequence()
            .map(OcrOptionLocator.TextCandidate::text)
            .filter {
                val normalizedLength = AnswerOptionTextMatcher.normalizeOptionText(it).length
                normalizedLength >= if (shouldUseShortOcrOptionSupport) {
                    MIN_SHORT_OPTION_SUPPORT_LENGTH
                } else {
                    OcrOptionSupportScorer.MIN_LONG_OPTION_LENGTH
                }
            }
            .take(MAX_OPTION_SUPPORT_TEXTS)
            .toList()
    }

    private fun computeOptionSupportScore(
        quiz: Quiz,
        nearbyOptionTexts: List<String>
    ): Double {
        return OcrOptionSupportScorer.score(
            options = quiz.options,
            nearbyTexts = nearbyOptionTexts,
            minMatchScore = minMatchScore,
            allowShortOptions = shouldUseShortOcrOptionSupport
        )
    }

    private fun getQuizIndex(quizSnapshot: List<Quiz>): QuizManager.QuizMatchIndex {
        val index = cachedQuizIndex
        if (cachedQuizSnapshot === quizSnapshot && index != null) {
            return index
        }

        return synchronized(this) {
            val lockedIndex = cachedQuizIndex
            if (cachedQuizSnapshot === quizSnapshot && lockedIndex != null) {
                lockedIndex
            } else {
                QuizManager.buildMatchIndex(quizSnapshot).also {
                    cachedQuizSnapshot = quizSnapshot
                    cachedQuizIndex = it
                }
            }
        }
    }

    private fun addMatchesGraphic(
        graphicOverlay: GraphicOverlay,
        matches: List<QuizGraphicItem>
    ) {
        if (matches.isEmpty()) {
            return
        }
        graphicOverlay.add(
            QuizGraphic(
                graphicOverlay,
                matches,
                shouldGroupRecognizedTextInBlocks,
                showConfidence,
                useBriefAnswerDisplay,
                overlayTextSizeSp
            )
        )
    }

    private fun buildDisplayMatches(
        matchedQuizs: List<MatchedTextItem>,
        lineCandidates: List<OcrOptionLocator.TextCandidate>,
        imageWidth: Int,
        imageHeight: Int
    ): List<QuizGraphicItem> {
        if (matchedQuizs.isEmpty()) {
            return emptyList()
        }

        val bestMatches = matchedQuizs.groupBy { matchIdentity(it.item) }
            .mapNotNull { (_, items) ->
                items.minWithOrNull(
                    compareByDescending<MatchedTextItem> { it.item.distance }
                        .thenBy { sourceLengthDifference(it) }
                        .thenBy { resolveSourceLineCount(it.source) }
                        .thenBy { it.source.rect.width().toLong() * it.source.rect.height() }
                )
            }
        val spatiallyUniqueMatches = OcrDisplayMatchSelector.select(
            bestMatches.map { match ->
                OcrDisplayMatchSelector.Candidate(
                    value = match,
                    identity = matchIdentity(match.item),
                    bounds = match.source.rect.toLocatorBounds(),
                    score = match.item.distance
                )
            }
        ).map(OcrDisplayMatchSelector.Candidate<MatchedTextItem>::value)
        val matchesByReadingOrder = spatiallyUniqueMatches.sortedWith(
            compareBy<MatchedTextItem> { it.source.startOrder }
                .thenBy { it.source.rect.top }
                .thenBy { it.source.rect.left }
        )

        val localizedMatches = matchesByReadingOrder.mapIndexed { index, match ->
            val locatedOptions = if (locateScreenAnswerRects) {
                val nextQuestionStartOrder = matchesByReadingOrder
                    .getOrNull(index + 1)
                    ?.source
                    ?.startOrder
                OcrOptionLocator.locate(
                    question = OcrOptionLocator.QuestionMatch(
                        options = match.item.question.options,
                        answerIndices = match.item.question.answer,
                        bounds = match.source.rect.toLocatorBounds(),
                        startOrder = match.source.startOrder,
                        endOrder = resolveQuestionEndOrder(match, lineCandidates)
                    ),
                    candidates = lineCandidates,
                    nextQuestionStartOrder = nextQuestionStartOrder,
                    imageHeight = imageHeight,
                    minMatchScore = minMatchScore
                )
            } else {
                OcrOptionLocator.Result(emptyList(), emptyList())
            }
            match.item.copy(
                rect = padAndClampRect(match.item.rect, imageWidth, imageHeight),
                answerRects = locatedOptions.answerBounds.map {
                    padAndClampRect(it.toAndroidRect(), imageWidth, imageHeight)
                },
                optionRects = locatedOptions.optionBounds.map {
                    padAndClampRect(it.toAndroidRect(), imageWidth, imageHeight)
                },
                isAnswerPartiallyMatched = locatedOptions.isAnswerPartiallyMatched,
                debugLines = match.item.debugLines + buildAnswerLocationDebugLines(
                    match,
                    locatedOptions
                )
            )
        }
        return OcrDisplayMatchSelector.selectQuizGraphicItems(localizedMatches)
    }

    private fun sourceLengthDifference(match: MatchedTextItem): Int {
        val sourceLength = QuizManager.normalizeQuestionText(match.source.text).length
        val promptLength = QuizManager.normalizeQuestionText(match.item.question.prompt).length
        return abs(sourceLength - promptLength)
    }

    private fun buildAnswerLocationDebugLines(
        match: MatchedTextItem,
        result: OcrOptionLocator.Result
    ): List<String> {
        if (!locateScreenAnswerRects) {
            return emptyList()
        }
        val answerCount = match.item.question.answer.size
        val partial = if (result.isAnswerPartiallyMatched) " partial" else ""
        return listOf(
            "答案框 ${result.answerBounds.size}/$answerCount$partial",
            "选项框 ${result.optionBounds.size}/${match.item.question.options.size}"
        )
    }

    private fun resolveQuestionEndOrder(
        match: MatchedTextItem,
        lineCandidates: List<OcrOptionLocator.TextCandidate>
    ): Int {
        return resolveQuestionEndOrder(
            source = match.source,
            prompt = match.item.question.prompt,
            lineCandidates = lineCandidates
        )
    }

    private fun resolveQuestionEndOrder(
        source: RecognizedTextItem,
        prompt: String,
        lineCandidates: List<OcrOptionLocator.TextCandidate>
    ): Int {
        if (source.endOrder >= source.startOrder) {
            return source.endOrder
        }
        val normalizedPrompt = QuizManager.normalizeQuestionText(prompt)
        if (normalizedPrompt.isBlank()) {
            return source.endOrder
        }
        val accumulatedText = StringBuilder()
        val questionLines = lineCandidates
            .asSequence()
            .filter { it.order >= source.startOrder }
            .filter { it.order % ORDER_SCALE == LINE_CANDIDATE_ORDER_OFFSET }
            .filter { source.rect.contains(it.bounds.toAndroidRect()) }
            .sortedBy { it.order }
            .toList()
        for (candidate in questionLines) {
            accumulatedText.append(candidate.text)
            val normalizedAccumulated = QuizManager.normalizeQuestionText(
                accumulatedText.toString()
            )
            if (
                normalizedAccumulated.contains(normalizedPrompt) ||
                (
                    normalizedPrompt.length >= MIN_PROMPT_PREFIX_MATCH_LENGTH &&
                        normalizedPrompt.contains(normalizedAccumulated) &&
                        normalizedAccumulated.length * 2 >= normalizedPrompt.length
                    )
            ) {
                return candidate.order + ORDER_SCALE - LINE_CANDIDATE_ORDER_OFFSET - 1
            }
        }
        return questionLines
            .takeWhile { !OcrQuestionCandidateBuilder.isOptionLine(it.text) }
            .lastOrNull()
            ?.order
            ?.plus(ORDER_SCALE - LINE_CANDIDATE_ORDER_OFFSET - 1)
            ?: source.endOrder
    }

    private fun Rect.toLocatorBounds(): OcrOptionLocator.Bounds {
        return OcrOptionLocator.Bounds(left, top, right, bottom)
    }

    private fun OcrOptionLocator.Bounds.toAndroidRect(): Rect {
        return Rect(left, top, right, bottom)
    }

    private fun createLineCandidate(
        text: String,
        boundingBox: Rect,
        order: Int
    ): OcrOptionLocator.TextCandidate? {
        val rect = Rect(boundingBox)
        if (
            text.isBlank() ||
            rect.width() <= MIN_TEXT_RECT_SIZE ||
            rect.height() <= MIN_TEXT_RECT_SIZE ||
            rect.width() * rect.height() <= MIN_TEXT_RECT_AREA
        ) {
            return null
        }
        return OcrOptionLocator.TextCandidate(
            text = text,
            bounds = rect.toLocatorBounds(),
            order = order
        )
    }

    private fun buildRecognizedTextItems(
        results: OcrDocument,
        lineCandidates: MutableList<OcrOptionLocator.TextCandidate>
    ): List<RecognizedTextItem> {
        val recognizedTextItems = mutableListOf<RecognizedTextItem>()
        val crossBlockLines = mutableListOf<OcrQuestionCandidateBuilder.Line>()
        val lineReadingOrders = buildLineReadingOrders(results)
        results.textBlocks.forEachIndexed { blockIndex, textBlock ->
            val blockStartOrder = textBlock.lines.indices
                .minOfOrNull { lineIndex ->
                    lineReadingOrders.getValue(OcrReadingOrder.Key(blockIndex, lineIndex))
                }
                ?.times(ORDER_SCALE)
                ?: return@forEachIndexed
            val blockLines = mutableListOf<RecognizedLineItem>()
            textBlock.lines.forEachIndexed { lineIndex, line ->
                val lineOrder = lineReadingOrders.getValue(
                    OcrReadingOrder.Key(blockIndex, lineIndex)
                )
                val lineBaseOrder = lineOrder * ORDER_SCALE
                line.boundingBox?.let { boundingBox ->
                    val lineCandidate = createLineCandidate(
                        line.text,
                        boundingBox,
                        lineBaseOrder + LINE_CANDIDATE_ORDER_OFFSET
                    )
                    lineCandidate?.let { candidate ->
                        lineCandidates.add(candidate)
                        crossBlockLines.add(
                            OcrQuestionCandidateBuilder.Line(
                                text = candidate.text,
                                bounds = candidate.bounds,
                                order = lineBaseOrder,
                                blockIndex = blockIndex
                            )
                        )
                    }
                    val recognizedLine = createRecognizedLineItem(
                        line.text,
                        boundingBox,
                        lineBaseOrder
                    )
                    recognizedLine?.let(blockLines::add)
                    if (!shouldGroupRecognizedTextInBlocks) {
                        createRecognizedTextItem(
                            line.text,
                            boundingBox,
                            lineBaseOrder,
                            lineBaseOrder + ORDER_SCALE - 1
                        )
                            ?.let(recognizedTextItems::add)
                    }
                }
                line.elements.forEachIndexed { elementIndex, element ->
                    element.boundingBox?.let { boundingBox ->
                        createLineCandidate(
                            element.text,
                            boundingBox,
                            lineBaseOrder + elementIndex
                        )?.let(lineCandidates::add)
                    }
                }
            }
            addLineWindowRecognizedTextItems(
                recognizedTextItems,
                blockLines.sortedBy(RecognizedLineItem::order)
            )
            if (shouldGroupRecognizedTextInBlocks) {
                textBlock.boundingBox?.let { boundingBox ->
                    createRecognizedTextItem(
                        text = textBlock.text,
                        boundingBox = boundingBox,
                        startOrder = blockStartOrder,
                        endOrder = blockStartOrder - 1
                    )?.let(recognizedTextItems::add)
                }
            }
        }
        if (shouldMergeOcrTextAcrossBlocks) {
            OcrQuestionCandidateBuilder.build(crossBlockLines).forEach { candidate ->
                createRecognizedTextItem(
                    text = candidate.text,
                    boundingBox = candidate.bounds.toAndroidRect(),
                    startOrder = candidate.startOrder,
                    endOrder = candidate.endOrder + ORDER_SCALE - 1
                )?.let(recognizedTextItems::add)
            }
        }
        return recognizedTextItems
    }

    private fun buildLineReadingOrders(results: OcrDocument): Map<OcrReadingOrder.Key, Int> {
        var sourceOrder = 0
        val items = buildList {
            results.textBlocks.forEachIndexed { blockIndex, textBlock ->
                textBlock.lines.forEachIndexed { lineIndex, line ->
                    add(
                        OcrReadingOrder.Item(
                            key = OcrReadingOrder.Key(blockIndex, lineIndex),
                            bounds = line.boundingBox?.toLocatorBounds(),
                            sourceOrder = sourceOrder++
                        )
                    )
                }
            }
        }
        return OcrReadingOrder.assign(items)
    }

    private fun addLineWindowRecognizedTextItems(
        output: MutableList<RecognizedTextItem>,
        lines: List<RecognizedLineItem>
    ) {
        if (lines.size < MIN_LINE_WINDOW_SIZE) {
            return
        }
        lines.forEachIndexed { startIndex, startLine ->
            val combinedText = StringBuilder(startLine.text)
            val combinedRect = Rect(startLine.rect)
            val maxEndExclusive = minOf(lines.size, startIndex + MAX_LINE_WINDOW_SIZE)
            for (endIndex in startIndex + 1 until maxEndExclusive) {
                val endLine = lines[endIndex]
                combinedText.append('\n').append(endLine.text)
                combinedRect.union(endLine.rect)
                val text = combinedText.toString()
                if (
                    QuizManager.normalizeQuestionText(text).length <
                    MIN_WINDOW_NORMALIZED_TEXT_LENGTH
                ) {
                    continue
                }
                createRecognizedTextItem(
                    text = text,
                    boundingBox = combinedRect,
                    startOrder = startLine.order,
                    endOrder = endLine.order + ORDER_SCALE - 1
                )?.let(output::add)
            }
        }
    }

    private fun matchIdentity(item: QuizGraphicItem): String {
        return if (item.question.id != 0) {
            item.question.id.toString()
        } else {
            item.question.prompt
        }
    }

    private fun padAndClampRect(rect: Rect, imageWidth: Int, imageHeight: Int): Rect {
        val padded = Rect(rect)
        padded.inset(-DISPLAY_RECT_PADDING_PX, -DISPLAY_RECT_PADDING_PX)
        if (imageWidth > 0) {
            padded.left = padded.left.coerceIn(0, imageWidth)
            padded.right = padded.right.coerceIn(0, imageWidth)
        }
        if (imageHeight > 0) {
            padded.top = padded.top.coerceIn(0, imageHeight)
            padded.bottom = padded.bottom.coerceIn(0, imageHeight)
        }
        return padded
    }

    override fun stop() {
        matchEpoch.incrementAndGet()
        matchRequests.close()
        matchingJob.cancel()
        matchScope.cancel()
        super.stop()
        ocrEngine.close()
    }

    override fun detectInImage(image: InputImage): Task<OcrDocument> {
        return ocrEngine.recognize(image)
    }

    override fun detectInBitmap(bitmap: Bitmap): Task<OcrDocument> {
        return ocrEngine.recognize(bitmap)
    }

    override fun requiresBitmapInput(): Boolean {
        return ocrEngine.requiresBitmapInput
    }

    override fun onSuccess(results: OcrDocument, graphicOverlay: GraphicOverlay) {
        Log.d(TAG, "On-device text detection successful with ${ocrEngine.type.stableValue}")

        val quizSnapshot = quizzes.value ?: emptyList()
        val lineCandidates = mutableListOf<OcrOptionLocator.TextCandidate>()
        val recognizedTextItems = buildRecognizedTextItems(results, lineCandidates)
        addMatchesGraphic(graphicOverlay, displayedMatches)
//        Log.e("###", quizzes.value?.size.toString())

//        runIO {
//            Log.d(TAG, "Text is: " + text.text)

        val imageWidth = graphicOverlay.imageWidth
        val imageHeight = graphicOverlay.imageHeight
        matchRequests.trySend(
            MatchRequest(
                epoch = matchEpoch.get(),
                quizSnapshot = quizSnapshot,
                recognizedTextItems = recognizedTextItems,
                lineCandidates = lineCandidates,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                graphicOverlay = graphicOverlay
            )
        )
    }

    override fun onFailure(e: Exception) {
        matchEpoch.incrementAndGet()
        while (matchRequests.tryReceive().isSuccess) {
            // Drop queued frames; a currently running request is invalidated by the epoch above.
        }
        displayedMatches = emptyList()
        stableResultGate.reset()
        onMatchesDetected?.invoke(emptyList())
        Log.w(TAG, "Text detection failed.$e")
    }

    private suspend fun processMatchRequest(request: MatchRequest) {
        val quizIndex = getQuizIndex(request.quizSnapshot)
        val matchedQuizs: MutableList<MatchedTextItem> = ArrayList()
        for (item in request.recognizedTextItems) {
            coroutineContext.ensureActive()
            val matched = getMatchedQuizGraphicItem(item, quizIndex, request.lineCandidates)
            matched?.let(matchedQuizs::add)
        }

        val sortedMatches = buildDisplayMatches(
            matchedQuizs = matchedQuizs,
            lineCandidates = request.lineCandidates,
            imageWidth = request.imageWidth,
            imageHeight = request.imageHeight
        )

        withContext(Dispatchers.Main) {
            if (request.epoch != matchEpoch.get()) {
                return@withContext
            }
            val stableMatches = resolveStableMatches(sortedMatches)
            displayedMatches = stableMatches
            onMatchesDetected?.invoke(stableMatches)

            request.graphicOverlay.clear()
            addMatchesGraphic(request.graphicOverlay, stableMatches)
            request.graphicOverlay.postInvalidate()
        }
    }

    private fun resolveStableMatches(newMatches: List<QuizGraphicItem>): List<QuizGraphicItem> {
        return stableResultGate.resolve(newMatches, displayedMatches)
    }

    private fun buildStableMatchesFingerprint(matches: List<QuizGraphicItem>): String {
        return matches
            .sortedWith(
                compareBy<QuizGraphicItem> { it.rect.top }
                    .thenBy { it.rect.left }
                    .thenByDescending { it.distance }
            )
            .joinToString("|") { match ->
                val identity = match.question.id
                    .takeIf { it != 0 }
                    ?.toString()
                    ?: match.question.prompt
                val answers = match.answerRects.joinToString(",") { rect ->
                    rect.toStableRectKey()
                }
                "$identity:${match.rect.toStableRectKey()}:$answers:${match.isAnswerPartiallyMatched}"
            }
    }

    private fun Rect.toStableRectKey(): String {
        return listOf(left, top, right, bottom)
            .joinToString(",") { coordinate ->
                ((coordinate + STABLE_RECT_BUCKET_PX / 2) / STABLE_RECT_BUCKET_PX).toString()
            }
    }

    companion object {
        private const val TAG = "QuizRecProcessor"
        private const val MIN_TEXT_RECT_SIZE = 3
        private const val MIN_TEXT_RECT_AREA = 24
        private const val MIN_NORMALIZED_TEXT_LENGTH = 2
        private const val MIN_WINDOW_NORMALIZED_TEXT_LENGTH = 6
        private const val MIN_LINE_WINDOW_SIZE = 2
        private const val MAX_LINE_WINDOW_SIZE = 5
        private const val OPTION_RERANK_CANDIDATE_COUNT = 5
        private const val OPTION_CONTEXT_LINE_COUNT = 8
        private const val MAX_OPTION_SUPPORT_TEXTS = 12
        private const val MAX_OPTION_LINE_PARTS = 2
        private const val MIN_SHORT_OPTION_SUPPORT_LENGTH = 1
        private const val STRONG_QUESTION_MATCH_SCORE = 0.90
        private const val MIN_AMBIGUOUS_MATCH_MARGIN = 0.02
        private const val REQUIRED_STABLE_MATCH_FRAMES = 2
        private const val STABLE_RECT_BUCKET_PX = 12
        private const val DISPLAY_RECT_PADDING_PX = 4
        private const val MIN_PROMPT_PREFIX_MATCH_LENGTH = 6
        private const val ORDER_SCALE = 1_000
        private const val LINE_CANDIDATE_ORDER_OFFSET = 900

        private fun logExtrasForTesting(text: OcrDocument?) {
            if (text != null) {
                Log.v(MANUAL_TESTING_LOG, "Detected text has : " + text.textBlocks.size + " blocks")
                for (i in text.textBlocks.indices) {
                    val lines = text.textBlocks[i].lines
                    Log.v(
                        MANUAL_TESTING_LOG,
                        String.format("Detected text block %d has %d lines", i, lines.size)
                    )
                    for (j in lines.indices) {
                        val elements = lines[j].elements
                        Log.v(
                            MANUAL_TESTING_LOG,
                            String.format("Detected text line %d has %d elements", j, elements.size)
                        )
                        for (k in elements.indices) {
                            val element = elements[k]
                            Log.v(
                                MANUAL_TESTING_LOG,
                                String.format("Detected text element %d says: %s", k, element.text)
                            )
                            Log.v(
                                MANUAL_TESTING_LOG, String.format(
                                    "Detected text element %d has a bounding box: %s",
                                    k,
                                    element.boundingBox?.flattenToString()
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

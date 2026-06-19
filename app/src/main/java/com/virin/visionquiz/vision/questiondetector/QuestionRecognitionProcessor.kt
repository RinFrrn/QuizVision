package com.virin.visionquiz.vision.questiondetector

import android.content.Context
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
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList

class QuizRecognitionProcessor(
    private val context: Context,
    private val quizzes: LiveData<List<Quiz>>,
    private val onMatchesDetected: ((List<QuizGraphicItem>) -> Unit)? = null,
    private val minMatchScore: Double = QuizManager.DEFAULT_MIN_MATCH_SCORE,
    private val locateScreenAnswerRects: Boolean = false
) : VisionProcessorBase<Text>(context) {

    private val textRecognizer: TextRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
    private val shouldGroupRecognizedTextInBlocks: Boolean =
        PreferenceUtils.shouldGroupRecognizedTextInBlocks(context)
    private val showConfidence: Boolean = PreferenceUtils.shouldShowTextConfidence(context)
    private val useBriefAnswerDisplay: Boolean = PreferenceUtils.shouldUseBriefAnswerDisplay(context)
    private val overlayTextSizeSp: Float = PreferenceUtils.getQuizOverlayTextSizeSp(context)
    private val matchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val matchGeneration = AtomicInteger()
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

    private data class StableMatchCandidate(
        val fingerprint: String,
        val matches: List<QuizGraphicItem>,
        val count: Int
    )

    private var stableCandidate: StableMatchCandidate? = null

    private fun createRecognizedTextItem(
        text: String,
        boundingBox: Rect,
        startOrder: Int,
        endOrder: Int = startOrder
    ): RecognizedTextItem? {
        val rect = Rect(boundingBox)
        if (!isValidRecognizedTextItem(text, rect)) {
            return null
        }
        return RecognizedTextItem(text, rect, startOrder, endOrder)
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
        val rect = Rect(boundingBox)
        if (!isValidRecognizedTextItem(text, rect)) {
            return null
        }
        return RecognizedLineItem(text, rect, order)
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
        val nearbyOptionTexts = collectNearbyOptionTexts(source, lineCandidates)
        val rankedMatches = matches
            .map { match ->
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
        lineCandidates: List<OcrOptionLocator.TextCandidate>
    ): List<String> {
        val maxOrder = source.endOrder + OPTION_CONTEXT_LINE_COUNT * ORDER_SCALE
        return lineCandidates
            .asSequence()
            .filter { it.order % ORDER_SCALE == LINE_CANDIDATE_ORDER_OFFSET }
            .filter { it.order > source.endOrder }
            .filter { it.order <= maxOrder }
            .filter { it.bounds.top >= source.rect.top }
            .map { it.text }
            .filter { AnswerOptionTextMatcher.normalizeOptionText(it).length >= MIN_OPTION_SUPPORT_LENGTH }
            .take(MAX_OPTION_SUPPORT_TEXTS)
            .toList()
    }

    private fun computeOptionSupportScore(
        quiz: Quiz,
        nearbyOptionTexts: List<String>
    ): Double {
        var matchedOptionCount = 0
        quiz.options.forEach { option ->
            val normalizedOption = AnswerOptionTextMatcher.normalizeOptionText(option)
            if (normalizedOption.length < MIN_OPTION_SUPPORT_LENGTH) {
                return@forEach
            }
            val hasMatch = nearbyOptionTexts.any { candidate ->
                AnswerOptionTextMatcher.candidateScore(
                    candidate,
                    normalizedOption,
                    minMatchScore
                ) != null
            }
            if (hasMatch) {
                matchedOptionCount++
            }
        }
        return when {
            matchedOptionCount >= 3 -> 0.10
            matchedOptionCount == 2 -> 0.07
            matchedOptionCount == 1 -> 0.03
            else -> 0.0
        }
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
                items.maxByOrNull { it.item.distance }
            }
        val matchesByReadingOrder = bestMatches.sortedWith(
            compareBy<MatchedTextItem> { it.source.startOrder }
                .thenBy { it.source.rect.top }
                .thenBy { it.source.rect.left }
        )

        return matchesByReadingOrder.mapIndexed { index, match ->
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
            .sortedByDescending { it.distance }
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
        if (match.source.endOrder >= match.source.startOrder) {
            return match.source.endOrder
        }
        val normalizedPrompt = QuizManager.normalizeQuestionText(match.item.question.prompt)
        if (normalizedPrompt.isBlank()) {
            return match.source.endOrder
        }
        val accumulatedText = StringBuilder()
        val questionLines = lineCandidates
            .asSequence()
            .filter { it.order >= match.source.startOrder }
            .filter { it.order % ORDER_SCALE == LINE_CANDIDATE_ORDER_OFFSET }
            .filter { match.source.rect.contains(it.bounds.toAndroidRect()) }
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
        return match.source.endOrder
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
        results: Text,
        lineCandidates: MutableList<OcrOptionLocator.TextCandidate>
    ): List<RecognizedTextItem> {
        val recognizedTextItems = mutableListOf<RecognizedTextItem>()
        var lineOrder = 0
        results.textBlocks.forEach { textBlock ->
            val blockStartOrder = lineOrder * ORDER_SCALE
            val blockLines = mutableListOf<RecognizedLineItem>()
            textBlock.lines.forEach { line ->
                val lineBaseOrder = lineOrder * ORDER_SCALE
                line.boundingBox?.let { boundingBox ->
                    createLineCandidate(
                        line.text,
                        boundingBox,
                        lineBaseOrder + LINE_CANDIDATE_ORDER_OFFSET
                    )
                        ?.let(lineCandidates::add)
                    createRecognizedLineItem(
                        line.text,
                        boundingBox,
                        lineBaseOrder
                    )?.let(blockLines::add)
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
                lineOrder++
            }
            addLineWindowRecognizedTextItems(recognizedTextItems, blockLines)
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
        return recognizedTextItems
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
        matchScope.cancel()
        super.stop()
        textRecognizer.close()
    }

    override fun detectInImage(image: InputImage): Task<Text> {
        return textRecognizer.process(image)
    }


    override fun onSuccess(results: Text, graphicOverlay: GraphicOverlay) {
        Log.d(TAG, "On-device Text detection successful")

        val generation = matchGeneration.incrementAndGet()
        val quizSnapshot = quizzes.value ?: emptyList()
        val lineCandidates = mutableListOf<OcrOptionLocator.TextCandidate>()
        val recognizedTextItems = buildRecognizedTextItems(results, lineCandidates)
        addMatchesGraphic(graphicOverlay, displayedMatches)
//        Log.e("###", quizzes.value?.size.toString())

//        runIO {
//            Log.d(TAG, "Text is: " + text.text)

        val imageWidth = graphicOverlay.imageWidth
        val imageHeight = graphicOverlay.imageHeight
        matchScope.launch {
            val quizIndex = getQuizIndex(quizSnapshot)
            val matchedQuizs: MutableList<MatchedTextItem> = ArrayList()
            for (item in recognizedTextItems) {
                val matched = getMatchedQuizGraphicItem(item, quizIndex, lineCandidates)
                matched?.let { matchedQuizs.add(it) }
            }

            val sortedMatches = buildDisplayMatches(
                matchedQuizs = matchedQuizs,
                lineCandidates = lineCandidates,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )

            withContext(Dispatchers.Main) {
                if (generation != matchGeneration.get()) {
                    return@withContext
                }
                val stableMatches = resolveStableMatches(sortedMatches)
                displayedMatches = stableMatches
                onMatchesDetected?.invoke(stableMatches)
//            logExtrasForTesting(text)

                graphicOverlay.clear()
                addMatchesGraphic(graphicOverlay, stableMatches)
                graphicOverlay.postInvalidate()
            }
        }
    }

    override fun onFailure(e: Exception) {
        displayedMatches = emptyList()
        stableCandidate = null
        onMatchesDetected?.invoke(emptyList())
        Log.w(TAG, "Text detection failed.$e")
    }

    private fun resolveStableMatches(newMatches: List<QuizGraphicItem>): List<QuizGraphicItem> {
        val fingerprint = buildStableMatchesFingerprint(newMatches)
        val previousCandidate = stableCandidate
        val count = if (previousCandidate?.fingerprint == fingerprint) {
            previousCandidate.count + 1
        } else {
            1
        }
        stableCandidate = StableMatchCandidate(fingerprint, newMatches, count)

        if (newMatches.isEmpty()) {
            return if (displayedMatches.isEmpty() || count >= REQUIRED_STABLE_MATCH_FRAMES) {
                newMatches
            } else {
                displayedMatches
            }
        }
        if (displayedMatches.isEmpty() || count >= REQUIRED_STABLE_MATCH_FRAMES) {
            return newMatches
        }
        return displayedMatches
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
        private const val MIN_OPTION_SUPPORT_LENGTH = 5
        private const val STRONG_QUESTION_MATCH_SCORE = 0.90
        private const val MIN_AMBIGUOUS_MATCH_MARGIN = 0.02
        private const val REQUIRED_STABLE_MATCH_FRAMES = 2
        private const val STABLE_RECT_BUCKET_PX = 12
        private const val DISPLAY_RECT_PADDING_PX = 4
        private const val MIN_PROMPT_PREFIX_MATCH_LENGTH = 6
        private const val ORDER_SCALE = 1_000
        private const val LINE_CANDIDATE_ORDER_OFFSET = 900

        private fun logExtrasForTesting(text: Text?) {
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
                                    element.boundingBox!!.flattenToString()
                                )
                            )
                            Log.v(
                                MANUAL_TESTING_LOG, String.format(
                                    "Expected corner point size is 4, get %d",
                                    element.cornerPoints!!.size
                                )
                            )
                            for (point in element.cornerPoints!!) {
                                Log.v(
                                    MANUAL_TESTING_LOG, String.format(
                                        "Corner point for element %d is located at: x - %d, y = %d",
                                        k,
                                        point.x,
                                        point.y
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

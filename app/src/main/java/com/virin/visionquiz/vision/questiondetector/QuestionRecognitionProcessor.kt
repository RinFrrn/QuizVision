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

    private data class MatchedTextItem(
        val item: QuizGraphicItem,
        val source: RecognizedTextItem
    )

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

    private fun getMatchedQuizGraphicItem(
        recognizedTextItem: RecognizedTextItem,
        quizIndex: QuizManager.QuizMatchIndex
    ): MatchedTextItem? {
        val bestMatch = QuizManager.matchQuiz(
            recognizedTextItem.text,
            quizIndex,
            minScore = minMatchScore
        ).firstOrNull()
        if (bestMatch != null) {
            return MatchedTextItem(
                item = QuizGraphicItem(
                    bestMatch.first,
                    bestMatch.second,
                    recognizedTextItem.rect
                ),
                source = recognizedTextItem
            )
        }
        return null
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
                }
            )
        }
            .sortedByDescending { it.distance }
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
            textBlock.lines.forEach { line ->
                val lineBaseOrder = lineOrder * ORDER_SCALE
                line.boundingBox?.let { boundingBox ->
                    createLineCandidate(
                        line.text,
                        boundingBox,
                        lineBaseOrder + LINE_CANDIDATE_ORDER_OFFSET
                    )
                        ?.let(lineCandidates::add)
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
                val matched = getMatchedQuizGraphicItem(item, quizIndex)
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
                displayedMatches = sortedMatches
                onMatchesDetected?.invoke(sortedMatches)
//            logExtrasForTesting(text)

                graphicOverlay.clear()
                addMatchesGraphic(graphicOverlay, sortedMatches)
                graphicOverlay.postInvalidate()
            }
        }
    }

    override fun onFailure(e: Exception) {
        displayedMatches = emptyList()
        onMatchesDetected?.invoke(emptyList())
        Log.w(TAG, "Text detection failed.$e")
    }

    companion object {
        private const val TAG = "QuizRecProcessor"
        private const val MIN_TEXT_RECT_SIZE = 3
        private const val MIN_TEXT_RECT_AREA = 24
        private const val MIN_NORMALIZED_TEXT_LENGTH = 2
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

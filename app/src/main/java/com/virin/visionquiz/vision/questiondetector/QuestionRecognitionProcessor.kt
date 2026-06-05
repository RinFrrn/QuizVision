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
    private val minMatchScore: Double = QuizManager.DEFAULT_MIN_MATCH_SCORE
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
        val rect: Rect
    )

    private fun createRecognizedTextItem(
        text: String,
        boundingBox: Rect
    ): RecognizedTextItem? {
        val rect = Rect(boundingBox)
        if (!isValidRecognizedTextItem(text, rect)) {
            return null
        }
        return RecognizedTextItem(text, rect)
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
        text: String,
        textRect: Rect,
        quizIndex: QuizManager.QuizMatchIndex
    ): QuizGraphicItem? {
        val bestMatch = QuizManager.matchQuiz(
            text,
            quizIndex,
            minScore = minMatchScore
        ).firstOrNull()
        if (bestMatch != null) {
            return QuizGraphicItem(
                bestMatch.first, bestMatch.second, textRect
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
        matchedQuizs: List<QuizGraphicItem>,
        imageWidth: Int,
        imageHeight: Int
    ): List<QuizGraphicItem> {
        if (matchedQuizs.isEmpty()) {
            return emptyList()
        }

        return matchedQuizs.groupBy(::matchIdentity)
            .mapNotNull { (_, items) ->
                val best = items.maxByOrNull { it.distance } ?: return@mapNotNull null
                QuizGraphicItem(
                    best.question,
                    best.distance,
                    padAndClampRect(best.rect, imageWidth, imageHeight)
                )
            }
            .sortedByDescending { it.distance }
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
        val recognizedTextItems: MutableList<RecognizedTextItem> = ArrayList()
        addMatchesGraphic(graphicOverlay, displayedMatches)
//        Log.e("###", quizzes.value?.size.toString())

//        runIO {
//            Log.d(TAG, "Text is: " + text.text)

        for (textBlock in results.textBlocks) {
//            println("###  textBlock ${textBlock.boundingBox}")

            if (shouldGroupRecognizedTextInBlocks) {
                textBlock.boundingBox?.let { boundingBox ->
                    createRecognizedTextItem(textBlock.text, boundingBox)?.let(recognizedTextItems::add)
//                    matched?.let {
//                        val newQus = it.question.copy(prompt = "$boundingBox")
//                        matchedQuizs.add(it.copy(question = newQus))
//                    }
                }
            } else {
                for (line in textBlock.lines) {
                    line.boundingBox?.let { boundingBox ->
                        createRecognizedTextItem(line.text, boundingBox)?.let(recognizedTextItems::add)
                    }
                }
            }
        }

        val imageWidth = graphicOverlay.imageWidth
        val imageHeight = graphicOverlay.imageHeight
        matchScope.launch {
            val quizIndex = getQuizIndex(quizSnapshot)
            val matchedQuizs: MutableList<QuizGraphicItem> = ArrayList()
            for (item in recognizedTextItems) {
                val matched = getMatchedQuizGraphicItem(item.text, item.rect, quizIndex)
                matched?.let { matchedQuizs.add(it) }
            }

            val sortedMatches = buildDisplayMatches(
                matchedQuizs = matchedQuizs,
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

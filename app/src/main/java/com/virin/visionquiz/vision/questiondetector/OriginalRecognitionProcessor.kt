package com.virin.visionquiz.vision.questiondetector

import android.content.Context
import android.graphics.Rect
import android.util.Log
import androidx.lifecycle.LiveData
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizManager
import com.virin.visionquiz.dao.QuizUiType
import com.virin.visionquiz.preference.PreferenceUtils
import com.virin.visionquiz.util.QuizGraphicItem
import com.virin.visionquiz.util.runMain
import com.virin.visionquiz.vision.VisionProcessorBase
import com.virin.visionquiz.vision.graphic.GraphicOverlay
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

class OriginalRecognitionProcessor(
    private val context: Context,
    private val onMatchesDetected: ((List<QuizGraphicItem>) -> Unit)? = null
) : VisionProcessorBase<Text>(context) {

    private val textRecognizer: TextRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
    private val shouldGroupRecognizedTextInBlocks: Boolean =
        PreferenceUtils.shouldGroupRecognizedTextInBlocks(context)
    private val showConfidence: Boolean = PreferenceUtils.shouldShowTextConfidence(context)
    private val useBriefAnswerDisplay: Boolean = PreferenceUtils.shouldUseBriefAnswerDisplay(context)
    private val overlayTextSizeSp: Float = PreferenceUtils.getQuizOverlayTextSizeSp(context)

    private fun getMatchedQuizGraphicItem(text: String, textRect: Rect): QuizGraphicItem? {
        // debug return raw text
        return QuizGraphicItem(
            Quiz(
                prompt = text,
                options = listOf(text),
                answer = setOf(0),
                isMultipleChoice = false,
                questionType = QuizUiType.SUBJECTIVE.label,
                libraryId = 0
            ),
            1.0, textRect
        )
    }

    override fun stop() {
        super.stop()
        textRecognizer.close()
    }

    override fun detectInImage(image: InputImage): Task<Text> {
        return textRecognizer.process(image)
    }


    override fun onSuccess(results: Text, graphicOverlay: GraphicOverlay) {
        Log.d(TAG, "On-device Text detection successful")


        val matchedQuizs: MutableList<QuizGraphicItem> = ArrayList()
//        Log.e("###", quizzes.value?.size.toString())

//        runIO {
//            Log.d(TAG, "Text is: " + text.text)

        val defaultRect = Rect(0, 0, 300, 100)

        for (textBlock in results.textBlocks) {
            if (shouldGroupRecognizedTextInBlocks) {
                val matched =
                    getMatchedQuizGraphicItem(textBlock.text, textBlock.boundingBox ?: defaultRect)

                matched?.let { matchedQuizs.add(it) }
            } else {
                for (line in textBlock.lines) {

                    val matched = getMatchedQuizGraphicItem(
                        line.text, line.boundingBox ?: defaultRect
                    )

                    matched?.let { matchedQuizs.add(it) }
                }
            }
        }

        onMatchesDetected?.invoke(matchedQuizs)

        runMain {
//            logExtrasForTesting(text)

            graphicOverlay.add(
                QuizGraphic(
                    graphicOverlay,
                    matchedQuizs,
                    shouldGroupRecognizedTextInBlocks,
                    showConfidence,
                    useBriefAnswerDisplay,
                    overlayTextSizeSp
                )
            )
        }
//        }
    }

    override fun onFailure(e: Exception) {
        onMatchesDetected?.invoke(emptyList())
        Log.w(TAG, "Text detection failed.$e")
    }

    companion object {
        private const val TAG = "QuizRecProcessor"

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

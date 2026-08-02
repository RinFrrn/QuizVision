package com.virin.visionquiz.vision.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

class MlKitOcrEngine private constructor(
    private val recognizer: TextRecognizer
) : OcrEngine {

    constructor() : this(
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    )

    override val type: OcrEngineType = OcrEngineType.ML_KIT
    override val requiresBitmapInput: Boolean = false

    private val isClosed = AtomicBoolean(false)

    override fun recognize(image: InputImage): Task<OcrDocument> {
        if (isClosed.get()) {
            return closedTask()
        }
        return recognizer.process(image).continueWith { task ->
            if (!task.isSuccessful) {
                throw task.exception ?: IllegalStateException("ML Kit OCR did not complete")
            }
            task.result.toOcrDocument()
        }
    }

    override fun recognize(bitmap: Bitmap): Task<OcrDocument> {
        if (isClosed.get()) {
            return closedTask()
        }
        return recognize(InputImage.fromBitmap(bitmap, 0))
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            recognizer.close()
        }
    }

    private fun closedTask(): Task<OcrDocument> {
        return Tasks.forException(IllegalStateException("ML Kit OCR engine is closed"))
    }
}

private fun Text.toOcrDocument(): OcrDocument {
    return OcrDocument(
        text = text,
        textBlocks = textBlocks.map { block ->
            OcrTextBlock(
                text = block.text,
                boundingBox = block.boundingBox.copyOrNull(),
                lines = block.lines.map { line ->
                    OcrTextLine(
                        text = line.text,
                        boundingBox = line.boundingBox.copyOrNull(),
                        elements = line.elements.map { element ->
                            OcrTextElement(
                                text = element.text,
                                boundingBox = element.boundingBox.copyOrNull(),
                                confidence = element.confidence.asOcrConfidence(),
                                recognizedLanguage = element.recognizedLanguage.nonBlankOrNull()
                            )
                        },
                        confidence = line.confidence.asOcrConfidence(),
                        recognizedLanguage = line.recognizedLanguage.nonBlankOrNull()
                    )
                },
                recognizedLanguage = block.recognizedLanguage.nonBlankOrNull()
            )
        }
    )
}

private fun Rect?.copyOrNull(): Rect? = this?.let(::Rect)

private fun Float.asOcrConfidence(): Float? {
    return takeIf { it.isFinite() && it >= 0f }?.coerceIn(0f, 1f)
}

private fun String?.nonBlankOrNull(): String? = this?.takeIf(String::isNotBlank)

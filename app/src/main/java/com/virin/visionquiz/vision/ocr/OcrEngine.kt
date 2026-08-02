package com.virin.visionquiz.vision.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage

/** A replaceable on-device OCR implementation. */
interface OcrEngine : AutoCloseable {

    val type: OcrEngineType

    /**
     * Whether this engine needs an upright [Bitmap] instead of the source-native [InputImage].
     *
     * Frame processors should inspect this flag before converting camera YUV data. This keeps the
     * ML Kit path free from an unnecessary and lossy YUV-to-bitmap conversion.
     */
    val requiresBitmapInput: Boolean

    fun recognize(image: InputImage): Task<OcrDocument>

    fun recognize(bitmap: Bitmap): Task<OcrDocument>

    override fun close()
}

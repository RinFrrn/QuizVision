package com.virin.visionquiz.vision.ocr

import android.content.Context

object OcrEngineFactory {

    @JvmStatic
    fun create(context: Context, type: OcrEngineType): OcrEngine {
        return when (type) {
            OcrEngineType.ML_KIT -> MlKitOcrEngine()
            OcrEngineType.PADDLE_OCR_V6_SMALL -> PaddleOcrEngine(context)
        }
    }
}

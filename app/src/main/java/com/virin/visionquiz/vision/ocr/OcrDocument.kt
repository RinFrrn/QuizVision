package com.virin.visionquiz.vision.ocr

import android.graphics.Rect

/**
 * OCR output that is independent from a concrete recognition SDK.
 *
 * Coordinates are expressed in pixels of the upright input image. Every [Rect] is copied while
 * adapting an SDK result so callers cannot accidentally mutate an SDK-owned rectangle.
 */
data class OcrDocument(
    val text: String,
    val textBlocks: List<OcrTextBlock>
)

data class OcrTextBlock(
    val text: String,
    val boundingBox: Rect?,
    val lines: List<OcrTextLine>,
    val recognizedLanguage: String? = null
)

data class OcrTextLine(
    val text: String,
    val boundingBox: Rect?,
    val elements: List<OcrTextElement>,
    val confidence: Float? = null,
    val recognizedLanguage: String? = null
)

data class OcrTextElement(
    val text: String,
    val boundingBox: Rect?,
    val confidence: Float? = null,
    val recognizedLanguage: String? = null
)

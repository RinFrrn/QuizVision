package com.virin.visionquiz.vision.ocr

/** OCR engines exposed through the persisted search setting. */
enum class OcrEngineType(val stableValue: String) {
    ML_KIT("ml_kit"),
    PADDLE_OCR_V6_SMALL("pp_ocr_v6_small");

    companion object {
        val DEFAULT: OcrEngineType = ML_KIT

        /** Unknown or migrated values deliberately fall back to the established ML Kit engine. */
        @JvmStatic
        fun fromStableValue(value: String?): OcrEngineType {
            val normalizedValue = value?.trim()?.lowercase()
            return entries.firstOrNull { it.stableValue == normalizedValue } ?: DEFAULT
        }
    }
}

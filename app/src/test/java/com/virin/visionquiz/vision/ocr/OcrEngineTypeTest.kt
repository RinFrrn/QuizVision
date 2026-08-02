package com.virin.visionquiz.vision.ocr

import com.virin.visionquiz.preference.PreferenceUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrEngineTypeTest {

    @Test
    fun persistedValuesStayAlignedWithPreferenceConstants() {
        assertEquals(PreferenceUtils.OCR_ENGINE_ML_KIT, OcrEngineType.ML_KIT.stableValue)
        assertEquals(
            PreferenceUtils.OCR_ENGINE_PP_OCR_V6_SMALL,
            OcrEngineType.PADDLE_OCR_V6_SMALL.stableValue
        )
    }

    @Test
    fun parsesStableValues() {
        assertEquals(
            OcrEngineType.ML_KIT,
            OcrEngineType.fromStableValue("ml_kit")
        )
        assertEquals(
            OcrEngineType.PADDLE_OCR_V6_SMALL,
            OcrEngineType.fromStableValue("pp_ocr_v6_small")
        )
    }

    @Test
    fun parsingIsWhitespaceAndCaseTolerant() {
        assertEquals(
            OcrEngineType.PADDLE_OCR_V6_SMALL,
            OcrEngineType.fromStableValue("  PP_OCR_V6_SMALL  ")
        )
    }

    @Test
    fun unknownOrMissingValuesFallBackToMlKit() {
        assertEquals(OcrEngineType.ML_KIT, OcrEngineType.fromStableValue(null))
        assertEquals(OcrEngineType.ML_KIT, OcrEngineType.fromStableValue(""))
        assertEquals(OcrEngineType.ML_KIT, OcrEngineType.fromStableValue("removed_engine"))
    }
}

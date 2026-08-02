package com.virin.visionquiz.preference

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceUtilsTest {

    @Test
    fun supportedOcrEngineValuesArePreserved() {
        assertEquals(
            PreferenceUtils.OCR_ENGINE_ML_KIT,
            PreferenceUtils.normalizeOcrEngineValue(PreferenceUtils.OCR_ENGINE_ML_KIT)
        )
        assertEquals(
            PreferenceUtils.OCR_ENGINE_PP_OCR_V6_SMALL,
            PreferenceUtils.normalizeOcrEngineValue(PreferenceUtils.OCR_ENGINE_PP_OCR_V6_SMALL)
        )
    }

    @Test
    fun missingOrUnknownOcrEngineFallsBackToMlKit() {
        assertEquals(
            PreferenceUtils.OCR_ENGINE_ML_KIT,
            PreferenceUtils.normalizeOcrEngineValue(null)
        )
        assertEquals(
            PreferenceUtils.OCR_ENGINE_ML_KIT,
            PreferenceUtils.normalizeOcrEngineValue("unsupported_engine")
        )
    }
}

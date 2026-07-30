package com.virin.visionquiz.cram

object CramCacheType {
    const val LOCAL_ANALYSIS = "cram_local_analysis_v1"
    const val MODULE_ANALYSIS = "cram_module_analysis_v1"
    const val FINAL_REPORT = "cram_final_report_v1"
}

object CramCacheSubKey {
    const val MAIN = "main"
}

/**
 * A final report is specific to the local study plan that was used to build it.
 *
 * Keeping that identity in [LibraryInsightCache.subKey] lets several reversible
 * daily-duration plans coexist without changing the database schema.
 */
internal fun finalReportCacheSubKey(localFingerprint: String): String {
    return "plan-v1:$localFingerprint"
}

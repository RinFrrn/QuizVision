package com.virin.visionquiz.cram

import android.content.Context

/**
 * Single source of truth for per-library, per-destination AI data-sharing
 * consent. Both the dashboard and the background service must validate it.
 */
internal object CramAiConsentStore {
    const val PREFERENCES_NAME = "cram_dashboard_settings"

    fun grantedSignature(context: Context, libraryId: Int): String? {
        if (libraryId <= 0) return null
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(key(libraryId), null)
    }

    fun grant(context: Context, libraryId: Int, signature: String) {
        require(libraryId > 0)
        require(signature.isNotBlank())
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key(libraryId), signature)
            .apply()
    }

    fun matches(context: Context, libraryId: Int, signature: String?): Boolean {
        return !signature.isNullOrBlank() &&
            grantedSignature(context, libraryId) == signature
    }

    private fun key(libraryId: Int): String {
        return "library_${libraryId}_ai_data_sharing_consent_destination_v2"
    }
}

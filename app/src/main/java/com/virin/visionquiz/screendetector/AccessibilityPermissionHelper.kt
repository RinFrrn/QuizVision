package com.virin.visionquiz.screendetector

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.virin.visionquiz.util.PermissionManager

object AccessibilityPermissionHelper {
    fun isServiceEnabled(context: Context): Boolean {
        if (QuizAccessibilityService.instance != null) {
            return true
        }
        val expectedComponent = ComponentName(
            context,
            QuizAccessibilityService::class.java
        ).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        for (enabledService in splitter) {
            if (enabledService.equals(expectedComponent, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun hasShownIntro(context: Context): Boolean {
        return context
            .getSharedPreferences(PermissionManager.PERMISSION_STATE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(PermissionManager.KEY_ACCESSIBILITY_SEARCH_INTRO_SHOWN, false)
    }

    fun markIntroShown(context: Context) {
        context
            .getSharedPreferences(PermissionManager.PERMISSION_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PermissionManager.KEY_ACCESSIBILITY_SEARCH_INTRO_SHOWN, true)
            .apply()
    }
}

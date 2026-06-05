package com.virin.visionquiz.util

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager

@Deprecated("Use XML property directly.")
object BarsUtil {
    fun transparentStatusBar(activity: Activity) {
        transparentStatusBar(activity.window)
    }

    fun transparentStatusBar(window: Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            val option: Int =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            val vis: Int = window.getDecorView().getSystemUiVisibility()
            window.getDecorView().setSystemUiVisibility(option or vis)
            window.setStatusBarColor(Color.TRANSPARENT)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        }
    }

    fun transparentNavBar(activity: Activity) {
        transparentNavBar(activity.window)
    }

    fun transparentNavBar(window: Window) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.navigationBarColor = Color.TRANSPARENT
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            if (window.attributes.flags and WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION == 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            }
        }
        val decorView = window.decorView
        val vis = decorView.systemUiVisibility
        val option =
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        decorView.systemUiVisibility = vis or option
    }
}
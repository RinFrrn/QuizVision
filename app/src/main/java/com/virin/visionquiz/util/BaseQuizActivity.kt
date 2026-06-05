package com.virin.visionquiz.util

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

open class BaseQuizActivity : AppCompatActivity() {

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

//        EdgeToEdgeUtils.applyEdgeToEdge(window, true, resources.getColor(android.R.color.transparent, theme), resources.getColor(android.R.color.transparent, theme))
    }
}

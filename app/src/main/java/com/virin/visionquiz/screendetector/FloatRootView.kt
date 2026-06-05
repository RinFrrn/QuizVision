package com.virin.visionquiz.screendetector

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.google.android.material.button.MaterialButton

class FloatRootView(context: Context) : View(context) {

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        println("onTouchEvent")
        return false
//        return super.onTouchEvent(event)
    }
}
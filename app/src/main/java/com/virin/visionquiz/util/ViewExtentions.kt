package com.virin.visionquiz.util

import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat.getSystemService
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

//fun TextInputLayout.clearFocusAndHideKeyboard() {
//    // 取消TextInputLayout的焦点
//    this.clearFocus()
//    // 隐藏键盘
//    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
//    imm.hideSoftInputFromWindow(this.windowToken, 0)
//}

fun View.clearFocusWhenScrollBegin(recyclerView: RecyclerView) {
    recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            super.onScrollStateChanged(recyclerView, newState)
            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                if (this@clearFocusWhenScrollBegin.hasFocus()) {
                    // 取消焦点
                    this@clearFocusWhenScrollBegin.clearFocus()
                }
            }
        }
    })
}

fun TextInputEditText.hideKeyboardWhenFocusCleared(context: Context) {
    val focusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
        if (!hasFocus) {
            val imm = context.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
    this.onFocusChangeListener = focusChangeListener
}
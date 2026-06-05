package com.virin.visionquiz.util

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virin.visionquiz.R

fun showProgressAlertDialog(context: Context, title: CharSequence? = null): AlertDialog {
    // 创建一个线性布局
    val layout = LinearLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        setPadding(32.dp, 24.dp, 32.dp, 24.dp)
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }

    // 创建一个进度指示器
    val progressBar = ProgressBar(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    val textView = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        text = title
        setPadding(16.dp, 0, 0, 0)
        setTextAppearance(R.style.TextAppearance_Material3_TitleLarge)
    }

    // 将进度指示器添加到布局
    layout.addView(progressBar)
    layout.addView(textView)

    // 创建 MaterialAlertDialogBuilder
    val alertDialog = MaterialAlertDialogBuilder(context)
        .setView(layout) // 将自定义布局设置为对话框的视图
        .setCancelable(false) // 设置为不可取消
        .create()

    alertDialog.show()
    return alertDialog
}
package com.virin.visionquiz.util

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.widget.TextView
import androidx.core.graphics.scaleMatrix
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.ShapeAppearanceModel

@Deprecated("较难优雅实现在不缩放本体的同时，缩放文字和绘制圆形")
class CircleBackgroundMaterialButton : MaterialButton {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )
//
//    //    private val circlePaint = Paint()
//    private var scaleAnim: ValueAnimator? = null
////    private var scaleValue = MIN_SCALE
//
//    init {
//        scaleX = MIN_SCALE
//        scaleY = MIN_SCALE
////        circlePaint.color = Color.BLUE
//
//        addOnCheckedChangeListener { button, isChecked ->
//            val text = button.as TextView
//                    scaleAnim?.cancel()
//            scaleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
//                addUpdateListener {
//                    val value: Float =
//                        if (isChecked) it.animatedValue as Float * (MAX_SCALE - MIN_SCALE) + MIN_SCALE
//                        else (1 - it.animatedValue as Float) * (MAX_SCALE - MIN_SCALE) + MIN_SCALE
////                    scaleX = value
//                    text.sc = value
////                    invalidate()
//                }
//                duration = 300
//                start()
//            }
//        }
//    }
//
////    override fun onDraw(canvas: Canvas) {
////        // 获取按钮的宽度、高度，计算出圆角半径
////        canvas.drawCircle(width.toFloat() / 2, height.toFloat() / 2, width.coerceAtMost(height).toFloat() / 2 * scaleValue, circlePaint)
////        super.onDraw(canvas)
////    }
//
//    companion object {
//        private const val MIN_SCALE = 0.8f
//        private const val MAX_SCALE = 1.0f
//    }
}
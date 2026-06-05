package com.virin.visionquiz.util

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.PopupMenu

@SuppressLint("ClickableViewAccessibility")
class LongPressPopupMenu(
    private val context: Context, private val view: View, private val menuRes: Int
) {
    private val popupMenu = CustomPopupMenu(context, view)

    private var onMenuItemClickListener: PopupMenu.OnMenuItemClickListener? = null

    private var onClickListener: View.OnClickListener? = null

    private var isLongPress = false

    private var isShowOnSingleTap = false //需要在单击时显示菜单，则将isShowOnSingleTap设置为`true

    init {
        popupMenu.menuInflater.inflate(
            menuRes, popupMenu.menu
        )
        view.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    if (isLongPress) {
                        isLongPress = false
                        popupMenu.dismiss()
                        val selectedItem = popupMenu.selectedItem
                        if (selectedItem != null && onMenuItemClickListener != null) {
                            onMenuItemClickListener!!.onMenuItemClick(selectedItem)
                        }
                        return@setOnTouchListener true
                    } else {
                        if (isShowOnSingleTap) {
                            popupMenu.show()
                            return@setOnTouchListener true
                        }
                        onClickListener?.onClick(view)
                        return@setOnTouchListener false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    isLongPress = false
                    popupMenu.dismiss()
                    return@setOnTouchListener false
                }
                else -> {
                    return@setOnTouchListener true
                }
            }
        }
    }

    private val gestureDetector =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isShowOnSingleTap) {
                    isLongPress = true
                    popupMenu.show()
                }
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (isShowOnSingleTap) {
                    popupMenu.show()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                if (e1 == null) return false
                if (!isLongPress) return false
                val menu = popupMenu.menu
                val itemHeight =
                    48.dp//context.resources.getDimensionPixelSize(R.dimen.menu_item_height)
                val scrollY = e1.y.minus(e2.y).toInt()
                val selectedItemIndex = (menu.size() - 1) - (scrollY / itemHeight)
                if (selectedItemIndex >= 0 && selectedItemIndex < menu.size()) {
                    popupMenu.setSelectedItem(menu.getItem(selectedItemIndex))
                }
                return true
            }
        })

    fun setOnMenuItemClickListener(listener: PopupMenu.OnMenuItemClickListener) {
        onMenuItemClickListener = listener
    }

    fun setOnClickListener(listener: View.OnClickListener) {
        onClickListener = listener
    }

    fun showOnSingleTap() {
        isShowOnSingleTap = true
    }

    fun showOnLongPress() {
        isShowOnSingleTap = false
    }
}

class CustomPopupMenu(context: Context, anchor: View) : PopupMenu(context, anchor) {

    var selectedItem: MenuItem? = null
        private set

    fun setSelectedItem(item: MenuItem?) {
        selectedItem?.isChecked = false
        item?.isChecked = true
        selectedItem = item
    }

}
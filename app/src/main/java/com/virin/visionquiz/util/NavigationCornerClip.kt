package com.virin.visionquiz.util

import android.graphics.Outline
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.virin.visionquiz.R

private const val FALLBACK_NAVIGATION_CORNER_RADIUS_DP = 24f

private data class NavigationCornerClipState(
    val outlineProvider: ViewOutlineProvider?,
    val clipToOutline: Boolean,
    var activeCount: Int = 1
)

fun View.enableNavigationCornerClip(sceneRoot: ViewGroup) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

    val existingState = getTag(R.id.tag_navigation_corner_clip_state) as? NavigationCornerClipState
    if (existingState != null) {
        existingState.activeCount += 1
    } else {
        setTag(
            R.id.tag_navigation_corner_clip_state,
            NavigationCornerClipState(outlineProvider, clipToOutline)
        )
    }

    val radius = navigationCornerRadius(sceneRoot)
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(
                0,
                0,
                maxOf(view.width, 1),
                maxOf(view.height, 1),
                radius
            )
        }
    }
    clipToOutline = true
    invalidateOutline()
}

fun View.clearNavigationCornerClip() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

    val state = getTag(R.id.tag_navigation_corner_clip_state) as? NavigationCornerClipState
        ?: return
    state.activeCount -= 1
    if (state.activeCount > 0) return

    outlineProvider = state.outlineProvider
    clipToOutline = state.clipToOutline
    setTag(R.id.tag_navigation_corner_clip_state, null)
    invalidateOutline()
}

private fun View.navigationCornerRadius(sceneRoot: ViewGroup): Float {
    val insets = rootWindowInsets ?: sceneRoot.rootWindowInsets
    val systemRadius = insets?.navigationRoundedCornerRadius()
    if (systemRadius != null && systemRadius > 0) {
        return systemRadius.toFloat()
    }
    return FALLBACK_NAVIGATION_CORNER_RADIUS_DP * resources.displayMetrics.density
}

private fun android.view.WindowInsets.navigationRoundedCornerRadius(): Int? {
    val positions = intArrayOf(
        RoundedCorner.POSITION_TOP_LEFT,
        RoundedCorner.POSITION_TOP_RIGHT,
        RoundedCorner.POSITION_BOTTOM_RIGHT,
        RoundedCorner.POSITION_BOTTOM_LEFT
    )
    var maxRadius = 0
    for (position in positions) {
        maxRadius = maxOf(maxRadius, getRoundedCorner(position)?.radius ?: 0)
    }
    return maxRadius.takeIf { it > 0 }
}

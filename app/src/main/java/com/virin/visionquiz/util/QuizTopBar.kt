package com.virin.visionquiz.util

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.virin.visionquiz.R

fun Fragment.configureQuizTopBar(
    toolbar: MaterialToolbar,
    title: CharSequence,
    showNavigation: Boolean = true,
    navigationIconRes: Int = R.drawable.round_arrow_back_24,
    applyStatusBarInset: Boolean = true,
    onNavigationClick: (() -> Unit)? = null
) {
    toolbar.applyQuizTopBarStyle(applyStatusBarInset)
    toolbar.title = title
    if (showNavigation) {
        toolbar.setNavigationIcon(navigationIconRes)
        toolbar.setNavigationOnClickListener {
            onNavigationClick?.invoke() ?: navigateBackFromTopBar()
        }
    } else {
        toolbar.navigationIcon = null
        toolbar.setNavigationOnClickListener(null)
    }
}

fun MaterialToolbar.applyQuizTopBarStyle(applyStatusBarInset: Boolean = true) {
    val surface = MaterialColors.getColor(this, R.attr.colorSurface)
    val onSurface = MaterialColors.getColor(this, R.attr.colorOnSurface)
    setBackgroundColor(surface)
    setTitleTextColor(onSurface)
    setNavigationIconTint(onSurface)
    overflowIcon?.setTint(onSurface)
    if (applyStatusBarInset) {
        applyStatusBarInsets()
    }
}

private data class QuizTopBarBaseMetrics(
    val height: Int,
    val paddingLeft: Int,
    val paddingTop: Int,
    val paddingRight: Int,
    val paddingBottom: Int
)

private fun MaterialToolbar.applyStatusBarInsets() {
    if (getTag(R.id.tag_quiz_top_bar_base_metrics) == null) {
        setTag(
            R.id.tag_quiz_top_bar_base_metrics,
            QuizTopBarBaseMetrics(
                height = layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                paddingLeft = paddingLeft,
                paddingTop = paddingTop,
                paddingRight = paddingRight,
                paddingBottom = paddingBottom
            )
        )
    }
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val toolbar = view as MaterialToolbar
        val metrics = toolbar.getTag(R.id.tag_quiz_top_bar_base_metrics) as QuizTopBarBaseMetrics
        val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        toolbar.setPadding(
            metrics.paddingLeft,
            metrics.paddingTop + statusBarTop,
            metrics.paddingRight,
            metrics.paddingBottom
        )
        toolbar.layoutParams?.let { layoutParams ->
            if (metrics.height > 0) {
                val targetHeight = metrics.height + statusBarTop
                if (layoutParams.height != targetHeight) {
                    layoutParams.height = targetHeight
                    toolbar.layoutParams = layoutParams
                }
            }
        }
        insets
    }
    if (isAttachedToWindow) {
        ViewCompat.requestApplyInsets(this)
    } else {
        doOnAttach { ViewCompat.requestApplyInsets(it) }
    }
}

fun View.applyCollapsingQuizTopBarInsets(
    collapsingToolbar: CollapsingToolbarLayout,
    toolbar: MaterialToolbar,
    statusBarScrim: View? = null,
    header: View? = null,
    onApplyInsets: ((WindowInsetsCompat) -> Unit)? = null
) {
    val baseHeaderPaddingTop = header?.paddingTop ?: 0
    val baseCollapsingMinHeight = collapsingToolbar.minimumHeight
    val baseToolbarTopMargin =
        (toolbar.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0

    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
        val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top

        val collapsingMinHeight = baseCollapsingMinHeight + statusBarTop
        if (collapsingToolbar.minimumHeight != collapsingMinHeight) {
            collapsingToolbar.minimumHeight = collapsingMinHeight
        }

        val toolbarLayoutParams = toolbar.layoutParams as? ViewGroup.MarginLayoutParams
        val toolbarTopMargin = baseToolbarTopMargin + statusBarTop
        if (toolbarLayoutParams != null && toolbarLayoutParams.topMargin != toolbarTopMargin) {
            toolbarLayoutParams.topMargin = toolbarTopMargin
            toolbar.layoutParams = toolbarLayoutParams
        }

        statusBarScrim?.let { scrim ->
            val layoutParams = scrim.layoutParams
            if (layoutParams.height != statusBarTop) {
                layoutParams.height = statusBarTop
                scrim.layoutParams = layoutParams
            }
        }

        header?.let { headerView ->
            val headerPaddingTop = baseHeaderPaddingTop + statusBarTop
            if (headerView.paddingTop != headerPaddingTop) {
                headerView.setPadding(
                    headerView.paddingLeft,
                    headerPaddingTop,
                    headerView.paddingRight,
                    headerView.paddingBottom
                )
            }
        }

        onApplyInsets?.invoke(insets)
        insets
    }

    if (isAttachedToWindow) {
        ViewCompat.requestApplyInsets(this)
    } else {
        doOnAttach { ViewCompat.requestApplyInsets(it) }
    }
}

fun Fragment.navigateBackFromTopBar() {
    NavigationBackAnimationSource.markNextPopFromNavigationButton()
    findNavController().popBackStack()
}

@SuppressLint("RestrictedApi")
fun Fragment.refreshQuizTopBarMenu(
    toolbar: MaterialToolbar,
    menuResId: Int,
    onPrepareMenu: (Menu) -> Unit = {},
    onMenuItemSelected: (MenuItem) -> Boolean
) {
    toolbar.menu.clear()
    toolbar.inflateMenu(menuResId)
    (toolbar.menu as? MenuBuilder)?.setOptionalIconsVisible(true)
    onPrepareMenu(toolbar.menu)
    val onSurface = MaterialColors.getColor(toolbar, R.attr.colorOnSurface)
    toolbar.overflowIcon?.setTint(onSurface)
    toolbar.setOnMenuItemClickListener { onMenuItemSelected(it) }
}

fun MaterialToolbar.tintQuizMenuItems(errorItemIds: Set<Int> = emptySet()) {
    val onSurfaceVariant = MaterialColors.getColor(this, R.attr.colorOnSurfaceVariant)
    val error = MaterialColors.getColor(this, R.attr.colorError)
    tintQuizMenu(menu, onSurfaceVariant, error, errorItemIds)
}

private fun tintQuizMenu(
    menu: Menu,
    onSurfaceVariant: Int,
    error: Int,
    errorItemIds: Set<Int>
) {
    for (index in 0 until menu.size()) {
        val item = menu.getItem(index)
        val color = if (item.itemId in errorItemIds) error else onSurfaceVariant
        item.iconTintList = ColorStateList.valueOf(color)
        item.title = SpannableString(item.title).apply {
            setSpan(ForegroundColorSpan(color), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (item.hasSubMenu()) {
            item.subMenu?.let { tintQuizMenu(it, onSurfaceVariant, error, errorItemIds) }
        }
    }
}

package com.virin.visionquiz.util

import android.content.Context
import android.util.TypedValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Reads Material3 color attributes from the current XML theme and applies them
 * to Compose's MaterialTheme. This bridges the XML Theme.Material3.DayNight
 * with its custom color values into Compose.
 */
@Composable
fun MdcThemeBridge(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    val colorScheme = if (isDark) darkColorScheme() else lightColorScheme()

    val resolved = colorScheme.copy(
        primary = context.resolveColor(com.google.android.material.R.attr.colorPrimary, colorScheme.primary),
        onPrimary = context.resolveColor(com.google.android.material.R.attr.colorOnPrimary, colorScheme.onPrimary),
        primaryContainer = context.resolveColor(com.google.android.material.R.attr.colorPrimaryContainer, colorScheme.primaryContainer),
        onPrimaryContainer = context.resolveColor(com.google.android.material.R.attr.colorOnPrimaryContainer, colorScheme.onPrimaryContainer),
        secondary = context.resolveColor(com.google.android.material.R.attr.colorSecondary, colorScheme.secondary),
        onSecondary = context.resolveColor(com.google.android.material.R.attr.colorOnSecondary, colorScheme.onSecondary),
        secondaryContainer = context.resolveColor(com.google.android.material.R.attr.colorSecondaryContainer, colorScheme.secondaryContainer),
        onSecondaryContainer = context.resolveColor(com.google.android.material.R.attr.colorOnSecondaryContainer, colorScheme.onSecondaryContainer),
        tertiary = context.resolveColor(com.google.android.material.R.attr.colorTertiary, colorScheme.tertiary),
        onTertiary = context.resolveColor(com.google.android.material.R.attr.colorOnTertiary, colorScheme.onTertiary),
        tertiaryContainer = context.resolveColor(com.google.android.material.R.attr.colorTertiaryContainer, colorScheme.tertiaryContainer),
        onTertiaryContainer = context.resolveColor(com.google.android.material.R.attr.colorOnTertiaryContainer, colorScheme.onTertiaryContainer),
        error = context.resolveColor(com.google.android.material.R.attr.colorError, colorScheme.error),
        onError = context.resolveColor(com.google.android.material.R.attr.colorOnError, colorScheme.onError),
        errorContainer = context.resolveColor(com.google.android.material.R.attr.colorErrorContainer, colorScheme.errorContainer),
        onErrorContainer = context.resolveColor(com.google.android.material.R.attr.colorOnErrorContainer, colorScheme.onErrorContainer),
        background = context.resolveColor(android.R.attr.colorBackground, colorScheme.background),
        onBackground = context.resolveColor(com.google.android.material.R.attr.colorOnBackground, colorScheme.onBackground),
        surface = context.resolveColor(com.google.android.material.R.attr.colorSurface, colorScheme.surface),
        onSurface = context.resolveColor(com.google.android.material.R.attr.colorOnSurface, colorScheme.onSurface),
        surfaceVariant = context.resolveColor(com.google.android.material.R.attr.colorSurfaceVariant, colorScheme.surfaceVariant),
        onSurfaceVariant = context.resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, colorScheme.onSurfaceVariant),
        outline = context.resolveColor(com.google.android.material.R.attr.colorOutline, colorScheme.outline),
        outlineVariant = context.resolveColor(com.google.android.material.R.attr.colorOutlineVariant, colorScheme.outlineVariant),
        surfaceContainerLowest = context.resolveColor(com.google.android.material.R.attr.colorSurfaceContainerLowest, colorScheme.surfaceContainerLowest),
        surfaceContainerLow = context.resolveColor(com.google.android.material.R.attr.colorSurfaceContainerLow, colorScheme.surfaceContainerLow),
        surfaceContainer = context.resolveColor(com.google.android.material.R.attr.colorSurfaceContainer, colorScheme.surfaceContainer),
        surfaceContainerHigh = context.resolveColor(com.google.android.material.R.attr.colorSurfaceContainerHigh, colorScheme.surfaceContainerHigh),
        surfaceContainerHighest = context.resolveColor(com.google.android.material.R.attr.colorSurfaceContainerHighest, colorScheme.surfaceContainerHighest),
        surfaceDim = context.resolveColor(com.google.android.material.R.attr.colorSurfaceDim, colorScheme.surfaceDim),
        surfaceBright = context.resolveColor(com.google.android.material.R.attr.colorSurfaceBright, colorScheme.surfaceBright),
        inverseSurface = context.resolveColor(com.google.android.material.R.attr.colorSurfaceInverse, colorScheme.inverseSurface),
        inverseOnSurface = context.resolveColor(com.google.android.material.R.attr.colorOnSurfaceInverse, colorScheme.inverseOnSurface),
        inversePrimary = context.resolveColor(com.google.android.material.R.attr.colorPrimaryInverse, colorScheme.inversePrimary),
    )

    MaterialTheme(
        colorScheme = resolved,
        content = content
    )
}

private fun Context.resolveColor(attr: Int, fallback: Color): Color {
    val tv = TypedValue()
    return if (theme.resolveAttribute(attr, tv, true) && tv.resourceId != 0) {
        Color(androidx.core.content.ContextCompat.getColor(this, tv.resourceId))
    } else {
        fallback
    }
}

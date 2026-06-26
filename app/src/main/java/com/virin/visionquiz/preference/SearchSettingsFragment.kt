package com.virin.visionquiz.preference

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.virin.visionquiz.R
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Locale

/**
 * Material 3 settings page for quiz search options.
 */
class SearchSettingsFragment : Fragment() {

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        return ScrollView(context).apply {
            setBackgroundColor(context.resolveThemeColor(com.google.android.material.R.attr.colorSurface))
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(16.dp(context), 16.dp(context), 16.dp(context), 24.dp(context))
                    addCameraSection(context)
                    addGeneralDisplaySection(context)
                    addScreenSearchSection(context)
                    addAccessibilitySection(context)
                    addMatchThresholdSection(context)
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun LinearLayout.addCameraSection(context: Context) {
        val rearResolutionEntries = getCameraResolutionEntries(context, CameraSelector.LENS_FACING_BACK)
        val frontResolutionEntries = getCameraResolutionEntries(context, CameraSelector.LENS_FACING_FRONT)
        val rows = listOf(
            createListRow(
                context = context,
                keyResId = R.string.pref_key_camerax_rear_camera_target_resolution,
                titleResId = R.string.pref_title_camerax_rear_camera_target_resolution,
                defaultValue = getDefaultCameraResolutionValue(
                    keyResId = R.string.pref_key_camerax_rear_camera_target_resolution,
                    entries = rearResolutionEntries
                ),
                entries = rearResolutionEntries,
                values = rearResolutionEntries
            ),
            createListRow(
                context = context,
                keyResId = R.string.pref_key_camerax_front_camera_target_resolution,
                titleResId = R.string.pref_title_camerax_front_camera_target_resolution,
                defaultValue = getDefaultCameraResolutionValue(
                    keyResId = R.string.pref_key_camerax_front_camera_target_resolution,
                    entries = frontResolutionEntries
                ),
                entries = frontResolutionEntries,
                values = frontResolutionEntries
            ),
            createSwitchRow(
                context = context,
                keyResId = R.string.pref_key_camera_live_viewport,
                titleResId = R.string.pref_title_camera_live_viewport,
                summary = getString(R.string.pref_summary_camera_live_viewport),
                defaultValue = true
            )
        )
        addSection(context, getString(R.string.pref_category_title_camera), rows)
    }

    private fun LinearLayout.addGeneralDisplaySection(context: Context) {
        val rows = listOf(
            createSwitchRow(
                context = context,
                keyResId = R.string.pref_key_brief_answer_display,
                titleResId = R.string.pref_title_brief_answer_display,
                summary = getString(R.string.pref_summary_brief_answer_display),
                defaultValue = false
            ),
            createArrayListRow(
                context = context,
                keyResId = R.string.pref_key_quiz_overlay_text_size,
                titleResId = R.string.pref_title_quiz_overlay_text_size,
                defaultValue = "12",
                entriesResId = R.array.pref_entries_quiz_overlay_text_size,
                valuesResId = R.array.pref_entry_values_quiz_overlay_text_size
            ),
            createSwitchRow(
                context = context,
                keyResId = R.string.pref_key_show_text_confidence,
                titleResId = R.string.pref_title_show_text_confidence,
                summary = getString(R.string.pref_summary_show_text_confidence),
                defaultValue = false
            ),
            createSwitchRow(
                context = context,
                keyResId = R.string.pref_key_group_recognized_text_in_blocks,
                titleResId = R.string.pref_title_group_recognized_text_in_blocks,
                summary = getString(R.string.pref_summary_group_recognized_text_in_blocks),
                defaultValue = true
            )
        )
        addSection(context, getString(R.string.pref_category_general_display), rows)
    }

    private fun LinearLayout.addScreenSearchSection(context: Context) {
        val rows = listOf(
            createArrayListRow(
                context = context,
                keyResId = R.string.pref_key_screen_search_interval_ms,
                titleResId = R.string.pref_title_screen_search_interval,
                defaultValue = "750",
                entriesResId = R.array.pref_entries_screen_search_interval,
                valuesResId = R.array.pref_entry_values_screen_search_interval
            ),
            createSwitchRow(
                context = context,
                keyResId = R.string.pref_key_screen_search_detect_changes,
                titleResId = R.string.pref_title_screen_search_detect_changes,
                summary = getString(R.string.pref_summary_screen_search_detect_changes),
                defaultValue = true
            ),
            createSwitchRow(
                context = context,
                keyResId = R.string.pref_key_screen_search_show_answer_frames,
                titleResId = R.string.pref_title_screen_search_show_answer_frames,
                summary = getString(R.string.pref_summary_screen_search_show_answer_frames),
                defaultValue = false
            ),
            createArrayListRow(
                context = context,
                keyResId = R.string.pref_key_screen_capture_frame_rate,
                titleResId = R.string.pref_title_screen_capture_frame_rate,
                defaultValue = "30",
                entriesResId = R.array.pref_entries_screen_capture_frame_rate,
                valuesResId = R.array.pref_entry_values_screen_capture_frame_rate
            )
        )
        addSection(context, getString(R.string.pref_category_screen_search), rows)
    }

    private fun LinearLayout.addAccessibilitySection(context: Context) {
        val rows = listOf(
            createArrayListRow(
                context = context,
                keyResId = R.string.pref_key_accessibility_search_interval_ms,
                titleResId = R.string.pref_title_accessibility_search_interval,
                defaultValue = "250",
                entriesResId = R.array.pref_entries_screen_search_interval,
                valuesResId = R.array.pref_entry_values_screen_search_interval
            ),
            createColorListRow(
                context = context,
                keyResId = R.string.pref_key_accessibility_answer_dot_color,
                titleResId = R.string.pref_title_accessibility_answer_dot_color,
                defaultValue = DEFAULT_ACCESSIBILITY_ANSWER_DOT_COLOR,
                entriesResId = R.array.pref_entries_accessibility_answer_dot_color,
                valuesResId = R.array.pref_entry_values_accessibility_answer_dot_color
            ),
            createArrayListRow(
                context = context,
                keyResId = R.string.pref_key_accessibility_vertical_swipe_mode,
                titleResId = R.string.pref_title_accessibility_vertical_swipe_mode,
                summary = getString(R.string.pref_summary_accessibility_vertical_swipe_mode),
                defaultValue = getString(
                    R.string.pref_value_accessibility_vertical_swipe_fixed
                ),
                entriesResId = R.array.pref_entries_accessibility_vertical_swipe_mode,
                valuesResId = R.array.pref_entry_values_accessibility_vertical_swipe_mode
            )
        )
        addSection(context, getString(R.string.pref_category_accessibility_search), rows)
    }

    private fun LinearLayout.addMatchThresholdSection(context: Context) {
        val rows = listOf(
            createThresholdRow(
                context = context,
                keyResId = R.string.pref_key_camera_search_min_match_score,
                titleResId = R.string.pref_title_camera_search_min_match_score,
                defaultValue = DEFAULT_THRESHOLD_SCORE
            ),
            createThresholdRow(
                context = context,
                keyResId = R.string.pref_key_screen_search_min_match_score,
                titleResId = R.string.pref_title_screen_search_min_match_score,
                defaultValue = DEFAULT_THRESHOLD_SCORE
            ),
            createThresholdRow(
                context = context,
                keyResId = R.string.pref_key_accessibility_search_min_match_score,
                titleResId = R.string.pref_title_accessibility_search_min_match_score,
                defaultValue = ACCESSIBILITY_THRESHOLD_DEFAULT_SCORE
            )
        )
        addSection(context, getString(R.string.pref_category_match_threshold), rows)
    }

    private fun LinearLayout.addSection(context: Context, title: String, rows: List<View>) {
        addView(TextView(context).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setTextColor(context.resolveThemeColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(8.dp(context), 12.dp(context), 8.dp(context), 8.dp(context))
        })

        addView(MaterialCardView(context).apply {
            radius = 8.dp(context).toFloat()
            cardElevation = 0f
            strokeWidth = 1.dp(context)
            setStrokeColor(context.resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant))
            setCardBackgroundColor(
                context.resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerLow)
            )

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                rows.forEachIndexed { index, row ->
                    addView(row)
                    if (index < rows.lastIndex) {
                        addView(createDivider(context))
                    }
                }
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 16.dp(context)
        })
    }

    private fun createSwitchRow(
        context: Context,
        @StringRes keyResId: Int,
        @StringRes titleResId: Int,
        summary: String? = null,
        defaultValue: Boolean
    ): View {
        val key = getString(keyResId)
        val switch = MaterialSwitch(context).apply {
            isChecked = sharedPreferences.getBoolean(key, defaultValue)
        }
        return createBaseRow(context, getString(titleResId), summary, switch).apply {
            setOnClickListener {
                switch.isChecked = !switch.isChecked
            }
            switch.setOnCheckedChangeListener { _, isChecked ->
                sharedPreferences.edit().putBoolean(key, isChecked).apply()
            }
        }
    }

    private fun createArrayListRow(
        context: Context,
        @StringRes keyResId: Int,
        @StringRes titleResId: Int,
        summary: String? = null,
        defaultValue: String,
        @ArrayRes entriesResId: Int,
        @ArrayRes valuesResId: Int
    ): View {
        return createListRow(
            context = context,
            keyResId = keyResId,
            titleResId = titleResId,
            summary = summary,
            defaultValue = defaultValue,
            entries = resources.getStringArray(entriesResId),
            values = resources.getStringArray(valuesResId)
        )
    }

    private fun createColorListRow(
        context: Context,
        @StringRes keyResId: Int,
        @StringRes titleResId: Int,
        defaultValue: String,
        @ArrayRes entriesResId: Int,
        @ArrayRes valuesResId: Int
    ): View {
        val key = getString(keyResId)
        val entries = resources.getStringArray(entriesResId)
        val values = resources.getStringArray(valuesResId)
        val currentValue = readStringValue(key, defaultValue, values)
        val summaryView = TextView(context).apply {
            text = entryForValue(currentValue, entries, values)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(context.resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            maxLines = 1
        }
        val swatchView = View(context)
        updateColorSwatch(context, swatchView, currentValue)

        val trailing = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(summaryView)
            addView(swatchView, LinearLayout.LayoutParams(18.dp(context), 18.dp(context)).apply {
                leftMargin = 8.dp(context)
            })
        }

        return createBaseRow(context, getString(titleResId), null, trailing).apply {
            setOnClickListener {
                val selectedIndex = values.indexOf(readStringValue(key, defaultValue, values))
                    .coerceAtLeast(0)
                MaterialAlertDialogBuilder(context)
                    .setTitle(getString(titleResId))
                    .setSingleChoiceItems(entries, selectedIndex) { dialog, which ->
                        val newValue = values[which]
                        sharedPreferences.edit().putString(key, newValue).apply()
                        summaryView.text = entries[which]
                        updateColorSwatch(context, swatchView, newValue)
                        dialog.dismiss()
                    }
                    .show()
            }
        }
    }

    private fun createThresholdRow(
        context: Context,
        @StringRes keyResId: Int,
        @StringRes titleResId: Int,
        defaultValue: Double
    ): View {
        val key = getString(keyResId)
        val valueView = TextView(context).apply {
            text = formatThreshold(readThresholdValue(key, defaultValue))
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(context.resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            maxLines = 1
        }

        return createBaseRow(
            context = context,
            title = getString(titleResId),
            summary = null,
            trailing = valueView
        ).apply {
            setOnClickListener {
                showThresholdDialog(
                    context = context,
                    key = key,
                    title = getString(titleResId),
                    summaryView = valueView,
                    defaultValue = defaultValue
                )
            }
        }
    }

    private fun showThresholdDialog(
        context: Context,
        key: String,
        title: String,
        summaryView: TextView,
        defaultValue: Double
    ) {
        val initialValue = readThresholdValue(key, defaultValue)
        val valueView = TextView(context).apply {
            text = formatThreshold(initialValue)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(context.resolveThemeColor(com.google.android.material.R.attr.colorPrimary))
            gravity = Gravity.CENTER
            setPadding(0, 8.dp(context), 0, 4.dp(context))
        }
        fun saveThreshold(value: Double) {
            val formattedValue = formatThreshold(value)
            sharedPreferences.edit().putString(key, formattedValue).apply()
            valueView.text = formattedValue
            summaryView.text = formattedValue
        }

        val slider = Slider(context).apply {
            valueFrom = MIN_THRESHOLD_SCORE.toFloat()
            valueTo = MAX_THRESHOLD_SCORE.toFloat()
            stepSize = THRESHOLD_SCORE_STEP.toFloat()
            value = initialValue.toFloat()
            addOnChangeListener { _, rawValue, _ ->
                saveThreshold(rawValue.toDouble())
            }
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24.dp(context), 8.dp(context), 24.dp(context), 0)
                addView(TextView(context).apply {
                    text = getString(R.string.pref_summary_search_min_match_score)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(
                        context.resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
                    )
                })
                addView(valueView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(slider, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            })
            .setNeutralButton(R.string.pref_threshold_restore_default, null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            slider.value = defaultValue.toFloat()
            saveThreshold(defaultValue)
        }
    }

    private fun createListRow(
        context: Context,
        @StringRes keyResId: Int,
        @StringRes titleResId: Int,
        summary: String? = null,
        defaultValue: String,
        entries: Array<String>,
        values: Array<String>
    ): View {
        val key = getString(keyResId)
        val currentValue = readStringValue(key, defaultValue, values)
        val currentEntry = entryForValue(currentValue, entries, values)
        val summaryView = TextView(context).apply {
            text = currentEntry
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(context.resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            maxLines = 2
        }

        return createBaseRow(context, getString(titleResId), summary, summaryView).apply {
            setOnClickListener {
                val selectedIndex = values.indexOf(currentStoredString(key, defaultValue)).coerceAtLeast(0)
                MaterialAlertDialogBuilder(context)
                    .setTitle(getString(titleResId))
                    .setSingleChoiceItems(entries, selectedIndex) { dialog, which ->
                        val newValue = values[which]
                        sharedPreferences.edit().putString(key, newValue).apply()
                        summaryView.text = entries[which]
                        dialog.dismiss()
                    }
                    .show()
            }
        }
    }

    private fun createBaseRow(
        context: Context,
        title: String,
        summary: String?,
        trailing: View
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 64.dp(context)
            isClickable = true
            isFocusable = true
            background = selectableItemBackground(context)
            setPadding(20.dp(context), 12.dp(context), 16.dp(context), 12.dp(context))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = title
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    setTextColor(context.resolveThemeColor(com.google.android.material.R.attr.colorOnSurface))
                    includeFontPadding = false
                })
                if (!summary.isNullOrBlank()) {
                    addView(TextView(context).apply {
                        text = summary
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                        setTextColor(
                            context.resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
                        )
                        setPadding(0, 4.dp(context), 12.dp(context), 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(FrameLayout(context).apply {
                foregroundGravity = Gravity.CENTER
                addView(trailing, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 16.dp(context)
            })
        }
    }

    private fun createDivider(context: Context): View {
        return View(context).apply {
            setBackgroundColor(context.resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply {
                leftMargin = 20.dp(context)
            }
        }
    }

    private fun readStringValue(key: String, defaultValue: String, values: Array<String>): String {
        val value = currentStoredString(key, defaultValue)
        return if (value in values) value else defaultValue
    }

    private fun currentStoredString(key: String, defaultValue: String): String {
        return sharedPreferences.getString(key, defaultValue) ?: defaultValue
    }

    private fun readThresholdValue(key: String, defaultValue: Double): Double {
        val formattedDefaultValue = formatThreshold(defaultValue)
        val storedValue = currentStoredString(key, formattedDefaultValue).toDoubleOrNull()
        return normalizeThreshold(storedValue ?: defaultValue)
    }

    private fun normalizeThreshold(value: Double): Double {
        if (!value.isFinite()) {
            return DEFAULT_THRESHOLD_SCORE
        }
        val clampedValue = value.coerceIn(MIN_THRESHOLD_SCORE, MAX_THRESHOLD_SCORE)
        val steps = ((clampedValue - MIN_THRESHOLD_SCORE) / THRESHOLD_SCORE_STEP).roundToInt()
        return (MIN_THRESHOLD_SCORE + steps * THRESHOLD_SCORE_STEP)
            .coerceIn(MIN_THRESHOLD_SCORE, MAX_THRESHOLD_SCORE)
    }

    private fun formatThreshold(value: Double): String {
        return String.format(Locale.US, "%.2f", normalizeThreshold(value))
    }

    private fun entryForValue(value: String, entries: Array<String>, values: Array<String>): String {
        val index = values.indexOf(value)
        return if (index in entries.indices) entries[index] else value
    }

    private fun updateColorSwatch(context: Context, swatchView: View, colorValue: String) {
        val fillColor = runCatching { Color.parseColor(colorValue) }
            .getOrDefault(Color.BLACK)
        swatchView.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            setStroke(
                1.dp(context),
                context.resolveThemeColor(com.google.android.material.R.attr.colorOutlineVariant)
            )
        }
    }

    private fun getCameraResolutionEntries(context: Context, lensFacing: Int): Array<String> {
        val cameraCharacteristics = getCameraCharacteristics(context, lensFacing)
        val outputSizes = cameraCharacteristics
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
        return outputSizes
            ?.map(Size::toString)
            ?.toTypedArray()
            ?: arrayOf(
                "2000x2000",
                "1600x1600",
                "1200x1200",
                "1000x1000",
                "800x800",
                "600x600",
                "400x400",
                "200x200",
                "100x100"
            )
    }

    private fun getDefaultCameraResolutionValue(
        @StringRes keyResId: Int,
        entries: Array<String>
    ): String {
        val targetSize = PreferenceUtils.getDefaultCameraXTargetResolution()
        val defaultValue = selectClosestSize(entries.toList(), targetSize)
        val key = getString(keyResId)
        if (!sharedPreferences.contains(key)) {
            sharedPreferences.edit().putString(key, defaultValue).apply()
        }
        return defaultValue
    }

    private fun getCameraCharacteristics(context: Context, lensFacing: Int): CameraCharacteristics? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return try {
            cameraManager.cameraIdList
                .mapNotNull { cameraManager.getCameraCharacteristics(it) }
                .firstOrNull { characteristics ->
                    characteristics.get(CameraCharacteristics.LENS_FACING) == lensFacing
                }
        } catch (e: CameraAccessException) {
            null
        }
    }

    private fun selectClosestSize(supportedSizes: List<String>, targetSize: Size): String {
        return supportedSizes.minByOrNull { sizeString ->
            val parsedSize = Size.parseSize(sizeString)
            abs(parsedSize.width - targetSize.width) + abs(parsedSize.height - targetSize.height)
        } ?: PreferenceUtils.getDefaultCameraXTargetResolutionString()
    }

    private fun selectableItemBackground(context: Context) =
        TypedValue().let { typedValue ->
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
            ContextCompat.getDrawable(context, typedValue.resourceId)
        }

    private fun Context.resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun Int.dp(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val MIN_THRESHOLD_SCORE = 0.60
        const val MAX_THRESHOLD_SCORE = 1.00
        const val DEFAULT_THRESHOLD_SCORE = 0.76
        const val ACCESSIBILITY_THRESHOLD_DEFAULT_SCORE = 1.00
        const val THRESHOLD_SCORE_STEP = 0.02
        const val DEFAULT_ACCESSIBILITY_ANSWER_DOT_COLOR = "#000000"
    }
}

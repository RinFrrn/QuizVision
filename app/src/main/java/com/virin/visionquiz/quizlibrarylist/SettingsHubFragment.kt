package com.virin.visionquiz.quizlibrarylist

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.virin.visionquiz.R
import com.virin.visionquiz.preference.SettingsActivity
import com.virin.visionquiz.util.BaseQuizFragment
import com.virin.visionquiz.util.configureQuizTopBar
import com.virin.visionquiz.util.dp

class SettingsHubFragment : BaseQuizFragment() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var content: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings_hub, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        content = view.findViewById(R.id.settings_hub_content)
        configureQuizTopBar(toolbar, getString(R.string.settings_hub_title))
        content.addView(settingsCard())
    }

    private fun settingsCard(): MaterialCardView {
        val context = requireContext()
        return MaterialCardView(context).apply {
            radius = 8.dp.toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(settingsTile(
                    title = getString(R.string.search_settings_menu_title),
                    summary = getString(R.string.settings_hub_search_summary),
                    iconRes = R.drawable.round_search_24,
                    onClick = ::openSearchSettings
                ))
                addView(settingsTile(
                    title = getString(R.string.import_settings_menu_title),
                    summary = getString(R.string.settings_hub_import_summary),
                    iconRes = R.drawable.icon_document_search_24px,
                    onClick = ::openImportSettings
                ), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 12.dp
                })
                addView(settingsTile(
                    title = getString(R.string.ai_settings_title),
                    summary = getString(R.string.settings_hub_ai_summary),
                    iconRes = R.drawable.icon_science_24px,
                    onClick = ::openAiSettings
                ), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 12.dp
                })
            })
        }
    }

    private fun settingsTile(
        title: String,
        summary: String,
        iconRes: Int,
        onClick: () -> Unit
    ): View {
        val context = requireContext()
        return MaterialCardView(context).apply {
            radius = 8.dp.toFloat()
            cardElevation = 0f
            strokeWidth = 1.dp
            setStrokeColor(resolveColor(com.google.android.material.R.attr.colorOutlineVariant))
            setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceContainer))
            isClickable = true
            isFocusable = true
            foreground = selectableItemBackground()
            setOnClickListener { onClick() }

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(18.dp, 18.dp, 18.dp, 16.dp)

                addView(FrameLayout(context).apply {
                    background = ContextCompat.getDrawable(context, R.drawable.bg_library_icon_container)
                    addView(ImageView(context).apply {
                        setImageResource(iconRes)
                        imageTintList = ColorStateList.valueOf(
                            resolveColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
                        )
                        contentDescription = null
                    }, FrameLayout.LayoutParams(28.dp, 28.dp, Gravity.CENTER))
                }, LinearLayout.LayoutParams(56.dp, 56.dp))

                addView(TextView(context).apply {
                    text = title
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                    setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface))
                    maxLines = 1
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 12.dp
                })
                addView(TextView(context).apply {
                    text = summary
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    maxLines = 2
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 4.dp
                })
            })
        }
    }

    private fun openImportSettings() {
        findNavController().navigate(R.id.action_SettingsHubFragment_to_ImportCandidateSettingsFragment)
    }

    private fun openSearchSettings() {
        startActivity(Intent(requireContext(), SettingsActivity::class.java).apply {
            putExtra(
                SettingsActivity.EXTRA_LAUNCH_SOURCE,
                SettingsActivity.LaunchSource.QUIZ_CAMERAX
            )
        })
    }

    private fun openAiSettings() {
        startActivity(Intent(requireContext(), SettingsActivity::class.java).apply {
            putExtra(
                SettingsActivity.EXTRA_LAUNCH_SOURCE,
                SettingsActivity.LaunchSource.AI_SETTINGS
            )
        })
    }

    private fun selectableItemBackground() =
        TypedValue().let { typedValue ->
            requireContext().theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                typedValue,
                true
            )
            ContextCompat.getDrawable(requireContext(), typedValue.resourceId)
        }

    private fun resolveColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(requireContext(), typedValue.resourceId)
        } else {
            typedValue.data
        }
    }
}

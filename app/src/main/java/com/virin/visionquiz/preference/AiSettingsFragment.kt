package com.virin.visionquiz.preference

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.virin.visionquiz.R
import com.virin.visionquiz.ai.AiConfig
import com.virin.visionquiz.ai.AiConfigStore
import com.virin.visionquiz.ai.AiEndpointValidator
import com.virin.visionquiz.ai.AiExplanationRepository
import com.virin.visionquiz.ai.AiProfile
import com.virin.visionquiz.ai.AiPrompt
import com.virin.visionquiz.ai.AiPromptBuilder
import com.virin.visionquiz.ai.AiTestResult
import com.virin.visionquiz.ai.AiTestStatus
import com.virin.visionquiz.ai.OpenAiCompatibleClient
import com.virin.visionquiz.util.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiSettingsFragment : Fragment() {
    private lateinit var configStore: AiConfigStore
    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var profileContainer: LinearLayout
    private lateinit var quickReviewInput: TextInputEditText
    private lateinit var detailedAnalysisInput: TextInputEditText
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        configStore = AiConfigStore(requireContext())
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 12.dp, 16.dp, 32.dp)
        }
        enabledSwitch = MaterialSwitch(context).apply {
            text = getString(R.string.ai_settings_enabled)
            setTypeface(typeface, Typeface.BOLD)
            isChecked = configStore.isEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                configStore.setEnabled(isChecked)
            }
        }
        content.addView(enabledSwitch, matchWrap(bottom = 4))
        content.addView(TextView(context).apply {
            text = getString(R.string.ai_settings_enabled_summary)
            setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            textSize = 13f
        }, matchWrap(bottom = 12))

        val profileHeader = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionTitle(context, R.string.ai_profiles_title), LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ))
            addView(MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = getString(R.string.ai_profile_add)
                setOnClickListener { showProfileEditor(null) }
            })
        }
        content.addView(profileHeader, matchWrap())
        profileContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(profileContainer, matchWrap(bottom = 12))

        content.addView(sectionTitle(context, R.string.ai_settings_prompts))
        content.addView(buildPromptCard(context))
        content.addView(sectionTitle(context, R.string.ai_settings_cache))
        content.addView(buildCacheCard(context))

        quickReviewInput.setText(configStore.quickReviewPrompt())
        detailedAnalysisInput.setText(configStore.analysisPrompt())
        renderProfiles()
        return ScrollView(context).apply {
            setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface))
            isFillViewport = true
            addView(content)
        }
    }

    private fun renderProfiles() {
        profileContainer.removeAllViews()
        val defaultId = configStore.getDefaultProfileId()
        configStore.listProfiles().forEach { profile ->
            profileContainer.addView(
                buildProfileCard(profile, profile.id == defaultId),
                matchWrap(bottom = 10)
            )
        }
    }

    private fun buildProfileCard(profile: AiProfile, isDefault: Boolean): View {
        val context = requireContext()
        return settingsCard(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14.dp, 12.dp, 14.dp, 10.dp)
                addView(TextView(context).apply {
                    text = if (isDefault) {
                        "${profile.name} · ${getString(R.string.ai_profile_default)}"
                    } else {
                        profile.name
                    }
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                })
                addView(TextView(context).apply {
                    text = "${profile.model} · ${profile.baseUrl}"
                    setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    textSize = 13f
                    maxLines = 2
                }, matchWrap(top = 4))
                addView(TextView(context).apply {
                    text = formatTestResult(profile)
                    setTextColor(
                        resolveColor(
                            when {
                                profile.isTestResultStale() ->
                                    com.google.android.material.R.attr.colorTertiary
                                profile.testResult.status == AiTestStatus.SUCCESS ->
                                    com.google.android.material.R.attr.colorPrimary
                                profile.testResult.status == AiTestStatus.FAILURE ->
                                    com.google.android.material.R.attr.colorError
                                else -> com.google.android.material.R.attr.colorOnSurfaceVariant
                            }
                        )
                    )
                    textSize = 13f
                }, matchWrap(top = 8))
                addView(LinearLayout(context).apply {
                    gravity = Gravity.END
                    if (!isDefault) {
                        addActionButton(this, R.string.ai_profile_set_default) {
                            configStore.setDefaultProfile(profile.id)
                            renderProfiles()
                        }
                    }
                    addActionButton(this, R.string.ai_profile_copy) {
                        runCatching { configStore.duplicateProfile(profile.id) }
                            .onSuccess { renderProfiles() }
                            .onFailure(::showError)
                    }
                    addActionButton(this, R.string.edit) { showProfileEditor(profile) }
                    if (!isDefault) {
                        addActionButton(this, R.string.delete) { confirmDelete(profile) }
                    }
                }, matchWrap(top = 4))
            })
        }
    }

    private fun addActionButton(parent: LinearLayout, textRes: Int, action: () -> Unit) {
        parent.addView(MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            setText(textRes)
            setOnClickListener { action() }
        })
    }

    private fun showProfileEditor(existing: AiProfile?) {
        val context = requireContext()
        val draft = existing ?: AiProfile(
            name = getString(R.string.ai_profile_new_name),
            baseUrl = AiConfigStore.DEFAULT_BASE_URL,
            apiKey = "",
            model = AiConfigStore.DEFAULT_MODEL
        )
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
        }
        val nameLayout = inputLayout(context, R.string.ai_profile_name)
        val nameInput = singleLineInput(context).apply { setText(draft.name) }
        nameLayout.addView(nameInput)
        column.addView(nameLayout, matchWrap(bottom = 12))
        val urlLayout = inputLayout(context, R.string.ai_settings_base_url)
        val urlInput = singleLineInput(context).apply { setText(draft.baseUrl) }
        urlLayout.addView(urlInput)
        column.addView(urlLayout, matchWrap(bottom = 12))
        val keyLayout = inputLayout(context, R.string.ai_settings_api_key)
        val keyInput = singleLineInput(context).apply {
            setText(draft.apiKey)
        }
        keyLayout.addView(keyInput)
        column.addView(keyLayout, matchWrap(bottom = 12))
        val modelRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val modelLayout = inputLayout(context, R.string.ai_settings_model).apply {
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
        }
        val modelInput = MaterialAutoCompleteTextView(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            threshold = 0
            setText(draft.model)
        }
        modelLayout.addView(modelInput)
        modelRow.addView(modelLayout, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ))
        val fetchModelsButton = MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            setText(R.string.ai_models_fetch)
        }
        modelRow.addView(fetchModelsButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = 8.dp
        })
        column.addView(modelRow, matchWrap())
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) R.string.ai_profile_add else R.string.ai_profile_edit)
            .setView(column)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.ai_settings_test, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            fetchModelsButton.setOnClickListener {
                fetchModels(
                    urlLayout,
                    urlInput,
                    keyLayout,
                    keyInput,
                    modelLayout,
                    modelInput,
                    fetchModelsButton
                )
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val profile = validateAndSaveProfile(
                    draft,
                    nameLayout,
                    nameInput,
                    urlLayout,
                    urlInput,
                    keyLayout,
                    keyInput,
                    modelLayout,
                    modelInput
                ) ?: return@setOnClickListener
                renderProfiles()
                dialog.dismiss()
                if (existing == null && configStore.listProfiles().size == 1) {
                    configStore.setDefaultProfile(profile.id)
                }
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                val profile = validateAndSaveProfile(
                    draft,
                    nameLayout,
                    nameInput,
                    urlLayout,
                    urlInput,
                    keyLayout,
                    keyInput,
                    modelLayout,
                    modelInput
                ) ?: return@setOnClickListener
                val button = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
                testProfile(profile, button) {
                    renderProfiles()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun validateAndSaveProfile(
        draft: AiProfile,
        nameLayout: TextInputLayout,
        nameInput: TextInputEditText,
        urlLayout: TextInputLayout,
        urlInput: TextInputEditText,
        keyLayout: TextInputLayout,
        keyInput: TextInputEditText,
        modelLayout: TextInputLayout,
        modelInput: EditText
    ): AiProfile? {
        listOf(nameLayout, urlLayout, keyLayout, modelLayout).forEach { it.error = null }
        val name = nameInput.text?.toString().orEmpty().trim()
        val url = urlInput.text?.toString().orEmpty().trim()
        val key = keyInput.text?.toString().orEmpty().trim()
        val model = modelInput.text?.toString().orEmpty().trim()
        var valid = true
        if (name.isBlank()) {
            nameLayout.error = getString(R.string.ai_settings_required)
            valid = false
        }
        if (url.isBlank()) {
            urlLayout.error = getString(R.string.ai_settings_required)
            valid = false
        } else {
            AiEndpointValidator.buildEndpoint(url).exceptionOrNull()?.let {
                urlLayout.error = it.message
                valid = false
            }
        }
        if (key.isBlank()) {
            keyLayout.error = getString(R.string.ai_settings_required)
            valid = false
        }
        if (model.isBlank()) {
            modelLayout.error = getString(R.string.ai_settings_required)
            valid = false
        }
        if (!valid) return null
        return runCatching {
            configStore.saveProfile(
                draft.copy(name = name, baseUrl = url, apiKey = key, model = model)
            )
        }.onFailure {
            nameLayout.error = it.message
        }.getOrNull()
    }

    private fun fetchModels(
        urlLayout: TextInputLayout,
        urlInput: TextInputEditText,
        keyLayout: TextInputLayout,
        keyInput: TextInputEditText,
        modelLayout: TextInputLayout,
        modelInput: MaterialAutoCompleteTextView,
        button: MaterialButton
    ) {
        urlLayout.error = null
        keyLayout.error = null
        modelLayout.error = null
        val url = urlInput.text?.toString().orEmpty().trim()
        val key = keyInput.text?.toString().orEmpty().trim()
        var valid = true
        if (url.isBlank()) {
            urlLayout.error = getString(R.string.ai_settings_required)
            valid = false
        } else {
            AiEndpointValidator.buildModelsEndpoint(url).exceptionOrNull()?.let {
                urlLayout.error = it.message
                valid = false
            }
        }
        if (key.isBlank()) {
            keyLayout.error = getString(R.string.ai_settings_required)
            valid = false
        }
        if (!valid) return

        button.isEnabled = false
        button.setText(R.string.ai_models_fetching)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { OpenAiCompatibleClient().listModels(url, key) }
            }
            if (!button.isAttachedToWindow) return@launch
            button.isEnabled = true
            button.setText(R.string.ai_models_fetch)
            result.onSuccess { models ->
                if (models.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.ai_models_empty,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@onSuccess
                }
                modelInput.setAdapter(
                    ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_dropdown_item_1line,
                        models
                    )
                )
                modelInput.showDropDown()
            }.onFailure { error ->
                modelLayout.error = getString(
                    R.string.ai_models_fetch_failed,
                    error.message ?: getString(R.string.ai_request_failed)
                )
            }
        }
    }

    private fun testProfile(
        profile: AiProfile,
        button: Button,
        onComplete: () -> Unit
    ) {
        button.isEnabled = false
        button.setText(R.string.ai_loading)
        val config = buildConfig(profile)
        val started = System.currentTimeMillis()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    OpenAiCompatibleClient().complete(
                        config,
                        AiPrompt(
                            AiPromptBuilder.SYSTEM_PROMPT,
                            "这是连接测试。请只回复“连接成功”。"
                        )
                    )
                }
            }
            val duration = System.currentTimeMillis() - started
            val testResult = result.fold(
                onSuccess = {
                    AiTestResult(
                        status = AiTestStatus.SUCCESS,
                        testedAt = System.currentTimeMillis(),
                        durationMillis = duration,
                        message = it.trim().take(200),
                        configFingerprint = profile.connectionFingerprint()
                    )
                },
                onFailure = {
                    AiTestResult(
                        status = AiTestStatus.FAILURE,
                        testedAt = System.currentTimeMillis(),
                        durationMillis = duration,
                        message = (it.message ?: getString(R.string.ai_request_failed)).take(240),
                        configFingerprint = profile.connectionFingerprint()
                    )
                }
            )
            configStore.saveTestResult(profile.id, testResult)
            button.isEnabled = true
            button.setText(R.string.ai_settings_test)
            Toast.makeText(
                requireContext(),
                if (testResult.status == AiTestStatus.SUCCESS) {
                    R.string.ai_settings_test_success
                } else {
                    R.string.ai_settings_test_failed
                },
                Toast.LENGTH_SHORT
            ).show()
            onComplete()
        }
    }

    private fun buildConfig(profile: AiProfile): AiConfig {
        return AiConfig(
            enabled = enabledSwitch.isChecked,
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKey,
            model = profile.model,
            quickReviewPrompt = quickReviewInput.text?.toString().orEmpty(),
            analysisPrompt = detailedAnalysisInput.text?.toString().orEmpty(),
            techniquePrompt = configStore.techniquePrompt(),
            mnemonicPrompt = configStore.mnemonicPrompt(),
            profileId = profile.id,
            profileName = profile.name
        )
    }

    private fun formatTestResult(profile: AiProfile): String {
        val result = profile.testResult
        if (result.status == AiTestStatus.NOT_TESTED) {
            return getString(R.string.ai_profile_not_tested)
        }
        val prefix = when {
            profile.isTestResultStale() -> getString(R.string.ai_profile_test_stale)
            result.status == AiTestStatus.SUCCESS -> getString(R.string.ai_profile_test_success)
            else -> getString(R.string.ai_profile_test_failure)
        }
        val time = dateFormat.format(Date(result.testedAt))
        return "$prefix · $time · ${result.durationMillis} ms\n${result.message}"
    }

    private fun confirmDelete(profile: AiProfile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_profile_delete_title)
            .setMessage(getString(R.string.ai_profile_delete_message, profile.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                runCatching { configStore.deleteProfile(profile.id) }
                    .onSuccess { renderProfiles() }
                    .onFailure(::showError)
            }
            .show()
    }

    private fun showError(error: Throwable) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(error.message ?: getString(R.string.ai_request_failed))
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun savePrompts(): Boolean {
        val prompts = listOf(quickReviewInput, detailedAnalysisInput)
        if (prompts.any { it.text?.toString().orEmpty().isBlank() }) {
            Toast.makeText(requireContext(), R.string.ai_settings_required, Toast.LENGTH_SHORT).show()
            return false
        }
        configStore.savePrompts(
            quickReviewPrompt = quickReviewInput.text.toString(),
            analysisPrompt = detailedAnalysisInput.text.toString(),
            techniquePrompt = configStore.techniquePrompt(),
            mnemonicPrompt = configStore.mnemonicPrompt()
        )
        return true
    }

    private fun buildPromptCard(context: Context): View {
        return settingsCard(context).apply {
            val column = cardColumn(context)
            quickReviewInput = addPromptEditor(
                column,
                context,
                R.string.ai_settings_quick_review_prompt,
                AiPromptBuilder.DEFAULT_QUICK_REVIEW_PROMPT
            )
            detailedAnalysisInput = addPromptEditor(
                column,
                context,
                R.string.ai_settings_detailed_analysis_prompt,
                AiPromptBuilder.DEFAULT_ANALYSIS_PROMPT
            )
            column.addView(MaterialButton(context).apply {
                setText(R.string.ai_settings_save_prompts)
                setOnClickListener {
                    if (savePrompts()) {
                        Toast.makeText(
                            context,
                            R.string.ai_settings_prompts_saved,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }, wrapWrap(Gravity.END, bottom = 4))
            addView(column)
        }
    }

    private fun addPromptEditor(
        parent: LinearLayout,
        context: Context,
        hintRes: Int,
        defaultValue: String
    ): TextInputEditText {
        val layout = inputLayout(context, hintRes)
        val input = TextInputEditText(context).apply {
            minLines = 3
            maxLines = 7
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        layout.addView(input)
        parent.addView(layout, matchWrap(top = 4))
        parent.addView(MaterialButton(
            context,
            null,
            com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = getString(R.string.ai_settings_restore_default)
            setOnClickListener { input.setText(defaultValue) }
        }, wrapWrap(Gravity.END, bottom = 8))
        return input
    }

    private fun buildCacheCard(context: Context): View {
        return settingsCard(context).apply {
            addView(MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = getString(R.string.ai_settings_clear_cache)
                setOnClickListener {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.ai_settings_clear_cache)
                        .setMessage(R.string.ai_settings_clear_cache_message)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.confirm) { _, _ ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                withContext(Dispatchers.IO) {
                                    AiExplanationRepository(context.applicationContext).clearAll()
                                }
                                Toast.makeText(
                                    context,
                                    R.string.ai_settings_cache_cleared,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .show()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                setMargins(12.dp, 8.dp, 12.dp, 8.dp)
            })
        }
    }

    private fun settingsCard(context: Context) = MaterialCardView(context).apply {
        radius = 12.dp.toFloat()
        cardElevation = 0f
        strokeWidth = 1.dp
        setStrokeColor(resolveColor(com.google.android.material.R.attr.colorOutlineVariant))
        setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
    }

    private fun cardColumn(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(14.dp, 14.dp, 14.dp, 10.dp)
    }

    private fun sectionTitle(context: Context, textRes: Int) = TextView(context).apply {
        setText(textRes)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        setTextColor(resolveColor(com.google.android.material.R.attr.colorPrimary))
        setPadding(8.dp, 12.dp, 8.dp, 8.dp)
    }

    private fun inputLayout(context: Context, hintRes: Int) = TextInputLayout(
        context,
        null,
        com.google.android.material.R.attr.textInputOutlinedStyle
    ).apply {
        hint = getString(hintRes)
    }

    private fun singleLineInput(context: Context) = TextInputEditText(context).apply {
        setSingleLine(true)
    }

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, top.dp, 0, bottom.dp) }

    private fun wrapWrap(
        gravityValue: Int,
        bottom: Int = 0
    ) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        gravity = gravityValue
        setMargins(0, 0, 0, bottom.dp)
    }

    private fun resolveColor(attr: Int): Int {
        val typed = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, typed, true)
        return typed.data
    }
}

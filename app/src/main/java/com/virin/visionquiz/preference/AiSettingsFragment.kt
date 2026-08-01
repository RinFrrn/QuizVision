package com.virin.visionquiz.preference

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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
    private lateinit var cramAnalysisInput: TextInputEditText
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
        cramAnalysisInput.setText(configStore.cramAnalysisPrompt())
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
            isClickable = true
            isFocusable = true
            foreground = selectableItemBackground(context)
            setOnClickListener { showProfileEditor(profile) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp, 14.dp, 12.dp, 10.dp)
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(context).apply {
                        text = profile.name
                        setTextAppearance(
                            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                        )
                        setTypeface(typeface, Typeface.BOLD)
                    }, LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ))
                    if (isDefault) {
                        addView(profileBadge(context, getString(R.string.ai_profile_default)))
                    }
                })
                addView(TextView(context).apply {
                    text = profile.model.ifBlank { getString(R.string.ai_settings_model) }
                    setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    maxLines = 1
                }, matchWrap(top = 6))
                addView(TextView(context).apply {
                    text = profile.baseUrl
                    setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    maxLines = 1
                }, matchWrap(top = 2))
                addView(TextView(context).apply {
                    text = "● ${formatTestResult(profile)}"
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
                    maxLines = 3
                }, matchWrap(top = 10))
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    addView(TextView(context).apply {
                        setText(R.string.ai_profile_edit_hint)
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                        setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    }, LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f
                    ))
                    if (!isDefault) {
                        addActionButton(this, R.string.ai_profile_set_default) { _ ->
                            configStore.setDefaultProfile(profile.id)
                            renderProfiles()
                        }
                    }
                    addActionButton(this, R.string.ai_profile_more) { anchor ->
                        showProfileMenu(anchor, profile, isDefault)
                    }
                }, matchWrap(top = 6))
            })
        }
    }

    private fun profileBadge(context: Context, textValue: String) = TextView(context).apply {
        text = textValue
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
        setTextColor(resolveColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
        setPadding(10.dp, 5.dp, 10.dp, 5.dp)
        background = GradientDrawable().apply {
            cornerRadius = 24.dp.toFloat()
            setColor(resolveColor(com.google.android.material.R.attr.colorPrimaryContainer))
        }
    }

    private fun addActionButton(
        parent: LinearLayout,
        textRes: Int,
        action: (View) -> Unit
    ) {
        parent.addView(MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            setText(textRes)
            setOnClickListener(action)
        })
    }

    private fun showProfileMenu(anchor: View, profile: AiProfile, isDefault: Boolean) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, PROFILE_ACTION_COPY, 0, R.string.ai_profile_copy)
            if (!isDefault) {
                menu.add(0, PROFILE_ACTION_DELETE, 1, R.string.delete)
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    PROFILE_ACTION_COPY -> {
                        runCatching { configStore.duplicateProfile(profile.id) }
                            .onSuccess { renderProfiles() }
                            .onFailure(::showError)
                        true
                    }
                    PROFILE_ACTION_DELETE -> {
                        confirmDelete(profile)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showProfileEditor(existing: AiProfile?) {
        val context = requireContext()
        val draft = existing ?: AiProfile(
            name = suggestedProfileName(),
            baseUrl = AiConfigStore.DEFAULT_BASE_URL,
            apiKey = "",
            model = AiConfigStore.DEFAULT_MODEL
        )
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_ai_profile_editor, null)
        val title: TextView = sheet.findViewById(R.id.ai_profile_editor_title)
        val nameLayout: TextInputLayout = sheet.findViewById(R.id.ai_profile_name_layout)
        val nameInput: TextInputEditText = sheet.findViewById(R.id.ai_profile_name_input)
        val urlLayout: TextInputLayout = sheet.findViewById(R.id.ai_profile_url_layout)
        val urlInput: TextInputEditText = sheet.findViewById(R.id.ai_profile_url_input)
        val keyLayout: TextInputLayout = sheet.findViewById(R.id.ai_profile_key_layout)
        val keyInput: TextInputEditText = sheet.findViewById(R.id.ai_profile_key_input)
        val modelLayout: TextInputLayout = sheet.findViewById(R.id.ai_profile_model_layout)
        val modelInput: MaterialAutoCompleteTextView =
            sheet.findViewById(R.id.ai_profile_model_input)
        val fetchModelsButton: MaterialButton =
            sheet.findViewById(R.id.ai_profile_fetch_models_button)
        val testStatusCard: MaterialCardView =
            sheet.findViewById(R.id.ai_profile_test_status_card)
        val testStatusText: TextView = sheet.findViewById(R.id.ai_profile_test_status_text)
        val cancelButton: MaterialButton = sheet.findViewById(R.id.ai_profile_cancel_button)
        val testButton: MaterialButton = sheet.findViewById(R.id.ai_profile_test_button)
        val saveButton: MaterialButton = sheet.findViewById(R.id.ai_profile_save_button)
        val actions: LinearLayout = sheet.findViewById(R.id.ai_profile_editor_actions)

        title.setText(if (existing == null) R.string.ai_profile_add else R.string.ai_profile_edit)
        nameInput.setText(draft.name)
        urlInput.setText(draft.baseUrl)
        keyInput.setText(draft.apiKey)
        modelInput.threshold = 0
        modelInput.setText(draft.model, false)
        var latestTestResult = draft.testResult
        if (latestTestResult.status != AiTestStatus.NOT_TESTED) {
            showEditorTestResult(draft, testStatusCard, testStatusText)
        }

        val dialog = BottomSheetDialog(context)
        dialog.setContentView(sheet)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.window?.navigationBarColor =
            resolveColor(com.google.android.material.R.attr.colorSurface)
        val actionsBaseBottomPadding = actions.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(actions) { view, insets ->
            val navigationBarBottom = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars()
            ).bottom
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                actionsBaseBottomPadding + navigationBarBottom
            )
            insets
        }
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            bottomSheet.post {
                val parent = bottomSheet.parent as? View ?: return@post
                val statusBarTop = ViewCompat.getRootWindowInsets(bottomSheet)
                    ?.getInsets(WindowInsetsCompat.Type.statusBars())
                    ?.top
                    ?: 0
                val topGap = maxOf(statusBarTop + 12.dp, 32.dp)
                bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                    height = (parent.height - topGap).coerceAtLeast(1)
                }
                BottomSheetBehavior.from(bottomSheet).apply {
                    skipCollapsed = true
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }
        cancelButton.setOnClickListener { dialog.dismiss() }
        fetchModelsButton.setOnClickListener {
            hideKeyboard(urlInput)
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
        testButton.setOnClickListener {
            val profile = validateProfileDraft(
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
            hideKeyboard(modelInput)
            testProfileDraft(
                profile = profile,
                testButton = testButton,
                saveButton = saveButton,
                fetchModelsButton = fetchModelsButton,
                statusCard = testStatusCard,
                statusText = testStatusText,
                onResult = { latestTestResult = it }
            )
        }
        saveButton.setOnClickListener {
            val profile = validateProfileDraft(
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
            runCatching {
                configStore.saveProfile(profile.copy(testResult = latestTestResult))
            }.onSuccess {
                renderProfiles()
                dialog.dismiss()
            }.onFailure {
                nameLayout.error = it.message
            }
        }
        dialog.show()
    }

    private fun validateProfileDraft(
        draft: AiProfile,
        nameLayout: TextInputLayout,
        nameInput: TextInputEditText,
        urlLayout: TextInputLayout,
        urlInput: TextInputEditText,
        keyLayout: TextInputLayout,
        keyInput: TextInputEditText,
        modelLayout: TextInputLayout,
        modelInput: MaterialAutoCompleteTextView
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
        return draft.copy(name = name, baseUrl = url, apiKey = key, model = model)
    }

    private fun suggestedProfileName(): String {
        val baseName = getString(R.string.ai_profile_new_name)
        val existingNames = configStore.listProfiles().map { it.name.lowercase() }.toSet()
        if (baseName.lowercase() !in existingNames) return baseName
        var suffix = 2
        while ("$baseName $suffix".lowercase() in existingNames) suffix++
        return "$baseName $suffix"
    }

    private fun hideKeyboard(view: View) {
        val inputMethodManager = requireContext().getSystemService(
            Context.INPUT_METHOD_SERVICE
        ) as android.view.inputmethod.InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
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
                modelLayout.helperText = getString(R.string.ai_models_found, models.size)
                modelInput.showDropDown()
            }.onFailure { error ->
                modelLayout.error = getString(
                    R.string.ai_models_fetch_failed,
                    error.message ?: getString(R.string.ai_request_failed)
                )
            }
        }
    }

    private fun testProfileDraft(
        profile: AiProfile,
        testButton: MaterialButton,
        saveButton: MaterialButton,
        fetchModelsButton: MaterialButton,
        statusCard: MaterialCardView,
        statusText: TextView,
        onResult: (AiTestResult) -> Unit
    ) {
        testButton.isEnabled = false
        saveButton.isEnabled = false
        fetchModelsButton.isEnabled = false
        testButton.setText(R.string.ai_loading)
        statusCard.visibility = View.VISIBLE
        statusCard.strokeColor = resolveColor(com.google.android.material.R.attr.colorOutlineVariant)
        statusCard.setCardBackgroundColor(
            resolveColor(com.google.android.material.R.attr.colorSurfaceContainer)
        )
        statusText.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        statusText.setText(R.string.ai_profile_testing)
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
            if (!testButton.isAttachedToWindow) return@launch
            testButton.isEnabled = true
            saveButton.isEnabled = true
            fetchModelsButton.isEnabled = true
            testButton.setText(R.string.ai_settings_test)
            onResult(testResult)
            showEditorTestResult(
                profile.copy(testResult = testResult),
                statusCard,
                statusText
            )
        }
    }

    private fun showEditorTestResult(
        profile: AiProfile,
        statusCard: MaterialCardView,
        statusText: TextView
    ) {
        val result = profile.testResult
        if (result.status == AiTestStatus.NOT_TESTED) {
            statusCard.visibility = View.GONE
            return
        }
        statusCard.visibility = View.VISIBLE
        val stale = profile.isTestResultStale()
        val colorAttr = when {
            stale -> com.google.android.material.R.attr.colorTertiary
            result.status == AiTestStatus.SUCCESS -> com.google.android.material.R.attr.colorPrimary
            else -> com.google.android.material.R.attr.colorError
        }
        statusCard.strokeColor = resolveColor(colorAttr)
        statusText.setTextColor(resolveColor(colorAttr))
        statusText.text = if (stale) {
            formatTestResult(profile)
        } else {
            getString(
                if (result.status == AiTestStatus.SUCCESS) {
                    R.string.ai_profile_test_success_inline
                } else {
                    R.string.ai_profile_test_failure_inline
                },
                result.durationMillis,
                result.message
            )
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
            cramAnalysisPrompt = cramAnalysisInput.text?.toString().orEmpty(),
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
        val prompts = listOf(quickReviewInput, detailedAnalysisInput, cramAnalysisInput)
        if (prompts.any { it.text?.toString().orEmpty().isBlank() }) {
            Toast.makeText(requireContext(), R.string.ai_settings_required, Toast.LENGTH_SHORT).show()
            return false
        }
        configStore.savePrompts(
            quickReviewPrompt = quickReviewInput.text.toString(),
            analysisPrompt = detailedAnalysisInput.text.toString(),
            techniquePrompt = configStore.techniquePrompt(),
            mnemonicPrompt = configStore.mnemonicPrompt(),
            cramAnalysisPrompt = cramAnalysisInput.text.toString()
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
            cramAnalysisInput = addPromptEditor(
                column,
                context,
                R.string.ai_settings_cram_analysis_prompt,
                AiPromptBuilder.DEFAULT_CRAM_ANALYSIS_PROMPT
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

    private fun selectableItemBackground(context: Context) =
        context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
            try {
                it.getDrawable(0)
            } finally {
                it.recycle()
            }
        }

    private companion object {
        const val PROFILE_ACTION_COPY = 1
        const val PROFILE_ACTION_DELETE = 2
    }
}

package com.virin.visionquiz.quizlibrarylist

import android.animation.AnimatorInflater
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.virin.visionquiz.R
import com.virin.visionquiz.databinding.FragmentImportCandidateSettingsBinding
import com.virin.visionquiz.util.BaseQuizFragment
import com.virin.visionquiz.util.ImportCandidateConfig
import com.virin.visionquiz.util.ImportCandidateSettings
import com.virin.visionquiz.util.configureQuizTopBar
import com.virin.visionquiz.util.dp
import com.virin.visionquiz.util.findImportCandidateOwner
import com.virin.visionquiz.util.refreshQuizTopBarMenu

class ImportCandidateSettingsFragment : BaseQuizFragment() {

    private var _binding: FragmentImportCandidateSettingsBinding? = null
    private val binding get() = _binding!!

    private val groups = mutableListOf<CandidateGroup>()
    private val inputStateRefreshers = mutableListOf<() -> Unit>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImportCandidateSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureQuizTopBar(binding.toolbar, getString(R.string.import_settings_title))
        refreshQuizTopBarMenu(
            binding.toolbar,
            R.menu.import_candidate_settings_menu,
            onMenuItemSelected = ::onTopBarMenuItemSelected
        )
        loadGroups(ImportCandidateSettings.load(requireContext()))
        renderGroups()
    }

    override fun onPause() {
        saveSettings()
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun onTopBarMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.reset_import_candidates -> {
                confirmReset()
                true
            }
            else -> false
        }
    }

    private fun loadGroups(config: ImportCandidateConfig) {
        groups.clear()
        groups += CandidateGroup(getString(R.string.import_settings_prompt_headers), config.promptHeaders.toMutableList())
        groups += CandidateGroup(getString(R.string.import_settings_type_headers), config.typeHeaders.toMutableList())
        groups += CandidateGroup(getString(R.string.import_settings_answer_headers), config.answerHeaders.toMutableList())
        groups += CandidateGroup(getString(R.string.import_settings_option_prefixes), config.optionPrefixes.toMutableList())
        groups += CandidateGroup(getString(R.string.import_settings_analysis_headers), config.analysisHeaders.toMutableList())
        groups += CandidateGroup(getString(R.string.import_settings_single_choice_types), config.singleChoiceTypes.toMutableList())
        groups += CandidateGroup(getString(R.string.import_settings_multiple_choice_types), config.multipleChoiceTypes.toMutableList())
        groups += CandidateGroup(getString(R.string.import_settings_judgement_types), config.judgementTypes.toMutableList())
        groups += CandidateGroup(getString(R.string.import_settings_fill_blank_types), config.fillBlankTypes.toMutableList())
        groups += CandidateGroup(getString(R.string.import_settings_subjective_types), config.subjectiveTypes.toMutableList())
    }

    private fun renderGroups() {
        binding.settingsContainer.removeAllViews()
        inputStateRefreshers.clear()
        groups.forEach { group ->
            addGroupView(group)
        }
    }

    private fun addGroupView(group: CandidateGroup) {
        val titleView = TextView(requireContext()).apply {
            text = group.title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setPadding(0, 14.dp, 0, 8.dp)
        }
        binding.settingsContainer.addView(titleView)

        val chipGroup = ChipGroup(requireContext()).apply {
            isSingleLine = false
            chipSpacingHorizontal = 8.dp
            chipSpacingVertical = 6.dp
        }
        binding.settingsContainer.addView(chipGroup)
        renderChips(group, chipGroup)

        val inputRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.TOP
            isBaselineAligned = false
            setPadding(0, 10.dp, 0, 8.dp)
        }
        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.import_settings_add_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxStrokeWidth = 1.dp
            boxStrokeWidthFocused = 2.dp
            setBoxCornerRadii(
                16.dp.toFloat(),
                16.dp.toFloat(),
                16.dp.toFloat(),
                16.dp.toFloat()
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            minHeight = 56.dp
        }
        inputLayout.addView(input)
        inputRow.addView(inputLayout)

        val addButton = MaterialButton(requireContext()).apply {
            text = getString(R.string.import_settings_add)
            icon = requireContext().getDrawable(R.drawable.twotone_add_24)
            iconPadding = 8.dp
            cornerRadius = 18.dp
            insetTop = 0
            insetBottom = 0
            minHeight = 56.dp
            minWidth = 104.dp
            translationY = 6.dp.toFloat()
            stateListAnimator = AnimatorInflater.loadStateListAnimator(
                requireContext(),
                R.animator.quiz_feature_press_state
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                56.dp
            ).apply {
                marginStart = 10.dp
            }
        }

        fun validationFor(rawText: String): CandidateValidation {
            val value = rawText.trim()
            if (value.isBlank()) return CandidateValidation.Blank
            val owner = findImportCandidateOwner(
                value,
                groups.map { it.title to it.items }
            )
            return if (owner == null) {
                CandidateValidation.Valid(value)
            } else {
                CandidateValidation.Duplicate(value, owner)
            }
        }

        fun refreshInputState() {
            when (val validation = validationFor(input.text?.toString().orEmpty())) {
                CandidateValidation.Blank -> {
                    addButton.isEnabled = false
                    inputLayout.error = null
                }
                is CandidateValidation.Duplicate -> {
                    addButton.isEnabled = false
                    inputLayout.error = getString(
                        R.string.import_settings_duplicate_error,
                        validation.value,
                        validation.owner
                    )
                }
                is CandidateValidation.Valid -> {
                    addButton.isEnabled = true
                    inputLayout.error = null
                }
            }
        }

        fun submitCandidate() {
            val validation = validationFor(input.text?.toString().orEmpty())
            if (validation !is CandidateValidation.Valid) {
                refreshInputState()
                return
            }
            group.items += validation.value
            saveSettings()
            renderChips(group, chipGroup)
            input.text?.clear()
            refreshAllInputStates()
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshInputState()
        })
        input.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            ) {
                submitCandidate()
                true
            } else {
                false
            }
        }
        addButton.setOnClickListener { submitCandidate() }
        inputStateRefreshers += ::refreshInputState
        refreshInputState()
        inputRow.addView(addButton)
        binding.settingsContainer.addView(inputRow)
    }

    private fun renderChips(group: CandidateGroup, chipGroup: ChipGroup) {
        chipGroup.removeAllViews()
        group.items.forEach { item ->
            chipGroup.addView(createChip(group, chipGroup, item))
        }
    }

    private fun createChip(group: CandidateGroup, chipGroup: ChipGroup, item: String): Chip {
        return Chip(requireContext()).apply {
            text = item
            isCloseIconVisible = true
            isCheckable = false
            setOnCloseIconClickListener {
                group.items.remove(item)
                saveSettings()
                renderChips(group, chipGroup)
                refreshAllInputStates()
            }
            setOnClickListener {
                val index = group.items.indexOf(item)
                if (index > 0) {
                    java.util.Collections.swap(group.items, index, index - 1)
                    saveSettings()
                    renderChips(group, chipGroup)
                }
            }
            setOnLongClickListener {
                val index = group.items.indexOf(item)
                if (index < group.items.lastIndex) {
                    java.util.Collections.swap(group.items, index, index + 1)
                    saveSettings()
                    renderChips(group, chipGroup)
                }
                true
            }
        }
    }

    private fun refreshAllInputStates() {
        inputStateRefreshers.forEach { it() }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_settings_reset_title)
            .setMessage(R.string.import_settings_reset_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                ImportCandidateSettings.resetToDefault(requireContext())
                loadGroups(ImportCandidateSettings.load(requireContext()))
                renderGroups()
                Snackbar.make(binding.root, R.string.import_settings_reset_done, Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun saveSettings() {
        if (_binding == null) return
        ImportCandidateSettings.save(
            requireContext(),
            ImportCandidateConfig(
                promptHeaders = groups[0].items,
                typeHeaders = groups[1].items,
                answerHeaders = groups[2].items,
                optionPrefixes = groups[3].items,
                analysisHeaders = groups[4].items,
                singleChoiceTypes = groups[5].items,
                multipleChoiceTypes = groups[6].items,
                judgementTypes = groups[7].items,
                fillBlankTypes = groups[8].items,
                subjectiveTypes = groups[9].items
            )
        )
    }

    private data class CandidateGroup(
        val title: String,
        val items: MutableList<String>
    )

    private sealed interface CandidateValidation {
        data object Blank : CandidateValidation
        data class Valid(val value: String) : CandidateValidation
        data class Duplicate(val value: String, val owner: String) : CandidateValidation
    }
}

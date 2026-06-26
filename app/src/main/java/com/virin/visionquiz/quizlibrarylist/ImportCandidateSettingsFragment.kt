package com.virin.visionquiz.quizlibrarylist

import android.os.Bundle
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
import com.virin.visionquiz.util.refreshQuizTopBarMenu

class ImportCandidateSettingsFragment : BaseQuizFragment() {

    private var _binding: FragmentImportCandidateSettingsBinding? = null
    private val binding get() = _binding!!

    private val groups = mutableListOf<CandidateGroup>()

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

        group.items.forEachIndexed { index, item ->
            chipGroup.addView(createChip(group, index, item))
        }

        val inputRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8.dp, 0, 4.dp)
        }
        val inputLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.import_settings_add_hint)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
                ) {
                    addCandidate(group, text?.toString().orEmpty())
                    text?.clear()
                    true
                } else {
                    false
                }
            }
        }
        inputLayout.addView(input)
        inputRow.addView(inputLayout)

        val addButton = MaterialButton(requireContext()).apply {
            text = getString(R.string.import_settings_add)
            icon = requireContext().getDrawable(R.drawable.twotone_add_24)
            setOnClickListener {
                addCandidate(group, input.text?.toString().orEmpty())
                input.text?.clear()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 8.dp
            }
        }
        inputRow.addView(addButton)
        binding.settingsContainer.addView(inputRow)
    }

    private fun createChip(group: CandidateGroup, index: Int, item: String): Chip {
        return Chip(requireContext()).apply {
            text = item
            isCloseIconVisible = true
            isCheckable = false
            setOnCloseIconClickListener {
                group.items.removeAt(index)
                saveSettings()
                renderGroups()
            }
            setOnClickListener {
                if (index > 0) {
                    java.util.Collections.swap(group.items, index, index - 1)
                    saveSettings()
                    renderGroups()
                }
            }
            setOnLongClickListener {
                if (index < group.items.lastIndex) {
                    java.util.Collections.swap(group.items, index, index + 1)
                    saveSettings()
                    renderGroups()
                }
                true
            }
        }
    }

    private fun addCandidate(group: CandidateGroup, rawText: String) {
        val value = rawText.trim()
        if (value.isBlank()) return
        if (group.items.none { it == value }) {
            group.items += value
            saveSettings()
            renderGroups()
        }
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
}

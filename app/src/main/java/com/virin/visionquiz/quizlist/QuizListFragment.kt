package com.virin.visionquiz.quizlist

import RenameDialogFragment
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizUiType
import com.virin.visionquiz.dao.QuizLibrary
import com.virin.visionquiz.databinding.ActivityQuizListBinding
import com.virin.visionquiz.quizdetector.CameraXDetectorActivity
import com.virin.visionquiz.quizlist.quizcontent.showQuizContentDialog
import com.virin.visionquiz.screendetector.ScreenDetectorController
import com.virin.visionquiz.util.*

@ExperimentalCamera2Interop
class QuizListFragment : BaseQuizFragment() {

    private lateinit var viewModel: QuizListViewModel

    private var _binding: ActivityQuizListBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private var isSearchFieldFocused = false
    private var isFilterDialogShowing = false

    override fun onResume() {
        super.onResume()
        ScreenDetectorController.onHostResumed(requireActivity())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = ActivityQuizListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = QuizListAdapter(onItemClickListBtnListener)
        binding.recyclerView.adapter = adapter

        val adapter2 = QuizListAdapter(onItemClickListBtnListener)
        binding.filteredRecyclerView.adapter = adapter2

        // Get the ViewModel
        val libId = arguments?.getInt(LIBRARY_ID)
        if (libId != null) {
            viewModel = ViewModelProvider(
                this, QuizListViewModel.factory(requireActivity().application, libId)
            )[QuizListViewModel::class.java]
        } else {
            Toast.makeText(requireContext(), "Library ID not found.", Toast.LENGTH_SHORT).show()
        }
        binding.viewModel = viewModel
        configureQuizTopBar(binding.toolbar, viewModel.library.value?.name ?: "浏览题目")
        binding.toolbar.menu.clear()

        fun updateSearchHighlight() {
            val mode = viewModel.currentSearchMode.value ?: QuizSearchMode.KEYWORD
            val query = viewModel.currentSearchQuery.value.orEmpty()
            val scope = viewModel.currentKeywordScope.value ?: KeywordSearchScope()
            adapter2.setKeywordHighlightConfig(
                QuizListAdapter.KeywordHighlightConfig(
                    enabled = mode == QuizSearchMode.KEYWORD,
                    query = query,
                    scope = scope
                )
            )
        }

        viewModel.quizList.observe(viewLifecycleOwner) { list ->
            val visibleList = viewModel.displayQuizList.value ?: list ?: emptyList()
            binding.emptyLl.visibility = if (visibleList.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.quizTypeStats.observe(viewLifecycleOwner) { stats ->
            binding.totalCountTv.text = stats.total.toString()
            binding.singleChoiceCountTv.text = stats.singleChoice.toString()
            binding.multipleChoiceCountTv.text = stats.multipleChoice.toString()
            binding.judgementCountTv.text = stats.judgement.toString()
            binding.fillBlankCountTv.text = stats.fillBlank.toString()
            binding.subjectiveCountTv.text = stats.subjective.toString()
        }

        viewModel.displayQuizList.observe(viewLifecycleOwner) { list ->
            binding.emptyLl.visibility = if (list.isNullOrEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.selectedTypeFilter.observe(viewLifecycleOwner) { selectedType ->
            updateTypeFilterCards(selectedType)
            updateSearchFieldState(
                selectedType,
                viewModel.currentSearchQuery.value.orEmpty(),
                viewModel.currentSearchMode.value ?: QuizSearchMode.KEYWORD,
                viewModel.currentKeywordScope.value ?: KeywordSearchScope()
            )
        }

        viewModel.currentSearchQuery.observe(viewLifecycleOwner) { query ->
            if (binding.filledEditText.text?.toString() != query) {
                binding.filledEditText.setText(query)
                binding.filledEditText.setSelection(query.length)
            }
            updateSearchHighlight()
            updateSearchFieldState(
                viewModel.selectedTypeFilter.value,
                query.orEmpty(),
                viewModel.currentSearchMode.value ?: QuizSearchMode.KEYWORD,
                viewModel.currentKeywordScope.value ?: KeywordSearchScope()
            )
        }

        viewModel.currentSearchMode.observe(viewLifecycleOwner) { mode ->
            updateSearchHighlight()
            updateSearchFieldState(
                viewModel.selectedTypeFilter.value,
                viewModel.currentSearchQuery.value.orEmpty(),
                mode,
                viewModel.currentKeywordScope.value ?: KeywordSearchScope()
            )
        }

        viewModel.currentKeywordScope.observe(viewLifecycleOwner) { scope ->
            updateSearchHighlight()
            updateSearchFieldState(
                viewModel.selectedTypeFilter.value,
                viewModel.currentSearchQuery.value.orEmpty(),
                viewModel.currentSearchMode.value ?: QuizSearchMode.KEYWORD,
                scope
            )
        }

        viewModel.library.observe(viewLifecycleOwner) { lib ->
            binding.toolbar.title = lib?.name ?: "未知题库"
        }

        viewModel.filteredQuizList.observe(viewLifecycleOwner) { filteredList ->
            binding.filterEmptyLl.visibility =
                if (filteredList?.isEmpty() == true) View.VISIBLE else View.GONE
        }

        binding.filledEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
                val input = s.toString()

                binding.filteredRecyclerView.visibility =
                    if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                binding.recyclerView.visibility =
                    if (s.isNullOrEmpty()) View.VISIBLE else View.INVISIBLE

                viewModel.questionFilter(input)
            }

        })

        binding.filledTextField.setStartIconOnClickListener { showSearchFilterDialog() }

        binding.filledTextField.clearFocusWhenScrollBegin(binding.recyclerView)
        binding.filledTextField.clearFocusWhenScrollBegin(binding.filteredRecyclerView)
        binding.filledEditText.onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
            isSearchFieldFocused = hasFocus
            refreshSearchFieldState()
            if (!hasFocus) {
                val imm = requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }

        binding.totalCard.setOnClickListener { viewModel.toggleTypeFilter(null) }
        binding.singleChoiceCard.setOnClickListener { viewModel.toggleTypeFilter(QuizUiType.SINGLE_CHOICE) }
        binding.multipleChoiceCard.setOnClickListener { viewModel.toggleTypeFilter(QuizUiType.MULTIPLE_CHOICE) }
        binding.judgementCard.setOnClickListener { viewModel.toggleTypeFilter(QuizUiType.JUDGEMENT) }
        binding.fillBlankCard.setOnClickListener { viewModel.toggleTypeFilter(QuizUiType.FILL_BLANK) }
        binding.subjectiveCard.setOnClickListener { viewModel.toggleTypeFilter(QuizUiType.SUBJECTIVE) }


        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            // 获取状态栏和导航栏的WindowInsets
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 获取输入法窗口的WindowInsets
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())

//            Log.e(TAG, "systemBarsInsets $systemBarsInsets")
//            Log.e(TAG, "imeInsets $imeInsets")
//            Log.e(TAG, "mandatorySystemGesturesInsets $mandatorySystemGesturesInsets")

//            E/QuizListFragment: systemBarsInsets Insets{left=0, top=82, right=0, bottom=39}
//            E/QuizListFragment: imeInsets Insets{left=0, top=0, right=0, bottom=0}
//            E/QuizListFragment: mandatorySystemGesturesInsets Insets{left=0, top=82, right=0, bottom=84}
//            keyboard...
//            E/QuizListFragment: systemBarsInsets Insets{left=0, top=82, right=0, bottom=39}
//            E/QuizListFragment: imeInsets Insets{left=0, top=0, right=0, bottom=900}
//            E/QuizListFragment: mandatorySystemGesturesInsets Insets{left=0, top=82, right=0, bottom=84}
//            pip
//            E/QuizListFragment: systemBarsInsets Insets{left=0, top=0, right=0, bottom=0}
//            E/QuizListFragment: imeInsets Insets{left=0, top=0, right=0, bottom=0}
//            E/QuizListFragment: mandatorySystemGesturesInsets Insets{left=0, top=0, right=0, bottom=2}

            val bottomInset =
                if (imeInsets.bottom > 0) imeInsets.bottom else systemBarsInsets.bottom
            if (binding.contentLl.paddingBottom != bottomInset) {
                binding.contentLl.setPadding(
                    binding.contentLl.paddingLeft,
                    binding.contentLl.paddingTop,
                    binding.contentLl.paddingRight,
                    bottomInset
                )
            }

            insets
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private val onItemClickListBtnListener = object : QuizListAdapter.OnQuizClickListener {
        override fun onQuizClicked(quiz: Quiz) {
            val visibleList = if (binding.filteredRecyclerView.visibility == View.VISIBLE) {
                viewModel.filteredQuizList.value ?: emptyList()
            } else {
                viewModel.displayQuizList.value ?: emptyList()
            }
            val quizIndex = visibleList.indexOfFirst { it.id == quiz.id }
            val allQuizzes = viewModel.quizList.value.orEmpty()
            if (quizIndex >= 0) {
                showQuizContentDialog(requireContext(), visibleList, quizIndex, allQuizzes)
            } else {
                showQuizContentDialog(requireContext(), quiz, allQuizzes)
            }
        }
    }

    private fun updateTypeFilterCards(selectedType: QuizUiType?) {
        updateStatsCard(
            binding.totalCard,
            isSelected = selectedType == null,
            valueView = binding.totalCountTv,
            palette = totalCardPalette()
        )
        updateStatsCard(
            binding.singleChoiceCard,
            isSelected = selectedType == QuizUiType.SINGLE_CHOICE,
            valueView = binding.singleChoiceCountTv,
            palette = typeCardPalette(QuizUiType.SINGLE_CHOICE)
        )
        updateStatsCard(
            binding.multipleChoiceCard,
            isSelected = selectedType == QuizUiType.MULTIPLE_CHOICE,
            valueView = binding.multipleChoiceCountTv,
            palette = typeCardPalette(QuizUiType.MULTIPLE_CHOICE)
        )
        updateStatsCard(
            binding.judgementCard,
            isSelected = selectedType == QuizUiType.JUDGEMENT,
            valueView = binding.judgementCountTv,
            palette = typeCardPalette(QuizUiType.JUDGEMENT)
        )
        updateStatsCard(
            binding.fillBlankCard,
            isSelected = selectedType == QuizUiType.FILL_BLANK,
            valueView = binding.fillBlankCountTv,
            palette = typeCardPalette(QuizUiType.FILL_BLANK)
        )
        updateStatsCard(
            binding.subjectiveCard,
            isSelected = selectedType == QuizUiType.SUBJECTIVE,
            valueView = binding.subjectiveCountTv,
            palette = typeCardPalette(QuizUiType.SUBJECTIVE)
        )
    }

    private fun updateStatsCard(
        card: MaterialCardView,
        isSelected: Boolean,
        valueView: android.widget.TextView,
        palette: StatsCardPalette
    ) {
        val backgroundColor = if (isSelected) palette.selectedBackground else palette.background
        val strokeColor = if (isSelected) palette.selectedStroke else palette.stroke
        val valueColor = if (isSelected) palette.selectedValue else palette.value
        val labelColor = if (isSelected) palette.selectedLabel else palette.label

        card.setCardBackgroundColor(backgroundColor)
        card.strokeColor = strokeColor
        valueView.setTextColor(valueColor)
        val labelView = (card.getChildAt(0) as ViewGroup).getChildAt(0) as android.widget.TextView
        labelView.setTextColor(labelColor)
        card.strokeWidth = if (isSelected) 3.dp else 1.dp
        card.cardElevation = if (isSelected) 2.dp.toFloat() else 0f
    }

    private fun totalCardPalette(): StatsCardPalette {
        val surfaceVariant = MaterialColors.getColor(requireView(), R.attr.colorSurfaceVariant)
        val outlineVariant = MaterialColors.getColor(requireView(), R.attr.colorOutlineVariant)
        val onSurface = MaterialColors.getColor(requireView(), R.attr.colorOnSurface)
        val onSurfaceVariant = MaterialColors.getColor(requireView(), R.attr.colorOnSurfaceVariant)
        val selectedBackground =
            MaterialColors.getColor(requireView(), R.attr.colorPrimaryContainer)
        val selectedStroke = MaterialColors.getColor(requireView(), R.attr.colorPrimary)
        val selectedText = MaterialColors.getColor(requireView(), R.attr.colorOnPrimaryContainer)
        return StatsCardPalette(
            background = surfaceVariant,
            stroke = outlineVariant,
            label = onSurfaceVariant,
            value = onSurface,
            selectedBackground = selectedBackground,
            selectedStroke = selectedStroke,
            selectedLabel = selectedText,
            selectedValue = selectedText
        )
    }

    private fun typeCardPalette(type: QuizUiType): StatsCardPalette {
        val surface = MaterialColors.getColor(requireView(), R.attr.colorSurface)
        val outlineVariant = MaterialColors.getColor(requireView(), R.attr.colorOutlineVariant)
        val onSurfaceVariant = MaterialColors.getColor(requireView(), R.attr.colorOnSurfaceVariant)
        val (containerAttr, onContainerAttr, accentAttr) = when (type) {
            QuizUiType.SINGLE_CHOICE -> Triple(
                R.attr.colorPrimaryContainer,
                R.attr.colorOnPrimaryContainer,
                R.attr.colorPrimary
            )

            QuizUiType.MULTIPLE_CHOICE -> Triple(
                R.attr.colorSecondaryContainer,
                R.attr.colorOnSecondaryContainer,
                R.attr.colorSecondary
            )

            QuizUiType.JUDGEMENT -> Triple(
                R.attr.colorTertiaryContainer,
                R.attr.colorOnTertiaryContainer,
                R.attr.colorTertiary
            )

            QuizUiType.FILL_BLANK -> Triple(
                R.attr.colorErrorContainer,
                R.attr.colorOnErrorContainer,
                R.attr.colorError
            )

            QuizUiType.SUBJECTIVE -> Triple(
                R.attr.colorSurfaceContainerHighest,
                R.attr.colorOnSurfaceVariant,
                R.attr.colorOutline
            )
        }

        val container = MaterialColors.getColor(requireView(), containerAttr)
        val onContainer = MaterialColors.getColor(requireView(), onContainerAttr)
        val accent = MaterialColors.getColor(requireView(), accentAttr)
        return StatsCardPalette(
            background = ColorUtils.blendARGB(surface, container, 0.58f),
            stroke = ColorUtils.blendARGB(outlineVariant, accent, 0.5f),
            label = ColorUtils.blendARGB(onSurfaceVariant, onContainer, 0.58f),
            value = ColorUtils.blendARGB(onSurfaceVariant, onContainer, 0.82f),
            selectedBackground = container,
            selectedStroke = accent,
            selectedLabel = onContainer,
            selectedValue = onContainer
        )
    }

    private fun updateSearchFieldState(
        selectedType: QuizUiType?,
        query: String,
        searchMode: QuizSearchMode,
        keywordScope: KeywordSearchScope
    ) {
        val parts = mutableListOf<String>()
        selectedType?.let { parts += it.label }
        when (searchMode) {
            QuizSearchMode.FUZZY -> {
                if (query.isNotBlank()) {
                    parts += "近似"
                }
            }

            QuizSearchMode.KEYWORD -> {
                parts += "关键词"
                parts += keywordScope.label()
            }
        }

        binding.filledTextField.prefixText = null

        val hasActiveFilter = parts.isNotEmpty() || query.isNotBlank()
        val shouldShowHelperText = hasActiveFilter && (isSearchFieldFocused || isFilterDialogShowing)
        binding.filledTextField.isHelperTextEnabled = shouldShowHelperText
        binding.filledTextField.helperText = if (shouldShowHelperText) {
            "筛选条件：${parts.joinToString(" · ")}"
        } else {
            null
        }

        val iconColorAttr = if (hasActiveFilter) {
            R.attr.colorPrimary
        } else {
            R.attr.colorOnSurfaceVariant
        }
        binding.filledTextField.setStartIconTintList(
            ColorStateList.valueOf(MaterialColors.getColor(requireView(), iconColorAttr))
        )

        if (!hasActiveFilter) {
            binding.filterEmptyTv.text = "无结果"
            return
        }

        binding.filterEmptyTv.text = "当前筛选下无结果"
    }

    private fun refreshSearchFieldState() {
        updateSearchFieldState(
            viewModel.selectedTypeFilter.value,
            viewModel.currentSearchQuery.value.orEmpty(),
            viewModel.currentSearchMode.value ?: QuizSearchMode.KEYWORD,
            viewModel.currentKeywordScope.value ?: KeywordSearchScope()
        )
    }

    private fun showSearchFilterDialog() {
        isFilterDialogShowing = true
        refreshSearchFieldState()

        val context = requireContext()
        val currentMode = viewModel.currentSearchMode.value ?: QuizSearchMode.KEYWORD
        val currentScope = viewModel.currentKeywordScope.value ?: KeywordSearchScope()
        val fuzzyButtonId = View.generateViewId()
        val keywordButtonId = View.generateViewId()

        val contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
        }

        contentView.addView(createDialogSectionTitle("搜索方式"))
        val modeGroup = MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val keywordButton = createDialogToggleButton("关键词").apply { id = keywordButtonId }
        val fuzzyButton = createDialogToggleButton("近似").apply { id = fuzzyButtonId }
        modeGroup.addView(keywordButton)
        modeGroup.addView(fuzzyButton)
        modeGroup.check(
            when (currentMode) {
                QuizSearchMode.FUZZY -> fuzzyButtonId
                QuizSearchMode.KEYWORD -> keywordButtonId
            }
        )
        contentView.addView(modeGroup)

        val fuzzyDescription = TextView(context).apply {
            text = "近似会按题目语义和相似度匹配，适合文字有识别误差或表达不完全一致的情况。"
            setTextColor(MaterialColors.getColor(requireView(), R.attr.colorOnSurfaceVariant))
            textSize = 13f
            setPadding(0, 8.dp, 0, 0)
            visibility = if (currentMode == QuizSearchMode.FUZZY) View.VISIBLE else View.GONE
        }
        contentView.addView(fuzzyDescription)

        val scopeContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16.dp, 0, 0)
        }
        scopeContainer.addView(createDialogSectionTitle("关键词范围"))
        val scopeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val promptCheckBox = MaterialCheckBox(context).apply {
            text = "题目"
            isChecked = currentScope.includePrompt
        }
        val answerCheckBox = MaterialCheckBox(context).apply {
            text = "答案"
            isChecked = currentScope.includeAnswers
        }
        var updatingScopeChecks = false
        scopeRow.addView(promptCheckBox)
        scopeRow.addView(answerCheckBox)
        scopeContainer.addView(scopeRow)
        contentView.addView(scopeContainer)

        fun updateScopeVisibility(checkedButtonId: Int) {
            scopeContainer.visibility =
                if (checkedButtonId == keywordButtonId) View.VISIBLE else View.GONE
            fuzzyDescription.visibility =
                if (checkedButtonId == fuzzyButtonId) View.VISIBLE else View.GONE
        }
        updateScopeVisibility(modeGroup.checkedButtonId)
        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            updateScopeVisibility(checkedId)
            val selectedMode = if (checkedId == keywordButtonId) {
                QuizSearchMode.KEYWORD
            } else {
                QuizSearchMode.FUZZY
            }
            viewModel.setSearchMode(selectedMode)
        }
        promptCheckBox.setOnCheckedChangeListener { button, _ ->
            if (updatingScopeChecks) return@setOnCheckedChangeListener
            if (!promptCheckBox.isChecked && !answerCheckBox.isChecked) {
                updatingScopeChecks = true
                button.isChecked = true
                updatingScopeChecks = false
                return@setOnCheckedChangeListener
            }
            viewModel.setKeywordPromptScope(promptCheckBox.isChecked)
        }
        answerCheckBox.setOnCheckedChangeListener { button, _ ->
            if (updatingScopeChecks) return@setOnCheckedChangeListener
            if (!promptCheckBox.isChecked && !answerCheckBox.isChecked) {
                updatingScopeChecks = true
                button.isChecked = true
                updatingScopeChecks = false
                return@setOnCheckedChangeListener
            }
            viewModel.setKeywordAnswerScope(answerCheckBox.isChecked)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("筛选条件")
            .setView(contentView)
            .setPositiveButton("确定", null)
            .show()
        dialog.setOnDismissListener {
            isFilterDialogShowing = false
            refreshSearchFieldState()
        }
    }

    private fun createDialogSectionTitle(text: String): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            setTextColor(MaterialColors.getColor(requireView(), R.attr.colorOnSurfaceVariant))
            textSize = 13f
            setPadding(0, 0, 0, 8.dp)
        }
    }

    private fun createDialogToggleButton(text: String): MaterialButton {
        return MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            this.text = text
            isCheckable = true
            minWidth = 88.dp
        }
    }

    private data class StatsCardPalette(
        val background: Int,
        val stroke: Int,
        val label: Int,
        val value: Int,
        val selectedBackground: Int,
        val selectedStroke: Int,
        val selectedLabel: Int,
        val selectedValue: Int
    )

    @SuppressLint("RestrictedApi")
    private fun prepareTopBarMenu(menu: Menu) {
        val colorOnSurfaceVariant =
            MaterialColors.getColor(requireView(), R.attr.colorOnSurfaceVariant)
        listOfNotNull(
            menu.findItem(R.id.camera),
            menu.findItem(R.id.screen),
            menu.findItem(R.id.more),
            menu.findItem(R.id.share),
            menu.findItem(R.id.rename)
        ).forEach {
            it.iconTintList = ColorStateList.valueOf(colorOnSurfaceVariant)
            it.title = SpannableString(it.title).apply {
                setSpan(
                    ForegroundColorSpan(colorOnSurfaceVariant),
                    0,
                    it.title?.length ?: 0,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        menu.findItem(R.id.delete)?.apply {
            val error = MaterialColors.getColor(requireView(), R.attr.colorError)
            iconTintList = ColorStateList.valueOf(error)
            title = SpannableString(title).apply {
                setSpan(ForegroundColorSpan(error), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun onTopBarMenuItemSelected(menuItem: MenuItem): Boolean {
//        Snackbar.make(requireView(), "menuItem ${menuItem.title}", Snackbar.LENGTH_LONG)
//            .setAction("Action", null).show()

        when (menuItem.itemId) {
            R.id.camera -> {
                // 启动摄像头
                val libId = viewModel.library.value?.id
                val intent = Intent(
                    requireActivity(), CameraXDetectorActivity::class.java
                )
                intent.putExtra(CameraXDetectorActivity.LIBRARY_ID, libId!!)
                startActivity(intent)
            }

            R.id.screen -> {
                // 屏幕录制
                val libId = viewModel.library.value?.id
                ScreenDetectorController.startQuizDetection(
                    requireActivity(),
                    libId!!,
                    viewModel.quizList
                )
            }

            R.id.rename -> {
                val lib = viewModel.library.value

                if (lib != null) {
                    RenameDialogFragment(
                        "重新命名",
                        lib.name,
                        object : RenameDialogFragment.RenameDialogListener {
                            override fun onDialogPositiveClick(newName: String) {
                                val newLib = QuizLibrary(lib.id, newName, lib.quizCount)
                                viewModel.updateQuizLibrary(newLib)
                            }
                        }).show(parentFragmentManager)
                }
            }

            R.id.delete -> {
                val lib = viewModel.library.value
                if (lib != null) {
                    MaterialAlertDialogBuilder(requireContext()).setTitle("删除题库“${lib.name}”？")
                        .setPositiveButton(R.string.delete) { dialog, which ->
                            // 删除题目和题库
                            viewModel.deleteQuizLibrary(lib)
                            findNavController().popBackStack()
                            dialog.dismiss() // 关闭对话框
                        }.setNegativeButton(R.string.cancel) { dialog, which ->
                            // 点击“取消”按钮后执行的操作
                            dialog.dismiss() // 关闭对话框
                        }.show()

                }
            }
            // 分享菜单
            R.id.share -> {
                var selectedFileType: QuizExportUtil.FileType? = null
                val fileTypeItems =
                    QuizExportUtil.FileType.values().map { it.displayName }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext()).setTitle(resources.getString(R.string.export_as))
//                    .setMessage(resources.getString(R.string.select_excel_format_to_export))
                    .setSingleChoiceItems(
                        fileTypeItems, selectedFileType?.getIndex() ?: -1
                    ) { dialog, which ->
                        selectedFileType = QuizExportUtil.FileType.values()[which]
                    }.setNegativeButton(resources.getString(R.string.cancel)) { dialog, which ->
                        // Respond to negative button press
                    }.setPositiveButton(resources.getString(R.string.export)) { dialog, which ->
                        // Respond to positive button press
                        if (selectedFileType != null && viewModel.library.value != null && viewModel.quizList.value != null) {
                            when (selectedFileType) {
                                QuizExportUtil.FileType.DOCX -> {
                                    runIO {
                                        try {
                                            QuizExportUtil.createAndSaveWordFile(
                                                viewModel.library.value!!,
                                                viewModel.quizList.value!!
                                            ).also { targetPath ->
                                                runMain {
                                                    Snackbar.make(
                                                        requireView(),
                                                        "文件已保存至 $targetPath",
                                                        Snackbar.LENGTH_LONG
                                                    ).setTextMaxLines(3)
                                                        .setAction(R.string.open) { view ->
                                                            QuizExportUtil.openWordFile(
                                                                targetPath,
                                                                requireContext()
                                                            )
                                                        }.show()
                                                }
                                            }
                                        } catch (err: Exception) {
                                            runMain {
                                                MaterialAlertDialogBuilder(requireContext())
                                                    .setTitle("导出失败")
                                                    .setMessage(err.toString())
                                                    .setPositiveButton(R.string.confirm) { button, which -> }
                                                    .show()
                                            }
                                        }
                                    }
                                }

                                QuizExportUtil.FileType.XLS_ANGUI_FOR_ANDROID, QuizExportUtil.FileType.XLSX_ANGUI_FOR_IOS -> {
                                    runIO {
                                        try {
                                            val isXLSX =
                                                selectedFileType == QuizExportUtil.FileType.XLSX_ANGUI_FOR_IOS
                                            QuizExportUtil.createAndSaveExcel4AnGui(
                                                isXLSX,
                                                viewModel.library.value!!,
                                                viewModel.quizList.value!!
                                            ).also { targetPath ->
                                                runMain {
                                                    Snackbar.make(
                                                        requireView(),
                                                        "文件已保存至 $targetPath",
                                                        Snackbar.LENGTH_LONG
                                                    ).setTextMaxLines(3)
                                                        .setAction(R.string.open) { view ->
                                                            if (isXLSX) {
                                                                QuizExportUtil.openXLSXFile(
                                                                    targetPath,
                                                                    requireContext()
                                                                )
                                                            } else {
                                                                QuizExportUtil.openXLSFile(
                                                                    targetPath,
                                                                    requireContext()
                                                                )
                                                            }
                                                        }.show()
                                                }
                                            }
                                        } catch (err: Exception) {
                                            runMain {
                                                err.printStackTrace()
                                                MaterialAlertDialogBuilder(requireContext())
                                                    .setTitle("导出失败")
                                                    .setMessage(err.toString())
                                                    .setPositiveButton(R.string.confirm) { button, which -> }
                                                    .show()
                                            }
                                        }
                                    }
                                }

                                QuizExportUtil.FileType.XLSX_KUAISOU -> {
                                    runIO {
                                        try {
                                            QuizExportUtil.createAndSaveExcel4KuaiSou(
                                                viewModel.library.value!!,
                                                viewModel.quizList.value!!
                                            ).also { targetPath ->
                                                runMain {
                                                    Snackbar.make(
                                                        requireView(),
                                                        "文件已保存至 $targetPath",
                                                        Snackbar.LENGTH_LONG
                                                    ).setTextMaxLines(3)
                                                        .setAction(R.string.open) { view ->
                                                            QuizExportUtil.openXLSXFile(
                                                                targetPath,
                                                                requireContext()
                                                            )
                                                        }.show()
                                                }
                                            }
                                        } catch (err: Exception) {
                                            runMain {
                                                err.printStackTrace()
                                                MaterialAlertDialogBuilder(requireContext())
                                                    .setTitle("导出失败")
                                                    .setMessage(err.toString())
                                                    .setPositiveButton(R.string.confirm) { button, which -> }
                                                    .show()
                                            }
                                        }
                                    }
                                }

                                QuizExportUtil.FileType.XLSX_MTB -> {
                                    runIO {
                                        try {
                                            QuizExportUtil.createAndSaveExcel4MTB(
                                                viewModel.library.value!!,
                                                viewModel.quizList.value!!
                                            ).also { targetPath ->
                                                runMain {
                                                    Snackbar.make(
                                                        requireView(),
                                                        "文件已保存至 $targetPath",
                                                        Snackbar.LENGTH_LONG
                                                    ).setTextMaxLines(3)
                                                        .setAction(R.string.open) { view ->
                                                            QuizExportUtil.openXLSXFile(
                                                                targetPath,
                                                                requireContext()
                                                            )
                                                        }.show()
                                                }
                                            }
                                        } catch (err: Exception) {
                                            runMain {
                                                err.printStackTrace()
                                                MaterialAlertDialogBuilder(requireContext())
                                                    .setTitle("导出失败")
                                                    .setMessage(err.toString())
                                                    .setPositiveButton(R.string.confirm) { button, which -> }
                                                    .show()
                                            }
                                        }
                                    }
                                }

                            }
                        } else {
                            Toast.makeText(requireContext(), "未选择导出类型", Toast.LENGTH_LONG)
                                .show()
//                            Toast.makeText(requireContext(), "导出失败，请重试。", Toast.LENGTH_SHORT).show()
                        }
                    }.show()
            }
        }

        return true
    }

    companion object {
        private const val TAG = "QuizListFragment"

        public const val LIBRARY_ID = "LibraryId"

        @JvmStatic
        fun newInstance(libraryId: Int) = QuizListFragment().apply {
            arguments = Bundle().apply {
                putInt(LIBRARY_ID, libraryId)
            }
        }
    }
}

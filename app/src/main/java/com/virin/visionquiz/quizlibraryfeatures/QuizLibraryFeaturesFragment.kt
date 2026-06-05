package com.virin.visionquiz.quizlibraryfeatures

import RenameDialogFragment
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Toast
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.setPadding
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.snackbar.Snackbar
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizLibrary
import com.virin.visionquiz.dao.QuizStudyMode
import com.virin.visionquiz.databinding.FragmentQuizLibraryDetailBinding
import com.virin.visionquiz.quizdetector.CameraXDetectorActivity
import com.virin.visionquiz.quizlist.QuizListFragment
import com.virin.visionquiz.quizstudy.LibraryAnswerStats
import com.virin.visionquiz.quizstudy.QuizFavoritesFragment
import com.virin.visionquiz.quizstudy.QuizLibraryFeaturesViewModel
import com.virin.visionquiz.quizstudy.QuizRunnerFragment
import com.virin.visionquiz.screendetector.ScreenDetectorController
import com.virin.visionquiz.util.BaseQuizFragment
import com.virin.visionquiz.util.QuizExportUtil
import com.virin.visionquiz.util.applyCollapsingQuizTopBarInsets
import com.virin.visionquiz.util.configureQuizTopBar
import com.virin.visionquiz.util.refreshQuizTopBarMenu
import com.virin.visionquiz.util.runIO
import com.virin.visionquiz.util.runMain
import com.virin.visionquiz.util.tintQuizMenuItems

class QuizLibraryFeaturesFragment : BaseQuizFragment() {

    private var _binding: FragmentQuizLibraryDetailBinding? = null
    private val binding get() = _binding!!
    private val libraryId: Int
        get() = requireArguments().getInt(LIBRARY_ID)

    private val viewModel: QuizLibraryFeaturesViewModel by viewModels {
        QuizLibraryFeaturesViewModel.factory(requireActivity().application, libraryId)
    }
    private var pendingExportFile: QuizExportUtil.ExportFile? = null
    private var exportProgressDialog: AlertDialog? = null
    private val exportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val exportFile = pendingExportFile
        pendingExportFile = null
        if (result.resultCode != Activity.RESULT_OK || exportFile == null) return@registerForActivityResult

        val uri = result.data?.data
        if (uri == null) {
            Toast.makeText(requireContext(), "未选择保存位置", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        runIO {
            try {
                QuizExportUtil.writeExportFileToUri(requireContext(), exportFile, uri)
                runMain {
                    dismissExportProgress()
                    _binding?.root?.let { root ->
                        Snackbar.make(root, "文件已保存", Snackbar.LENGTH_LONG).show()
                    }
                }
            } catch (err: Exception) {
                runMain {
                    dismissExportProgress()
                    showExportError(err)
                }
            }
        }
    }
    private var currentAppBarOffset = 0
    private var savedAppBarOffset = 0
    private var libraryTitle: CharSequence = "题库"
    private val appBarOffsetListener = AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
        currentAppBarOffset = verticalOffset
        updateFeatureTitleTransition(appBarLayout.totalScrollRange, verticalOffset)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuizLibraryDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureQuizTopBar(binding.toolbar, "", applyStatusBarInset = false)
        refreshQuizTopBarMenu(
            binding.toolbar,
            R.menu.quiz_library_features_menu,
            onPrepareMenu = {
                binding.toolbar.tintQuizMenuItems(errorItemIds = setOf(R.id.delete))
            },
            onMenuItemSelected = ::onTopBarMenuItemSelected
        )
        applyStatusBarScrimInsets()
        binding.cameraButton.setOnClickListener {
            startActivity(
                Intent(requireActivity(), CameraXDetectorActivity::class.java).apply {
                    putExtra(CameraXDetectorActivity.LIBRARY_ID, libraryId)
                }
            )
        }
        binding.screenRecordButton.setOnClickListener {
            ScreenDetectorController.startQuizDetection(
                requireActivity(),
                libraryId,
                viewModel.quizList
            )
        }
        binding.accessibilitySearchButton.setOnClickListener {
            ScreenDetectorController.startAccessibilityQuizDetection(
                requireActivity(),
                libraryId,
                viewModel.quizList
            )
        }
        binding.appBar.addOnOffsetChangedListener(appBarOffsetListener)
        binding.appBar.doOnLayout {
            restoreAppBarOffset()
            updateFeatureTitleTransition()
        }

        val features = listOf(
            StudyFeature("顺序背题", "按题库顺序练习支持题型", R.drawable.icon_list_arrow_24px, FeatureAction.ORDERED_PRACTICE),
            StudyFeature("随机背题", "随机顺序练习支持题型", R.drawable.icon_shuffle_24px, FeatureAction.RANDOM_PRACTICE),
            StudyFeature("模拟考试", "按题型配置数量并随机组卷", R.drawable.icon_science_24px, FeatureAction.EXAM),
            StudyFeature("我的收藏", "查看已收藏题目", R.drawable.icon_bookmarks_24px, FeatureAction.FAVORITES),
            StudyFeature("错题库", "查看尚未巩固的错题", R.drawable.icon_error_24px, FeatureAction.WRONG),
            StudyFeature("作答历史", "查看每次背题和考试记录", R.drawable.icon_history_edu_24px, FeatureAction.HISTORY),
            StudyFeature("考试历史", "回顾每场考试成绩和逐题详情", R.drawable.icon_experiment_24px, FeatureAction.EXAM_HISTORY),
            StudyFeature("题目列表", "查看和搜索全部题型", R.drawable.round_more_horiz_24, FeatureAction.QUIZ_LIST)
        )
        val adapter = QuizLibFeaturesAdapter(features, LibraryAnswerStats()) { feature ->
            handleFeatureClick(feature)
        }
        binding.studyFeaturesRecyclerView.layoutManager = GridLayoutManager(requireContext(), FEATURE_GRID_SPAN_COUNT).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (adapter.isFullSpan(position)) FEATURE_GRID_SPAN_COUNT else 1
                }
            }
        }
        binding.studyFeaturesRecyclerView.adapter = adapter

        viewModel.library.observe(viewLifecycleOwner) { library ->
            libraryTitle = library?.name ?: "题库"
            binding.libraryTitleText.text = libraryTitle
            updateFeatureTitleTransition()
        }
        viewModel.answerStats.observe(viewLifecycleOwner) { stats ->
            adapter.updateAnswerStats(stats)
        }
        viewModel.stats.observe(viewLifecycleOwner) { stats ->
            binding.libraryStatsText.text =
                "共 ${stats.total} 题 · 单选 ${stats.singleChoice} · 多选 ${stats.multipleChoice} · 判断 ${stats.judgement}"
        }
    }

    override fun onResume() {
        super.onResume()
        ScreenDetectorController.onHostResumed(requireActivity())
    }

    private fun applyStatusBarScrimInsets() {
        val baseFeatureListPaddingBottom = binding.studyFeaturesRecyclerView.paddingBottom
        binding.root.applyCollapsingQuizTopBarInsets(
            collapsingToolbar = binding.collapsingToolbar,
            toolbar = binding.toolbar,
            statusBarScrim = binding.statusBarScrim,
            header = binding.quizLibraryAppBarHeader
        ) { insets ->
            val navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val featureListPaddingBottom = baseFeatureListPaddingBottom + navigationBottom
            if (binding.studyFeaturesRecyclerView.paddingBottom != featureListPaddingBottom) {
                binding.studyFeaturesRecyclerView.setPadding(
                    binding.studyFeaturesRecyclerView.paddingLeft,
                    binding.studyFeaturesRecyclerView.paddingTop,
                    binding.studyFeaturesRecyclerView.paddingRight,
                    featureListPaddingBottom
                )
            }
        }
    }

    override fun onPause() {
        savedAppBarOffset = currentAppBarOffset
        super.onPause()
    }

    override fun onDestroyView() {
        savedAppBarOffset = currentAppBarOffset
        binding.appBar.removeOnOffsetChangedListener(appBarOffsetListener)
        super.onDestroyView()
        dismissExportProgress()
        _binding = null
    }

    private fun restoreAppBarOffset() {
        val offset = savedAppBarOffset
        if (offset == 0) return
        binding.appBar.post {
            if (_binding == null) return@post
            val layoutParams = binding.appBar.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            val behavior = layoutParams?.behavior as? AppBarLayout.Behavior
            behavior?.topAndBottomOffset = offset
            currentAppBarOffset = offset
            updateFeatureTitleTransition()
        }
    }

    private fun updateFeatureTitleTransition(
        totalScrollRange: Int = binding.appBar.totalScrollRange,
        verticalOffset: Int = currentAppBarOffset
    ) {
        if (_binding == null) return
        val collapseFraction = if (totalScrollRange <= 0) {
            0f
        } else {
            (-verticalOffset / totalScrollRange.toFloat()).coerceIn(0f, 1f)
        }
        val expandedTitleAlpha = 1f - collapseFraction.progressBetween(
            EXPANDED_TITLE_FADE_START,
            EXPANDED_TITLE_FADE_END
        )
        val collapsedTitleAlpha = collapseFraction.progressBetween(
            COLLAPSED_TITLE_FADE_START,
            COLLAPSED_TITLE_FADE_END
        )

        binding.libraryTitleText.alpha = expandedTitleAlpha
        binding.libraryTitleText.visibility = View.VISIBLE
        binding.toolbar.title = if (collapsedTitleAlpha > 0f) libraryTitle else ""
        val onSurface = MaterialColors.getColor(binding.toolbar, R.attr.colorOnSurface)
        binding.toolbar.setTitleTextColor(
            ColorUtils.setAlphaComponent(onSurface, (collapsedTitleAlpha * 255).toInt())
        )
    }

    private fun Float.progressBetween(start: Float, end: Float): Float {
        if (end <= start) return 1f
        return ((this - start) / (end - start)).coerceIn(0f, 1f)
    }

    private fun onTopBarMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.share -> {
                showExportDialog()
                true
            }
            R.id.rename -> {
                showRenameDialog()
                true
            }
            R.id.delete -> {
                confirmDeleteLibrary()
                true
            }
            else -> false
        }
    }

    private fun showRenameDialog() {
        val lib = viewModel.library.value ?: return
        RenameDialogFragment(
            "重新命名",
            lib.name,
            object : RenameDialogFragment.RenameDialogListener {
                override fun onDialogPositiveClick(newName: String) {
                    viewModel.updateQuizLibrary(QuizLibrary(lib.id, newName, lib.quizCount))
                }
            }
        ).show(parentFragmentManager)
    }

    private fun confirmDeleteLibrary() {
        val lib = viewModel.library.value ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除题库“${lib.name}”？")
            .setPositiveButton(R.string.delete) { dialog, _ ->
                viewModel.deleteQuizLibrary(lib)
                findNavController().popBackStack()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showExportDialog() {
        val lib = viewModel.library.value
        val quizzes = viewModel.quizList.value
        if (lib == null || quizzes == null) {
            Toast.makeText(requireContext(), "题库加载中，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }

        var selectedFileType = QuizExportUtil.FileType.DOCX
        val dialogPadding = 24.dp()
        val listTopPadding = 12.dp()
        val contentView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dialogPadding, 12.dp(), dialogPadding, 12.dp())
        }
//        contentView.addView(
//            TextView(requireContext()).apply {
//                text = "选择导出格式后，可以保存到本地路径，或分享到其他 App。"
//                setTextColor(MaterialColors.getColor(binding.root, R.attr.colorOnSurfaceVariant))
//                textSize = 14f
//            }
//        )
        val radioGroup = RadioGroup(requireContext()).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, 0, 0, listTopPadding)
        }
        QuizExportUtil.FileType.values().forEach { fileType ->
            val radioButton = MaterialRadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = fileType.displayName
                tag = fileType
                isChecked = fileType == selectedFileType
            }
            radioGroup.addView(radioButton)
        }
        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            selectedFileType = group.findViewById<MaterialRadioButton>(checkedId).tag as QuizExportUtil.FileType
        }
        contentView.addView(radioGroup)

        val actionGroup = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16.dp(), 0, 0)
        }
        val saveButton = MaterialButton(requireContext()).apply {
            text = "保存到本地"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val shareButton = MaterialButton(requireContext()).apply {
            text = "分享到其他App"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dp()
            }
        }
        val cancelButton = MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.borderlessButtonStyle
        ).apply {
            text = resources.getString(R.string.cancel)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dp()
            }
        }
        actionGroup.addView(saveButton)
        actionGroup.addView(shareButton)
        actionGroup.addView(cancelButton)
        contentView.addView(actionGroup)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("导出题库")
            .setView(contentView)
            .create()
        saveButton.setOnClickListener {
            dialog.dismiss()
            prepareExportForSave(lib, quizzes, selectedFileType)
        }
        shareButton.setOnClickListener {
            dialog.dismiss()
            shareExport(lib, quizzes, selectedFileType)
        }
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun prepareExportForSave(
        library: QuizLibrary,
        quizzes: List<Quiz>,
        fileType: QuizExportUtil.FileType
    ) {
        showExportProgress("正在生成文档")
        runIO {
            try {
                val exportFile = QuizExportUtil.createExportFile(library, quizzes, fileType)
                runMain {
                    dismissExportProgress()
                    pendingExportFile = exportFile
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = exportFile.mimeType
                        putExtra(Intent.EXTRA_TITLE, exportFile.fileName)
                    }
                    exportDocumentLauncher.launch(intent)
                }
            } catch (err: Exception) {
                runMain {
                    dismissExportProgress()
                    showExportError(err)
                }
            }
        }
    }

    private fun shareExport(
        library: QuizLibrary,
        quizzes: List<Quiz>,
        fileType: QuizExportUtil.FileType
    ) {
        showExportProgress("正在生成文档")
        runIO {
            try {
                val exportFile = QuizExportUtil.createExportFile(library, quizzes, fileType)
                val uri = QuizExportUtil.createShareUri(requireContext(), exportFile)
                runMain {
                    dismissExportProgress()
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = exportFile.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TITLE, exportFile.fileName)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newUri(
                            requireContext().contentResolver,
                            exportFile.fileName,
                            uri
                        )
                    }
                    startActivity(Intent.createChooser(shareIntent, "分享导出文件"))
                }
            } catch (err: Exception) {
                runMain {
                    dismissExportProgress()
                    showExportError(err)
                }
            }
        }
    }

    private fun showExportProgress(message: String) {
        dismissExportProgress()
        val contentView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(24.dp())
        }
        contentView.addView(
            CircularProgressIndicator(requireContext()).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(40.dp(), 40.dp())
            }
        )
        contentView.addView(
            TextView(requireContext()).apply {
                text = message
                setTextColor(MaterialColors.getColor(binding.root, R.attr.colorOnSurface))
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = 16.dp()
                }
            }
        )
        exportProgressDialog = MaterialAlertDialogBuilder(requireContext())
            .setView(contentView)
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                setOnKeyListener { _, keyCode, _ -> keyCode == android.view.KeyEvent.KEYCODE_BACK }
                show()
            }
    }

    private fun dismissExportProgress() {
        exportProgressDialog?.dismiss()
        exportProgressDialog = null
    }

    private fun showExportError(err: Exception) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("导出失败")
            .setMessage(err.toString())
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun Int.dp(): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun handleFeatureClick(feature: StudyFeature) {
        val bundle = bundleOf(LIBRARY_ID to libraryId)
        when (feature.action) {
            FeatureAction.ORDERED_PRACTICE -> findNavController().navigate(
                R.id.QuizRunnerFragment,
                bundleOf(
                    LIBRARY_ID to libraryId,
                    QuizRunnerFragment.MODE to QuizStudyMode.ORDERED_PRACTICE.value
                )
            )
            FeatureAction.RANDOM_PRACTICE -> findNavController().navigate(
                R.id.QuizRunnerFragment,
                bundleOf(
                    LIBRARY_ID to libraryId,
                    QuizRunnerFragment.MODE to QuizStudyMode.RANDOM_PRACTICE.value
                )
            )
            FeatureAction.EXAM -> findNavController().navigate(R.id.ExamConfigFragment, bundle)
            FeatureAction.FAVORITES -> findNavController().navigate(
                R.id.QuizFavoritesFragment,
                bundleOf(
                    LIBRARY_ID to libraryId,
                    QuizFavoritesFragment.COLLECTION_TYPE to QuizFavoritesFragment.TYPE_FAVORITES
                )
            )
            FeatureAction.WRONG -> findNavController().navigate(
                R.id.QuizWrongFragment,
                bundleOf(
                    LIBRARY_ID to libraryId,
                    QuizFavoritesFragment.COLLECTION_TYPE to QuizFavoritesFragment.TYPE_WRONG
                )
            )
            FeatureAction.HISTORY -> findNavController().navigate(R.id.QuizHistoryFragment, bundle)
            FeatureAction.EXAM_HISTORY -> findNavController().navigate(R.id.ExamHistoryFragment, bundle)
            FeatureAction.QUIZ_LIST -> findNavController().navigate(
                R.id.QuizListFragment,
                bundleOf(QuizListFragment.LIBRARY_ID to libraryId)
            )
        }
    }

    data class StudyFeature(
        val title: String,
        val description: String,
        val iconResId: Int,
        val action: FeatureAction
    )

    enum class FeatureAction {
        ORDERED_PRACTICE,
        RANDOM_PRACTICE,
        EXAM,
        FAVORITES,
        WRONG,
        HISTORY,
        EXAM_HISTORY,
        QUIZ_LIST
    }

    companion object {
        private const val FEATURE_GRID_SPAN_COUNT = 2
        private const val EXPANDED_TITLE_FADE_START = 0.30f
        private const val EXPANDED_TITLE_FADE_END = 0.72f
        private const val COLLAPSED_TITLE_FADE_START = 0.55f
        private const val COLLAPSED_TITLE_FADE_END = 0.92f
        const val LIBRARY_ID = "library_id"
    }
}

class QuizLibFeaturesAdapter(
    private var features: List<QuizLibraryFeaturesFragment.StudyFeature>,
    private var answerStats: LibraryAnswerStats,
    private val onItemClick: (QuizLibraryFeaturesFragment.StudyFeature) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class FeatureViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardView: MaterialCardView = itemView.findViewById(R.id.feature_card)
        val titleText: TextView = itemView.findViewById(R.id.feature_title)
        val descriptionText: TextView = itemView.findViewById(R.id.feature_description)
        val iconView: ImageView = itemView.findViewById(R.id.feature_icon)
    }

    class StatsViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val todayAnsweredValue: TextView = itemView.findViewById(R.id.today_answered_value)
        val todayWrongValue: TextView = itemView.findViewById(R.id.today_wrong_value)
        val totalAnsweredValue: TextView = itemView.findViewById(R.id.total_answered_value)
        val totalWrongValue: TextView = itemView.findViewById(R.id.total_wrong_value)
        val accuracyValue: TextView = itemView.findViewById(R.id.accuracy_value)
    }

    fun submitList(newFeatures: List<QuizLibraryFeaturesFragment.StudyFeature>) {
        features = newFeatures
        notifyDataSetChanged()
    }

    fun updateAnswerStats(newStats: LibraryAnswerStats) {
        answerStats = newStats
        notifyItemChanged(features.size)
    }

    fun isFullSpan(position: Int): Boolean {
        return position == features.size ||
            (features.size % 2 == 1 &&
                position == features.lastIndex &&
                features.getOrNull(position)?.action == QuizLibraryFeaturesFragment.FeatureAction.QUIZ_LIST)
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == features.size) VIEW_TYPE_STATS else VIEW_TYPE_FEATURE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_STATS -> StatsViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_quiz_library_answer_stats, parent, false)
            )
            else -> FeatureViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_quiz_library_feature, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is FeatureViewHolder -> {
                val feature = features[position]
                holder.titleText.text = feature.title
                holder.descriptionText.text = feature.description
                holder.iconView.setImageResource(feature.iconResId)
                holder.cardView.setOnClickListener { onItemClick(feature) }
            }
            is StatsViewHolder -> {
                holder.todayAnsweredValue.text = answerStats.todayAnswered.toString()
                holder.todayWrongValue.text = answerStats.todayWrong.toString()
                holder.totalAnsweredValue.text = answerStats.totalAnswered.toString()
                holder.totalWrongValue.text = answerStats.totalWrong.toString()
                holder.accuracyValue.text = answerStats.accuracyPercent?.let { "$it%" } ?: "--"
            }
        }
    }

    override fun getItemCount() = features.size + 1

    companion object {
        private const val VIEW_TYPE_FEATURE = 0
        private const val VIEW_TYPE_STATS = 1
    }
}

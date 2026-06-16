package com.virin.visionquiz.quizlibraryfeatures

import RenameDialogFragment
import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.widget.ImageViewCompat
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
import com.google.android.material.progressindicator.LinearProgressIndicator
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
import com.virin.visionquiz.quizstudy.ReviewEntryState
import com.virin.visionquiz.quizstudy.ReviewStats
import com.virin.visionquiz.ai.AiConfigStore
import com.virin.visionquiz.ai.BatchAiExplanationService
import com.virin.visionquiz.screendetector.ScreenDetectorController
import com.virin.visionquiz.util.BaseQuizFragment
import com.virin.visionquiz.util.SimilarQuizStore
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
    private var enqueueSimilarAnalysisAfterPermission = false
    private var enqueueBatchAiAfterPermission = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (enqueueSimilarAnalysisAfterPermission) {
            enqueueSimilarAnalysisAfterPermission = false
            enqueueSimilarAnalysis()
        }
        if (enqueueBatchAiAfterPermission) {
            enqueueBatchAiAfterPermission = false
            enqueueBatchAiExplanation()
        }
    }
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

        val adapter = QuizLibFeaturesAdapter(
            features = buildLibraryStudyFeatures(),
            answerStats = LibraryAnswerStats(),
            reviewEntryState = ReviewEntryState(),
            reviewStats = ReviewStats()
        ) { feature ->
            handleFeatureClick(feature)
        }
        binding.studyFeaturesRecyclerView.layoutManager = GridLayoutManager(requireContext(), FEATURE_GRID_SPAN_COUNT).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return adapter.getSpanSize(position, FEATURE_GRID_SPAN_COUNT)
                }
            }
        }
        binding.studyFeaturesRecyclerView.adapter = adapter

        SimilarQuizStore.progress.observe(viewLifecycleOwner) { map ->
            val p = map[libraryId]
            val description = when (p?.status) {
                SimilarQuizStore.Status.QUEUED,
                SimilarQuizStore.Status.RUNNING,
                SimilarQuizStore.Status.FAILED -> p.text
                SimilarQuizStore.Status.CANCELLED,
                SimilarQuizStore.Status.COMPLETED,
                null -> if (SimilarQuizStore.hasAnalysis(requireContext(), libraryId)) {
                    "已完成，点击重新分析"
                } else {
                    "分析题库中的相似题目，辅助记忆"
                }
            }
            adapter.updateFeatureDescription(FeatureAction.SIMILAR_ANALYSIS, description)
        }

        viewModel.library.observe(viewLifecycleOwner) { library ->
            libraryTitle = library?.name ?: "题库"
            binding.libraryTitleText.text = libraryTitle
            updateFeatureTitleTransition()
        }
        viewModel.answerStats.observe(viewLifecycleOwner) { stats ->
            adapter.updateAnswerStats(stats)
        }
        viewModel.reviewStats.observe(viewLifecycleOwner) { stats ->
            adapter.updateReviewStats(stats)
        }
        viewModel.reviewEntryState.observe(viewLifecycleOwner) { state ->
            adapter.updateReviewEntryState(state)
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

    private fun computeSimilarAnalysis() {
        val quizzes = viewModel.quizList.value
        if (quizzes.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "题库为空", Toast.LENGTH_SHORT).show()
            return
        }
        val activeProgress = SimilarQuizStore.progress.value?.get(libraryId)
        if (activeProgress?.status == SimilarQuizStore.Status.QUEUED ||
            activeProgress?.status == SimilarQuizStore.Status.RUNNING
        ) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("相似题分析")
                .setMessage(activeProgress.text)
                .setPositiveButton("取消任务") { _, _ ->
                    SimilarQuizStore.cancelAnalysis(requireContext(), libraryId)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        if (SimilarQuizStore.hasAnalysis(requireContext(), libraryId)) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("重新分析？")
                .setMessage("已有分析结果，是否重新计算？")
                .setPositiveButton("重新分析") { _, _ -> requestNotificationThenEnqueue() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            requestNotificationThenEnqueue()
        }
    }

    private fun requestNotificationThenEnqueue() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            enqueueSimilarAnalysisAfterPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            enqueueSimilarAnalysis()
        }
    }

    private fun enqueueSimilarAnalysis() {
        val added = SimilarQuizStore.enqueueAnalysis(requireContext(), libraryId)
        val message = if (added) {
            "已加入后台分析队列"
        } else {
            "该题库已在分析队列中"
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun startBatchAiExplanation() {
        val config = AiConfigStore(requireContext()).read()
        if (!config.isComplete()) {
            showAiConfigurationRequired()
            return
        }
        val quizzes = viewModel.quizList.value
        if (quizzes.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "题库为空", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            enqueueBatchAiAfterPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            enqueueBatchAiExplanation()
        }
    }

    private fun enqueueBatchAiExplanation() {
        ContextCompat.startForegroundService(requireContext(), BatchAiExplanationService.start(requireContext(), libraryId))
        Snackbar.make(binding.root, "已开始后台生成解析", Snackbar.LENGTH_SHORT).show()
    }

    private fun showAiConfigurationRequired() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_not_configured_title)
            .setMessage(R.string.ai_not_configured_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ai_go_to_settings) { _, _ ->
                startActivity(Intent(requireContext(),
                    com.virin.visionquiz.preference.SettingsActivity::class.java).apply {
                    putExtra(
                        com.virin.visionquiz.preference.SettingsActivity.EXTRA_LAUNCH_SOURCE,
                        com.virin.visionquiz.preference.SettingsActivity.LaunchSource.AI_SETTINGS
                    )
                })
            }
            .show()
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
            FeatureAction.REVIEW -> openReviewSession()
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
            FeatureAction.EXPORT -> showExportDialog()
            FeatureAction.SIMILAR_ANALYSIS -> computeSimilarAnalysis()
            FeatureAction.BATCH_AI_EXPLAIN -> startBatchAiExplanation()
            FeatureAction.QUIZ_LIST -> findNavController().navigate(
                R.id.QuizListFragment,
                bundleOf(QuizListFragment.LIBRARY_ID to libraryId)
            )
        }
    }

    private fun openReviewSession() {
        runIO {
            val quizIds = viewModel.buildReviewQuizList()
            runMain {
                if (_binding == null) return@runMain
                if (quizIds.isEmpty()) {
                    Toast.makeText(requireContext(), "暂无待复习题目", Toast.LENGTH_SHORT).show()
                } else {
                    findNavController().navigate(
                        R.id.QuizRunnerFragment,
                        QuizRunnerFragment.arguments(
                            libraryId = libraryId,
                            mode = QuizStudyMode.REVIEW,
                            quizIds = quizIds.toIntArray()
                        )
                    )
                }
            }
        }
    }

    data class StudyFeature(
        val title: String,
        val description: String,
        val iconResId: Int,
        val action: FeatureAction
    )

    enum class FeatureAction {
        REVIEW,
        ORDERED_PRACTICE,
        RANDOM_PRACTICE,
        EXAM,
        FAVORITES,
        WRONG,
        HISTORY,
        EXAM_HISTORY,
        EXPORT,
        SIMILAR_ANALYSIS,
        BATCH_AI_EXPLAIN,
        QUIZ_LIST
    }

    companion object {
        private const val FEATURE_GRID_SPAN_COUNT = 6
        private const val EXPANDED_TITLE_FADE_START = 0.30f
        private const val EXPANDED_TITLE_FADE_END = 0.72f
        private const val COLLAPSED_TITLE_FADE_START = 0.55f
        private const val COLLAPSED_TITLE_FADE_END = 0.92f
        const val LIBRARY_ID = "library_id"
    }
}

internal sealed class QuizLibraryFeatureListItem {
    data class SectionHeader(val title: String) : QuizLibraryFeatureListItem()
    data class FeatureItem(
        val feature: QuizLibraryFeaturesFragment.StudyFeature,
        val fullSpan: Boolean = false,
        val compact: Boolean = false
    ) : QuizLibraryFeatureListItem()

    data object Stats : QuizLibraryFeatureListItem()
}

internal fun buildLibraryStudyFeatures(): List<QuizLibraryFeaturesFragment.StudyFeature> {
    return listOf(
        QuizLibraryFeaturesFragment.StudyFeature(
            "开始学习",
            "待复习 0 题 · 待学习 0 题",
            R.drawable.icon_history_edu_24px,
            QuizLibraryFeaturesFragment.FeatureAction.REVIEW
        ),
        QuizLibraryFeaturesFragment.StudyFeature(
            "顺序背题",
            "按题库顺序练习支持题型",
            R.drawable.icon_list_arrow_24px,
            QuizLibraryFeaturesFragment.FeatureAction.ORDERED_PRACTICE
        ),
        QuizLibraryFeaturesFragment.StudyFeature(
            "随机背题",
            "随机顺序练习支持题型",
            R.drawable.icon_shuffle_24px,
            QuizLibraryFeaturesFragment.FeatureAction.RANDOM_PRACTICE
        ),
        QuizLibraryFeaturesFragment.StudyFeature(
            "模拟考试",
            "按题型配置数量并随机组卷",
            R.drawable.icon_science_24px,
            QuizLibraryFeaturesFragment.FeatureAction.EXAM
        ),
        QuizLibraryFeaturesFragment.StudyFeature(
            "我的收藏",
            "查看已收藏题目",
            R.drawable.icon_bookmarks_24px,
            QuizLibraryFeaturesFragment.FeatureAction.FAVORITES
        ),
        QuizLibraryFeaturesFragment.StudyFeature(
            "错题库",
            "查看尚未巩固的错题",
            R.drawable.icon_error_24px,
            QuizLibraryFeaturesFragment.FeatureAction.WRONG
        ),
        QuizLibraryFeaturesFragment.StudyFeature(
            "作答历史",
            "查看每次背题和考试记录",
            R.drawable.icon_history_24px,
            QuizLibraryFeaturesFragment.FeatureAction.HISTORY
        ),
        QuizLibraryFeaturesFragment.StudyFeature(
            "考试历史",
            "回顾每场考试成绩和逐题详情",
            R.drawable.icon_experiment_24px,
            QuizLibraryFeaturesFragment.FeatureAction.EXAM_HISTORY
        ),
        QuizLibraryFeaturesFragment.StudyFeature(
            "相似题分析",
            "分析题库中的相似题目，辅助记忆",
            R.drawable.icon_document_search_24px,
            QuizLibraryFeaturesFragment.FeatureAction.SIMILAR_ANALYSIS
        ),
        QuizLibraryFeaturesFragment.StudyFeature(
            "生成 AI 解析",
            "批量为题库生成快速复盘解析",
            R.drawable.icon_science_24px,
            QuizLibraryFeaturesFragment.FeatureAction.BATCH_AI_EXPLAIN
        ),
        QuizLibraryFeaturesFragment.StudyFeature(
            "导出题库",
            "保存为文档或分享到其他 App",
            R.drawable.icon_file_export_24px,
            QuizLibraryFeaturesFragment.FeatureAction.EXPORT
        ),
        QuizLibraryFeaturesFragment.StudyFeature(
            "浏览题目",
            "查看和搜索全部题型",
            R.drawable.icon_all_inclusive_24px,
            QuizLibraryFeaturesFragment.FeatureAction.QUIZ_LIST
        )
    )
}

internal fun buildGroupedFeatureItems(
    features: List<QuizLibraryFeaturesFragment.StudyFeature>
): List<QuizLibraryFeatureListItem> {
    val byAction = features.associateBy { it.action }
    fun QuizLibraryFeaturesFragment.FeatureAction.featureItem(
        fullSpan: Boolean = false,
        compact: Boolean = false
    ) = byAction[this]?.let { QuizLibraryFeatureListItem.FeatureItem(it, fullSpan, compact) }

    return buildList {
        add(QuizLibraryFeatureListItem.SectionHeader("Today"))
        QuizLibraryFeaturesFragment.FeatureAction.REVIEW.featureItem(fullSpan = true)?.let(::add)

        add(QuizLibraryFeatureListItem.SectionHeader("自主练习"))
        QuizLibraryFeaturesFragment.FeatureAction.ORDERED_PRACTICE.featureItem(compact = true)?.let(::add)
        QuizLibraryFeaturesFragment.FeatureAction.RANDOM_PRACTICE.featureItem(compact = true)?.let(::add)
        QuizLibraryFeaturesFragment.FeatureAction.EXAM.featureItem(compact = true)?.let(::add)

        add(QuizLibraryFeatureListItem.SectionHeader("题库概览"))
        add(QuizLibraryFeatureListItem.Stats)
        QuizLibraryFeaturesFragment.FeatureAction.QUIZ_LIST.featureItem(fullSpan = true)?.let(::add)

        add(QuizLibraryFeatureListItem.SectionHeader("复盘巩固"))
        QuizLibraryFeaturesFragment.FeatureAction.FAVORITES.featureItem()?.let(::add)
        QuizLibraryFeaturesFragment.FeatureAction.WRONG.featureItem()?.let(::add)
        QuizLibraryFeaturesFragment.FeatureAction.HISTORY.featureItem()?.let(::add)
        QuizLibraryFeaturesFragment.FeatureAction.EXAM_HISTORY.featureItem()?.let(::add)

        add(QuizLibraryFeatureListItem.SectionHeader("题库工具"))
        QuizLibraryFeaturesFragment.FeatureAction.BATCH_AI_EXPLAIN.featureItem()?.let(::add)
        QuizLibraryFeaturesFragment.FeatureAction.EXPORT.featureItem()?.let(::add)
        QuizLibraryFeaturesFragment.FeatureAction.SIMILAR_ANALYSIS.featureItem()?.let(::add)
    }
}

internal fun isFullSpanFeatureItem(item: QuizLibraryFeatureListItem): Boolean {
    return when (item) {
        is QuizLibraryFeatureListItem.SectionHeader -> true
        is QuizLibraryFeatureListItem.FeatureItem -> item.fullSpan
        QuizLibraryFeatureListItem.Stats -> true
    }
}

internal fun quizLibraryFeatureSpanSize(
    item: QuizLibraryFeatureListItem,
    spanCount: Int
): Int {
    return when {
        isFullSpanFeatureItem(item) -> spanCount
        item is QuizLibraryFeatureListItem.FeatureItem && item.compact -> (spanCount / 3).coerceAtLeast(1)
        else -> (spanCount / 2).coerceAtLeast(1)
    }
}

class QuizLibFeaturesAdapter(
    features: List<QuizLibraryFeaturesFragment.StudyFeature>,
    private var answerStats: LibraryAnswerStats,
    private var reviewEntryState: ReviewEntryState,
    private var reviewStats: ReviewStats,
    private val onItemClick: (QuizLibraryFeaturesFragment.StudyFeature) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var features: List<QuizLibraryFeaturesFragment.StudyFeature> = features
    private var items: List<QuizLibraryFeatureListItem> = buildGroupedFeatureItems(features)

    class SectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleText: TextView = itemView.findViewById(R.id.section_title)
    }

    class FeatureViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardView: MaterialCardView = itemView.findViewById(R.id.feature_card)
        val titleText: TextView = itemView.findViewById(R.id.feature_title)
        val descriptionText: TextView = itemView.findViewById(R.id.feature_description)
        val iconView: ImageView = itemView.findViewById(R.id.feature_icon)
        val accuracyGroup: View? = itemView.findViewById(R.id.feature_accuracy_group)
        val accuracyValue: TextView? = itemView.findViewById(R.id.feature_accuracy_value)
        val accuracyProgress: LinearProgressIndicator? =
            itemView.findViewById(R.id.feature_accuracy_progress)
    }

    class StatsViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val todayAnsweredValue: TextView = itemView.findViewById(R.id.today_answered_value)
        val todayWrongValue: TextView = itemView.findViewById(R.id.today_wrong_value)
        val totalAnsweredValue: TextView = itemView.findViewById(R.id.total_answered_value)
        val totalWrongValue: TextView = itemView.findViewById(R.id.total_wrong_value)
        val reviewDueValue: TextView = itemView.findViewById(R.id.review_due_value)
        val reviewTodayValue: TextView = itemView.findViewById(R.id.review_today_value)
        val reviewTotalCardsValue: TextView = itemView.findViewById(R.id.review_total_cards_value)
        val reviewLapsesValue: TextView = itemView.findViewById(R.id.review_lapses_value)
    }

    fun submitList(newFeatures: List<QuizLibraryFeaturesFragment.StudyFeature>) {
        features = newFeatures
        items = buildGroupedFeatureItems(features)
        notifyDataSetChanged()
    }

    fun updateFeatureDescription(action: QuizLibraryFeaturesFragment.FeatureAction, description: String) {
        val index = features.indexOfFirst { it.action == action }
        if (index >= 0 && features[index].description != description) {
            features = features.toMutableList().apply {
                this[index] = this[index].copy(description = description)
            }
            items = buildGroupedFeatureItems(features)
            val itemIndex = items.indexOfFirst { item ->
                item is QuizLibraryFeatureListItem.FeatureItem && item.feature.action == action
            }
            if (itemIndex >= 0) {
                notifyItemChanged(itemIndex)
            } else {
                notifyDataSetChanged()
            }
        }
    }

    fun updateAnswerStats(newStats: LibraryAnswerStats) {
        answerStats = newStats
        notifyStatsChanged()
        notifyFeatureChanged(QuizLibraryFeaturesFragment.FeatureAction.QUIZ_LIST)
    }

    fun updateReviewEntryState(newState: ReviewEntryState) {
        reviewEntryState = newState
        updateFeature(
            action = QuizLibraryFeaturesFragment.FeatureAction.REVIEW,
            title = newState.title,
            description = newState.description
        )
    }

    fun updateReviewStats(newStats: ReviewStats) {
        reviewStats = newStats
        notifyStatsChanged()
    }

    fun isFullSpan(position: Int): Boolean {
        return items.getOrNull(position)?.let(::isFullSpanFeatureItem) ?: true
    }

    fun getSpanSize(position: Int, spanCount: Int): Int {
        return items.getOrNull(position)?.let { quizLibraryFeatureSpanSize(it, spanCount) } ?: spanCount
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is QuizLibraryFeatureListItem.SectionHeader -> VIEW_TYPE_SECTION
            is QuizLibraryFeatureListItem.FeatureItem -> {
                if ((items[position] as QuizLibraryFeatureListItem.FeatureItem).compact) {
                    VIEW_TYPE_COMPACT_FEATURE
                } else {
                    VIEW_TYPE_FEATURE
                }
            }
            QuizLibraryFeatureListItem.Stats -> VIEW_TYPE_STATS
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_SECTION -> SectionViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_quiz_library_feature_section, parent, false)
            )
            VIEW_TYPE_STATS -> StatsViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_quiz_library_answer_stats, parent, false)
            )
            VIEW_TYPE_COMPACT_FEATURE -> FeatureViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_quiz_library_feature_compact, parent, false)
            )
            else -> FeatureViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_quiz_library_feature, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is QuizLibraryFeatureListItem.SectionHeader -> {
                (holder as SectionViewHolder).titleText.text = item.title
            }
            is QuizLibraryFeatureListItem.FeatureItem -> {
                bindFeature(holder as FeatureViewHolder, item.feature, item.fullSpan)
            }
            QuizLibraryFeatureListItem.Stats -> {
                holder as StatsViewHolder
                holder.todayAnsweredValue.text = answerStats.todayAnswered.toString()
                holder.todayWrongValue.text = answerStats.todayWrong.toString()
                holder.totalAnsweredValue.text = answerStats.totalAnswered.toString()
                holder.totalWrongValue.text = answerStats.totalWrong.toString()
                holder.reviewDueValue.text = reviewStats.dueToday.toString()
                holder.reviewTodayValue.text = reviewStats.reviewedToday.toString()
                holder.reviewTotalCardsValue.text = reviewStats.totalCards.toString()
                holder.reviewLapsesValue.text = reviewStats.totalLapses.toString()
            }
        }
    }

    override fun getItemCount() = items.size

    private fun bindFeature(
        holder: FeatureViewHolder,
        feature: QuizLibraryFeaturesFragment.StudyFeature,
        fullSpan: Boolean
    ) {
        holder.titleText.text = feature.title
        holder.descriptionText.text = feature.description
        holder.iconView.setImageResource(feature.iconResId)
        holder.cardView.setOnClickListener { onItemClick(feature) }
        bindFeatureAccuracy(holder, feature)
        if (
            fullSpan &&
            feature.action == QuizLibraryFeaturesFragment.FeatureAction.REVIEW &&
            reviewEntryState.hasPendingWork
        ) {
            applyPrimaryFeatureStyle(holder)
        } else {
            applyDefaultFeatureStyle(holder)
        }
    }

    private fun bindFeatureAccuracy(
        holder: FeatureViewHolder,
        feature: QuizLibraryFeaturesFragment.StudyFeature
    ) {
        val showAccuracy = feature.action == QuizLibraryFeaturesFragment.FeatureAction.QUIZ_LIST
        holder.accuracyGroup?.isVisible = showAccuracy
        if (!showAccuracy) return
        val accuracyPercent = answerStats.accuracyPercent?.coerceIn(0, 100)
        holder.accuracyValue?.text = accuracyPercent?.let { "$it%" } ?: "--"
        holder.accuracyProgress?.setProgressCompat(accuracyPercent ?: 0, false)
    }

    private fun updateFeature(
        action: QuizLibraryFeaturesFragment.FeatureAction,
        title: String,
        description: String
    ) {
        val index = features.indexOfFirst { it.action == action }
        if (index >= 0 && (features[index].title != title || features[index].description != description)) {
            features = features.toMutableList().apply {
                this[index] = this[index].copy(title = title, description = description)
            }
            items = buildGroupedFeatureItems(features)
            val itemIndex = items.indexOfFirst { item ->
                item is QuizLibraryFeatureListItem.FeatureItem && item.feature.action == action
            }
            if (itemIndex >= 0) {
                notifyItemChanged(itemIndex)
            } else {
                notifyDataSetChanged()
            }
        }
    }

    private fun applyPrimaryFeatureStyle(holder: FeatureViewHolder) {
        val backgroundColor = MaterialColors.getColor(holder.itemView, R.attr.colorPrimaryContainer)
        val contentColor = MaterialColors.getColor(holder.itemView, R.attr.colorOnPrimaryContainer)
        val strokeColor = MaterialColors.getColor(holder.itemView, R.attr.colorPrimary)
        holder.cardView.setCardBackgroundColor(backgroundColor)
        holder.cardView.strokeColor = strokeColor
        holder.cardView.strokeWidth = holder.itemView.dp(1)
        holder.cardView.cardElevation = holder.itemView.dp(1).toFloat()
        holder.titleText.setTextColor(contentColor)
        holder.descriptionText.setTextColor(contentColor)
        ImageViewCompat.setImageTintList(holder.iconView, ColorStateList.valueOf(contentColor))
    }

    private fun applyDefaultFeatureStyle(holder: FeatureViewHolder) {
        val backgroundColor = MaterialColors.getColor(holder.itemView, R.attr.colorSurfaceContainer)
        val titleColor = MaterialColors.getColor(holder.itemView, R.attr.colorOnSurface)
        val descriptionColor = MaterialColors.getColor(holder.itemView, R.attr.colorOnSurfaceVariant)
        val iconColor = MaterialColors.getColor(holder.itemView, R.attr.colorPrimary)
        val strokeColor = MaterialColors.getColor(holder.itemView, R.attr.colorOutlineVariant)
        holder.cardView.setCardBackgroundColor(backgroundColor)
        holder.cardView.strokeColor = strokeColor
        holder.cardView.strokeWidth = holder.itemView.dp(1)
        holder.cardView.cardElevation = 0f
        holder.titleText.setTextColor(titleColor)
        holder.descriptionText.setTextColor(descriptionColor)
        ImageViewCompat.setImageTintList(holder.iconView, ColorStateList.valueOf(iconColor))
    }

    private fun notifyStatsChanged() {
        val statsIndex = items.indexOfFirst { it is QuizLibraryFeatureListItem.Stats }
        if (statsIndex >= 0) {
            notifyItemChanged(statsIndex)
        }
    }

    private fun notifyFeatureChanged(action: QuizLibraryFeaturesFragment.FeatureAction) {
        val itemIndex = items.indexOfFirst { item ->
            item is QuizLibraryFeatureListItem.FeatureItem && item.feature.action == action
        }
        if (itemIndex >= 0) {
            notifyItemChanged(itemIndex)
        }
    }

    private fun View.dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val VIEW_TYPE_SECTION = 0
        private const val VIEW_TYPE_FEATURE = 1
        private const val VIEW_TYPE_STATS = 2
        private const val VIEW_TYPE_COMPACT_FEATURE = 3
    }
}

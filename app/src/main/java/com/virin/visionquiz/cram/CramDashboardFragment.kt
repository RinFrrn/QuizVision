package com.virin.visionquiz.cram

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.virin.visionquiz.R
import com.virin.visionquiz.ai.AiMarkdownRenderer
import com.virin.visionquiz.databinding.FragmentCramDashboardBinding
import com.virin.visionquiz.dao.QuizStudyMode
import com.virin.visionquiz.preference.SettingsActivity
import com.virin.visionquiz.quizlist.quizcontent.QuizContentDialogHandle
import com.virin.visionquiz.quizlist.quizcontent.showQuizContentDialog
import com.virin.visionquiz.quizlibraryfeatures.QuizLibraryFeaturesFragment
import com.virin.visionquiz.quizstudy.QuizRunnerFragment
import com.virin.visionquiz.util.BaseQuizFragment
import com.virin.visionquiz.util.MdcThemeBridge
import com.virin.visionquiz.util.configureQuizTopBar
import com.virin.visionquiz.util.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

class CramDashboardFragment : BaseQuizFragment() {

    private var _binding: FragmentCramDashboardBinding? = null
    private val binding get() = _binding!!
    private val libraryId: Int
        get() = requireArguments().getInt(QuizLibraryFeaturesFragment.LIBRARY_ID)

    private val viewModel: CramDashboardViewModel by viewModels {
        CramDashboardViewModel.factory(requireActivity().application, libraryId)
    }
    private var pendingAiAnalysisAfterPermission = false
    private var markdownSheetDialog: BottomSheetDialog? = null
    private var pendingQuizSheetSelection: CramQuizSheetSelection? = null
    private var quizContentDialogHandle: QuizContentDialogHandle? = null
    private var quizSheetOpening = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (pendingAiAnalysisAfterPermission) {
            pendingAiAnalysisAfterPermission = false
            viewModel.startAiAnalysis()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCramDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configureQuizTopBar(binding.toolbar, "3天冲刺")
        binding.cramDashboardContent.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MdcThemeBridge {
                    CramDashboardRoute(
                        viewModel = viewModel,
                        onChooseExamDate = ::showExamDatePicker,
                        onOpenPractice = ::openPractice,
                        onOpenQuickCard = ::openQuickCard,
                        onOpenFullReport = ::openFullReport,
                        onOpenQuizReference = ::openMnemonicQuizReference,
                        onOpenPriorityModule = ::openPriorityModule,
                        onStartAiAnalysis = ::requestAiAnalysis,
                        onOpenAiSettings = ::openAiSettings
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshLocal()
    }

    override fun onDestroyView() {
        pendingQuizSheetSelection = null
        quizContentDialogHandle?.dismiss()
        quizContentDialogHandle = null
        quizSheetOpening = false
        markdownSheetDialog?.dismiss()
        markdownSheetDialog = null
        super.onDestroyView()
        _binding = null
    }

    private fun showExamDatePicker(currentEpochDay: Long) {
        val selectionMillis = LocalDate.ofEpochDay(currentEpochDay)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val todayUtcMillis = LocalDate.now()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("选择考试日期")
            .setSelection(selectionMillis)
            .setCalendarConstraints(
                CalendarConstraints.Builder()
                    .setValidator(DateValidatorPointForward.from(todayUtcMillis))
                    .build()
            )
            .build()
        picker.addOnPositiveButtonClickListener { selectedMillis ->
            val epochDay = Instant.ofEpochMilli(selectedMillis)
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toEpochDay()
            viewModel.setExamDate(epochDay)
        }
        picker.show(childFragmentManager, EXAM_DATE_PICKER_TAG)
    }

    private fun openPractice(entry: CramPracticeEntry) {
        val quizIds = viewModel.quizIdsFor(entry)
        if (quizIds.isEmpty()) {
            val message = when (entry) {
                CramPracticeEntry.TODAY_TASK -> "生成冲刺分析后即可开始今日任务"
                CramPracticeEntry.SELF_TEST -> "生成冲刺分析后即可开始30题自测"
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            return
        }
        val mode = when (entry) {
            CramPracticeEntry.TODAY_TASK -> QuizStudyMode.CRAM_PRACTICE
            CramPracticeEntry.SELF_TEST -> QuizStudyMode.CRAM_SELF_TEST
        }
        findNavController().navigate(
            R.id.QuizRunnerFragment,
            QuizRunnerFragment.arguments(
                libraryId = libraryId,
                mode = mode,
                quizIds = quizIds
            )
        )
    }

    private fun requestAiAnalysis() {
        val state = viewModel.state.value ?: return
        if (!state.aiConfigured) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("需要配置 AI")
                .setMessage("本地统计仍可正常使用。配置第三方 AI 服务后，可生成母规则、数字口诀和判断题陷阱。")
                .setPositiveButton("前往设置") { _, _ -> openAiSettings() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        if (state.questionCount <= 0) {
            Toast.makeText(requireContext(), "题库中暂无可分析题目", Toast.LENGTH_SHORT).show()
            return
        }
        if (state.aiDataSharingConsentGranted) {
            startAiAnalysisWithNotificationPermission()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("发送题库到第三方 AI？")
            .setMessage(
                "将本题库的 ${state.questionCount} 道题的题干、选项、标准答案、" +
                    "题库依据/备注分批发送到你配置的第三方 AI 服务，可能产生接口费用。" +
                    "数据处理受该服务的隐私政策约束。是否同意并开始分析？"
            )
            .setPositiveButton("同意并开始") { _, _ ->
                viewModel.grantAiDataSharingConsent()
                startAiAnalysisWithNotificationPermission()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startAiAnalysisWithNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAiAnalysisAfterPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startAiAnalysis()
        }
    }

    private fun openAiSettings() {
        startActivity(
            Intent(requireContext(), SettingsActivity::class.java).apply {
                putExtra(
                    SettingsActivity.EXTRA_LAUNCH_SOURCE,
                    SettingsActivity.LaunchSource.AI_SETTINGS
                )
            }
        )
    }

    private fun openQuickCard() {
        val content = viewModel.state.value?.content
        val markdown = content?.quickCardMarkdown.orEmpty()
        if (markdown.isBlank()) {
            Toast.makeText(requireContext(), "生成冲刺分析后即可查看考前速记卡", Toast.LENGTH_SHORT).show()
            return
        }
        showMarkdownSheet(
            title = "考前20分钟",
            markdown = markdown,
            memoryPointSourceLabel = "考前速记",
            allowLegacyNumericReferences = CramLocalContentRenderer.extractQuickCard(
                content?.aiReportMarkdown.orEmpty()
            ) != null
        )
    }

    private fun openFullReport() {
        val content = viewModel.state.value?.content ?: return
        val aiReport = content.aiReportMarkdown?.takeIf(String::isNotBlank)
        val markdown = aiReport ?: content.localReportMarkdown
        if (markdown.isBlank()) {
            Toast.makeText(requireContext(), "冲刺指南尚未生成", Toast.LENGTH_SHORT).show()
            return
        }
        showMarkdownSheet(
            title = if (aiReport == null) "本地冲刺指南" else "完整冲刺总稿",
            markdown = markdown,
            memoryPointSourceLabel = if (aiReport == null) {
                "本地分析"
            } else {
                "AI 冲刺总稿"
            },
            allowLegacyNumericReferences = aiReport != null
        )
    }

    private fun showMarkdownSheet(
        title: String,
        markdown: String,
        memoryPointSourceLabel: String,
        allowLegacyNumericReferences: Boolean
    ) {
        val context = requireContext()
        markdownSheetDialog?.dismiss()

        val dialog = BottomSheetDialog(context)
        markdownSheetDialog = dialog
        val surfaceColor = MaterialColors.getColor(
            requireView(),
            com.google.android.material.R.attr.colorSurface
        )
        val onSurfaceColor = MaterialColors.getColor(
            requireView(),
            com.google.android.material.R.attr.colorOnSurface
        )
        val outlineColor = MaterialColors.getColor(
            requireView(),
            com.google.android.material.R.attr.colorOutlineVariant
        )
        val primaryColor = MaterialColors.getColor(
            requireView(),
            com.google.android.material.R.attr.colorPrimary
        )
        val sheetHeight = (resources.displayMetrics.heightPixels * 0.92f).roundToInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    28.dp.toFloat(), 28.dp.toFloat(),
                    28.dp.toFloat(), 28.dp.toFloat(),
                    0f, 0f,
                    0f, 0f
                )
                setColor(surfaceColor)
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                sheetHeight
            )
        }
        content.addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 2.dp.toFloat()
                    setColor(outlineColor)
                }
            },
            LinearLayout.LayoutParams(36.dp, 4.dp).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = 8.dp
                bottomMargin = 4.dp
            }
        )

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dp, 0, 12.dp, 8.dp)
        }
        header.addView(
            TextView(context).apply {
                text = title
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall
                )
                setTextColor(onSurfaceColor)
                contentDescription = "$title，标题"
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val borderlessBackground = TypedValue().also {
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                it,
                true
            )
        }.resourceId
        header.addView(
            TextView(context).apply {
                text = "关闭"
                contentDescription = "关闭$title"
                gravity = Gravity.CENTER
                minWidth = 64.dp
                minHeight = 48.dp
                setPadding(12.dp, 0, 12.dp, 0)
                setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_LabelLarge
                )
                setTextColor(primaryColor)
                if (borderlessBackground != 0) {
                    setBackgroundResource(borderlessBackground)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss() }
            }
        )
        content.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(
            View(context).apply { setBackgroundColor(outlineColor) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1.dp
            ).apply {
                marginStart = 24.dp
                marginEnd = 24.dp
            }
        )

        val horizontalPadding = 24.dp
        val topPadding = 12.dp
        val bottomPadding = 16.dp
        val markdownView = TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setTextColor(onSurfaceColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(2.dp.toFloat(), 1.22f)
            includeFontPadding = false
            setTextIsSelectable(true)
        }
        AiMarkdownRenderer(
            context = context,
            preserveHeadingBodySpacing = true
        ).render(markdownView, markdown)
        val reportReferences = CramQuizMemoryPointExtractor.extract(
            markdown = markdown,
            sourceLabel = memoryPointSourceLabel,
            sourceKey = "$title:${markdown.hashCode()}",
            allowLegacyNumericReferences = allowLegacyNumericReferences
        )
        CramQuizReferenceLinkifier.linkify(
            textView = markdownView,
            linkColor = primaryColor,
            isResolvable = viewModel::hasQuizReference,
            referenceContexts = reportReferences,
            onQuizClick = { clickedReference ->
                openReportQuizReference(clickedReference, reportReferences)
            }
        )
        val scrollView = NestedScrollView(context).apply {
            isFillViewport = true
            clipToPadding = true
            setPadding(
                horizontalPadding,
                topPadding,
                horizontalPadding,
                bottomPadding
            )
            addView(
                markdownView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        content.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        dialog.setContentView(content)
        dialog.setOnShowListener {
            dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )?.let { bottomSheet ->
                bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                    height = sheetHeight
                }
                content.layoutParams = content.layoutParams.apply {
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
            dialog.behavior.apply {
                isFitToContents = true
                skipCollapsed = true
                peekHeight = sheetHeight
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.setOnDismissListener {
            if (markdownSheetDialog === dialog) {
                markdownSheetDialog = null
            }
        }
        dialog.show()
    }

    private fun openReportQuizReference(
        clickedReference: CramQuizReferenceContext,
        reportReferences: List<CramQuizReferenceContext>
    ) {
        if (quizSheetOpening || quizContentDialogHandle != null) return
        val selection = viewModel.reportQuizSheetSelection(
            clickedReference = clickedReference,
            reportReferences = reportReferences
        )
        if (selection == null) {
            Toast.makeText(
                requireContext(),
                "当前题库中未找到${clickedReference.target.displayLabel}",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        quizSheetOpening = true
        pendingQuizSheetSelection = selection
        openPendingQuizSheet()
    }

    private fun openMnemonicQuizReference(
        target: CramQuizReferenceTarget,
        memoryPointId: String
    ) {
        if (quizSheetOpening || quizContentDialogHandle != null) return
        val selection = viewModel.mnemonicQuizSheetSelection(
            target = target,
            preferredMemoryPointId = memoryPointId
        )
        if (selection == null) {
            Toast.makeText(
                requireContext(),
                "当前题库中未找到${target.displayLabel}",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        quizSheetOpening = true
        pendingQuizSheetSelection = selection
        openPendingQuizSheet()
    }

    private fun openPriorityModule(moduleId: String) {
        if (quizSheetOpening || quizContentDialogHandle != null) return
        val selection = viewModel.priorityModuleQuizSheetSelection(moduleId)
        if (selection == null) {
            Toast.makeText(
                requireContext(),
                "当前分组中暂无可浏览题目",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        quizSheetOpening = true
        pendingQuizSheetSelection = selection
        openPendingQuizSheet()
    }

    private fun openPendingQuizSheet() {
        val selection = pendingQuizSheetSelection ?: return
        pendingQuizSheetSelection = null
        val owner = viewLifecycleOwner
        val root = _binding?.root
        if (root == null || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            quizSheetOpening = false
            return
        }
        root.post {
            if (
                !isAdded ||
                _binding == null ||
                !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                quizSheetOpening = false
                return@post
            }
            quizContentDialogHandle = showQuizContentDialog(
                context = requireActivity(),
                quizzes = selection.quizzes,
                initialIndex = selection.initialIndex,
                allQuizzes = selection.allQuizzes,
                extras = selection.extras,
                onDismissed = {
                    quizContentDialogHandle = null
                    quizSheetOpening = false
                }
            )
            if (quizContentDialogHandle == null) {
                quizSheetOpening = false
            }
        }
    }

    companion object {
        private const val EXAM_DATE_PICKER_TAG = "cram_exam_date_picker"
    }
}

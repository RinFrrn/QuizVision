package com.virin.visionquiz.cram

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun CramDashboardRoute(
    viewModel: CramDashboardViewModel,
    onChooseExamDate: (Long) -> Unit,
    onOpenPractice: (CramPracticeEntry) -> Unit,
    onOpenQuickCard: () -> Unit,
    onOpenFullReport: () -> Unit,
    onOpenQuizReference: (CramQuizReferenceTarget, String) -> Unit,
    onOpenPriorityModule: (String) -> Unit,
    onStartAiAnalysis: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val state by viewModel.state.observeAsState()
    val current = state
    if (current == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }
    CramDashboardScreen(
        state = current,
        onChooseExamDate = { onChooseExamDate(current.examDateEpochDay) },
        onDailyMinutesChanged = viewModel::setDailyMinutes,
        onStartAiAnalysis = onStartAiAnalysis,
        onCancelAiAnalysis = viewModel::cancelAiAnalysis,
        onRefreshLocal = { viewModel.refresh(forceLocal = true) },
        onOpenAiSettings = onOpenAiSettings,
        onOpenPractice = onOpenPractice,
        onOpenQuickCard = onOpenQuickCard,
        onOpenFullReport = onOpenFullReport,
        onOpenQuizReference = onOpenQuizReference,
        onOpenPriorityModule = onOpenPriorityModule
    )
}

@Composable
internal fun CramDashboardScreen(
    state: CramDashboardUiState,
    onChooseExamDate: () -> Unit,
    onDailyMinutesChanged: (Int) -> Unit,
    onStartAiAnalysis: () -> Unit,
    onCancelAiAnalysis: () -> Unit,
    onRefreshLocal: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenPractice: (CramPracticeEntry) -> Unit,
    onOpenQuickCard: () -> Unit,
    onOpenFullReport: () -> Unit,
    onOpenQuizReference: (CramQuizReferenceTarget, String) -> Unit,
    onOpenPriorityModule: (String) -> Unit
) {
    var prioritySheetTarget by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 12.dp,
            end = 16.dp,
            bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "countdown") {
            CountdownCard(
                state = state,
                onChooseExamDate = onChooseExamDate,
                configurationEnabled = !state.isAnalysisInProgress
            )
        }
        item(key = "daily-duration") {
            DailyDurationCard(
                dailyMinutes = state.dailyMinutes,
                onDailyMinutesChanged = onDailyMinutesChanged,
                configurationEnabled = !state.isAnalysisInProgress
            )
        }
        item(key = "analysis-action") {
            AnalysisActionCard(
                state = state,
                onStartAiAnalysis = onStartAiAnalysis,
                onCancelAiAnalysis = onCancelAiAnalysis,
                onRefreshLocal = onRefreshLocal,
                onOpenAiSettings = onOpenAiSettings,
                onOpenFullReport = onOpenFullReport
            )
        }
        item(key = "today-heading") {
            SectionHeading(
                title = "今天",
                supportingText = "只做最值得提分的内容"
            )
        }
        item(key = "today-task") {
            TodayTaskCard(
                state = state,
                onStart = { onOpenPractice(CramPracticeEntry.TODAY_TASK) }
            )
        }
        item(key = "priority-heading") {
            SectionHeading(
                title = cramPrioritySectionTitle(state.content.priorityGroupingMode),
                supportingText = cramPrioritySectionSupportingText(
                    state.content.priorityGroupingMode
                ),
                actionLabel = "排序说明",
                onAction = { prioritySheetTarget = PRIORITY_HELP_SHEET_KEY }
            )
        }
        if (state.content.priorityModules.isEmpty()) {
            item(key = "priority-empty") {
                AnalysisEmptyCard(
                    icon = Icons.Default.BarChart,
                    title = "等待题库分析",
                    message = "生成后会在这里列出最先该复习的分组。"
                )
            }
        } else {
            items(
                items = state.content.priorityModules.take(MAX_VISIBLE_MODULES),
                key = { "module-${it.id}" }
            ) { module ->
                PriorityModuleCard(
                    module = module,
                    onClick = {
                        prioritySheetTarget = priorityModuleSheetKey(module.id)
                    }
                )
            }
        }
        item(key = "mnemonic-heading") {
            SectionHeading(
                title = "数字速记",
                supportingText = "把零散数字压缩成可背诵的链条"
            )
        }
        if (state.content.mnemonics.isEmpty()) {
            item(key = "mnemonic-empty") {
                AnalysisEmptyCard(
                    icon = Icons.Default.Numbers,
                    title = "暂无数字链",
                    message = "分析会提取时限、比例、金额、倍数和有效期。"
                )
            }
        } else {
            items(
                items = state.content.mnemonics.take(MAX_VISIBLE_MNEMONICS),
                key = { "mnemonic-${it.id}" }
            ) { mnemonic ->
                MnemonicCard(
                    mnemonic = mnemonic,
                    onOpenQuizReference = onOpenQuizReference
                )
            }
        }
        item(key = "exam-heading") {
            SectionHeading(
                title = "考前工具",
                supportingText = "临场速记与高频真题自测"
            )
        }
        item(key = "quick-card") {
            QuickReviewCard(
                state = state,
                onOpen = onOpenQuickCard
            )
        }
        item(key = "self-test") {
            SelfTestCard(
                quizCount = state.content.selfTestQuizIds.size,
                onStart = { onOpenPractice(CramPracticeEntry.SELF_TEST) }
            )
        }
        item(key = "bottom-spacer") {
            Spacer(
                modifier = Modifier
                    .navigationBarsPadding()
                    .height(4.dp)
            )
        }
    }

    prioritySheetTarget?.let { target ->
        val detailRequested = target.startsWith(PRIORITY_MODULE_SHEET_PREFIX)
        val moduleId = target
            .takeIf { detailRequested }
            ?.removePrefix(PRIORITY_MODULE_SHEET_PREFIX)
        val module = moduleId?.let { id ->
            state.content.priorityModules.firstOrNull { it.id == id }
        }
        CramPriorityExplanationSheet(
            groupingMode = state.content.priorityGroupingMode,
            totalQuestionCount = state.questionCount,
            moduleCount = state.content.priorityModules.size,
            module = module,
            detailRequested = detailRequested,
            onDismiss = { prioritySheetTarget = null },
            onBrowseModule = {
                onOpenPriorityModule(it.id)
            }
        )
    }
}

@Composable
private fun CountdownCard(
    state: CramDashboardUiState,
    onChooseExamDate: () -> Unit,
    configurationEnabled: Boolean
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocalFireDepartment,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "冲刺计划",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = cramCountdownLabel(state.daysRemaining),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (state.daysRemaining == 0) {
                            "今天只看高频规则和错题，不再扩展新内容"
                        } else {
                            "每天 ${state.dailyMinutes} 分钟，先保及格再冲高分"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onPrimaryContainer.copy(alpha = 0.78f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onChooseExamDate,
                enabled = configurationEnabled,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = colors.onPrimaryContainer
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = colors.onPrimaryContainer.copy(alpha = 0.38f)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(formatCramExamDate(state.examDateEpochDay))
            }
        }
    }
}

@Composable
private fun DailyDurationCard(
    dailyMinutes: Int,
    onDailyMinutesChanged: (Int) -> Unit,
    configurationEnabled: Boolean
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "每天可学习",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant
                )
                Text(
                    text = "$dailyMinutes 分钟",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            IconButton(
                onClick = {
                    onDailyMinutesChanged(dailyMinutes - DAILY_MINUTES_STEP)
                },
                enabled = configurationEnabled && dailyMinutes > MIN_DAILY_MINUTES
            ) {
                Icon(Icons.Default.Remove, contentDescription = "减少15分钟")
            }
            IconButton(
                onClick = {
                    onDailyMinutesChanged(dailyMinutes + DAILY_MINUTES_STEP)
                },
                enabled = configurationEnabled && dailyMinutes < MAX_DAILY_MINUTES
            ) {
                Icon(Icons.Default.Add, contentDescription = "增加15分钟")
            }
        }
    }
}

@Composable
private fun AnalysisActionCard(
    state: CramDashboardUiState,
    onStartAiAnalysis: () -> Unit,
    onCancelAiAnalysis: () -> Unit,
    onRefreshLocal: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onOpenFullReport: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.aiConfigured) {
                        Icons.Default.AutoAwesome
                    } else {
                        Icons.Default.CloudOff
                    },
                    contentDescription = null,
                    tint = colors.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.aiConfigured) "冲刺分析" else "本地分析可用",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = state.analysisMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            if (state.isAnalysisInProgress) {
                Spacer(modifier = Modifier.height(14.dp))
                val progress = state.analysisProgress
                if (progress == null) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        drawStopIndicator = {}
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            if (state.aiConfigured) {
                Button(
                    onClick = onStartAiAnalysis,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isAnalysisInProgress && state.questionCount > 0
                ) {
                    if (state.isAnalysisInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(analysisActionLabel(state.analysisPhase))
                }
                if (state.isAnalysisInProgress) {
                    TextButton(
                        onClick = onCancelAiAnalysis,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("取消分析")
                    }
                }
            } else {
                Text(
                    text = "无需 AI 也会保留题型统计、高频模块和练习队列；配置 AI 后可进一步提炼母规则、口诀与陷阱。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onRefreshLocal,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("刷新本地版")
                    }
                    OutlinedButton(
                        onClick = onOpenAiSettings,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("配置 AI")
                    }
                }
            }
            if (state.content.localReportMarkdown.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenFullReport,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (state.content.aiReportMarkdown.isNullOrBlank()) {
                            "查看本地冲刺指南"
                        } else {
                            "查看完整冲刺总稿"
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(
    title: String,
    supportingText: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.padding(start = 4.dp)
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun TodayTaskCard(
    state: CramDashboardUiState,
    onStart: () -> Unit
) {
    val content = state.content
    val total = content.todayQuizIds.size
    val completed = content.todayCompletedCount.coerceIn(0, total.coerceAtLeast(0))
    val progress = if (total > 0) completed.toFloat() / total else 0f
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Today,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = content.todayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = when {
                    content.todaySummary.isNotBlank() -> content.todaySummary
                    total > 0 -> "完成 $total 道高价值真题，预计 ${state.dailyMinutes} 分钟"
                    else -> "分析完成后，会根据高频模块与错题生成今天的任务。"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (total > 0) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "进度",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        text = "$completed/$total",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFeatureSettings = "tnum"
                        )
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp),
                    color = colors.onSecondaryContainer,
                    trackColor = colors.onSecondaryContainer.copy(alpha = 0.18f),
                    drawStopIndicator = {}
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onStart,
                enabled = total > 0,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.onSecondaryContainer,
                    contentColor = colors.secondaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (completed > 0) "继续今日任务" else "开始今日任务")
            }
        }
    }
}

internal fun cramPrioritySectionTitle(mode: CramPriorityGroupingMode): String {
    return when (mode) {
        CramPriorityGroupingMode.UNAVAILABLE -> "复习优先级"
        CramPriorityGroupingMode.KNOWLEDGE_MODULES -> "模块优先级"
        CramPriorityGroupingMode.MIXED -> "复习分组优先级"
        CramPriorityGroupingMode.QUESTION_TYPE_FALLBACK -> "题型复习顺序"
    }
}

internal fun cramPrioritySectionSupportingText(mode: CramPriorityGroupingMode): String {
    return when (mode) {
        CramPriorityGroupingMode.UNAVAILABLE ->
            "先按覆盖题量；同题量再参考题型与数字考点"
        CramPriorityGroupingMode.KNOWLEDGE_MODULES ->
            "先按覆盖题量；同题量再参考题型与数字考点"
        CramPriorityGroupingMode.MIXED ->
            "知识模块与题型兜底混排；点开查看依据"
        CramPriorityGroupingMode.QUESTION_TYPE_FALLBACK ->
            "题库未标模块，暂按题型与覆盖题量排序"
    }
}

internal fun cramPriorityGroupingDescription(
    mode: CramPriorityGroupingMode,
    totalQuestionCount: Int
): String {
    return when (mode) {
        CramPriorityGroupingMode.UNAVAILABLE -> if (totalQuestionCount <= 0) {
            "暂无可分析题目，导入后才会计算复习顺序。"
        } else {
            "分析完成后，会根据这份题库实际包含的信息生成复习分组。"
        }
        CramPriorityGroupingMode.KNOWLEDGE_MODULES ->
            "当前依据题库的“出处/依据”字段形成知识模块。"
        CramPriorityGroupingMode.MIXED ->
            "有出处的题按知识模块归组，其余题因缺少模块信息按题型兜底，当前是混合排序。"
        CramPriorityGroupingMode.QUESTION_TYPE_FALLBACK ->
            "这份题库没有可用的模块/出处字段，当前 " +
                "${totalQuestionCount.coerceAtLeast(0)} 道题按题型自动分组。" +
                "这里不是知识章节排名。"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CramPriorityExplanationSheet(
    groupingMode: CramPriorityGroupingMode,
    totalQuestionCount: Int,
    moduleCount: Int,
    module: CramPriorityModuleUi?,
    detailRequested: Boolean,
    onDismiss: () -> Unit,
    onBrowseModule: (CramPriorityModuleUi) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var isClosing by remember { mutableStateOf(false) }
    val closeSheet = {
        if (!isClosing) {
            isClosing = true
            scope.launch {
                try {
                    sheetState.hide()
                } finally {
                    onDismiss()
                }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = {
            if (!isClosing) onDismiss()
        },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    paneTitle = if (detailRequested) {
                        "复习分组详情"
                    } else {
                        "复习优先级说明"
                    }
                }
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
        ) {
            when {
                detailRequested && module == null -> {
                    Text(
                        text = "分组已更新",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "分析结果刚刚发生变化，这个分组已不存在。关闭后可查看最新排序。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant
                    )
                }
                module != null -> {
                    PriorityModuleDetailContent(
                        module = module,
                        groupingMode = groupingMode,
                        totalQuestionCount = totalQuestionCount,
                        browseEnabled = !isClosing,
                        onBrowseModule = { selectedModule ->
                            if (!isClosing) {
                                isClosing = true
                                scope.launch {
                                    try {
                                        sheetState.hide()
                                    } finally {
                                        onDismiss()
                                    }
                                    onBrowseModule(selectedModule)
                                }
                            }
                        }
                    )
                }
                else -> {
                    PriorityHelpContent(
                        groupingMode = groupingMode,
                        totalQuestionCount = totalQuestionCount,
                        moduleCount = moduleCount
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                onClick = closeSheet,
                enabled = !isClosing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun PriorityHelpContent(
    groupingMode: CramPriorityGroupingMode,
    totalQuestionCount: Int,
    moduleCount: Int
) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = "复习优先级怎么算？",
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "它是这份题库的三天冲刺复习顺序，不是官方章节重要性、" +
            "考试频率、真实错误率或命中概率。",
        style = MaterialTheme.typography.bodyLarge
    )
    Spacer(modifier = Modifier.height(18.dp))
    PrioritySheetSectionTitle("当前如何分组")
    Spacer(modifier = Modifier.height(8.dp))
    PriorityNotice(
        text = cramPriorityGroupingDescription(groupingMode, totalQuestionCount)
    )
    if (moduleCount == 1) {
        Spacer(modifier = Modifier.height(10.dp))
        PriorityNotice(
            text = "当前只有一个分组，没有先后比较；占题库 100% 也不代表它最重要。"
        )
    }
    Spacer(modifier = Modifier.height(20.dp))
    PrioritySheetSectionTitle("排序规则")
    Spacer(modifier = Modifier.height(10.dp))
    PriorityRule(
        number = 1,
        title = "先看覆盖题量",
        body = "分组里的题越多，越先复习；这样能在有限时间内覆盖更多原题。"
    )
    Spacer(modifier = Modifier.height(12.dp))
    PriorityRule(
        number = 2,
        title = "同题量再看复习信号",
        body = "再参考数字/时限、多选漏选、判断辨析、重复规则，以及是否有解析或依据。"
    )
    Spacer(modifier = Modifier.height(12.dp))
    PriorityRule(
        number = 3,
        title = "当前不使用个人错题率",
        body = "这个排序来自题库结构，不代表你本人在哪个模块最容易错。"
    )
    Spacer(modifier = Modifier.height(20.dp))
    PrioritySheetSectionTitle("卡片如何读")
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "卡片上的“题数”是该分组的原题数；“占题库”只表示它在当前题库中的比例。" +
            "即使若干分组覆盖 100%，也不代表覆盖考试的全部知识点。",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant
    )
}

@Composable
private fun PriorityModuleDetailContent(
    module: CramPriorityModuleUi,
    groupingMode: CramPriorityGroupingMode,
    totalQuestionCount: Int,
    browseEnabled: Boolean,
    onBrowseModule: (CramPriorityModuleUi) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer
    ) {
        Text(
            text = "第 ${module.rank.coerceAtLeast(1)} 优先",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = module.title,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = if (module.isFallback) {
            "这是题型兜底分组，不是官方知识章节。"
        } else {
            "这是根据题库“出处/依据”字段形成的知识模块。"
        },
        style = MaterialTheme.typography.bodyLarge,
        color = colors.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(18.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            PriorityMetric(
                label = "覆盖范围",
                value = buildString {
                    append("${module.questionCount} 题")
                    module.coveragePercent?.let { append(" · 占题库 $it%") }
                }
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = colors.outlineVariant
            )
            PriorityMetric(
                label = "分组方式",
                value = module.reason.ifBlank {
                    cramPriorityGroupingDescription(groupingMode, totalQuestionCount)
                }
            )
            if (module.typeSummary.isNotBlank()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = colors.outlineVariant
                )
                PriorityMetric(
                    label = "题型构成",
                    value = module.typeSummary
                )
            }
            if (module.numericFactCount > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = colors.outlineVariant
                )
                PriorityMetric(
                    label = "数字与时限信号",
                    value = "提取到 ${module.numericFactCount} 处数字/时限表达"
                )
            }
            if (!module.isFallback && module.sourceReferenceCount > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = colors.outlineVariant
                )
                PriorityMetric(
                    label = "题库依据",
                    value = "涉及 ${module.sourceReferenceCount} 个不同出处"
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(20.dp))
    PrioritySheetSectionTitle("为什么排在这里")
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "先按覆盖题量决定顺序；只有题量相同时，才比较数字/时限、" +
            "题型、重复规则、解析与依据等复习信号。",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(12.dp))
    PriorityNotice(
        text = "这是冲刺安排，不代表官方权重、真实难度或考试命中概率。"
    )
    Spacer(modifier = Modifier.height(20.dp))
    Button(
        onClick = { onBrowseModule(module) },
        enabled = browseEnabled && module.quizIds.isNotEmpty(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            if (module.quizIds.isEmpty()) {
                "暂无可浏览题目"
            } else {
                "浏览本组 ${module.quizIds.size} 道题"
            }
        )
        if (module.quizIds.isNotEmpty()) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun PrioritySheetSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun PriorityNotice(text: String) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun PriorityRule(
    number: Int,
    title: String,
    body: String
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer
        ) {
            Box(
                modifier = Modifier.size(30.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PriorityMetric(
    label: String,
    value: String
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(3.dp))
    Text(
        text = value,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun PriorityModuleCard(
    module: CramPriorityModuleUi,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        enabled = module.quizIds.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "第 ${module.rank.coerceAtLeast(1)} 优先"
                },
                shape = CircleShape,
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = module.rank.coerceAtLeast(1).toString(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = module.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val metadata = buildList {
                    add("${module.questionCount} 题")
                    module.coveragePercent?.let { add("占题库 $it%") }
                }.joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (module.reason.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = module.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = if (module.quizIds.isEmpty()) {
                        "暂无可浏览题目"
                    } else {
                        "查看依据与本组题目"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (module.quizIds.isEmpty()) {
                        colors.onSurfaceVariant
                    } else {
                        colors.primary
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (module.quizIds.isEmpty()) {
                    colors.onSurfaceVariant.copy(alpha = 0.48f)
                } else {
                    colors.primary
                }
            )
        }
    }
}

@Composable
private fun MnemonicCard(
    mnemonic: CramMnemonicUi,
    onOpenQuizReference: (CramQuizReferenceTarget, String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = mnemonic.title,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = mnemonic.numberChain,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.2.sp
                ),
                fontWeight = FontWeight.Bold,
                color = colors.primary
            )
            if (mnemonic.explanation.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = mnemonic.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (mnemonic.quizIds.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                val linkStyles = TextLinkStyles(
                    style = SpanStyle(
                        color = colors.primary,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.SemiBold
                    ),
                    pressedStyle = SpanStyle(
                        color = colors.primary.copy(alpha = 0.68f)
                    )
                )
                Text(
                    text = buildCramMnemonicQuizLinks(
                        quizIds = mnemonic.quizIds,
                        memoryPointId = cramMnemonicMemoryPointId(mnemonic.id),
                        linkStyles = linkStyles,
                        onOpenQuizReference = onOpenQuizReference
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

internal fun buildCramMnemonicQuizLinks(
    quizIds: List<Int>,
    memoryPointId: String,
    linkStyles: TextLinkStyles,
    onOpenQuizReference: (CramQuizReferenceTarget, String) -> Unit
): AnnotatedString {
    return buildAnnotatedString {
        append("相关题目：")
        quizIds.forEachIndexed { index, quizId ->
            if (index > 0) append("、")
            withLink(
                LinkAnnotation.Clickable(
                    tag = "cram-quiz-$quizId",
                    styles = linkStyles,
                    linkInteractionListener = {
                        onOpenQuizReference(
                            CramQuizReferenceTarget(
                                kind = CramQuizReferenceKind.DATABASE_ID,
                                value = quizId
                            ),
                            memoryPointId
                        )
                    }
                )
            ) {
                append(quizId.toString())
            }
        }
    }
}

@Composable
private fun QuickReviewCard(
    state: CramDashboardUiState,
    onOpen: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    ActionCard(
        icon = Icons.Default.Schedule,
        title = "考前20分钟",
        description = state.content.quickCardPreview
            ?.takeIf(String::isNotBlank)
            ?: "只保留必背数字链、主体口诀与最高频陷阱。",
        buttonLabel = "打开速记卡",
        enabled = state.content.quickCardAvailable,
        containerColor = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        onClick = onOpen
    )
}

@Composable
private fun SelfTestCard(
    quizCount: Int,
    onStart: () -> Unit
) {
    ActionCard(
        icon = Icons.Default.Quiz,
        title = "30题自测",
        description = if (quizCount > 0) {
            "已从高频模块与易错题中选出 $quizCount 道真题。"
        } else {
            "分析完成后，从原题库选择10道判断、10道单选、10道多选。"
        },
        buttonLabel = "开始自测",
        enabled = quizCount > 0,
        onClick = onStart
    )
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    buttonLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color =
        MaterialTheme.colorScheme.surfaceContainer,
    contentColor: androidx.compose.ui.graphics.Color =
        MaterialTheme.colorScheme.onSurface
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.74f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onClick,
                enabled = enabled
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = buttonLabel
                )
            }
        }
    }
}

@Composable
private fun AnalysisEmptyCard(
    icon: ImageVector,
    title: String,
    message: String
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

private fun analysisActionLabel(phase: CramAnalysisPhase): String {
    return when (phase) {
        CramAnalysisPhase.NOT_STARTED -> "用 AI 深度提炼"
        CramAnalysisPhase.REQUESTED -> "正在准备"
        CramAnalysisPhase.ANALYZING -> "正在分析"
        CramAnalysisPhase.READY -> "刷新 AI 分析"
        CramAnalysisPhase.FAILED -> "重试 AI 分析"
    }
}

private const val MAX_VISIBLE_MODULES = 8
private const val MAX_VISIBLE_MNEMONICS = 8
private const val PRIORITY_HELP_SHEET_KEY = "help"
private const val PRIORITY_MODULE_SHEET_PREFIX = "module:"

private fun priorityModuleSheetKey(moduleId: String): String {
    return "$PRIORITY_MODULE_SHEET_PREFIX$moduleId"
}

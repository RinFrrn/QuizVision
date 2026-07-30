package com.virin.visionquiz.cram

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CramDashboardRoute(
    viewModel: CramDashboardViewModel,
    onChooseExamDate: (Long) -> Unit,
    onOpenPractice: (CramPracticeEntry) -> Unit,
    onOpenQuickCard: () -> Unit,
    onOpenFullReport: () -> Unit,
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
        onOpenFullReport = onOpenFullReport
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
    onOpenFullReport: () -> Unit
) {
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
                title = "模块优先级",
                supportingText = "按覆盖率与易错程度排序"
            )
        }
        if (state.content.priorityModules.isEmpty()) {
            item(key = "priority-empty") {
                AnalysisEmptyCard(
                    icon = Icons.Default.BarChart,
                    title = "等待题库分析",
                    message = "生成后会在这里列出最先该学的模块。"
                )
            }
        } else {
            items(
                items = state.content.priorityModules.take(MAX_VISIBLE_MODULES),
                key = { "module-${it.id}" }
            ) { module ->
                PriorityModuleCard(module)
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
                MnemonicCard(mnemonic)
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
    supportingText: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, start = 2.dp, end = 2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = supportingText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

@Composable
private fun PriorityModuleCard(module: CramPriorityModuleUi) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.BarChart,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp).size(18.dp)
                )
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
                    module.coveragePercent?.let { add("覆盖 $it%") }
                    module.reason.takeIf(String::isNotBlank)?.let(::add)
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
            }
        }
    }
}

@Composable
private fun MnemonicCard(mnemonic: CramMnemonicUi) {
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

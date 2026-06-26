package com.virin.visionquiz.quizstudy

import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.virin.visionquiz.ai.AiExplanationType
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizStudyMode
import com.virin.visionquiz.dao.QuizUiType
import com.virin.visionquiz.dao.ReviewCard
import com.virin.visionquiz.dao.ReviewRating
import com.virin.visionquiz.dao.inferredUiType
import com.virin.visionquiz.util.convertNumToChar
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class QuizRunnerPagerState(
    val currentPage: Int = 0,
    val quizIds: List<Int> = emptyList(),
    val revision: Int = 0,
    val userScrollEnabled: Boolean = true,
    val colors: QuizRunnerComposeColors = QuizRunnerComposeColors(),
    val textSize: QuizRunnerComposeTextSize = QuizRunnerComposeTextSize()
)

internal data class QuizRunnerPageState(
    val quiz: Quiz,
    val mode: QuizStudyMode,
    val selection: Set<Int>,
    val optionOrder: List<Int>,
    val answerVisible: Boolean,
    val reviewMode: Boolean,
    val resultText: String,
    val examSummary: String?,
    val historySummary: String?,
    val historyDetail: String?,
    val showReviewRating: Boolean = false,
    val currentReviewCard: ReviewCard? = null,
    val practiceReviewRating: ReviewRating? = null,
    val aiEnabled: Boolean,
    val aiConfigComplete: Boolean,
    val quickAiState: AiExplanationUiState,
    val detailedAiState: AiExplanationUiState,
    val currentQuizId: Int? = null,
    val submitVisible: Boolean,
    val submitEnabled: Boolean,
    val submitLabel: String
)

internal data class QuizRunnerComposeTextSize(
    val promptSp: Float = 20f,
    val optionSp: Float = 16f,
    val resultSp: Float = 16f,
    val supportSp: Float = 14f
)

internal data class QuizRunnerComposeColors(
    val primary: Color = Color(0xFF386A20),
    val onPrimary: Color = Color.White,
    val primaryContainer: Color = Color(0xFFB7F397),
    val onPrimaryContainer: Color = Color(0xFF082100),
    val secondaryContainer: Color = Color(0xFFDDE8D2),
    val onSecondaryContainer: Color = Color(0xFF151E11),
    val tertiaryContainer: Color = Color(0xFFBCECEB),
    val onTertiaryContainer: Color = Color(0xFF002020),
    val error: Color = Color(0xFFBA1A1A),
    val errorContainer: Color = Color(0xFFFFDAD6),
    val onErrorContainer: Color = Color(0xFF410002),
    val surface: Color = Color.White,
    val onSurface: Color = Color(0xFF1A1C18),
    val surfaceContainer: Color = Color(0xFFF0F1EB),
    val surfaceContainerHigh: Color = Color(0xFFEAECE5),
    val surfaceContainerLow: Color = Color(0xFFF6F7F1),
    val onSurfaceVariant: Color = Color(0xFF44483F),
    val outline: Color = Color(0xFF74796D),
    val outlineVariant: Color = Color(0xFFC4C8BB)
)

internal data class QuizRunnerPagerCallbacks(
    val pageState: (Int) -> QuizRunnerPageState?,
    val onPageSettled: (Int) -> Unit,
    val onOptionClick: (page: Int, optionIndex: Int) -> Unit,
    val onSubmit: (Int) -> Unit,
    val onReviewRating: (page: Int, rating: ReviewRating) -> Unit,
    val onGenerateAi: (page: Int, type: AiExplanationType, forceRefresh: Boolean) -> Unit,
    val onOpenAiSettings: () -> Unit,
    val onScrollChanged: (inProgress: Boolean) -> Unit,
    val aiTrigger: androidx.compose.runtime.MutableState<Int>,
    val renderMarkdown: (TextView, String) -> Unit
)

private fun runnerLightColorScheme(colors: QuizRunnerComposeColors): ColorScheme {
    return lightColorScheme(
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        primaryContainer = colors.primaryContainer,
        onPrimaryContainer = colors.onPrimaryContainer,
        secondaryContainer = colors.secondaryContainer,
        onSecondaryContainer = colors.onSecondaryContainer,
        tertiaryContainer = colors.tertiaryContainer,
        onTertiaryContainer = colors.onTertiaryContainer,
        error = colors.error,
        errorContainer = colors.errorContainer,
        onErrorContainer = colors.onErrorContainer,
        surface = colors.surface,
        onSurface = colors.onSurface,
        surfaceContainer = colors.surfaceContainer,
        surfaceContainerHigh = colors.surfaceContainerHigh,
        surfaceContainerLow = colors.surfaceContainerLow,
        onSurfaceVariant = colors.onSurfaceVariant,
        outline = colors.outline,
        outlineVariant = colors.outlineVariant
    )
}

@Composable
internal fun QuizRunnerPager(
    state: QuizRunnerPagerState,
    callbacks: QuizRunnerPagerCallbacks
) {
    val scheme = runnerLightColorScheme(state.colors)
    MaterialTheme(colorScheme = scheme) {
        if (state.quizIds.isEmpty()) return@MaterialTheme
        val pagerState = rememberPagerState(
            initialPage = state.currentPage.coerceIn(state.quizIds.indices),
            pageCount = { state.quizIds.size }
        )

        LaunchedEffect(state.currentPage, state.quizIds.size, state.revision) {
            val target = state.currentPage.coerceIn(state.quizIds.indices)
            if (pagerState.settledPage != target) {
                pagerState.animateScrollToPage(target)
            }
        }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }
                .distinctUntilChanged()
                .collect(callbacks.onPageSettled)
        }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.isScrollInProgress }
                .distinctUntilChanged()
                .collect(callbacks.onScrollChanged)
        }

        HorizontalPager(
            state = pagerState,
            key = { state.quizIds[it] },
            userScrollEnabled = state.userScrollEnabled,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .testTag("quiz_pager")
        ) { page ->
            callbacks.aiTrigger.value  // subscribe to AI updates
            val pageState = callbacks.pageState(page) ?: return@HorizontalPager
            QuizRunnerPage(
                state = pageState,
                page = page,
                textSize = state.textSize,
                callbacks = callbacks
            )
        }
    }
}

@Composable
private fun QuizRunnerPage(
    state: QuizRunnerPageState,
    page: Int,
    textSize: QuizRunnerComposeTextSize,
    callbacks: QuizRunnerPagerCallbacks
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    top = 12.dp,
                    end = 16.dp,
                    bottom = 12.dp
                )
        ) {
            state.examSummary?.let {
                InfoCard(
                    text = it,
                    background = MaterialTheme.colorScheme.primaryContainer,
                    foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                    textSizeSp = textSize.resultSp
                )
                Spacer(Modifier.height(12.dp))
            }

            Text(
                text = state.quiz.prompt,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = textSize.promptSp.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = (textSize.promptSp + 5f).sp,
                modifier = Modifier.padding(top = 12.dp)
            )
            HorizontalDivider(Modifier.padding(top = 16.dp))
            Spacer(Modifier.height(18.dp))

            state.optionOrder.forEachIndexed { displayIndex, optionIndex ->
                QuizOption(
                    label = "${convertNumToChar(displayIndex)}. ${state.quiz.options[optionIndex]}",
                    selected = optionIndex in state.selection,
                    correct = optionIndex in state.quiz.answer,
                    revealAnswer = state.answerVisible || state.reviewMode,
                    enabled = !state.reviewMode &&
                        !(state.mode != QuizStudyMode.EXAM && state.answerVisible),
                    type = state.quiz.inferredUiType(),
                    textSizeSp = textSize.optionSp,
                    onClick = { callbacks.onOptionClick(page, optionIndex) }
                )
                Spacer(Modifier.height(10.dp))
            }

            if (state.answerVisible || state.reviewMode) {
                Spacer(Modifier.height(6.dp))
                if (!state.showReviewRating) {
                    Text(
                        text = state.resultText,
                        fontSize = textSize.resultSp.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (state.quiz.isCorrectAnswer(state.selection)) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                }
                InfoCard(
                    text = "答案：${shuffledAnswerString(state.quiz.answer, state.optionOrder)}",
                    background = MaterialTheme.colorScheme.secondaryContainer,
                    foreground = MaterialTheme.colorScheme.onSecondaryContainer,
                    textSizeSp = textSize.resultSp
                )
                state.practiceReviewRating?.let { rating ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "本题复习评分",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = textSize.supportSp.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    ReviewRatingBar(
                        page = page,
                        onReviewRating = callbacks.onReviewRating,
                        selectedRating = rating
                    )
                }
            }

            if (state.aiEnabled) {
                Spacer(Modifier.height(14.dp))
                AiSection(state, page, textSize, callbacks)
            }

            state.historySummary?.let { summary ->
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            summary,
                            fontSize = textSize.supportSp.sp,
                            fontWeight = FontWeight.Bold
                        )
                        state.historyDetail?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = textSize.supportSp.sp,
                                lineHeight = (textSize.supportSp + 4f).sp
                            )
                        }
                    }
                }
            }

            if (state.submitVisible) {
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = { callbacks.onSubmit(page) },
                        enabled = state.submitEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text(state.submitLabel)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun QuizRunnerReviewRatingDock(
    page: Int,
    reviewCard: ReviewCard?,
    colors: QuizRunnerComposeColors,
    onReviewRating: (page: Int, rating: ReviewRating) -> Unit
) {
    MaterialTheme(colorScheme = runnerLightColorScheme(colors)) {
        ReviewRatingDock(
            page = page,
            reviewCard = reviewCard,
            onReviewRating = onReviewRating
        )
    }
}

@Composable
private fun ReviewRatingDock(
    page: Int,
    reviewCard: ReviewCard?,
    onReviewRating: (page: Int, rating: ReviewRating) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "给本题记忆打分",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "评分后自动进入下一题",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            ReviewRatingBar(
                page = page,
                reviewCard = reviewCard,
                onReviewRating = onReviewRating
            )
        }
    }
}

@Composable
private fun ReviewRatingBar(
    page: Int,
    onReviewRating: (page: Int, rating: ReviewRating) -> Unit,
    reviewCard: ReviewCard? = null,
    selectedRating: ReviewRating? = null
) {
    val previewIntervals = remember(reviewCard) {
        val card = reviewCard ?: ReviewCard(
            id = 0, quizId = 0, libraryId = 0,
            dueAt = 0, intervalDays = 0.0, easeFactor = 2.5
        )
        SpacedRepetitionScheduler.previewNextIntervals(card)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        ReviewRating.values().forEach { rating ->
            val (container, content) = when (rating) {
                ReviewRating.FORGOT ->
                    MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                ReviewRating.HARD ->
                    Color(0xFFFFDDB3) to Color(0xFF2B1700)
                ReviewRating.GOOD ->
                    MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                ReviewRating.EASY ->
                    MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            }
            Button(
                onClick = { onReviewRating(page, rating) },
                shape = RoundedCornerShape(12.dp),
                border = if (selectedRating == rating) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                } else {
                    null
                },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = container,
                    contentColor = content
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(82.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(rating.emoji, fontSize = 24.sp, lineHeight = 26.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(rating.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    val intervalDays = previewIntervals[rating] ?: 0.0
                    val intervalText = SpacedRepetitionScheduler.formatReviewInterval(intervalDays)
                    Text(
                        intervalText,
                        fontSize = 10.sp,
                        color = content.copy(alpha = 0.7f),
                        lineHeight = 12.sp
                    )
                }
            }
        }
    }
}


private fun shuffledAnswerString(answer: Set<Int>, optionOrder: List<Int>): String {
    if (optionOrder.isEmpty()) return ""
    return answer
        .map { originalIndex -> optionOrder.indexOf(originalIndex) }
        .sorted()
        .joinToString("") { convertNumToChar(it).toString() }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuizOption(
    label: String,
    selected: Boolean,
    correct: Boolean,
    revealAnswer: Boolean,
    enabled: Boolean,
    type: QuizUiType,
    textSizeSp: Float,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val container = when {
        revealAnswer && correct -> colors.primaryContainer
        revealAnswer && selected -> colors.errorContainer
        revealAnswer -> colors.surfaceContainerHigh
        selected -> when (type) {
            QuizUiType.MULTIPLE_CHOICE -> colors.secondaryContainer
            QuizUiType.JUDGEMENT -> colors.tertiaryContainer
            else -> colors.primaryContainer
        }
        else -> colors.surface
    }
    val content = when {
        revealAnswer && correct -> colors.onPrimaryContainer
        revealAnswer && selected -> colors.onErrorContainer
        revealAnswer -> colors.onSurfaceVariant
        selected -> when (type) {
            QuizUiType.MULTIPLE_CHOICE -> colors.onSecondaryContainer
            QuizUiType.JUDGEMENT -> colors.onTertiaryContainer
            else -> colors.onPrimaryContainer
        }
        else -> colors.onSurface
    }
    val outline = when {
        revealAnswer && correct -> colors.primary
        revealAnswer && selected -> colors.error
        selected -> colors.primary
        else -> colors.outlineVariant
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, outline, shape)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick
            )
    ) {
        Text(
            text = label,
            color = content,
            fontSize = textSizeSp.sp,
            lineHeight = (textSizeSp + 5f).sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
        )
    }
}

@Composable
private fun AiSection(
    state: QuizRunnerPageState,
    page: Int,
    textSize: QuizRunnerComposeTextSize,
    callbacks: QuizRunnerPagerCallbacks
) {
    AiStateCard(
        title = "快速复习",
        state = state.quickAiState,
        textSizeSp = textSize.supportSp,
        configComplete = state.aiConfigComplete,
        idleActionLabel = "生成快速复习",
        onAction = {
            if (state.aiConfigComplete) {
                callbacks.onGenerateAi(page, AiExplanationType.QUICK_REVIEW, false)
            } else {
                callbacks.onOpenAiSettings()
            }
        },
        onLongAction = {
            callbacks.onGenerateAi(page, AiExplanationType.QUICK_REVIEW, true)
        },
        renderMarkdown = callbacks.renderMarkdown
    )

    Spacer(Modifier.height(10.dp))
    AiStateCard(
        title = "详细解析",
        state = state.detailedAiState,
        textSizeSp = textSize.supportSp,
        configComplete = state.aiConfigComplete,
        idleActionLabel = "详细解析",
        onAction = {
            if (state.aiConfigComplete) {
                callbacks.onGenerateAi(page, AiExplanationType.DETAILED_ANALYSIS, false)
            } else {
                callbacks.onOpenAiSettings()
            }
        },
        onLongAction = {
            callbacks.onGenerateAi(page, AiExplanationType.DETAILED_ANALYSIS, true)
        },
        renderMarkdown = callbacks.renderMarkdown
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiStateCard(
    title: String,
    state: AiExplanationUiState,
    textSizeSp: Float,
    configComplete: Boolean,
    idleActionLabel: String,
    onAction: () -> Unit,
    onLongAction: () -> Unit,
    renderMarkdown: (TextView, String) -> Unit
) {
    val content = when (state) {
        is AiExplanationUiState.Streaming -> state.content
        is AiExplanationUiState.Success -> state.content
        is AiExplanationUiState.Error -> state.partialContent
        else -> ""
    }
    val status = when (state) {
        AiExplanationUiState.Idle -> if (configComplete) "点击生成" else "请先配置 AI"
        AiExplanationUiState.Loading -> "正在生成..."
        AiExplanationUiState.ConfigurationRequired -> "请先配置 AI"
        is AiExplanationUiState.Streaming -> "正在生成..."
        is AiExplanationUiState.Success -> "生成完成"
        is AiExplanationUiState.Error -> state.message
    }
    val showStatusInsideCard = state !is AiExplanationUiState.Success
    Column(Modifier.fillMaxWidth()) {
        AiStateTitleRow(
            title = title,
            status = (state as? AiExplanationUiState.Success)?.let {
                if (it.fromCache) "已读取缓存" else status
            },
            showRefresh = state is AiExplanationUiState.Success,
            onRefresh = onLongAction
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
        ) {
            Column(Modifier.padding(14.dp)) {
                if (showStatusInsideCard) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.isAiRequestInProgress()) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier
                                    .width(22.dp)
                                    .height(22.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            status,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (state == AiExplanationUiState.Idle ||
                            state == AiExplanationUiState.ConfigurationRequired ||
                            state is AiExplanationUiState.Error
                        ) {
                            Text(
                                text = if (state is AiExplanationUiState.Error) {
                                    "重试"
                                } else {
                                    idleActionLabel
                                },
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .combinedClickable(
                                        onClick = onAction,
                                        onLongClick = onLongAction
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                if (content.isNotBlank()) {
                    if (showStatusInsideCard) {
                        Spacer(Modifier.height(10.dp))
                    }
                    MarkdownText(content, textSizeSp, renderMarkdown)
                }
            }
        }
    }
}

@Composable
private fun AiStateTitleRow(
    title: String,
    status: String?,
    showRefresh: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            if (status != null) {
                Text(
                    text = status,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (showRefresh) {
            FilledTonalIconButton(
                onClick = onRefresh,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "重新生成",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun MarkdownText(
    content: String,
    textSizeSp: Float,
    renderMarkdown: (TextView, String) -> Unit
) {
    val color = MaterialTheme.colorScheme.onSurface
    val markdownTextSizeSp = textSizeSp + 1.5f
    AndroidView(
        factory = { context ->
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, markdownTextSizeSp)
                setTextColor(android.graphics.Color.BLACK)
                setLineSpacing(0f, 1.22f)
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                typeface = Typeface.DEFAULT
            }
        },
        update = { view ->
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, markdownTextSizeSp)
            view.setLineSpacing(0f, 1.22f)
            view.setTextColor(
                android.graphics.Color.argb(
                    (color.alpha * 255).toInt(),
                    (color.red * 255).toInt(),
                    (color.green * 255).toInt(),
                    (color.blue * 255).toInt()
                )
            )
            renderMarkdown(view, content)
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun InfoCard(
    text: String,
    background: Color,
    foreground: Color,
    textSizeSp: Float
) {
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(12.dp))
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text,
            color = foreground,
            fontSize = textSizeSp.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

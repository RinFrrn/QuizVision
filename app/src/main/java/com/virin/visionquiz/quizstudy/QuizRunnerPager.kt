package com.virin.visionquiz.quizstudy

import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.virin.visionquiz.dao.ReviewRating
import com.virin.visionquiz.dao.inferredUiType
import com.virin.visionquiz.util.convertNumToChar
import kotlinx.coroutines.flow.distinctUntilChanged

private val IosEaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val IosEaseIn = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)

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
    val practiceReviewRating: ReviewRating? = null,
    val aiEnabled: Boolean,
    val aiConfigComplete: Boolean,
    val quickAiState: AiExplanationUiState,
    val detailedAiState: AiExplanationUiState,
    val contextualSuggestionsState: AiExplanationUiState = AiExplanationUiState.Idle,
    val contextualSuggestions: List<String> = emptyList(),
    val contextualQaStates: Map<Int, AiExplanationUiState> = emptyMap(),
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
    val onRequestContextualSuggestions: (page: Int) -> Unit,
    val onContextualSuggestionClick: (page: Int, suggestionIndex: Int, suggestionText: String) -> Unit,
    val onScrollChanged: (inProgress: Boolean) -> Unit,
    val aiTrigger: androidx.compose.runtime.MutableState<Int>,
    val renderMarkdown: (TextView, String) -> Unit
)

@Composable
internal fun QuizRunnerPager(
    state: QuizRunnerPagerState,
    callbacks: QuizRunnerPagerCallbacks
) {
    val scheme = lightColorScheme(
        primary = state.colors.primary,
        onPrimary = state.colors.onPrimary,
        primaryContainer = state.colors.primaryContainer,
        onPrimaryContainer = state.colors.onPrimaryContainer,
        secondaryContainer = state.colors.secondaryContainer,
        onSecondaryContainer = state.colors.onSecondaryContainer,
        tertiaryContainer = state.colors.tertiaryContainer,
        onTertiaryContainer = state.colors.onTertiaryContainer,
        error = state.colors.error,
        errorContainer = state.colors.errorContainer,
        onErrorContainer = state.colors.onErrorContainer,
        surface = state.colors.surface,
        onSurface = state.colors.onSurface,
        surfaceContainer = state.colors.surfaceContainer,
        surfaceContainerHigh = state.colors.surfaceContainerHigh,
        surfaceContainerLow = state.colors.surfaceContainerLow,
        onSurfaceVariant = state.colors.onSurfaceVariant,
        outline = state.colors.outline,
        outlineVariant = state.colors.outlineVariant
    )
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
    val animationSpec = tween<androidx.compose.ui.unit.Dp>(
        durationMillis = if (state.showReviewRating) 260 else 190,
        easing = if (state.showReviewRating) IosEaseOut else IosEaseIn
    )
    val bottomContentPadding by animateDpAsState(
        targetValue = if (state.showReviewRating) 174.dp else 12.dp,
        animationSpec = animationSpec,
        label = "review_rating_bottom_padding"
    )
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
                    bottom = bottomContentPadding
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
                        callbacks = callbacks,
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

        AnimatedVisibility(
            visible = state.showReviewRating,
            enter = fadeIn(
                animationSpec = tween(durationMillis = 220, easing = IosEaseOut)
            ) +
                slideInVertically(
                    animationSpec = tween(durationMillis = 260, easing = IosEaseOut),
                    initialOffsetY = { it / 2 }
                ),
            exit = fadeOut(
                animationSpec = tween(durationMillis = 150, easing = IosEaseIn)
            ) +
                slideOutVertically(
                    animationSpec = tween(durationMillis = 190, easing = IosEaseIn),
                    targetOffsetY = { it / 2 }
                ),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReviewRatingDock(
                page = page,
                callbacks = callbacks
            )
        }
    }
}

@Composable
private fun ReviewRatingDock(
    page: Int,
    callbacks: QuizRunnerPagerCallbacks,
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
            .navigationBarsPadding()
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
                callbacks = callbacks
            )
        }
    }
}

@Composable
private fun ReviewRatingBar(
    page: Int,
    callbacks: QuizRunnerPagerCallbacks,
    selectedRating: ReviewRating? = null
) {
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
                onClick = { callbacks.onReviewRating(page, rating) },
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
                    .height(68.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(rating.emoji, fontSize = 24.sp, lineHeight = 26.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(rating.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
    Text("AI 快速复习", fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    AiStateCard(
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

    if (state.quickAiState is AiExplanationUiState.Success) {
        Spacer(Modifier.height(10.dp))
        ContextualSuggestionsSection(state, page, textSize, callbacks)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContextualSuggestionsSection(
    state: QuizRunnerPageState,
    page: Int,
    textSize: QuizRunnerComposeTextSize,
    callbacks: QuizRunnerPagerCallbacks
) {
    Text("上下文建议", fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))

    when (val suggestionsState = state.contextualSuggestionsState) {
        AiExplanationUiState.Idle -> {
            Text(
                "点击生成学习建议",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = (textSize.supportSp - 1).sp,
                modifier = Modifier
                    .combinedClickable(
                        onClick = { callbacks.onRequestContextualSuggestions(page) }
                    )
                    .padding(vertical = 4.dp)
            )
        }
        AiExplanationUiState.Loading,
        is AiExplanationUiState.Streaming -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.width(18.dp).height(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在生成建议...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = (textSize.supportSp - 1).sp
                )
            }
        }
        is AiExplanationUiState.Success -> {
            if (state.contextualSuggestions.isEmpty()) {
                Text(
                    "暂无建议",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = (textSize.supportSp - 1).sp
                )
            } else {
                state.contextualSuggestions.forEachIndexed { index, suggestion ->
                    val qaState = state.contextualQaStates[index]
                    val isExpanded = qaState != null && qaState !is AiExplanationUiState.Idle

                    SuggestionChip(
                        text = suggestion,
                        index = index,
                        isExpanded = isExpanded,
                        textSizeSp = textSize.supportSp,
                        onClick = {
                            callbacks.onContextualSuggestionClick(page, index, suggestion)
                        }
                    )

                    if (isExpanded) {
                        Spacer(Modifier.height(6.dp))
                        AiStateCard(
                            state = qaState,
                            textSizeSp = textSize.supportSp,
                            configComplete = state.aiConfigComplete,
                            idleActionLabel = "生成回答",
                            onAction = {
                                callbacks.onContextualSuggestionClick(page, index, suggestion)
                            },
                            onLongAction = {},
                            renderMarkdown = callbacks.renderMarkdown
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        is AiExplanationUiState.Error -> {
            Text(
                "建议生成失败: ${suggestionsState.message}",
                color = MaterialTheme.colorScheme.error,
                fontSize = (textSize.supportSp - 1).sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "点击重试",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = (textSize.supportSp - 1).sp,
                modifier = Modifier
                    .combinedClickable(
                        onClick = { callbacks.onRequestContextualSuggestions(page) }
                    )
                    .padding(vertical = 4.dp)
            )
        }
        AiExplanationUiState.ConfigurationRequired -> {
            Text(
                "请先配置 AI",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = (textSize.supportSp - 1).sp
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionChip(
    text: String,
    index: Int,
    isExpanded: Boolean,
    textSizeSp: Float,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val backgroundColor = if (isExpanded) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (isExpanded) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(
                1.dp,
                if (isExpanded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape
            )
            .combinedClickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${index + 1}.",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = textSizeSp.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = text,
                color = contentColor,
                fontSize = textSizeSp.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (isExpanded) "▾" else "▸",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = textSizeSp.sp
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiStateCard(
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
        is AiExplanationUiState.Success -> if (state.fromCache) "已读取缓存" else "生成完成"
        is AiExplanationUiState.Error -> state.message
    }
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
            if (content.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                MarkdownText(content, textSizeSp, renderMarkdown)
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
    AndroidView(
        factory = { context ->
            TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                setTextColor(android.graphics.Color.BLACK)
                setLineSpacing(0f, 1.15f)
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(true)
                typeface = Typeface.DEFAULT
            }
        },
        update = { view ->
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
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

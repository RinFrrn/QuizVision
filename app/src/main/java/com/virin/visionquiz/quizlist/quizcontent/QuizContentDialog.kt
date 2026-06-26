package com.virin.visionquiz.quizlist.quizcontent

import android.app.Activity
import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LiveData
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.material.color.MaterialColors
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizUiType
import com.virin.visionquiz.dao.answerString
import com.virin.visionquiz.dao.inferredUiType
import com.virin.visionquiz.dao.typeString
import com.virin.visionquiz.ai.AiExplanationType
import com.virin.visionquiz.quizstudy.AiExplanationUiState
import com.virin.visionquiz.quizstudy.AiRequestKey
import com.virin.visionquiz.quizstudy.existingSimilarAnalysisSubKey
import com.virin.visionquiz.util.MAX_SIMILAR_QUIZ_RESULTS
import com.virin.visionquiz.util.QuizSimilarityIndex
import com.virin.visionquiz.util.SimilarQuizStore
import com.virin.visionquiz.util.convertNumToChar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Public entry points (API unchanged)
// ---------------------------------------------------------------------------

fun showQuizContentDialog(
    context: Context,
    quiz: Quiz,
    allQuizzes: List<Quiz> = listOf(quiz)
) {
    showQuizContentDialog(context, listOf(quiz), 0, allQuizzes)
}

fun showQuizContentDialog(
    context: Context,
    quizzes: List<Quiz>,
    initialIndex: Int,
    allQuizzes: List<Quiz> = quizzes
) {
    if (quizzes.isEmpty()) return

    val activity = context as? Activity ?: return
    val decorView = activity.window.decorView as? ViewGroup ?: return

    val overlay = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    val composeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        (context as? LifecycleOwner)?.let(::setViewTreeLifecycleOwner)
        (context as? ViewModelStoreOwner)?.let(::setViewTreeViewModelStoreOwner)
        (context as? SavedStateRegistryOwner)?.let(::setViewTreeSavedStateRegistryOwner)
        setContent {
            QuizContentTheme(context) {
                QuizContentBottomSheet(
                    context = context,
                    quizzes = quizzes,
                    allQuizzes = allQuizzes,
                    initialIndex = initialIndex,
                    onDismiss = { (overlay.parent as? ViewGroup)?.removeView(overlay) }
                )
            }
        }
    }

    overlay.addView(composeView)
    decorView.addView(overlay)
}

fun showSimilarQuizContentDialog(
    context: Context,
    originQuiz: Quiz,
    similarQuizzes: List<Quiz>,
    allQuizzes: List<Quiz>,
    aiStates: LiveData<Map<AiRequestKey, AiExplanationUiState>>? = null,
    aiConfigComplete: Boolean = false,
    onGenerateExistingSimilarAnalysis: ((List<Quiz>, Boolean) -> Unit)? = null,
    onOpenAiSettings: (() -> Unit)? = null,
    renderMarkdown: ((TextView, String) -> Unit)? = null,
    onQuizClick: (Quiz) -> Unit
) {
    val activity = context as? Activity ?: return
    val decorView = activity.window.decorView as? ViewGroup ?: return

    val overlay = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    val composeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        (context as? LifecycleOwner)?.let(::setViewTreeLifecycleOwner)
        (context as? ViewModelStoreOwner)?.let(::setViewTreeViewModelStoreOwner)
        (context as? SavedStateRegistryOwner)?.let(::setViewTreeSavedStateRegistryOwner)
        setContent {
            QuizContentTheme(context) {
                SimilarQuizContentBottomSheet(
                    context = context,
                    originQuiz = originQuiz,
                    initialSimilarQuizzes = similarQuizzes,
                    allQuizzes = allQuizzes,
                    aiStates = aiStates,
                    aiConfigComplete = aiConfigComplete,
                    onGenerateExistingSimilarAnalysis = onGenerateExistingSimilarAnalysis,
                    onOpenAiSettings = onOpenAiSettings,
                    renderMarkdown = renderMarkdown,
                    onQuizClick = onQuizClick,
                    onDismiss = { (overlay.parent as? ViewGroup)?.removeView(overlay) }
                )
            }
        }
    }

    overlay.addView(composeView)
    decorView.addView(overlay)
}

// ---------------------------------------------------------------------------
// Bottom sheet shell
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuizContentBottomSheet(
    context: Context,
    quizzes: List<Quiz>,
    allQuizzes: List<Quiz>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var visible by remember { mutableStateOf(true) }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = {
                visible = false
                onDismiss()
            },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 6.dp,
            dragHandle = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.height(4.dp),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Box(Modifier.fillMaxWidth(0.1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            },
            contentWindowInsets = { WindowInsets(0) }
        ) {
            QuizContentCard(
                context = context,
                quizzes = quizzes,
                allQuizzes = allQuizzes,
                initialIndex = initialIndex,
                onDismiss = {
                    visible = false
                    onDismiss()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimilarQuizContentBottomSheet(
    context: Context,
    originQuiz: Quiz,
    initialSimilarQuizzes: List<Quiz>,
    allQuizzes: List<Quiz>,
    aiStates: LiveData<Map<AiRequestKey, AiExplanationUiState>>?,
    aiConfigComplete: Boolean,
    onGenerateExistingSimilarAnalysis: ((List<Quiz>, Boolean) -> Unit)?,
    onOpenAiSettings: (() -> Unit)?,
    renderMarkdown: ((TextView, String) -> Unit)?,
    onQuizClick: (Quiz) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var visible by remember { mutableStateOf(true) }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = {
                visible = false
                onDismiss()
            },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 6.dp,
            dragHandle = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.height(4.dp),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Box(Modifier.fillMaxWidth(0.1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            },
            contentWindowInsets = { WindowInsets(0) }
        ) {
            SimilarQuizContentCard(
                context = context,
                originQuiz = originQuiz,
                initialSimilarQuizzes = initialSimilarQuizzes,
                allQuizzes = allQuizzes,
                aiStates = aiStates,
                aiConfigComplete = aiConfigComplete,
                onGenerateExistingSimilarAnalysis = onGenerateExistingSimilarAnalysis,
                onOpenAiSettings = onOpenAiSettings,
                renderMarkdown = renderMarkdown,
                onQuizClick = { quiz ->
                    visible = false
                    onDismiss()
                    onQuizClick(quiz)
                },
                onDismiss = {
                    visible = false
                    onDismiss()
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Main content card (shared by bottom sheet)
// ---------------------------------------------------------------------------

@Composable
private fun QuizContentCard(
    context: Context,
    quizzes: List<Quiz>,
    allQuizzes: List<Quiz>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    val originalIndex = remember(initialIndex, quizzes) {
        initialIndex.coerceIn(quizzes.indices)
    }
    var currentIndex by remember {
        mutableIntStateOf(originalIndex)
    }
    var selectedSimilarQuiz by remember { mutableStateOf<Quiz?>(null) }
    val quiz = selectedSimilarQuiz ?: quizzes[currentIndex]
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var snackbarJob by remember { mutableStateOf<Job?>(null) }

    // Similar-quiz keyword search
    var similarKeywordQuery by remember(quiz.id) { mutableStateOf("") }
    val allQuizzesById = remember(allQuizzes) { allQuizzes.associateBy(Quiz::id) }

    // Defer heavy index: only built when user actually types a keyword
    var similarityIndex by remember { mutableStateOf<QuizSimilarityIndex?>(null) }
    var similarQuizzes by remember(quiz.id) { mutableStateOf<List<Quiz>>(emptyList()) }
    var hasAnalysis by remember(quiz.id) {
        mutableStateOf(SimilarQuizStore.hasAnalysis(context, quiz.libraryId))
    }

    LaunchedEffect(quiz.id, similarKeywordQuery) {
        val query = similarKeywordQuery
        if (query.isBlank()) {
            similarQuizzes = SimilarQuizStore
                .getSimilarQuizIds(context, quiz.libraryId, quiz.id)
                .mapNotNull(allQuizzesById::get)
        } else {
            // Build index on background thread (only once), then search
            val index = similarityIndex ?: withContext(Dispatchers.Default) {
                QuizSimilarityIndex(allQuizzes)
            }.also { similarityIndex = it }
            similarQuizzes = withContext(Dispatchers.Default) {
                index.findSimilar(
                    currentQuiz = quiz,
                    requiredKeywords = query,
                    maxResults = MAX_SIMILAR_QUIZ_RESULTS
                ).map { it.quiz }
            }
        }
    }

    LaunchedEffect(quiz.id) {
        scrollState.scrollTo(0)
    }

    fun clearSimilarNavigation() {
        selectedSimilarQuiz = null
        snackbarJob?.cancel()
        snackbarJob = null
        snackbarHostState.currentSnackbarData?.dismiss()
    }

    fun openSimilarQuiz(similarQuiz: Quiz) {
        selectedSimilarQuiz = similarQuiz
        snackbarJob?.cancel()
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarJob = coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "已跳转到相似题目",
                actionLabel = "返回原题",
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) {
                currentIndex = originalIndex
                selectedSimilarQuiz = null
            }
            snackbarJob = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 720.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            QuizHeader(
                quiz = quiz,
                positionText = if (selectedSimilarQuiz == null) {
                    "第 ${currentIndex + 1} / ${quizzes.size} 题"
                } else {
                    "相似题目"
                }
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = quiz.prompt,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            val visibleOptions = quiz.options.withIndex().filter { it.value.isNotBlank() }
            if (visibleOptions.isNotEmpty()) {
                SectionLabel("选项")
                visibleOptions.forEach { (index, option) ->
                    val isAnswer = index in quiz.answer
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isAnswer) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            }
                        )
                    ) {
                        Text(
                            text = "${convertNumToChar(index)}. $option",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            color = if (isAnswer) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontSize = 16.sp,
                            lineHeight = 23.sp,
                            fontWeight = if (isAnswer) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            SectionLabel(
                if (quiz.inferredUiType() == QuizUiType.SUBJECTIVE) "参考答案" else "答案"
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = "答案：${quiz.answerString()}",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "题型：${quiz.typeString()} · 题库 ID：${quiz.libraryId}",
                modifier = Modifier.padding(top = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )

            SimilarQuizSection(
                quizzes = similarQuizzes,
                hasAnalysis = hasAnalysis,
                keywordQuery = similarKeywordQuery,
                onKeywordQueryChange = { similarKeywordQuery = it },
                onQuizClick = ::openSimilarQuiz
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                enabled = selectedSimilarQuiz == null && currentIndex > 0,
                onClick = {
                    clearSimilarNavigation()
                    currentIndex--
                }
            ) {
                Text("上一题")
            }
            OutlinedButton(
                enabled = selectedSimilarQuiz == null && currentIndex < quizzes.lastIndex,
                onClick = {
                    clearSimilarNavigation()
                    currentIndex++
                }
            ) {
                Text("下一题")
            }
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun SimilarQuizContentCard(
    context: Context,
    originQuiz: Quiz,
    initialSimilarQuizzes: List<Quiz>,
    allQuizzes: List<Quiz>,
    aiStates: LiveData<Map<AiRequestKey, AiExplanationUiState>>?,
    aiConfigComplete: Boolean,
    onGenerateExistingSimilarAnalysis: ((List<Quiz>, Boolean) -> Unit)?,
    onOpenAiSettings: (() -> Unit)?,
    renderMarkdown: ((TextView, String) -> Unit)?,
    onQuizClick: (Quiz) -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()
    var similarKeywordQuery by remember(originQuiz.id) { mutableStateOf("") }
    var similarityIndex by remember { mutableStateOf<QuizSimilarityIndex?>(null) }
    var similarQuizzes by remember(originQuiz.id) { mutableStateOf(initialSimilarQuizzes) }
    var hasAnalysis by remember(originQuiz.id) {
        mutableStateOf(SimilarQuizStore.hasAnalysis(context, originQuiz.libraryId))
    }

    LaunchedEffect(originQuiz.id, similarKeywordQuery, initialSimilarQuizzes, allQuizzes) {
        val query = similarKeywordQuery
        similarQuizzes = if (query.isBlank()) {
            initialSimilarQuizzes
        } else {
            val index = similarityIndex ?: withContext(Dispatchers.Default) {
                QuizSimilarityIndex(allQuizzes)
            }.also { similarityIndex = it }
            withContext(Dispatchers.Default) {
                index.findSimilar(
                    currentQuiz = originQuiz,
                    requiredKeywords = query,
                    maxResults = MAX_SIMILAR_QUIZ_RESULTS
                ).map { it.quiz }
            }
        }
    }

    LaunchedEffect(originQuiz.id) {
        hasAnalysis = SimilarQuizStore.hasAnalysis(context, originQuiz.libraryId)
        scrollState.scrollTo(0)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 720.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            QuizHeader(
                quiz = originQuiz,
                positionText = "相似题目"
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = originQuiz.prompt,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold
            )
            ExistingSimilarAnalysisSection(
                originQuiz = originQuiz,
                similarQuizzes = similarQuizzes,
                aiStates = aiStates,
                aiConfigComplete = aiConfigComplete,
                onGenerate = onGenerateExistingSimilarAnalysis,
                onOpenAiSettings = onOpenAiSettings,
                renderMarkdown = renderMarkdown
            )
            SimilarQuizSection(
                quizzes = similarQuizzes,
                hasAnalysis = hasAnalysis,
                keywordQuery = similarKeywordQuery,
                onKeywordQueryChange = { similarKeywordQuery = it },
                onQuizClick = onQuizClick
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sub-composables
// ---------------------------------------------------------------------------

@Composable
private fun QuizHeader(quiz: Quiz, positionText: String) {
    val (containerColor, contentColor) = when (quiz.inferredUiType()) {
        QuizUiType.SINGLE_CHOICE ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        QuizUiType.MULTIPLE_CHOICE ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        QuizUiType.JUDGEMENT ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        QuizUiType.FILL_BLANK ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        QuizUiType.SUBJECTIVE ->
            MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = positionText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Surface(
            shape = RoundedCornerShape(50),
            color = containerColor,
            contentColor = contentColor
        ) {
            Text(
                text = quiz.inferredUiType().label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun ExistingSimilarAnalysisSection(
    originQuiz: Quiz,
    similarQuizzes: List<Quiz>,
    aiStates: LiveData<Map<AiRequestKey, AiExplanationUiState>>?,
    aiConfigComplete: Boolean,
    onGenerate: ((List<Quiz>, Boolean) -> Unit)?,
    onOpenAiSettings: (() -> Unit)?,
    renderMarkdown: ((TextView, String) -> Unit)?
) {
    val analysisQuizzes = remember(similarQuizzes) { similarQuizzes }
    Spacer(Modifier.height(18.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SectionLabel("AI 相似题辨析")

    if (analysisQuizzes.isEmpty()) {
        Text(
            text = "暂无可分析的相似题",
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    if (aiStates == null || onGenerate == null) {
        Text(
            text = "当前入口暂不支持 AI 辨析",
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    val observedAiStates = aiStates.observeAsState(emptyMap())
    val key = remember(originQuiz.id, analysisQuizzes) {
        AiRequestKey(
            originQuiz.id,
            AiExplanationType.EXISTING_SIMILAR_ANALYSIS,
            existingSimilarAnalysisSubKey(analysisQuizzes)
        )
    }
    val state = observedAiStates.value[key] ?: AiExplanationUiState.Idle

    Text(
        text = "AI 将从 ${analysisQuizzes.size} 道相似题中选择对比对象",
        modifier = Modifier.padding(start = 2.dp, top = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall
    )
    if (state is AiExplanationUiState.Success && state.fromCache) {
        Text(
            text = "已读取缓存",
            modifier = Modifier.padding(start = 2.dp, top = 3.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            when (state) {
                AiExplanationUiState.Idle,
                AiExplanationUiState.ConfigurationRequired -> {
                    val message = if (state == AiExplanationUiState.ConfigurationRequired || !aiConfigComplete) {
                        "需要先完成 AI 配置"
                    } else {
                        "分析当前题与相似题的共同考点、关键差异和易错点"
                    }
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = {
                            if (aiConfigComplete) {
                                onGenerate(analysisQuizzes, false)
                            } else {
                                onOpenAiSettings?.invoke()
                            }
                        },
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        Text(if (aiConfigComplete) "生成 AI 辨析" else "去配置 AI")
                    }
                }
                AiExplanationUiState.Loading -> {
                    SimilarAnalysisLoadingText("正在生成 AI 辨析...")
                }
                is AiExplanationUiState.Streaming -> {
                    SimilarAnalysisLoadingText("正在生成 AI 辨析...")
                    Spacer(Modifier.height(10.dp))
                    AiMarkdownContent(state.content, renderMarkdown)
                }
                is AiExplanationUiState.Success -> {
                    AiMarkdownContent(state.content, renderMarkdown)
                    FilledTonalButton(
                        onClick = { onGenerate(analysisQuizzes, true) },
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Text("重新生成")
                    }
                }
                is AiExplanationUiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (state.partialContent.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        AiMarkdownContent(state.partialContent, renderMarkdown)
                    }
                    TextButton(
                        onClick = { onGenerate(analysisQuizzes, true) },
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        Text("重试")
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarAnalysisLoadingText(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.height(18.dp).width(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun AiMarkdownContent(
    content: String,
    renderMarkdown: ((TextView, String) -> Unit)?
) {
    AndroidView(
        factory = { context ->
            TextView(context).apply {
                setTextColor(MaterialColors.getColor(context, R.attr.colorOnSurface, AndroidColor.BLACK))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setLineSpacing(0f, 1.22f)
            }
        },
        update = { textView ->
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            textView.setLineSpacing(0f, 1.22f)
            if (renderMarkdown != null) {
                renderMarkdown(textView, content)
            } else {
                textView.text = content
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SimilarQuizSection(
    quizzes: List<Quiz>,
    hasAnalysis: Boolean,
    keywordQuery: String,
    onKeywordQueryChange: (String) -> Unit,
    onQuizClick: (Quiz) -> Unit
) {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SectionLabel("相似题目")

    OutlinedTextField(
        value = keywordQuery,
        onValueChange = onKeywordQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        label = { Text("查找匹配关键词") },
        placeholder = { Text("输入题干或答案关键词") },
        supportingText = { Text("多个关键词用空格分隔，匹配内容会高亮显示") },
        singleLine = true
    )

    if (quizzes.isEmpty()) {
        Text(
            text = if (keywordQuery.isNotBlank()) {
                "没有找到匹配关键词的题目"
            } else if (hasAnalysis) {
                "暂无相似题目"
            } else {
                "尚未分析，可在题库功能中使用相似题分析"
            },
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    quizzes.forEachIndexed { index, quiz ->
        Card(
            onClick = { onQuizClick(quiz) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = highlightedKeywordText(
                        text = "${index + 1}. ${quiz.prompt}",
                        keywordQuery = keywordQuery
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3
                )
                Text(
                    text = quiz.inferredUiType().label,
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = highlightedKeywordText(
                        text = quiz.correctOptionsText(),
                        keywordQuery = keywordQuery
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun Quiz.correctOptionsText(): String {
    val optionsText = answer.sorted().joinToString("；") { index ->
        val option = options.getOrNull(index).orEmpty()
        if (option.isBlank()) {
            convertNumToChar(index).toString()
        } else {
            "${convertNumToChar(index)}. $option"
        }
    }
    return "正确选项：$optionsText"
}

@Composable
private fun highlightedKeywordText(text: String, keywordQuery: String): AnnotatedString {
    val highlightStyle = SpanStyle(
        background = MaterialTheme.colorScheme.primaryContainer,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        fontWeight = FontWeight.Bold
    )
    val ranges = remember(text, keywordQuery) {
        keywordHighlightRanges(text, keywordQuery)
    }
    if (ranges.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        var cursor = 0
        ranges.forEach { range ->
            if (cursor < range.first) {
                append(text.substring(cursor, range.first))
            }
            pushStyle(highlightStyle)
            append(text.substring(range.first, range.last + 1))
            pop()
            cursor = range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}

private fun keywordHighlightRanges(text: String, keywordQuery: String): List<IntRange> {
    val keywords = keywordQuery
        .split(Regex("\\s+"))
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
    if (keywords.isEmpty()) return emptyList()

    val ranges = buildList {
        keywords.forEach { keyword ->
            var startIndex = 0
            while (startIndex < text.length) {
                val matchIndex = text.indexOf(keyword, startIndex, ignoreCase = true)
                if (matchIndex < 0) break
                add(matchIndex..(matchIndex + keyword.length - 1))
                startIndex = matchIndex + keyword.length
            }
        }
    }.sortedWith(compareBy<IntRange> { it.first }.thenByDescending { it.last })

    if (ranges.isEmpty()) return emptyList()
    val merged = mutableListOf<IntRange>()
    ranges.forEach { range ->
        val previous = merged.lastOrNull()
        if (previous == null || range.first > previous.last + 1) {
            merged += range
        } else if (range.last > previous.last) {
            merged[merged.lastIndex] = previous.first..range.last
        }
    }
    return merged
}

@Composable
private fun QuizContentTheme(context: Context, content: @Composable () -> Unit) {
    fun color(attr: Int, fallback: Int): Color {
        return Color(MaterialColors.getColor(context, attr, fallback))
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = color(R.attr.colorPrimary, AndroidColor.rgb(56, 106, 32)),
            onPrimary = color(R.attr.colorOnPrimary, AndroidColor.WHITE),
            primaryContainer = color(R.attr.colorPrimaryContainer, AndroidColor.rgb(183, 243, 151)),
            onPrimaryContainer = color(R.attr.colorOnPrimaryContainer, AndroidColor.rgb(8, 33, 0)),
            secondaryContainer = color(R.attr.colorSecondaryContainer, AndroidColor.LTGRAY),
            onSecondaryContainer = color(R.attr.colorOnSecondaryContainer, AndroidColor.DKGRAY),
            tertiaryContainer = color(R.attr.colorTertiaryContainer, AndroidColor.CYAN),
            onTertiaryContainer = color(R.attr.colorOnTertiaryContainer, AndroidColor.DKGRAY),
            errorContainer = color(R.attr.colorErrorContainer, AndroidColor.rgb(255, 218, 214)),
            onErrorContainer = color(R.attr.colorOnErrorContainer, AndroidColor.rgb(65, 0, 2)),
            surface = color(R.attr.colorSurface, AndroidColor.WHITE),
            onSurface = color(R.attr.colorOnSurface, AndroidColor.BLACK),
            surfaceContainer = color(R.attr.colorSurfaceContainer, AndroidColor.rgb(234, 238, 232)),
            surfaceContainerHigh = color(R.attr.colorSurfaceContainerHigh, AndroidColor.rgb(224, 230, 222)),
            surfaceContainerHighest = color(R.attr.colorSurfaceContainerHighest, AndroidColor.rgb(214, 220, 212)),
            onSurfaceVariant = color(R.attr.colorOnSurfaceVariant, AndroidColor.DKGRAY),
            outline = color(R.attr.colorOutline, AndroidColor.GRAY),
            outlineVariant = color(R.attr.colorOutlineVariant, AndroidColor.LTGRAY)
        ),
        content = content
    )
}

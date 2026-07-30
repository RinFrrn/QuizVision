package com.virin.visionquiz.quizlist.quizcontent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
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
import androidx.lifecycle.MutableLiveData
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
import com.virin.visionquiz.ai.AiConfigStore
import com.virin.visionquiz.ai.AiExplanationType
import com.virin.visionquiz.ai.AiExplanationRepository
import com.virin.visionquiz.ai.AiMarkdownRenderer
import com.virin.visionquiz.ai.AiPromptBuilder
import com.virin.visionquiz.preference.SettingsActivity
import com.virin.visionquiz.quizstudy.AiExplanationUiState
import com.virin.visionquiz.quizstudy.AiRequestKey
import com.virin.visionquiz.quizstudy.existingSimilarAnalysisSubKey
import com.virin.visionquiz.quizstudy.isAiRequestInProgress
import com.virin.visionquiz.util.MAX_SIMILAR_QUIZ_RESULTS
import com.virin.visionquiz.util.QuizSimilarityIndex
import com.virin.visionquiz.util.SimilarQuizStore
import com.virin.visionquiz.util.convertNumToChar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Public entry points
// ---------------------------------------------------------------------------

data class QuizContentMemoryPoint(
    val id: String,
    val sourceLabel: String,
    val cue: String,
    val context: String = "",
    val supportingText: String? = null
)

data class QuizContentExtras(
    val memoryPointsByQuizId: Map<Int, List<QuizContentMemoryPoint>> = emptyMap(),
    val preferredMemoryPointId: String? = null,
    val showMemoryPointEmptyState: Boolean = false
)

internal fun orderedQuizContentMemoryPoints(
    quizId: Int,
    extras: QuizContentExtras
): List<QuizContentMemoryPoint> {
    val points = extras.memoryPointsByQuizId[quizId]
        .orEmpty()
        .distinctBy(QuizContentMemoryPoint::id)
    val preferredId = extras.preferredMemoryPointId
    return if (preferredId == null || points.none { it.id == preferredId }) {
        points
    } else {
        points.sortedByDescending { it.id == preferredId }
    }
}

class QuizContentDialogHandle internal constructor(
    private val dismissAction: () -> Unit
) {
    private var dismissed = false

    @MainThread
    fun dismiss() {
        if (dismissed) return
        dismissed = true
        dismissAction()
    }
}

fun showQuizContentDialog(
    context: Context,
    quiz: Quiz,
    allQuizzes: List<Quiz> = listOf(quiz),
    extras: QuizContentExtras = QuizContentExtras()
): QuizContentDialogHandle? =
    showQuizContentDialog(context, listOf(quiz), 0, allQuizzes, extras)

fun showQuizContentDialog(
    context: Context,
    quizzes: List<Quiz>,
    initialIndex: Int,
    allQuizzes: List<Quiz> = quizzes,
    extras: QuizContentExtras = QuizContentExtras(),
    onDismissed: (() -> Unit)? = null
): QuizContentDialogHandle? {
    if (quizzes.isEmpty()) return null

    val activity = context as? Activity ?: return null
    val decorView = activity.window.decorView as? ViewGroup ?: return null

    val overlay = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        isClickable = true
    }
    val handle = QuizContentDialogHandle {
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        onDismissed?.invoke()
    }

    val composeView = ComposeView(context).apply {
        setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
        )
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
                    extras = extras,
                    onDismiss = handle::dismiss
                )
            }
        }
    }

    overlay.addView(composeView)
    decorView.addView(overlay)
    return handle
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
    dismissOnQuizClick: Boolean = true,
    shouldDismissOnQuizClick: ((Quiz) -> Boolean)? = null,
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
        setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
        )
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
                    dismissOnQuizClick = dismissOnQuizClick,
                    shouldDismissOnQuizClick = shouldDismissOnQuizClick,
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
    extras: QuizContentExtras,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(true) }
    var dismissing by remember { mutableStateOf(false) }

    fun closeSheet() {
        if (dismissing) return
        dismissing = true
        coroutineScope.launch {
            runCatching { sheetState.hide() }
            visible = false
            onDismiss()
        }
    }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = ::closeSheet,
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
                extras = extras,
                onDismiss = ::closeSheet
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
    dismissOnQuizClick: Boolean,
    shouldDismissOnQuizClick: ((Quiz) -> Boolean)?,
    onQuizClick: (Quiz) -> Unit,
    onDismiss: () -> Unit
) {
    var allowProgrammaticDismiss by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { allowProgrammaticDismiss || it != SheetValue.Hidden }
    )
    val coroutineScope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(true) }
    var dismissing by remember { mutableStateOf(false) }

    fun closeSheet() {
        if (dismissing) return
        dismissing = true
        coroutineScope.launch {
            allowProgrammaticDismiss = true
            runCatching { sheetState.hide() }
            visible = false
            onDismiss()
        }
    }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = ::closeSheet,
            sheetState = sheetState,
            sheetGesturesEnabled = false,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 6.dp,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            closeSheet()
                        },
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
            contentWindowInsets = { WindowInsets(0) },
            properties = ModalBottomSheetProperties(
                shouldDismissOnBackPress = true,
                shouldDismissOnClickOutside = true
            )
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
                    val shouldDismiss = shouldDismissOnQuizClick?.invoke(quiz) ?: dismissOnQuizClick
                    if (shouldDismiss) {
                        closeSheet()
                    }
                    onQuizClick(quiz)
                },
                onDismiss = ::closeSheet
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
    extras: QuizContentExtras,
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
    val aiConfigStore = remember(context) { AiConfigStore(context.applicationContext) }
    val aiRepository = remember(context) { AiExplanationRepository(context.applicationContext) }
    val aiStates = remember { MutableLiveData<Map<AiRequestKey, AiExplanationUiState>>(emptyMap()) }
    val markdownRenderer = remember(context) { AiMarkdownRenderer(context) }
    var aiConfigComplete by remember { mutableStateOf(aiConfigStore.read().isComplete()) }

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
                selectedSimilarQuiz = null
            }
            snackbarJob = null
        }
    }

    fun updateAiState(key: AiRequestKey, state: AiExplanationUiState) {
        aiStates.postValue(aiStates.value.orEmpty() + (key to state))
    }

    fun openAiSettings() {
        context.startActivity(Intent(context, SettingsActivity::class.java).apply {
            putExtra(
                SettingsActivity.EXTRA_LAUNCH_SOURCE,
                SettingsActivity.LaunchSource.AI_SETTINGS
            )
        })
    }

    fun requestAiExplanation(
        quiz: Quiz,
        type: AiExplanationType,
        forceRefresh: Boolean
    ) {
        val key = AiRequestKey(quiz.id, type)
        val config = aiConfigStore.read()
        aiConfigComplete = config.isComplete()
        if (!config.isComplete()) {
            updateAiState(key, AiExplanationUiState.ConfigurationRequired)
            return
        }
        val currentState = aiStates.value.orEmpty()[key]
        if (currentState.isAiRequestInProgress() && !forceRefresh) return
        updateAiState(key, AiExplanationUiState.Loading)
        coroutineScope.launch {
            var latestPartialContent = ""
            val prompt = AiPromptBuilder.build(
                quiz = quiz,
                type = type,
                taskPrompt = config.promptFor(type),
                selectedAnswer = null
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    aiRepository.getOrGenerate(
                        quizId = quiz.id,
                        libraryId = quiz.libraryId,
                        type = type,
                        config = config,
                        prompt = prompt,
                        forceRefresh = forceRefresh,
                        onPartialContent = { content ->
                            latestPartialContent = content
                            updateAiState(key, AiExplanationUiState.Streaming(content))
                        }
                    )
                }
            }
            result.onSuccess {
                updateAiState(key, AiExplanationUiState.Success(it.content, it.fromCache))
            }.onFailure {
                if (it is CancellationException) return@onFailure
                updateAiState(
                    key,
                    AiExplanationUiState.Error(
                        message = it.message ?: "AI 请求失败",
                        partialContent = latestPartialContent
                    )
                )
            }
        }
    }

    fun requestExistingSimilarAnalysis(
        visibleSimilarQuizzes: List<Quiz>,
        forceRefresh: Boolean
    ) {
        if (visibleSimilarQuizzes.isEmpty()) return
        val key = AiRequestKey(
            quiz.id,
            AiExplanationType.EXISTING_SIMILAR_ANALYSIS,
            existingSimilarAnalysisSubKey(visibleSimilarQuizzes)
        )
        val config = aiConfigStore.read()
        aiConfigComplete = config.isComplete()
        if (!config.isComplete()) {
            updateAiState(key, AiExplanationUiState.ConfigurationRequired)
            return
        }
        val currentState = aiStates.value.orEmpty()[key]
        if (currentState.isAiRequestInProgress() && !forceRefresh) return
        updateAiState(key, AiExplanationUiState.Loading)
        coroutineScope.launch {
            var latestPartialContent = ""
            val prompt = AiPromptBuilder.buildExistingSimilarAnalysis(
                quiz = quiz,
                similarQuizzes = visibleSimilarQuizzes,
                selectedAnswer = null
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    aiRepository.getOrGenerate(
                        quizId = quiz.id,
                        libraryId = quiz.libraryId,
                        type = AiExplanationType.EXISTING_SIMILAR_ANALYSIS,
                        config = config,
                        prompt = prompt,
                        forceRefresh = forceRefresh,
                        strictFingerprint = true,
                        onPartialContent = { content ->
                            latestPartialContent = content
                            updateAiState(key, AiExplanationUiState.Streaming(content))
                        }
                    )
                }
            }
            result.onSuccess {
                updateAiState(key, AiExplanationUiState.Success(it.content, it.fromCache))
            }.onFailure {
                if (it is CancellationException) return@onFailure
                updateAiState(
                    key,
                    AiExplanationUiState.Error(
                        message = it.message ?: "AI 请求失败",
                        partialContent = latestPartialContent
                    )
                )
            }
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
            QuizContentMemoryPointSection(
                quizId = quiz.id,
                extras = extras
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

            DialogAiExplanationSection(
                quiz = quiz,
                aiStates = aiStates,
                aiConfigComplete = aiConfigComplete,
                onGenerate = ::requestAiExplanation,
                onOpenAiSettings = ::openAiSettings,
                renderMarkdown = { target, content ->
                    markdownRenderer.render(target, content)
                }
            )
            ExistingSimilarAnalysisSection(
                originQuiz = quiz,
                similarQuizzes = similarQuizzes,
                aiStates = aiStates,
                aiConfigComplete = aiConfigComplete,
                onGenerate = ::requestExistingSimilarAnalysis,
                onOpenAiSettings = ::openAiSettings,
                renderMarkdown = { target, content ->
                    markdownRenderer.render(target, content)
                }
            )

            SimilarQuizSection(
                quizzes = similarQuizzes,
                hasAnalysis = hasAnalysis,
                keywordQuery = similarKeywordQuery,
                onKeywordQueryChange = { similarKeywordQuery = it },
                onQuizClick = ::openSimilarQuiz
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
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
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-56).dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
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
        QuizTypePill(quiz = quiz)
    }
}

@Composable
private fun QuizContentMemoryPointSection(
    quizId: Int,
    extras: QuizContentExtras
) {
    val points = orderedQuizContentMemoryPoints(quizId, extras)
    if (points.isEmpty() && !extras.showMemoryPointEmptyState) return

    var expanded by remember(quizId) { mutableStateOf(false) }
    val visiblePoints = if (expanded) points else points.take(2)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "本题记忆点",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            if (points.size > 1) {
                Text(
                    text = "${points.size} 条",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        if (points.isEmpty()) {
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        text = "这题暂无关联记忆点",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "先看标准答案和题库解析，不要套用上一题的口诀。",
                        modifier = Modifier.padding(top = 3.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            visiblePoints.forEach { point ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = point.sourceLabel,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = point.cue,
                            modifier = Modifier.padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (point.context.isNotBlank()) {
                            Text(
                                text = point.context,
                                modifier = Modifier.padding(top = 4.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        point.supportingText
                            ?.takeIf(String::isNotBlank)
                            ?.let { supportingText ->
                                Text(
                                    text = supportingText,
                                    modifier = Modifier.padding(top = 5.dp),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                        .copy(alpha = 0.74f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                    }
                }
            }
            if (points.size > 2) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        if (expanded) {
                            "收起"
                        } else {
                            "展开其余 ${points.size - 2} 条"
                        }
                    )
                }
            }
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
private fun DialogAiExplanationSection(
    quiz: Quiz,
    aiStates: LiveData<Map<AiRequestKey, AiExplanationUiState>>,
    aiConfigComplete: Boolean,
    onGenerate: (Quiz, AiExplanationType, Boolean) -> Unit,
    onOpenAiSettings: () -> Unit,
    renderMarkdown: (TextView, String) -> Unit
) {
    val observedAiStates = aiStates.observeAsState(emptyMap())
    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SectionLabel("AI 解析")
    DialogAiStateCard(
        title = "快速复习",
        state = observedAiStates.value[
            AiRequestKey(quiz.id, AiExplanationType.QUICK_REVIEW)
        ] ?: AiExplanationUiState.Idle,
        configComplete = aiConfigComplete,
        idleActionLabel = "生成快速复习",
        onAction = {
            if (aiConfigComplete) {
                onGenerate(quiz, AiExplanationType.QUICK_REVIEW, false)
            } else {
                onOpenAiSettings()
            }
        },
        onLongAction = {
            onGenerate(quiz, AiExplanationType.QUICK_REVIEW, true)
        },
        renderMarkdown = renderMarkdown
    )
    Spacer(Modifier.height(10.dp))
    DialogAiStateCard(
        title = "详细解析",
        state = observedAiStates.value[
            AiRequestKey(quiz.id, AiExplanationType.DETAILED_ANALYSIS)
        ] ?: AiExplanationUiState.Idle,
        configComplete = aiConfigComplete,
        idleActionLabel = "生成详细解析",
        onAction = {
            if (aiConfigComplete) {
                onGenerate(quiz, AiExplanationType.DETAILED_ANALYSIS, false)
            } else {
                onOpenAiSettings()
            }
        },
        onLongAction = {
            onGenerate(quiz, AiExplanationType.DETAILED_ANALYSIS, true)
        },
        renderMarkdown = renderMarkdown
    )
}

@Composable
private fun DialogAiStateCard(
    title: String,
    state: AiExplanationUiState,
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
    val showStatusInsideCard = state !is AiExplanationUiState.Success

    Column(Modifier.fillMaxWidth()) {
        DialogAiTitleRow(
            title = title,
            status = (state as? AiExplanationUiState.Success)?.let {
                if (it.fromCache) "已读取缓存" else "生成完成"
            },
            showRefresh = state is AiExplanationUiState.Success,
            onRefresh = onLongAction
        )
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (showStatusInsideCard) {
                    if (state.isAiRequestInProgress()) {
                        CenteredAiLoadingText(status)
                    } else {
                        CenteredAiActionContent(
                            actionLabel = if (state is AiExplanationUiState.Error) {
                                "重试"
                            } else if (state == AiExplanationUiState.ConfigurationRequired || !configComplete) {
                                "去配置 AI"
                            } else {
                                idleActionLabel
                            },
                            onAction = onAction
                        )
                    }
                }
                if (content.isNotBlank()) {
                    if (showStatusInsideCard) {
                        Spacer(Modifier.height(10.dp))
                    }
                    AiMarkdownContent(content, renderMarkdown)
                }
            }
        }
    }
}

@Composable
private fun DialogAiTitleRow(
    title: String,
    status: String?,
    showRefresh: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 6.dp),
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
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            if (status != null) {
                Text(
                    text = status,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
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
private fun CenteredAiActionContent(
    actionLabel: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TextButton(
            onClick = onAction,
        ) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun CenteredAiLoadingText(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp)
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

    if (analysisQuizzes.isEmpty()) {
        SectionLabel("AI 相似题辨析")
        Text(
            text = "暂无可分析的相似题",
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    if (aiStates == null || onGenerate == null) {
        SectionLabel("AI 相似题辨析")
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

    SimilarAnalysisTitleRow(
        status = buildString {
            append("AI 将从 ${analysisQuizzes.size} 道相似题中选择对比对象")
            if (state is AiExplanationUiState.Success && state.fromCache) {
                append(" · 已读取缓存")
            }
        },
        showRefresh = state is AiExplanationUiState.Success,
        onRefresh = { onGenerate(analysisQuizzes, true) }
    )
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        border = CardDefaults.outlinedCardBorder(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            when (state) {
                AiExplanationUiState.Idle,
                AiExplanationUiState.ConfigurationRequired -> {
                    CenteredAiActionContent(
                        actionLabel = if (aiConfigComplete) "生成 AI 辨析" else "去配置 AI",
                        onAction = {
                            if (aiConfigComplete) {
                                onGenerate(analysisQuizzes, false)
                            } else {
                                onOpenAiSettings?.invoke()
                            }
                        }
                    )
                }
                AiExplanationUiState.Loading -> {
                    CenteredAiLoadingText("正在生成 AI 辨析...")
                }
                is AiExplanationUiState.Streaming -> {
                    CenteredAiLoadingText("正在生成 AI 辨析...")
                    Spacer(Modifier.height(10.dp))
                    AiMarkdownContent(state.content, renderMarkdown)
                }
                is AiExplanationUiState.Success -> {
                    AiMarkdownContent(state.content, renderMarkdown)
                }
                is AiExplanationUiState.Error -> {
                    CenteredAiActionContent(
                        actionLabel = "重试",
                        onAction = { onGenerate(analysisQuizzes, true) }
                    )
                    if (state.partialContent.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        AiMarkdownContent(state.partialContent, renderMarkdown)
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarAnalysisTitleRow(
    status: String,
    showRefresh: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "AI 相似题辨析",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = status,
                modifier = Modifier.padding(top = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
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
        OutlinedCard(
            onClick = { onQuizClick(quiz) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            border = CardDefaults.outlinedCardBorder(),
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
                QuizTypePill(
                    quiz = quiz,
                    modifier = Modifier.padding(top = 8.dp)
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

@Composable
private fun QuizTypePill(quiz: Quiz, modifier: Modifier = Modifier) {
    val (containerColor, contentColor) = quizTypePillColors(quiz)
    Surface(
        modifier = modifier,
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

@Composable
private fun quizTypePillColors(quiz: Quiz): Pair<Color, Color> {
    return when (quiz.inferredUiType()) {
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
}

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

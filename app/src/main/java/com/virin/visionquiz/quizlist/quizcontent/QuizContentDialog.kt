package com.virin.visionquiz.quizlist.quizcontent

import android.app.Dialog
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
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
import com.virin.visionquiz.util.MAX_SIMILAR_QUIZ_RESULTS
import com.virin.visionquiz.util.QuizSimilarityIndex
import com.virin.visionquiz.util.SimilarQuizStore
import com.virin.visionquiz.util.convertNumToChar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

    val dialog = Dialog(context)
    val composeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        (context as? LifecycleOwner)?.let(::setViewTreeLifecycleOwner)
        (context as? ViewModelStoreOwner)?.let(::setViewTreeViewModelStoreOwner)
        (context as? SavedStateRegistryOwner)?.let(::setViewTreeSavedStateRegistryOwner)
        setContent {
            QuizContentTheme(context) {
                QuizContentCard(
                    context = context,
                    quizzes = quizzes,
                    allQuizzes = allQuizzes,
                    initialIndex = initialIndex,
                    onDismiss = dialog::dismiss
                )
            }
        }
    }

    dialog.setContentView(composeView)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            dimAmount = 0.32f
        }
        setWindowAnimations(R.style.QuizContentDialogAnimation)
    }
    dialog.show()
}

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
    var similarKeywordQuery by remember(quiz.id) { mutableStateOf("") }
    val allQuizzesById = remember(allQuizzes) { allQuizzes.associateBy(Quiz::id) }
    val similarityIndex = remember(allQuizzes) { QuizSimilarityIndex(allQuizzes) }
    val similarQuizzes = remember(quiz.id, similarKeywordQuery, allQuizzes) {
        if (similarKeywordQuery.isBlank()) {
            SimilarQuizStore
                .getSimilarQuizIds(context, quiz.libraryId, quiz.id)
                .mapNotNull(allQuizzesById::get)
        } else {
            similarityIndex.findSimilar(
                currentQuiz = quiz,
                requiredKeywords = similarKeywordQuery,
                maxResults = MAX_SIMILAR_QUIZ_RESULTS
            ).map { it.quiz }
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .heightIn(max = 720.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 20.dp)
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
                    hasAnalysis = SimilarQuizStore.hasAnalysis(context, quiz.libraryId),
                    keywordQuery = similarKeywordQuery,
                    onKeywordQueryChange = { similarKeywordQuery = it },
                    onQuizClick = ::openSimilarQuiz
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
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
}

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
        label = { Text("必须包含的关键词") },
        placeholder = { Text("如：SF6 电流互感器") },
        supportingText = { Text("多个关键词以空格分隔，需同时包含") },
        singleLine = true
    )

    if (quizzes.isEmpty()) {
        Text(
            text = if (keywordQuery.isNotBlank()) {
                "没有同时包含这些关键词的题目"
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
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = "${index + 1}. ${quiz.prompt}",
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
                    text = quiz.correctOptionsText(),
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
            surfaceContainer = color(R.attr.colorSurfaceContainer, AndroidColor.LTGRAY),
            surfaceContainerHigh = color(R.attr.colorSurfaceContainerHigh, AndroidColor.LTGRAY),
            surfaceContainerHighest = color(R.attr.colorSurfaceContainerHighest, AndroidColor.LTGRAY),
            onSurfaceVariant = color(R.attr.colorOnSurfaceVariant, AndroidColor.DKGRAY),
            outline = color(R.attr.colorOutline, AndroidColor.GRAY),
            outlineVariant = color(R.attr.colorOutlineVariant, AndroidColor.LTGRAY)
        ),
        content = content
    )
}

package com.virin.visionquiz.quizstudy

import android.content.Intent
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.virin.visionquiz.R
import com.virin.visionquiz.ai.AiConfig
import com.virin.visionquiz.ai.AiConfigStore
import com.virin.visionquiz.ai.AiExplanationType
import com.virin.visionquiz.ai.AiMarkdownRenderer
import com.virin.visionquiz.dao.PracticeSession
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizAnswerRecord
import com.virin.visionquiz.dao.QuizStudyMode
import com.virin.visionquiz.dao.QuizUiType
import com.virin.visionquiz.dao.ReviewRating
import com.virin.visionquiz.dao.inferredUiType
import com.virin.visionquiz.databinding.FragmentQuizRunnerBinding
import com.virin.visionquiz.preference.SettingsActivity
import com.virin.visionquiz.quizlibraryfeatures.QuizLibraryFeaturesFragment
import com.virin.visionquiz.quizlist.quizcontent.showSimilarQuizContentDialog
import com.virin.visionquiz.util.BaseQuizFragment
import com.virin.visionquiz.util.SimilarQuizStore
import com.virin.visionquiz.util.NavigationBackAnimationSource
import com.virin.visionquiz.util.configureQuizTopBar
import com.virin.visionquiz.util.convertNumToChar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuizRunnerFragment : BaseQuizFragment() {

    private var _binding: FragmentQuizRunnerBinding? = null
    private val binding get() = _binding!!
    private val libraryId: Int
        get() = requireArguments().getInt(QuizLibraryFeaturesFragment.LIBRARY_ID)
    private val mode: QuizStudyMode
        get() = QuizStudyMode.values().firstOrNull {
            it.value == requireArguments().getString(MODE)
        } ?: QuizStudyMode.ORDERED_PRACTICE

    private val viewModel: QuizRunnerViewModel by viewModels {
        QuizRunnerViewModel.factory(requireActivity().application, libraryId)
    }

    private var quizzes: List<Quiz> = emptyList()
    private var supportedQuizSource: List<Quiz> = emptyList()
    private var currentIndex = 0
    private var currentSelection: Set<Int> = emptySet()
    private var answerVisible = false
    private var reviewMode = false
    private val examAnswers = linkedMapOf<Int, Set<Int>>()
    private val practiceAnswers = linkedMapOf<Int, Set<Int>>()
    private val practiceAnswerResults = linkedMapOf<Int, Boolean>()
    private val recordedPracticeQuizIds = mutableSetOf<Int>()
    private val reviewRatedQuizIds = mutableSetOf<Int>()
    private var favoriteIds: Set<Int> = emptySet()
    private var hasPreparedQuizList = false
    private var practiceSessionId = 0
    private var practiceSessionStartedAt = 0L
    private var restoredQuizOrderIds: IntArray? = null
    private var optionShuffleEnabled = false
    private val shuffledOptionOrders = mutableMapOf<Int, List<Int>>()  // quizId -> shuffled indices
    private var practiceAnswerSoundEnabled = true
    private var correctAnswerToneGenerator: ToneGenerator? = null
    private var wrongAnswerToneGenerator: ToneGenerator? = null
    private var answerRecords: List<QuizAnswerRecord> = emptyList()
    private val answerRecordsByQuizId = mutableMapOf<Int, List<QuizAnswerRecord>>()
    private var lastAiConfigSignature: String? = null
    private var aiMarkdownRenderer: AiMarkdownRenderer? = null
    private var runnerTextSize = RunnerTextSize.NORMAL
    private val historyDateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerStartedAt: Long = 0L
    private var timerPausedAt: Long = 0L
    private var similarQuizSnackbar: Snackbar? = null
    private var similarQuizOriginIndex: Int? = null
    private var examDurationMillis: Long = 0L
    private var examAutoSubmitted = false
    private var pagerRevision = 0
    private val aiTrigger = mutableStateOf(0)
    private var examSummaryText: String? = null
    private var pagerScrollInProgress = false
    private var pendingPagerRefresh = false
    private var quizPreparationJob: Job? = null
    private val reviewRatingTrigger = mutableStateOf(0)
    private val pagerRefreshRunnable = Runnable {
        if (pendingPagerRefresh) {
            pendingPagerRefresh = false
            refreshPagerState()
        }
    }
    private val practicePersistRunnable = Runnable { flushPracticePersist() }
    private val pagerState = mutableStateOf(QuizRunnerPagerState())
    private var cachedAiConfigStore: AiConfigStore? = null
    private var cachedAiConfig: AiConfig? = null
    private var cachedComposeColors: QuizRunnerComposeColors? = null

    private val timerRunnable = object : Runnable {
        override fun run() {
            updateTimerText()
            scheduleTimerTick()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuizRunnerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        restoreRunnerState(savedInstanceState)
        optionShuffleEnabled = readInitialOptionShuffleEnabled()
        practiceAnswerSoundEnabled = QuizStudySettings.readPracticeAnswerSoundEnabled(requireContext())
        runnerTextSize = readRunnerTextSize()
        configureQuizTopBar(
            binding.toolbar,
            mode.label,
            navigationIconRes = if (mode == QuizStudyMode.EXAM) R.drawable.round_close_24 else R.drawable.round_arrow_back_24,
            onNavigationClick = { requestExitConfirmation(fromNavigationButton = true) }
        )

        binding.previousButton.setOnClickListener { goToQuestion(currentIndex - 1) }
        binding.nextButton.setOnClickListener { goToQuestion(currentIndex + 1) }
        binding.showAnswerButton.setOnClickListener {
            val quiz = quizzes.getOrNull(currentIndex) ?: return@setOnClickListener
            val isSubmitted = mode != QuizStudyMode.EXAM && recordedPracticeQuizIds.contains(quiz.id)
            if (isSubmitted) {
                showSimilarQuizPanel()
            } else {
                showAnswer()
            }
        }
        binding.favoriteButton.setOnClickListener { toggleFavorite() }
        binding.answerCardButton.setOnClickListener { showAnswerCard() }
        setupQuizPager()
        setupReviewRatingBar()
        applyBottomInsets()
        setupExitConfirmation()
        setupRunnerMenu()
        binding.root.post {
            if (_binding != null && lastAiConfigSignature == null) {
                lastAiConfigSignature = currentAiConfigSignature()
            }
        }

        viewModel.favoriteQuizIds.observe(viewLifecycleOwner) { ids ->
            favoriteIds = ids.orEmpty().toSet()
            updateFavoriteButton()
            if (isCurrentAnswerVisibleForAi()) {
                maybeAutoRequestQuickReview(readCachedAiConfig())
            }
            refreshPagerState()
        }
        viewModel.answerRecords.observe(viewLifecycleOwner) { records ->
            answerRecords = records.orEmpty()
            answerRecordsByQuizId.clear()
            refreshPagerState()
        }
        if (mode == QuizStudyMode.REVIEW) {
            if (!hasPreparedQuizList) {
                hasPreparedQuizList = true
                prepareReviewQuizList()
            }
        } else {
            viewModel.quizList.observe(viewLifecycleOwner) { source ->
                if (!hasPreparedQuizList) {
                    hasPreparedQuizList = true
                    prepareQuizList(source.orEmpty())
                }
            }
        }
        viewModel.aiStates.observe(viewLifecycleOwner) { states ->
            val currentQuizId = quizzes.getOrNull(currentIndex)?.id ?: return@observe
            if (!states.orEmpty().keys.any { it.quizId == currentQuizId }) return@observe
            aiTrigger.value++
        }
        viewModel.practiceReviewRatings.observe(viewLifecycleOwner) { ratings ->
            val currentQuizId = quizzes.getOrNull(currentIndex)?.id ?: return@observe
            if (ratings.orEmpty().containsKey(currentQuizId)) {
                refreshPagerState()
            }
        }
    }

    private fun applyBottomInsets() {
        val basePaddingLeft = binding.contentGroup.paddingLeft
        val basePaddingTop = binding.contentGroup.paddingTop
        val basePaddingRight = binding.contentGroup.paddingRight
        val basePaddingBottom = binding.contentGroup.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bottomInset = when {
                imeBottom > 0 -> imeBottom
                else -> navigationBottom
            }
            binding.contentGroup.setPadding(
                basePaddingLeft,
                basePaddingTop,
                basePaddingRight,
                basePaddingBottom + bottomInset
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupQuizPager() {
        binding.quizPager.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.quizPager.setContent {
            QuizRunnerPager(
                state = pagerState.value,
                callbacks = QuizRunnerPagerCallbacks(
                    pageState = ::buildPagerPageState,
                    onPageSettled = ::onPagerPageSettled,
                    onOptionClick = ::onPagerOptionClick,
                    onSubmit = ::onPagerSubmit,
                    onReviewRating = ::onPagerReviewRating,
                    onGenerateAi = ::onPagerGenerateAi,
                    onOpenAiSettings = ::openAiSettings,
                    onScrollChanged = ::onPagerScrollChanged,
                    aiTrigger = aiTrigger,
                    renderMarkdown = { target, content ->
                        markdownRenderer().render(target, content)
                    }
                )
            )
        }
    }

    private fun setupReviewRatingBar() {
        binding.reviewRatingBar.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.reviewRatingBar.setContent {
            reviewRatingTrigger.value
            val pageState = buildPagerPageState(currentIndex)
            if (pageState?.showReviewRating == true) {
                QuizRunnerReviewRatingDock(
                    page = currentIndex,
                    reviewCard = pageState.currentReviewCard,
                    colors = resolveCachedComposeColors(),
                    onReviewRating = ::onPagerReviewRating
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveCurrentQuestionSelection()
        outState.putInt(STATE_CURRENT_INDEX, currentIndex)
        outState.putIntArray(STATE_CURRENT_SELECTION, currentSelection.toIntArray())
        outState.putBoolean(STATE_ANSWER_VISIBLE, answerVisible)
        outState.putBoolean(STATE_REVIEW_MODE, reviewMode)
        outState.putIntArray(STATE_QUIZ_ORDER_IDS, quizzes.map { it.id }.toIntArray())
        outState.putIntArray(STATE_RECORDED_PRACTICE_IDS, recordedPracticeQuizIds.toIntArray())
        outState.putIntArray(STATE_REVIEW_RATED_IDS, reviewRatedQuizIds.toIntArray())
        outState.putIntArray(
            STATE_PRACTICE_ANSWER_RESULTS,
            practiceAnswerResults.flatMap { (quizId, isCorrect) ->
                listOf(quizId, if (isCorrect) 1 else 0)
            }.toIntArray()
        )
        outState.putIntArray(
            STATE_PRACTICE_ANSWER_SELECTIONS,
            encodeAnswerMap(practiceAnswers)
        )
        outState.putIntArray(
            STATE_EXAM_ANSWER_SELECTIONS,
            encodeAnswerMap(examAnswers)
        )
        viewModel.examSessionId.value?.let { outState.putInt(STATE_EXAM_SESSION_ID, it) }
        viewModel.examStartedAt?.let { outState.putLong(STATE_EXAM_STARTED_AT, it) }
        outState.putLong(STATE_TIMER_STARTED_AT, timerStartedAt)
        outState.putLong(STATE_TIMER_PAUSED_AT, timerPausedAt)
        outState.putLong(STATE_EXAM_DURATION_MILLIS, examDurationMillis)
        outState.putBoolean(STATE_EXAM_AUTO_SUBMITTED, examAutoSubmitted)
    }

    override fun onDestroyView() {
        clearSimilarQuizReturn()
        stopTimer()
        quizPreparationJob?.cancel()
        quizPreparationJob = null
        correctAnswerToneGenerator?.release()
        wrongAnswerToneGenerator?.release()
        correctAnswerToneGenerator = null
        wrongAnswerToneGenerator = null
        timerHandler.removeCallbacks(pagerRefreshRunnable)
        timerHandler.removeCallbacks(practicePersistRunnable)
        aiMarkdownRenderer = null
        super.onDestroyView()
        _binding = null
    }

    override fun onPause() {
        saveCurrentQuestionSelection()
        pauseTimerForLifecycle()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        refreshRunnerTextSize()
        resumeTimerForLifecycle()
        cachedAiConfig = null
        cachedComposeColors = null
        if (lastAiConfigSignature == null) {
            binding.root.post {
                if (_binding != null && lastAiConfigSignature == null) {
                    lastAiConfigSignature = currentAiConfigSignature()
                }
            }
        } else {
            val configSignature = currentAiConfigSignature()
            if (lastAiConfigSignature != configSignature) {
                viewModel.clearAiUiStates()
            }
            lastAiConfigSignature = configSignature
        }
        if (_binding != null && quizzes.isNotEmpty()) {
            render()
        }
    }

    private fun restoreRunnerState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        currentIndex = savedInstanceState.getInt(STATE_CURRENT_INDEX, 0)
        currentSelection =
            savedInstanceState.getIntArray(STATE_CURRENT_SELECTION)?.toSet().orEmpty()
        answerVisible = savedInstanceState.getBoolean(STATE_ANSWER_VISIBLE, false)
        reviewMode = savedInstanceState.getBoolean(STATE_REVIEW_MODE, false)
        restoredQuizOrderIds = savedInstanceState.getIntArray(STATE_QUIZ_ORDER_IDS)
        timerStartedAt = savedInstanceState.getLong(STATE_TIMER_STARTED_AT, 0L)
        timerPausedAt = savedInstanceState.getLong(STATE_TIMER_PAUSED_AT, 0L)
        examDurationMillis = savedInstanceState.getLong(
            STATE_EXAM_DURATION_MILLIS,
            readExamDurationMillisFromArgs()
        )
        examAutoSubmitted = savedInstanceState.getBoolean(STATE_EXAM_AUTO_SUBMITTED, false)
        recordedPracticeQuizIds +=
            savedInstanceState.getIntArray(STATE_RECORDED_PRACTICE_IDS)?.toList().orEmpty()
        reviewRatedQuizIds +=
            savedInstanceState.getIntArray(STATE_REVIEW_RATED_IDS)?.toList().orEmpty()
        restorePracticeAnswerResults(savedInstanceState)
        practiceAnswers.putAll(decodeAnswerMap(savedInstanceState, STATE_PRACTICE_ANSWER_SELECTIONS))
        examAnswers.putAll(decodeAnswerMap(savedInstanceState, STATE_EXAM_ANSWER_SELECTIONS))
        if (savedInstanceState.containsKey(STATE_EXAM_SESSION_ID)) {
            viewModel.restoreExamSession(
                sessionId = savedInstanceState.getInt(STATE_EXAM_SESSION_ID),
                startedAt = savedInstanceState.getLong(STATE_EXAM_STARTED_AT, 0L)
                    .takeIf { it > 0L }
            )
        }
    }

    private fun restorePracticeAnswerResults(savedInstanceState: Bundle) {
        val encoded = savedInstanceState.getIntArray(STATE_PRACTICE_ANSWER_RESULTS) ?: return
        var index = 0
        while (index + 1 < encoded.size) {
            practiceAnswerResults[encoded[index++]] = encoded[index++] == 1
        }
    }

    private fun decodeAnswerMap(savedInstanceState: Bundle, key: String): Map<Int, Set<Int>> {
        val encoded = savedInstanceState.getIntArray(key) ?: return emptyMap()
        val restored = linkedMapOf<Int, Set<Int>>()
        var index = 0
        while (index + 1 < encoded.size) {
            val quizId = encoded[index++]
            val count = encoded[index++]
            if (count < 0 || index + count > encoded.size) return restored
            restored[quizId] = encoded.copyOfRange(index, index + count).toSet()
            index += count
        }
        return restored
    }

    private fun encodeAnswerMap(answers: Map<Int, Set<Int>>): IntArray {
        return answers.flatMap { (quizId, selected) ->
            listOf(quizId, selected.size) + selected.toList()
        }.toIntArray()
    }

    private fun prepareReviewQuizList() {
        val selectedIds = requireArguments().getIntArray(QUIZ_IDS)?.toList().orEmpty()
        val restoredOrder = restoredQuizOrderIds?.toList().orEmpty()
        val orderIds = restoredOrder.takeIf { it.isNotEmpty() } ?: selectedIds
        val studyMode = mode
        quizPreparationJob?.cancel()
        quizPreparationJob = viewLifecycleOwner.lifecycleScope.launch {
            val targetQuizzes = withContext(Dispatchers.IO) {
                viewModel.getQuizListByIds(orderIds)
            }
            withContext(Dispatchers.IO) {
                viewModel.loadReviewCardsForQuizIds(orderIds)
            }
            val prepared = withContext(Dispatchers.Default) {
                prepareQuizRunnerSession(
                    source = targetQuizzes,
                    selectedIds = selectedIds,
                    restoredOrderIds = restoredOrder,
                    mode = studyMode
                )
            }
            if (_binding == null) return@launch
            applyPreparedQuizSession(prepared)
        }
    }

    private fun prepareQuizList(source: List<Quiz>) {
        val selectedIds = requireArguments().getIntArray(QUIZ_IDS)?.toList().orEmpty()
        val restoredOrder = restoredQuizOrderIds?.toList().orEmpty()
        val studyMode = mode
        val shouldLoadPracticeSession = isPracticeSessionMode() && restoredOrder.isEmpty()
        val existingTimerStartedAt = timerStartedAt
        quizPreparationJob?.cancel()
        quizPreparationJob = viewLifecycleOwner.lifecycleScope.launch {
            val session = if (shouldLoadPracticeSession) {
                withContext(Dispatchers.IO) { viewModel.getPracticeSession(studyMode) }
            } else {
                null
            }
            val prepared = withContext(Dispatchers.Default) {
                prepareQuizRunnerSession(
                    source = source,
                    selectedIds = selectedIds,
                    restoredOrderIds = restoredOrder,
                    mode = studyMode,
                    practiceSession = session,
                    existingTimerStartedAt = existingTimerStartedAt
                )
            }
            if (_binding == null) return@launch
            applyPreparedQuizSession(prepared)
        }
    }

    private fun applyPreparedQuizSession(prepared: PreparedQuizRunnerSession) {
        supportedQuizSource = prepared.supportedQuizSource
        quizzes = prepared.quizzes
        prepared.practiceRestore?.let { restore ->
            practiceSessionId = restore.sessionId
            practiceSessionStartedAt = restore.sessionStartedAt
            practiceAnswers.clear()
            practiceAnswers.putAll(restore.practiceAnswers)
            practiceAnswerResults.clear()
            practiceAnswerResults.putAll(restore.practiceAnswerResults)
            recordedPracticeQuizIds.clear()
            recordedPracticeQuizIds += restore.recordedPracticeQuizIds
            reviewRatedQuizIds.clear()
            currentIndex = restore.currentIndex
            currentSelection = restore.currentSelection
            answerVisible = restore.answerVisible
            restore.timerStartedAt?.let {
                timerStartedAt = it
                practiceSessionStartedAt = it
            }
        }
        renderPreparedQuizList()
    }

    private fun buildFreshPracticeOrder(source: List<Quiz>): List<Quiz> {
        return if (mode == QuizStudyMode.RANDOM_PRACTICE) source.shuffled() else source
    }

    private fun renderPreparedQuizList() {
        binding.emptyView.isVisible = quizzes.isEmpty()
        binding.contentGroup.isVisible = quizzes.isNotEmpty()
        if (quizzes.isNotEmpty()) {
            currentIndex = currentIndex.coerceIn(0, quizzes.lastIndex)
            if (mode == QuizStudyMode.EXAM) {
                examDurationMillis = if (examDurationMillis > 0L) {
                    examDurationMillis
                } else {
                    readExamDurationMillisFromArgs()
                }
                viewModel.ensureExamSession(quizzes.size)
                if (reviewMode && examSummaryText == null) {
                    examSummaryText = buildExamSummaryText()
                }
            }
            startTimerIfNeeded()
            render()
        }
    }

    private fun startTimerIfNeeded() {
        if (timerStartedAt <= 0L) {
            timerStartedAt = viewModel.examStartedAt ?: System.currentTimeMillis()
        }
        timerHandler.removeCallbacks(timerRunnable)
        updateTimerText()
        scheduleTimerTick()
    }

    private fun scheduleTimerTick() {
        if (_binding == null || quizzes.isEmpty()) return
        if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            timerHandler.postDelayed(timerRunnable, TIMER_TICK_INTERVAL_MS)
        }
    }

    private fun stopTimer() {
        timerHandler.removeCallbacks(timerRunnable)
    }

    private fun pauseTimerForLifecycle() {
        stopTimer()
        if (mode != QuizStudyMode.EXAM && timerStartedAt > 0L && timerPausedAt <= 0L) {
            timerPausedAt = System.currentTimeMillis()
            if (isPracticeSessionMode()) {
                flushPracticePersist()
            }
        }
    }

    private fun resumeTimerForLifecycle() {
        if (_binding == null) return
        applyPausedTimerCompensation()
        if (quizzes.isNotEmpty()) {
            startTimerIfNeeded()
        }
    }

    private fun applyPausedTimerCompensation() {
        if (mode != QuizStudyMode.EXAM && timerStartedAt > 0L && timerPausedAt > 0L) {
            val pausedDuration = (System.currentTimeMillis() - timerPausedAt).coerceAtLeast(0L)
            timerStartedAt += pausedDuration
            practiceSessionStartedAt = timerStartedAt
            timerPausedAt = 0L
            if (isPracticeSessionMode()) {
                persistPracticeSession()
            }
        }
    }

    private fun updateTimerText() {
        if (_binding == null || timerStartedAt <= 0L) return
        val elapsed = currentTimerElapsedMillis()
        if (mode == QuizStudyMode.EXAM) {
            val remaining = (examDurationMillis - elapsed).coerceAtLeast(0L)
            binding.timerText.text = "剩余 ${formatClock(remaining)}"
            if (remaining <= 0L && !reviewMode && !examAutoSubmitted) {
                examAutoSubmitted = true
                autoSubmitExam()
            }
        } else {
            binding.timerText.text = "用时 ${formatClock(elapsed)}"
        }
    }

    private fun currentTimerElapsedMillis(now: Long = System.currentTimeMillis()): Long {
        if (timerStartedAt <= 0L) return 0L
        val effectiveNow = if (mode != QuizStudyMode.EXAM && timerPausedAt > 0L) {
            timerPausedAt
        } else {
            now
        }
        return (effectiveNow - timerStartedAt).coerceAtLeast(0L)
    }

    private fun autoSubmitExam() {
        saveCurrentQuestionSelection()
        Toast.makeText(requireContext(), "考试时间到，已自动交卷", Toast.LENGTH_SHORT).show()
        finishExam()
    }

    private fun formatClock(millis: Long): String {
        val totalSeconds = millis / 1_000L
        val totalMinutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(totalMinutes, seconds)
    }

    private fun readExamDurationMillisFromArgs(): Long {
        val minutes = requireArguments()
            .getInt(EXAM_DURATION_MINUTES, DEFAULT_EXAM_DURATION_MINUTES)
            .coerceIn(1, MAX_EXAM_DURATION_MINUTES)
        return minutes * 60_000L
    }

    private fun loadSelectionForCurrentQuestion() {
        val quiz = quizzes.getOrNull(currentIndex)
        currentSelection = if (quiz != null) {
            if (mode == QuizStudyMode.EXAM) {
                examAnswers[quiz.id].orEmpty()
            } else {
                practiceAnswers[quiz.id].orEmpty()
            }
        } else {
            emptySet()
        }
        answerVisible = reviewMode || (quiz != null && mode != QuizStudyMode.EXAM && recordedPracticeQuizIds.contains(quiz.id))
    }

    private fun render() {
        val quiz = quizzes.getOrNull(currentIndex) ?: return
        val type = quiz.inferredUiType()
        binding.progressText.text = "第 ${currentIndex + 1} / ${quizzes.size} 题"
        binding.typeText.text = type.label
        applyTypeStyle(type)
        binding.bottomControlsContainer.isVisible = true
        binding.bottomControlsDivider.isVisible = true
        binding.previousButton.isVisible = mode != QuizStudyMode.REVIEW
        binding.nextButton.isVisible = mode != QuizStudyMode.REVIEW
        binding.answerCardButton.isVisible = mode != QuizStudyMode.REVIEW
        binding.previousButton.isEnabled = currentIndex > 0
        binding.nextButton.isEnabled = currentIndex < quizzes.lastIndex
        val isPracticeSubmitted =
            isPracticeSessionMode() && recordedPracticeQuizIds.contains(quiz.id)
        val isReviewSubmitted =
            mode == QuizStudyMode.REVIEW && recordedPracticeQuizIds.contains(quiz.id)
        val showAnswerSection = mode != QuizStudyMode.EXAM || reviewMode
        binding.showAnswerButton.isVisible = when {
            mode == QuizStudyMode.REVIEW -> isReviewSubmitted
            else -> showAnswerSection
        }
        configureBottomControlsLayout(isReviewSubmitted)
        if (isPracticeSubmitted || isReviewSubmitted) {
            binding.showAnswerButton.text = "相似题目"
            binding.showAnswerButton.setIconResource(R.drawable.icon_familiar_face_and_zone_24px)
            binding.showAnswerButton.isEnabled = true
        } else {
            binding.showAnswerButton.text = "看答案"
            binding.showAnswerButton.setIconResource(R.drawable.icon_person_raised_hand_24px)
            binding.showAnswerButton.isEnabled = showAnswerSection && !isReviewSubmitted
        }
        binding.reviewRatingBar.isVisible = shouldShowReviewRatingDock(quiz)
        updateFavoriteButton()
        refreshPagerState()
        refreshReviewRatingBarState()
        if (isCurrentAnswerVisibleForAi()) {
            binding.root.post {
                if (_binding != null && isCurrentAnswerVisibleForAi()) {
                    maybeAutoRequestQuickReview(readCachedAiConfig())
                }
            }
        }
    }

    private fun configureBottomControlsLayout(isReviewSubmitted: Boolean) {
        if (mode == QuizStudyMode.REVIEW) {
            updateButtonConstraints(binding.favoriteButton) {
                width = 0
                marginStart = 0
                marginEnd = 0
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                startToEnd = ConstraintLayout.LayoutParams.UNSET
                endToStart = if (isReviewSubmitted) {
                    binding.showAnswerButton.id
                } else {
                    ConstraintLayout.LayoutParams.UNSET
                }
                endToEnd = if (isReviewSubmitted) {
                    ConstraintLayout.LayoutParams.UNSET
                } else {
                    ConstraintLayout.LayoutParams.PARENT_ID
                }
                horizontalChainStyle = ConstraintLayout.LayoutParams.CHAIN_SPREAD
            }
            updateButtonConstraints(binding.showAnswerButton) {
                width = 0
                marginStart = 0
                marginEnd = 0
                startToStart = ConstraintLayout.LayoutParams.UNSET
                startToEnd = binding.favoriteButton.id
                endToStart = ConstraintLayout.LayoutParams.UNSET
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
            return
        }

        updateButtonConstraints(binding.favoriteButton) {
            width = 0
            marginStart = dpToPx(16)
            marginEnd = 0
            startToStart = ConstraintLayout.LayoutParams.UNSET
            startToEnd = binding.previousButton.id
            endToStart = binding.showAnswerButton.id
            endToEnd = ConstraintLayout.LayoutParams.UNSET
            horizontalChainStyle = ConstraintLayout.LayoutParams.CHAIN_SPREAD
        }
        updateButtonConstraints(binding.showAnswerButton) {
            width = 0
            marginStart = 0
            marginEnd = 0
            startToStart = ConstraintLayout.LayoutParams.UNSET
            startToEnd = binding.favoriteButton.id
            endToStart = binding.answerCardButton.id
            endToEnd = ConstraintLayout.LayoutParams.UNSET
        }
    }

    private fun updateButtonConstraints(
        view: View,
        update: ConstraintLayout.LayoutParams.() -> Unit
    ) {
        val params = view.layoutParams as ConstraintLayout.LayoutParams
        params.update()
        view.layoutParams = params
    }

    private fun dpToPx(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun shouldShowReviewRatingDock(quiz: Quiz): Boolean {
        return mode == QuizStudyMode.REVIEW &&
            quiz.id in recordedPracticeQuizIds &&
            quiz.id !in reviewRatedQuizIds
    }

    private fun refreshReviewRatingBarState() {
        reviewRatingTrigger.value++
    }

    private fun refreshPagerState() {
        if (_binding == null) return
        pagerRevision++
        pagerState.value = QuizRunnerPagerState(
            currentPage = currentIndex.coerceIn(0, quizzes.lastIndex.coerceAtLeast(0)),
            quizIds = quizzes.map { it.id },
            revision = pagerRevision,
            userScrollEnabled = mode != QuizStudyMode.REVIEW,
            colors = resolveCachedComposeColors(),
            textSize = QuizRunnerComposeTextSize(
                promptSp = runnerTextSize.promptSp,
                optionSp = runnerTextSize.optionSp,
                resultSp = runnerTextSize.resultSp,
                supportSp = runnerTextSize.supportSp
            )
        )
    }

    private fun readCachedAiConfig(): AiConfig {
        cachedAiConfig?.let { return it }
        val store = cachedAiConfigStore ?: AiConfigStore(requireContext()).also { cachedAiConfigStore = it }
        val config = store.read()
        cachedAiConfig = config
        return config
    }

    private fun markdownRenderer(): AiMarkdownRenderer {
        return aiMarkdownRenderer ?: AiMarkdownRenderer(requireContext()).also {
            aiMarkdownRenderer = it
        }
    }

    private fun invalidateAiConfigCache() {
        cachedAiConfig = null
    }

    private fun resolveCachedComposeColors(): QuizRunnerComposeColors {
        cachedComposeColors?.let { return it }
        val colors = resolveComposeColors()
        cachedComposeColors = colors
        return colors
    }

    private fun buildPagerPageState(index: Int): QuizRunnerPageState? {
        val quiz = quizzes.getOrNull(index) ?: return null
        val aiStates = viewModel.aiStates.value.orEmpty()
        val selection = selectionForPage(index, quiz)
        val pageAnswerVisible = QuizRunnerInteractionPolicy.isAnswerVisible(
            mode = mode,
            reviewMode = reviewMode,
            quizId = quiz.id,
            submittedPracticeQuizIds = recordedPracticeQuizIds
        )
        val shouldPrepareAiSection = pageAnswerVisible && index == currentIndex
        val config = if (shouldPrepareAiSection) readCachedAiConfig() else null
        val isSubmitted = mode != QuizStudyMode.EXAM && quiz.id in recordedPracticeQuizIds
        val isPracticeSubmitted = isPracticeSessionMode() && quiz.id in recordedPracticeQuizIds
        val showReviewRating =
            mode == QuizStudyMode.REVIEW && isSubmitted && quiz.id !in reviewRatedQuizIds
        val currentReviewCard = if (mode == QuizStudyMode.REVIEW) {
            viewModel.getReviewCardForQuiz(quiz.id)
        } else null
        val practiceReviewRating = viewModel.practiceReviewRatings.value.orEmpty()[quiz.id]
            .takeIf { isPracticeSessionMode() && pageAnswerVisible }
        val isLastQuestion = index == quizzes.lastIndex
        val history = buildPracticeHistoryText(quiz, pageAnswerVisible)
        return QuizRunnerPageState(
            quiz = quiz,
            mode = mode,
            selection = selection,
            optionOrder = optionOrderFor(quiz),
            answerVisible = pageAnswerVisible,
            reviewMode = reviewMode,
            resultText = buildResultText(quiz, selection),
            examSummary = examSummaryText.takeIf {
                reviewMode && mode == QuizStudyMode.EXAM
            },
            historySummary = history?.first,
            historyDetail = history?.second,
            showReviewRating = showReviewRating,
            currentReviewCard = currentReviewCard,
            practiceReviewRating = practiceReviewRating,
            aiEnabled = shouldPrepareAiSection && config?.enabled == true,
            aiConfigComplete = config?.isComplete() == true,
            quickAiState = aiStates[
                AiRequestKey(quiz.id, AiExplanationType.QUICK_REVIEW)
            ] ?: AiExplanationUiState.Idle,
            detailedAiState = aiStates[
                AiRequestKey(quiz.id, AiExplanationType.DETAILED_ANALYSIS)
            ] ?: AiExplanationUiState.Idle,
            currentQuizId = quiz.id,
            submitVisible = when {
                reviewMode -> false
                mode == QuizStudyMode.REVIEW -> !isSubmitted
                mode == QuizStudyMode.EXAM -> isLastQuestion
                isPracticeSubmitted -> isLastQuestion
                else -> true
            },
            submitEnabled = when {
                reviewMode -> false
                mode == QuizStudyMode.REVIEW -> !isSubmitted
                mode == QuizStudyMode.EXAM -> true
                isPracticeSubmitted -> isLastQuestion
                else -> true
            },
            submitLabel = when {
                mode == QuizStudyMode.REVIEW -> "提交"
                mode == QuizStudyMode.EXAM -> "交卷"
                isPracticeSubmitted && isLastQuestion -> "交卷"
                else -> "提交"
            }
        )
    }

    private fun selectionForPage(index: Int, quiz: Quiz): Set<Int> {
        if (index == currentIndex) return currentSelection
        return if (mode == QuizStudyMode.EXAM) {
            examAnswers[quiz.id].orEmpty()
        } else {
            practiceAnswers[quiz.id].orEmpty()
        }
    }

    private fun optionOrderFor(quiz: Quiz): List<Int> {
        return if (optionShuffleEnabled) {
            shuffledOptionOrders.getOrPut(quiz.id) { quiz.options.indices.shuffled() }
        } else {
            quiz.options.indices.toList()
        }
    }

    private fun buildPracticeHistoryText(
        quiz: Quiz,
        pageAnswerVisible: Boolean
    ): Pair<String, String>? {
        if (mode == QuizStudyMode.EXAM || !pageAnswerVisible) return null
        val records = answerRecordsByQuizId.getOrPut(quiz.id) {
            answerRecords.asSequence()
                .filter {
                    it.quizId == quiz.id && it.mode != QuizStudyMode.EXAM.value
                }
                .sortedByDescending { it.answeredAt }
                .toList()
        }
        val correctCount = records.count { it.isCorrect }
        val rate = if (records.isEmpty()) 0 else correctCount * 100 / records.size
        val summary =
            "历史作答 ${records.size} 次 · 正确 $correctCount 次 · 正确率 $rate%"
        val detail = if (records.isEmpty()) {
            "暂无历史记录"
        } else {
            records.take(5).mapIndexed { index, record ->
                val result = if (record.isCorrect) "正确" else "错误"
                "${index + 1}. ${historyDateFormat.format(Date(record.answeredAt))}  $result"
            }.joinToString("\n")
        }
        return summary to detail
    }

    private fun resolveComposeColors(): QuizRunnerComposeColors {
        fun color(attr: Int): Color = Color(MaterialColors.getColor(binding.root, attr))
        return QuizRunnerComposeColors(
            primary = color(R.attr.colorPrimary),
            onPrimary = color(R.attr.colorOnPrimary),
            primaryContainer = color(R.attr.colorPrimaryContainer),
            onPrimaryContainer = color(R.attr.colorOnPrimaryContainer),
            secondaryContainer = color(R.attr.colorSecondaryContainer),
            onSecondaryContainer = color(R.attr.colorOnSecondaryContainer),
            tertiaryContainer = color(R.attr.colorTertiaryContainer),
            onTertiaryContainer = color(R.attr.colorOnTertiaryContainer),
            error = color(R.attr.colorError),
            errorContainer = color(R.attr.colorErrorContainer),
            onErrorContainer = color(R.attr.colorOnErrorContainer),
            surface = color(R.attr.colorSurface),
            onSurface = color(R.attr.colorOnSurface),
            surfaceContainer = color(R.attr.colorSurfaceContainer),
            surfaceContainerHigh = color(R.attr.colorSurfaceContainerHigh),
            surfaceContainerLow = color(R.attr.colorSurfaceContainerLow),
            onSurfaceVariant = color(R.attr.colorOnSurfaceVariant),
            outline = color(R.attr.colorOutline),
            outlineVariant = color(R.attr.colorOutlineVariant)
        )
    }

    private fun onPagerPageSettled(page: Int) {
        if (page !in quizzes.indices || page == currentIndex) return
        clearSimilarQuizReturn()
        saveCurrentQuestionSelection(persist = false)
        currentIndex = page
        loadSelectionForCurrentQuestion()
        render()
        persistPracticeSession()
    }

    private fun onPagerOptionClick(page: Int, optionIndex: Int) {
        if (!moveToPagerPage(page)) return
        val quiz = quizzes[page]
        val type = quiz.inferredUiType()
        currentSelection = QuizRunnerInteractionPolicy.nextSelection(
            type = type,
            current = currentSelection,
            optionIndex = optionIndex
        )
        if (mode == QuizStudyMode.EXAM) {
            examAnswers[quiz.id] = currentSelection
        } else {
            practiceAnswers[quiz.id] = currentSelection
        }
        if (shouldAutoSubmitPractice(type)) {
            handleSubmit()
        } else {
            render()
            persistPracticeSession()
        }
    }

    private fun onPagerSubmit(page: Int) {
        if (moveToPagerPage(page)) {
            handleSubmit()
        }
    }

    private fun onPagerReviewRating(page: Int, rating: ReviewRating) {
        if (!moveToPagerPage(page)) return
        if (isPracticeSessionMode()) {
            val quiz = quizzes.getOrNull(currentIndex) ?: return
            if (quiz.id in recordedPracticeQuizIds) {
                viewModel.schedulePracticeReviewRating(quiz.id, rating)
            }
            return
        }
        if (mode != QuizStudyMode.REVIEW) return
        val quiz = quizzes.getOrNull(currentIndex) ?: return
        if (quiz.id !in recordedPracticeQuizIds || !reviewRatedQuizIds.add(quiz.id)) return
        render()
        viewModel.scheduleReview(quiz.id, rating) {
            timerHandler.postDelayed(
                {
                    if (_binding == null || mode != QuizStudyMode.REVIEW) return@postDelayed
                    if (currentIndex < quizzes.lastIndex) {
                        goToQuestion(currentIndex + 1)
                    } else {
                        Toast.makeText(requireContext(), "本次复习完成", Toast.LENGTH_SHORT).show()
                        findNavController().popBackStack()
                    }
                },
                REVIEW_ADVANCE_DELAY_MS
            )
        }
    }

    private fun onPagerGenerateAi(
        page: Int,
        type: AiExplanationType,
        forceRefresh: Boolean
    ) {
        if (moveToPagerPage(page)) {
            requestAi(type, forceRefresh)
        }
    }

    private fun moveToPagerPage(page: Int): Boolean {
        if (page !in quizzes.indices) return false
        if (page != currentIndex) {
            clearSimilarQuizReturn()
            saveCurrentQuestionSelection(persist = false)
            currentIndex = page
            loadSelectionForCurrentQuestion()
        }
        return true
    }

    private fun setupExitConfirmation() {
        if (mode == QuizStudyMode.EXAM || mode == QuizStudyMode.REVIEW) {
            requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
                confirmExit(fromNavigationButton = false)
            }
        }
    }

    private fun setupRunnerMenu() {
        binding.toolbar.menu.clear()
        binding.toolbar.menu.add(Menu.NONE, MENU_TEXT_SIZE, Menu.NONE, "字体大小")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        if (mode != QuizStudyMode.EXAM) {
            binding.toolbar.menu.add(Menu.NONE, MENU_OPTION_SHUFFLE, Menu.NONE, "选项乱序")
                .setCheckable(true)
                .apply {
                    isChecked = optionShuffleEnabled
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                }
            binding.toolbar.menu.add(Menu.NONE, MENU_PRACTICE_SOUND, Menu.NONE, "提交提示音")
                .setCheckable(true)
                .apply {
                    isChecked = practiceAnswerSoundEnabled
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
                }
            if (isPracticeSessionMode()) {
                binding.toolbar.menu.add(Menu.NONE, MENU_RESET_PRACTICE, Menu.NONE, "重置本次背题")
                    .setIcon(R.drawable.round_delete_24)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
        }
        binding.toolbar.menu.add(Menu.NONE, MENU_AI_SETTINGS, Menu.NONE, getString(R.string.ai_settings_title))
            .setIcon(R.drawable.icon_science_24px)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        binding.toolbar.menu.add(
            Menu.NONE,
            MENU_AI_PROFILE,
            Menu.NONE,
            getString(R.string.ai_profile_switch)
        ).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_TEXT_SIZE -> {
                    showTextSizeDialog()
                    true
                }
                MENU_OPTION_SHUFFLE -> {
                    optionShuffleEnabled = !optionShuffleEnabled
                    menuItem.isChecked = optionShuffleEnabled
                    shuffledOptionOrders.clear()
                    QuizStudySettings.saveOptionShuffleEnabled(
                        requireContext(),
                        libraryId,
                        mode,
                        optionShuffleEnabled
                    )
                    if (quizzes.isNotEmpty()) {
                        render()
                    }
                    true
                }
                MENU_PRACTICE_SOUND -> {
                    practiceAnswerSoundEnabled = !practiceAnswerSoundEnabled
                    menuItem.isChecked = practiceAnswerSoundEnabled
                    QuizStudySettings.savePracticeAnswerSoundEnabled(
                        requireContext(),
                        practiceAnswerSoundEnabled
                    )
                    true
                }
                MENU_RESET_PRACTICE -> {
                    confirmResetPracticeSession()
                    true
                }
                MENU_AI_SETTINGS -> {
                    openAiSettings()
                    true
                }
                MENU_AI_PROFILE -> {
                    showAiProfileDialog()
                    true
                }
                else -> false
            }
        }
    }


    private val REQUIRED_DETAIL_AI_TYPES = setOf(
        AiExplanationType.QUICK_REVIEW,
        AiExplanationType.DETAILED_ANALYSIS
    )

    private fun onPagerScrollChanged(inProgress: Boolean) {
        pagerScrollInProgress = inProgress
        if (!inProgress && pendingPagerRefresh) {
            pendingPagerRefresh = false
            refreshPagerState()
        }
    }

    private fun requestAi(type: AiExplanationType, forceRefresh: Boolean) {
        val quiz = quizzes.getOrNull(currentIndex) ?: return
        val config = readCachedAiConfig()
        if (!config.isComplete()) {
            showAiConfigurationRequired()
            return
        }
        viewModel.requestAiExplanation(
            quiz = quiz,
            type = type,
            selectedAnswer = currentSelection.takeIf { it.isNotEmpty() },
            forceRefresh = forceRefresh
        )
    }

    private fun isCurrentAnswerVisibleForAi(): Boolean {
        val quiz = quizzes.getOrNull(currentIndex) ?: return false
        return QuizRunnerInteractionPolicy.isAnswerVisible(
            mode = mode,
            reviewMode = reviewMode,
            quizId = quiz.id,
            submittedPracticeQuizIds = recordedPracticeQuizIds
        )
    }

    private fun maybeAutoRequestQuickReview(config: AiConfig) {
        val quiz = quizzes.getOrNull(currentIndex) ?: return
        val state = viewModel.aiStates.value.orEmpty()[
            AiRequestKey(quiz.id, AiExplanationType.QUICK_REVIEW)
        ] ?: AiExplanationUiState.Idle
        if (state != AiExplanationUiState.Idle) return
        val isCorrect = quiz.isCorrectAnswer(currentSelection)
        val shouldGenerate = shouldAutoRequestQuickReview(
            answerShown = answerVisible || reviewMode,
            isCorrect = isCorrect,
            isFavorite = quiz.id in favoriteIds
        )
        if (!shouldGenerate) return
        if (!config.isComplete()) return
        viewModel.requestAiExplanation(
            quiz = quiz,
            type = AiExplanationType.QUICK_REVIEW,
            selectedAnswer = currentSelection.takeIf { it.isNotEmpty() }
        )
    }

    private fun showAiConfigurationRequired() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_not_configured_title)
            .setMessage(R.string.ai_not_configured_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ai_go_to_settings) { _, _ -> openAiSettings() }
            .show()
    }

    private fun openAiSettings() {
        startActivity(Intent(requireContext(), SettingsActivity::class.java).apply {
            putExtra(
                SettingsActivity.EXTRA_LAUNCH_SOURCE,
                SettingsActivity.LaunchSource.AI_SETTINGS
            )
        })
    }

    private fun showAiProfileDialog() {
        val store = AiConfigStore(requireContext())
        val profiles = store.listProfiles()
        val defaultId = store.getDefaultProfileId()
        val checkedIndex = profiles.indexOfFirst { it.id == defaultId }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_profile_switch)
            .setSingleChoiceItems(
                profiles.map { "${it.name} · ${it.model}" }.toTypedArray(),
                checkedIndex
            ) { dialog, which ->
                val selected = profiles.getOrNull(which) ?: return@setSingleChoiceItems
                if (selected.id != defaultId) {
                    store.setDefaultProfile(selected.id)
                    invalidateAiConfigCache()
                    viewModel.clearAiUiStates()
                    lastAiConfigSignature = currentAiConfigSignature()
                    render()
                    Toast.makeText(
                        requireContext(),
                        selected.name,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun currentAiConfigSignature(): String {
        val config = readCachedAiConfig()
        return listOf(
            config.enabled,
            config.profileId,
            config.baseUrl,
            config.model,
            config.quickReviewPrompt,
            config.analysisPrompt,
            config.techniquePrompt,
            config.mnemonicPrompt
        ).joinToString("\u001f")
    }

    private fun readInitialOptionShuffleEnabled(): Boolean {
        if (mode == QuizStudyMode.EXAM && requireArguments().containsKey(OPTION_SHUFFLE_ENABLED)) {
            return requireArguments().getBoolean(OPTION_SHUFFLE_ENABLED, false)
        }
        return QuizStudySettings.readOptionShuffleEnabled(requireContext(), libraryId, mode)
    }

    private fun showTextSizeDialog() {
        val levels = RunnerTextSize.ENTRIES
        val checkedIndex = levels.indexOfFirst { it == runnerTextSize }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("字体大小")
            .setSingleChoiceItems(
                levels.map { it.label }.toTypedArray(),
                checkedIndex
            ) { dialog, which ->
                val selected = levels.getOrNull(which) ?: RunnerTextSize.NORMAL
                QuizStudySettings.saveRunnerTextSizeLevel(requireContext(), selected.value)
                runnerTextSize = selected
                refreshPagerState()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun requestExitConfirmation(fromNavigationButton: Boolean = false): Boolean {
        if (!isAdded) return false
        confirmExit(fromNavigationButton)
        return true
    }

    private fun confirmExit(fromNavigationButton: Boolean = false) {
        if (mode == QuizStudyMode.REVIEW) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("退出今日学习？")
                .setMessage("已评分的题目会保存到复习计划，未评分的题目仍会留在待复习或待学习中。确定返回题库吗？")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("退出") { _, _ ->
                    stopTimer()
                    if (fromNavigationButton) {
                        NavigationBackAnimationSource.markNextPopFromNavigationButton()
                    }
                    findNavController().popBackStack()
                }
                .show()
            return
        }
        if (mode != QuizStudyMode.EXAM) {
            if (fromNavigationButton) {
                NavigationBackAnimationSource.markNextPopFromNavigationButton()
            }
            findNavController().popBackStack()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("退出考试？")
            .setMessage(
                if (!reviewMode) "当前考试进度会保留在本页状态中，退出后本次未交卷记录不会生成。"
                else "确定返回上一页吗？"
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("退出") { _, _ ->
                stopTimer()
                if (fromNavigationButton) {
                    NavigationBackAnimationSource.markNextPopFromNavigationButton()
                }
                findNavController().popBackStack()
            }
            .show()
    }

    private fun confirmResetPracticeSession() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("重置本次背题？")
            .setMessage("将清除当前${mode.label}的断点和本轮答题状态，题目的作答历史不会删除。")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("重置") { _, _ ->
                viewModel.resetPracticeSession(mode) {
                    rebuildPracticeSessionAfterReset("已重置本次背题")
                }
            }
            .show()
    }

    private fun rebuildPracticeSessionAfterReset(toastMessage: String? = null) {
        practiceSessionId = 0
        practiceSessionStartedAt = System.currentTimeMillis()
        timerStartedAt = practiceSessionStartedAt
        timerPausedAt = 0L
        currentIndex = 0
        currentSelection = emptySet()
        answerVisible = false
        practiceAnswers.clear()
        practiceAnswerResults.clear()
        recordedPracticeQuizIds.clear()
        reviewRatedQuizIds.clear()
        quizzes = buildFreshPracticeOrder(supportedQuizSource)
        renderPreparedQuizList()
        persistPracticeSession()
        toastMessage?.let {
            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isPracticeSessionMode(): Boolean {
        return mode == QuizStudyMode.ORDERED_PRACTICE ||
            mode == QuizStudyMode.RANDOM_PRACTICE
    }

    private fun shouldAutoSubmitPractice(type: QuizUiType): Boolean {
        return mode != QuizStudyMode.EXAM &&
            !answerVisible &&
            type in setOf(QuizUiType.SINGLE_CHOICE, QuizUiType.JUDGEMENT)
    }

    private fun handleSubmit() {
        val quiz = quizzes.getOrNull(currentIndex) ?: return
        if (mode == QuizStudyMode.REVIEW && recordedPracticeQuizIds.contains(quiz.id)) {
            return
        }
        if (isPracticeSessionMode() && recordedPracticeQuizIds.contains(quiz.id)) {
            if (currentIndex == quizzes.lastIndex) {
                confirmFinishPracticeSession()
            }
            return
        }
        if (currentSelection.isEmpty()) {
            Toast.makeText(requireContext(), "请先选择答案", Toast.LENGTH_SHORT).show()
            return
        }

        if (mode == QuizStudyMode.EXAM && !reviewMode) {
            examAnswers[quiz.id] = currentSelection
            if (currentIndex == quizzes.lastIndex) {
                confirmFinishExam()
            } else {
                render()
            }
            return
        }

        answerVisible = true
        if (recordedPracticeQuizIds.add(quiz.id)) {
            val isCorrect = quiz.isCorrectAnswer(currentSelection)
            practiceAnswers[quiz.id] = currentSelection
            practiceAnswerResults[quiz.id] = isCorrect
            playPracticeAnswerTone(isCorrect)
            viewModel.recordPracticeAnswer(
                quiz = quiz,
                selectedAnswer = currentSelection,
                isCorrect = isCorrect,
                mode = mode
            )
            scheduleDefaultPracticeReviewRating(quiz.id, isCorrect)
        }
        render()
        persistPracticeSession()
    }

    private fun scheduleDefaultPracticeReviewRating(quizId: Int, isCorrect: Boolean) {
        if (!isPracticeSessionMode()) return
        viewModel.schedulePracticeReviewRating(
            quizId = quizId,
            rating = PracticeReviewRatingPolicy.defaultRatingForPracticeAnswer(isCorrect)
        )
    }

    private fun playPracticeAnswerTone(isCorrect: Boolean) {
        if (!practiceAnswerSoundEnabled) return
        runCatching {
            if (isCorrect) {
                prepareCorrectAnswerToneGenerator()
                    .startTone(ToneGenerator.TONE_PROP_ACK, PRACTICE_CORRECT_SOUND_DURATION_MS)
            } else {
                prepareWrongAnswerToneGenerator()
                    .startTone(ToneGenerator.TONE_PROP_NACK, PRACTICE_WRONG_SOUND_DURATION_MS)
            }
        }
    }

    private fun prepareCorrectAnswerToneGenerator(): ToneGenerator {
        correctAnswerToneGenerator?.let { return it }
        return ToneGenerator(AudioManager.STREAM_MUSIC, PRACTICE_CORRECT_SOUND_VOLUME)
            .also { correctAnswerToneGenerator = it }
    }

    private fun prepareWrongAnswerToneGenerator(): ToneGenerator {
        wrongAnswerToneGenerator?.let { return it }
        return ToneGenerator(AudioManager.STREAM_MUSIC, PRACTICE_WRONG_SOUND_VOLUME)
            .also { wrongAnswerToneGenerator = it }
    }

    private fun confirmFinishPracticeSession() {
        val stats = buildAnswerCardStats()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("交卷并结束本轮背题？")
            .setMessage("交卷后会展示本轮统计，并重置当前${mode.label}进度；题目的作答历史会保留。")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("交卷") { _, _ ->
                showPracticeResultDialog(stats)
            }
            .show()
    }

    private fun showPracticeResultDialog(stats: AnswerCardStats) {
        val dialogView = inflateResultDialogView(stats)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("本轮背题结果")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("开始新一轮") { _, _ ->
                viewModel.resetPracticeSession(mode) {
                    rebuildPracticeSessionAfterReset("已开始新一轮背题")
                }
            }
            .show()
    }

    private fun showExamResultDialog() {
        val stats = buildAnswerCardStats()
        val dialogView = inflateResultDialogView(stats)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("考试结果")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("查看解析", null)
            .show()
    }

    private fun inflateResultDialogView(stats: AnswerCardStats): View {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_quiz_result, null)
        val answered = stats.correct + stats.incorrect
        val rate = if (answered == 0) 0 else stats.correct * 100 / answered
        view.findViewById<TextView>(R.id.result_accuracy_text).text = "$rate%"
        view.findViewById<TextView>(R.id.stat_correct_count).text = "${stats.correct}"
        view.findViewById<TextView>(R.id.stat_incorrect_count).text = "${stats.incorrect}"
        view.findViewById<TextView>(R.id.stat_unanswered_count).text = "${stats.unanswered}"
        view.findViewById<TextView>(R.id.result_total_text).text = "共 ${stats.total} 题"
        return view
    }

    private fun showAnswer() {
        val quiz = quizzes.getOrNull(currentIndex) ?: return
        answerVisible = true
        if (mode != QuizStudyMode.EXAM && recordedPracticeQuizIds.add(quiz.id)) {
            val isCorrect = currentSelection.isNotEmpty() && quiz.isCorrectAnswer(currentSelection)
            practiceAnswers[quiz.id] = currentSelection
            practiceAnswerResults[quiz.id] = isCorrect
            viewModel.recordPracticeAnswer(
                quiz = quiz,
                selectedAnswer = currentSelection,
                isCorrect = isCorrect,
                mode = mode
            )
            scheduleDefaultPracticeReviewRating(quiz.id, isCorrect)
        }
        render()
        persistPracticeSession()
    }

    private fun confirmFinishExam() {
        val unanswered = quizzes.count { examAnswers[it.id].orEmpty().isEmpty() }
        val message = if (unanswered > 0) {
            "还有 $unanswered 题未作答，确定要交卷吗？"
        } else {
            "确定要交卷吗？"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("交卷确认")
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("交卷") { _, _ ->
                finishExam()
            }
            .show()
    }

    private fun finishExam() {
        val sessionId = viewModel.examSessionId.value
        if (sessionId == null) {
            Toast.makeText(requireContext(), "考试初始化中，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        quizzes.forEach { quiz ->
            examAnswers.putIfAbsent(quiz.id, emptySet())
        }
        viewModel.finishExam(quizzes, examAnswers, sessionId)
        reviewMode = true
        examSummaryText = buildExamSummaryText()
        currentIndex = 0
        loadSelectionForCurrentQuestion()
        render()
        showExamResultDialog()
    }

    private fun buildExamSummaryText(): String {
        val correct = quizzes.count { quiz ->
            quiz.isCorrectAnswer(examAnswers[quiz.id].orEmpty())
        }
        return "考试完成：答对 $correct 题，答错 ${quizzes.size - correct} 题，共 ${quizzes.size} 题"
    }

    private fun toggleFavorite() {
        val quiz = quizzes.getOrNull(currentIndex) ?: return
        val becomingFavorite = quiz.id !in favoriteIds
        viewModel.toggleFavorite(quiz, becomingFavorite)
        if (becomingFavorite && (answerVisible || reviewMode)) {
            val state = viewModel.aiStates.value.orEmpty()[
                AiRequestKey(quiz.id, AiExplanationType.QUICK_REVIEW)
            ] ?: AiExplanationUiState.Idle
            val config = readCachedAiConfig()
            if (state == AiExplanationUiState.Idle && config.isComplete()) {
                viewModel.requestAiExplanation(
                    quiz = quiz,
                    type = AiExplanationType.QUICK_REVIEW,
                    selectedAnswer = currentSelection.takeIf { it.isNotEmpty() }
                )
            }
        }
    }

    private fun updateFavoriteButton() {
        val quiz = quizzes.getOrNull(currentIndex)
        val isFavorite = quiz != null && favoriteIds.contains(quiz.id)
        binding.favoriteButton.text = if (isFavorite) "已收藏" else "收藏"
        binding.favoriteButton.setIconResource(
            if (isFavorite) R.drawable.icon_bookmark_check_24px
            else R.drawable.icon_bookmark_add_24px
        )
    }

    private fun goToQuestion(
        targetIndex: Int,
        clearSimilarReturn: Boolean = true
    ) {
        if (targetIndex !in quizzes.indices || targetIndex == currentIndex) return
        if (clearSimilarReturn) {
            clearSimilarQuizReturn()
        }
        saveCurrentQuestionSelection(persist = false)
        currentIndex = targetIndex
        loadSelectionForCurrentQuestion()
        render()
        persistPracticeSession()
    }

    private fun navigateToSimilarQuiz(targetIndex: Int, originIndex: Int) {
        if (targetIndex !in quizzes.indices || targetIndex == currentIndex) return
        clearSimilarQuizReturn()
        goToQuestion(targetIndex, clearSimilarReturn = false)
        similarQuizOriginIndex = originIndex

        val snackbar = Snackbar.make(
            binding.root,
            "已跳转到相似题目",
            Snackbar.LENGTH_INDEFINITE
        ).setAction("返回原题") {
            val returnIndex = similarQuizOriginIndex
            clearSimilarQuizReturn()
            if (returnIndex != null) {
                goToQuestion(returnIndex, clearSimilarReturn = false)
            }
        }
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (similarQuizSnackbar === transientBottomBar) {
                    similarQuizSnackbar = null
                    similarQuizOriginIndex = null
                }
            }
        })
        similarQuizSnackbar = snackbar
        snackbar.show()
    }

    private fun clearSimilarQuizReturn() {
        val snackbar = similarQuizSnackbar
        similarQuizSnackbar = null
        similarQuizOriginIndex = null
        snackbar?.dismiss()
    }

    private fun showAnswerCard() {
        if (quizzes.isEmpty()) return
        saveCurrentQuestionSelection()

        val dialogView = layoutInflater.inflate(R.layout.dialog_answer_card, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.answer_card_recycler_view)
        renderAnswerCardStats(dialogView, buildAnswerCardStats())
        val adapter = AnswerCardAdapter { index ->
            goToQuestion(index)
        }
        configureAnswerCardGrid(recyclerView)
        recyclerView.adapter = adapter
        adapter.submitItems(buildAnswerCardItems())

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("答题卡")
            .setView(dialogView)
            .setNegativeButton("关闭", null)
            .create()
        adapter.onItemClick = { index ->
            goToQuestion(index)
            dialog.dismiss()
        }
        dialog.show()
    }



    private fun showSimilarQuizPanel() {
        val quiz = quizzes.getOrNull(currentIndex) ?: return
        val originIndex = currentIndex
        val similarIds = SimilarQuizStore.getSimilarQuizIds(requireContext(), libraryId, quiz.id)
        viewLifecycleOwner.lifecycleScope.launch {
            val allQuizzes = withContext(Dispatchers.IO) {
                viewModel.getAllQuizzes()
            }.ifEmpty {
                supportedQuizSource.ifEmpty { quizzes }
            }
            if (_binding == null || !isAdded) return@launch
            val allQuizById = allQuizzes.associateBy(Quiz::id)
            val storedSimilarQuizzes = similarIds.mapNotNull(allQuizById::get)
            showSimilarQuizContentDialog(
                context = requireContext(),
                originQuiz = quiz,
                similarQuizzes = storedSimilarQuizzes,
                allQuizzes = allQuizzes,
                aiStates = viewModel.aiStates,
                aiConfigComplete = readCachedAiConfig().isComplete(),
                onGenerateExistingSimilarAnalysis = { visibleSimilarQuizzes, forceRefresh ->
                    viewModel.requestExistingSimilarAnalysis(
                        quiz = quiz,
                        similarQuizzes = visibleSimilarQuizzes,
                        selectedAnswer = currentSelection.takeIf { it.isNotEmpty() },
                        forceRefresh = forceRefresh
                    )
                },
                onOpenAiSettings = ::openAiSettings,
                renderMarkdown = { target, content ->
                    markdownRenderer().render(target, content)
                },
                onQuizClick = { selectedQuiz ->
                    val targetIndex = quizzes.indexOfFirst { it.id == selectedQuiz.id }
                    if (targetIndex >= 0) {
                        navigateToSimilarQuiz(targetIndex, originIndex)
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "相似题不在本次今日学习中",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }
    }
    private fun configureAnswerCardGrid(recyclerView: RecyclerView) {
        val layoutManager = GridLayoutManager(
            requireContext(),
            ANSWER_CARD_DEFAULT_SPAN_COUNT.coerceAtMost(quizzes.size.coerceAtLeast(1))
        )
        recyclerView.layoutManager = layoutManager
        recyclerView.doOnLayout {
            val spanCount = calculateAnswerCardSpanCount(recyclerView)
            if (layoutManager.spanCount != spanCount) {
                layoutManager.spanCount = spanCount
            }
        }
    }

    private fun calculateAnswerCardSpanCount(recyclerView: RecyclerView): Int {
        val measuredWidth = recyclerView.width - recyclerView.paddingStart - recyclerView.paddingEnd
        val fallbackWidth = resources.displayMetrics.widthPixels -
            recyclerView.paddingStart -
            recyclerView.paddingEnd
        val availableWidth = measuredWidth.takeIf { it > 0 } ?: fallbackWidth
        val minCellWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            ANSWER_CARD_MIN_CELL_WIDTH_DP,
            resources.displayMetrics
        ).toInt()
        return (availableWidth / minCellWidth)
            .coerceAtLeast(1)
            .coerceAtMost(quizzes.size.coerceAtLeast(1))
    }

    private fun renderAnswerCardStats(dialogView: View, stats: AnswerCardStats) {
        dialogView.findViewById<TextView>(R.id.unanswered_count_text).text = "未答 ${stats.unanswered}"
        dialogView.findViewById<TextView>(R.id.answered_count_text).text = "已答 ${stats.answered}"
        dialogView.findViewById<TextView>(R.id.correct_count_text).text = "正确 ${stats.correct}"
        dialogView.findViewById<TextView>(R.id.incorrect_count_text).text = "错误 ${stats.incorrect}"
        dialogView.findViewById<TextView>(R.id.total_count_text).text = "共 ${stats.total} 题"
    }

    private fun buildAnswerCardStats(): AnswerCardStats {
        val statuses = quizzes.map { answerCardStatus(it) }
        return AnswerCardStats(
            total = statuses.size,
            unanswered = statuses.count { it == AnswerCardStatus.UNANSWERED },
            answered = statuses.count { it == AnswerCardStatus.ANSWERED },
            correct = statuses.count { it == AnswerCardStatus.CORRECT },
            incorrect = statuses.count { it == AnswerCardStatus.INCORRECT }
        )
    }

    private fun buildAnswerCardItems(): List<AnswerCardItem> {
        return quizzes.mapIndexed { index, quiz ->
            AnswerCardItem(
                number = index + 1,
                isCurrent = index == currentIndex,
                status = answerCardStatus(quiz)
            )
        }
    }

    private fun answerCardStatus(quiz: Quiz): AnswerCardStatus {
        return if (mode == QuizStudyMode.EXAM) {
            val selected = if (quizzes.getOrNull(currentIndex)?.id == quiz.id) {
                currentSelection
            } else {
                examAnswers[quiz.id].orEmpty()
            }
            when {
                reviewMode && quiz.isCorrectAnswer(selected) -> AnswerCardStatus.CORRECT
                reviewMode -> AnswerCardStatus.INCORRECT
                selected.isNotEmpty() -> AnswerCardStatus.ANSWERED
                else -> AnswerCardStatus.UNANSWERED
            }
        } else {
            when (practiceAnswerResults[quiz.id]) {
                true -> AnswerCardStatus.CORRECT
                false -> AnswerCardStatus.INCORRECT
                null -> AnswerCardStatus.UNANSWERED
            }
        }
    }

    private fun buildResultText(quiz: Quiz): String {
        return buildResultText(quiz, currentSelection)
    }

    private fun buildResultText(quiz: Quiz, selected: Set<Int>): String {
        return when {
            selected.isEmpty() -> "未作答"
            quiz.isCorrectAnswer(selected) -> "回答正确"
            else -> "回答错误，你的答案：${selected.sorted().joinToString("") { convertNumToChar(it).toString() }}"
        }
    }

    private fun refreshRunnerTextSize() {
        val newTextSize = readRunnerTextSize()
        if (newTextSize == runnerTextSize) {
            return
        }
        runnerTextSize = newTextSize
        binding.emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, runnerTextSize.promptSp)
        refreshPagerState()
    }

    private fun readRunnerTextSize(): RunnerTextSize {
        return RunnerTextSize.fromValue(
            QuizStudySettings.readRunnerTextSizeLevel(requireContext())
        )
    }

    private fun saveCurrentQuestionSelection(persist: Boolean = true) {
        if (mode == QuizStudyMode.EXAM) {
            quizzes.getOrNull(currentIndex)?.let { quiz ->
                examAnswers[quiz.id] = currentSelection
            }
        } else {
            quizzes.getOrNull(currentIndex)?.let { quiz ->
                if (currentSelection.isNotEmpty()) {
                    practiceAnswers[quiz.id] = currentSelection
                }
            }
            if (persist) {
                persistPracticeSession()
            }
        }
    }

    private fun applyTypeStyle(type: QuizUiType) {
        val (backgroundAttr, textAttr) = when (type) {
            QuizUiType.SINGLE_CHOICE -> R.attr.colorPrimaryContainer to R.attr.colorOnPrimaryContainer
            QuizUiType.MULTIPLE_CHOICE -> R.attr.colorSecondaryContainer to R.attr.colorOnSecondaryContainer
            QuizUiType.JUDGEMENT -> R.attr.colorTertiaryContainer to R.attr.colorOnTertiaryContainer
            QuizUiType.FILL_BLANK,
            QuizUiType.SUBJECTIVE -> R.attr.colorSurfaceContainerHighest to R.attr.colorOnSurfaceVariant
        }
        binding.typeText.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.getColor(binding.root, backgroundAttr)
        )
        binding.typeText.setTextColor(MaterialColors.getColor(binding.root, textAttr))
    }

    private fun persistPracticeSession() {
        if (!isPracticeSessionMode() || quizzes.isEmpty()) return
        timerHandler.removeCallbacks(practicePersistRunnable)
        timerHandler.postDelayed(practicePersistRunnable, PRACTICE_PERSIST_DEBOUNCE_MS)
    }

    private fun flushPracticePersist() {
        timerHandler.removeCallbacks(practicePersistRunnable)
        if (!isPracticeSessionMode() || quizzes.isEmpty()) return
        val now = System.currentTimeMillis()
        val startedAt = when {
            timerStartedAt > 0L -> now - currentTimerElapsedMillis(now)
            practiceSessionStartedAt > 0L -> practiceSessionStartedAt
            else -> now
        }
        practiceSessionStartedAt = startedAt
        viewModel.savePracticeSession(
            PracticeSession(
                id = practiceSessionId,
                libraryId = libraryId,
                mode = mode.value,
                quizOrder = quizzes.map { it.id }.encodeIntListText(),
                currentIndex = currentIndex.coerceIn(0, quizzes.lastIndex),
                currentSelection = currentSelection.encodeIntSetText(),
                practiceAnswers = practiceAnswers.encodeAnswerMapText(),
                practiceResults = practiceAnswerResults.encodeResultMapText(),
                recordedQuizIds = recordedPracticeQuizIds.encodeIntSetText(),
                answerVisible = answerVisible,
                startedAt = startedAt,
                updatedAt = now
            )
        )
    }

    companion object {
        const val MODE = "mode"
        const val QUIZ_IDS = "quiz_ids"
        const val EXAM_DURATION_MINUTES = "exam_duration_minutes"
        const val OPTION_SHUFFLE_ENABLED = "option_shuffle_enabled"
        private const val MENU_TEXT_SIZE = 1003
        private const val MENU_OPTION_SHUFFLE = 1000
        private const val MENU_PRACTICE_SOUND = 1002
        private const val MENU_RESET_PRACTICE = 1001
        private const val MENU_AI_SETTINGS = 1004
        private const val MENU_AI_PROFILE = 1005
        private const val PRACTICE_CORRECT_SOUND_VOLUME = 85
        private const val PRACTICE_WRONG_SOUND_VOLUME = 100
        private const val PRACTICE_CORRECT_SOUND_DURATION_MS = 150
        private const val PRACTICE_WRONG_SOUND_DURATION_MS = 220
        private const val TIMER_TICK_INTERVAL_MS = 1_000L
        private const val REVIEW_ADVANCE_DELAY_MS = 500L
        private const val ANSWER_CARD_DEFAULT_SPAN_COUNT = 5
        private const val PRACTICE_PERSIST_DEBOUNCE_MS = 250L
        private const val ANSWER_CARD_MIN_CELL_WIDTH_DP = 48f
        private const val DEFAULT_EXAM_DURATION_MINUTES = 60
        private const val MAX_EXAM_DURATION_MINUTES = 600
        private const val STATE_CURRENT_INDEX = "state_current_index"
        private const val STATE_CURRENT_SELECTION = "state_current_selection"
        private const val STATE_ANSWER_VISIBLE = "state_answer_visible"
        private const val STATE_REVIEW_MODE = "state_review_mode"
        private const val STATE_QUIZ_ORDER_IDS = "state_quiz_order_ids"
        private const val STATE_RECORDED_PRACTICE_IDS = "state_recorded_practice_ids"
        private const val STATE_REVIEW_RATED_IDS = "state_review_rated_ids"
        private const val STATE_PRACTICE_ANSWER_RESULTS = "state_practice_answer_results"
        private const val STATE_PRACTICE_ANSWER_SELECTIONS = "state_practice_answer_selections"
        private const val STATE_EXAM_ANSWER_SELECTIONS = "state_exam_answer_selections"
        private const val STATE_EXAM_SESSION_ID = "state_exam_session_id"
        private const val STATE_EXAM_STARTED_AT = "state_exam_started_at"
        private const val STATE_TIMER_STARTED_AT = "state_timer_started_at"
        private const val STATE_TIMER_PAUSED_AT = "state_timer_paused_at"
        private const val STATE_EXAM_DURATION_MILLIS = "state_exam_duration_millis"
        private const val STATE_EXAM_AUTO_SUBMITTED = "state_exam_auto_submitted"

        fun arguments(
            libraryId: Int,
            mode: QuizStudyMode,
            quizIds: IntArray? = null,
            examDurationMinutes: Int = DEFAULT_EXAM_DURATION_MINUTES,
            optionShuffleEnabled: Boolean = false
        ): Bundle {
            return bundleOf(
                QuizLibraryFeaturesFragment.LIBRARY_ID to libraryId,
                MODE to mode.value,
                QUIZ_IDS to quizIds,
                EXAM_DURATION_MINUTES to examDurationMinutes,
                OPTION_SHUFFLE_ENABLED to optionShuffleEnabled
            )
        }
    }
}

private data class AnswerCardItem(
    val number: Int,
    val status: AnswerCardStatus,
    val isCurrent: Boolean
)

private data class AnswerCardStats(
    val total: Int,
    val unanswered: Int,
    val answered: Int,
    val correct: Int,
    val incorrect: Int
)

private data class RunnerTextSize(
    val value: String,
    val label: String,
    val promptSp: Float,
    val optionSp: Float,
    val resultSp: Float,
    val supportSp: Float
) {
    companion object {
        val SMALL = RunnerTextSize("small", "小", 18f, 15f, 15f, 13f)
        val NORMAL = RunnerTextSize("normal", "正常", 20f, 16f, 16f, 14f)
        val LARGE = RunnerTextSize("large", "大", 22f, 18f, 18f, 16f)
        val EXTRA_LARGE = RunnerTextSize("extra_large", "特大", 24f, 20f, 20f, 18f)
        val ENTRIES = listOf(SMALL, NORMAL, LARGE, EXTRA_LARGE)

        fun fromValue(value: String): RunnerTextSize {
            return ENTRIES.firstOrNull { it.value == value } ?: NORMAL
        }
    }
}

private enum class AnswerCardStatus {
    UNANSWERED,
    ANSWERED,
    CORRECT,
    INCORRECT
}

private class AnswerCardAdapter(
    var onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<AnswerCardAdapter.ViewHolder>() {

    private var items: List<AnswerCardItem> = emptyList()

    fun submitItems(newItems: List<AnswerCardItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_answer_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], onItemClick)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView.findViewById(R.id.answer_card_item)
        private val numberText: TextView = itemView.findViewById(R.id.answer_card_number)

        fun bind(item: AnswerCardItem, onItemClick: (Int) -> Unit) {
            numberText.text = item.number.toString()
            val (backgroundAttr, textAttr) = when (item.status) {
                AnswerCardStatus.UNANSWERED -> R.attr.colorSurfaceContainerHigh to R.attr.colorOnSurfaceVariant
                AnswerCardStatus.ANSWERED -> R.attr.colorTertiaryContainer to R.attr.colorOnTertiaryContainer
                AnswerCardStatus.CORRECT -> R.attr.colorPrimaryContainer to R.attr.colorOnPrimaryContainer
                AnswerCardStatus.INCORRECT -> R.attr.colorErrorContainer to R.attr.colorOnErrorContainer
            }
            card.setCardBackgroundColor(MaterialColors.getColor(card, backgroundAttr))
            card.strokeColor = MaterialColors.getColor(
                card,
                if (item.isCurrent) R.attr.colorPrimary else R.attr.colorOutlineVariant
            )
            card.strokeWidth = if (item.isCurrent) 3 else 1
            numberText.setTextColor(MaterialColors.getColor(card, textAttr))
            card.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(position)
                }
            }
        }
    }
}

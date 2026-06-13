package com.virin.visionquiz.quizstudy

import android.annotation.SuppressLint
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
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.VelocityTracker
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import com.virin.visionquiz.dao.answerString
import com.virin.visionquiz.dao.inferredUiType
import com.virin.visionquiz.dao.isSupportedStudyType
import com.virin.visionquiz.databinding.FragmentQuizRunnerBinding
import com.virin.visionquiz.preference.SettingsActivity
import com.virin.visionquiz.quizlibraryfeatures.QuizLibraryFeaturesFragment
import com.virin.visionquiz.util.BaseQuizFragment
import com.virin.visionquiz.util.NavigationBackAnimationSource
import com.virin.visionquiz.util.configureQuizTopBar
import com.virin.visionquiz.util.convertNumToChar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private var favoriteIds: Set<Int> = emptySet()
    private var hasPreparedQuizList = false
    private var hasAnimatedInitialPracticeContent = false
    private var practiceSessionId = 0
    private var practiceSessionStartedAt = 0L
    private var swipeNavigationTouchListener: View.OnTouchListener? = null
    private var restoredQuizOrderIds: IntArray? = null
    private var isPageAnimating = false
    private var optionShuffleEnabled = false
    private val shuffledOptionOrders = mutableMapOf<Int, List<Int>>()  // quizId -> shuffled indices
    private var pendingPracticeSessionPersist: Runnable? = null
    private var practiceAnswerSoundEnabled = true
    private var correctAnswerToneGenerator: ToneGenerator? = null
    private var wrongAnswerToneGenerator: ToneGenerator? = null
    private var answerRecords: List<QuizAnswerRecord> = emptyList()
    private var lastAiConfigSignature: String? = null
    private var aiMarkdownRenderer: AiMarkdownRenderer? = null
    private var runnerTextSize = RunnerTextSize.NORMAL
    private val historyDateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerStartedAt: Long = 0L
    private var timerPausedAt: Long = 0L
    private var examDurationMillis: Long = 0L
    private var examAutoSubmitted = false
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
        aiMarkdownRenderer = AiMarkdownRenderer(requireContext())
        restoreRunnerState(savedInstanceState)
        optionShuffleEnabled = readInitialOptionShuffleEnabled()
        practiceAnswerSoundEnabled = QuizStudySettings.readPracticeAnswerSoundEnabled(requireContext())
        runnerTextSize = readRunnerTextSize()
        prepareCorrectAnswerToneGenerator()
        prepareWrongAnswerToneGenerator()
        configureQuizTopBar(
            binding.toolbar,
            mode.label,
            navigationIconRes = if (mode == QuizStudyMode.EXAM) R.drawable.round_close_24 else R.drawable.round_arrow_back_24,
            onNavigationClick = { requestExitConfirmation(fromNavigationButton = true) }
        )

        binding.previousButton.setOnClickListener { goToQuestion(currentIndex - 1, animate = true) }
        binding.nextButton.setOnClickListener { goToQuestion(currentIndex + 1, animate = true) }
        binding.submitButton.setOnClickListener { handleSubmit() }
        binding.showAnswerButton.setOnClickListener { showAnswer() }
        binding.favoriteButton.setOnClickListener { toggleFavorite() }
        binding.answerCardButton.setOnClickListener { showAnswerCard() }
        setupAiControls()
        applyBottomInsets()
        setupSwipeNavigation()
        setupExitConfirmation()
        setupRunnerMenu()
        lastAiConfigSignature = currentAiConfigSignature()

        viewModel.favoriteQuizIds.observe(viewLifecycleOwner) { ids ->
            favoriteIds = ids.orEmpty().toSet()
            updateFavoriteButton()
            renderAiSection()
        }
        viewModel.answerRecords.observe(viewLifecycleOwner) { records ->
            answerRecords = records.orEmpty()
            renderPracticeHistory()
        }
        viewModel.quizList.observe(viewLifecycleOwner) { source ->
            if (!hasPreparedQuizList) {
                hasPreparedQuizList = true
                prepareQuizList(source.orEmpty())
            }
        }
        viewModel.aiStates.observe(viewLifecycleOwner) {
            renderAiState()
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
            val bottomInset = if (imeBottom > 0) imeBottom else navigationBottom
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveCurrentQuestionSelection()
        outState.putInt(STATE_CURRENT_INDEX, currentIndex)
        outState.putIntArray(STATE_CURRENT_SELECTION, currentSelection.toIntArray())
        outState.putBoolean(STATE_ANSWER_VISIBLE, answerVisible)
        outState.putBoolean(STATE_REVIEW_MODE, reviewMode)
        outState.putIntArray(STATE_QUIZ_ORDER_IDS, quizzes.map { it.id }.toIntArray())
        outState.putIntArray(STATE_RECORDED_PRACTICE_IDS, recordedPracticeQuizIds.toIntArray())
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
        outState.putLong(STATE_EXAM_DURATION_MILLIS, examDurationMillis)
        outState.putBoolean(STATE_EXAM_AUTO_SUBMITTED, examAutoSubmitted)
    }

    override fun onDestroyView() {
        pendingPracticeSessionPersist?.let { binding.root.removeCallbacks(it) }
        pendingPracticeSessionPersist = null
        stopTimer()
        correctAnswerToneGenerator?.release()
        wrongAnswerToneGenerator?.release()
        correctAnswerToneGenerator = null
        wrongAnswerToneGenerator = null
        swipeNavigationTouchListener = null
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
        val configSignature = currentAiConfigSignature()
        if (lastAiConfigSignature != null && lastAiConfigSignature != configSignature) {
            viewModel.clearAiUiStates()
        }
        lastAiConfigSignature = configSignature
        if (_binding != null && quizzes.isNotEmpty()) {
            renderAiSection()
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
        examDurationMillis = savedInstanceState.getLong(
            STATE_EXAM_DURATION_MILLIS,
            readExamDurationMillisFromArgs()
        )
        examAutoSubmitted = savedInstanceState.getBoolean(STATE_EXAM_AUTO_SUBMITTED, false)
        recordedPracticeQuizIds +=
            savedInstanceState.getIntArray(STATE_RECORDED_PRACTICE_IDS)?.toList().orEmpty()
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

    private fun prepareQuizList(source: List<Quiz>) {
        val supported = source.filter { it.isSupportedStudyType() }
        supportedQuizSource = supported
        val selectedIds = requireArguments().getIntArray(QUIZ_IDS)?.toList().orEmpty()
        val restoredOrder = restoredQuizOrderIds?.toList().orEmpty()
        if (mode != QuizStudyMode.EXAM) {
            if (restoredOrder.isNotEmpty()) {
                val byId = supported.associateBy { it.id }
                quizzes = restoredOrder.mapNotNull { byId[it] }
                renderPreparedQuizList()
                schedulePracticeSessionPersist()
            } else {
                viewModel.loadPracticeSession(mode) { session ->
                    if (_binding == null) return@loadPracticeSession
                    applyPracticeSession(supported, session)
                }
            }
            return
        }

        quizzes = if (selectedIds.isNotEmpty()) {
            val byId = supported.associateBy { it.id }
            selectedIds.mapNotNull { byId[it] }
        } else {
            supported
        }
        renderPreparedQuizList()
    }

    private fun applyPracticeSession(supported: List<Quiz>, session: PracticeSession?) {
        practiceSessionId = session?.id ?: 0
        practiceSessionStartedAt = session?.startedAt ?: System.currentTimeMillis()
        val byId = supported.associateBy { it.id }
        val storedOrder = session?.quizOrder?.toIntList().orEmpty()
        quizzes = if (storedOrder.isNotEmpty()) {
            storedOrder.mapNotNull { byId[it] }.takeIf { it.isNotEmpty() }
                ?: buildFreshPracticeOrder(supported)
        } else {
            buildFreshPracticeOrder(supported)
        }

        practiceAnswers.clear()
        practiceAnswerResults.clear()
        recordedPracticeQuizIds.clear()
        session?.let {
            practiceAnswers.putAll(it.practiceAnswers.decodeAnswerMapText())
            practiceAnswerResults.putAll(it.practiceResults.decodeResultMapText())
            recordedPracticeQuizIds += it.recordedQuizIds.toIntSet()
            currentIndex = it.currentIndex
            currentSelection = it.currentSelection.toIntSet()
            answerVisible = it.answerVisible
            if (timerStartedAt <= 0L) {
                timerStartedAt = buildPracticeTimerStartedAt(it)
                practiceSessionStartedAt = timerStartedAt
            }
        }
        renderPreparedQuizList()
        schedulePracticeSessionPersist()
    }

    private fun buildPracticeTimerStartedAt(session: PracticeSession): Long {
        // Practice timing stores an adjusted anchor so time spent away from this page stays paused.
        val savedElapsed = (session.updatedAt - session.startedAt).coerceAtLeast(0L)
        return System.currentTimeMillis() - savedElapsed
    }

    private fun buildFreshPracticeOrder(source: List<Quiz>): List<Quiz> {
        return if (mode == QuizStudyMode.RANDOM_PRACTICE) source.shuffled() else source
    }

    private fun renderPreparedQuizList() {
        val shouldAnimateInitialPracticeContent =
            mode != QuizStudyMode.EXAM &&
                quizzes.isNotEmpty() &&
                !binding.contentGroup.isVisible &&
                !hasAnimatedInitialPracticeContent
        if (shouldAnimateInitialPracticeContent) {
            binding.questionScrollView.animate().cancel()
            binding.questionScrollView.alpha = 0f
        }

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
            }
            startTimerIfNeeded()
            render()
            if (shouldAnimateInitialPracticeContent) {
                animateInitialPracticeContent()
            }
        }
    }

    private fun animateInitialPracticeContent() {
        hasAnimatedInitialPracticeContent = true
        binding.questionScrollView.doOnPreDraw {
            if (_binding == null) return@doOnPreDraw
            binding.questionScrollView.animate()
                .alpha(1f)
                .setDuration(INITIAL_CONTENT_FADE_DURATION_MS)
                .start()
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
            persistPracticeSession()
        }
    }

    private fun resumeTimerForLifecycle() {
        if (_binding == null || quizzes.isEmpty()) return
        if (mode != QuizStudyMode.EXAM && timerPausedAt > 0L) {
            val pausedDuration = (System.currentTimeMillis() - timerPausedAt).coerceAtLeast(0L)
            timerStartedAt += pausedDuration
            practiceSessionStartedAt = timerStartedAt
            timerPausedAt = 0L
            persistPracticeSession()
        }
        startTimerIfNeeded()
    }

    private fun updateTimerText() {
        if (_binding == null || timerStartedAt <= 0L) return
        val elapsed = (System.currentTimeMillis() - timerStartedAt).coerceAtLeast(0L)
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
        applyRunnerTextSize()
        binding.progressText.text = "第 ${currentIndex + 1} / ${quizzes.size} 题"
        binding.typeText.text = type.label
        binding.promptText.text = quiz.prompt
        binding.answerPanel.isVisible = answerVisible || reviewMode
        binding.answerPanel.text = "答案：${quiz.answerString()}"
        binding.resultText.isVisible = answerVisible || reviewMode
        binding.resultText.text = buildResultText(quiz)

        binding.previousButton.isEnabled = currentIndex > 0
        binding.nextButton.isEnabled = currentIndex < quizzes.lastIndex
        binding.showAnswerButton.isVisible = mode != QuizStudyMode.EXAM || reviewMode
        val isPracticeSubmitted = mode != QuizStudyMode.EXAM && recordedPracticeQuizIds.contains(quiz.id)
        val isLastQuestion = currentIndex == quizzes.lastIndex
        binding.showAnswerButton.isEnabled = when {
            mode == QuizStudyMode.EXAM -> reviewMode
            else -> !isPracticeSubmitted
        }
        binding.submitButton.text = when {
            mode == QuizStudyMode.EXAM -> "交卷"
            isPracticeSubmitted && isLastQuestion -> "交卷"
            else -> "提交"
        }
        binding.submitButton.isVisible = when {
            reviewMode -> false
            mode == QuizStudyMode.EXAM -> isLastQuestion
            isPracticeSubmitted -> isLastQuestion
            else -> true
        }
        binding.submitButton.isEnabled = when {
            reviewMode -> false
            mode == QuizStudyMode.EXAM -> true
            isPracticeSubmitted -> isLastQuestion
            else -> true
        }
        renderAiSection()
        applyTypeStyle(type)
        renderOptions(quiz, type)
        updateFavoriteButton()
        renderExamSummaryIfNeeded()
        renderPracticeHistory()
    }

    private fun setupExitConfirmation() {
        if (mode == QuizStudyMode.EXAM) {
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
            binding.toolbar.menu.add(Menu.NONE, MENU_RESET_PRACTICE, Menu.NONE, "重置本次背题")
                .setIcon(R.drawable.round_delete_24)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
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

    private fun setupAiControls() {
        binding.aiGenerateQuickButton.setOnClickListener {
            requestAi(AiExplanationType.QUICK_REVIEW, forceRefresh = false)
        }
        binding.aiGenerateQuickButton.setOnLongClickListener {
            requestAi(AiExplanationType.QUICK_REVIEW, forceRefresh = true)
            true
        }
        binding.aiDetailedButton.setOnClickListener {
            requestAi(AiExplanationType.DETAILED_ANALYSIS, forceRefresh = false)
        }
        binding.aiDetailedButton.setOnLongClickListener {
            requestAi(AiExplanationType.DETAILED_ANALYSIS, forceRefresh = true)
            true
        }
        binding.aiRetryButton.setOnClickListener {
            requestAi(AiExplanationType.QUICK_REVIEW, forceRefresh = false)
        }
        binding.aiDetailedRetryButton.setOnClickListener {
            requestAi(AiExplanationType.DETAILED_ANALYSIS, forceRefresh = false)
        }
    }

    private fun requestAi(type: AiExplanationType, forceRefresh: Boolean) {
        val quiz = quizzes.getOrNull(currentIndex) ?: return
        if (!binding.aiSection.isVisible) return
        val config = AiConfigStore(requireContext()).read()
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

    private fun renderAiSection() {
        val answerIsShown = answerVisible || reviewMode
        val config = AiConfigStore(requireContext()).read()
        binding.aiSection.isVisible = answerIsShown && config.enabled
        if (binding.aiSection.isVisible) {
            renderAiState(config.isComplete())
            maybeAutoRequestQuickReview(config)
        }
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

    private fun renderAiState(
        configComplete: Boolean = AiConfigStore(requireContext()).read().isComplete()
    ) {
        if (_binding == null || !binding.aiSection.isVisible) return
        val quiz = quizzes.getOrNull(currentIndex) ?: return
        val states = viewModel.aiStates.value.orEmpty()
        val quickState = states[AiRequestKey(quiz.id, AiExplanationType.QUICK_REVIEW)]
            ?: AiExplanationUiState.Idle
        val detailedState = states[AiRequestKey(quiz.id, AiExplanationType.DETAILED_ANALYSIS)]
            ?: AiExplanationUiState.Idle
        renderQuickReviewState(quickState, configComplete)
        renderDetailedAnalysisState(quickState, detailedState)
    }

    private fun renderQuickReviewState(
        state: AiExplanationUiState,
        configComplete: Boolean
    ) {
        val quiz = quizzes.getOrNull(currentIndex) ?: return
        binding.aiLoadingIndicator.isVisible =
            state is AiExplanationUiState.Loading || state is AiExplanationUiState.Streaming
        binding.aiRetryButton.isVisible = state is AiExplanationUiState.Error
        binding.aiContentText.isVisible = when (state) {
            is AiExplanationUiState.Streaming,
            is AiExplanationUiState.Success -> true
            is AiExplanationUiState.Error -> state.partialContent.isNotBlank()
            else -> false
        }
        val autoEligible = shouldAutoRequestQuickReview(
            answerShown = answerVisible || reviewMode,
            isCorrect = quiz.isCorrectAnswer(currentSelection),
            isFavorite = quiz.id in favoriteIds
        )
        binding.aiGenerateQuickButton.isVisible =
            state == AiExplanationUiState.Idle && (!autoEligible || !configComplete)
        binding.aiGenerateQuickButton.isEnabled = !state.isAiRequestInProgress()
        when (state) {
            AiExplanationUiState.Idle -> {
                binding.aiStatusText.setText(R.string.ai_quick_review_manual)
                binding.aiContentText.text = ""
            }
            AiExplanationUiState.Loading -> {
                binding.aiStatusText.setText(R.string.ai_loading)
                binding.aiContentText.text = ""
            }
            AiExplanationUiState.ConfigurationRequired -> {
                binding.aiStatusText.setText(R.string.ai_not_configured_message)
                binding.aiContentText.text = ""
            }
            is AiExplanationUiState.Streaming -> {
                binding.aiStatusText.setText(R.string.ai_loading)
                renderAiMarkdown(state.content)
            }
            is AiExplanationUiState.Success -> {
                binding.aiStatusText.setText(R.string.ai_quick_review_ready)
                renderAiMarkdown(state.content)
            }
            is AiExplanationUiState.Error -> {
                binding.aiStatusText.text = state.message
                if (state.partialContent.isNotBlank()) {
                    renderAiMarkdown(state.partialContent)
                } else {
                    binding.aiContentText.text = ""
                }
            }
        }
    }

    private fun renderDetailedAnalysisState(
        quickState: AiExplanationUiState,
        state: AiExplanationUiState
    ) {
        binding.aiDetailedButton.isVisible = quickState is AiExplanationUiState.Success
        binding.aiDetailedButton.isEnabled = !state.isAiRequestInProgress()
        val showCard = state != AiExplanationUiState.Idle
        binding.aiDetailedCard.isVisible = showCard
        if (!showCard) {
            binding.aiDetailedContentText.text = ""
            return
        }
        binding.aiDetailedLoadingIndicator.isVisible =
            state is AiExplanationUiState.Loading || state is AiExplanationUiState.Streaming
        binding.aiDetailedRetryButton.isVisible = state is AiExplanationUiState.Error
        binding.aiDetailedContentText.isVisible = when (state) {
            is AiExplanationUiState.Streaming,
            is AiExplanationUiState.Success -> true
            is AiExplanationUiState.Error -> state.partialContent.isNotBlank()
            else -> false
        }
        when (state) {
            AiExplanationUiState.Idle -> Unit
            AiExplanationUiState.Loading -> {
                binding.aiDetailedStatusText.setText(R.string.ai_loading)
                binding.aiDetailedContentText.text = ""
            }
            AiExplanationUiState.ConfigurationRequired -> {
                binding.aiDetailedStatusText.setText(R.string.ai_not_configured_message)
                binding.aiDetailedContentText.text = ""
            }
            is AiExplanationUiState.Streaming -> {
                binding.aiDetailedStatusText.setText(R.string.ai_loading)
                renderAiMarkdown(binding.aiDetailedContentText, state.content)
            }
            is AiExplanationUiState.Success -> {
                binding.aiDetailedStatusText.setText(
                    if (state.fromCache) R.string.ai_cached else R.string.ai_generated
                )
                renderAiMarkdown(binding.aiDetailedContentText, state.content)
            }
            is AiExplanationUiState.Error -> {
                binding.aiDetailedStatusText.text = state.message
                if (state.partialContent.isNotBlank()) {
                    renderAiMarkdown(binding.aiDetailedContentText, state.partialContent)
                } else {
                    binding.aiDetailedContentText.text = ""
                }
            }
        }
    }

    private fun renderAiMarkdown(content: String) {
        renderAiMarkdown(binding.aiContentText, content)
    }

    private fun renderAiMarkdown(target: TextView, content: String) {
        aiMarkdownRenderer?.render(target, content)
            ?: run { target.text = content }
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
                    viewModel.clearAiUiStates()
                    lastAiConfigSignature = currentAiConfigSignature()
                    renderAiSection()
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
        val config = AiConfigStore(requireContext()).read()
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
                applyRunnerTextSize()
                for (index in 0 until binding.optionGroup.childCount) {
                    val optionText = binding.optionGroup.getChildAt(index)
                        .findViewById<TextView>(R.id.option_text)
                    optionText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, runnerTextSize.optionSp)
                }
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
        quizzes = buildFreshPracticeOrder(supportedQuizSource)
        renderPreparedQuizList()
        persistPracticeSession()
        toastMessage?.let {
            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeNavigation() {
        val configuration = ViewConfiguration.get(requireContext())
        val touchSlop = configuration.scaledTouchSlop.toFloat()
        val density = resources.displayMetrics.density
        val policy = SwipeNavigationGesturePolicy(
            horizontalLockDistance = touchSlop * 3f,
            verticalLockDistance = touchSlop * 2f,
            longSwipeDistance = maxOf(
                resources.displayMetrics.widthPixels * SWIPE_SCREEN_WIDTH_RATIO,
                SWIPE_DISTANCE_DP * density
            ),
            quickSwipeDistance = maxOf(touchSlop * 5f, QUICK_SWIPE_DISTANCE_DP * density),
            minimumFlingVelocity = maxOf(
                configuration.scaledMinimumFlingVelocity * 2f,
                MINIMUM_FLING_VELOCITY_DP_PER_SECOND * density
            )
        )
        var downX = 0f
        var downY = 0f
        var gestureDirection = GestureDirection.UNDECIDED
        var velocityTracker: VelocityTracker? = null
        swipeNavigationTouchListener = View.OnTouchListener listener@{ v, event ->
            val deltaX = event.x - downX
            val deltaY = event.y - downY
            val absDeltaX = kotlin.math.abs(deltaX)
            val absDeltaY = kotlin.math.abs(deltaY)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    gestureDirection = GestureDirection.UNDECIDED
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    if (gestureDirection == GestureDirection.UNDECIDED) {
                        gestureDirection = policy.resolveDirection(absDeltaX, absDeltaY)
                    }
                    if (gestureDirection == GestureDirection.HORIZONTAL) {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    } else {
                        v.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(
                        1_000,
                        configuration.scaledMaximumFlingVelocity.toFloat()
                    )
                    val velocityX = velocityTracker?.xVelocity ?: 0f
                    val velocityY = velocityTracker?.yVelocity ?: 0f
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        gestureDirection == GestureDirection.HORIZONTAL &&
                        policy.shouldNavigate(deltaX, deltaY, velocityX, velocityY)
                    ) {
                        if (deltaX < 0) {
                            goToQuestion(currentIndex + 1, animate = true)
                        } else {
                            goToQuestion(currentIndex - 1, animate = true)
                        }
                        gestureDirection = GestureDirection.UNDECIDED
                        velocityTracker?.recycle()
                        velocityTracker = null
                        return@listener true
                    }
                }
            }
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                gestureDirection = GestureDirection.UNDECIDED
                velocityTracker?.recycle()
                velocityTracker = null
            }
            gestureDirection == GestureDirection.HORIZONTAL
        }
        binding.questionScrollView.setOnTouchListener(swipeNavigationTouchListener)
        binding.submitButton.setOnTouchListener(swipeNavigationTouchListener)
        binding.showAnswerButton.setOnTouchListener(swipeNavigationTouchListener)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun renderOptions(quiz: Quiz, type: QuizUiType) {
        binding.optionGroup.removeAllViews()

        val optionOrder = if (optionShuffleEnabled) {
            shuffledOptionOrders.getOrPut(quiz.id) { quiz.options.indices.shuffled() }
        } else {
            quiz.options.indices.toList()
        }
        optionOrder.forEachIndexed { displayIndex, optionIndex ->
            val option = quiz.options[optionIndex]
            val isSelected = currentSelection.contains(optionIndex)
            val canChangeAnswer = !reviewMode && !(mode != QuizStudyMode.EXAM && answerVisible)
            val cardView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_quiz_option, binding.optionGroup, false) as MaterialCardView
            val optionText = cardView.findViewById<TextView>(R.id.option_text)
            optionText.setTextSize(TypedValue.COMPLEX_UNIT_SP, runnerTextSize.optionSp)
            optionText.text = "${convertNumToChar(displayIndex)}. $option"
            cardView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.quiz_study_option_gap)
            }
            applyOptionStyle(cardView, optionText, quiz, optionIndex)
            if (canChangeAnswer) {
                cardView.setOnTouchListener(swipeNavigationTouchListener)
                cardView.isClickable = true
                cardView.isFocusable = true
                cardView.setOnClickListener {
                    currentSelection = if (type == QuizUiType.MULTIPLE_CHOICE) {
                        if (isSelected) currentSelection - optionIndex else currentSelection + optionIndex
                    } else {
                        setOf(optionIndex)
                    }
                    if (mode == QuizStudyMode.EXAM) {
                        quizzes.getOrNull(currentIndex)?.let { q ->
                            examAnswers[q.id] = currentSelection
                        }
                    }
                    if (shouldAutoSubmitPractice(type)) {
                        handleSubmit()
                    } else {
                        if (mode != QuizStudyMode.EXAM) {
                            quizzes.getOrNull(currentIndex)?.let { q ->
                                practiceAnswers[q.id] = currentSelection
                            }
                            persistPracticeSession()
                        }
                        renderOptions(quiz, type)
                    }
                }
            }
            binding.optionGroup.addView(cardView)
        }
    }

    private fun shouldAutoSubmitPractice(type: QuizUiType): Boolean {
        return mode != QuizStudyMode.EXAM &&
            !answerVisible &&
            type in setOf(QuizUiType.SINGLE_CHOICE, QuizUiType.JUDGEMENT)
    }

    private fun updateSelection(type: QuizUiType, index: Int, checked: Boolean): Set<Int> {
        return if (type == QuizUiType.MULTIPLE_CHOICE) {
            if (checked) currentSelection + index else currentSelection - index
        } else {
            if (checked) setOf(index) else emptySet()
        }
    }

    private fun applyOptionStyle(card: MaterialCardView, optionText: TextView, quiz: Quiz, index: Int) {
        val selected = currentSelection.contains(index)
        if (!answerVisible && !reviewMode) {
            val strokeColor = MaterialColors.getColor(card, if (selected) R.attr.colorPrimary else R.attr.colorOutlineVariant)
            card.setStrokeColor(ColorStateList.valueOf(strokeColor))
            card.setCardBackgroundColor(MaterialColors.getColor(card, if (selected) R.attr.colorPrimaryContainer else R.attr.colorSurface))
            optionText.setTextColor(MaterialColors.getColor(card, if (selected) R.attr.colorOnPrimaryContainer else R.attr.colorOnSurface))
            return
        }
        val correct = quiz.answer.contains(index)
        val (backgroundAttr, textAttr, strokeAttr) = when {
            correct -> listOf(R.attr.colorPrimaryContainer, R.attr.colorOnPrimaryContainer, R.attr.colorPrimary)
            selected -> listOf(R.attr.colorErrorContainer, R.attr.colorOnErrorContainer, R.attr.colorError)
            else -> listOf(R.attr.colorSurfaceContainerHigh, R.attr.colorOnSurfaceVariant, R.attr.colorOutlineVariant)
        }
        card.setCardBackgroundColor(MaterialColors.getColor(card, backgroundAttr))
        card.setStrokeColor(ColorStateList.valueOf(MaterialColors.getColor(card, strokeAttr)))
        optionText.setTextColor(MaterialColors.getColor(card, textAttr))
    }

    private fun handleSubmit() {
        val quiz = quizzes.getOrNull(currentIndex) ?: return
        if (mode != QuizStudyMode.EXAM && recordedPracticeQuizIds.contains(quiz.id)) {
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
        }
        render()
        persistPracticeSession()
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
        currentIndex = 0
        loadSelectionForCurrentQuestion()
        render()
        showExamResultDialog()
    }

    private fun toggleFavorite() {
        val quiz = quizzes.getOrNull(currentIndex) ?: return
        val becomingFavorite = quiz.id !in favoriteIds
        viewModel.toggleFavorite(quiz, becomingFavorite)
        if (becomingFavorite && (answerVisible || reviewMode)) {
            val state = viewModel.aiStates.value.orEmpty()[
                AiRequestKey(quiz.id, AiExplanationType.QUICK_REVIEW)
            ] ?: AiExplanationUiState.Idle
            val config = AiConfigStore(requireContext()).read()
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

    private fun goToQuestion(targetIndex: Int, animate: Boolean = false) {
        if (targetIndex !in quizzes.indices || targetIndex == currentIndex) return
        if (isPageAnimating) return
        saveCurrentQuestionSelection()
        if (animate) {
            animateQuestionChange(targetIndex)
            return
        }
        currentIndex = targetIndex
        loadSelectionForCurrentQuestion()
        render()
        persistPracticeSession()
    }

    private fun animateQuestionChange(targetIndex: Int) {
        val page = binding.questionScrollView
        val width = page.width.takeIf { it > 0 } ?: run {
            goToQuestion(targetIndex, animate = false)
            return
        }
        val direction = if (targetIndex > currentIndex) 1 else -1
        val travel = width * 0.22f

        isPageAnimating = true
        page.animate().cancel()
        page.animate()
            .translationX(-direction * travel)
            .alpha(0f)
            .setDuration(PAGE_ANIMATION_DURATION_MS)
            .withEndAction {
                currentIndex = targetIndex
                loadSelectionForCurrentQuestion()
                render()
                persistPracticeSession()
                page.scrollTo(0, 0)
                page.translationX = direction * travel
                page.alpha = 0f
                page.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(PAGE_ANIMATION_DURATION_MS)
                    .withEndAction {
                        isPageAnimating = false
                    }
                    .start()
            }
            .start()
    }

    private fun showAnswerCard() {
        if (quizzes.isEmpty()) return
        saveCurrentQuestionSelection()

        val dialogView = layoutInflater.inflate(R.layout.dialog_answer_card, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.answer_card_recycler_view)
        renderAnswerCardStats(dialogView, buildAnswerCardStats())
        val adapter = AnswerCardAdapter { index ->
            goToQuestion(index, animate = true)
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
            goToQuestion(index, animate = true)
            dialog.dismiss()
        }
        dialog.show()
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
        val selected = currentSelection
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
        applyRunnerTextSize()
        for (index in 0 until binding.optionGroup.childCount) {
            val optionText = binding.optionGroup.getChildAt(index)
                .findViewById<TextView>(R.id.option_text)
            optionText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, runnerTextSize.optionSp)
        }
    }

    private fun readRunnerTextSize(): RunnerTextSize {
        return RunnerTextSize.fromValue(
            QuizStudySettings.readRunnerTextSizeLevel(requireContext())
        )
    }

    private fun applyRunnerTextSize() {
        binding.emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, runnerTextSize.promptSp)
        binding.examResultText.setTextSize(TypedValue.COMPLEX_UNIT_SP, runnerTextSize.resultSp)
        binding.promptText.setTextSize(TypedValue.COMPLEX_UNIT_SP, runnerTextSize.promptSp)
        binding.resultText.setTextSize(TypedValue.COMPLEX_UNIT_SP, runnerTextSize.resultSp)
        binding.answerPanel.setTextSize(TypedValue.COMPLEX_UNIT_SP, runnerTextSize.resultSp)
        binding.historySummaryText.setTextSize(TypedValue.COMPLEX_UNIT_SP, runnerTextSize.supportSp)
        binding.historyDetailText.setTextSize(TypedValue.COMPLEX_UNIT_SP, runnerTextSize.supportSp)
        binding.aiContentText.setTextSize(TypedValue.COMPLEX_UNIT_SP, runnerTextSize.supportSp)
        binding.aiDetailedContentText.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            runnerTextSize.supportSp
        )
    }

    private fun renderExamSummaryIfNeeded() {
        binding.examResultPanel.isVisible = reviewMode && mode == QuizStudyMode.EXAM
        if (reviewMode && mode == QuizStudyMode.EXAM) {
            val correct = quizzes.count { it.isCorrectAnswer(examAnswers[it.id].orEmpty()) }
            binding.examResultText.text = "考试完成：答对 $correct 题，答错 ${quizzes.size - correct} 题，共 ${quizzes.size} 题"
        }
    }

    private fun renderPracticeHistory() {
        val quiz = quizzes.getOrNull(currentIndex) ?: run {
            binding.historyPanel.isVisible = false
            return
        }
        val shouldShow = mode != QuizStudyMode.EXAM &&
            (answerVisible || recordedPracticeQuizIds.contains(quiz.id))
        binding.historyPanel.isVisible = shouldShow
        if (!shouldShow) return

        val records = answerRecords
            .filter { it.quizId == quiz.id && it.mode != QuizStudyMode.EXAM.value }
            .sortedByDescending { it.answeredAt }
        val correctCount = records.count { it.isCorrect }
        val totalCount = records.size
        val rate = if (totalCount == 0) 0 else correctCount * 100 / totalCount
        binding.historySummaryText.text = "历史作答 $totalCount 次 · 正确 $correctCount 次 · 正确率 $rate%"
        binding.historyDetailText.text = if (records.isEmpty()) {
            "暂无历史记录"
        } else {
            records.take(5).mapIndexed { index, record ->
                val result = if (record.isCorrect) "正确" else "错误"
                "${index + 1}. ${historyDateFormat.format(Date(record.answeredAt))}  $result"
            }.joinToString("\n")
        }
    }

    private fun saveCurrentQuestionSelection() {
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
            persistPracticeSession()
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
        if (mode == QuizStudyMode.EXAM || quizzes.isEmpty()) return
        val now = System.currentTimeMillis()
        val startedAt = when {
            timerStartedAt > 0L -> timerStartedAt
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

    private fun schedulePracticeSessionPersist() {
        if (mode == QuizStudyMode.EXAM || quizzes.isEmpty() || _binding == null) return
        pendingPracticeSessionPersist?.let { binding.root.removeCallbacks(it) }
        pendingPracticeSessionPersist = Runnable {
            pendingPracticeSessionPersist = null
            if (_binding == null) return@Runnable
            persistPracticeSession()
        }.also { runnable ->
            binding.root.postDelayed(runnable, INITIAL_PERSIST_DELAY_MS)
        }
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
        private const val INITIAL_CONTENT_FADE_DURATION_MS = 180L
        private const val INITIAL_PERSIST_DELAY_MS = 450L
        private const val PAGE_ANIMATION_DURATION_MS = 140L
        private const val TIMER_TICK_INTERVAL_MS = 1_000L
        private const val SWIPE_SCREEN_WIDTH_RATIO = 0.18f
        private const val SWIPE_DISTANCE_DP = 120f
        private const val QUICK_SWIPE_DISTANCE_DP = 64f
        private const val MINIMUM_FLING_VELOCITY_DP_PER_SECOND = 600f
        private const val ANSWER_CARD_DEFAULT_SPAN_COUNT = 5
        private const val ANSWER_CARD_MIN_CELL_WIDTH_DP = 48f
        private const val DEFAULT_EXAM_DURATION_MINUTES = 60
        private const val MAX_EXAM_DURATION_MINUTES = 600
        private const val STATE_CURRENT_INDEX = "state_current_index"
        private const val STATE_CURRENT_SELECTION = "state_current_selection"
        private const val STATE_ANSWER_VISIBLE = "state_answer_visible"
        private const val STATE_REVIEW_MODE = "state_review_mode"
        private const val STATE_QUIZ_ORDER_IDS = "state_quiz_order_ids"
        private const val STATE_RECORDED_PRACTICE_IDS = "state_recorded_practice_ids"
        private const val STATE_PRACTICE_ANSWER_RESULTS = "state_practice_answer_results"
        private const val STATE_PRACTICE_ANSWER_SELECTIONS = "state_practice_answer_selections"
        private const val STATE_EXAM_ANSWER_SELECTIONS = "state_exam_answer_selections"
        private const val STATE_EXAM_SESSION_ID = "state_exam_session_id"
        private const val STATE_EXAM_STARTED_AT = "state_exam_started_at"
        private const val STATE_TIMER_STARTED_AT = "state_timer_started_at"
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

private fun Collection<Int>.encodeIntSetText(): String {
    return sorted().joinToString(",")
}

private fun List<Int>.encodeIntListText(): String {
    return joinToString(",")
}

private fun String.toIntList(): List<Int> {
    if (isBlank()) return emptyList()
    return split(",").mapNotNull { it.toIntOrNull() }
}

private fun String.toIntSet(): Set<Int> {
    return toIntList().toSet()
}

private fun Map<Int, Set<Int>>.encodeAnswerMapText(): String {
    return entries.joinToString("|") { (quizId, selected) ->
        "$quizId:${selected.sorted().joinToString(",")}"
    }
}

private fun String.decodeAnswerMapText(): Map<Int, Set<Int>> {
    if (isBlank()) return emptyMap()
    return split("|").mapNotNull { item ->
        val parts = item.split(":", limit = 2)
        val quizId = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val selected = parts.getOrNull(1).orEmpty().toIntSet()
        quizId to selected
    }.toMap(LinkedHashMap())
}

private fun Map<Int, Boolean>.encodeResultMapText(): String {
    return entries.joinToString("|") { (quizId, isCorrect) ->
        "$quizId:${if (isCorrect) 1 else 0}"
    }
}

private fun String.decodeResultMapText(): Map<Int, Boolean> {
    if (isBlank()) return emptyMap()
    return split("|").mapNotNull { item ->
        val parts = item.split(":", limit = 2)
        val quizId = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val isCorrect = parts.getOrNull(1) == "1"
        quizId to isCorrect
    }.toMap(LinkedHashMap())
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

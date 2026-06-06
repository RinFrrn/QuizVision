package com.virin.visionquiz.screendetector

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.LiveData
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizManager
import com.virin.visionquiz.preference.PreferenceUtils
import com.virin.visionquiz.util.QuizGraphicItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class AccessibilityTextSource(
    private val context: Context,
    private val quizzes: LiveData<List<Quiz>>,
    private val onMatchesDetected: (List<QuizGraphicItem>, Int) -> Unit,
    private val onPageActivityDetected: () -> Unit
) : QuizAccessibilityService.Callback {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val matchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val matchGeneration = AtomicInteger()
    private val intervalMs = PreferenceUtils.getAccessibilitySearchIntervalMs(appContext).toLong()
    private val minMatchScore = PreferenceUtils.getAccessibilitySearchMinMatchScore(appContext)
    private val scanLock = Any()

    @Volatile
    private var active = false

    @Volatile
    private var paused = false

    @Volatile
    private var cachedQuizSnapshot: List<Quiz>? = null

    @Volatile
    private var cachedQuizIndex: QuizManager.QuizMatchIndex? = null

    @Volatile
    private var minimumPublishedGeneration = 0

    private var scanInFlight = false
    private var pendingScan = false
    private var pendingScanAllowPaused = false
    private var lastScanStartedAtMs = 0L
    private var scheduledScanAtMs = 0L
    private var scheduledScanAllowPaused = false
    private var lastPageActivityAtMs = 0L
    private var stableCandidateFingerprint: String? = null
    private var stableCandidateCount = 0
    private var publishedSnapshotVersion = 0
    private var lastPublishedFingerprint: String? = null

    private val periodicScanRunnable = object : Runnable {
        override fun run() {
            if (!active) {
                return
            }
            scanOnce(allowPaused = false)
            handler.postDelayed(this, intervalMs)
        }
    }

    private val eventScanRunnable = Runnable {
        scanOnce(allowPaused = false)
    }

    private val rateLimitedScanRunnable = Runnable {
        val allowPaused = scheduledScanAllowPaused
        scheduledScanAtMs = 0L
        scheduledScanAllowPaused = false
        scanOnce(allowPaused = allowPaused)
    }

    private val stabilityScanRunnable = Runnable {
        requestFreshScan()
    }

    fun start() {
        active = true
        paused = false
        lastPageActivityAtMs = 0L
        publishedSnapshotVersion = 0
        lastPublishedFingerprint = null
        resetStableCandidate()
        QuizAccessibilityService.callback = this
        publishScreenFrameInfo()
        handler.removeCallbacks(periodicScanRunnable)
        handler.postDelayed(periodicScanRunnable, intervalMs)
        scanOnce(allowPaused = false)
    }

    fun pause() {
        paused = true
        handler.removeCallbacks(eventScanRunnable)
        handler.removeCallbacks(rateLimitedScanRunnable)
        handler.removeCallbacks(stabilityScanRunnable)
        scheduledScanAtMs = 0L
        scheduledScanAllowPaused = false
    }

    fun resume() {
        if (!active) {
            return
        }
        paused = false
        publishScreenFrameInfo()
        scanOnce(allowPaused = false)
    }

    fun retryOnce() {
        scanOnce(allowPaused = true)
    }

    fun requestFreshScan() {
        minimumPublishedGeneration = maxOf(
            minimumPublishedGeneration,
            matchGeneration.get() + 1
        )
        scanOnce(allowPaused = true)
    }

    fun stop() {
        active = false
        paused = false
        lastPageActivityAtMs = 0L
        lastPublishedFingerprint = null
        resetStableCandidate()
        handler.removeCallbacks(periodicScanRunnable)
        handler.removeCallbacks(eventScanRunnable)
        handler.removeCallbacks(rateLimitedScanRunnable)
        handler.removeCallbacks(stabilityScanRunnable)
        scheduledScanAtMs = 0L
        scheduledScanAllowPaused = false
        if (QuizAccessibilityService.callback === this) {
            QuizAccessibilityService.callback = null
        }
        matchScope.cancel()
        synchronized(scanLock) {
            scanInFlight = false
            pendingScan = false
            pendingScanAllowPaused = false
        }
        ScreenDetectorSession.clearScreenFrameInfo()
    }

    override fun onAccessibilityContentChanged(hasPageMovement: Boolean) {
        if (!active || paused) {
            return
        }
        if (hasPageMovement) {
            lastPageActivityAtMs = SystemClock.uptimeMillis()
            resetStableCandidate()
            lastPublishedFingerprint = null
            minimumPublishedGeneration = maxOf(
                minimumPublishedGeneration,
                matchGeneration.get() + 1
            )
            onPageActivityDetected()
            scheduleStabilityScan(PAGE_STABILITY_QUIET_PERIOD_MS)
        }
        handler.removeCallbacks(eventScanRunnable)
        handler.postDelayed(eventScanRunnable, EVENT_SCAN_DEBOUNCE_MS)
    }

    private fun scanOnce(allowPaused: Boolean) {
        if (!active || (paused && !allowPaused)) {
            return
        }
        val service = QuizAccessibilityService.instance ?: return
        val now = SystemClock.uptimeMillis()
        if (!allowPaused) {
            val delayMs = intervalMs - (now - lastScanStartedAtMs)
            if (delayMs > 0) {
                scheduleRateLimitedScan(delayMs, allowPaused = false)
                return
            }
        }
        if (!markScanStarted(allowPaused)) {
            return
        }
        lastScanStartedAtMs = now
        val screenBounds: Rect
        val nodes: List<QuizAccessibilityService.TextNode>
        try {
            screenBounds = getScreenBounds()
            publishScreenFrameInfo(screenBounds)
            nodes = service.collectVisibleTextNodes(appContext.packageName, screenBounds)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to collect accessibility text nodes.", e)
            finishScan()
            return
        }
        val generation = matchGeneration.incrementAndGet()
        val quizSnapshot = quizzes.value ?: emptyList()

        matchScope.launch {
            try {
                val candidates = buildTextCandidates(nodes, screenBounds)
                val quizIndex = getQuizIndex(quizSnapshot)
                val matches = candidates.asSequence()
                    .filter { it.allowQuestionMatch }
                    .mapNotNull { candidate ->
                        val bestMatch = QuizManager.matchQuiz(
                            candidate.text,
                            quizIndex,
                            minScore = minMatchScore
                        ).firstOrNull()
                            ?: return@mapNotNull null
                        if (!isReliableQuestionMatch(candidate.text, bestMatch.first.prompt)) {
                            return@mapNotNull null
                        }
                        AccessibilityMatch(
                            question = bestMatch.first,
                            distance = bestMatch.second,
                            rect = Rect(candidate.rect),
                            candidate = candidate
                        )
                    }
                    .toList()
                val displayMatches = buildDisplayMatches(matches, candidates, screenBounds)
                withContext(Dispatchers.Main) {
                    if (!active ||
                        generation != matchGeneration.get() ||
                        generation < minimumPublishedGeneration
                    ) {
                        return@withContext
                    }
                    publishStableMatches(
                        displayMatches = displayMatches,
                        nodeCount = nodes.size,
                        candidateCount = candidates.size
                    )
                }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    finishScan()
                }
            }
        }
    }

    private fun scheduleRateLimitedScan(delayMs: Long, allowPaused: Boolean) {
        val targetTimeMs = SystemClock.uptimeMillis() + delayMs
        scheduledScanAllowPaused = scheduledScanAllowPaused || allowPaused
        if (scheduledScanAtMs != 0L && scheduledScanAtMs <= targetTimeMs) {
            return
        }
        handler.removeCallbacks(rateLimitedScanRunnable)
        scheduledScanAtMs = targetTimeMs
        handler.postDelayed(rateLimitedScanRunnable, delayMs)
    }

    private fun markScanStarted(allowPaused: Boolean): Boolean {
        return synchronized(scanLock) {
            if (scanInFlight) {
                pendingScan = true
                pendingScanAllowPaused = pendingScanAllowPaused || allowPaused
                false
            } else {
                scanInFlight = true
                true
            }
        }
    }

    private fun finishScan() {
        var hasPendingScan = false
        val allowPausedForPending = synchronized(scanLock) {
            scanInFlight = false
            hasPendingScan = pendingScan
            pendingScan = false
            pendingScanAllowPaused.also {
                pendingScanAllowPaused = false
            }
        }
        if (!hasPendingScan) {
            return
        }
        handler.post {
            scanOnce(allowPaused = allowPausedForPending)
        }
    }

    private fun publishStableMatches(
        displayMatches: List<QuizGraphicItem>,
        nodeCount: Int,
        candidateCount: Int
    ) {
        val quietRemainingMs = PAGE_STABILITY_QUIET_PERIOD_MS -
            (SystemClock.uptimeMillis() - lastPageActivityAtMs)
        if (lastPageActivityAtMs > 0L && quietRemainingMs > 0L) {
            scheduleStabilityScan(quietRemainingMs)
            return
        }

        val fingerprint = buildDisplayFingerprint(displayMatches)
        if (stableCandidateFingerprint == fingerprint) {
            stableCandidateCount++
        } else {
            stableCandidateFingerprint = fingerprint
            stableCandidateCount = 1
        }
        if (stableCandidateCount < REQUIRED_STABLE_SCAN_COUNT) {
            scheduleStabilityScan(STABLE_SCAN_CONFIRM_DELAY_MS)
            return
        }

        if (lastPublishedFingerprint != fingerprint) {
            publishedSnapshotVersion++
            lastPublishedFingerprint = fingerprint
        }
        Log.d(
            TAG,
            "stable accessibility scan version=$publishedSnapshotVersion, " +
                "nodes=$nodeCount, candidates=$candidateCount, matches=${displayMatches.size}"
        )
        onMatchesDetected(displayMatches, publishedSnapshotVersion)
    }

    private fun scheduleStabilityScan(delayMs: Long) {
        handler.removeCallbacks(stabilityScanRunnable)
        handler.postDelayed(stabilityScanRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun resetStableCandidate() {
        stableCandidateFingerprint = null
        stableCandidateCount = 0
        handler.removeCallbacks(stabilityScanRunnable)
    }

    private fun buildDisplayFingerprint(matches: List<QuizGraphicItem>): String {
        return matches
            .sortedWith(
                compareBy<QuizGraphicItem> { it.rect.top }
                    .thenBy { it.rect.left }
                    .thenBy { it.question.id }
            )
            .joinToString("|") { match ->
                val identity = match.question.id.takeIf { it != 0 }?.toString()
                    ?: match.question.prompt
                val answers = match.answerRects.joinToString(",") { it.flattenToString() }
                val options = match.optionRects.joinToString(",") { it.flattenToString() }
                "$identity:${match.rect.flattenToString()}:$answers:$options"
            }
    }

    private fun buildTextCandidates(
        nodes: List<QuizAccessibilityService.TextNode>,
        screenBounds: Rect
    ): List<TextCandidate> {
        if (nodes.isEmpty()) {
            return emptyList()
        }
        val orderedNodes = nodes
            .distinctBy { "${it.rect.flattenToString()}#${it.text}" }
            .sortedWith(
                compareBy<QuizAccessibilityService.TextNode> { it.rect.top }
                    .thenBy { it.rect.left }
                    .thenBy { it.rect.bottom }
            )
        val candidates = mutableListOf<TextCandidate>()
        orderedNodes.forEachIndexed { index, node ->
            val normalizedLength = QuizManager.normalizeQuestionText(node.text).length
            candidates.add(
                TextCandidate(
                    text = node.text,
                    rect = Rect(node.rect),
                    startNodeIndex = index,
                    endNodeIndex = index,
                    allowQuestionMatch = normalizedLength >= MIN_NORMALIZED_TEXT_LENGTH
                )
            )
        }

        for (start in orderedNodes.indices) {
            var combinedText = ""
            val combinedRect = Rect(orderedNodes[start].rect)
            for (end in start until minOf(orderedNodes.size, start + MAX_COMBINED_NODE_COUNT)) {
                val node = orderedNodes[end]
                combinedText = if (combinedText.isBlank()) {
                    node.text
                } else {
                    "$combinedText ${node.text}"
                }
                combinedRect.union(node.rect)
                if (end == start) {
                    continue
                }
                if (combinedRect.height() > screenBounds.height() * MAX_COMBINED_RECT_HEIGHT_RATIO) {
                    break
                }
                val nodeCount = end - start + 1
                val normalizedLength = QuizManager.normalizeQuestionText(combinedText).length
                val allowQuestionMatch = normalizedLength >= MIN_COMBINED_TEXT_LENGTH
                if (allowQuestionMatch || nodeCount <= MAX_ANSWER_CANDIDATE_NODE_COUNT) {
                    candidates.add(
                        TextCandidate(
                            text = combinedText,
                            rect = Rect(combinedRect),
                            startNodeIndex = start,
                            endNodeIndex = end,
                            allowQuestionMatch = allowQuestionMatch
                        )
                    )
                }
            }
        }
        return candidates.distinctBy { "${it.rect.flattenToString()}#${QuizManager.normalizeQuestionText(it.text)}" }
    }

    private fun getQuizIndex(quizSnapshot: List<Quiz>): QuizManager.QuizMatchIndex {
        val index = cachedQuizIndex
        if (cachedQuizSnapshot === quizSnapshot && index != null) {
            return index
        }
        return synchronized(this) {
            val lockedIndex = cachedQuizIndex
            if (cachedQuizSnapshot === quizSnapshot && lockedIndex != null) {
                lockedIndex
            } else {
                QuizManager.buildMatchIndex(quizSnapshot).also {
                    cachedQuizSnapshot = quizSnapshot
                    cachedQuizIndex = it
                }
            }
        }
    }

    private fun buildDisplayMatches(
        matches: List<AccessibilityMatch>,
        candidates: List<TextCandidate>,
        screenBounds: Rect
    ): List<QuizGraphicItem> {
        if (matches.isEmpty()) {
            return emptyList()
        }
        val bestMatches = matches.groupBy(::matchIdentity)
            .mapNotNull { (_, items) ->
                val best = items.maxByOrNull { it.distance } ?: return@mapNotNull null
                DisplayAccessibilityMatch(
                    item = QuizGraphicItem(
                        best.question,
                        best.distance,
                        padAndClampRect(best.rect, screenBounds),
                        answerRects = emptyList()
                    ),
                    candidate = best.candidate
                )
            }
            .sortedByDescending { it.distance }

        if (bestMatches.isEmpty()) {
            return emptyList()
        }

        val questionCandidates = bestMatches.sortedBy { it.candidate.startNodeIndex }

        val optionRectsByIdentity = questionCandidates.mapIndexed { index, item ->
            val nextStart = questionCandidates.getOrNull(index + 1)?.candidate?.startNodeIndex
            val optionRects = findOptionRects(
                optionTexts = item.item.question.options,
                questionCandidate = item.candidate,
                allCandidates = candidates,
                nextQuestionStartNodeIndex = nextStart,
                screenBounds = screenBounds
            )
            matchIdentity(item.item) to optionRects
        }.toMap()

        return bestMatches.map { match ->
            val optionRects = optionRectsByIdentity[matchIdentity(match.item)].orEmpty()
            val completeOptionRects = optionRects.takeIf {
                it.size == match.item.question.options.size
            }.orEmpty()
            val answerRects = if (completeOptionRects.isNotEmpty()) {
                match.item.question.answer.sorted().mapNotNull(completeOptionRects::getOrNull)
            } else {
                val questionCandidate = questionCandidates.first {
                    matchIdentity(it.item) == matchIdentity(match.item)
                }
                val nextStart = questionCandidates
                    .getOrNull(questionCandidates.indexOf(questionCandidate) + 1)
                    ?.candidate
                    ?.startNodeIndex
                findOptionRects(
                    optionTexts = match.item.question.answer.sorted().mapNotNull {
                        match.item.question.options.getOrNull(it)
                    },
                    questionCandidate = questionCandidate.candidate,
                    allCandidates = candidates,
                    nextQuestionStartNodeIndex = nextStart,
                    screenBounds = screenBounds
                )
            }
            match.item.copy(
                answerRects = answerRects,
                optionRects = completeOptionRects
            )
        }
    }

    private fun findOptionRects(
        optionTexts: List<String>,
        questionCandidate: TextCandidate,
        allCandidates: List<TextCandidate>,
        nextQuestionStartNodeIndex: Int?,
        screenBounds: Rect
    ): List<Rect> {
        if (optionTexts.isEmpty()) {
            return emptyList()
        }

        val searchStart = questionCandidate.endNodeIndex + 1
        val searchEnd = nextQuestionStartNodeIndex ?: minOf(
            allCandidates.maxOfOrNull { it.endNodeIndex + 1 } ?: searchStart,
            searchStart + MAX_ANSWER_SEARCH_NODE_COUNT
        )
        if (searchStart >= searchEnd) {
            return emptyList()
        }

        val searchCandidates = allCandidates
            .asSequence()
            .filter { it.startNodeIndex >= searchStart && it.endNodeIndex < searchEnd }
            .filter { it.nodeCount <= MAX_ANSWER_CANDIDATE_NODE_COUNT }
            .filter { it.rect.top >= questionCandidate.rect.top }
            .filter { it.rect.height() <= screenBounds.height() * MAX_ANSWER_RECT_HEIGHT_RATIO }
            .filter { it.rect.top <= questionCandidate.rect.bottom + screenBounds.height() * MAX_ANSWER_VERTICAL_SPAN_RATIO }
            .toList()

        return optionTexts.mapNotNull { optionText ->
            val normalizedOption = normalizeOptionText(optionText)
            if (normalizedOption.isBlank()) {
                return@mapNotNull null
            }
            searchCandidates
                .mapNotNull { candidate ->
                    val score = answerCandidateScore(candidate.text, normalizedOption)
                        ?: return@mapNotNull null
                    AnswerCandidateMatch(candidate, score)
                }
                .minWithOrNull(
                    compareBy<AnswerCandidateMatch> { it.score }
                        .thenBy { it.candidate.nodeCount }
                        .thenBy { rectArea(it.candidate.rect) }
                        .thenBy { it.candidate.startNodeIndex }
                        .thenBy { it.candidate.rect.top }
                        .thenBy { it.candidate.rect.left }
                )
                ?.let { padAndClampRect(it.candidate.rect, screenBounds) }
        }.distinctBy { it.flattenToString() }
    }

    private fun answerCandidateScore(candidateText: String, normalizedOption: String): Int? {
        val normalizedCandidate = normalizeOptionText(candidateText)
        if (normalizedCandidate.isBlank()) {
            return null
        }
        if (normalizedCandidate == normalizedOption) {
            return ANSWER_MATCH_EXACT
        }
        if (normalizedOption.length <= SHORT_OPTION_EXACT_MATCH_MAX_LENGTH) {
            return null
        }
        if (
            normalizedCandidate.length < MIN_CONTAINS_OPTION_LENGTH ||
            normalizedOption.length < MIN_CONTAINS_OPTION_LENGTH
        ) {
            return null
        }
        if (normalizedCandidate.contains(normalizedOption)) {
            return ANSWER_MATCH_CONTAINS_OPTION
        }
        if (
            normalizedOption.contains(normalizedCandidate) &&
            normalizedCandidate.length * 2 >= normalizedOption.length
        ) {
            return ANSWER_MATCH_OPTION_CONTAINS_CANDIDATE
        }
        return null
    }

    private fun isReliableQuestionMatch(candidateText: String, prompt: String): Boolean {
        if (minMatchScore < STRICT_MATCH_SCORE) {
            return true
        }
        val normalizedCandidate = QuizManager.normalizeQuestionText(candidateText)
        val normalizedPrompt = QuizManager.normalizeQuestionText(prompt)
        return normalizedCandidate == normalizedPrompt ||
            normalizedCandidate.contains(normalizedPrompt)
    }

    private fun normalizeOptionText(text: String): String {
        return QuizManager.normalizeAnswerText(
            OPTION_PREFIX_REGEX.replace(
                text.replace(WHITESPACE_REGEX, " ").trim(),
                ""
            )
        )
    }

    private fun rectArea(rect: Rect): Int {
        return rect.width().coerceAtLeast(0) * rect.height().coerceAtLeast(0)
    }

    private fun matchIdentity(item: QuizGraphicItem): String {
        return if (item.question.id != 0) {
            item.question.id.toString()
        } else {
            item.question.prompt
        }
    }

    private fun matchIdentity(item: AccessibilityMatch): String {
        return if (item.question.id != 0) {
            item.question.id.toString()
        } else {
            item.question.prompt
        }
    }

    private fun padAndClampRect(rect: Rect, screenBounds: Rect): Rect {
        val padded = Rect(rect)
        padded.inset(-DISPLAY_RECT_PADDING_PX, -DISPLAY_RECT_PADDING_PX)
        padded.left = padded.left.coerceIn(screenBounds.left, screenBounds.right)
        padded.right = padded.right.coerceIn(screenBounds.left, screenBounds.right)
        padded.top = padded.top.coerceIn(screenBounds.top, screenBounds.bottom)
        padded.bottom = padded.bottom.coerceIn(screenBounds.top, screenBounds.bottom)
        return padded
    }

    private fun publishScreenFrameInfo(bounds: Rect = getScreenBounds()) {
        ScreenDetectorSession.publishScreenFrameInfo(bounds.width(), bounds.height())
    }

    private fun getScreenBounds(): Rect {
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Rect(0, 0, bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private data class TextCandidate(
        val text: String,
        val rect: Rect,
        val startNodeIndex: Int,
        val endNodeIndex: Int,
        val allowQuestionMatch: Boolean
    ) {
        val nodeCount: Int = endNodeIndex - startNodeIndex + 1
    }

    private data class AnswerCandidateMatch(
        val candidate: TextCandidate,
        val score: Int
    )

    private data class AccessibilityMatch(
        val question: Quiz,
        val distance: Double,
        val rect: Rect,
        val candidate: TextCandidate
    )

    private data class DisplayAccessibilityMatch(
        val item: QuizGraphicItem,
        val candidate: TextCandidate
    ) {
        val distance: Double = item.distance
    }

    companion object {
        private const val TAG = "AccessibilityTextSource"
        private const val EVENT_SCAN_DEBOUNCE_MS = 70L
        private const val PAGE_STABILITY_QUIET_PERIOD_MS = 400L
        private const val STABLE_SCAN_CONFIRM_DELAY_MS = 120L
        private const val REQUIRED_STABLE_SCAN_COUNT = 2
        private const val MIN_NORMALIZED_TEXT_LENGTH = 2
        private const val MIN_COMBINED_TEXT_LENGTH = 6
        private const val MAX_COMBINED_NODE_COUNT = 8
        private const val MAX_ANSWER_SEARCH_NODE_COUNT = 24
        private const val MAX_ANSWER_CANDIDATE_NODE_COUNT = 3
        private const val MIN_CONTAINS_OPTION_LENGTH = 2
        private const val SHORT_OPTION_EXACT_MATCH_MAX_LENGTH = 4
        private const val STRICT_MATCH_SCORE = 1.0
        private const val MAX_COMBINED_RECT_HEIGHT_RATIO = 0.45f
        private const val MAX_ANSWER_RECT_HEIGHT_RATIO = 0.18f
        private const val MAX_ANSWER_VERTICAL_SPAN_RATIO = 0.5f
        private const val DISPLAY_RECT_PADDING_PX = 4
        private const val ANSWER_MATCH_EXACT = 0
        private const val ANSWER_MATCH_CONTAINS_OPTION = 1
        private const val ANSWER_MATCH_OPTION_CONTAINS_CANDIDATE = 2
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val OPTION_PREFIX_REGEX = Regex("^[A-Ha-h](?:[、.．)）]\\s*|\\s+)")
    }
}

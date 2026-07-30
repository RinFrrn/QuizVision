package com.virin.visionquiz.cram

import android.content.Context
import com.virin.visionquiz.dao.LibraryInsightCache
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizDatabase
import com.virin.visionquiz.dao.QuizStudyMode
import com.virin.visionquiz.dao.isSupportedStudyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CramStudyPack(
    val libraryId: Int,
    val libraryName: String,
    val questionCount: Int,
    val quizzes: List<Quiz>,
    val localFingerprint: String,
    val analysis: CramAnalysisResult,
    val localMarkdown: String,
    val aiReportMarkdown: String?,
    val quickCardMarkdown: String,
    val todayQuizIds: List<Int>,
    val todayCompletedCount: Int,
    val selfTestQuizIds: List<Int>,
    val generatedAt: Long
)

class CramAnalysisRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = QuizDatabase.getInstance(appContext)
    private val libraryDao = database.categoryDao()
    private val quizDao = database.questionDao()
    private val cacheDao = database.libraryInsightCacheDao()
    private val progressStore = CramAnalysisProgressStore(appContext)

    suspend fun loadStudyPack(
        libraryId: Int,
        forceLocal: Boolean = false,
        dailyMinutes: Int = 60,
        planDay: Int = 1
    ): CramStudyPack? = withContext(Dispatchers.IO) {
        if (libraryId <= 0) return@withContext null
        val library = libraryDao.getQuizLibraryByIdOrNull(libraryId) ?: return@withContext null
        val quizzes = quizDao.getQuizsByCategoryOnce(libraryId)
        if (quizzes.isEmpty()) return@withContext null

        val dailyQuestionLimit = dailyQuestionLimit(dailyMinutes)
        val quizFingerprint = CramAiPromptBuilder.quizFingerprint(quizzes)
        val localFingerprint = buildLocalAnalysisFingerprint(
            quizFingerprint = quizFingerprint,
            dailyQuestionLimit = dailyQuestionLimit,
            selfTestSize = SELF_TEST_SIZE
        )
        val existingLocal = cacheDao.getCache(
            libraryId,
            CramCacheType.LOCAL_ANALYSIS,
            CramCacheSubKey.MAIN
        )
        var localUpdatedAt = existingLocal?.updatedAt ?: 0L
        val cachedAnalysis = if (!forceLocal && existingLocal?.fingerprint == localFingerprint) {
            runCatching { CramAnalysisJson.decode(existingLocal.content) }.getOrNull()
        } else {
            null
        }
        val analysis = cachedAnalysis ?: LocalQuestionBankAnalyzer.analyze(
            quizzes = quizzes,
            config = CramAnalysisConfig(
                dailyQuestionLimit = dailyQuestionLimit,
                selfTestSize = SELF_TEST_SIZE
            )
        ).also { result ->
            val now = System.currentTimeMillis()
            localUpdatedAt = now
            cacheDao.upsertCache(
                LibraryInsightCache(
                    id = existingLocal?.id ?: 0,
                    libraryId = libraryId,
                    type = CramCacheType.LOCAL_ANALYSIS,
                    subKey = CramCacheSubKey.MAIN,
                    fingerprint = localFingerprint,
                    content = CramAnalysisJson.encode(result),
                    createdAt = existingLocal?.createdAt ?: now,
                    updatedAt = now
                )
            )
        }

        val planReportCache = cacheDao.getCache(
            libraryId,
            CramCacheType.FINAL_REPORT,
            finalReportCacheSubKey(localFingerprint)
        )
        val legacyReportCache = cacheDao.getCache(
            libraryId,
            CramCacheType.FINAL_REPORT,
            CramCacheSubKey.MAIN
        )
        val matchingReportCache = listOfNotNull(planReportCache, legacyReportCache)
            .firstOrNull {
                it.content.isNotBlank() &&
                    isFinalReportBoundToLocalFingerprint(it.fingerprint, localFingerprint)
            }
        val localMarkdown = CramLocalContentRenderer.renderReport(analysis)
        val aiReport = matchingReportCache?.content
        val quickCard = CramLocalContentRenderer.extractQuickCard(aiReport.orEmpty())
            ?: CramLocalContentRenderer.renderQuickCard(analysis)
        val validIds = quizzes.asSequence()
            .filter { it.isSupportedStudyType() }
            .map { it.id }
            .toHashSet()
        val todayQuizIds = analysis.threeDayPlan.days
            .firstOrNull { it.day == planDay.coerceIn(1, 3) }
            ?: analysis.threeDayPlan.days.firstOrNull()
        val validTodayQuizIds = todayQuizIds
            ?.quizIds
            .orEmpty()
            .filter(validIds::contains)
            .distinct()
        val selfTestIds = analysis.selfTest.quizIds
            .filter(validIds::contains)
            .distinct()
        val todaySession = database.practiceSessionDao()
            .getPracticeSession(libraryId, QuizStudyMode.CRAM_PRACTICE.value)

        CramStudyPack(
            libraryId = libraryId,
            libraryName = library.name,
            questionCount = quizzes.size,
            quizzes = quizzes,
            localFingerprint = localFingerprint,
            analysis = analysis,
            localMarkdown = localMarkdown,
            aiReportMarkdown = aiReport,
            quickCardMarkdown = quickCard,
            todayQuizIds = validTodayQuizIds,
            todayCompletedCount = countCompletedForExactCramQueue(
                sessionQuizOrder = todaySession?.quizOrder,
                recordedQuizIds = todaySession?.recordedQuizIds,
                currentQuizIds = validTodayQuizIds
            ),
            selfTestQuizIds = selfTestIds,
            generatedAt = (matchingReportCache?.updatedAt ?: localUpdatedAt)
                .takeIf { it > 0L }
                ?: System.currentTimeMillis()
        )
    }

    fun readProgress(libraryId: Int): CramAnalysisProgress = progressStore.read(libraryId)

    companion object {
        private const val SELF_TEST_SIZE = 30
    }
}

internal fun dailyQuestionLimit(dailyMinutes: Int): Int {
    return (dailyMinutes.coerceIn(15, 240) * QUESTIONS_PER_HOUR / 60)
        .coerceIn(MIN_DAILY_QUESTIONS, MAX_DAILY_QUESTIONS)
}

private const val QUESTIONS_PER_HOUR = 60
private const val MIN_DAILY_QUESTIONS = 20
private const val MAX_DAILY_QUESTIONS = 180

internal fun buildLocalAnalysisFingerprint(
    quizFingerprint: String,
    dailyQuestionLimit: Int,
    selfTestSize: Int
): String {
    return "local-analysis-v3:$quizFingerprint:$dailyQuestionLimit:$selfTestSize"
}

internal fun buildFinalReportCacheFingerprint(
    localFingerprint: String,
    promptFingerprint: String
): String {
    return "$localFingerprint|$promptFingerprint"
}

internal fun isFinalReportBoundToLocalFingerprint(
    finalReportFingerprint: String,
    localFingerprint: String
): Boolean {
    return finalReportFingerprint.startsWith("$localFingerprint|")
}

internal fun countCompletedForExactCramQueue(
    sessionQuizOrder: String?,
    recordedQuizIds: String?,
    currentQuizIds: List<Int>
): Int {
    if (currentQuizIds.isEmpty()) return 0
    val storedOrder = sessionQuizOrder
        .orEmpty()
        .split(',')
        .mapNotNull(String::toIntOrNull)
    if (storedOrder != currentQuizIds) return 0
    val recorded = recordedQuizIds
        .orEmpty()
        .split(',')
        .mapNotNull(String::toIntOrNull)
        .toSet()
    return currentQuizIds.count(recorded::contains)
}

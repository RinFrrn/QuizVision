package com.virin.visionquiz.quizlibrarylist

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import androidx.room.withTransaction
import com.virin.visionquiz.dao.*
import com.virin.visionquiz.quizstudy.ReviewStats
import com.virin.visionquiz.quizstudy.SpacedRepetitionScheduler
import com.virin.visionquiz.quizstudy.buildReviewStats
import com.virin.visionquiz.util.SimilarQuizStore

class QuizRepositoryImpl(context: Context) : QuizRepository {

    private val appContext = context.applicationContext
    private val database: QuizDatabase
    private val quizLibDao: QuizLibraryDao
    private val quizDao: QuizDao
    private val favoriteDao: QuizFavoriteDao
    private val examSessionDao: ExamSessionDao
    private val answerRecordDao: QuizAnswerRecordDao
    private val practiceSessionDao: PracticeSessionDao
    private val aiExplanationCacheDao: AiExplanationCacheDao
    private val reviewCardDao: ReviewCardDao

    init {
        database = QuizDatabase.getInstance(context)
        quizLibDao = database.categoryDao()
        quizDao = database.questionDao()
        favoriteDao = database.favoriteDao()
        examSessionDao = database.examSessionDao()
        answerRecordDao = database.answerRecordDao()
        practiceSessionDao = database.practiceSessionDao()
        aiExplanationCacheDao = database.aiExplanationCacheDao()
        reviewCardDao = database.reviewCardDao()
    }

    override suspend fun getQuizLibraryById(id: Int): QuizLibrary {
        return quizLibDao.getQuizLibraryById(id)
    }

    override suspend fun getQuizLibraryByName(name: String): QuizLibrary {
        return quizLibDao.getQuizLibraryByName(name)
    }

    override fun getQuizLibraryList(): LiveData<List<QuizLibrary>> {
        return quizLibDao.getAllCategories()
    }

    override suspend fun insertQuizLibrary(quizLibrary: QuizLibrary) {
        quizLibDao.insertCategory(quizLibrary)
    }

    override suspend fun updateQuizLibrary(quizLibrary: QuizLibrary) {
         quizLibDao.updateCategory(quizLibrary)
    }

    override suspend fun deleteQuizLibrary(quizLibrary: QuizLibrary) {
        SimilarQuizStore.cancelAnalysis(appContext, quizLibrary.id)
        database.withTransaction {
            favoriteDao.deleteFavoritesByLibraryId(quizLibrary.id)
            answerRecordDao.deleteAnswerRecordsByLibraryId(quizLibrary.id)
            examSessionDao.deleteExamSessionsByLibraryId(quizLibrary.id)
            practiceSessionDao.deletePracticeSessionsByLibraryId(quizLibrary.id)
            aiExplanationCacheDao.deleteByLibraryId(quizLibrary.id)
            reviewCardDao.deleteCardsByLibraryId(quizLibrary.id)
            quizDao.deleteQuizzesByCategoryId(quizLibrary.id)
            quizLibDao.deleteCategoryById(quizLibrary.id)
        }
        SimilarQuizStore.clear(appContext, quizLibrary.id)
    }

    override fun getQuizListByLibraryId(libraryId: Int): LiveData<List<Quiz>> {
        return quizDao.getQuizsByCategory(libraryId)
    }

    override suspend fun getQuizListByLibraryIdOnce(libraryId: Int): List<Quiz> {
        return quizDao.getQuizsByCategoryOnce(libraryId)
    }

    override suspend fun getQuizListByIds(quizIds: List<Int>): List<Quiz> {
        if (quizIds.isEmpty()) return emptyList()
        val quizById = quizDao.getQuizsByIds(quizIds).associateBy { it.id }
        return quizIds.mapNotNull { quizById[it] }
    }

    override suspend fun getCategoryCountByName(name: String): Int {
        return quizLibDao.getCategoryCountByName(name)
    }

    override suspend fun getQuizCountByLibraryId(libraryId: Int): Int {
        return quizDao.getQuizCountByLibraryId(libraryId)
    }

    override suspend fun insertQuiz(quiz: Quiz) {
        quizDao.insertQuiz(quiz)
        SimilarQuizStore.clear(appContext, quiz.libraryId)
    }

    override suspend fun insertQuizzes(quizzes: List<Quiz>) {
        quizDao.insertQuizzes(quizzes)
        quizzes.map { it.libraryId }.distinct().forEach {
            SimilarQuizStore.clear(appContext, it)
        }
    }

    override suspend fun updateQuiz(quiz: Quiz) {
        quizDao.updateQuiz(quiz)
        SimilarQuizStore.clear(appContext, quiz.libraryId)
    }

    override suspend fun deleteQuiz(quiz: Quiz) {
        database.withTransaction {
            favoriteDao.deleteFavoriteByQuizId(quiz.id)
            aiExplanationCacheDao.deleteByQuizId(quiz.id)
            reviewCardDao.deleteCardByQuizId(quiz.id)
            quizDao.deleteQuiz(quiz)
        }
        SimilarQuizStore.clear(appContext, quiz.libraryId)
    }

    override fun getFavoriteQuizIdsByLibraryId(libraryId: Int): LiveData<List<Int>> {
        return favoriteDao.getFavoriteQuizIdsByLibraryId(libraryId)
    }

    override fun getFavoritesByLibraryId(libraryId: Int): LiveData<List<QuizFavorite>> {
        return favoriteDao.getFavoritesByLibraryId(libraryId)
    }

    override suspend fun setQuizFavorite(quiz: Quiz, isFavorite: Boolean) {
        if (isFavorite) {
            favoriteDao.insertFavorite(QuizFavorite(quizId = quiz.id, libraryId = quiz.libraryId))
        } else {
            favoriteDao.deleteFavoriteByQuizId(quiz.id)
        }
    }

    override suspend fun isQuizFavorite(quizId: Int): Boolean {
        return favoriteDao.getFavoriteCountByQuizId(quizId) > 0
    }

    override fun getAnswerRecordsByLibraryId(libraryId: Int): LiveData<List<QuizAnswerRecord>> {
        return answerRecordDao.getAnswerRecordsByLibraryId(libraryId)
    }

    override fun getAnswerRecordsByExamSessionId(examSessionId: Int): LiveData<List<QuizAnswerRecord>> {
        return answerRecordDao.getAnswerRecordsByExamSessionId(examSessionId)
    }

    override suspend fun insertAnswerRecord(record: QuizAnswerRecord) {
        answerRecordDao.insertAnswerRecord(record)
    }

    override suspend fun insertAnswerRecords(records: List<QuizAnswerRecord>) {
        if (records.isNotEmpty()) {
            answerRecordDao.insertAnswerRecords(records)
        }
    }

    override suspend fun insertExamSession(examSession: ExamSession): Long {
        return examSessionDao.insertExamSession(examSession)
    }

    override suspend fun updateExamSession(examSession: ExamSession) {
        examSessionDao.updateExamSession(examSession)
    }

    override fun getCompletedExamSessionsByLibraryId(libraryId: Int): LiveData<List<ExamSession>> {
        return examSessionDao.getCompletedExamSessionsByLibraryId(libraryId)
    }

    override fun getExamSessionById(sessionId: Int): LiveData<ExamSession?> {
        return examSessionDao.getExamSessionById(sessionId)
    }

    override suspend fun getPracticeSession(libraryId: Int, mode: String): PracticeSession? {
        return practiceSessionDao.getPracticeSession(libraryId, mode)
    }

    override suspend fun upsertPracticeSession(session: PracticeSession) {
        practiceSessionDao.upsertPracticeSession(session)
    }

    override suspend fun deletePracticeSession(libraryId: Int, mode: String) {
        practiceSessionDao.deletePracticeSession(libraryId, mode)
    }

    override suspend fun deleteHistoryByRange(libraryId: Int, startTime: Long, endTime: Long) {
        database.withTransaction {
            answerRecordDao.deleteAnswerRecordsByAnsweredRange(libraryId, startTime, endTime)
            examSessionDao.deleteExamSessionsByEndedRange(libraryId, startTime, endTime)
        }
    }

    override suspend fun getDueReviewCards(libraryId: Int): List<ReviewCard> {
        return reviewCardDao.getDueCards(libraryId, System.currentTimeMillis())
    }

    override fun getDueReviewCardCount(libraryId: Int): LiveData<Int> {
        return reviewCardDao.getDueCardCount(libraryId, System.currentTimeMillis())
    }

    override fun getReviewStatsByLibraryId(libraryId: Int): LiveData<ReviewStats> {
        return reviewCardDao.getCardsByLibraryId(libraryId).map { cards: List<ReviewCard> ->
            buildReviewStats(cards)
        }
    }

    override fun getReviewQuizIdsByLibraryId(libraryId: Int): LiveData<List<Int>> {
        return reviewCardDao.getReviewQuizIdsLiveData(libraryId)
    }

    override suspend fun getReviewCardByQuizId(quizId: Int): ReviewCard? {
        return reviewCardDao.getCardByQuizId(quizId)
    }

    override suspend fun upsertReviewCard(card: ReviewCard) {
        reviewCardDao.upsertCard(card)
    }

    override suspend fun scheduleReview(
        quizId: Int,
        libraryId: Int,
        rating: ReviewRating
    ): ReviewCard {
        return scheduleReviewFromBaseline(
            quizId = quizId,
            libraryId = libraryId,
            rating = rating,
            baseline = null
        ).scheduled
    }

    override suspend fun scheduleReviewFromBaseline(
        quizId: Int,
        libraryId: Int,
        rating: ReviewRating,
        baseline: ReviewCard?
    ): ReviewScheduleResult {
        return database.withTransaction {
            val now = System.currentTimeMillis()
            val card = baseline?.copy(quizId = quizId, libraryId = libraryId)
                ?: reviewCardDao.getCardByQuizId(quizId)
                ?: ReviewCard(
                    quizId = quizId,
                    libraryId = libraryId,
                    dueAt = now,
                    createdAt = now
                )
            val scheduled = SpacedRepetitionScheduler.schedule(card, rating, now)
            reviewCardDao.upsertCard(scheduled)
            ReviewScheduleResult(
                baseline = card,
                scheduled = scheduled
            )
        }
    }

    override suspend fun getNewReviewQuizIds(libraryId: Int, limit: Int): List<Int> {
        if (limit <= 0) return emptyList()
        val allSupportedQuizIds = quizDao.getQuizsByCategoryOnce(libraryId)
            .filter { it.isSupportedStudyType() }
            .map { it.id }
        val existingCardQuizIds = reviewCardDao.getReviewQuizIds(libraryId)
        return SpacedRepetitionScheduler.pickNewCards(
            allQuizIds = allSupportedQuizIds,
            existingCardQuizIds = existingCardQuizIds,
            limit = limit
        )
    }

    override suspend fun countByLibraryAndType(libraryId: Int, type: String): Int {
        return aiExplanationCacheDao.countByLibraryAndType(libraryId, type)
    }

    override suspend fun buildReviewQuizList(libraryId: Int, newCardLimit: Int): List<Int> {
        val dueCards = getDueReviewCards(libraryId)
        val newQuizIds = getNewReviewQuizIds(libraryId, newCardLimit)
        return SpacedRepetitionScheduler.buildReviewSession(
            dueCards = dueCards,
            newQuizIds = newQuizIds,
            newCardLimit = newCardLimit
        )
    }
}
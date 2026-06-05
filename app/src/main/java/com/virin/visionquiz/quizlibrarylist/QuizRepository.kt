package com.virin.visionquiz.quizlibrarylist

import androidx.lifecycle.LiveData
import com.virin.visionquiz.dao.ExamSession
import com.virin.visionquiz.dao.PracticeSession
import com.virin.visionquiz.dao.Quiz
import com.virin.visionquiz.dao.QuizAnswerRecord
import com.virin.visionquiz.dao.QuizFavorite
import com.virin.visionquiz.dao.QuizLibrary

interface QuizRepository {

    suspend fun getQuizLibraryById(id: Int): QuizLibrary

    suspend fun getQuizLibraryByName(name: String): QuizLibrary

    fun getQuizLibraryList(): LiveData<List<QuizLibrary>>

    suspend fun insertQuizLibrary(quizLibrary: QuizLibrary)

    suspend fun updateQuizLibrary(quizLibrary: QuizLibrary)

    suspend fun deleteQuizLibrary(quizLibrary: QuizLibrary)

    fun getQuizListByLibraryId(libraryId: Int): LiveData<List<Quiz>>

    suspend fun getQuizListByLibraryIdOnce(libraryId: Int): List<Quiz>

    suspend fun getQuizListByIds(quizIds: List<Int>): List<Quiz>

    suspend fun getCategoryCountByName(name: String): Int

    suspend fun getQuizCountByLibraryId(libraryId: Int): Int

    suspend fun insertQuiz(quiz: Quiz)

    suspend fun insertQuizzes(quizzes: List<Quiz>)

    suspend fun updateQuiz(quiz: Quiz)

    suspend fun deleteQuiz(quiz: Quiz)

    fun getFavoriteQuizIdsByLibraryId(libraryId: Int): LiveData<List<Int>>

    fun getFavoritesByLibraryId(libraryId: Int): LiveData<List<QuizFavorite>>

    suspend fun setQuizFavorite(quiz: Quiz, isFavorite: Boolean)

    suspend fun isQuizFavorite(quizId: Int): Boolean

    fun getAnswerRecordsByLibraryId(libraryId: Int): LiveData<List<QuizAnswerRecord>>

    fun getAnswerRecordsByExamSessionId(examSessionId: Int): LiveData<List<QuizAnswerRecord>>

    suspend fun insertAnswerRecord(record: QuizAnswerRecord)

    suspend fun insertAnswerRecords(records: List<QuizAnswerRecord>)

    suspend fun insertExamSession(examSession: ExamSession): Long

    suspend fun updateExamSession(examSession: ExamSession)

    fun getCompletedExamSessionsByLibraryId(libraryId: Int): LiveData<List<ExamSession>>

    fun getExamSessionById(sessionId: Int): LiveData<ExamSession?>

    suspend fun getPracticeSession(libraryId: Int, mode: String): PracticeSession?

    suspend fun upsertPracticeSession(session: PracticeSession)

    suspend fun deletePracticeSession(libraryId: Int, mode: String)

    suspend fun deleteHistoryByRange(libraryId: Int, startTime: Long, endTime: Long)

}

package com.virin.visionquiz.dao

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface QuizLibraryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: QuizLibrary)

    @Query("SELECT * FROM QuizLibrary")
    fun getAllCategories(): LiveData<List<QuizLibrary>>

    @Query("SELECT * FROM QuizLibrary WHERE id = :id")
    suspend fun getQuizLibraryById(id: Int): QuizLibrary

    @Query("SELECT * FROM QuizLibrary WHERE name = :name")
    suspend fun getQuizLibraryByName(name: String): QuizLibrary

    @Query("UPDATE QuizLibrary SET quiz_count = :count WHERE id = :id")
    suspend fun setQuizCount(id: Int, count: Int)

    @Query("UPDATE QuizLibrary SET name = :name WHERE id = :id")
    suspend fun setCategoryName(id: Int, name: String)

    @Update
    suspend fun updateCategory(questionCategory: QuizLibrary)

    @Query("DELETE FROM QuizLibrary WHERE id = :id")
    suspend fun deleteCategoryById(id: Int)

    @Query("SELECT COUNT(*) FROM QuizLibrary WHERE name = :name")
    suspend fun getCategoryCountByName(name: String): Int
}

@Dao
interface QuizDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuiz(question: Quiz)

    // 批量插入Quiz记录
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuizzes(quizzes: List<Quiz>)

    @Update
    suspend fun updateQuiz(quiz: Quiz)

    @Delete
    suspend fun deleteQuiz(quiz: Quiz)

    // 删除所有Quiz记录
//    @Query("DELETE FROM Quiz")
//    suspend fun deleteAllQuizzes()

    @Query("SELECT COUNT(*) FROM Quiz WHERE library_id = :libraryId")
    suspend fun getQuizCountByLibraryId(libraryId: Int): Int

    @Query("DELETE FROM Quiz WHERE library_id = :libraryId")
    suspend fun deleteQuizzesByCategoryId(libraryId: Int)

    @Query("SELECT * FROM Quiz WHERE library_id = :libraryId")
    fun getQuizsByCategory(libraryId: Int): LiveData<List<Quiz>>

    @Query("SELECT * FROM Quiz WHERE library_id = :libraryId")
    suspend fun getQuizsByCategoryOnce(libraryId: Int): List<Quiz>

    @Query("SELECT * FROM Quiz WHERE id IN (:quizIds)")
    suspend fun getQuizsByIds(quizIds: List<Int>): List<Quiz>

    @Query("SELECT * FROM Quiz WHERE id = :quizId")
    suspend fun getQuizById(quizId: Int): Quiz?

//    @Query("SELECT * FROM Quiz WHERE library_id = :libraryId AND answer LIKE '%' || :answerId || '%'")
//    suspend fun getQuizsByCategoryAndAnswer(libraryId: Int, answerId: Int): List<Quiz>

    @Query("SELECT * FROM Quiz WHERE library_id = :libraryId AND options IN (:options)")
    fun getQuizsByCategoryAndOptions(libraryId: Int, options: List<String>): LiveData<List<Quiz>>

    // 获取所有Quiz库的ID
    @Query("SELECT DISTINCT library_id FROM Quiz")
    fun getAllLibraryIds(): LiveData<List<Int>>

    // 删除表中prompt重复的项目
    @Query("DELETE FROM Quiz WHERE id NOT IN (SELECT MIN(id) FROM Quiz GROUP BY prompt)")
    fun deleteDuplicatePrompts()
}

@Dao
interface QuizFavoriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFavorite(favorite: QuizFavorite): Long

    @Query("DELETE FROM QuizFavorite WHERE quiz_id = :quizId")
    suspend fun deleteFavoriteByQuizId(quizId: Int)

    @Query("DELETE FROM QuizFavorite WHERE library_id = :libraryId")
    suspend fun deleteFavoritesByLibraryId(libraryId: Int)

    @Query("SELECT * FROM QuizFavorite WHERE library_id = :libraryId ORDER BY created_at DESC")
    fun getFavoritesByLibraryId(libraryId: Int): LiveData<List<QuizFavorite>>

    @Query("SELECT quiz_id FROM QuizFavorite WHERE library_id = :libraryId")
    fun getFavoriteQuizIdsByLibraryId(libraryId: Int): LiveData<List<Int>>

    @Query("SELECT COUNT(*) FROM QuizFavorite WHERE quiz_id = :quizId")
    suspend fun getFavoriteCountByQuizId(quizId: Int): Int
}

@Dao
interface ExamSessionDao {
    @Insert
    suspend fun insertExamSession(examSession: ExamSession): Long

    @Update
    suspend fun updateExamSession(examSession: ExamSession)

    @Query("SELECT * FROM ExamSession WHERE library_id = :libraryId AND is_completed = 1 ORDER BY ended_at DESC")
    fun getCompletedExamSessionsByLibraryId(libraryId: Int): LiveData<List<ExamSession>>

    @Query("SELECT * FROM ExamSession WHERE id = :sessionId")
    fun getExamSessionById(sessionId: Int): LiveData<ExamSession?>

    @Query("DELETE FROM ExamSession WHERE library_id = :libraryId")
    suspend fun deleteExamSessionsByLibraryId(libraryId: Int)

    @Query("DELETE FROM ExamSession WHERE library_id = :libraryId AND ended_at IS NOT NULL AND ended_at BETWEEN :startTime AND :endTime")
    suspend fun deleteExamSessionsByEndedRange(libraryId: Int, startTime: Long, endTime: Long)
}

@Dao
interface QuizAnswerRecordDao {
    @Insert
    suspend fun insertAnswerRecord(record: QuizAnswerRecord)

    @Insert
    suspend fun insertAnswerRecords(records: List<QuizAnswerRecord>)

    @Query("SELECT * FROM QuizAnswerRecord WHERE library_id = :libraryId ORDER BY answered_at DESC")
    fun getAnswerRecordsByLibraryId(libraryId: Int): LiveData<List<QuizAnswerRecord>>

    @Query("SELECT * FROM QuizAnswerRecord WHERE exam_session_id = :examSessionId ORDER BY id ASC")
    fun getAnswerRecordsByExamSessionId(examSessionId: Int): LiveData<List<QuizAnswerRecord>>

    @Query("DELETE FROM QuizAnswerRecord WHERE library_id = :libraryId")
    suspend fun deleteAnswerRecordsByLibraryId(libraryId: Int)

    @Query("DELETE FROM QuizAnswerRecord WHERE library_id = :libraryId AND answered_at BETWEEN :startTime AND :endTime")
    suspend fun deleteAnswerRecordsByAnsweredRange(libraryId: Int, startTime: Long, endTime: Long)
}

@Dao
interface PracticeSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPracticeSession(session: PracticeSession)

    @Query("SELECT * FROM PracticeSession WHERE library_id = :libraryId AND mode = :mode LIMIT 1")
    suspend fun getPracticeSession(libraryId: Int, mode: String): PracticeSession?

    @Query("DELETE FROM PracticeSession WHERE library_id = :libraryId AND mode = :mode")
    suspend fun deletePracticeSession(libraryId: Int, mode: String)

    @Query("DELETE FROM PracticeSession WHERE library_id = :libraryId")
    suspend fun deletePracticeSessionsByLibraryId(libraryId: Int)
}

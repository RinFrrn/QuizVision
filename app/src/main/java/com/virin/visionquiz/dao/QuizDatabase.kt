package com.virin.visionquiz.dao

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        QuizLibrary::class,
        Quiz::class,
        QuizFavorite::class,
        ExamSession::class,
        QuizAnswerRecord::class,
        PracticeSession::class,
        AiExplanationCache::class
    ],
    version = 6
)
@TypeConverters(Converters::class)
abstract class QuizDatabase : RoomDatabase() {
    abstract fun categoryDao(): QuizLibraryDao
    abstract fun questionDao(): QuizDao
    abstract fun favoriteDao(): QuizFavoriteDao
    abstract fun examSessionDao(): ExamSessionDao
    abstract fun answerRecordDao(): QuizAnswerRecordDao
    abstract fun practiceSessionDao(): PracticeSessionDao
    abstract fun aiExplanationCacheDao(): AiExplanationCacheDao

    companion object {
        private const val DB_NAME = "quizzes_database.db"
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Quiz ADD COLUMN question_type TEXT")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_QuizLibrary_name ON QuizLibrary(name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_Quiz_library_id ON Quiz(library_id)")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS QuizFavorite (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        quiz_id INTEGER NOT NULL,
                        library_id INTEGER NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_QuizFavorite_quiz_id ON QuizFavorite(quiz_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_QuizFavorite_library_id ON QuizFavorite(library_id)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ExamSession (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        library_id INTEGER NOT NULL,
                        started_at INTEGER NOT NULL,
                        ended_at INTEGER,
                        total_count INTEGER NOT NULL,
                        correct_count INTEGER NOT NULL,
                        is_completed INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ExamSession_library_id ON ExamSession(library_id)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS QuizAnswerRecord (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        quiz_id INTEGER NOT NULL,
                        library_id INTEGER NOT NULL,
                        mode TEXT NOT NULL,
                        selected_answer TEXT NOT NULL,
                        is_correct INTEGER NOT NULL,
                        answered_at INTEGER NOT NULL,
                        exam_session_id INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_QuizAnswerRecord_quiz_id ON QuizAnswerRecord(quiz_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_QuizAnswerRecord_library_id ON QuizAnswerRecord(library_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_QuizAnswerRecord_exam_session_id ON QuizAnswerRecord(exam_session_id)")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS PracticeSession (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        library_id INTEGER NOT NULL,
                        mode TEXT NOT NULL,
                        quiz_order TEXT NOT NULL,
                        current_index INTEGER NOT NULL,
                        current_selection TEXT NOT NULL,
                        practice_answers TEXT NOT NULL,
                        practice_results TEXT NOT NULL,
                        recorded_quiz_ids TEXT NOT NULL,
                        answer_visible INTEGER NOT NULL,
                        started_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_PracticeSession_library_id ON PracticeSession(library_id)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_PracticeSession_library_id_mode ON PracticeSession(library_id, mode)")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS AiExplanationCache (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        quiz_id INTEGER NOT NULL,
                        library_id INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        fingerprint TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_AiExplanationCache_quiz_id_type " +
                        "ON AiExplanationCache(quiz_id, type)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_AiExplanationCache_library_id " +
                        "ON AiExplanationCache(library_id)"
                )
            }
        }

        @Volatile
        private var instance: QuizDatabase? = null

        fun getInstance(context: Context): QuizDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): QuizDatabase {
            return Room.databaseBuilder(context, QuizDatabase::class.java, DB_NAME)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6
                )
                .build()
        }
    }
}

//// 获取AppDatabase实例
//val db = AppDatabase.getInstance(context)
//
//// 获取QuizLibraryDao实例
//val categoryDao = db.categoryDao()
//
//// 插入新的类别
//val category = QuizLibrary(1, "数学")
//categoryDao.insertCategory(category)
//
//// 获取所有类别
//val categories = categoryDao.getAllCategories()
//
//// 获取QuizDao实例
//val questionDao = db.questionDao()
//
//// 插入新的题目
//val question = Quiz(1, "1 + 1 = ?", "2", category.id)
//questionDao.insertQuiz(question)
//
//// 获取数学类别下的所有题目
//val questions = questionDao.getQuizsByCategory(category.id)

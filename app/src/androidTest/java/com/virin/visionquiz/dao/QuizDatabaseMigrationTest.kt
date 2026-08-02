package com.virin.visionquiz.dao

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuizDatabaseMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrate5To6CreatesAiCacheTableAndIndexes() {
        openDatabase(version = 5).close()
        val migrated = openDatabase(version = 6)
        migrated.writableDatabase.query(
            "SELECT COUNT(*) FROM sqlite_master " +
                "WHERE type = 'table' AND name = 'AiExplanationCache'"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        migrated.writableDatabase.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN (" +
                "'index_AiExplanationCache_quiz_id_type'," +
                "'index_AiExplanationCache_library_id')"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(2, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate6To7CreatesReviewCardTableAndIndexes() {
        openDatabase(version = 6).close()
        val migrated = openDatabase(version = 7)
        migrated.writableDatabase.query(
            "SELECT COUNT(*) FROM sqlite_master " +
                "WHERE type = 'table' AND name = 'ReviewCard'"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        migrated.writableDatabase.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN (" +
                "'index_ReviewCard_quiz_id'," +
                "'index_ReviewCard_library_id'," +
                "'index_ReviewCard_due_at')"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(3, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate7To8PreservesQuizzesAndCreatesInsightCache() {
        val oldDatabase = openDatabase(version = 7)
        oldDatabase.writableDatabase.execSQL(
            """
            CREATE TABLE Quiz (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                prompt TEXT NOT NULL,
                options TEXT NOT NULL,
                answer TEXT NOT NULL,
                is_multiple_choice INTEGER NOT NULL,
                question_type TEXT,
                library_id INTEGER NOT NULL
            )
            """.trimIndent()
        )
        oldDatabase.writableDatabase.execSQL(
            """
            INSERT INTO Quiz (
                id, prompt, options, answer, is_multiple_choice, question_type, library_id
            ) VALUES (
                7, '迁移前题目', '正确,错误', '0', 0, '判断', 3
            )
            """.trimIndent()
        )
        oldDatabase.close()

        val migrated = openDatabase(version = 8)
        migrated.writableDatabase.query(
            """
            SELECT prompt, library_id, explanation, `reference`, source_row
            FROM Quiz
            WHERE id = 7
            """.trimIndent()
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("迁移前题目", cursor.getString(0))
            assertEquals(3, cursor.getInt(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }
        migrated.writableDatabase.query(
            "SELECT COUNT(*) FROM sqlite_master " +
                "WHERE type = 'table' AND name = 'LibraryInsightCache'"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        migrated.writableDatabase.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = " +
                "'index_LibraryInsightCache_library_id_type_sub_key'"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate8To9PreservesCardsAndCreatesFsrsStateAndReviewLog() {
        val oldDatabase = openDatabase(version = 8)
        oldDatabase.writableDatabase.execSQL(
            """
            CREATE TABLE ReviewCard (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                quiz_id INTEGER NOT NULL,
                library_id INTEGER NOT NULL,
                due_at INTEGER NOT NULL,
                interval_days REAL NOT NULL,
                ease_factor REAL NOT NULL,
                review_count INTEGER NOT NULL,
                lapse_count INTEGER NOT NULL,
                last_reviewed_at INTEGER,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        oldDatabase.writableDatabase.execSQL(
            """
            INSERT INTO ReviewCard (
                id, quiz_id, library_id, due_at, interval_days, ease_factor,
                review_count, lapse_count, last_reviewed_at, created_at
            ) VALUES (1, 9, 3, 1000, 5.0, 2.5, 4, 1, 500, 100)
            """.trimIndent()
        )
        oldDatabase.close()

        val migrated = openDatabase(version = 9)
        migrated.writableDatabase.query(
            """
            SELECT quiz_id, state, stability, difficulty, scheduler_version
            FROM ReviewCard WHERE id = 1
            """.trimIndent()
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(9, cursor.getInt(0))
            assertEquals("review", cursor.getString(1))
            assertEquals(5.0, cursor.getDouble(2), 0.0001)
            assertEquals(5.0, cursor.getDouble(3), 0.0001)
            assertEquals(0, cursor.getInt(4))
        }
        migrated.writableDatabase.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'ReviewLog'"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        migrated.writableDatabase.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name IN (" +
                "'index_ReviewLog_quiz_id','index_ReviewLog_library_id'," +
                "'index_ReviewLog_reviewed_at')"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(3, cursor.getInt(0))
        }
        migrated.close()
    }

    private fun openDatabase(version: Int): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE migration_marker (id INTEGER PRIMARY KEY NOT NULL)")
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
            ) {
                if (oldVersion == 5 && newVersion == 6) {
                    QuizDatabase.MIGRATION_5_6.migrate(db)
                }
                if (oldVersion == 6 && newVersion == 7) {
                    QuizDatabase.MIGRATION_6_7.migrate(db)
                }
                if (oldVersion == 7 && newVersion == 8) {
                    QuizDatabase.MIGRATION_7_8.migrate(db)
                }
                if (oldVersion == 8 && newVersion == 9) {
                    QuizDatabase.MIGRATION_8_9.migrate(db)
                }
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DB)
                .callback(callback)
                .build()
        ).also { it.writableDatabase }
    }

    companion object {
        private const val TEST_DB = "quiz-database-migration-test"
    }
}

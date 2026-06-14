package com.virin.visionquiz.dao

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
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

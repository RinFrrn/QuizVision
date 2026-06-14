package com.virin.visionquiz.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    indices = [
        Index(value = ["quiz_id"], unique = true),
        Index(value = ["library_id"]),
        Index(value = ["due_at"])
    ]
)
data class ReviewCard(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "quiz_id") val quizId: Int,
    @ColumnInfo(name = "library_id") val libraryId: Int,
    @ColumnInfo(name = "due_at") val dueAt: Long,
    @ColumnInfo(name = "interval_days") val intervalDays: Double = 0.0,
    @ColumnInfo(name = "ease_factor") val easeFactor: Double = 2.5,
    @ColumnInfo(name = "review_count") val reviewCount: Int = 0,
    @ColumnInfo(name = "lapse_count") val lapseCount: Int = 0,
    @ColumnInfo(name = "last_reviewed_at") val lastReviewedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

enum class ReviewRating(val value: Int, val emoji: String, val label: String) {
    FORGOT(1, "😰", "忘了"),
    HARD(2, "😕", "困难"),
    GOOD(3, "😊", "良好"),
    EASY(4, "😎", "简单")
}

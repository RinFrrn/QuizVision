package com.virin.visionquiz.dao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable audit record for one scheduling decision. */
@Entity(
    indices = [
        Index(value = ["quiz_id"]),
        Index(value = ["library_id"]),
        Index(value = ["reviewed_at"])
    ]
)
data class ReviewLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "quiz_id") val quizId: Int,
    @ColumnInfo(name = "library_id") val libraryId: Int,
    @ColumnInfo(name = "rating") val rating: Int,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Long,
    @ColumnInfo(name = "previous_due_at") val previousDueAt: Long,
    @ColumnInfo(name = "next_due_at") val nextDueAt: Long,
    @ColumnInfo(name = "previous_interval_days") val previousIntervalDays: Double,
    @ColumnInfo(name = "next_interval_days") val nextIntervalDays: Double,
    @ColumnInfo(name = "previous_state") val previousState: String,
    @ColumnInfo(name = "next_state") val nextState: String,
    @ColumnInfo(name = "previous_stability") val previousStability: Double,
    @ColumnInfo(name = "next_stability") val nextStability: Double,
    @ColumnInfo(name = "previous_difficulty") val previousDifficulty: Double,
    @ColumnInfo(name = "next_difficulty") val nextDifficulty: Double,
    @ColumnInfo(name = "elapsed_days") val elapsedDays: Double,
    @ColumnInfo(name = "source_mode") val sourceMode: String,
    @ColumnInfo(name = "scheduler_version") val schedulerVersion: Int,
    @ColumnInfo(name = "is_correction") val isCorrection: Boolean = false
)

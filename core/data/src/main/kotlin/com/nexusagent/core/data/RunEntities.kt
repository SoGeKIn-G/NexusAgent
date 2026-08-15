package com.nexusagent.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey val id: Long,
    val goal: String,
    val startedAt: Long,
    val endedAt: Long?,
    /** "running" | "done" | "failed" */
    val status: String,
    val summary: String?,
    val provider: String,
    val model: String,
    val stepCount: Int,
    val promptTokens: Int,
    val responseTokens: Int,
)

/**
 * One step of one run.
 *
 * The measurement columns are the point of this table. Individually they are a debugging
 * aid; in aggregate across a few hundred rows they are the evidence behind the project's
 * compression claim - the difference between "I optimized the context window" and a
 * measured p50 over real screens.
 *
 * `ON DELETE CASCADE` so clearing history cannot strand orphaned steps.
 */
@Entity(
    tableName = "steps",
    foreignKeys = [
        ForeignKey(
            entity = RunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("runId")],
)
data class StepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    @ColumnInfo(name = "stepIndex") val index: Int,
    val thought: String,
    val action: String,
    val result: String,
    val packageName: String,
    val latencyMs: Long,

    // Compression instrumentation
    val rawNodeCount: Int,
    val keptNodeCount: Int,
    val rawBytes: Int,
    val compressedBytes: Int,
    val walkDurationMs: Long,
)

/** Aggregate returned by the history screen's headline query. */
data class CompressionSummary(
    val steps: Int,
    val avgNodeReduction: Float,
    val avgByteReduction: Float,
    val avgWalkMs: Float,
    val totalRawBytes: Long,
    val totalCompressedBytes: Long,
)

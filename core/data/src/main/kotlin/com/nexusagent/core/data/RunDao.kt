package com.nexusagent.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RunDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: RunEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStep(step: StepEntity)

    @Query("SELECT * FROM runs ORDER BY startedAt DESC LIMIT :limit")
    fun observeRuns(limit: Int = 100): Flow<List<RunEntity>>

    @Query("SELECT * FROM steps WHERE runId = :runId ORDER BY stepIndex ASC")
    fun observeSteps(runId: Long): Flow<List<StepEntity>>

    @Transaction
    @Query("SELECT * FROM runs WHERE id = :runId")
    suspend fun run(runId: Long): RunEntity?

    /**
     * The headline metric, computed in SQL over every recorded step.
     *
     * Rows where `rawBytes = 0` are excluded: the agent loop runs with baseline
     * measurement off (it doubles the tree walk), so only debug-screen and benchmark
     * captures carry a comparison. Averaging them in would drag the reduction toward zero
     * and quietly understate the real figure.
     */
    @Query(
        """
        SELECT
            COUNT(*)                                                   AS steps,
            AVG((1.0 - CAST(keptNodeCount AS REAL) / rawNodeCount) * 100)   AS avgNodeReduction,
            AVG((1.0 - CAST(compressedBytes AS REAL) / rawBytes) * 100)     AS avgByteReduction,
            AVG(walkDurationMs)                                        AS avgWalkMs,
            SUM(rawBytes)                                              AS totalRawBytes,
            SUM(compressedBytes)                                       AS totalCompressedBytes
        FROM steps
        WHERE rawBytes > 0 AND rawNodeCount > 0
        """,
    )
    fun observeCompressionSummary(): Flow<CompressionSummary?>

    /**
     * Resolves runs left marked `running` by a process that died mid-task.
     *
     * Only one run can be active at a time, so any row still `running` when a new one
     * starts was orphaned - killed by the system, a force-stop, or a reinstall. Without
     * this they accumulate in history as permanent `running / 0 steps` zombies that never
     * resolve and make the list untrustworthy.
     */
    @Query(
        """
        UPDATE runs
           SET status = 'interrupted',
               summary = 'Interrupted - the app was closed while this run was in progress.',
               endedAt = :now
         WHERE status = 'running'
        """,
    )
    suspend fun resolveOrphanedRuns(now: Long)

    @Query("DELETE FROM runs")
    suspend fun clearAll()

    /**
     * Trims history to the newest [keep] runs.
     *
     * Called after each run: an agent that records every step forever will grow the
     * database without bound on a phone, and nobody reviews a trace from three weeks ago.
     */
    @Query("DELETE FROM runs WHERE id NOT IN (SELECT id FROM runs ORDER BY startedAt DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}

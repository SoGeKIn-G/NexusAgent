package com.nexusagent.di

import android.content.Context
import com.nexusagent.agent.perception.PerceptionRepository
import com.nexusagent.agent.runtime.execution.ExecutionRepository
import com.nexusagent.agent.runtime.orchestrator.AgentOrchestrator
import com.nexusagent.agent.runtime.reasoning.ReasoningRepository
import com.nexusagent.core.data.HistoryRepository
import com.nexusagent.core.data.NexusDatabase
import com.nexusagent.history.DatabaseRunRecorder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Bindings for the agent layer.
 *
 * These live here rather than in the agent modules so those modules stay free of Hilt and
 * KSP - annotation processing is the slowest part of the build, and on this machine that
 * is worth avoiding wherever the dependency graph is this simple.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun providePerceptionRepository(): PerceptionRepository = PerceptionRepository()

    @Provides
    @Singleton
    fun provideExecutionRepository(
        @ApplicationContext context: Context,
    ): ExecutionRepository = ExecutionRepository(context)

    /**
     * Singleton because it owns the HTTP client's connection pool. A new client per call
     * would spend more time on TLS handshakes than on inference.
     */
    @Provides
    @Singleton
    fun provideReasoningRepository(
        @ApplicationContext context: Context,
    ): ReasoningRepository = ReasoningRepository(context)

    /**
     * Application-scoped, not tied to any UI lifecycle.
     *
     * The agent spends nearly all of its time with our own UI in the background - that is
     * what "drives other apps" means. Scoping the loop to a ViewModel would cancel it the
     * moment it started being useful. AgentService keeps the process alive; this scope
     * keeps the work alive.
     */
    @Provides
    @Singleton
    @AgentScope
    fun provideAgentScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NexusDatabase =
        NexusDatabase.create(context)

    @Provides
    @Singleton
    fun provideHistoryRepository(database: NexusDatabase): HistoryRepository =
        HistoryRepository(database.runDao())

    @Provides
    @Singleton
    fun provideOrchestrator(
        perception: PerceptionRepository,
        execution: ExecutionRepository,
        reasoning: ReasoningRepository,
        @AgentScope scope: CoroutineScope,
        recorder: DatabaseRunRecorder,
    ): AgentOrchestrator =
        AgentOrchestrator(perception, execution, reasoning, scope, recorder)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AgentScope

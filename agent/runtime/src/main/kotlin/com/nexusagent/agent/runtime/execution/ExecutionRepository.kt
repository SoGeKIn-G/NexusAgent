package com.nexusagent.agent.runtime.execution

import android.content.Context
import com.nexusagent.core.model.ActionResult
import com.nexusagent.core.model.AgentAction
import com.nexusagent.core.model.ResolvedTarget
import com.nexusagent.core.model.ScreenSnapshot
import com.nexusagent.core.model.toTarget

/**
 * The execution layer's public surface.
 *
 * Holds the id -> target map from the most recent snapshot, because the model refers to
 * elements by the small integers it was shown ("click(7)") and something has to translate
 * those back into descriptions the resolver can search for.
 *
 * The map is replaced wholesale on every snapshot rather than accumulated. Ids are only
 * meaningful within the snapshot that issued them - carrying stale ids forward would let
 * the agent act on a screen that no longer exists, which is precisely the class of bug
 * this design exists to prevent.
 */
class ExecutionRepository(context: Context) {

    private val executor = ActionExecutor(context.applicationContext)

    @Volatile
    private var targets: Map<Int, ResolvedTarget> = emptyMap()

    val shortcutCatalogue: String get() = executor.shortcutCatalogue

    /** Call on every new snapshot, before dispatching any action derived from it. */
    fun bind(snapshot: ScreenSnapshot) {
        targets = snapshot.elements.associate { it.id to it.toTarget() }
    }

    suspend fun execute(action: AgentAction): ActionResult = executor.execute(action, targets)

    /** True when an id from the model corresponds to something in the current snapshot. */
    fun knows(elementId: Int): Boolean = targets.containsKey(elementId)

    val boundElementCount: Int get() = targets.size
}

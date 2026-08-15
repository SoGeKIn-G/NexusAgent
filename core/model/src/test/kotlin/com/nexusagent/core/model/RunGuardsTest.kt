package com.nexusagent.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs on the JVM — no device, no emulator. This is the payoff for keeping :core:model
 * free of Android dependencies.
 */
class RunGuardsTest {

    private val guards = RunGuards()

    private fun step(index: Int, signature: Int, action: AgentAction) = StepRecord(
        index = index,
        thought = "test",
        action = action,
        result = ActionResult.Ineffective,
        screenSignature = signature,
        latencyMs = 0,
        stats = null,
    )

    @Test
    fun `detects the agent tapping the same element on an unchanged screen`() {
        val history = (0 until 3).map { step(it, signature = 42, action = AgentAction.Click(7)) }
        assertTrue(guards.isLooping(history))
    }

    @Test
    fun `progress on the same screen is not a loop`() {
        val history = listOf(
            step(0, signature = 42, action = AgentAction.Click(7)),
            step(1, signature = 42, action = AgentAction.Click(9)),
            step(2, signature = 42, action = AgentAction.Click(11)),
        )
        assertFalse(guards.isLooping(history))
    }

    @Test
    fun `same action across changing screens is not a loop`() {
        // Scrolling a long list repeatedly is legitimate: the action repeats but the
        // screen changes each time.
        val history = listOf(
            step(0, signature = 1, action = AgentAction.Scroll(ScrollDirection.FORWARD)),
            step(1, signature = 2, action = AgentAction.Scroll(ScrollDirection.FORWARD)),
            step(2, signature = 3, action = AgentAction.Scroll(ScrollDirection.FORWARD)),
        )
        assertFalse(guards.isLooping(history))
    }

    @Test
    fun `short history cannot loop`() {
        assertFalse(guards.isLooping(listOf(step(0, 42, AgentAction.Click(7)))))
    }

    @Test
    fun `ping-pong between two screens is caught as oscillation`() {
        // The real failure seen on device: HOME -> NOTIFICATIONS -> scroll -> BACK ->
        // HOME -> NOTIFICATIONS. Every action differs, so `isLooping` never fires, but
        // the agent is visiting the same two screens forever.
        val history = listOf(
            step(0, signature = 1, action = AgentAction.Global(GlobalAction.HOME)),
            step(1, signature = 2, action = AgentAction.Global(GlobalAction.NOTIFICATIONS)),
            step(2, signature = 2, action = AgentAction.Scroll(ScrollDirection.FORWARD)),
            step(3, signature = 1, action = AgentAction.Global(GlobalAction.BACK)),
            step(4, signature = 1, action = AgentAction.Global(GlobalAction.HOME)),
            step(5, signature = 2, action = AgentAction.Global(GlobalAction.NOTIFICATIONS)),
        )
        assertFalse("isLooping should not fire - the actions all differ", guards.isLooping(history))
        assertTrue("oscillation should fire", guards.isOscillating(history))
    }

    @Test
    fun `scrolling through a long list is not oscillation`() {
        // Each scroll reveals new content, so signatures keep changing. A progressing run
        // must not be killed by the oscillation guard.
        val history = (0 until 8).map {
            step(it, signature = 100 + it, action = AgentAction.Scroll(ScrollDirection.FORWARD))
        }
        assertFalse(guards.isOscillating(history))
    }

    @Test
    fun `a short history cannot oscillate`() {
        val history = (0 until 3).map { step(it, signature = 1, action = AgentAction.Click(1)) }
        assertFalse(guards.isOscillating(history))
    }

    @Test
    fun `step budget stops the run`() {
        assertFalse(guards.isStepBudgetExceeded(24))
        assertTrue(guards.isStepBudgetExceeded(25))
    }

    @Test
    fun `wall clock stops the run`() {
        val start = 0L
        assertFalse(guards.isTimedOut(start, 179_000L))
        assertTrue(guards.isTimedOut(start, 180_000L))
    }
}

class SnapshotStatsTest {

    @Test
    fun `reduction percentages are computed from raw and kept counts`() {
        val stats = SnapshotStats(
            rawNodeCount = 1000,
            keptNodeCount = 100,
            rawBytes = 40_000,
            compressedBytes = 4_000,
            walkDurationMs = 12,
        )
        assertEquals(90f, stats.nodeReductionPercent, 0.01f)
        assertEquals(90f, stats.byteReductionPercent, 0.01f)
    }

    @Test
    fun `empty snapshot does not divide by zero`() {
        val stats = SnapshotStats(0, 0, 0, 0, 0)
        assertEquals(0f, stats.nodeReductionPercent, 0.01f)
        assertEquals(0f, stats.byteReductionPercent, 0.01f)
    }
}

class ScreenSignatureTest {

    private fun snapshot(vararg texts: String, pkg: String = "com.whatsapp") = ScreenSnapshot(
        packageName = pkg,
        activityName = "HomeActivity",
        elements = texts.mapIndexed { i, t ->
            UiElement(
                id = i,
                className = "TextView",
                text = t,
                bounds = Bounds(0, i * 100, 1080, (i + 1) * 100),
                clickable = true,
            )
        },
        stats = SnapshotStats(0, 0, 0, 0, 0),
        capturedAtMillis = 0,
    )

    @Test
    fun `identical screens share a signature`() {
        assertEquals(snapshot("Rahul", "Mom").signature, snapshot("Rahul", "Mom").signature)
    }

    @Test
    fun `reordering alone does not count as a change`() {
        // Texts are sorted before hashing, so a reshuffled list isn't a false "screen changed".
        assertEquals(snapshot("Rahul", "Mom").signature, snapshot("Mom", "Rahul").signature)
    }

    @Test
    fun `different content changes the signature`() {
        assertTrue(snapshot("Rahul").signature != snapshot("Priya").signature)
    }

    @Test
    fun `sparse screens are flagged for screenshot fallback`() {
        assertTrue(snapshot("only one").isSparse())
        assertFalse(snapshot("a", "b", "c", "d", "e").isSparse())
    }
}

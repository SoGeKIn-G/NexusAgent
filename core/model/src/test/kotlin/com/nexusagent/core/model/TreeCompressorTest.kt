package com.nexusagent.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hand-built node tree. This is the whole reason [UiNode] is an interface — the
 * compression algorithm can be exercised against realistic hierarchies on the JVM,
 * with no device, no emulator and no Robolectric.
 */
private class FakeNode(
    override val className: String = "android.widget.FrameLayout",
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val viewIdResourceName: String? = null,
    override val bounds: Bounds = Bounds(0, 0, 1080, 100),
    override val isVisibleToUser: Boolean = true,
    override val isClickable: Boolean = false,
    override val isLongClickable: Boolean = false,
    override val isEditable: Boolean = false,
    override val isScrollable: Boolean = false,
    override val isCheckable: Boolean = false,
    override val isChecked: Boolean = false,
    override val isFocused: Boolean = false,
    override val isEnabled: Boolean = true,
    private val children: List<FakeNode> = emptyList(),
) : UiNode {
    override val childCount: Int get() = children.size
    override fun childAt(index: Int): UiNode? = children.getOrNull(index)
}

private fun text(value: String) = FakeNode(className = "android.widget.TextView", text = value)

class TreeCompressorTest {

    private val compressor = TreeCompressor()

    private fun compress(root: UiNode) =
        compressor.compress(root, "com.test", "TestActivity", nowMillis = 0)

    @Test
    fun `structural containers are discarded`() {
        val tree = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(
                    className = "android.widget.LinearLayout",
                    children = listOf(
                        FakeNode(
                            className = "androidx.constraintlayout.widget.ConstraintLayout",
                            children = listOf(
                                FakeNode(className = "android.widget.Button", isClickable = true, contentDescription = "Send"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val snapshot = compress(tree)

        // Four wrappers collapse to the single thing the agent can actually tap.
        assertEquals(1, snapshot.elements.size)
        assertEquals("Button", snapshot.elements[0].className)
        assertEquals("Send", snapshot.elements[0].contentDescription)
        // FrameLayout > LinearLayout > ConstraintLayout > Button
        assertEquals(4, snapshot.stats.rawNodeCount)
    }

    @Test
    fun `text hoists into the clickable ancestor`() {
        // The canonical WhatsApp chat row: a clickable container whose meaning lives
        // entirely in non-clickable children.
        val row = FakeNode(
            className = "android.widget.LinearLayout",
            isClickable = true,
            children = listOf(
                FakeNode(className = "android.widget.ImageView"),
                text("Rahul"),
                text("Hey, running late"),
                text("2:14 PM"),
            ),
        )

        val snapshot = compress(row)

        assertEquals(1, snapshot.elements.size)
        val body = snapshot.elements[0].text
        assertNotNull(body)
        // Pruning without hoisting would have thrown away the contact name, which is
        // exactly the information the model needs to pick the right row.
        assertTrue("lost the contact name: $body", body!!.contains("Rahul"))
        assertTrue("lost the preview: $body", body.contains("Hey, running late"))
        assertTrue("lost the timestamp: $body", body.contains("2:14 PM"))
    }

    @Test
    fun `text stops at the nearest interactive ancestor, not the outermost`() {
        val screen = FakeNode(
            children = listOf(
                FakeNode(className = "android.widget.LinearLayout", isClickable = true, children = listOf(text("Rahul"))),
                FakeNode(className = "android.widget.LinearLayout", isClickable = true, children = listOf(text("Priya"))),
            ),
        )

        val snapshot = compress(screen)

        assertEquals(2, snapshot.elements.size)
        assertEquals("Rahul", snapshot.elements[0].text)
        assertEquals("Priya", snapshot.elements[1].text)
    }

    @Test
    fun `invisible subtrees are skipped entirely`() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(className = "android.widget.Button", isClickable = true, contentDescription = "Visible"),
                FakeNode(
                    isVisibleToUser = false,
                    children = List(50) { FakeNode(className = "android.widget.Button", isClickable = true) },
                ),
            ),
        )

        val snapshot = compress(tree)

        assertEquals(1, snapshot.elements.size)
        // The hidden page's 50 children are never even visited — we stop at its root.
        assertEquals(3, snapshot.stats.rawNodeCount)
    }

    @Test
    fun `zero-size nodes are skipped`() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(className = "android.widget.Button", isClickable = true, bounds = Bounds(0, 0, 0, 0)),
                FakeNode(className = "android.widget.Button", isClickable = true, bounds = Bounds(0, 0, 100, 50)),
            ),
        )
        assertEquals(1, compress(tree).elements.size)
    }

    @Test
    fun `disabled controls are not offered as targets`() {
        val tree = FakeNode(
            children = listOf(
                FakeNode(className = "android.widget.Button", isClickable = true, isEnabled = false, text = "Pay"),
            ),
        )

        val snapshot = compress(tree)

        // Still surfaced as text so the model knows the button exists, but not as
        // something it can tap — otherwise it burns steps clicking a dead control.
        assertTrue(snapshot.elements.none { it.clickable })
        assertTrue(snapshot.elements.any { it.text?.contains("Pay") == true })
    }

    @Test
    fun `headings with no interactive ancestor survive as text`() {
        val tree = FakeNode(
            children = listOf(
                text("Your cart is empty"),
                FakeNode(className = "android.widget.Button", isClickable = true, contentDescription = "Shop now"),
            ),
        )

        val snapshot = compress(tree)

        assertTrue(
            "empty-state message was lost",
            snapshot.elements.any { it.text?.contains("Your cart is empty") == true },
        )
    }

    @Test
    fun `duplicate text and description are not repeated`() {
        val node = FakeNode(
            className = "android.widget.Button",
            isClickable = true,
            text = "Send",
            contentDescription = "Send",
        )

        val snapshot = compress(node)

        assertEquals("Send", snapshot.elements[0].contentDescription)
        assertNull("description was duplicated into the body", snapshot.elements[0].text)
    }

    @Test
    fun `long text is truncated`() {
        val long = "x".repeat(500)
        val node = FakeNode(className = "android.widget.LinearLayout", isClickable = true, children = listOf(text(long)))

        val body = compress(node).elements[0].text!!

        assertTrue("expected truncation, got ${body.length}", body.length <= CompressionConfig().maxTextLength)
    }

    @Test
    fun `element count is capped on pathological screens`() {
        val huge = FakeNode(
            children = List(500) { FakeNode(className = "android.widget.Button", isClickable = true) },
        )

        val snapshot = compress(huge)

        assertTrue(snapshot.elements.size <= CompressionConfig().maxElements)
    }

    @Test
    fun `empty screen does not crash`() {
        val snapshot = compressor.compress(null, "com.test", null, nowMillis = 0)
        assertTrue(snapshot.elements.isEmpty())
        assertEquals(0, snapshot.stats.rawNodeCount)
    }

    @Test
    fun `whitespace is collapsed`() {
        val node = FakeNode(
            className = "android.widget.LinearLayout",
            isClickable = true,
            children = listOf(text("  Hello\n\n   world  ")),
        )
        assertEquals("Hello world", compress(node).elements[0].text)
    }
}

class CompressionMetricsTest {

    /**
     * A realistic chat list: 20 rows, each a clickable container over an avatar plus
     * three text views, inside three layers of structural wrappers.
     */
    private fun chatList(rows: Int = 20): FakeNode = FakeNode(
        className = "android.widget.FrameLayout",
        children = listOf(
            FakeNode(
                className = "androidx.recyclerview.widget.RecyclerView",
                isScrollable = true,
                children = List(rows) { i ->
                    FakeNode(
                        className = "android.widget.FrameLayout",
                        children = listOf(
                            FakeNode(
                                className = "android.widget.LinearLayout",
                                isClickable = true,
                                children = listOf(
                                    FakeNode(className = "android.widget.ImageView"),
                                    FakeNode(
                                        className = "android.widget.LinearLayout",
                                        children = listOf(
                                            text("Contact $i"),
                                            text("Message preview number $i"),
                                        ),
                                    ),
                                    text("1${i}:00"),
                                ),
                            ),
                        ),
                    )
                },
            ),
        ),
    )

    @Test
    fun `compression hits the target reduction on a realistic list`() {
        val compressor = TreeCompressor(CompressionConfig(measureBaseline = true))
        val snapshot = compressor.compress(chatList(), "com.whatsapp", "HomeActivity", 0)

        val stats = snapshot.stats
        println(
            "nodes ${stats.rawNodeCount} -> ${stats.keptNodeCount} " +
                "(${"%.1f".format(stats.nodeReductionPercent)}% fewer), " +
                "bytes ${stats.rawBytes} -> ${stats.compressedBytes} " +
                "(${"%.1f".format(stats.byteReductionPercent)}% smaller)",
        )

        assertTrue(
            "node reduction was only ${stats.nodeReductionPercent}%",
            stats.nodeReductionPercent >= 70f,
        )
        assertTrue(
            "byte reduction was only ${stats.byteReductionPercent}%",
            stats.byteReductionPercent >= 60f,
        )
    }

    @Test
    fun `every row survives compression with its contact name`() {
        val compressor = TreeCompressor()
        val snapshot = compressor.compress(chatList(), "com.whatsapp", "HomeActivity", 0)

        // Reduction is worthless if it drops the data the model grounds on.
        repeat(20) { i ->
            assertTrue(
                "row $i lost its contact name",
                snapshot.elements.any { it.text?.contains("Contact $i") == true },
            )
        }
    }
}

class SnapshotJsonTest {

    @Test
    fun `false flags are omitted and true flags are terse`() {
        val json = SnapshotJson.encode(
            "com.test",
            "MainActivity",
            listOf(
                UiElement(
                    id = 0,
                    className = "Button",
                    contentDescription = "Send",
                    bounds = Bounds(1, 2, 3, 4),
                    clickable = true,
                ),
            ),
        )

        assertEquals("""{"pkg":"com.test","act":"MainActivity","el":[{"i":0,"c":"Button","d":"Send","k":1,"b":[1,2,3,4]}]}""", json)
    }

    @Test
    fun `quotes and newlines are escaped`() {
        val json = SnapshotJson.encode(
            "com.test",
            null,
            listOf(UiElement(id = 0, className = "T", text = "say \"hi\"\nthere", bounds = Bounds(0, 0, 1, 1))),
        )
        assertTrue(json.contains("""say \"hi\"\nthere"""))
    }

    @Test
    fun `activity package prefix is stripped`() {
        val json = SnapshotJson.encode("com.test", "com.test.ui.MainActivity", emptyList())
        assertTrue(json.contains(""""act":"MainActivity""""))
    }
}

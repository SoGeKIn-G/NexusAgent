package com.nexusagent.core.model

/**
 * Hand-rolled compact JSON for the screen payload.
 *
 * A general-purpose serializer is the wrong tool here. Every byte in this document is a
 * token the model pays for on every single step, so the encoding is deliberately hostile
 * to readability: one-character keys, boolean flags emitted only when true, nulls omitted
 * entirely, no whitespace.
 *
 * Key legend (also sent to the model once, in the system prompt):
 *
 * ```
 *   i  id            k  clickable      s  scrollable    b  bounds [l,t,r,b]
 *   c  class         e  editable       x  checked
 *   t  text          l  long-clickable f  focused
 *   d  description
 * ```
 */
object SnapshotJson {

    fun encode(
        packageName: String,
        activityName: String?,
        elements: List<UiElement>,
    ): String = buildString {
        append("{\"pkg\":").appendJsonString(packageName)
        if (activityName != null) {
            append(",\"act\":").appendJsonString(activityName.simpleName())
        }
        append(",\"el\":[")
        elements.forEachIndexed { index, element ->
            if (index > 0) append(',')
            appendElement(element)
        }
        append("]}")
    }

    private fun StringBuilder.appendElement(e: UiElement) {
        append("{\"i\":").append(e.id)
        append(",\"c\":").appendJsonString(e.className)
        e.text?.let { append(",\"t\":").appendJsonString(it) }
        e.contentDescription?.let { append(",\"d\":").appendJsonString(it) }
        // Only true flags are written; absence means false. Roughly halves the flag cost.
        if (e.clickable) append(",\"k\":1")
        if (e.longClickable) append(",\"l\":1")
        if (e.editable) append(",\"e\":1")
        if (e.scrollable) append(",\"s\":1")
        if (e.checkable) append(",\"x\":").append(if (e.checked) 1 else 0)
        if (e.focused) append(",\"f\":1")
        append(",\"b\":[")
            .append(e.bounds.left).append(',')
            .append(e.bounds.top).append(',')
            .append(e.bounds.right).append(',')
            .append(e.bounds.bottom)
        append("]}")
    }

    /**
     * Serializes the **unpruned** tree, to measure what compression actually saved.
     *
     * This is the honest baseline: what a naive implementation would have transmitted -
     * every node, every attribute, no hoisting. Returns byte length only; the string is
     * never kept, because on a heavy screen it is large and would be pure garbage
     * pressure.
     */
    fun encodeBaseline(root: UiNode): Int {
        val sb = StringBuilder()
        appendRawNode(sb, root)
        return sb.length
    }

    private fun appendRawNode(sb: StringBuilder, node: UiNode) {
        sb.append("{\"class\":").appendJsonString(node.className)
        node.text?.let { sb.append(",\"text\":").appendJsonString(it) }
        node.contentDescription?.let { sb.append(",\"contentDescription\":").appendJsonString(it) }
        node.viewIdResourceName?.let { sb.append(",\"viewIdResourceName\":").appendJsonString(it) }
        sb.append(",\"clickable\":").append(node.isClickable)
        sb.append(",\"longClickable\":").append(node.isLongClickable)
        sb.append(",\"editable\":").append(node.isEditable)
        sb.append(",\"scrollable\":").append(node.isScrollable)
        sb.append(",\"checkable\":").append(node.isCheckable)
        sb.append(",\"checked\":").append(node.isChecked)
        sb.append(",\"focused\":").append(node.isFocused)
        sb.append(",\"enabled\":").append(node.isEnabled)
        sb.append(",\"visible\":").append(node.isVisibleToUser)
        sb.append(",\"bounds\":\"[").append(node.bounds.left).append(',').append(node.bounds.top)
            .append("][").append(node.bounds.right).append(',').append(node.bounds.bottom).append("]\"")

        if (node.childCount > 0) {
            sb.append(",\"children\":[")
            var wrote = 0
            for (i in 0 until node.childCount) {
                val child = node.childAt(i) ?: continue
                if (wrote++ > 0) sb.append(',')
                appendRawNode(sb, child)
            }
            sb.append(']')
        }
        sb.append('}')
    }

    /** Minimal RFC 8259 string escaping. Kotlin has no '\f' literal, hence the unicode escape. */
    private fun StringBuilder.appendJsonString(value: String): StringBuilder {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        return append('"')
    }
}

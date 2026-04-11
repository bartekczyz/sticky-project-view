// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package info.chrzanowski.stickyprojectview

import com.intellij.openapi.diagnostic.Logger
import java.awt.Rectangle
import javax.swing.JTree
import javax.swing.tree.TreePath
import kotlin.math.max
import kotlin.math.min

internal object StickyTreeDebugLogger {
    private val LOG = Logger.getInstance(StickyTreeDebugLogger::class.java)

    fun logAttachment(tree: JTree, reason: String) {
        if (!LOG.isDebugEnabled) {
            return
        }

        LOG.debug(
            "[SPV] attach reason=$reason" +
                " treeHash=${System.identityHashCode(tree)}" +
                " rowCount=${tree.rowCount}" +
                " visible=${rect(tree.visibleRect)}"
        )
    }

    fun logManagerEvent(
        tree: JTree,
        reason: String,
        forceRecalculate: Boolean,
        didRecalculate: Boolean,
        visibleRect: Rectangle,
        panelHeight: Int,
        stickyItems: List<StickyTreeItem>
    ) {
        if (!LOG.isDebugEnabled) {
            return
        }

        LOG.debug(
            "[SPV] manager reason=$reason" +
                " force=$forceRecalculate" +
                " didRecalculate=$didRecalculate" +
                " treeHash=${System.identityHashCode(tree)}" +
                " visible=${rect(visibleRect)}" +
                " panelHeight=$panelHeight" +
                " visibleRows=${visibleRows(tree, visibleRect)}" +
                " stack=${stack(tree, stickyItems)}"
        )
    }

    fun logRecalculation(
        tree: JTree,
        visibleRect: Rectangle,
        stickyItems: List<StickyTreeItem>,
        panelHeight: Int,
        steps: List<String>
    ) {
        if (!LOG.isDebugEnabled) {
            return
        }

        LOG.debug(
            "[SPV] recalc" +
                " treeHash=${System.identityHashCode(tree)}" +
                " visible=${rect(visibleRect)}" +
                " panelHeight=$panelHeight" +
                " visibleRows=${visibleRows(tree, visibleRect)}" +
                " stack=${stack(tree, stickyItems)}" +
                " steps=${steps.joinToString(" || ")}"
        )
    }

    fun logPaint(
        tree: JTree,
        panelWidth: Int,
        panelHeight: Int,
        stickyItems: List<StickyTreeItem>,
        placements: List<String>
    ) {
        if (!LOG.isDebugEnabled) {
            return
        }

        LOG.debug(
            "[SPV] paint" +
                " treeHash=${System.identityHashCode(tree)}" +
                " panel=${panelWidth}x$panelHeight" +
                " visible=${rect(tree.visibleRect)}" +
                " visibleRows=${visibleRows(tree, tree.visibleRect)}" +
                " stack=${stack(tree, stickyItems)}" +
                " placements=${placements.joinToString(" | ")}"
        )
    }

    private fun visibleRows(tree: JTree, visibleRect: Rectangle, limit: Int = 8): String {
        if (tree.rowCount == 0) {
            return "[]"
        }

        val startRow = max(tree.getClosestRowForLocation(0, visibleRect.y) - 1, 0)
        val endExclusive = min(startRow + limit, tree.rowCount)
        return buildString {
            append('[')
            for (row in startRow until endExclusive) {
                if (row > startRow) {
                    append(", ")
                }

                val path = tree.getPathForRow(row)
                val bounds = tree.getRowBounds(row)
                append(row)
                append(':')
                append(path?.lastPathComponent ?: "<null>")
                append("@")
                append(rect(bounds))
                if (path != null) {
                    append("(expanded=")
                    append(tree.isExpanded(path))
                    append(",leaf=")
                    append(tree.model.isLeaf(path.lastPathComponent))
                    append(')')
                }
            }
            append(']')
        }
    }

    private fun stack(tree: JTree, stickyItems: List<StickyTreeItem>): String {
        if (stickyItems.isEmpty()) {
            return "[]"
        }

        return stickyItems.joinToString(prefix = "[", postfix = "]") { item ->
            val bounds = tree.getRowBounds(item.row)
            "${pathText(item.treePath)}#row=${item.row},y=${item.yLocation},bounds=${rect(bounds)}"
        }
    }

    fun pathText(path: TreePath): String {
        return path.path.joinToString(" > ") { it.toString() }
    }

    fun rect(rectangle: Rectangle?): String {
        return if (rectangle == null) {
            "null"
        } else {
            "(${rectangle.x},${rectangle.y},${rectangle.width},${rectangle.height})"
        }
    }
}

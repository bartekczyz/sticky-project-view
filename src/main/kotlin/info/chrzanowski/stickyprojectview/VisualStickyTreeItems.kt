// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package info.chrzanowski.stickyprojectview

import java.awt.Rectangle
import javax.swing.JTree
import javax.swing.tree.TreePath

/**
 * Manages the collection and calculation of sticky tree items based on the visible area.
 * Inspired by VisualStickyLines for editor implementation.
 */
class VisualStickyTreeItems(private val tree: JTree) {
    private val stickyItems: MutableList<StickyTreeItem> = mutableListOf()
    private var totalHeight: Int = 0

    /**
     * Recalculates which tree items should be sticky based on the current viewport
     */
    fun recalculate(visibleRect: Rectangle, revealedPath: TreePath? = null, maxStickyHeightPercent: Int = 50) {
        clear()
        val contentBudget = (visibleRect.height.toLong() * maxStickyHeightPercent.coerceIn(10, 90) / 100).toInt()
        val steps = mutableListOf<String>()

        if (visibleRect.isEmpty || tree.rowCount == 0) {
            steps += "stop emptyVisibleRect=${visibleRect.isEmpty} rowCount=${tree.rowCount}"
            StickyTreeDebugLogger.logRecalculation(tree, visibleRect, stickyItems, totalHeight, steps)
            return
        }

        // No sticky items if we haven't scrolled at all
        if (visibleRect.y < 0 || visibleRect.y == 0 && revealedPath == null) {
            steps += "stop topOfViewport y=${visibleRect.y}"
            StickyTreeDebugLogger.logRecalculation(tree, visibleRect, stickyItems, totalHeight, steps)
            return
        }

        var stickyDepth = 0
        var panelHeight = 0

        while (stickyDepth < tree.rowCount) {
            val lookupTop = visibleRect.y + panelHeight
            val firstVisibleRow = findTopPartiallyVisibleRow(lookupTop)
            if (firstVisibleRow < 0) {
                steps += "stop noFirstVisibleRow lookupTop=$lookupTop"
                break
            }

            val firstVisiblePath = tree.getPathForRow(firstVisibleRow) ?: break
            val stickyPathChain = pathToRoot(firstVisiblePath).filter(::isVisiblePath)
            if (stickyDepth >= stickyPathChain.size) {
                steps += "stop depthOutOfChain depth=$stickyDepth chain=${stickyPathChain.joinToString(" -> ", transform = StickyTreeDebugLogger::pathText)}"
                break
            }

            val stickyPath = stickyPathChain[stickyDepth]
            if (stickyPath == revealedPath) {
                steps += "stop revealedPath depth=$stickyDepth candidate=${StickyTreeDebugLogger.pathText(stickyPath)}"
                break
            }
            if (!isStickyCandidate(stickyPath)) {
                steps += "stop notStickyCandidate depth=$stickyDepth candidate=${StickyTreeDebugLogger.pathText(stickyPath)}"
                break
            }

            val row = tree.getRowForPath(stickyPath)
            if (row < 0) {
                steps += "stop rowMissing candidate=${StickyTreeDebugLogger.pathText(stickyPath)}"
                break
            }

            val rowBounds = tree.getRowBounds(row) ?: break
            val rowHeight = rowBounds.height.coerceAtLeast(1)
            val keepsRevealedItemBelowStack =
                revealedPath != null &&
                    stickyPath != revealedPath &&
                    isDescendantOf(revealedPath, stickyPath) &&
                    rowBounds.y == lookupTop
            if ((rowBounds.y > lookupTop || rowBounds.y == lookupTop && !keepsRevealedItemBelowStack) ||
                isPanelTooBig(rowHeight, panelHeight, contentBudget)
            ) {
                steps += "stop rowNotAboveOrPanelTooBig depth=$stickyDepth candidate=${StickyTreeDebugLogger.pathText(stickyPath)} rowBounds=${StickyTreeDebugLogger.rect(rowBounds)} lookupTop=$lookupTop panelHeight=$panelHeight rowHeight=$rowHeight viewportHeight=${visibleRect.height}"
                break
            }

            val subtreeBottom = subtreeBottom(stickyPath) ?: break
            val overlap = (lookupTop + rowHeight - subtreeBottom).coerceAtLeast(0)
            val visibleHeight = rowHeight - overlap
            if (visibleHeight <= 0) {
                steps += "stop invisibleCandidate depth=$stickyDepth candidate=${StickyTreeDebugLogger.pathText(stickyPath)} overlap=$overlap visibleHeight=$visibleHeight subtreeBottom=$subtreeBottom"
                break
            }

            steps += "add depth=$stickyDepth lookupTop=$lookupTop firstVisibleRow=$firstVisibleRow firstVisible=${StickyTreeDebugLogger.pathText(firstVisiblePath)} candidate=${StickyTreeDebugLogger.pathText(stickyPath)} row=$row rowBounds=${StickyTreeDebugLogger.rect(rowBounds)} subtreeBottom=$subtreeBottom overlap=$overlap visibleHeight=$visibleHeight yLocation=${panelHeight - overlap}"
            stickyItems.add(
                StickyTreeItem(
                    treePath = stickyPath,
                    row = row,
                    yLocation = panelHeight - overlap
                )
            )

            panelHeight += visibleHeight
            stickyDepth += 1

            if (overlap > 0 || isPanelTooBig(rowHeight, panelHeight, contentBudget)) {
                steps += "stop handoffOrPanelTooBig overlap=$overlap panelHeight=$panelHeight rowHeight=$rowHeight viewportHeight=${visibleRect.height}"
                break
            }
        }

        totalHeight = panelHeight
        StickyTreeDebugLogger.logRecalculation(tree, visibleRect, stickyItems, totalHeight, steps)
    }

    /**
     * Returns the list of sticky items with their Y positions calculated
     */
    fun items(): List<StickyTreeItem> = stickyItems

    fun height(): Int = totalHeight

    fun clear() {
        stickyItems.clear()
        totalHeight = 0
    }

    private fun isVisiblePath(path: TreePath) = tree.getRowForPath(path) >= 0

    private fun pathToRoot(path: TreePath): List<TreePath> = generateSequence(path) { it.parentPath }.toList().asReversed()

    private fun isStickyCandidate(path: TreePath) = tree.isExpanded(path) && !tree.model.isLeaf(path.lastPathComponent)

    private fun subtreeBottom(path: TreePath) =
        lastVisibleSubtreeRow(path)
            ?.let(tree::getRowBounds)
            ?.let { it.y + it.height }

    private fun lastVisibleSubtreeRow(path: TreePath): Int? {
        val row = tree.getRowForPath(path)
        if (row < 0) {
            return null
        }

        var lastRow = row
        for (nextRow in row + 1 until tree.rowCount) {
            val nextPath = tree.getPathForRow(nextRow) ?: break
            if (!isDescendantOf(nextPath, path)) {
                break
            }
            lastRow = nextRow
        }
        return lastRow
    }

    private fun isDescendantOf(path: TreePath, ancestor: TreePath): Boolean {
        if (path.pathCount < ancestor.pathCount) {
            return false
        }

        val pathSegments = path.path
        val ancestorSegments = ancestor.path
        for (index in ancestorSegments.indices) {
            if (pathSegments[index] != ancestorSegments[index]) {
                return false
            }
        }

        return true
    }

    private fun findTopPartiallyVisibleRow(topY: Int): Int {
        val rowCount = tree.rowCount
        if (rowCount <= 0) {
            return -1
        }

        val rowHeight = tree.rowHeight.coerceAtLeast(1)
        var guess = (topY / rowHeight).coerceIn(0, rowCount - 1)
        if (guess > 0) {
            guess -= 1
        }

        var fallback = -1
        for (row in guess until rowCount) {
            val bounds = tree.getRowBounds(row) ?: continue
            if (bounds.y + bounds.height <= topY) {
                fallback = row
                continue
            }
            return row
        }

        return if (fallback >= 0) fallback else rowCount - 1
    }

    private fun isPanelTooBig(itemHeight: Int, panelHeight: Int, contentBudget: Int) =
        panelHeight + 2 * itemHeight > contentBudget
}

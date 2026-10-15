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
        val steps = if (StickyTreeDebugLogger.isDebugEnabled) mutableListOf<String>() else null

        if (visibleRect.isEmpty || tree.rowCount == 0) {
            steps?.add("stop emptyVisibleRect=${visibleRect.isEmpty} rowCount=${tree.rowCount}")
            StickyTreeDebugLogger.logRecalculation(tree, visibleRect, stickyItems, totalHeight, steps.orEmpty())
            return
        }

        // No sticky items if we haven't scrolled at all
        if (visibleRect.y < 0 || visibleRect.y == 0 && revealedPath == null) {
            steps?.add("stop topOfViewport y=${visibleRect.y}")
            StickyTreeDebugLogger.logRecalculation(tree, visibleRect, stickyItems, totalHeight, steps.orEmpty())
            return
        }

        var stickyDepth = 0
        var panelHeight = 0

        while (stickyDepth < tree.rowCount) {
            val lookupTop = visibleRect.y + panelHeight
            val firstVisibleRow = findTopPartiallyVisibleRow(lookupTop)
            if (firstVisibleRow < 0) {
                steps?.add("stop noFirstVisibleRow lookupTop=$lookupTop")
                break
            }

            val firstVisiblePath = tree.getPathForRow(firstVisibleRow) ?: break
            val stickyPathChain = pathToRoot(firstVisiblePath).filter(::isVisiblePath)
            if (stickyDepth >= stickyPathChain.size) {
                steps?.add("stop depthOutOfChain depth=$stickyDepth chain=${stickyPathChain.joinToString(" -> ", transform = StickyTreeDebugLogger::pathText)}")
                break
            }

            val stickyPath = stickyPathChain[stickyDepth]
            if (stickyPath == revealedPath) {
                steps?.add("stop revealedPath depth=$stickyDepth candidate=${StickyTreeDebugLogger.pathText(stickyPath)}")
                break
            }
            if (!isStickyCandidate(stickyPath)) {
                steps?.add("stop notStickyCandidate depth=$stickyDepth candidate=${StickyTreeDebugLogger.pathText(stickyPath)}")
                break
            }

            val row = tree.getRowForPath(stickyPath)
            if (row < 0) {
                steps?.add("stop rowMissing candidate=${StickyTreeDebugLogger.pathText(stickyPath)}")
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
                steps?.add("stop rowNotAboveOrPanelTooBig depth=$stickyDepth candidate=${StickyTreeDebugLogger.pathText(stickyPath)} rowBounds=${StickyTreeDebugLogger.rect(rowBounds)} lookupTop=$lookupTop panelHeight=$panelHeight rowHeight=$rowHeight viewportHeight=${visibleRect.height}")
                break
            }

            val subtreeBottom = subtreeBottom(stickyPath) ?: break
            val overlap = (lookupTop + rowHeight - subtreeBottom).coerceAtLeast(0)
            val visibleHeight = rowHeight - overlap
            if (visibleHeight <= 0) {
                steps?.add("stop invisibleCandidate depth=$stickyDepth candidate=${StickyTreeDebugLogger.pathText(stickyPath)} overlap=$overlap visibleHeight=$visibleHeight subtreeBottom=$subtreeBottom")
                break
            }

            steps?.add("add depth=$stickyDepth lookupTop=$lookupTop firstVisibleRow=$firstVisibleRow firstVisible=${StickyTreeDebugLogger.pathText(firstVisiblePath)} candidate=${StickyTreeDebugLogger.pathText(stickyPath)} row=$row rowBounds=${StickyTreeDebugLogger.rect(rowBounds)} subtreeBottom=$subtreeBottom overlap=$overlap visibleHeight=$visibleHeight yLocation=${panelHeight - overlap}")
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
                steps?.add("stop handoffOrPanelTooBig overlap=$overlap panelHeight=$panelHeight rowHeight=$rowHeight viewportHeight=${visibleRect.height}")
                break
            }
        }

        totalHeight = panelHeight
        StickyTreeDebugLogger.logRecalculation(tree, visibleRect, stickyItems, totalHeight, steps.orEmpty())
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
        var upperRow = tree.rowCount - 1
        while (lastRow < upperRow) {
            val middleRow = lastRow + (upperRow - lastRow + 1) / 2
            val middlePath = tree.getPathForRow(middleRow) ?: return null
            if (isDescendantOf(middlePath, path)) {
                lastRow = middleRow
            } else {
                upperRow = middleRow - 1
            }
        }
        return lastRow
    }

    private fun isDescendantOf(path: TreePath, ancestor: TreePath): Boolean {
        return ancestor.isDescendant(path)
    }

    private fun findTopPartiallyVisibleRow(topY: Int): Int {
        val row = tree.getClosestRowForLocation(0, topY)
        if (row < 0) {
            return -1
        }

        val bounds = tree.getRowBounds(row) ?: return -1
        return if (bounds.y + bounds.height <= topY) (row + 1).coerceAtMost(tree.rowCount - 1) else row
    }

    private fun isPanelTooBig(itemHeight: Int, panelHeight: Int, contentBudget: Int) =
        panelHeight + 2 * itemHeight > contentBudget
}

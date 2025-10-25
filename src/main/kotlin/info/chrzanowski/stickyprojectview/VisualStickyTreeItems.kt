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
    private var offsetY: Int = 0
    
    /**
     * Recalculates which tree items should be sticky based on the current viewport
     */
    fun recalculate(visibleRect: Rectangle) {
        clear()

        if (visibleRect.isEmpty || tree.rowCount == 0) {
            return
        }

        val rowHeight = tree.rowHeight.coerceAtLeast(1)

        // No sticky items if we haven't scrolled at all
        if (visibleRect.y <= 0) {
            return
        }

        // Find the row at the effective viewport top (accounting for sticky items we'll add)
        var topRow = -1
        for (row in 0 until tree.rowCount) {
            val rowBounds = tree.getRowBounds(row)
            if (rowBounds != null) {
                val rowBottom = rowBounds.y
                // This row's bottom is below the viewport top
                if (rowBottom > visibleRect.y) {
                    topRow = row
                    break
                }
            }
        }

        if (topRow < 0) {
            return
        }

        val topPath = tree.getPathForRow(topRow) ?: return

        // Collect the chain of expanded nodes that should be sticky
        collectExpandedChain(topPath, topRow, visibleRect.y, rowHeight)

        if (stickyItems.isNotEmpty()) {
            totalHeight = stickyItems.size * rowHeight
        }
    }
    
    /**
     * Returns the list of sticky items with their Y positions calculated
     */
    fun items(): List<StickyTreeItem> {
        return stickyItems
    }
    
    /**
     * Returns the total height of all sticky items
     */
    fun height(): Int {
        return totalHeight
    }

    /**
     * Clears all sticky items
     */
    fun clear() {
        stickyItems.clear()
        totalHeight = 0
        offsetY = 0
    }
    
    /**
     * Collects the chain of expanded nodes from root down to the deepest one
     * that has children visible in or below the viewport
     */
    private fun collectExpandedChain(topPath: TreePath, topRow: Int, scrollY: Int, rowHeight: Int) {
        // Build list of ancestors from root to topPath
        val ancestors = mutableListOf<TreePath>()
        var currentPath: TreePath? = topPath
        while (currentPath != null) {
            ancestors.add(0, currentPath)
            currentPath = currentPath.parentPath
        }

        // The effective viewport top starts at scrollY and moves down as we add sticky items
        // This represents where the actual visible content starts (after accounting for STP)
        var effectiveViewportTop = scrollY

        for (i in ancestors.indices) {
            val path = ancestors[i]
            val row = tree.getRowForPath(path)
            if (row < 0) continue

            val rowBounds = tree.getRowBounds(row) ?: continue
            val rowTop = rowBounds.y

            // Check if this row should be sticky:
            // 1. It must be at or before topRow (ancestor or the top row itself)
            // 2. Any part of it must be scrolled out: row top is above the effective viewport top
            val isAncestor = row <= topRow
            val isScrolledOut = rowTop < effectiveViewportTop

            if (isAncestor && isScrolledOut) {
                // Must be expanded
                if (tree.isExpanded(path)) {
                    // Must have children (not a leaf)
                    val model = tree.model
                    val node = path.lastPathComponent
                    if (!model.isLeaf(node)) {
                        val item = StickyTreeItem(
                            treePath = path,
                            row = row,
                            depth = path.pathCount - 1,
                            isRoot = path.pathCount == 1
                        )
                        stickyItems.add(item)

                        // Move the effective viewport top down by this item's height
                        // This simulates the sticky panel covering the viewport
                        effectiveViewportTop += rowHeight
                    }
                }
            }
        }
    }
}

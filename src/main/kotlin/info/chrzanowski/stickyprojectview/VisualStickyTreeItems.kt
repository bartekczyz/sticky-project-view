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

        // Find the first visible row (one that's at least partially visible)
        val firstVisibleRow = tree.getClosestRowForLocation(0, visibleRect.y)
        if (firstVisibleRow < 0) {
            return
        }

        val firstVisiblePath = tree.getPathForRow(firstVisibleRow) ?: return

        // Build ancestor chain from root to first visible row
        val ancestors = mutableListOf<TreePath>()
        var currentPath: TreePath? = firstVisiblePath
        while (currentPath != null) {
            ancestors.add(0, currentPath)
            currentPath = currentPath.parentPath
        }

        // The effective viewport top grows as we add sticky items
        var effectiveViewportTop = visibleRect.y

        // Process each ancestor to see if it should be sticky
        for (path in ancestors) {
            val row = tree.getRowForPath(path)
            if (row < 0) continue

            val rowBounds = tree.getRowBounds(row) ?: continue
            val rowTop = rowBounds.y
            val rowBottom = rowTop + rowBounds.height
            val depth = path.pathCount - 1
            val isRoot = depth == 0

            // Determine if this row should be sticky
            val shouldBeSticky = if (isRoot) {
                // Root sticks as soon as any scrolling happens
                rowTop < visibleRect.y
            } else {
                // Non-root items stick when their bottom is at or above the effective viewport top
                rowBottom <= effectiveViewportTop
            }

            if (shouldBeSticky) {
                // Must be expanded and have children
                if (tree.isExpanded(path)) {
                    val model = tree.model
                    val node = path.lastPathComponent
                    if (!model.isLeaf(node)) {
                        val item = StickyTreeItem(
                            treePath = path,
                            row = row,
                            depth = depth,
                            isRoot = isRoot
                        )
                        stickyItems.add(item)

                        // Move effective viewport down to account for this sticky item
                        effectiveViewportTop += rowHeight
                    }
                }
            }
        }

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
}

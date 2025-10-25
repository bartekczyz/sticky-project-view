// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package info.chrzanowski.stickyprojectview

import javax.swing.tree.TreePath

/**
 * Represents a tree item that should be displayed as sticky at the top of the scrollable tree view.
 * Similar to StickyLine but adapted for tree structures.
 */
data class StickyTreeItem(
    /**
     * The tree path identifying this node in the tree hierarchy
     */
    val treePath: TreePath,
    
    /**
     * The vertical position (row index) of this item in the tree
     */
    val row: Int,
    
    /**
     * The depth level in the tree (0 for root, 1 for children, etc.)
     */
    val depth: Int,
    
    /**
     * Whether this is the root item
     */
    val isRoot: Boolean = depth == 0
) : Comparable<StickyTreeItem> {
    
    override fun compareTo(other: StickyTreeItem): Int {
        return row.compareTo(other.row)
    }
}

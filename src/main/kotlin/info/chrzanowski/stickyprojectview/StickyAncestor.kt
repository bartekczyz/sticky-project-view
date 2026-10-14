package info.chrzanowski.stickyprojectview

import javax.swing.tree.TreePath

data class StickyAncestor(val treePath: TreePath, val label: String, val fullPath: String)

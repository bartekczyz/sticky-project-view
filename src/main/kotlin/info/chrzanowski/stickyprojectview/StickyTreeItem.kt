// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package info.chrzanowski.stickyprojectview

import javax.swing.tree.TreePath

data class StickyTreeItem(
    val treePath: TreePath,
    val row: Int,
    val yLocation: Int
)

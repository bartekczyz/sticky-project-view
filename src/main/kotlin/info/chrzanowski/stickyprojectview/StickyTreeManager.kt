// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package info.chrzanowski.stickyprojectview

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import java.awt.Point
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.awt.Rectangle
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.event.ChangeListener
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath

/**
 * Manages the sticky tree items behavior by coordinating between the tree,
 * the visual sticky items calculator, and the sticky panel.
 * Inspired by StickyLinesManager for editor implementation.
 */
class StickyTreeManager(
    private val tree: JTree,
    private val scrollPane: JScrollPane,
    private val visualStickyItems: VisualStickyTreeItems,
    private val stickyPanel: StickyTreePanel,
    parentDisposable: Disposable
) : TreeExpansionListener, Disposable {

    private var lastVisibleRect: Rectangle = Rectangle()
    private var observedModel: TreeModel = tree.model
    private var revealedStickyItem: RevealedStickyItem? = null
    private val viewportChangeListener = ChangeListener { recalculateAndRepaint(reason = "viewport") }
    private val treeSelectionListener = TreeSelectionListener {
        if (tree.selectionPath != revealedStickyItem?.treePath) {
            revealedStickyItem = null
        }
        repaintPanel("selection")
    }
    private val focusListener = object : FocusListener {
        override fun focusGained(event: FocusEvent?) {
            repaintPanel("focusGained")
        }

        override fun focusLost(event: FocusEvent?) {
            repaintPanel("focusLost")
        }
    }
    private val treeModelListener = object : TreeModelListener {
        override fun treeNodesChanged(event: TreeModelEvent) = recalculateAndRepaint(forceRecalculate = true, reason = "treeNodesChanged")

        override fun treeNodesInserted(event: TreeModelEvent) = recalculateAndRepaint(forceRecalculate = true, reason = "treeNodesInserted")

        override fun treeNodesRemoved(event: TreeModelEvent) = recalculateAndRepaint(forceRecalculate = true, reason = "treeNodesRemoved")

        override fun treeStructureChanged(event: TreeModelEvent) = recalculateAndRepaint(forceRecalculate = true, reason = "treeStructureChanged")
    }
    private val propertyChangeListener = PropertyChangeListener(::handlePropertyChange)

    init {
        Disposer.register(parentDisposable, this)

        scrollPane.viewport.addChangeListener(viewportChangeListener)
        tree.addTreeExpansionListener(this)
        tree.addTreeSelectionListener(treeSelectionListener)
        tree.addFocusListener(focusListener)
        tree.addPropertyChangeListener(propertyChangeListener)
        observedModel.addTreeModelListener(treeModelListener)
    }

    override fun treeExpanded(event: TreeExpansionEvent) {
        recalculateAndRepaint(forceRecalculate = true, reason = "treeExpanded")
    }

    override fun treeCollapsed(event: TreeExpansionEvent) {
        recalculateAndRepaint(forceRecalculate = true, reason = "treeCollapsed")
    }

    /**
     * Recalculates sticky items and repaints the panel
     */
    fun recalculateAndRepaint(forceRecalculate: Boolean = false, reason: String = "unspecified") {
        val visibleRect = currentViewportRect()
        val didRecalculate = forceRecalculate || visibleRect != lastVisibleRect

        if (didRecalculate) {
            lastVisibleRect = Rectangle(visibleRect)
            val revealedPath = revealedStickyItem?.takeIf { it.viewportY == visibleRect.y }?.treePath
            if (revealedPath == null) {
                revealedStickyItem = null
            }
            visualStickyItems.recalculate(visibleRect, revealedPath)
        }
        stickyPanel.repaintStickyItems()
        StickyTreeDebugLogger.logManagerEvent(
            tree = tree,
            reason = reason,
            forceRecalculate = forceRecalculate,
            didRecalculate = didRecalculate,
            visibleRect = visibleRect,
            panelHeight = visualStickyItems.height(),
            stickyItems = visualStickyItems.items()
        )
    }

    private fun currentViewportRect(): Rectangle {
        return Rectangle(scrollPane.viewport.viewRect)
    }

    /**
     * Forces a recalculation and repaint
     */
    fun forceUpdate() {
        lastVisibleRect = Rectangle()
        recalculateAndRepaint(forceRecalculate = true, reason = "forceUpdate")
    }

    fun navigateTo(stickyItem: StickyTreeItem) {
        val row = tree.getRowForPath(stickyItem.treePath).takeIf { it >= 0 } ?: return
        val rowBounds = tree.getRowBounds(row) ?: return
        val targetY = navigationTargetY(stickyItem.treePath, rowBounds, currentViewportRect())
        val previewHeight = previewStickyHeight(targetY, stickyItem.treePath)

        revealedStickyItem = RevealedStickyItem(stickyItem.treePath, targetY)
        StickyTreeDebugLogger.logNavigation(tree, stickyItem, rowBounds, currentViewportRect(), targetY, previewHeight)
        tree.selectionPath = stickyItem.treePath
        tree.requestFocusInWindow()
        scrollPane.viewport.viewPosition = Point(0, targetY)
        forceUpdate()
    }

    private fun handlePropertyChange(event: PropertyChangeEvent) {
        when (event.propertyName) {
            "model" -> {
                (event.oldValue as? TreeModel)?.removeTreeModelListener(treeModelListener)
                val newModel = (event.newValue as? TreeModel) ?: (tree.model as TreeModel?)
                if (newModel == null) {
                    visualStickyItems.clear()
                    stickyPanel.repaintStickyItems()
                    return
                }
                observedModel = newModel
                observedModel.addTreeModelListener(treeModelListener)
                recalculateAndRepaint(forceRecalculate = true, reason = "modelChanged")
            }

            "rowHeight" -> recalculateAndRepaint(forceRecalculate = true, reason = "rowHeightChanged")
            "cellRenderer", "font" -> repaintPanel(event.propertyName)
        }
    }

    private fun repaintPanel(reason: String) {
        stickyPanel.repaintStickyItems()
        StickyTreeDebugLogger.logManagerEvent(
            tree = tree,
            reason = "repaint:$reason",
            forceRecalculate = false,
            didRecalculate = false,
            visibleRect = currentViewportRect(),
            panelHeight = visualStickyItems.height(),
            stickyItems = visualStickyItems.items()
        )
    }

    internal fun navigationTargetY(stickyItem: StickyTreeItem, viewportRect: Rectangle): Int {
        val rowBounds = tree.getRowBounds(stickyItem.row) ?: return 0
        return navigationTargetY(stickyItem.treePath, rowBounds, viewportRect)
    }

    private fun navigationTargetY(stickyPath: TreePath, rowBounds: Rectangle, viewportRect: Rectangle): Int {
        val maxY = maxViewportY(viewportRect.height)
        var targetY = rowBounds.y.coerceIn(0, maxY)
        repeat(4) {
            val remainingStickyHeight = previewStickyHeight(viewportRect, targetY, stickyPath)
            val nextTargetY = (rowBounds.y - remainingStickyHeight).coerceIn(0, maxY)
            if (nextTargetY == targetY) {
                return targetY
            }
            targetY = nextTargetY
        }
        return targetY
    }

    private fun previewStickyHeight(targetY: Int, revealedPath: TreePath): Int {
        return previewStickyHeight(currentViewportRect(), targetY, revealedPath)
    }

    private fun previewStickyHeight(viewportRect: Rectangle, targetY: Int, revealedPath: TreePath): Int {
        val previewItems = VisualStickyTreeItems(tree)
        previewItems.recalculate(Rectangle(viewportRect).apply { y = targetY }, revealedPath)
        return previewItems.height()
    }

    private fun maxViewportY(viewportHeight: Int): Int {
        return (maxOf(tree.height, tree.preferredSize.height) - viewportHeight).coerceAtLeast(0)
    }

    override fun dispose() {
        scrollPane.viewport.removeChangeListener(viewportChangeListener)
        tree.removeTreeExpansionListener(this)
        tree.removeTreeSelectionListener(treeSelectionListener)
        tree.removeFocusListener(focusListener)
        tree.removePropertyChangeListener(propertyChangeListener)
        observedModel.removeTreeModelListener(treeModelListener)
    }

    private data class RevealedStickyItem(
        val treePath: TreePath,
        val viewportY: Int
    )
}

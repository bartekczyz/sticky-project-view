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
import javax.swing.SwingUtilities
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
    parentDisposable: Disposable,
    maxStickyHeightPercent: Int = 50,
) : TreeExpansionListener, Disposable {

    private var lastVisibleRect: Rectangle = Rectangle()
    private var maxStickyHeightPercent = maxStickyHeightPercent.coerceIn(10, 90)
    private var observedModel: TreeModel = tree.model
    private var revealedStickyItem: RevealedStickyItem? = null
    private var updateScheduled = false
    private var recalculationPending = false
    private var disposed = false
    private val viewportChangeListener = ChangeListener { recalculateAndRepaint(reason = "viewport") }
    private val treeSelectionListener = TreeSelectionListener {
        if (revealedStickyItem != null && tree.selectionPath != revealedStickyItem?.treePath) {
            revealedStickyItem = null
            if (!recalculationPending) {
                recalculateAndRepaint(forceRecalculate = true, reason = "selectionClearedReveal")
            }
        } else {
            repaintPanel("selection")
        }
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
        override fun treeNodesChanged(event: TreeModelEvent) = scheduleRecalculation("treeNodesChanged")

        override fun treeNodesInserted(event: TreeModelEvent) = scheduleRecalculation("treeNodesInserted")

        override fun treeNodesRemoved(event: TreeModelEvent) = scheduleRecalculation("treeNodesRemoved")

        override fun treeStructureChanged(event: TreeModelEvent) = scheduleRecalculation("treeStructureChanged")
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
        scheduleRecalculation("treeExpanded")
    }

    override fun treeCollapsed(event: TreeExpansionEvent) {
        scheduleRecalculation("treeCollapsed")
    }

    private fun scheduleRecalculation(reason: String) {
        if (disposed) return
        recalculationPending = true
        if (updateScheduled) return
        updateScheduled = true
        // Model listeners can run before the tree UI updates its layout cache.
        SwingUtilities.invokeLater {
            updateScheduled = false
            if (!disposed && recalculationPending) {
                recalculateAndRepaint(forceRecalculate = true, reason = "deferred:$reason")
            }
        }
    }

    /**
     * Recalculates sticky items and repaints the panel
     */
    fun recalculateAndRepaint(forceRecalculate: Boolean = false, reason: String = "unspecified") {
        if (disposed || recalculationPending && !forceRecalculate) return
        recalculationPending = false
        val visibleRect = currentViewportRect()
        val didRecalculate = forceRecalculate || visibleRect != lastVisibleRect

        if (didRecalculate) {
            lastVisibleRect = Rectangle(visibleRect)
            val revealedPath = revealedStickyItem?.takeIf { it.viewportY == visibleRect.y }?.treePath
            if (revealedPath == null) {
                revealedStickyItem = null
            }
            visualStickyItems.recalculate(visibleRect, revealedPath, maxStickyHeightPercent)
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

    fun setMaxStickyHeightPercent(percent: Int) {
        val normalized = percent.coerceIn(10, 90)
        if (maxStickyHeightPercent == normalized) return
        maxStickyHeightPercent = normalized
        forceUpdate()
    }

    fun navigateTo(stickyItem: StickyTreeItem, originalX: Int = scrollPane.viewport.viewPosition.x) {
        val row = tree.getRowForPath(stickyItem.treePath).takeIf { it >= 0 } ?: return
        val rowBounds = tree.getRowBounds(row) ?: return
        val targetY = navigationTargetY(stickyItem.treePath, rowBounds, currentViewportRect())
        val previewHeight = previewStickyHeight(targetY, stickyItem.treePath)

        StickyTreeDebugLogger.logNavigation(tree, stickyItem, rowBounds, currentViewportRect(), targetY, previewHeight)
        tree.selectionPath = stickyItem.treePath
        tree.requestFocusInWindow()
        revealedStickyItem = RevealedStickyItem(stickyItem.treePath, targetY)
        scrollPane.viewport.viewPosition = Point(clampViewportX(tree, scrollPane, originalX), targetY)
        forceUpdate()
    }

    private fun handlePropertyChange(event: PropertyChangeEvent) {
        when (event.propertyName) {
            "model" -> {
                (event.oldValue as? TreeModel)?.removeTreeModelListener(treeModelListener)
                val newModel = (event.newValue as? TreeModel) ?: tree.model
                if (newModel == null) {
                    visualStickyItems.clear()
                    stickyPanel.repaintStickyItems()
                    return
                }
                observedModel = newModel
                observedModel.addTreeModelListener(treeModelListener)
                revealedStickyItem = null
                visualStickyItems.clear()
                stickyPanel.repaintStickyItems()
                scheduleRecalculation("modelChanged")
            }

            "rowHeight" -> scheduleRecalculation("rowHeightChanged")
            "cellRenderer", "font" -> repaintPanel(event.propertyName)
        }
    }

    private fun repaintPanel(reason: String) {
        if (disposed || recalculationPending) return
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
        previewItems.recalculate(Rectangle(viewportRect).apply { y = targetY }, revealedPath, maxStickyHeightPercent)
        return previewItems.height()
    }

    private fun maxViewportY(viewportHeight: Int): Int {
        return (maxOf(tree.height, tree.preferredSize.height) - viewportHeight).coerceAtLeast(0)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        recalculationPending = false
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

internal fun clampViewportX(tree: JTree, scrollPane: JScrollPane, originalX: Int): Int {
    val maxX = (tree.width - scrollPane.viewport.extentSize.width).coerceAtLeast(0)
    return originalX.coerceIn(0, maxX)
}

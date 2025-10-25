// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package info.chrzanowski.stickyprojectview

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import java.awt.Rectangle
import java.awt.event.AdjustmentEvent
import java.awt.event.AdjustmentListener
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener

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
) : AdjustmentListener, TreeExpansionListener, Disposable {
    
    private var lastVisibleRect: Rectangle = Rectangle()
    
    init {
        Disposer.register(parentDisposable, this)
        
        // Listen to scroll events
        scrollPane.verticalScrollBar.addAdjustmentListener(this)
        
        // Listen to tree expansion/collapse events
        tree.addTreeExpansionListener(this)
    }
    
    override fun adjustmentValueChanged(e: AdjustmentEvent) {
        // Update on every scroll event, not just when adjustment is done
        recalculateAndRepaint()
    }
    
    override fun treeExpanded(event: TreeExpansionEvent) {
        recalculateAndRepaint()
    }
    
    override fun treeCollapsed(event: TreeExpansionEvent) {
        recalculateAndRepaint()
    }
    
    /**
     * Recalculates sticky items and repaints the panel
     */
    fun recalculateAndRepaint() {
        val visibleRect = tree.visibleRect
        
        // Only recalculate if the visible area has changed
        if (visibleRect != lastVisibleRect) {
            lastVisibleRect = Rectangle(visibleRect)
            visualStickyItems.recalculate(visibleRect)
            stickyPanel.repaintStickyItems()
        }
    }
    
    /**
     * Forces a recalculation and repaint
     */
    fun forceUpdate() {
        lastVisibleRect = Rectangle()
        recalculateAndRepaint()
    }
    
    override fun dispose() {
        scrollPane.verticalScrollBar.removeAdjustmentListener(this)
        tree.removeTreeExpansionListener(this)
    }
}

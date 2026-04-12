// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package info.chrzanowski.stickyprojectview

import com.intellij.ui.components.JBPanel
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.SwingUtilities

/**
 * Panel that displays sticky tree items at the top of the tree viewport.
 * Inspired by StickyLinesPanel for editor implementation.
 */
class StickyTreePanel(
    private val tree: JTree,
    private val visualStickyItems: VisualStickyTreeItems
) : JBPanel<StickyTreePanel>() {
    private var panelWidth: Int = 0
    private var panelHeight: Int = 0

    init {
        layout = null
        isOpaque = true
        background = tree.background
    }
    
    /**
     * Repaints the sticky items based on the current state
     */
    fun repaintStickyItems() {
        val items = visualStickyItems.items()
        val nextWidth = currentViewportWidth()
        val nextHeight = if (items.isEmpty()) 0 else visualStickyItems.height()
        val sizeChanged = nextWidth != panelWidth || nextHeight != panelHeight

        panelWidth = nextWidth
        panelHeight = nextHeight
        isVisible = nextHeight > 0
        setSize(panelWidth, panelHeight)

        if (sizeChanged) {
            revalidate()
        }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        val items = visualStickyItems.items()
        if (items.isEmpty() || width <= 0 || height <= 0) {
            return
        }

        g.color = tree.background
        g.fillRect(0, 0, width, height)

        val defaultRowHeight = tree.rowHeight.coerceAtLeast(1)
        val placements = mutableListOf<String>()
        for (index in items.indices.reversed()) {
            val stickyItem = items[index]
            val rowBounds = tree.getRowBounds(stickyItem.row)
            val rowHeight = rowBounds?.height?.coerceAtLeast(defaultRowHeight) ?: defaultRowHeight
            val yLocation = stickyItem.yLocation
            val visibleTop = maxOf(yLocation, 0)
            val visibleBottom = minOf(yLocation + rowHeight, height)
            if (rowBounds == null || visibleTop >= visibleBottom) {
                placements += "${StickyTreeDebugLogger.pathText(stickyItem.treePath)} skipped rowBounds=${StickyTreeDebugLogger.rect(rowBounds)} visibleTop=$visibleTop visibleBottom=$visibleBottom"
                continue
            }
            if (width <= 0 || rowHeight <= 0 || rowBounds.x >= width) {
                placements += "${StickyTreeDebugLogger.pathText(stickyItem.treePath)} skipped width=$width rowHeight=$rowHeight rowBounds=${StickyTreeDebugLogger.rect(rowBounds)}"
                continue
            }

            placements += "${StickyTreeDebugLogger.pathText(stickyItem.treePath)} row=${stickyItem.row} y=$yLocation visibleTop=$visibleTop visibleBottom=$visibleBottom rowBounds=${StickyTreeDebugLogger.rect(rowBounds)} paintWidth=$width"
            val rowGraphics = g.create(0, visibleTop, width, visibleBottom - visibleTop)
            try {
                rowGraphics.color = tree.background
                rowGraphics.fillRect(0, 0, width, visibleBottom - visibleTop)
                rowGraphics.translate(0, yLocation - visibleTop - rowBounds.y)
                tree.paint(rowGraphics)
            } finally {
                rowGraphics.dispose()
            }
        }
        StickyTreeDebugLogger.logPaint(tree, width, height, items, placements)
    }

    override fun getPreferredSize(): Dimension = Dimension(panelWidth, panelHeight)

    private fun currentViewportWidth(): Int {
        val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, tree) as? JViewport
        return viewport?.extentSize?.width ?: tree.visibleRect.width
    }
}

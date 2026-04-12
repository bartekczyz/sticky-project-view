// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package info.chrzanowski.stickyprojectview

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import java.awt.Color
import java.awt.Dimension
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.JViewport
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * Panel that displays sticky tree items at the top of the tree viewport.
 * Inspired by StickyLinesPanel for editor implementation.
 */
class StickyTreePanel(
    private val tree: JTree,
    private val visualStickyItems: VisualStickyTreeItems,
    private val onItemClick: (StickyTreeItem) -> Unit = {}
) : JBPanel<StickyTreePanel>() {
    private var panelWidth: Int = 0
    private var stickyContentHeight: Int = 0
    private var panelHeight: Int = 0
    private var decoration: StackDecoration = StackDecoration.NONE

    init {
        layout = null
        isOpaque = false
        background = tree.background
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return
                }

                itemAt(event.x, event.y)?.let {
                    event.consume()
                    onItemClick(it)
                }
            }
        })
    }
    
    /**
     * Repaints the sticky items based on the current state
     */
    fun repaintStickyItems() {
        val items = visualStickyItems.items()
        val nextWidth = currentViewportWidth()
        val nextStickyContentHeight = if (items.isEmpty()) 0 else visualStickyItems.height()
        val nextDecoration = decorationMetrics(items, nextStickyContentHeight)
        val nextHeight = if (items.isEmpty()) 0 else nextStickyContentHeight + nextDecoration.totalHeight()
        val sizeChanged = nextWidth != panelWidth || nextHeight != panelHeight

        panelWidth = nextWidth
        stickyContentHeight = nextStickyContentHeight
        panelHeight = nextHeight
        decoration = nextDecoration
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

        val defaultRowHeight = tree.rowHeight.coerceAtLeast(1)
        val placements = mutableListOf<String>()
        for (index in items.indices.reversed()) {
            val stickyItem = items[index]
            val rowBounds = tree.getRowBounds(stickyItem.row)
            val rowHeight = rowBounds?.height?.coerceAtLeast(defaultRowHeight) ?: defaultRowHeight
            val yLocation = stickyItem.yLocation
            val visibleTop = maxOf(yLocation, 0)
            val visibleBottom = minOf(yLocation + rowHeight, stickyContentHeight)
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
        paintSeparatorAndShadow(g)
        StickyTreeDebugLogger.logPaint(tree, width, height, items, placements)
    }

    override fun getPreferredSize(): Dimension = Dimension(panelWidth, panelHeight)

    internal fun stickyContentHeight(): Int = stickyContentHeight

    private fun itemAt(x: Int, y: Int): StickyTreeItem? {
        if (x !in 0 until width || y !in 0 until height) {
            return null
        }

        val defaultRowHeight = tree.rowHeight.coerceAtLeast(1)
        for (stickyItem in visualStickyItems.items()) {
            val rowHeight = tree.getRowBounds(stickyItem.row)?.height?.coerceAtLeast(defaultRowHeight) ?: defaultRowHeight
            val visibleTop = maxOf(stickyItem.yLocation, 0)
            val visibleBottom = minOf(stickyItem.yLocation + rowHeight, stickyContentHeight)
            if (y in visibleTop until visibleBottom) {
                return stickyItem
            }
        }
        return null
    }

    private fun currentViewportWidth(): Int {
        val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, tree) as? JViewport
        return viewport?.extentSize?.width ?: tree.visibleRect.width
    }

    private fun paintSeparatorAndShadow(graphics: Graphics) {
        val g = graphics as? Graphics2D ?: return
        if (width <= 0 || height <= 0 || stickyContentHeight <= 0) {
            return
        }

        val borderY = stickyContentHeight
        val shadowStartY = borderY + BORDER_HEIGHT
        val shadowHeight = decoration.shadowHeight.coerceAtMost(height - shadowStartY)
        if (shadowHeight > 0) {
            val oldPaint = g.paint
            g.paint = GradientPaint(
                0f,
                shadowStartY.toFloat(),
                shadowColor(decoration.shadowStrength),
                0f,
                (shadowStartY + shadowHeight).toFloat(),
                SHADOW_TRANSPARENT_COLOR
            )
            g.fillRect(0, shadowStartY, width, shadowHeight)
            g.paint = oldPaint
        }

        g.color = separatorColor()
        g.fillRect(0, borderY, width, BORDER_HEIGHT)
    }

    private fun decorationMetrics(items: List<StickyTreeItem>, contentHeight: Int): StackDecoration {
        val lastItem = items.lastOrNull() ?: return StackDecoration.NONE
        val defaultRowHeight = tree.rowHeight.coerceAtLeast(1)
        val rowHeight = tree.getRowBounds(lastItem.row)?.height?.coerceAtLeast(defaultRowHeight) ?: defaultRowHeight
        val visibleTop = maxOf(lastItem.yLocation, 0)
        val visibleBottom = minOf(lastItem.yLocation + rowHeight, contentHeight)
        val visibleHeight = (visibleBottom - visibleTop).coerceAtLeast(0)
        val handoffProgress = 1f - visibleHeight.toFloat() / rowHeight.toFloat()
        val shadowStrength = BASE_SHADOW_STRENGTH + (1f - BASE_SHADOW_STRENGTH) * handoffProgress
        val shadowHeight = (rowHeight * (MIN_SHADOW_FACTOR + (MAX_SHADOW_FACTOR - MIN_SHADOW_FACTOR) * shadowStrength))
            .roundToInt()
            .coerceAtLeast(MIN_SHADOW_HEIGHT)
        return StackDecoration(shadowHeight, shadowStrength)
    }

    private fun separatorColor(): Color {
        return ColorUtil.toAlpha(JBColor.namedColor("Separator.separatorColor", JBColor.border()), SEPARATOR_ALPHA)
    }

    private fun shadowColor(strength: Float): Color {
        val alpha = if (JBColor.isBright()) {
            (LIGHT_SHADOW_ALPHA_MIN + (LIGHT_SHADOW_ALPHA_MAX - LIGHT_SHADOW_ALPHA_MIN) * strength).roundToInt()
        } else {
            (DARK_SHADOW_ALPHA_MIN + (DARK_SHADOW_ALPHA_MAX - DARK_SHADOW_ALPHA_MIN) * strength).roundToInt()
        }
        return Color(0, 0, 0, alpha)
    }

    private companion object {
        private val SHADOW_TRANSPARENT_COLOR = Color(0, 0, 0, 0)
        private const val BORDER_HEIGHT = 1
        private const val SEPARATOR_ALPHA = 70
        private const val BASE_SHADOW_STRENGTH = 0.45f
        private const val MIN_SHADOW_FACTOR = 0.16f
        private const val MAX_SHADOW_FACTOR = 0.34f
        private const val MIN_SHADOW_HEIGHT = 5
        private const val LIGHT_SHADOW_ALPHA_MIN = 12f
        private const val LIGHT_SHADOW_ALPHA_MAX = 30f
        private const val DARK_SHADOW_ALPHA_MIN = 28f
        private const val DARK_SHADOW_ALPHA_MAX = 72f
    }

    private data class StackDecoration(
        val shadowHeight: Int,
        val shadowStrength: Float
    ) {
        fun totalHeight(): Int = BORDER_HEIGHT + shadowHeight

        companion object {
            val NONE = StackDecoration(0, 0f)
        }
    }
}

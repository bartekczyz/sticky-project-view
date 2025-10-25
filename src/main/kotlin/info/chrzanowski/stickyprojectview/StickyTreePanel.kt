// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package info.chrzanowski.stickyprojectview

import com.intellij.ui.components.JBPanel
import java.awt.Component
import java.awt.Graphics
import javax.swing.JTree

/**
 * Panel that displays sticky tree items at the top of the tree viewport.
 * Inspired by StickyLinesPanel for editor implementation.
 */
class StickyTreePanel(
    private val tree: JTree,
    private val visualStickyItems: VisualStickyTreeItems
) : JBPanel<StickyTreePanel>() {

    private data class RenderedItem(val component: Component, val yPosition: Int, val height: Int)

    private val renderedComponents = mutableListOf<RenderedItem>()
    
    init {
        layout = null // Use absolute positioning
        isOpaque = true
        background = tree.background
    }
    
    /**
     * Repaints the sticky items based on current state
     */
    fun repaintStickyItems() {
        val items = visualStickyItems.items()

        if (items.isEmpty()) {
            isVisible = false
            setSize(0, 0)
            revalidate()
            repaint()
            return
        }

        val rowHeight = tree.rowHeight.coerceAtLeast(20)
        val panelWidth = tree.width
        val panelHeight = visualStickyItems.height()

        renderedComponents.clear()
        items.forEachIndexed { index, _ ->
            val yPosition = index * rowHeight
            renderedComponents.add(RenderedItem(tree, yPosition, rowHeight))
        }

        setSize(panelWidth, panelHeight)
        isVisible = true
        revalidate()
        repaint()
    }
    
    /**
     * Renders a tree node component for the sticky item
     */
    private fun renderTreeNode(item: StickyTreeItem): Component? {
        val renderer = tree.cellRenderer ?: return null
        val lastPathComponent = item.treePath.lastPathComponent

        // Use the actual row number to preserve tree indentation
        return renderer.getTreeCellRendererComponent(
            tree,
            lastPathComponent,
            false,
            tree.isExpanded(item.treePath),
            tree.model.isLeaf(lastPathComponent),
            item.row,
            false
        )
    }
    
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        val items = visualStickyItems.items()

        renderedComponents.forEachIndexed { index, renderedItem ->
            if (index >= items.size) return@forEachIndexed

            val stickyItem = items[index]
            val component = renderTreeNode(stickyItem) ?: return@forEachIndexed

            // Calculate indentation based on depth
            // IntelliJ trees typically use depth * indent per level
            val indent = stickyItem.depth * getIndentPerLevel()

            component.setBounds(0, 0, width - indent, renderedItem.height)
            component.setSize(width - indent, renderedItem.height)

            val graphics = g.create() as Graphics
            graphics.translate(indent, renderedItem.yPosition)
            graphics.clipRect(0, 0, width - indent, renderedItem.height)
            component.paint(graphics)
            graphics.dispose()
        }

        if (renderedComponents.isNotEmpty()) {
            val oldColor = g.color
            g.color = tree.background.darker()
            g.drawLine(0, height - 1, width, height - 1)
            g.color = oldColor
        }
    }

    /**
     * Gets the indent per level from the tree UI, including space for expand/collapse icon
     */
    private fun getIndentPerLevel(): Int {
        // Try to get the indent from the tree UI
        val ui = tree.ui
        if (ui is javax.swing.plaf.basic.BasicTreeUI) {
            return try {
                val field = javax.swing.plaf.basic.BasicTreeUI::class.java.getDeclaredField("leftChildIndent")
                field.isAccessible = true
                val leftIndent = field.getInt(ui)

                val rightField = javax.swing.plaf.basic.BasicTreeUI::class.java.getDeclaredField("rightChildIndent")
                rightField.isAccessible = true
                val rightIndent = rightField.getInt(ui)

                // Add space for the expand/collapse icon
                leftIndent + rightIndent + 6
            } catch (e: Exception) {
                26 // Default fallback (20 for indent + 6 for icon)
            }
        }
        return 26 // Default fallback (20 for indent + 6 for icon)
    }
}

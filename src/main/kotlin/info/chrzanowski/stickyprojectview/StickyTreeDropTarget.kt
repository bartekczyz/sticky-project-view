package info.chrzanowski.stickyprojectview

import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDManager
import com.intellij.ide.dnd.DnDNativeTarget
import com.intellij.ide.dnd.DnDTarget
import com.intellij.openapi.Disposable
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.awt.RelativeRectangle
import java.awt.Component
import java.awt.Point
import javax.swing.JLayeredPane
import javax.swing.JTree
import javax.swing.SwingUtilities

internal class StickyTreeDropTarget(
    private val tree: JTree,
    private val panel: StickyTreePanel,
) : DnDNativeTarget, Disposable {
    private val manager = DnDManager.getInstance()
    private var disposed = false

    init {
        manager.registerTarget(this, panel)
    }

    override fun update(event: DnDEvent): Boolean {
        panel.setDropHighlight(null)
        event.setDropPossible(false, "")
        val target = target()
        val mapped = mappedEvent(event)
        if (target == null || mapped == null) {
            event.hideHighlighter()
            return false
        }
        return try {
            target.update(mapped)
        } finally {
            if (!event.isDropPossible) panel.setDropHighlight(null)
        }
    }

    override fun drop(event: DnDEvent) {
        try {
            val target = target() ?: return
            val mapped = mappedEvent(event) ?: return
            target.drop(mapped)
        } finally {
            panel.setDropHighlight(null)
        }
    }

    override fun cleanUpOnLeave() {
        try {
            target()?.cleanUpOnLeave()
        } finally {
            panel.setDropHighlight(null)
        }
    }

    // DnDManager exposes registration but no target getter; resolve its client property afresh for pane changes.
    private fun target(): DnDTarget? =
        if (disposed) null else (tree.getClientProperty("DnD Target") as? DnDTarget)?.takeUnless { it === this }

    private fun mappedEvent(event: DnDEvent): DnDEvent? {
        if (disposed) return null
        val panelPoint = event.getPointOn(panel)
        if (!panel.contains(panelPoint)) return null
        val point = SwingUtilities.convertPoint(panel, panelPoint, tree)
        val item = panel.itemAt(panelPoint.x, panelPoint.y)
        if (item != null) {
            val bounds = tree.getPathBounds(item.treePath) ?: return null
            point.y = bounds.y + panelPoint.y - item.yLocation
        } else if (panelPoint.y < panel.stickyContentHeight()) {
            return null
        }
        return object : DnDEvent by event {
            override fun getPoint(): Point = Point(point)
            override fun getPointOn(component: Component): Point = SwingUtilities.convertPoint(tree, point, component)
            override fun getHandlerComponent(): Component = tree
            override fun getCurrentOverComponent(): Component = tree
            override fun getRelativePoint(): RelativePoint = RelativePoint(tree, point)

            override fun setHighlighting(rectangle: RelativeRectangle, type: Int) {
                if (!highlightStickyRow(rectangle, type)) {
                    event.setHighlighting(highlight(rectangle), type)
                }
            }

            override fun setHighlighting(layeredPane: JLayeredPane, rectangle: RelativeRectangle, type: Int) {
                if (!highlightStickyRow(rectangle, type)) {
                    event.setHighlighting(layeredPane, highlight(rectangle), type)
                }
            }

            override fun hideHighlighter() {
                panel.setDropHighlight(null)
                event.hideHighlighter()
            }

            private fun highlightStickyRow(rectangle: RelativeRectangle, type: Int): Boolean {
                // RelativePoint normalizes showing components to their root pane; retain the tree's identity.
                if (item == null || rectangle.point.originalComponent !== tree || type != DnDEvent.DropTargetHighlightingType.RECTANGLE) {
                    return false
                }
                // The native highlighter is a separate layered-pane component, not part of tree.paint().
                event.hideHighlighter()
                panel.setDropHighlight(item.treePath)
                return true
            }

            private fun highlight(rectangle: RelativeRectangle): RelativeRectangle {
                if (item == null || rectangle.point.originalComponent !== tree) return rectangle
                val bounds = rectangle.getRectangleOn(tree)
                bounds.translate(panelPoint.x - point.x, panelPoint.y - point.y)
                val visible = panel.visibleItemBounds(item) ?: return rectangle
                return RelativeRectangle(panel, bounds.intersection(visible))
            }
        }
    }

    override fun dispose() {
        if (disposed) return
        cleanUpOnLeave()
        disposed = true
        manager.unregisterTarget(this, panel)
    }
}
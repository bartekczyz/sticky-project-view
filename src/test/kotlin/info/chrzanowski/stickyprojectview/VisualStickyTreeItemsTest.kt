package info.chrzanowski.stickyprojectview

import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Color
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

class VisualStickyTreeItemsTest {

    @Test
    fun `sticky rows keep full height while their subtree is still visible`() {
        runOnEdt {
            val tree = createTree(
                treeNode("root",
                    treeNode("parent",
                        treeNode("child",
                            treeNode("leaf")
                        )
                    )
                )
            )
            val stickyItems = VisualStickyTreeItems(tree)

            stickyItems.recalculate(Rectangle(0, 1, 200, 120))

            assertEquals(listOf("parent", "child"), stickyItems.items().map(::labelOf))
            assertEquals(listOf(0, 20), stickyItems.items().map(StickyTreeItem::yLocation))
            assertEquals(40, stickyItems.height())
        }
    }

    @Test
    fun `sticky row slides out when the subtree ends`() {
        runOnEdt {
            val tree = createTree(
                treeNode("root",
                    treeNode("parent",
                        treeNode("leaf")
                    ),
                    treeNode("sibling")
                )
            )
            val stickyItems = VisualStickyTreeItems(tree)

            stickyItems.recalculate(Rectangle(0, 21, 200, 120))

            assertEquals(listOf("parent"), stickyItems.items().map(::labelOf))
            assertEquals(listOf(-1), stickyItems.items().map(StickyTreeItem::yLocation))
            assertEquals(19, stickyItems.height())
        }
    }

    @Test
    fun `manager recalculates after collapse without scrolling`() {
        runOnEdt {
            val tree = createTree(
                treeNode("root",
                    treeNode("parent",
                        treeNode("leaf")
                    ),
                    treeNode("sibling")
                )
            )
            val stickyItems = VisualStickyTreeItems(tree)
            val scrollPane = JScrollPane(tree).apply {
                setSize(200, 100)
                doLayout()
                viewport.viewPosition = java.awt.Point(0, 21)
            }
            val stickyPanel = StickyTreePanel(tree, stickyItems)
            val manager = StickyTreeManager(tree, scrollPane, stickyItems, stickyPanel, DisposableStub())

            manager.forceUpdate()
            assertEquals(19, stickyPanel.preferredSize.height)

            tree.collapseRow(0)

            assertEquals(0, stickyPanel.preferredSize.height)
            manager.dispose()
        }
    }

    @Test
    fun `folding child paints under its parent`() {
        runOnEdt {
            val parentColor = Color(0xCC, 0x44, 0x44)
            val childColor = Color(0x44, 0x66, 0xCC)
            val tree = StripeTree(
                treeNode("root",
                    treeNode("parent",
                        treeNode("child",
                            treeNode("leaf")
                        ),
                        treeNode("sibling")
                    )
                ),
                mapOf(
                    "parent" to parentColor,
                    "child" to childColor
                )
            ).applyTreeDefaults()
            val stickyItems = VisualStickyTreeItems(tree)
            stickyItems.recalculate(Rectangle(0, 21, 200, 120))

            assertEquals(listOf("parent", "child"), stickyItems.items().map(::labelOf))
            assertEquals(listOf(0, 19), stickyItems.items().map(StickyTreeItem::yLocation))

            val stickyPanel = StickyTreePanel(tree, stickyItems)
            stickyPanel.repaintStickyItems()
            stickyPanel.setSize(stickyPanel.preferredSize)

            val image = BufferedImage(stickyPanel.width, stickyPanel.height, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try {
                stickyPanel.paint(graphics)
            } finally {
                graphics.dispose()
            }

            val sampleX = tree.getRowBounds(1).x + 5
            assertEquals(parentColor.rgb, image.getRGB(sampleX, 19))
            assertEquals(childColor.rgb, image.getRGB(sampleX, 20))
        }
    }

    @Test
    fun `sticky panel preserves tree chrome painted outside renderer`() {
        runOnEdt {
            val markerColor = Color(0x2F, 0x9E, 0x44)
            val tree = MarkerTree(
                treeNode("root",
                    treeNode("parent",
                        treeNode("leaf")
                    ),
                    treeNode("sibling")
                ),
                markerColor
            ).applyTreeDefaults()
            val stickyItems = VisualStickyTreeItems(tree)
            stickyItems.recalculate(Rectangle(0, 21, 200, 120))

            val stickyPanel = StickyTreePanel(tree, stickyItems)
            stickyPanel.repaintStickyItems()
            stickyPanel.setSize(stickyPanel.preferredSize)

            val image = BufferedImage(stickyPanel.width, stickyPanel.height, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try {
                stickyPanel.paint(graphics)
            } finally {
                graphics.dispose()
            }

            assertEquals(markerColor.rgb, image.getRGB(6, 6))
        }
    }

    @Test
    fun `clicking sticky item reveals it below its parents`() {
        runOnEdt {
            val tree = createTree(
                treeNode("root",
                    treeNode("parent",
                        treeNode("child",
                            treeNode("leaf")
                        ),
                        treeNode("sibling")
                    )
                )
            )
            val stickyItems = VisualStickyTreeItems(tree)
            val scrollPane = JScrollPane(tree).apply {
                setSize(200, 140)
                doLayout()
                viewport.viewPosition = Point(0, 21)
            }
            lateinit var manager: StickyTreeManager
            val stickyPanel = StickyTreePanel(tree, stickyItems) { manager.navigateTo(it) }
            manager = StickyTreeManager(tree, scrollPane, stickyItems, stickyPanel, DisposableStub())

            manager.forceUpdate()
            stickyPanel.setSize(200, stickyItems.height())
            assertEquals(listOf("parent", "child"), stickyItems.items().map(::labelOf))

            stickyPanel.dispatchEvent(
                MouseEvent(
                    stickyPanel,
                    MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(),
                    0,
                    50,
                    25,
                    1,
                    false,
                    MouseEvent.BUTTON1
                )
            )

            assertEquals("child", tree.selectionPath.lastPathComponent.toString())
            assertEquals(listOf("parent"), stickyItems.items().map(::labelOf))
            val childBounds = tree.getRowBounds(tree.getRowForPath(tree.selectionPath))
            assertEquals(stickyPanel.preferredSize.height, childBounds.y - scrollPane.viewport.viewPosition.y)

            manager.dispose()
        }
    }

    @Test
    fun `navigation target offsets clicked item by remaining sticky height`() {
        runOnEdt {
            val tree = createTree(
                treeNode("root",
                    treeNode("before-0"),
                    treeNode("before-1"),
                    treeNode("before-2"),
                    treeNode("before-3"),
                    treeNode("before-4"),
                    treeNode("parent",
                        treeNode("child",
                            treeNode("leaf")
                        ),
                        treeNode("sibling")
                    )
                )
            )
            expandAll(tree)
            tree.setSize(200, 400)
            val stickyItems = VisualStickyTreeItems(tree)
            val viewportRect = Rectangle(0, tree.getRowBounds(tree.rowCount - 3).y + 1, 200, 140)
            stickyItems.recalculate(viewportRect)
            assertEquals(listOf("parent", "child"), stickyItems.items().map(::labelOf))

            val scrollPane = JScrollPane(tree).apply {
                setSize(200, 140)
                doLayout()
            }
            val manager = StickyTreeManager(tree, scrollPane, stickyItems, StickyTreePanel(tree, stickyItems), DisposableStub())
            val childStickyItem = stickyItems.items().last()
            val childBounds = tree.getRowBounds(childStickyItem.row)

            assertEquals(childBounds.y - 20, manager.navigationTargetY(childStickyItem, viewportRect))

            manager.dispose()
        }
    }

    private fun createTree(root: DefaultMutableTreeNode): JTree {
        return JTree(root).applyTreeDefaults()
    }

    private fun treeNode(name: String, vararg children: DefaultMutableTreeNode): DefaultMutableTreeNode {
        return DefaultMutableTreeNode(name).apply {
            children.forEach(::add)
        }
    }

    private fun labelOf(item: StickyTreeItem): String {
        return item.treePath.lastPathComponent.toString()
    }

    private fun runOnEdt(block: () -> Unit) {
        SwingUtilities.invokeAndWait(block)
    }

    private fun expandAll(tree: JTree) {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    private class DisposableStub : com.intellij.openapi.Disposable {
        override fun dispose() = Unit
    }

    private class MarkerTree(
        root: DefaultMutableTreeNode,
        private val markerColor: Color
    ) : JTree(root) {
        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)

            val rowBounds = getRowBounds(0) ?: return
            graphics.color = markerColor
            graphics.fillRect(0, rowBounds.y, 12, rowBounds.height)
        }
    }

    private class StripeTree(
        root: DefaultMutableTreeNode,
        private val rowColors: Map<String, Color>
    ) : JTree(root) {
        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)

            for (row in 0 until rowCount) {
                val rowBounds = getRowBounds(row) ?: continue
                val path = getPathForRow(row) ?: continue
                val color = rowColors[path.lastPathComponent.toString()] ?: continue
                graphics.color = color
                graphics.fillRect(0, rowBounds.y, width, rowBounds.height)
            }
        }
    }

    private fun <T : JTree> T.applyTreeDefaults(): T {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = 20
        setSize(200, 200)
        expandRow(0)
        expandRow(1)
        return this
    }
}

package info.chrzanowski.stickyprojectview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `height percentage retains row margin across tiny odd and resized viewports`() {
        runOnEdt {
            val root = treeNode("root")
            var node = root
            repeat(8) { index ->
                val child = treeNode("directory$index")
                node.add(child)
                node = child
            }
            repeat(40) { node.add(treeNode("file$it")) }
            val tree = JTree(root).applyTreeDefaults()
            var row = 0
            while (row < tree.rowCount) tree.expandRow(row++)
            val items = VisualStickyTreeItems(tree)
            val rect = Rectangle(0, 220, 200, 201)
            items.recalculate(rect)
            val defaultItems = items.items().toList()
            items.recalculate(rect, maxStickyHeightPercent = 50)
            assertEquals(defaultItems, items.items())
            assertEquals(80, items.height())
            items.recalculate(rect, maxStickyHeightPercent = 10)
            assertEquals(0, items.height())
            items.recalculate(rect, maxStickyHeightPercent = -1)
            assertEquals(0, items.height())
            items.recalculate(rect, maxStickyHeightPercent = 90)
            assertEquals(160, items.height())
            items.recalculate(rect, maxStickyHeightPercent = 100)
            assertEquals(160, items.height())
            items.recalculate(Rectangle(rect).apply { height = 39 }, maxStickyHeightPercent = 90)
            assertEquals(0, items.height())
            items.recalculate(Rectangle(rect).apply { height = 101 }, maxStickyHeightPercent = 90)
            assertEquals(60, items.height())

            val pane = JScrollPane(tree)
            pane.viewport.extentSize = java.awt.Dimension(200, 201)
            pane.viewport.viewPosition = Point(0, 220)
            val panel = StickyTreePanel(tree, items)
            val manager = StickyTreeManager(tree, pane, items, panel, DisposableStub())
            try {
                manager.forceUpdate()
                assertEquals(80, panel.stickyContentHeight())
                manager.setMaxStickyHeightPercent(90)
                assertEquals(220, pane.viewport.viewPosition.y)
                assertEquals(160, panel.stickyContentHeight())
                val target = items.items().last()
                manager.navigateTo(target)
                assertEquals(tree.getPathBounds(target.treePath).y, pane.viewport.viewPosition.y + items.height())
                val preview = VisualStickyTreeItems(tree)
                preview.recalculate(pane.viewport.viewRect, target.treePath, 90)
                assertEquals(preview.items(), items.items())
                assertEquals(preview.height(), panel.stickyContentHeight())
            } finally {
                manager.dispose()
            }
        }
    }

    @Test
    fun `scrolled sticky painting matches native row even beyond unscrolled viewport width`() {
        runOnEdt {
            val marker = Color.MAGENTA
            val tree = object : JTree(treeNode("root", treeNode("parent", treeNode("leaf")))) {
                override fun paintComponent(graphics: Graphics) {
                    super.paintComponent(graphics)
                    val bounds = getRowBounds(0)
                    graphics.color = marker
                    graphics.fillRect(bounds.x, bounds.y, 8, bounds.height)
                }
            }.applyTreeDefaults()
            val scrollPane = JScrollPane(tree)
            scrollPane.viewport.extentSize = java.awt.Dimension(15, 100)
            tree.setSize(400, 200)
            val bounds = tree.getRowBounds(0)
            org.junit.Assert.assertTrue(bounds.x >= 15)
            scrollPane.viewport.viewPosition = Point(bounds.x - 3, 1)
            val items = VisualStickyTreeItems(tree)
            items.recalculate(scrollPane.viewport.viewRect)
            val panel = StickyTreePanel(tree, items)
            panel.repaintStickyItems()
            val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            try {
                panel.paint(graphics)
            } finally {
                graphics.dispose()
            }
            assertEquals(marker.rgb, image.getRGB(4, 6))
        }
    }

    @Test
    fun `reveal preserves horizontal position before selection handlers and clamps it`() {
        runOnEdt {
            for (originalX in listOf(45, 350)) {
                val tree = createTree(treeNode("root", treeNode("parent", treeNode("child", treeNode("leaf")), treeNode("sibling"))))
                val scrollPane = JScrollPane(tree)
                scrollPane.viewport.extentSize = java.awt.Dimension(100, 140)
                scrollPane.viewport.viewSize = java.awt.Dimension(400, 200)
                scrollPane.viewport.viewPosition = Point(originalX, 21)
                val items = VisualStickyTreeItems(tree)
                val panel = StickyTreePanel(tree, items)
                val manager = StickyTreeManager(tree, scrollPane, items, panel, DisposableStub())
                try {
                    manager.forceUpdate()
                    val child = items.items().last()
                    tree.addTreeSelectionListener { scrollPane.viewport.viewPosition = Point(0, 40) }
                    manager.navigateTo(child)
                    assertEquals(originalX.coerceAtMost(300), scrollPane.viewport.viewPosition.x)
                    assertEquals(listOf("parent"), items.items().map(::labelOf))
                } finally {
                    manager.dispose()
                }
            }
        }
    }

    @Test
    fun `selection clears revealed exception and recalculates at unchanged viewport`() {
        runOnEdt {
            val tree = createTree(treeNode("root", treeNode("parent", treeNode("child", treeNode("leaf")), treeNode("sibling"))))
            val scrollPane = JScrollPane(tree)
            scrollPane.viewport.extentSize = java.awt.Dimension(200, 140)
            val items = VisualStickyTreeItems(tree)
            val panel = StickyTreePanel(tree, items)
            val manager = StickyTreeManager(tree, scrollPane, items, panel, DisposableStub())
            try {
                scrollPane.viewport.viewPosition = Point(0, 21)
                manager.forceUpdate()
                manager.navigateTo(items.items().last())
                assertEquals(listOf("parent"), items.items().map(::labelOf))
                val position = Point(scrollPane.viewport.viewPosition)
                tree.selectionPath = tree.getPathForRow(2)
                assertEquals(position, scrollPane.viewport.viewPosition)
                val expected = VisualStickyTreeItems(tree).apply { recalculate(scrollPane.viewport.viewRect) }
                assertEquals(emptyList<String>(), expected.items().map(::labelOf))
                assertEquals(expected.items(), items.items())
                assertEquals(expected.height(), panel.stickyContentHeight())
            } finally {
                manager.dispose()
            }
        }
    }

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

            for (percent in listOf(10, 50, 90)) {
                stickyItems.recalculate(Rectangle(0, 21, 200, 401), maxStickyHeightPercent = percent)
                assertEquals(listOf("parent"), stickyItems.items().map(::labelOf))
                assertEquals(listOf(-1), stickyItems.items().map(StickyTreeItem::yLocation))
                assertEquals(19, stickyItems.height())
            }
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
            assertEquals(19, stickyPanel.stickyContentHeight())
            assertNotEquals(stickyPanel.stickyContentHeight(), stickyPanel.preferredSize.height)

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
    fun `sticky panel paints a separator and shadow at the bottom edge`() {
        runOnEdt {
            val rowColor = Color(0x7A, 0xC7, 0xFF)
            val tree = StripeTree(
                treeNode("root",
                    treeNode("parent",
                        treeNode("leaf")
                    ),
                    treeNode("sibling")
                ),
                mapOf("parent" to rowColor)
            ).applyTreeDefaults()
            val stickyItems = VisualStickyTreeItems(tree)
            stickyItems.recalculate(Rectangle(0, 1, 200, 120))

            val stickyPanel = StickyTreePanel(tree, stickyItems)
            stickyPanel.repaintStickyItems()
            stickyPanel.setSize(stickyPanel.preferredSize)

            val image = BufferedImage(stickyPanel.width, stickyPanel.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                stickyPanel.paint(graphics)
            } finally {
                graphics.dispose()
            }

            val sampleX = stickyPanel.width / 2
            val borderY = stickyPanel.stickyContentHeight()
            assertNotEquals(tree.background.rgb, image.getRGB(sampleX, borderY))
            assertNotEquals(image.getRGB(sampleX, borderY), image.getRGB(sampleX, borderY + 1))
            val shadowAlpha = image.getRGB(sampleX, borderY + 1).ushr(24) and 0xFF
            assertNotEquals(0, shadowAlpha)
            assertNotEquals(255, shadowAlpha)
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
            val stickyPanel = StickyTreePanel(tree, stickyItems)
            manager = StickyTreeManager(tree, scrollPane, stickyItems, stickyPanel, DisposableStub())
            val router = StickyTreeInputRouter(tree, stickyPanel) { item, x -> manager.navigateTo(item, x) }

            manager.forceUpdate()
            stickyPanel.setSize(200, stickyItems.height())
            assertEquals(listOf("parent", "child"), stickyItems.items().map(::labelOf))

            var clicks = 0
            val gesturePanel = StickyTreePanel(tree, stickyItems)
            val gestureRouter = StickyTreeInputRouter(tree, gesturePanel) { _, _ -> clicks++ }
            gesturePanel.repaintStickyItems()
            gesturePanel.dispatchEvent(MouseEvent(gesturePanel, MouseEvent.MOUSE_PRESSED, 1, 0, 50, 25, 1, false, MouseEvent.BUTTON1))
            assertEquals("press must not reveal before a gesture completes", 0, clicks)
            gestureRouter.dispose()

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

            stickyPanel.dispatchEvent(MouseEvent(stickyPanel, MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), 0, 50, 25, 1, false, MouseEvent.BUTTON1))
            val loop = java.awt.Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
            val interval = (java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int) ?: 500
            javax.swing.Timer(interval + 200) { loop.exit() }.apply { isRepeats = false; start() }
            loop.enter()
            assertEquals("child", tree.selectionPath.lastPathComponent.toString())
            assertEquals(listOf("parent"), stickyItems.items().map(::labelOf))
            val childBounds = tree.getRowBounds(tree.getRowForPath(tree.selectionPath))
            assertEquals(stickyPanel.stickyContentHeight(), childBounds.y - scrollPane.viewport.viewPosition.y)

            router.dispose()
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

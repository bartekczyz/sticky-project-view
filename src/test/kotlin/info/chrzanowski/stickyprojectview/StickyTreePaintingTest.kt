package info.chrzanowski.stickyprojectview

import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.tree.ui.DefaultTreeUI
import com.intellij.ui.treeStructure.Tree
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.image.BufferedImage
import javax.swing.JScrollPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class StickyTreePaintingTest : BasePlatformTestCase() {

    fun testSelectedSourceDirectoryKeepsFileColorWhenSticky() {
        assertStickyFileColor(Color(0x20, 0x40, 0x20), selected = true)
    }

    fun testSelectedExcludedDirectoryKeepsFileColorWhenSticky() {
        assertStickyFileColor(Color(0x40, 0x20, 0x20), selected = true)
    }

    fun testUnselectedDirectoryKeepsFileColorWhenSticky() {
        assertStickyFileColor(Color(0x20, 0x40, 0x20), selected = false)
    }

    fun testSelectedDirectoryWithoutFileColorKeepsNativeAppearance() {
        assertStickyFileColor(null, selected = true)
    }

    fun testDisabledFileColorsKeepNativeAppearance() {
        assertStickyFileColor(Color(0x20, 0x40, 0x20), selected = true, fileColorsEnabled = false)
    }

    private fun assertStickyFileColor(fileColor: Color?, selected: Boolean, fileColorsEnabled: Boolean = true) {
        Registry.get("ide.experimental.ui").setValue(true, testRootDisposable)
        Registry.get("ide.experimental.ui.tree.selection").setValue(true, testRootDisposable)
        Registry.get("ide.file.colors.at.left").setValue(false, testRootDisposable)
        assertTrue("The test requires inset tree selection", ExperimentalUI.isNewUI())
        val parent = DefaultMutableTreeNode("parent").apply {
            add(DefaultMutableTreeNode("child").apply {
                repeat(20) { add(DefaultMutableTreeNode("file$it")) }
            })
        }
        val root = DefaultMutableTreeNode("root").apply { add(parent) }
        val tree = object : Tree(DefaultTreeModel(root)) {
            override fun isFileColorsEnabled(): Boolean = fileColorsEnabled

            override fun getFileColorFor(value: Any): Color? =
                if (value == parent.userObject) fileColor else null
        }.apply {
            setUI(DefaultTreeUI())
            isOpaque = false
            isRootVisible = false
            showsRootHandles = true
            rowHeight = 20
            expandRow(0)
            expandRow(1)
            setSize(200, preferredSize.height)
            if (selected) setSelectionRow(0)
        }
        val pane = JScrollPane(tree).apply {
            viewport.extentSize = Dimension(200, 120)
        }
        val nativeImage = BufferedImage(tree.width, tree.height, BufferedImage.TYPE_INT_RGB)
        nativeImage.createGraphics().let { graphics ->
            try {
                graphics.color = tree.background
                graphics.fillRect(0, 0, nativeImage.width, nativeImage.height)
                tree.paint(graphics)
            } finally {
                graphics.dispose()
            }
        }
        val expectedBackground = if (fileColorsEnabled) fileColor ?: tree.background else tree.background
        assertEquals("The native row must have the expected background", expectedBackground.rgb, nativeImage.getRGB(tree.width - 1, 10))
        val items = VisualStickyTreeItems(tree)
        val panel = StickyTreePanel(tree, items)

        for (viewportY in listOf(1, 21, 41)) {
            pane.viewport.viewPosition = Point(0, viewportY)
            items.recalculate(pane.viewport.viewRect)
            assertEquals(listOf("parent", "child"), items.items().map { it.treePath.lastPathComponent.toString() })
            panel.repaintStickyItems()
            val stickyImage = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_RGB)
            stickyImage.createGraphics().let { graphics ->
                try {
                    panel.paint(graphics)
                } finally {
                    graphics.dispose()
                }
            }

            for (x in listOf(0, tree.width / 2, tree.width - 1)) {
                assertEquals("Sticky directory color at x=$x, scroll=$viewportY", nativeImage.getRGB(x, 10), stickyImage.getRGB(x, 10))
            }
            assertEquals("The child must not inherit the parent's tint", nativeImage.getRGB(tree.width - 1, 30), stickyImage.getRGB(tree.width - 1, 30))
        }
    }
}
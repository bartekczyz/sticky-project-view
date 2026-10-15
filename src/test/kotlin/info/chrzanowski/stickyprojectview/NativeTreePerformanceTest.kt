package info.chrzanowski.stickyprojectview

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.tree.ui.DefaultTreeUI
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Dimension
import java.awt.Point
import javax.swing.JScrollPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import kotlin.random.Random

class NativeTreePerformanceTest : BasePlatformTestCase() {

    fun testExpandedDirectoryRestorationKeepsEdtResponsive() {
        val root = DefaultMutableTreeNode("root")
        val parent = DefaultMutableTreeNode("parent")
        root.add(parent)
        repeat(5_000) { index ->
            parent.add(DefaultMutableTreeNode("directory$index").apply {
                add(DefaultMutableTreeNode("file.txt"))
            })
        }
        val model = DefaultTreeModel(root)
        val tree = Tree(model).apply {
            setUI(DefaultTreeUI())
            isRootVisible = false
            rowHeight = 20
            setSize(400, 200)
        }
        val pane = JScrollPane(tree).apply {
            viewport.extentSize = Dimension(400, 200)
        }
        val controller = ProjectViewStickyController(project)
        try {
            controller.initialize()
            for (enabled in listOf(false, true)) {
                controller.applyPreferences(StickyProjectViewPreferences(enabled, 50))
                if (enabled) controller.attachToTree(tree, pane)
                assertEquals(enabled, controller.isAttached())
                tree.collapseRow(0)
                val started = System.nanoTime()
                TreeUtil.expandAll(tree)
                UIUtil.dispatchAllInvocationEvents()
                val elapsedMillis = (System.nanoTime() - started) / 1_000_000
                assertInstanceOf(tree.ui, DefaultTreeUI::class.java)
                assertEquals(10_001, tree.rowCount)
                println("Native directory expansion: overlay=$enabled, ui=${tree.ui.javaClass.name}, rows=${tree.rowCount}, elapsedMs=$elapsedMillis")
                assertTrue("Native expansion blocked the EDT for $elapsedMillis ms (overlay=$enabled)", elapsedMillis < 2_000)

                pane.viewport.viewPosition = Point(0, 2_000)
                val expandedPaths = tree.expandedPaths.toList().shuffled(Random(0))
                val insertionStarted = System.nanoTime()
                val addedFiles = List(200) { index ->
                    DefaultMutableTreeNode("added$index.txt").also { node ->
                        val directory = parent.getChildAt(4_999) as DefaultMutableTreeNode
                        model.insertNodeInto(node, directory, directory.childCount)
                        tree.expandPaths(expandedPaths)
                    }
                }
                UIUtil.dispatchAllInvocationEvents()
                val insertionMillis = (System.nanoTime() - insertionStarted) / 1_000_000
                assertEquals(10_001 + addedFiles.size, tree.rowCount)
                println("Native expanded directory insertion: overlay=$enabled, elapsedMs=$insertionMillis")
                assertTrue("Native insertion blocked the EDT for $insertionMillis ms (overlay=$enabled)", insertionMillis < 2_000)
                addedFiles.forEach(model::removeNodeFromParent)
            }
        } finally {
            Disposer.dispose(controller)
        }
    }
}
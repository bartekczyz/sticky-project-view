package info.chrzanowski.stickyprojectview

import com.intellij.openapi.util.Disposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class StickyTreeManagerTest {

    @Test
    fun `model update bursts do not repeatedly query tree layout`() {
        val baseline = onEdt { Fixture(attachManager = false) }
        val fixture = onEdt { Fixture() }
        try {
            onEdt {
                baseline.tree.rowLookups = 0
                fixture.tree.rowLookups = 0
                repeat(200) { index ->
                    baseline.model.insertNodeInto(DefaultMutableTreeNode("added$index"), baseline.parent, baseline.parent.childCount)
                    fixture.model.insertNodeInto(DefaultMutableTreeNode("added$index"), fixture.parent, fixture.parent.childCount)
                }
            }
            onEdt {
                assertEquals(listOf("parent"), fixture.items.items().map { it.treePath.lastPathComponent.toString() })
                assertTrue(
                    "200 insertions used ${fixture.tree.rowLookups} layout lookups (native baseline: ${baseline.tree.rowLookups})",
                    fixture.tree.rowLookups - baseline.tree.rowLookups < 256,
                )
            }
        } finally {
            onEdt {
                fixture.dispose()
                baseline.dispose()
            }
        }
    }

    @Test
    fun `expansion bursts do not repeatedly query tree layout`() {
        val baseline = onEdt { Fixture(attachManager = false) }
        val fixture = onEdt { Fixture() }
        try {
            val branches = onEdt {
                listOf(baseline, fixture).map { target ->
                    List(100) { index ->
                        val branch = DefaultMutableTreeNode("branch$index").apply { add(DefaultMutableTreeNode("leaf")) }
                        target.model.insertNodeInto(branch, target.parent, target.parent.childCount)
                        TreePath(branch.path)
                    }
                }
            }
            onEdt {
                for ((index, target) in listOf(baseline, fixture).withIndex()) {
                    target.tree.rowLookups = 0
                    branches[index].forEach(target.tree::expandPath)
                }
            }
            onEdt {
                assertTrue(
                    "100 expansions used ${fixture.tree.rowLookups} layout lookups (native baseline: ${baseline.tree.rowLookups})",
                    fixture.tree.rowLookups - baseline.tree.rowLookups < 256,
                )
                assertMatchesViewport(fixture)
            }
        } finally {
            onEdt {
                fixture.dispose()
                baseline.dispose()
            }
        }
    }

    @Test
    fun `pending model updates absorb viewport and selection events`() {
        val baseline = onEdt { Fixture(attachManager = false) }
        val fixture = onEdt { Fixture() }
        try {
            onEdt {
                for (target in listOf(baseline, fixture)) {
                    target.tree.rowLookups = 0
                    repeat(100) { index ->
                        target.model.nodeChanged(target.parent.getChildAt(index))
                        target.scrollPane.viewport.viewPosition = Point(0, 420 + index)
                        target.tree.setSelectionRow(index + 10)
                    }
                }
            }
            onEdt {
                assertTrue(
                    "Mixed burst used ${fixture.tree.rowLookups} layout lookups (native baseline: ${baseline.tree.rowLookups})",
                    fixture.tree.rowLookups - baseline.tree.rowLookups < 256,
                )
                assertMatchesViewport(fixture)
            }
        } finally {
            onEdt {
                fixture.dispose()
                baseline.dispose()
            }
        }
    }

    @Test
    fun `removed rows and collapsed branches refresh after native layout settles`() {
        val fixture = onEdt { Fixture() }
        try {
            onEdt {
                fixture.model.removeNodeFromParent(fixture.parent.lastChild as DefaultMutableTreeNode)
                fixture.tree.collapseRow(0)
            }
            onEdt {
                assertTrue(fixture.items.items().isEmpty())
                assertEquals(0, fixture.panel.stickyContentHeight())
                fixture.tree.expandRow(0)
            }
            onEdt { assertMatchesViewport(fixture) }
            onEdt {
                fixture.model.reload()
                fixture.tree.expandRow(0)
                fixture.tree.rowHeight = 24
            }
            onEdt { assertMatchesViewport(fixture) }
        } finally {
            onEdt { fixture.dispose() }
        }
    }

    @Test
    fun `replacing model ignores events from old model and refreshes current rows`() {
        val fixture = onEdt { Fixture() }
        try {
            onEdt {
                fixture.model.nodeChanged(fixture.parent)
                val replacement = DefaultMutableTreeNode("replacement").apply {
                    repeat(100) { add(DefaultMutableTreeNode("new$it")) }
                }
                fixture.tree.model = DefaultTreeModel(DefaultMutableTreeNode("root").apply { add(replacement) })
                fixture.tree.expandRow(0)
            }
            onEdt {
                assertEquals(listOf("replacement"), fixture.items.items().map { it.treePath.lastPathComponent.toString() })
                assertMatchesViewport(fixture)
                fixture.tree.rowLookups = 0
                fixture.model.nodeChanged(fixture.parent)
            }
            onEdt { assertEquals(0, fixture.tree.rowLookups) }
        } finally {
            onEdt { fixture.dispose() }
        }
    }

    @Test
    fun `explicit refresh consumes pending model recalculation`() {
        val fixture = onEdt { Fixture() }
        try {
            onEdt {
                fixture.model.nodeChanged(fixture.parent)
                fixture.manager.forceUpdate()
                assertMatchesViewport(fixture)
                fixture.tree.rowLookups = 0
            }
            onEdt { assertEquals(0, fixture.tree.rowLookups) }
        } finally {
            onEdt { fixture.dispose() }
        }
    }

    @Test
    fun `disposal cancels pending and future model recalculations`() {
        val fixture = onEdt { Fixture() }
        onEdt {
            fixture.model.nodeChanged(fixture.parent)
            fixture.dispose()
            fixture.tree.rowLookups = 0
        }
        onEdt {
            assertEquals(0, fixture.tree.rowLookups)
            fixture.model.nodeChanged(fixture.parent)
            fixture.manager.forceUpdate()
        }
        onEdt { assertEquals(0, fixture.tree.rowLookups) }
    }

    private fun assertMatchesViewport(fixture: Fixture) {
        val expected = VisualStickyTreeItems(fixture.tree).apply { recalculate(fixture.scrollPane.viewport.viewRect) }
        assertEquals(expected.items(), fixture.items.items())
        assertEquals(expected.height(), fixture.panel.stickyContentHeight())
    }

    private class Fixture(attachManager: Boolean = true) {
        val parent = DefaultMutableTreeNode("parent").apply {
            repeat(2_000) { add(DefaultMutableTreeNode("file$it")) }
        }
        val model = DefaultTreeModel(DefaultMutableTreeNode("root").apply { add(this@Fixture.parent) })
        val tree = CountingTree(model).apply {
            isRootVisible = false
            rowHeight = 20
            expandRow(0)
            setSize(200, preferredSize.height)
        }
        val scrollPane = JScrollPane(tree).apply {
            viewport.extentSize = Dimension(200, 200)
            viewport.viewPosition = Point(0, 400)
        }
        val items = VisualStickyTreeItems(tree)
        val panel = StickyTreePanel(tree, items)
        private val disposable = Disposer.newDisposable()
        val manager = StickyTreeManager(tree, scrollPane, items, panel, disposable).apply {
            forceUpdate()
            if (!attachManager) dispose()
        }

        fun dispose() = Disposer.dispose(disposable)
    }

    private class CountingTree(model: DefaultTreeModel) : JTree(model) {
        var rowLookups = 0

        override fun getPathForRow(row: Int): TreePath? {
            rowLookups++
            return super.getPathForRow(row)
        }

        override fun getRowBounds(row: Int): Rectangle? {
            rowLookups++
            return super.getRowBounds(row)
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
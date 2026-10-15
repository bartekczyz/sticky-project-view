package info.chrzanowski.stickyprojectview

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import java.awt.Point
import javax.swing.JTree
import javax.swing.ScrollPaneLayout
import javax.swing.tree.DefaultMutableTreeNode

class StickyTreeOverlayTest : BasePlatformTestCase() {

    fun testAttachmentPreservesNativeScrollPaneLayout() {
        val pane = JBScrollPane(JTree()).apply { setSize(300, 200); doLayout() }
        val originalLayout = pane.layout
        val controller = ProjectViewStickyController(project)
        try {
            controller.initialize()
            controller.applyPreferences(StickyProjectViewPreferences(true))
            controller.attachToTree(pane.viewport.view as JTree, pane)
            assertTrue(controller.isAttached())
            assertSame("The overlay must not replace IntelliJ's native scroll-pane layout", originalLayout, pane.layout)
        } finally {
            Disposer.dispose(controller)
        }
    }

    fun testDetachmentPreservesLayoutInstalledByLookAndFeelChange() {
        val pane = JBScrollPane(JTree()).apply { setSize(300, 200); doLayout() }
        val controller = ProjectViewStickyController(project)
        try {
            controller.initialize()
            controller.applyPreferences(StickyProjectViewPreferences(true))
            controller.attachToTree(pane.viewport.view as JTree, pane)
            val updatedLayout = ScrollPaneLayout.UIResource()
            pane.layout = updatedLayout
            controller.applyPreferences(StickyProjectViewPreferences(false))
            assertSame("Disabling the overlay must not restore an obsolete layout", updatedLayout, pane.layout)
        } finally {
            Disposer.dispose(controller)
        }
    }

    fun testOverlayFollowsNativeViewportBoundsAfterResize() {
        val tree = JTree(DefaultMutableTreeNode("root").apply {
            add(DefaultMutableTreeNode("parent").apply {
                repeat(200) { add(DefaultMutableTreeNode("file$it")) }
            })
        }).apply {
            isRootVisible = false
            rowHeight = 20
            expandRow(0)
        }
        val pane = JBScrollPane(tree).apply {
            setSize(300, 200)
            doLayout()
            viewport.viewPosition = Point(0, 200)
        }
        val controller = ProjectViewStickyController(project)
        try {
            controller.initialize()
            controller.applyPreferences(StickyProjectViewPreferences(true))
            controller.attachToTree(tree, pane)
            pane.setSize(450, 250)
            pane.doLayout()
            UIUtil.dispatchAllInvocationEvents()
            val panel = pane.components.filterIsInstance<StickyTreePanel>().single()
            assertTrue(panel.height > 0)
            assertEquals(pane.viewport.bounds.location, panel.location)
            assertEquals(pane.viewport.width, panel.width)
            assertEquals(panel.preferredSize.height, panel.height)
        } finally {
            Disposer.dispose(controller)
        }
    }
}
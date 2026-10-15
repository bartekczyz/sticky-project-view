package info.chrzanowski.stickyprojectview

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Dimension
import java.awt.Point
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class StickyAncestorNavigationTest : BasePlatformTestCase() {
    fun testActionRegistrationAndUnavailableStates() {
        val action = ActionManager.getInstance().getAction("StickyProjectView.NavigateAncestors")
        assertInstanceOf(action, NavigateStickyAncestorAction::class.java)
        assertEquals(ActionUpdateThread.EDT, action.actionUpdateThread)
        assertEmpty(action.shortcutSet.shortcuts.toList())
        val event = AnActionEvent.createEvent(
            action,
            SimpleDataContext.getProjectContext(project),
            null,
            ActionPlaces.UNKNOWN,
            ActionUiKind.NONE,
            null,
        )
        action.update(event)
        assertFalse(event.presentation.isEnabled)
        val settings = StickyProjectViewSettings.getInstance()
        val original = settings.preferences
        try {
            settings.updatePreferences(original.copy(enabled = false))
            action.update(event)
            assertFalse(event.presentation.isEnabled)
            action.actionPerformed(event)
        } finally {
            settings.updatePreferences(original)
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
        }
    }

    fun testSnapshotsNavigationAndStaleChoices() {
        val root = DefaultMutableTreeNode("root")
        val parent = DefaultMutableTreeNode("duplicate")
        val child = DefaultMutableTreeNode("duplicate")
        root.add(parent)
        parent.add(child)
        repeat(60) { child.add(DefaultMutableTreeNode("file$it")) }
        val tree = JTree(root).apply {
            isRootVisible = false
            rowHeight = 20
            expandRow(0)
            expandRow(1)
            setSize(600, 1300)
        }
        val pane = JScrollPane(tree)
        pane.viewport.extentSize = Dimension(200, 200)
        pane.viewport.viewPosition = Point(60, 400)
        val controller = ProjectViewStickyController(project)
        try {
            controller.initialize()
            controller.attachToTree(tree, pane)
            val before = pane.viewport.viewPosition
            val height = controller.stickyPanelHeight()
            val choices = controller.stickyAncestors()
            assertEquals(listOf("duplicate", "duplicate"), choices.map { it.label })
            assertEquals(listOf("root › duplicate", "root › duplicate › duplicate"), choices.map { it.fullPath })
            assertTrue(controller.hasStickyAncestors())
            // Observing or abandoning a chooser leaves selection and geometry untouched.
            assertNull(tree.selectionPath)
            assertEquals(before, pane.viewport.viewPosition)
            assertEquals(height, controller.stickyPanelHeight())
            controller.applyPreferences(StickyProjectViewPreferences(true, 90))
            assertTrue(controller.navigateToStickyAncestor(choices.last().treePath))
            assertEquals(choices.last().treePath, tree.selectionPath)
            assertEquals(60, pane.viewport.viewPosition.x)
            assertTrue(tree.getPathBounds(tree.selectionPath).y >= pane.viewport.viewPosition.y)
            assertTrue(controller.stickyAncestors().none { it.treePath == tree.selectionPath })
            assertFalse(controller.stickyItemLabels().contains("file0"))
            pane.viewport.viewPosition = before
            val modelChoice = controller.stickyAncestors().last()
            tree.model = DefaultTreeModel(root)
            assertFalse(controller.navigateToStickyAncestor(modelChoice.treePath))
            tree.expandRow(0)
            tree.expandRow(1)
            pane.viewport.viewPosition = before
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            val removed = controller.stickyAncestors().last()
            (tree.model as DefaultTreeModel).removeNodeFromParent(child)
            assertFalse(controller.navigateToStickyAncestor(removed.treePath))
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            assertTrue(controller.stickyAncestors().none { it.treePath == removed.treePath })
            controller.attachToTree(JTree(), JScrollPane())
            assertFalse(controller.navigateToStickyAncestor(choices.first().treePath))
            controller.applyPreferences(StickyProjectViewPreferences(false))
            assertFalse(controller.hasStickyAncestors())
            assertEmpty(controller.stickyAncestors())
            assertFalse(controller.navigateToStickyAncestor(choices.first().treePath))
        } finally {
            Disposer.dispose(controller)
        }
    }
}

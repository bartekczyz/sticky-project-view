package info.chrzanowski.stickyprojectview

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.impl.ProjectViewImpl
import com.intellij.ide.projectView.impl.ProjectViewListener
import com.intellij.ide.projectView.impl.ProjectViewPane
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ReflectionUtil

class ProjectViewStickyControllerTest : BasePlatformTestCase() {
    fun testInitializeBeforeProjectPaneTreeIsCreated() {
        val projectView = ProjectView.getInstance(project) as ProjectViewImpl
        val pane = ProjectViewPane(project)
        val previousViewId = projectView.currentViewId
        val controller = ProjectViewStickyController(project)
        projectView.addProjectPane(pane)
        try {
            // Reproduce startup with a selected pane whose UI has not been created yet.
            ReflectionUtil.setField(ProjectViewImpl::class.java, projectView, String::class.java, "currentViewId", pane.id)
            assertSame(pane, projectView.getProjectViewPaneById(projectView.currentViewId))
            assertNull(pane.tree)

            controller.initialize()
            assertFalse(controller.isAttached())

            pane.createComponent()
            assertNotNull(pane.tree)
            project.messageBus.syncPublisher(ProjectViewListener.TOPIC).paneShown(pane, null)
            assertTrue(controller.isAttached())
        } finally {
            Disposer.dispose(controller)
            ReflectionUtil.setField(ProjectViewImpl::class.java, projectView, String::class.java, "currentViewId", previousViewId)
            projectView.removeProjectPane(pane)
            Disposer.dispose(pane)
        }
    }
}
package info.chrzanowski.stickyprojectview

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl

class ToggleStickyProjectViewActionTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture() = TempDirTestFixtureImpl()

    fun testSameToggleIsAvailableInBothProjectViewMenus() {
        val actionManager = ActionManager.getInstance()
        val toggle = actionManager.getAction("StickyProjectView.Toggle")
        assertInstanceOf(toggle, ToggleStickyProjectViewAction::class.java)

        for (groupId in listOf("ProjectView.ToolWindow.SecondaryActions", "StickyProjectView.Group")) {
            val group = actionManager.getAction(groupId) as ActionGroup
            assertEquals(groupId, 1, group.getChildren(null).count { it === toggle })
        }
    }
}
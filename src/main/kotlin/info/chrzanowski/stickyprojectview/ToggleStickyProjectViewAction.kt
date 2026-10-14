package info.chrzanowski.stickyprojectview

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware

class ToggleStickyProjectViewAction : ToggleAction(MyBundle.message("action.StickyProjectView.Toggle.text")),
    DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent) = StickyProjectViewSettings.getInstance().preferences.enabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val settings = StickyProjectViewSettings.getInstance()
        settings.updatePreferences(settings.preferences.copy(enabled = state))
    }
}
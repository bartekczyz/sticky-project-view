package info.chrzanowski.stickyprojectview

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction

class ConfigureStickyProjectViewAction : DumbAwareAction(MyBundle.message("action.StickyProjectView.Configure.text")) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, StickyProjectViewConfigurable::class.java)
    }
}

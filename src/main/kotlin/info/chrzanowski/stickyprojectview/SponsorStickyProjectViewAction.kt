package info.chrzanowski.stickyprojectview

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class SponsorStickyProjectViewAction : DumbAwareAction(MyBundle.message("action.StickyProjectView.Sponsor.text")) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        BrowserUtil.browse(StickyProjectViewSupport.GITHUB_URL)
    }
}
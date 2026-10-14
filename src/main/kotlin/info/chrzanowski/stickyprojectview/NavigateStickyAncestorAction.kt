package info.chrzanowski.stickyprojectview

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer

class NavigateStickyAncestorAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = StickyProjectViewSettings.getInstance().preferences.enabled &&
                event.project?.service<StickyProjectViewService>()?.hasStickyAncestors() == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        if (!StickyProjectViewSettings.getInstance().preferences.enabled) return
        val service = event.project?.service<StickyProjectViewService>() ?: return
        val ancestors = service.stickyAncestors()
        if (ancestors.isEmpty()) return
        JBPopupFactory.getInstance().createPopupChooserBuilder(ancestors)
            .setTitle(MyBundle.message("ancestors.title"))
            .setRenderer(
                SimpleListCellRenderer.create<StickyAncestor> { label, ancestor, _ ->
                    label.text = ancestor?.fullPath.orEmpty()
                },
            )
            .setNamerForFiltering { it.fullPath }
            .setRequestFocus(true)
            .setItemChosenCallback { service.navigateToStickyAncestor(it.treePath); service.focusTree() }
            .setCancelCallback { service.focusTree(); true }
            .createPopup().showInBestPositionFor(event.dataContext)
    }
}

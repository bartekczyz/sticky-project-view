// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package info.chrzanowski.stickyprojectview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer

@Service(Service.Level.PROJECT)
class StickyProjectViewService(project: Project) : Disposable {

    private val controller = ProjectViewStickyController(project)
    @Volatile
    private var disposed = false

    init {
        Disposer.register(this, controller)
        val application = ApplicationManager.getApplication()
        application.messageBus.connect(this).subscribe(
            StickyProjectViewSettingsListener.TOPIC,
            object : StickyProjectViewSettingsListener {
                override fun settingsChanged(preferences: StickyProjectViewPreferences) {
                    application.invokeLater {
                        if (!project.isDisposed && !disposed) {
                            controller.applyPreferences(StickyProjectViewSettings.getInstance().preferences)
                        }
                    }
                }
            },
        )
    }

    fun initialize() {
        controller.applyPreferences(StickyProjectViewSettings.getInstance().preferences)
        controller.initialize()
    }

    // Observations must not attach a pane or refresh the sticky calculation.
    fun isAttached() = controller.isAttached()

    fun hasStickyAncestors() = controller.hasStickyAncestors()

    fun stickyAncestors() = controller.stickyAncestors()

    fun navigateToStickyAncestor(treePath: javax.swing.tree.TreePath): Boolean =
        !disposed && StickyProjectViewSettings.getInstance().preferences.enabled && controller.navigateToStickyAncestor(treePath)

    internal fun focusTree() = controller.focusTree()

    fun getStickyItemLabels() = controller.stickyItemLabels()

    fun getStickyPanelHeight() = controller.stickyPanelHeight()

    fun getVisibleRowLabels() = controller.visibleRowLabels()

    fun getSelectedPathLabels() = controller.selectedPathLabels()

    fun selectPath(pathSegments: List<String>) = controller.selectPath(pathSegments)

    override fun dispose() {
        disposed = true
    }
}

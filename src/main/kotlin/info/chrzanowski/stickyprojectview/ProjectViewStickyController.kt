// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package info.chrzanowski.stickyprojectview

import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.impl.AbstractProjectViewPane
import com.intellij.ide.projectView.impl.ProjectViewImpl
import com.intellij.ide.projectView.impl.ProjectViewListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import java.awt.Container
import java.awt.LayoutManager
import java.awt.Point
import java.nio.file.Path
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.ScrollPaneLayout
import javax.swing.SwingUtilities

class ProjectViewStickyController(private val project: Project) : Disposable {

    private var attachment: StickyTreeAttachment? = null

    init {
        project.messageBus.connect(this).subscribe(
            ProjectViewListener.TOPIC,
            object : ProjectViewListener {
                override fun paneShown(current: AbstractProjectViewPane, previous: AbstractProjectViewPane?) {
                    attachToPane(current)
                }
            }
        )
    }

    fun initialize() {
        currentPane()?.let {
            StickyTreeDebugLogger.logAttachment(it.tree, "initialize")
            attachToPane(it)
        }
    }

    fun isAttached() = withAttachment(false) { true }

    fun stickyItemLabels(): List<String> =
        withAttachment(emptyList()) { visualStickyItems.items().map { it.treePath.lastPathComponent.toString() } }

    fun stickyPanelHeight() = withAttachment(0) { stickyPanel.preferredSize.height }

    fun visibleRowLabels(): List<String> = withAttachment(emptyList()) {
        (0 until tree.rowCount).mapNotNull(tree::getPathForRow).map { it.lastPathComponent.toString() }
    }

    fun selectedPathLabels(): List<String> = withAttachment(emptyList()) {
        tree.selectionPath?.path?.drop(1)?.map(Any::toString).orEmpty()
    }

    fun selectPath(pathSegments: List<String>): Boolean {
        if (!isAttached()) return false
        val file = resolveFile(pathSegments) ?: return false
        val callback = onEdt {
            currentProjectView()?.selectCB(null, file, false) ?: ActionCallback.REJECTED
        }
        if (!callback.waitFor(SELECTION_TIMEOUT_MS)) {
            return false
        }

        return onEdt {
            val currentAttachment = currentAttachment() ?: return@onEdt false
            currentAttachment.tree.selectionPath
                ?.let(currentAttachment.tree::getRowForPath)
                ?.takeIf { it >= 0 }
                ?.also { scrollToRow(currentAttachment, it) } != null
        }
    }

    override fun dispose() {
        detach()
    }

    private fun attachToPane(pane: AbstractProjectViewPane?) {
        val tree = pane?.tree ?: run {
            detach()
            return
        }
        val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, tree) as? JScrollPane ?: run {
            detach()
            return
        }

        val currentAttachment = attachment
        if (currentAttachment?.tree === tree) {
            StickyTreeDebugLogger.logAttachment(tree, "reuse")
            currentAttachment.manager.forceUpdate()
            return
        }

        detach()
        StickyTreeDebugLogger.logAttachment(tree, "attach")

        val attachmentDisposable = Disposer.newDisposable()
        val visualStickyItems = VisualStickyTreeItems(tree)
        lateinit var stickyManager: StickyTreeManager
        val stickyPanel = StickyTreePanel(tree, visualStickyItems) { stickyManager.navigateTo(it) }
        stickyManager = StickyTreeManager(tree, scrollPane, visualStickyItems, stickyPanel, attachmentDisposable)

        val originalLayout = installStickyPanel(scrollPane, stickyPanel)

        attachment = StickyTreeAttachment(tree, scrollPane, stickyPanel, visualStickyItems, stickyManager, attachmentDisposable, originalLayout)
        stickyManager.forceUpdate()
    }

    private fun currentPane(): AbstractProjectViewPane? {
        val projectView = currentProjectView() ?: return null
        val currentPaneId = projectView.currentViewId ?: return null
        return projectView.getProjectViewPaneById(currentPaneId)
    }

    private fun currentProjectView() = ProjectView.getInstance(project) as? ProjectViewImpl

    private fun detach() {
        attachment?.dispose()
        attachment = null
    }

    private fun currentAttachment(): StickyTreeAttachment? {
        return attachment
    }

    private fun <T> withAttachment(default: T, action: StickyTreeAttachment.() -> T): T =
        onEdt { currentAttachment()?.action() ?: default }

    private fun installStickyPanel(scrollPane: JScrollPane, stickyPanel: StickyTreePanel): LayoutManager {
        val originalLayout = scrollPane.layout
        if (stickyPanel.parent !== scrollPane) {
            scrollPane.add(stickyPanel)
            scrollPane.setComponentZOrder(stickyPanel, 0)
        }
        scrollPane.layout = StickyTreeOverlayLayout(stickyPanel)
        scrollPane.revalidate()
        scrollPane.repaint()
        return originalLayout
    }

    private fun resolveFile(pathSegments: List<String>) =
        project.basePath
            ?.let(Path::of)
            ?.resolve(pathSegments.joinToString("/"))
            ?.let(LocalFileSystem.getInstance()::refreshAndFindFileByNioFile)

    private fun scrollToRow(attachment: StickyTreeAttachment, row: Int) {
        val rowBounds = attachment.tree.getRowBounds(row) ?: return
        val extentHeight = attachment.scrollPane.viewport.extentSize.height
        val maxY = (maxOf(attachment.tree.height, attachment.tree.preferredSize.height) - extentHeight).coerceAtLeast(0)
        attachment.scrollPane.viewport.viewPosition = Point(0, rowBounds.y.coerceAtMost(maxY))
        attachment.manager.forceUpdate()
    }

    private fun <T> onEdt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) {
            return action()
        }

        var result: Result<T>? = null
        SwingUtilities.invokeAndWait {
            result = runCatching(action)
        }
        return result!!.getOrThrow()
    }

    private class StickyTreeAttachment(
        val tree: JTree,
        val scrollPane: JScrollPane,
        val stickyPanel: StickyTreePanel,
        val visualStickyItems: VisualStickyTreeItems,
        val manager: StickyTreeManager,
        private val disposable: Disposable,
        private val originalLayout: LayoutManager
    ) {
        fun dispose() {
            Disposer.dispose(disposable)
            if (stickyPanel.parent === scrollPane) {
                scrollPane.remove(stickyPanel)
            }
            scrollPane.layout = originalLayout
            scrollPane.revalidate()
            scrollPane.repaint()
        }
    }

    private class StickyTreeOverlayLayout(
        private val stickyPanel: StickyTreePanel
    ) : ScrollPaneLayout() {
        override fun layoutContainer(parent: Container) {
            super.layoutContainer(parent)

            val viewportBounds = viewport?.bounds ?: return
            val stickyHeight = stickyPanel.preferredSize.height
            stickyPanel.setBounds(
                viewportBounds.x,
                viewportBounds.y,
                viewportBounds.width,
                stickyHeight
            )
        }
    }

    private companion object {
        const val SELECTION_TIMEOUT_MS = 30_000L
    }
}

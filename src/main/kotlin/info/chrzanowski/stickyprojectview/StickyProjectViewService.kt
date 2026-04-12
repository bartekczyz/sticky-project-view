// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package info.chrzanowski.stickyprojectview

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import org.jetbrains.annotations.ApiStatus

@Service(Service.Level.PROJECT)
class StickyProjectViewService(project: Project) : Disposable {

    private val controller = ProjectViewStickyController(project)

    init {
        Disposer.register(this, controller)
    }

    fun initialize() = controller.initialize()

    fun isAttached() = controller.isAttached()

    fun getStickyItemLabels() = controller.stickyItemLabels()

    fun getStickyPanelHeight() = controller.stickyPanelHeight()

    fun getVisibleRowLabels() = controller.visibleRowLabels()

    fun getSelectedPathLabels() = controller.selectedPathLabels()

    fun selectPath(pathSegments: List<String>) = controller.selectPath(pathSegments)

    override fun dispose() = Unit
}

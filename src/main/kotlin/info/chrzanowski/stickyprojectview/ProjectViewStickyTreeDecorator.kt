// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package info.chrzanowski.stickyprojectview

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import javax.swing.SwingUtilities

/**
 * Project activity that decorates the Project View tree with sticky items functionality.
 * This is automatically executed on project startup.
 */
class ProjectViewStickyTreeDecorator : ProjectActivity {

    override suspend fun execute(project: Project) {
        SwingUtilities.invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }

            project.service<StickyProjectViewService>().initialize()
        }
    }
}

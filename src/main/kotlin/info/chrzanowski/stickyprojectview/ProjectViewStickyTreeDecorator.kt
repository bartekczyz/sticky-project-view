// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package info.chrzanowski.stickyprojectview

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.SwingUtilities

/**
 * Project activity that decorates the Project View tree with sticky items functionality.
 * This is automatically executed on project startup.
 */
class ProjectViewStickyTreeDecorator : ProjectActivity {
    
    override suspend fun execute(project: Project) {
        SwingUtilities.invokeLater {
            setupStickyTreeForProject(project)
        }
    }
    
    private fun setupStickyTreeForProject(project: Project) {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val projectViewToolWindow = toolWindowManager.getToolWindow("Project") ?: return
        
        // Find the tree component in the Project View
        val tree = findProjectViewTree(projectViewToolWindow.component) ?: return
        val scrollPane = findScrollPane(tree) ?: return
        
        // Create sticky tree components
        val visualStickyItems = VisualStickyTreeItems(tree)
        val stickyPanel = StickyTreePanel(tree, visualStickyItems)
        
        // Create a disposable tied to the project
        val disposable = Disposable { }
        Disposer.register(project, disposable)
        
        val manager = StickyTreeManager(tree, scrollPane, visualStickyItems, stickyPanel, disposable)
        
        // Install the sticky panel over the tree
        installStickyPanel(scrollPane, stickyPanel)
        
        // Initial update
        manager.forceUpdate()
    }
    
    /**
     * Finds the JTree component in the Project View
     */
    private fun findProjectViewTree(component: Component): JTree? {
        if (component is JTree) {
            return component
        }
        
        if (component is java.awt.Container) {
            for (child in component.components) {
                val tree = findProjectViewTree(child)
                if (tree != null) {
                    return tree
                }
            }
        }
        
        return null
    }
    
    /**
     * Finds the scroll pane containing the tree
     */
    private fun findScrollPane(tree: JTree): JScrollPane? {
        var parent = tree.parent
        while (parent != null) {
            if (parent is JScrollPane) {
                return parent
            }
            parent = parent.parent
        }
        return null
    }
    
    /**
     * Installs the sticky panel as an overlay on the scroll pane
     */
    private fun installStickyPanel(scrollPane: JScrollPane, stickyPanel: StickyTreePanel) {
        scrollPane.layout = object : javax.swing.ScrollPaneLayout() {
            override fun layoutContainer(parent: java.awt.Container) {
                super.layoutContainer(parent)

                val viewportBounds = viewport?.bounds ?: return
                val stickyHeight = stickyPanel.height

                if (stickyHeight > 0 && stickyPanel.isVisible) {
                    stickyPanel.setBounds(
                        viewportBounds.x,
                        viewportBounds.y,
                        viewportBounds.width,
                        stickyHeight
                    )
                }
            }
        }

        // Add sticky panel to the scroll pane
        scrollPane.add(stickyPanel)

        // Make sure sticky panel is visible above other components
        scrollPane.setComponentZOrder(stickyPanel, 0)
    }
}

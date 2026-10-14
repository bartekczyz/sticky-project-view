package info.chrzanowski.stickyprojectview

import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.openapi.application.ReadAction
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

object StickyTreePathPresentation {
    fun fullPath(path: TreePath): String = ReadAction.compute<String, RuntimeException> {
        val component = path.lastPathComponent
        val node = (component as? DefaultMutableTreeNode)?.userObject ?: component
        (node as? ProjectViewNode<*>)?.virtualFile?.presentableUrl
            ?: path.path.joinToString(" › ") { it.toString() }
    }

    fun tooltip(path: TreePath): String = "<html>${fullPath(path).replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")}</html>"
}
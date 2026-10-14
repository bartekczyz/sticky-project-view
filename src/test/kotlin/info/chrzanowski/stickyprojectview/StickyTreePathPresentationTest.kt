package info.chrzanowski.stickyprojectview

import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class StickyTreePathPresentationTest : BasePlatformTestCase() {
    fun testRealFileAndDirectoryPaths() {
        val file = myFixture.addFileToProject("one/same.txt", "hello")
        val other = myFixture.addFileToProject("two/same.txt", "hello")
        val filePath = TreePath(DefaultMutableTreeNode(PsiFileNode(project, file, ViewSettings.DEFAULT)))
        val otherPath = TreePath(DefaultMutableTreeNode(PsiFileNode(project, other, ViewSettings.DEFAULT)))
        assertEquals(file.virtualFile.presentableUrl, StickyTreePathPresentation.fullPath(filePath))
        assertFalse(StickyTreePathPresentation.fullPath(filePath) == StickyTreePathPresentation.fullPath(otherPath))
        val directory = TreePath(DefaultMutableTreeNode(PsiDirectoryNode(project, file.containingDirectory, ViewSettings.DEFAULT)))
        assertEquals(file.containingDirectory.virtualFile.presentableUrl, StickyTreePathPresentation.fullPath(directory))
    }

    fun testLogicalChainAndHtmlEscaping() {
        val path = TreePath(arrayOf(DefaultMutableTreeNode("logical root"), DefaultMutableTreeNode("<&\"'>")))
        assertEquals("logical root › <&\"'>", StickyTreePathPresentation.fullPath(path))
        assertEquals("<html>logical root › &lt;&amp;&quot;&#39;&gt;</html>", StickyTreePathPresentation.tooltip(path))
    }
}
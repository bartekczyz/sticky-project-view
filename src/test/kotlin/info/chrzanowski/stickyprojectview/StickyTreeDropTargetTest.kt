package info.chrzanowski.stickyprojectview

import com.intellij.ide.dnd.DnDAction
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDEventImpl
import com.intellij.ide.dnd.DnDManager
import com.intellij.ide.dnd.DnDManagerImpl
import com.intellij.ide.dnd.DnDNativeTarget
import com.intellij.ide.dnd.DnDTarget
import com.intellij.ide.projectView.impl.ProjectViewDropTarget
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.ui.awt.RelativeRectangle
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.JLayeredPane
import javax.swing.JRootPane
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class StickyTreeDropTargetTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        ApplicationManager.getApplication().replaceService(DnDManager::class.java, DnDManagerImpl(), testRootDisposable)
    }

    fun testHoverAndDropUseStickyDirectoryInsteadOfCoveredNode() = fixture { tree, pane, panel, target ->
        val point = Point(60, 5)
        val covered = tree.getClosestPathForLocation(point.x, pane.viewport.viewPosition.y + point.y)
        assertEquals("leaf-0", covered.lastPathComponent.toString())
        val event = event(panel, point)
        val router = overlayTarget(panel)

        assertFalse(router.update(event))
        assertTrue(event.isDropPossible)
        assertEquals("parent", target.hovered?.lastPathComponent.toString())
        router.drop(event)
        assertEquals(target.hovered, target.dropped)
        assertEquals(point, event.point)
        assertSame(target.attached, event.attachedObject)
        assertEquals(DnDAction.MOVE, target.action)
        assertSame(target, tree.getClientProperty("DnD Target"))
    }

    fun testShadowRoutesToVisibleUnderlyingTreeRow() = fixture { tree, pane, panel, target ->
        val before = paint(panel)
        val point = Point(60, panel.stickyContentHeight())
        val expected = tree.getClosestPathForLocation(point.x, pane.viewport.viewPosition.y + point.y)
        val event = event(panel, point)
        val router = overlayTarget(panel)
        router.update(event(panel, Point(60, 5)))
        router.update(event)
        assertImageEquals(before, paint(panel))
        router.drop(event)
        assertEquals(expected, target.hovered)
        assertEquals(expected, target.dropped)
    }

    fun testNativeDragsAreSupportedAndRejectionIsPreserved() = fixture { _, _, panel, target ->
        val router = overlayTarget(panel)
        assertTrue(router is DnDNativeTarget)
        val before = paint(panel)
        router.update(event(panel, Point(60, 5)))
        target.accept = false
        val event = event(panel, Point(60, 5))
        event.setDropPossible(true)
        assertFalse(router.update(event))
        assertFalse(event.isDropPossible)
        assertImageEquals(before, paint(panel))
    }

    fun testCurrentTreeTargetIsResolvedForEachCallback() = fixture { tree, _, panel, original ->
        val replacement = RecordingTarget(tree)
        val manager = DnDManager.getInstance()
        manager.unregisterTarget(original, tree)
        manager.registerTarget(replacement, tree, testRootDisposable)
        val event = event(panel, Point(60, 5))
        val router = overlayTarget(panel)
        router.update(event)
        router.drop(event)
        assertNull(original.hovered)
        assertEquals("parent", replacement.dropped?.lastPathComponent.toString())
    }

    fun testMissingTreeTargetRejectsInsteadOfFallingThrough() = fixture { tree, _, panel, target ->
        val router = overlayTarget(panel)
        val before = paint(panel)
        router.update(event(panel, Point(60, 5)))
        DnDManager.getInstance().unregisterTarget(target, tree)
        val event = event(panel, Point(60, 5))
        event.setDropPossible(true)
        assertFalse(router.update(event))
        assertFalse(event.isDropPossible)
        assertImageEquals(before, paint(panel))
        router.drop(event)
        assertNull(target.dropped)
    }

    fun testDisablingOverlayUnregistersOnlyItsDropTarget() = fixture { tree, _, panel, target ->
        val router = overlayTarget(panel)
        val before = paint(panel)
        router.update(event(panel, Point(60, 5)))
        controller.applyPreferences(StickyProjectViewPreferences(false))
        assertNull(panel.getClientProperty("DnD Target"))
        assertSame(target, tree.getClientProperty("DnD Target"))
        assertImageEquals(before, paint(panel))
        router.drop(event(panel, Point(60, 5)))
        assertNull(target.dropped)
    }

    fun testHighlightIsPaintedOnStickyRow() = fixture { _, _, panel, _ ->
        var highlight: RelativeRectangle? = null
        val before = paint(panel)
        overlayTarget(panel).update(event(panel, Point(60, 5), onHighlight = { highlight = it }))
        assertNull("Do not add a second native pane-level highlight over the sticky feedback", highlight)
        val highlighted = paint(panel)
        val border = JBUI.CurrentTheme.DragAndDrop.BORDER_COLOR.rgb
        assertEquals(border, highlighted.getRGB(5, 0))
        assertEquals(border, highlighted.getRGB(5, 19))
        assertImageEquals(before, highlighted, panel.stickyContentHeight())
    }

    fun testStickyDropFeedbackPaintsNativeBorderAndFillAcrossViewport() = fixture { _, _, panel, _ ->
        val before = paint(panel)
        overlayTarget(panel).update(event(panel, Point(60, 5)))
        val highlighted = paint(panel)
        val border = JBUI.CurrentTheme.DragAndDrop.BORDER_COLOR.rgb
        assertEquals("The sticky drop target needs the native left border", border, highlighted.getRGB(0, 5))
        assertEquals("The highlight must span the viewport", border, highlighted.getRGB(panel.width - 1, 5))
        assertTrue("The sticky drop target needs the native background tint", before.getRGB(5, 5) != highlighted.getRGB(5, 5))
    }

    fun testHighlightUsesTreeOriginWhenRelativePointIsRootPaneBased() = fixture(showing = true) { tree, _, panel, _ ->
        val rectangle = RelativeRectangle(tree, tree.getRowBounds(0))
        assertSame(tree, rectangle.point.originalComponent)
        assertSame(SwingUtilities.getRootPane(tree), rectangle.point.component)
        assertNotSame(tree, rectangle.point.component)
        var highlight: RelativeRectangle? = null
        val event = event(panel, Point(60, 5), onHighlight = { highlight = it })
        overlayTarget(panel).update(event)
        assertTrue(event.isDropPossible)
        assertNull("Do not forward the highlight to the offscreen original row", highlight)
        assertEquals(
            "A displayed tree must highlight the sticky row, not the offscreen original row",
            JBUI.CurrentTheme.DragAndDrop.BORDER_COLOR.rgb, paint(panel).getRGB(0, 5),
        )
    }

    fun testDropAndLeaveClearStickyFeedbackWithoutChangingSelection() = fixture { tree, _, panel, _ ->
        tree.setSelectionRow(3)
        val selection = tree.selectionPath
        val before = paint(panel)
        val router = overlayTarget(panel)
        val event = event(panel, Point(60, 5))
        router.update(event)
        router.cleanUpOnLeave()
        assertImageEquals(before, paint(panel))
        router.update(event)
        router.drop(event)
        assertImageEquals(before, paint(panel))
        assertEquals(selection, tree.selectionPath)
    }

    fun testNativeHandlerCanHideStickyHighlight() = fixture { _, _, panel, target ->
        val before = paint(panel)
        val router = overlayTarget(panel)
        val event = event(panel, Point(60, 5))
        router.update(event)
        target.hideHighlight = true
        router.update(event)
        assertTrue(event.isDropPossible)
        assertImageEquals(before, paint(panel))
    }

    fun testLayeredPaneHighlightOverloadPaintsStickyRow() = fixture(showing = true) { _, _, panel, target ->
        target.layeredPane = JLayeredPane()
        overlayTarget(panel).update(event(panel, Point(60, 5)))
        val highlighted = paint(panel)
        val border = JBUI.CurrentTheme.DragAndDrop.BORDER_COLOR.rgb
        assertEquals(border, highlighted.getRGB(0, 5))
        assertEquals(border, highlighted.getRGB(panel.width - 1, 5))
    }

    fun testPushedOffRowMapsAndClipsHighlightToVisiblePortion() = fixture { tree, pane, panel, target ->
        val model = tree.model as DefaultTreeModel
        val next = DefaultMutableTreeNode("next").apply {
            repeat(30) { add(DefaultMutableTreeNode("next-leaf-$it")) }
        }
        model.insertNodeInto(next, model.root as DefaultMutableTreeNode, 1)
        tree.expandPath(TreePath(next.path))
        pane.viewport.viewPosition = Point(0, 610)
        controller.attachToTree(tree, pane)
        assertEquals(-10, panel.itemAt(60, 0)!!.yLocation)
        val before = paint(panel)
        val event = event(panel, Point(60, 0))
        val router = overlayTarget(panel)
        router.update(event)
        val highlighted = paint(panel)
        val border = JBUI.CurrentTheme.DragAndDrop.BORDER_COLOR.rgb
        assertEquals(border, highlighted.getRGB(0, 0))
        assertEquals(border, highlighted.getRGB(5, 9))
        assertTrue(before.getRGB(5, 0) != highlighted.getRGB(5, 0))
        assertTrue("The clipped row must not acquire a new top border", border != highlighted.getRGB(5, 0))
        assertImageEquals(before, highlighted, 10)
        router.drop(event)
        assertEquals("parent", target.dropped?.lastPathComponent.toString())
        assertEquals(10, target.point!!.y)
        assertImageEquals(before, paint(panel))
    }

    fun testNestedStickyFeedbackMovesBetweenRowsAndRespectsParentMask() = fixture { tree, pane, panel, _ ->
        val model = tree.model as DefaultTreeModel
        val root = model.root as DefaultMutableTreeNode
        val parent = root.getChildAt(0) as DefaultMutableTreeNode
        val child = DefaultMutableTreeNode("child").apply {
            repeat(30) { add(DefaultMutableTreeNode("nested-$it")) }
        }
        parent.removeAllChildren()
        parent.add(child)
        model.nodeStructureChanged(parent)
        tree.expandPath(TreePath(parent.path))
        tree.expandPath(TreePath(child.path))
        val next = DefaultMutableTreeNode("next").apply {
            repeat(30) { add(DefaultMutableTreeNode("next-leaf-$it")) }
        }
        model.insertNodeInto(next, root, 1)
        tree.expandPath(TreePath(next.path))
        pane.viewport.viewPosition = Point(0, 610)
        controller.attachToTree(tree, pane)
        assertEquals(parent, panel.itemAt(60, 15)!!.treePath.lastPathComponent)
        assertEquals(child, panel.itemAt(60, 20)!!.treePath.lastPathComponent)

        val before = paint(panel)
        val router = overlayTarget(panel)
        val border = JBUI.CurrentTheme.DragAndDrop.BORDER_COLOR.rgb
        router.update(event(panel, Point(60, 15)))
        val parentFeedback = paint(panel)
        assertEquals(border, parentFeedback.getRGB(0, 15))
        assertImageEquals(before, parentFeedback, 20)

        router.update(event(panel, Point(60, 20)))
        val childFeedback = paint(panel)
        assertEquals(border, childFeedback.getRGB(0, 20))
        assertEquals(border, childFeedback.getRGB(5, 29))
        for (y in 0 until 20) {
            assertEquals("The parent must mask the pushed-off child", before.getRGB(5, y), childFeedback.getRGB(5, y))
        }
        assertImageEquals(before, childFeedback, 30)
    }

    fun testHorizontalScrollPreservesTreeCoordinates() = fixture(showing = true) { tree, pane, panel, target ->
        pane.viewport.viewSize = Dimension(600, tree.height)
        pane.viewport.viewPosition = Point(12, 21)
        controller.attachToTree(tree, pane)
        overlayTarget(panel).update(event(panel, Point(60, 5)))
        assertEquals(Point(72, 5), target.point)
        assertEquals(target.point, target.pointOnTree)
        assertSame(tree, target.handler)
        val highlighted = paint(panel)
        val border = JBUI.CurrentTheme.DragAndDrop.BORDER_COLOR.rgb
        assertEquals(border, highlighted.getRGB(0, 5))
        assertEquals(border, highlighted.getRGB(panel.width - 1, 5))
    }

    fun testRemovedStickyPathCannotReceiveDropAfterHover() = fixture { tree, _, panel, target ->
        val event = event(panel, Point(60, 5))
        val router = overlayTarget(panel)
        router.update(event)
        val parent = target.hovered!!.lastPathComponent as DefaultMutableTreeNode
        (tree.model as DefaultTreeModel).removeNodeFromParent(parent)
        router.drop(event)
        assertNull(target.dropped)
    }

    @Suppress("JetBrainsInternalApiUsage") // Exercise the platform handler without adding it to the shipped plugin.
    fun testNativeProjectViewHandlerReceivesFilesAndStickyDestination() = fixture(showing = true) { tree, _, panel, original ->
        var destination: TreePath? = null
        var droppedFiles: List<File>? = null
        val nativeTarget = object : ProjectViewDropTarget(tree, project) {
            override fun getPsiElement(path: TreePath): PsiElement? = null
            override fun getModule(element: PsiElement): Module? = null
            override fun createMoveHandler(): DropHandler {
                return object : DropHandler by super.createMoveHandler() {
                    override fun doDropFiles(files: List<File>, target: TreePath) {
                        destination = target
                        droppedFiles = files
                    }
                }
            }
        }
        val manager = DnDManager.getInstance()
        manager.unregisterTarget(original, tree)
        manager.registerTarget(nativeTarget, tree, testRootDisposable)
        val files = listOf(File("dragged-file.txt"))
        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor): Any = files
        }
        val nativeInfo = DnDNativeTarget.EventInfo(transferable.transferDataFlavors, transferable)
        val event = event(panel, Point(60, 5), attached = nativeInfo)
        val router = overlayTarget(panel)
        val before = paint(panel)
        router.update(event)
        assertTrue(event.isDropPossible)
        assertEquals(JBUI.CurrentTheme.DragAndDrop.BORDER_COLOR.rgb, paint(panel).getRGB(0, 5))
        router.drop(event)
        assertEquals("parent", destination?.lastPathComponent.toString())
        assertEquals(files, droppedFiles)
        assertImageEquals(before, paint(panel))
    }

    private lateinit var controller: ProjectViewStickyController

    private fun fixture(showing: Boolean = false, test: (JTree, JScrollPane, StickyTreePanel, RecordingTarget) -> Unit) {
        val root = DefaultMutableTreeNode("root")
        root.add(DefaultMutableTreeNode("parent").apply {
            repeat(30) { add(DefaultMutableTreeNode("leaf-$it")) }
        })
        // Exercise RelativePoint's displayed-tree normalization without requiring a native window.
        val tree = object : JTree(root) {
            override fun isShowing(): Boolean = showing || super.isShowing()
        }.apply {
            isRootVisible = false
            rowHeight = 20
            expandRow(0)
        }
        val pane = JScrollPane(tree).apply {
            setSize(300, 140)
            doLayout()
            viewport.viewPosition = Point(0, 21)
        }
        if (showing) JRootPane().contentPane.add(pane)
        val target = RecordingTarget(tree)
        DnDManager.getInstance().registerTarget(target, tree, testRootDisposable)
        assertSame(target, tree.getClientProperty("DnD Target"))
        controller = ProjectViewStickyController(project)
        try {
            controller.initialize()
            controller.attachToTree(tree, pane)
            val panel = pane.components.filterIsInstance<StickyTreePanel>().single()
            assertTrue(panel.height > 0)
            test(tree, pane, panel, target)
        } finally {
            Disposer.dispose(controller)
        }
    }

    private fun overlayTarget(panel: StickyTreePanel): DnDTarget {
        val target = panel.getClientProperty("DnD Target")
        assertNotNull("Sticky rows must participate in native drop-target routing", target)
        return target as DnDTarget
    }

    private fun paint(panel: StickyTreePanel): BufferedImage {
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun assertImageEquals(expected: BufferedImage, actual: BufferedImage, fromY: Int = 0) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        for (y in fromY until expected.height) {
            for (x in 0 until expected.width) {
                assertEquals("Pixel at ($x, $y)", expected.getRGB(x, y), actual.getRGB(x, y))
            }
        }
    }

    private fun event(
        panel: StickyTreePanel,
        point: Point,
        attached: Any = StringSelection("file"),
        onHighlight: (RelativeRectangle) -> Unit = {},
    ): DnDEvent {
        val delegate = DnDEventImpl(null, DnDAction.MOVE, attached, point)
        return object : DnDEvent by delegate {
            override fun getHandlerComponent(): Component = panel
            override fun getPointOn(component: Component): Point = SwingUtilities.convertPoint(panel, point, component)
            override fun setHighlighting(rectangle: RelativeRectangle, type: Int) = onHighlight(rectangle)
            override fun hideHighlighter() {}
        }
    }

    private class RecordingTarget(private val tree: JTree) : DnDNativeTarget {
        var hovered: TreePath? = null
        var dropped: TreePath? = null
        var attached: Any? = null
        var action: DnDAction? = null
        var point: Point? = null
        var pointOnTree: Point? = null
        var handler: Component? = null
        var accept = true
        var hideHighlight = false
        var layeredPane: JLayeredPane? = null

        override fun update(event: DnDEvent): Boolean {
            hovered = tree.getClosestPathForLocation(event.point.x, event.point.y)
            attached = event.attachedObject
            action = event.action
            point = event.point
            pointOnTree = event.getPointOn(tree)
            handler = event.handlerComponent
            val rectangle = RelativeRectangle(tree, tree.getPathBounds(hovered))
            if (hideHighlight) {
                event.hideHighlighter()
            } else if (layeredPane != null) {
                event.setHighlighting(layeredPane!!, rectangle, DnDEvent.DropTargetHighlightingType.RECTANGLE)
            } else {
                event.setHighlighting(rectangle, DnDEvent.DropTargetHighlightingType.RECTANGLE)
            }
            event.setDropPossible(accept)
            return false
        }

        override fun drop(event: DnDEvent) {
            dropped = tree.getClosestPathForLocation(event.point.x, event.point.y)
        }
    }
}
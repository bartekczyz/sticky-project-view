package info.chrzanowski.stickyprojectview

import com.intellij.configurationStore.deserialize
import com.intellij.configurationStore.serialize
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.nio.file.Path
import javax.swing.JScrollPane
import javax.swing.JTree

class StickyProjectViewSettingsTest : BasePlatformTestCase() {
    fun testConfigurableApplyResetPublishesCombinedUpdate() {
        val settings = StickyProjectViewSettings.getInstance()
        val original = settings.preferences
        val configurable = StickyProjectViewConfigurable()
        val owner = Disposer.newDisposable()
        val snapshots = mutableListOf<StickyProjectViewPreferences>()
        ApplicationManager.getApplication().messageBus.connect(owner).subscribe(
            StickyProjectViewSettingsListener.TOPIC,
            object : StickyProjectViewSettingsListener {
                override fun settingsChanged(preferences: StickyProjectViewPreferences) { snapshots.add(preferences) }
            },
        )
        try {
            settings.updatePreferences(StickyProjectViewPreferences(true, 50))
            val component = requireNotNull(configurable.createComponent())
            configurable.reset()
            fun descendants(container: java.awt.Container): List<java.awt.Component> =
                container.components.flatMap { listOf(it) + if (it is java.awt.Container) descendants(it) else emptyList() }
            val controls = descendants(component)
            val checkbox = controls.filterIsInstance<javax.swing.JCheckBox>().single()
            val spinner = controls.filterIsInstance<javax.swing.JSpinner>().single()
            checkbox.isSelected = false
            spinner.value = 90
            assertTrue(configurable.isModified)
            assertEquals(StickyProjectViewPreferences(true, 50), settings.preferences)
            snapshots.clear()
            configurable.apply()
            assertEquals(listOf(StickyProjectViewPreferences(false, 90)), snapshots)
            assertFalse(configurable.isModified)
            checkbox.isSelected = true
            spinner.value = 10
            configurable.reset()
            assertFalse(checkbox.isSelected)
            assertEquals(90, spinner.value)
            assertFalse(configurable.isModified)
            configurable.apply()
            assertSize(1, snapshots)
        } finally {
            configurable.disposeUIResources()
            Disposer.dispose(owner)
            settings.updatePreferences(original)
            UIUtil.dispatchAllInvocationEvents()
        }
    }

    fun testDefaultsSerializationAndBounds() {
        val settings = StickyProjectViewSettings()
        assertEquals(StickyProjectViewPreferences(), settings.preferences)
        settings.updatePreferences(StickyProjectViewPreferences(false, 95))
        assertEquals(90, settings.preferences.maxStickyHeightPercent)
        val serialized = requireNotNull(serialize(settings.state))
        val restored = StickyProjectViewSettings()
        restored.loadState(deserialize<StickyProjectViewPreferences>(serialized))
        assertEquals(settings.preferences, restored.preferences)
        restored.loadState(StickyProjectViewPreferences(false, -1))
        assertEquals(10, restored.preferences.maxStickyHeightPercent)
    }

    fun testNoOpUpdatesAndDisposableNotifications() {
        val owner = Disposer.newDisposable()
        val snapshots = mutableListOf<StickyProjectViewPreferences>()
        ApplicationManager.getApplication().messageBus.connect(owner).subscribe(
            StickyProjectViewSettingsListener.TOPIC,
            object : StickyProjectViewSettingsListener {
                override fun settingsChanged(preferences: StickyProjectViewPreferences) {
                    snapshots.add(preferences)
                }
            },
        )
        val settings = StickyProjectViewSettings()
        try {
            settings.updatePreferences(StickyProjectViewPreferences())
            assertEmpty(snapshots)
            settings.updatePreferences(StickyProjectViewPreferences(false, 100))
            settings.updatePreferences(StickyProjectViewPreferences(false, 91))
            assertEquals(listOf(StickyProjectViewPreferences(false, 90)), snapshots)
        } finally {
            Disposer.dispose(owner)
        }
        settings.updatePreferences(StickyProjectViewPreferences())
        assertSize(1, snapshots)
    }

    fun testDisposedControllerCannotReattach() {
        val controller = ProjectViewStickyController(project)
        Disposer.dispose(controller)
        controller.applyPreferences(StickyProjectViewPreferences())
        controller.initialize()
        assertFalse(controller.isAttached())
    }

    fun testToggleRestoresLayoutAndListenersAcrossTwoProjects() {
        val secondProject = requireNotNull(
            ProjectManagerEx.getInstanceEx().newProject(
                Path.of(myFixture.tempDirPath).resolve("second"), OpenProjectTask { isNewProject = true },
            ),
        )
        val settings = StickyProjectViewSettings.getInstance()
        val original = settings.preferences
        val services = listOf(StickyProjectViewService(project), StickyProjectViewService(secondProject))
        val controllers = services.map { service ->
            val field = StickyProjectViewService::class.java.getDeclaredField("controller")
            field.isAccessible = true
            field.get(service) as ProjectViewStickyController
        }
        val trees = List(2) { JTree() }
        val panes = trees.map(::JScrollPane)
        val layouts = panes.map { it.layout }
        val selectionListeners = trees.map { it.treeSelectionListeners.toSet() }
        val viewportListeners = panes.map { it.viewport.changeListeners.toSet() }
        try {
            settings.updatePreferences(original.copy(enabled = true))
            UIUtil.dispatchAllInvocationEvents()
            services.forEach { it.initialize() }
            repeat(3) {
                controllers.forEachIndexed { index, controller -> controller.attachToTree(trees[index], panes[index]) }
                assertTrue(services.all { it.isAttached() })
                controllers.forEachIndexed { index, controller ->
                    val listeners = trees[index].treeSelectionListeners.toSet()
                    controller.attachToTree(trees[index], panes[index])
                    assertEquals(listeners, trees[index].treeSelectionListeners.toSet())
                    assertEquals(1, panes[index].components.count { it is StickyTreePanel })
                    val replacement = JTree()
                    val replacementPane = JScrollPane(replacement)
                    val replacementLayout = replacementPane.layout
                    controller.attachToTree(replacement, replacementPane)
                    assertSame(layouts[index], panes[index].layout)
                    assertEquals(selectionListeners[index], trees[index].treeSelectionListeners.toSet())
                    controller.attachToTree(trees[index], panes[index])
                    assertSame(replacementLayout, replacementPane.layout)
                    assertTrue(replacementPane.components.none { it is StickyTreePanel })
                }
                settings.updatePreferences(settings.preferences.copy(enabled = false))
                UIUtil.dispatchAllInvocationEvents()
                assertTrue(services.none { it.isAttached() })
                controllers.forEachIndexed { index, controller ->
                    assertSame(layouts[index], panes[index].layout)
                    assertEquals(selectionListeners[index], trees[index].treeSelectionListeners.toSet())
                    assertEquals(viewportListeners[index], panes[index].viewport.changeListeners.toSet())
                    assertTrue(panes[index].components.none { it is StickyTreePanel })
                    controller.attachToTree(trees[index], panes[index])
                    assertFalse(controller.isAttached())
                }
                settings.updatePreferences(settings.preferences.copy(enabled = true))
                UIUtil.dispatchAllInvocationEvents()
            }
            // Dispose before the queued preference callback can execute.
            controllers.forEachIndexed { index, controller -> controller.attachToTree(trees[index], panes[index]) }
            settings.updatePreferences(settings.preferences.copy(enabled = false))
            services.forEach(Disposer::dispose)
            UIUtil.dispatchAllInvocationEvents()
            controllers.forEach { it.initialize(); assertFalse(it.isAttached()) }
            panes.forEachIndexed { index, pane -> assertSame(layouts[index], pane.layout) }
        } finally {
            services.forEach { if (!Disposer.isDisposed(it)) Disposer.dispose(it) }
            settings.updatePreferences(original)
            UIUtil.dispatchAllInvocationEvents()
            WriteAction.run<RuntimeException> { Disposer.dispose(secondProject) }
        }
    }
}
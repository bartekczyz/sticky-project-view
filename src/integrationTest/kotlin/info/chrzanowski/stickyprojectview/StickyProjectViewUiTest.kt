package info.chrzanowski.stickyprojectview

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.toolwindows.projectView
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.awt.Rectangle
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class StickyProjectViewUiTest {

    private val pluginPath = Path.of(System.getProperty("path.to.build.plugin"))
    private val platformVersion = requireNotNull(System.getProperty("platformVersion"))
    private val originalDi = di
    private val ciServer = StarterJUnitCIServer()

    @BeforeEach
    fun reportIdeFailuresToJUnit() {
        di = DI {
            extend(originalDi)
            bindSingleton<CIServer>(overrides = true) {
                ciServer
            }
        }
    }

    @AfterEach
    fun assertNoIdeFailures() {
        di = originalDi
        ciServer.assertNoFailures()
    }

    @Test
    fun togglePersistsAcrossRestart(@TempDir temporaryDirectory: Path) {
        val projectDir = temporaryDirectory.resolve(PROJECT_NAME).createDirectories()
        createProjectStructure(projectDir)
        val context = Starter.newContext(
            "${CurrentTestMethod.hyphenateWithClass()}-${UUID.randomUUID()}",
            TestCase(IdeProductProvider.IU, LocalProjectInfo(projectDir, isReusable = true)).withVersion(platformVersion)
        ).apply {
            PluginConfigurator(this).installPluginFromPath(pluginPath)
            seedProfilerState(paths.configDir)
        }
        context.runIdeWithDriver(runTimeout = 3.minutes, launchName = "disable").useDriverAndCloseIde {
            waitForProjectOpen()
            waitForIndicators(2.minutes)
            assertEquals(platformVersion, service<ApplicationInfoRef>().getFullVersion())
            println("Toggle baseline: ${service<ApplicationInfoRef>().getBuild().asString()}")
            ideFrame { leftToolWindowToolbar.projectButton.open() }
            val sticky = service<StickyProjectViewServiceRef>(singleProject())
            waitFor("initial attachment", timeout = 10.seconds) { sticky.isAttached() }
            repeat(3) {
                invokeAction("StickyProjectView.Toggle")
                waitFor("disabled overlay", timeout = 10.seconds) { !sticky.isAttached() && sticky.getStickyPanelHeight() == 0 }
                invokeAction("StickyProjectView.Toggle")
                waitFor("enabled overlay", timeout = 10.seconds) { sticky.isAttached() }
            }
            invokeAction("StickyProjectView.Toggle")
            waitFor("disabled before restart", timeout = 10.seconds) { !sticky.isAttached() }
        }
        context.runIdeWithDriver(runTimeout = 3.minutes, launchName = "restart-disabled").useDriverAndCloseIde {
            waitForProjectOpen()
            waitForIndicators(2.minutes)
            assertEquals(platformVersion, service<ApplicationInfoRef>().getFullVersion())
            println("Restart baseline: ${service<ApplicationInfoRef>().getBuild().asString()}")
            assertTrue(!service<StickyProjectViewSettingsRef>().getPreferences().component1(), "Restart must load disabled state from storage")
            val sticky = service<StickyProjectViewServiceRef>(singleProject())
            sticky.initialize()
            assertTrue(!sticky.isAttached(), "Restart must retain the disabled preference")
            try {
                invokeAction("StickyProjectView.Toggle")
                waitFor("reenabled after restart", timeout = 10.seconds) { sticky.isAttached() }
            } finally {
                // This isolated config must finish with the default enabled state.
                service<StickyProjectViewSettingsRef>().updatePreferences(new(StickyProjectViewPreferencesRef::class, true, 50))
            }
        }
    }

    @Test
    fun stickyItemsFollowExpandedProjectViewPath(@TempDir temporaryDirectory: Path) {
        val projectDir = temporaryDirectory.resolve(PROJECT_NAME).createDirectories()
        createProjectStructure(projectDir)

        Starter.newContext(
            "${CurrentTestMethod.hyphenateWithClass()}-${UUID.randomUUID()}",
            TestCase(IdeProductProvider.IU, LocalProjectInfo(projectDir, isReusable = false))
                .withVersion(platformVersion)
        ).apply {
            PluginConfigurator(this).installPluginFromPath(pluginPath)
            seedProfilerState(paths.configDir)
        }.runIdeWithDriver(runTimeout = 8.minutes).useDriverAndCloseIde {
            waitForProjectOpen()
            waitForIndicators(5.minutes)

            val applicationInfo = service<ApplicationInfoRef>()
            val actualVersion = applicationInfo.getFullVersion()
            val actualBuild = applicationInfo.getBuild().asString()
            println("Starter baseline: requested=$platformVersion, actual=$actualVersion, build=$actualBuild")
            assertEquals(platformVersion, actualVersion)
            assertTrue(actualBuild.startsWith("IU-"), "Unexpected IDE product build: $actualBuild")

            ideFrame {
                leftToolWindowToolbar.projectButton.open()
                projectView {
                    projectViewTree.waitFound()
                }
            }

            val stickyService = service<StickyProjectViewServiceRef>(singleProject())
            waitFor("sticky Project View controller to attach", timeout = 30.seconds) {
                stickyService.isAttached()
            }

            assertTrue(
                stickyService.selectPath(TARGET_FILE_PATH.toList()),
                "Deep file was not selected. Selected path: ${stickyService.getSelectedPathLabels()}, visible rows: ${stickyService.getVisibleRowLabels()}"
            )
            waitFor("exact deep file selection", timeout = 30.seconds) {
                stickyService.getSelectedPathLabels().takeLast(TARGET_FILE_PATH.size) == TARGET_FILE_PATH.asList()
            }
            assertEquals(
                listOf(PROJECT_NAME) + TARGET_FILE_PATH,
                stickyService.getSelectedPathLabels(),
                "Selection must identify the requested file, not merely any selected row"
            )

            waitFor("sticky items to match expanded directories", timeout = 30.seconds) {
                stickyService.getStickyItemLabels().takeLast(TARGET_DIRECTORY_PATH.size) == TARGET_DIRECTORY_PATH.asList() &&
                    stickyService.getStickyPanelHeight() > 0
            }

            val expectedStickyItems = TARGET_DIRECTORY_PATH.asList()
            val actualStickyItems = stickyService.getStickyItemLabels()
            assertEquals(expectedStickyItems, actualStickyItems.takeLast(expectedStickyItems.size))
            assertTrue(stickyService.getStickyPanelHeight() > 0)

            val settings = service<StickyProjectViewSettingsRef>()
            val defaultHeight = stickyService.getStickyPanelHeight()
            val nativeTreeForSettings = cast(ideFrame().projectView().projectViewTree.component, ProjectTreeRef::class)
            val chooserSelection = stickyService.getSelectedPathLabels()
            invokeAction("StickyProjectView.NavigateAncestors", now = false)
            ui.x("//div[@text='Sticky Ancestors']").waitFound().click()
            ui.robot.pressAndReleaseKey(KeyEvent.VK_DOWN)
            ui.robot.pressAndReleaseKey(KeyEvent.VK_ESCAPE)
            waitFor("chooser cancellation restores tree focus", timeout = 10.seconds) {
                withContext(OnDispatcher.EDT) { nativeTreeForSettings.isFocusOwner() }
            }
            assertEquals(chooserSelection, stickyService.getSelectedPathLabels())
            invokeAction("StickyProjectView.NavigateAncestors", now = false)
            ui.x("//div[@text='Sticky Ancestors']").waitFound().click()
            repeat(actualStickyItems.size) { ui.robot.pressAndReleaseKey(KeyEvent.VK_UP) }
            ui.robot.pressAndReleaseKey(KeyEvent.VK_DOWN)
            ui.robot.pressAndReleaseKey(KeyEvent.VK_ENTER)
            val chosenLabel = actualStickyItems[1]
            val chosenPath = chooserSelection.take(chooserSelection.indexOf(chosenLabel) + 1)
            waitFor("keyboard chooser selects exact second ancestor", timeout = 10.seconds) {
                stickyService.getSelectedPathLabels() == chosenPath &&
                    withContext(OnDispatcher.EDT) { nativeTreeForSettings.isFocusOwner() }
            }
            assertTrue(stickyService.selectPath(TARGET_FILE_PATH.toList()))
            waitFor("restore stack after keyboard navigation", timeout = 10.seconds) {
                stickyService.getStickyItemLabels().takeLast(expectedStickyItems.size) == expectedStickyItems
            }
            val originalViewport = withContext(OnDispatcher.EDT) { nativeTreeForSettings.getVisibleRect() }
            try {
                invokeAction("StickyProjectView.Configure", now = false)
                val spinnerComponent = ui.x { byType("javax.swing.JSpinner") }.waitFound()
                val spinner = cast(spinnerComponent.component, SpinnerRef::class)
                assertEquals(50, withContext(OnDispatcher.EDT) { spinner.getValue() })
                withContext(OnDispatcher.EDT) { spinner.setValue(10) }
                spinnerComponent.click(Point(20, 10))
                ui.robot.pressAndReleaseKey(KeyEvent.VK_TAB)
                val applyButton = ui.x("//div[@text='Apply']").waitFound()
                val nativeApply = cast(applyButton.component, ButtonRef::class)
                waitFor("Settings enables Apply after editing", timeout = 10.seconds) {
                    withContext(OnDispatcher.EDT) { nativeApply.isEnabled() }
                }
                applyButton.click()
                waitFor("height Apply updates the existing overlay without scrolling", timeout = 10.seconds) {
                    settings.getPreferences().component2() == 10 && stickyService.getStickyPanelHeight() < defaultHeight
                }
                assertEquals(originalViewport, withContext(OnDispatcher.EDT) { nativeTreeForSettings.getVisibleRect() })
                withContext(OnDispatcher.EDT) { spinner.setValue(90) }
                spinnerComponent.click(Point(20, 10))
                ui.robot.pressAndReleaseKey(KeyEvent.VK_TAB)
                ui.x("//div[@text='Revert changes']").waitFound().click()
                waitFor("Reset restores the applied height", timeout = 10.seconds) {
                    withContext(OnDispatcher.EDT) { spinner.getValue() } == 10
                }
                withContext(OnDispatcher.EDT) { spinner.setValue(50) }
                spinnerComponent.click(Point(20, 10))
                ui.robot.pressAndReleaseKey(KeyEvent.VK_TAB)
                ui.x("//div[@text='OK']").waitFound().click()
                waitFor("default budget restored", timeout = 10.seconds) {
                    settings.getPreferences().component2() == 50 && stickyService.getStickyPanelHeight() == defaultHeight
                }
            } finally {
                settings.updatePreferences(new(StickyProjectViewPreferencesRef::class, true, 50))
            }

            val nextFilePath = TARGET_DIRECTORY_PATH + "DeepFile121.txt"
            assertTrue(stickyService.selectPath(nextFilePath.toList()))
            waitFor("sticky stack after changing selection", timeout = 30.seconds) {
                stickyService.getSelectedPathLabels() == listOf(PROJECT_NAME) + nextFilePath &&
                    stickyService.getStickyItemLabels().takeLast(expectedStickyItems.size) == expectedStickyItems
            }
            val panelHeightAfterSelection = stickyService.getStickyPanelHeight()
            repeat(3) {
                assertEquals(listOf(PROJECT_NAME) + nextFilePath, stickyService.getSelectedPathLabels())
                assertEquals(expectedStickyItems, stickyService.getStickyItemLabels().takeLast(expectedStickyItems.size))
                assertEquals(panelHeightAfterSelection, stickyService.getStickyPanelHeight())
            }

            val overlay = ui.x { byType("info.chrzanowski.stickyprojectview.StickyTreePanel") }.waitFound()
            val treeComponent = ideFrame().projectView().projectViewTree
            val nativeTree = cast(treeComponent.component, ProjectTreeRef::class)
            fun postWheel(target: Any, point: Point) {
                withContext(OnDispatcher.EDT) {
                    val event = new(MouseWheelEventRef::class, target, MouseEvent.MOUSE_WHEEL,
                        System.currentTimeMillis(), 0, point.x, point.y, 0, false,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -3)
                    utility(ToolkitRef::class).getDefaultToolkit().getSystemEventQueue().postEvent(event)
                }
            }
            fun deeperPoint(): Point {
                val labels = stickyService.getStickyItemLabels()
                val bounds = withContext(OnDispatcher.EDT) {
                    nativeTree.getRowBounds(stickyService.getVisibleRowLabels().indexOf("deeper"))
                }
                return Point(bounds.x + bounds.width / 2 - withContext(OnDispatcher.EDT) { nativeTree.getVisibleRect().x },
                    labels.indexOf("deeper") * bounds.height + bounds.height / 2)
            }
            overlay.moveMouse(deeperPoint())
            val expectedTooltipPath = projectDir.resolve(TARGET_DIRECTORY_PATH.joinToString("/")).toString()
            ui.x("//div[contains(@text, '$expectedTooltipPath')]").waitFound()
                .waitAnyTextsContains(expectedTooltipPath)

            overlay.rightClick(deeperPoint())
            val popup = ui.x { byType("javax.swing.JPopupMenu") }.waitFound()
            assertEquals(listOf(PROJECT_NAME) + TARGET_DIRECTORY_PATH, stickyService.getSelectedPathLabels())
            val popupLocation = popup.getLocationOnScreen()
            val overlayLocation = overlay.getLocationOnScreen()
            assertTrue(popupLocation.y >= overlayLocation.y && popupLocation.y <= overlayLocation.y + stickyService.getStickyPanelHeight(),
                "Popup must be anchored in the overlay, not at its offscreen native row")
            ui.robot.pressAndReleaseKey(KeyEvent.VK_ESCAPE)
            waitFor("context menu closes before wheel interaction", timeout = 10.seconds) { popup.notPresent() }

            assertTrue(stickyService.selectPath(nextFilePath.toList()))
            overlay.doubleClick(deeperPoint())
            waitFor("native double click collapses the sticky directory", timeout = 10.seconds) {
                "DeepFile120.txt" !in stickyService.getVisibleRowLabels()
            }

            assertTrue(stickyService.selectPath(nextFilePath.toList()))
            overlay.click(deeperPoint())
            waitFor("completed sticky label click reveals the exact ancestor", timeout = 10.seconds) {
                stickyService.getSelectedPathLabels() == listOf(PROJECT_NAME) + TARGET_DIRECTORY_PATH &&
                    stickyService.getStickyItemLabels().lastOrNull() == "nested"
            }
            assertTrue(stickyService.selectPath(TARGET_FILE_PATH.toList()))
            waitFor("selection clears the revealed ancestor exception", timeout = 10.seconds) {
                stickyService.getStickyItemLabels().takeLast(expectedStickyItems.size) == expectedStickyItems
            }
            val beforeNativeWheel = withContext(OnDispatcher.EDT) { nativeTree.getVisibleRect().y }
            postWheel(treeComponent.component, Point(150, beforeNativeWheel + stickyService.getStickyPanelHeight() + 80))
            waitFor("native wheel control outside the overlay", timeout = 10.seconds) {
                withContext(OnDispatcher.EDT) { nativeTree.getVisibleRect().y } < beforeNativeWheel
            }
            assertTrue(stickyService.selectPath(TARGET_FILE_PATH.toList()))
            val beforeWheel = withContext(OnDispatcher.EDT) { nativeTree.getVisibleRect().y }
            assertTrue(beforeWheel > 0, "Wheel scenario must start in a vertically scrolled viewport")
            postWheel(overlay.component, deeperPoint())
            waitFor("wheel over sticky rows scrolls the native viewport", timeout = 10.seconds) {
                withContext(OnDispatcher.EDT) { nativeTree.getVisibleRect().y } < beforeWheel
            }
        }
    }

    @Test
    fun largeTreeExpansionAndModelUpdates(@TempDir temporaryDirectory: Path) {
        val projectDir = temporaryDirectory.resolve(PROJECT_NAME).createDirectories()
        createProjectStructure(projectDir)
        val deepDirectory = projectDir.resolve(TARGET_DIRECTORY_PATH.joinToString("/"))
        repeat(60) { branch ->
            val directory = deepDirectory.resolve("branch-${branch.toString().padStart(3, '0')}").createDirectories()
            repeat(40) { file ->
                directory.resolve("File${file.toString().padStart(3, '0')}.txt").writeText("file $file")
            }
        }
        val targetPath = TARGET_DIRECTORY_PATH + arrayOf("branch-059", "File039.txt")
        val expectedAncestors = TARGET_DIRECTORY_PATH.asList() + "branch-059"

        Starter.newContext(
            "${CurrentTestMethod.hyphenateWithClass()}-${UUID.randomUUID()}",
            TestCase(IdeProductProvider.IU, LocalProjectInfo(projectDir, isReusable = false)).withVersion(platformVersion)
        ).apply {
            PluginConfigurator(this).installPluginFromPath(pluginPath)
            seedProfilerState(paths.configDir)
        }.runIdeWithDriver(runTimeout = 5.minutes).useDriverAndCloseIde {
            waitForProjectOpen()
            waitForIndicators(2.minutes)
            assertEquals(platformVersion, service<ApplicationInfoRef>().getFullVersion())
            ideFrame {
                leftToolWindowToolbar.projectButton.open()
                projectView { projectViewTree.waitFound() }
            }
            val sticky = service<StickyProjectViewServiceRef>(singleProject())
            val settings = service<StickyProjectViewSettingsRef>()
            val tree = cast(ideFrame().projectView().projectViewTree.component, ProjectTreeRef::class)
            try {
                for (enabled in listOf(false, true)) {
                    settings.updatePreferences(new(StickyProjectViewPreferencesRef::class, enabled, 50))
                    waitFor("overlay enabled=$enabled", timeout = 10.seconds) { sticky.isAttached() == enabled }
                    withContext(OnDispatcher.EDT) { tree.collapseRow(0) }
                    waitFor("collapsed project root", timeout = 10.seconds) {
                        withContext(OnDispatcher.EDT) { tree.getRowCount() } < 10
                    }
                    val started = System.nanoTime()
                    val expansion = withContext(OnDispatcher.EDT) { utility(TreeUtilRef::class).promiseExpandAll(tree) }
                    expansion.blockingGet(30_000)
                    waitFor("large tree expanded with overlay enabled=$enabled", timeout = 30.seconds) {
                        withContext(OnDispatcher.EDT) { tree.getRowCount() } >= 2_600
                    }
                    println("Large-tree expansion: overlay=$enabled, rows=${withContext(OnDispatcher.EDT) { tree.getRowCount() }}, elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                }
                assertTrue(sticky.selectPath(targetPath.asList()))
                waitFor("sticky ancestors in expanded large tree", timeout = 10.seconds) {
                    val actualAncestors = sticky.getStickyItemLabels()
                    val selection = sticky.getSelectedPathLabels()
                    val bounds = withContext(OnDispatcher.EDT) { tree.getRowBounds(tree.getMinSelectionRow()) }
                    val viewport = withContext(OnDispatcher.EDT) { tree.getVisibleRect() }
                    val stickyHeight = sticky.getStickyPanelHeight()
                    actualAncestors.takeLast(expectedAncestors.size) == expectedAncestors &&
                        selection == listOf(PROJECT_NAME) + targetPath && bounds.y >= viewport.y + stickyHeight
                }
                val initialRows = withContext(OnDispatcher.EDT) { tree.getRowCount() }
                val changedDirectory = utility(LocalFileSystemRef::class).getInstance()
                    .findFileByPath(deepDirectory.resolve("branch-059").toString().replace('\\', '/'))
                val addedFiles = List(200) { index ->
                    deepDirectory.resolve("branch-059/Added${index.toString().padStart(3, '0')}.txt").apply { writeText("added $index") }
                }
                changedDirectory.refresh(asynchronous = true, recursive = true)
                waitFor("asynchronous model insertion burst", timeout = 30.seconds) {
                    val actualRows = withContext(OnDispatcher.EDT) { tree.getRowCount() }
                    val actualChildren = changedDirectory.getChildren().size
                    actualRows == initialRows + addedFiles.size && actualChildren == 240
                }
                assertEquals(listOf(PROJECT_NAME) + targetPath, sticky.getSelectedPathLabels())
                assertTrue(sticky.selectPath(targetPath.asList()))
                waitFor("sticky rows after insertion burst", timeout = 10.seconds) {
                    sticky.getStickyItemLabels().takeLast(expectedAncestors.size) == expectedAncestors
                }
                addedFiles.forEach { it.deleteExisting() }
                changedDirectory.refresh(asynchronous = true, recursive = true)
                waitFor("asynchronous model removal burst", timeout = 30.seconds) {
                    withContext(OnDispatcher.EDT) { tree.getRowCount() } == initialRows && changedDirectory.getChildren().size == 40
                }
                assertEquals(listOf(PROJECT_NAME) + targetPath, sticky.getSelectedPathLabels())
                assertTrue(sticky.selectPath(targetPath.asList()))
                waitFor("sticky rows after removal burst", timeout = 10.seconds) {
                    sticky.getStickyItemLabels().takeLast(expectedAncestors.size) == expectedAncestors
                }
                withContext(OnDispatcher.EDT) { tree.collapseRow(0) }
                waitFor("collapse clears large-tree overlay", timeout = 10.seconds) {
                    sticky.getStickyItemLabels().isEmpty() && sticky.getStickyPanelHeight() == 0
                }
            } finally {
                settings.updatePreferences(new(StickyProjectViewPreferencesRef::class, true, 50))
            }
        }
    }

    @Test
    fun restoredProjectStartupKeepsEdtResponsive(@TempDir temporaryDirectory: Path) {
        val workload = System.getenv("STICKY_PERFORMANCE_PROJECT")?.takeIf(String::isNotBlank)
        val projectDir = workload?.let(Path::of) ?: temporaryDirectory.resolve(PROJECT_NAME).createDirectories().also {
            createProjectStructure(it)
        }
        if (workload != null) {
            require(projectDir.toAbsolutePath().normalize().startsWith(Path.of(System.getProperty("user.dir"), "build"))) {
                "The performance workload must be an isolated copy under build/"
            }
        }
        val workspace = projectDir.resolve(".idea/workspace.xml")
        val savedWorkspace = workspace.takeIf(Files::exists)?.let(Files::readAllBytes)
        val savedViewState = System.getenv("STICKY_PERFORMANCE_WORKSPACE")?.takeIf(String::isNotBlank)?.let(Path::of)
        val legacyTreeCache = System.getenv("STICKY_PERFORMANCE_LEGACY_TREE") == "true"
        val startupFailures = mutableListOf<String>()
        for (enabled in listOf(false, true)) {
            savedWorkspace?.let { Files.write(workspace, it) }
            val context = Starter.newContext(
                "${CurrentTestMethod.hyphenateWithClass()}-$enabled-${UUID.randomUUID()}",
                TestCase(IdeProductProvider.IU, LocalProjectInfo(projectDir, isReusable = true)).withVersion(platformVersion),
            ).apply {
                PluginConfigurator(this).installPluginFromPath(pluginPath)
                val probeJar = paths.pluginsDir.resolve("sticky-project-view/lib/edt-heartbeat.jar")
                JarOutputStream(Files.newOutputStream(probeJar)).use { jar ->
                    val name = "info/chrzanowski/stickyprojectview/EdtHeartbeat.class"
                    jar.putNextEntry(JarEntry(name))
                    requireNotNull(EdtHeartbeat::class.java.getResourceAsStream("/$name")).use { it.copyTo(jar) }
                    jar.closeEntry()
                }
                seedProfilerState(paths.configDir)
                if (legacyTreeCache) {
                    paths.configDir.resolve("options/ide.general.xml").writeText(
                        """
                        <application>
                          <component name="Registry">
                            <entry key="ide.tree.experimental.layout.cache" value="false" />
                          </component>
                        </application>
                        """.trimIndent(),
                    )
                }
                savedViewState?.let {
                    val stateDirectory = paths.configDir.resolve("workspace").createDirectories()
                    Files.copy(it, stateDirectory.resolve(it.fileName))
                }
                paths.configDir.resolve("options/sticky-project-view.xml").writeText(
                    """
                    <application>
                      <component name="StickyProjectViewSettings">
                        <option name="enabled" value="$enabled" />
                      </component>
                    </application>
                    """.trimIndent(),
                )
            }
            context.runIdeWithDriver(runTimeout = 4.minutes, launchName = "prepare-restored-startup").useDriverAndCloseIde {
                waitForProjectOpen()
                ideFrame { leftToolWindowToolbar.projectButton.open() }
                waitForIndicators(2.minutes)
                if (workload == null) {
                    val tree = cast(ideFrame().projectView().projectViewTree.component, ProjectTreeRef::class)
                    val expansion = withContext(OnDispatcher.EDT) { utility(TreeUtilRef::class).promiseExpandAll(tree) }
                    expansion.blockingGet(30_000)
                }
            }
            savedWorkspace?.let { Files.write(workspace, it) }
            savedViewState?.let {
                Files.copy(it, context.paths.configDir.resolve("workspace").resolve(it.fileName), StandardCopyOption.REPLACE_EXISTING)
            }
            context.runIdeWithDriver(runTimeout = 4.minutes, launchName = "restored-startup").useDriverAndCloseIde {
                if (legacyTreeCache) {
                    assertTrue(!utility(RegistryRef::class).`is`("ide.tree.experimental.layout.cache"))
                }
                val heartbeat = new(EdtHeartbeatRef::class)
                val captureJvmDumps = System.getenv("STICKY_PERFORMANCE_SAMPLE_JVM") == "true"
                val processId = if (captureJvmDumps) utility(ManagementFactoryRef::class).getRuntimeMXBean().getPid() else 0L
                check(!captureJvmDumps || processId > 0 && processId != ProcessHandle.current().pid())
                val jcmd = Path.of(System.getProperty("java.home"), "bin/jcmd").toString()
                val samples = CopyOnWriteArrayList<String>()
                val sampling = AtomicBoolean(captureJvmDumps)
                val samplingFailure = AtomicReference<Throwable>()
                val executor = Executors.newSingleThreadExecutor()
                val collector = executor.submit {
                    try {
                        while (sampling.get()) {
                            val dump = ProcessBuilder(jcmd, processId.toString(), "Thread.print")
                                .redirectErrorStream(true).start()
                            val output = dump.inputStream.bufferedReader().use { it.readText() }
                            check(dump.waitFor() == 0) { output }
                            samples.add(output.split("\n\n").filter {
                                it.startsWith("\"AWT-EventQueue") || it.contains("DefaultTreeLayoutCache") ||
                                    it.contains("info.chrzanowski.stickyprojectview")
                            }.joinToString("\n\n") { it.take(24_000) }.take(96_000))
                            while (samples.size > 40) samples.removeAt(0)
                            Thread.sleep(500)
                        }
                    } catch (failure: Throwable) {
                        if (sampling.get()) samplingFailure.set(failure)
                    }
                }
                var longestCallbackMillis = 0L
                try {
                    repeat(120) {
                        assertTrue(withContext(OnDispatcher.EDT) { utility(SwingUtilitiesRef::class).isEventDispatchThread() })
                        longestCallbackMillis = maxOf(longestCallbackMillis, heartbeat.getMaxDelayMillis())
                        Thread.sleep(100)
                    }
                    waitForProjectOpen()
                    ideFrame { leftToolWindowToolbar.projectButton.open() }
                    assertEquals(enabled, service<StickyProjectViewSettingsRef>().getPreferences().component1())
                    assertEquals(enabled, service<StickyProjectViewServiceRef>(singleProject()).isAttached())
                    samplingFailure.get()?.let { throw it }
                    println("Restored startup: overlay=$enabled, legacyCache=$legacyTreeCache, longestEdtCallbackMs=$longestCallbackMillis, samples=${samples.size}")
                    if (longestCallbackMillis >= 1_000) {
                        startupFailures.add("Startup blocked an EDT callback for $longestCallbackMillis ms (overlay=$enabled):\n" +
                            samples.distinct().joinToString("\n---\n"),
                        )
                    }
                } finally {
                    heartbeat.stop()
                    sampling.set(false)
                    collector.cancel(true)
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "EDT sampler did not stop")
                }
            }
        }
        assertTrue(startupFailures.isEmpty(), startupFailures.joinToString("\n===\n"))
    }

    private fun seedProfilerState(configDir: Path) {
        // IDEA 2025.3's first-save profiler bug creates unsupported OS configurations on macOS.
        // Use its own post-save state, including known types, rather than filtering IDE errors.
        configDir.resolve("options").createDirectories().resolve("profilerRunConfigurations.xml").writeText(
            """
            <application>
              <component name="ProfilerRunConfigurations">
                <profilerRunConfigurations>
                  <item configurationTypeId="IntellijProfilerConfiguration">
                    <IntellijProfilerConfigurationState name="IntelliJ Profiler" />
                  </item>
                </profilerRunConfigurations>
                <knownConfigurationTypes>
                  <id>IntellijProfilerConfiguration</id>
                  <id>AsyncProfilerCPUConfiguration</id>
                  <id>AsyncProfilerMemoryConfiguration</id>
                  <id>AsyncProfilerConfiguration</id>
                  <id>WinAsyncProfilerConfigurationType</id>
                  <id>JVMDTraceProfilerConfiguration</id>
                  <id>JFRSimpleConfiguration</id>
                </knownConfigurationTypes>
                <migrationStatus>
                  <status languageGroup="Java Profiler Configuration Type" lastMigrationIndex="3" />
                </migrationStatus>
              </component>
            </application>
            """.trimIndent()
        )
    }

    private fun createProjectStructure(projectDir: Path) {
        // Pre-create the IDE's local ignore rules so Settings saves do not race their generation
        // with the baseline full-line plugin's cancellable state serialization.
        projectDir.resolve(".idea").createDirectories().resolve(".gitignore").writeText(
            """
            # Default ignored files
            /shelf/
            /workspace.xml
            # Editor-based HTTP Client requests
            /httpRequests/
            # Datasource local storage ignored files
            /dataSources/
            /dataSources.local.xml
            """.trimIndent() + "\n"
        )
        var currentDirectory = projectDir
        TARGET_DIRECTORY_PATH.forEach { segment ->
            currentDirectory = currentDirectory.resolve(segment).createDirectories()
            currentDirectory.resolve("keep-$segment.txt").writeText(segment)
        }
        val targetDirectory = currentDirectory

        repeat(160) { index ->
            targetDirectory.resolve("DeepFile${index.toString().padStart(3, '0')}.txt").writeText("file $index")
        }

        repeat(80) { index ->
            projectDir.resolve("root-file-${index.toString().padStart(3, '0')}.txt").writeText("root $index")
        }
    }

    @Remote("info.chrzanowski.stickyprojectview.StickyProjectViewPreferences", plugin = "sticky-project-view")
    interface StickyProjectViewPreferencesRef {
        fun component1(): Boolean
        fun component2(): Int
    }

    @Remote("java.lang.management.ManagementFactory")
    interface ManagementFactoryRef {
        fun getRuntimeMXBean(): RuntimeMXBeanRef
    }

    @Remote("java.lang.management.RuntimeMXBean")
    interface RuntimeMXBeanRef {
        fun getPid(): Long
    }

    @Remote("info.chrzanowski.stickyprojectview.EdtHeartbeat", plugin = "sticky-project-view")
    interface EdtHeartbeatRef {
        fun getMaxDelayMillis(): Long
        fun stop()
    }

    @Remote("javax.swing.SwingUtilities")
    interface SwingUtilitiesRef {
        fun isEventDispatchThread(): Boolean
    }

    @Remote("com.intellij.openapi.util.registry.Registry")
    interface RegistryRef {
        fun `is`(key: String): Boolean
    }

    @Remote("javax.swing.JSpinner")
    interface SpinnerRef {
        fun getValue(): Int
        fun setValue(value: Any)
    }

    @Remote("javax.swing.JButton")
    interface ButtonRef {
        fun isEnabled(): Boolean
    }

    @Remote("info.chrzanowski.stickyprojectview.StickyProjectViewSettings", plugin = "sticky-project-view")
    interface StickyProjectViewSettingsRef {
        fun getPreferences(): StickyProjectViewPreferencesRef
        fun updatePreferences(preferences: StickyProjectViewPreferencesRef)
    }

    @Remote("info.chrzanowski.stickyprojectview.StickyProjectViewService", plugin = "sticky-project-view")
    interface StickyProjectViewServiceRef {
        fun initialize()
        fun isAttached(): Boolean
        fun getStickyItemLabels(): List<String>
        fun getStickyPanelHeight(): Int
        fun getVisibleRowLabels(): List<String>
        fun getSelectedPathLabels(): List<String>
        fun selectPath(pathSegments: List<String>): Boolean
    }

    @Remote("com.intellij.openapi.application.ApplicationInfo")
    interface ApplicationInfoRef {
        fun getFullVersion(): String
        fun getBuild(): BuildNumberRef
    }

    @Remote("com.intellij.openapi.util.BuildNumber")
    interface BuildNumberRef {
        fun asString(): String
    }

    @Remote("javax.swing.JTree")
    interface ProjectTreeRef {
        fun isFocusOwner(): Boolean
        fun getVisibleRect(): Rectangle
        fun getRowBounds(row: Int): Rectangle
        fun getRowCount(): Int
        fun getMinSelectionRow(): Int
        fun collapseRow(row: Int)
    }

    @Remote("com.intellij.util.ui.tree.TreeUtil")
    interface TreeUtilRef {
        fun promiseExpandAll(tree: ProjectTreeRef): PromiseRef
    }

    @Remote("org.jetbrains.concurrency.Promise")
    interface PromiseRef {
        fun blockingGet(timeout: Int): Any?
    }

    @Remote("com.intellij.openapi.vfs.LocalFileSystem")
    interface LocalFileSystemRef {
        fun getInstance(): LocalFileSystemRef
        fun findFileByPath(path: String): VirtualFileRef
    }

    @Remote("com.intellij.openapi.vfs.VirtualFile")
    interface VirtualFileRef {
        fun getChildren(): Array<VirtualFileRef>
        fun refresh(asynchronous: Boolean, recursive: Boolean)
    }

    @Remote("java.awt.event.MouseWheelEvent")
    interface MouseWheelEventRef

    @Remote("java.awt.Toolkit")
    interface ToolkitRef {
        fun getDefaultToolkit(): ToolkitRef
        fun getSystemEventQueue(): EventQueueRef
    }

    @Remote("java.awt.EventQueue")
    interface EventQueueRef {
        fun postEvent(event: MouseWheelEventRef)
    }

    private companion object {
        const val PROJECT_NAME = "sticky-project-view-ui"
        val TARGET_DIRECTORY_PATH = arrayOf("src", "main", "kotlin", "demo", "nested", "deeper")
        val TARGET_FILE_PATH = TARGET_DIRECTORY_PATH + "DeepFile120.txt"
    }
}

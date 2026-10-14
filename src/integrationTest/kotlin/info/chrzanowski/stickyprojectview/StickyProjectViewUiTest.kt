package info.chrzanowski.stickyprojectview

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.singleProject
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
import java.nio.file.Path
import java.awt.Rectangle
import java.awt.Point
import java.awt.event.KeyEvent
import java.util.UUID
import kotlin.io.path.createDirectories
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
    fun stickyItemsFollowExpandedProjectViewPath(@TempDir temporaryDirectory: Path) {
        val projectDir = temporaryDirectory.resolve(PROJECT_NAME).createDirectories()
        createProjectStructure(projectDir)

        Starter.newContext(
            "${CurrentTestMethod.hyphenateWithClass()}-${UUID.randomUUID()}",
            TestCase(IdeProductProvider.IU, LocalProjectInfo(projectDir, isReusable = false))
                .withVersion(platformVersion)
        ).apply {
            PluginConfigurator(this).installPluginFromPath(pluginPath)
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
            fun deeperPoint(): Point {
                val labels = stickyService.getStickyItemLabels()
                val bounds = withContext(OnDispatcher.EDT) {
                    nativeTree.getRowBounds(stickyService.getVisibleRowLabels().indexOf("deeper"))
                }
                return Point(bounds.x + bounds.width / 2 - withContext(OnDispatcher.EDT) { nativeTree.getVisibleRect().x },
                    labels.indexOf("deeper") * bounds.height + bounds.height / 2)
            }
            overlay.moveMouse(deeperPoint())
            ui.x("//div[contains(@text, '/src/main/kotlin/demo/nested/deeper')]").waitFound()
                .waitAnyTextsContains(projectDir.resolve(TARGET_DIRECTORY_PATH.joinToString("/")).toString())

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
            treeComponent.moveMouse(Point(150, beforeNativeWheel + stickyService.getStickyPanelHeight() + 80))
            ui.robot.rotateMouseWheel(-3)
            waitFor("native wheel control outside the overlay", timeout = 10.seconds) {
                withContext(OnDispatcher.EDT) { nativeTree.getVisibleRect().y } < beforeNativeWheel
            }
            assertTrue(stickyService.selectPath(TARGET_FILE_PATH.toList()))
            val beforeWheel = withContext(OnDispatcher.EDT) { nativeTree.getVisibleRect().y }
            assertTrue(beforeWheel > 0, "Wheel scenario must start in a vertically scrolled viewport")
            overlay.moveMouse(deeperPoint())
            ui.robot.rotateMouseWheel(-3)
            waitFor("wheel over sticky rows scrolls the native viewport", timeout = 10.seconds) {
                withContext(OnDispatcher.EDT) { nativeTree.getVisibleRect().y } < beforeWheel
            }
        }
    }

    private fun createProjectStructure(projectDir: Path) {
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

    @Remote("info.chrzanowski.stickyprojectview.StickyProjectViewService", plugin = "sticky-project-view")
    interface StickyProjectViewServiceRef {
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
        fun getVisibleRect(): Rectangle
        fun getRowBounds(row: Int): Rectangle
    }

    private companion object {
        const val PROJECT_NAME = "sticky-project-view-ui"
        val TARGET_DIRECTORY_PATH = arrayOf("src", "main", "kotlin", "demo", "nested", "deeper")
        val TARGET_FILE_PATH = TARGET_DIRECTORY_PATH + "DeepFile120.txt"
    }
}

package info.chrzanowski.stickyprojectview

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.toolwindows.projectView
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitForProjectOpen
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class StickyProjectViewUiTest {

    private val pluginPath = Path.of(System.getProperty("path.to.build.plugin"))

    @Test
    fun stickyItemsFollowExpandedProjectViewPath(@TempDir temporaryDirectory: Path) {
        val projectDir = temporaryDirectory.resolve(PROJECT_NAME).createDirectories()
        createProjectStructure(projectDir)

        Starter.newContext(
            CurrentTestMethod.hyphenateWithClass(),
            TestCase(IdeProductProvider.IU, LocalProjectInfo(projectDir, isReusable = false))
        ).apply {
            PluginConfigurator(this).installPluginFromPath(pluginPath)
        }.runIdeWithDriver().useDriverAndCloseIde {
            waitForProjectOpen()
            waitForIndicators(5.minutes)

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

            val targetBecameVisible = runCatching {
                waitFor("deep file to become selected in Project View", timeout = 30.seconds) {
                    stickyService.selectPath(TARGET_FILE_PATH.toList())
                }
            }.isSuccess
            assertTrue(
                targetBecameVisible,
                "Deep file was not selected. Selected path: ${stickyService.getSelectedPathLabels()}, visible rows: ${stickyService.getVisibleRowLabels()}"
            )

            waitFor("sticky items to match expanded directories", timeout = 30.seconds) {
                stickyService.getStickyItemLabels().takeLast(TARGET_DIRECTORY_PATH.size) == TARGET_DIRECTORY_PATH.asList() &&
                    stickyService.getStickyPanelHeight() > 0
            }

            val expectedStickyItems = TARGET_DIRECTORY_PATH.asList()
            val actualStickyItems = stickyService.getStickyItemLabels()
            assertEquals(expectedStickyItems, actualStickyItems.takeLast(expectedStickyItems.size))
            assertTrue(stickyService.getStickyPanelHeight() > 0)
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

    private companion object {
        const val PROJECT_NAME = "sticky-project-view-ui"
        val TARGET_DIRECTORY_PATH = arrayOf("src", "main", "kotlin", "demo", "nested", "deeper")
        val TARGET_FILE_PATH = TARGET_DIRECTORY_PATH + "DeepFile120.txt"
    }
}

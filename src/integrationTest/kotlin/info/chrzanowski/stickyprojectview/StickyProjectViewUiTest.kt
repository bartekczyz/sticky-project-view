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

    private companion object {
        const val PROJECT_NAME = "sticky-project-view-ui"
        val TARGET_DIRECTORY_PATH = arrayOf("src", "main", "kotlin", "demo", "nested", "deeper")
        val TARGET_FILE_PATH = TARGET_DIRECTORY_PATH + "DeepFile120.txt"
    }
}

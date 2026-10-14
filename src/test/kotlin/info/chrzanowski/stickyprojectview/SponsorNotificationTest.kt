package info.chrzanowski.stickyprojectview

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JLabel

class SponsorNotificationTest : BasePlatformTestCase() {
    fun testUnitTestStartupDoesNotRecordUsage() {
        val state = service<SponsorNotificationState>()
        val original = state.state
        runBlocking { SponsorNotificationActivity().execute(project) }
        assertEquals(original, state.state)
    }

    fun testNotificationOffersAllProvidersAndDismissalChoices() {
        val notification = SponsorNotificationActivity().createNotification(SponsorNotificationState())
        try {
            assertEquals(MyBundle.message("sponsor.notification.title"), notification.title)
            assertEquals(
                listOf("support.github", "support.kofi", "support.paypal", "sponsor.notification.later", "sponsor.notification.dismiss")
                    .map { MyBundle.message(it) },
                notification.actions.map { it.templatePresentation.text },
            )
        } finally {
            notification.expire()
        }
    }

    fun testSponsorActionIsRegisteredAndDumbAware() {
        val action = requireNotNull(ActionManager.getInstance().getAction("StickyProjectView.Sponsor"))
        assertTrue(action is SponsorStickyProjectViewAction)
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
    }

    fun testSettingsOfferAllSupportLinks() {
        val configurable = StickyProjectViewConfigurable()
        try {
            val panel = requireNotNull(configurable.createComponent())
            fun descendants(container: Container): List<Component> =
                container.components.flatMap { listOf(it) + if (it is Container) descendants(it) else emptyList() }
            val labels = descendants(panel).mapNotNull {
                when (it) {
                    is JLabel -> it.text
                    is AbstractButton -> it.text
                    else -> null
                }
            }
            for (key in listOf("settings.support", "support.github", "support.kofi", "support.paypal")) {
                assertContainsElements(labels, MyBundle.message(key))
            }
        } finally {
            configurable.disposeUIResources()
        }
    }
}
package info.chrzanowski.stickyprojectview

import com.intellij.configurationStore.deserialize
import com.intellij.configurationStore.serialize
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SponsorNotificationStateTest : BasePlatformTestCase() {
    private val firstUse = 1_800_000_000_000L
    private val eligibleAt = firstUse + SponsorNotificationState.MIN_USAGE_MILLIS

    fun testDefaultsAndBothEligibilityThresholds() {
        val state = SponsorNotificationState()
        assertEquals(SponsorNotificationPreferences(), state.state)
        repeat(SponsorNotificationState.MIN_SESSIONS - 1) {
            assertFalse(state.recordSessionAndClaimNotification(firstUse))
        }
        assertTrue(state.recordSessionAndClaimNotification(eligibleAt))
        assertEquals(firstUse, state.state.firstUsedAt)
        assertEquals(SponsorNotificationState.MIN_SESSIONS, state.state.sessions)
    }

    fun testEnoughSessionsCannotBypassMinimumAge() {
        val state = SponsorNotificationState()
        repeat(SponsorNotificationState.MIN_SESSIONS) {
            assertFalse(state.recordSessionAndClaimNotification(firstUse))
        }
        assertFalse(state.recordSessionAndClaimNotification(eligibleAt - 1))
        assertTrue(state.recordSessionAndClaimNotification(eligibleAt))
    }

    fun testEnoughAgeCannotBypassMinimumSessions() {
        val state = SponsorNotificationState()
        assertFalse(state.recordSessionAndClaimNotification(firstUse))
        assertFalse(state.recordSessionAndClaimNotification(eligibleAt))
        assertEquals(firstUse, state.state.firstUsedAt)
        assertFalse(state.recordSessionAndClaimNotification(firstUse - 1))
    }

    fun testClaimSnoozesBeforeAnotherProjectCanNotify() {
        val state = SponsorNotificationState()
        state.loadState(SponsorNotificationPreferences(firstUsedAt = firstUse, sessions = 10))
        assertTrue(state.recordSessionAndClaimNotification(eligibleAt))
        val nextReminder = eligibleAt + SponsorNotificationState.SNOOZE_DURATION_MILLIS
        assertEquals(nextReminder, state.state.snoozedUntil)
        assertFalse(state.recordSessionAndClaimNotification(eligibleAt))
        assertFalse(state.recordSessionAndClaimNotification(nextReminder - 1))
        assertTrue(state.recordSessionAndClaimNotification(nextReminder))
        assertEquals(nextReminder + SponsorNotificationState.SNOOZE_DURATION_MILLIS, state.state.snoozedUntil)
    }

    fun testConcurrentProjectsClaimOnlyOneNotification() {
        val state = SponsorNotificationState()
        state.loadState(SponsorNotificationPreferences(firstUsedAt = firstUse, sessions = 10))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val claims = executor.invokeAll(
                List(8) { Callable { state.recordSessionAndClaimNotification(eligibleAt) } }, 5, TimeUnit.SECONDS,
            ).map { it.get() }
            assertEquals(1, claims.count { it })
        } finally {
            executor.shutdownNow()
        }
    }

    fun testManualSnoozeAndPermanentDismissal() {
        val state = SponsorNotificationState()
        state.loadState(SponsorNotificationPreferences(firstUsedAt = firstUse, sessions = 10))
        state.snooze(eligibleAt)
        val nextReminder = eligibleAt + SponsorNotificationState.SNOOZE_DURATION_MILLIS
        assertFalse(state.recordSessionAndClaimNotification(nextReminder - 1))
        assertTrue(state.recordSessionAndClaimNotification(nextReminder))
        state.dismiss()
        val dismissed = state.state
        assertFalse(state.recordSessionAndClaimNotification(nextReminder + 2 * SponsorNotificationState.SNOOZE_DURATION_MILLIS))
        assertEquals(dismissed, state.state)
    }

    fun testSerializationKeepsUsageSnoozeAndDismissal() {
        val state = SponsorNotificationState()
        repeat(10) { state.recordSessionAndClaimNotification(firstUse) }
        state.recordSessionAndClaimNotification(eligibleAt)
        val restored = SponsorNotificationState()
        restored.loadState(deserialize<SponsorNotificationPreferences>(requireNotNull(serialize(state.state))))
        assertEquals(state.state, restored.state)
        assertFalse(restored.recordSessionAndClaimNotification(eligibleAt))
        state.dismiss()
        restored.loadState(deserialize<SponsorNotificationPreferences>(requireNotNull(serialize(state.state))))
        assertFalse(restored.recordSessionAndClaimNotification(eligibleAt + 2 * SponsorNotificationState.SNOOZE_DURATION_MILLIS))
    }

    fun testReminderStateDoesNotRoam() {
        val annotation = SponsorNotificationState::class.java.getAnnotation(State::class.java)
        assertEquals(RoamingType.DISABLED, annotation.storages.single().roamingType)
        assertEquals(SettingsCategory.PLUGINS, annotation.category)
    }
}
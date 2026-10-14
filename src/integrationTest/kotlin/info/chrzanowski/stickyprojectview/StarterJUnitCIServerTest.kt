package info.chrzanowski.stickyprojectview

import com.intellij.ide.starter.ci.NoCIServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StarterJUnitCIServerTest {
    @Test
    fun noReportedIdeErrorsPass() {
        val ciServer = StarterJUnitCIServer()
        assertEquals(NoCIServer.isBuildRunningOnCI, ciServer.isBuildRunningOnCI)
        ciServer.assertNoFailures()
    }

    @Test
    fun reportedIdeErrorsFailJUnitWithAttributableDetails() {
        val ciServer = StarterJUnitCIServer()
        ciServer.reportTestFailure("baseline-launch", "IDE exception", "stack trace", "launch/log/idea.log")

        val error = assertThrows(AssertionError::class.java) { ciServer.assertNoFailures() }
        listOf("baseline-launch", "IDE exception", "stack trace", "launch/log/idea.log").forEach {
            assertTrue(error.message.orEmpty().contains(it))
        }
    }
}
package info.chrzanowski.stickyprojectview

import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.CopyOnWriteArrayList

internal class StarterJUnitCIServer : CIServer by NoCIServer {
    private val failures = CopyOnWriteArrayList<String>()

    override fun reportTestFailure(testName: String, message: String, details: String, linkToLogs: String?) {
        failures += "$testName: $message\n$details\nLogs: $linkToLogs"
    }

    fun assertNoFailures() {
        assertTrue(failures.isEmpty(), failures.joinToString("\n\n"))
    }
}
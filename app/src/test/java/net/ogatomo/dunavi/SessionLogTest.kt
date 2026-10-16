package net.ogatomo.dunavi

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun initLog(): File {
        val dir = tmp.newFolder("logs")
        SessionLog.resetForTest(dir)
        return dir
    }

    @Test fun sanitizeMasksCredentialsAndObservedAtShapes() {
        val clean = SessionLog.sanitize(
            "PAP user=bob password=hunter2 apn=example.ne.jp TCP 203.0.113.4:443 ATD09012345678",
        )
        val atClean = SessionLog.sanitize("AT+CGDCONT=1,IP,private")
        assertFalse("bob" in clean)
        assertFalse("hunter2" in clean)
        assertFalse("example.ne.jp" in clean)
        assertFalse("09012345678" in clean)
        assertFalse("203.0.113.4:443" in clean)
        assertTrue("user=***" in clean)
        assertTrue("AT+CGDCONT=<redacted>" in atClean)
        assertTrue("<destination>" in clean)
    }

    @Test fun normalModeKeepsOnlyReadableDiagnosticsAndErrors() {
        initLog()
        SessionLog.beginSession()
        SessionLog.log(LogCategory.AT, "RX", "AT+CGDCONT=1,IP,private")
        SessionLog.diagnostic(LogCategory.AT, message = "AT modem initialized")
        SessionLog.log(LogCategory.ERROR, null, "TCP connection failed")
        SessionLog.endSession()
        SessionLog.awaitIdle()
        val lines = SessionLog.lines.value
        assertTrue(lines.any { it.message == "AT modem initialized" })
        assertTrue(lines.any { it.message == "TCP connection failed" })
        assertFalse(lines.any { "CGDCONT" in it.message })
    }

    @Test fun uiHistoryIsBoundedButFileKeepsWholeSession() {
        val dir = initLog()
        SessionLog.detailed = true
        val id = SessionLog.beginSession()
        repeat(1_000) { SessionLog.log(LogCategory.AT, "RX", "line=$it") }
        SessionLog.awaitIdle()
        assertEquals(750, SessionLog.lines.value.size)
        assertTrue(SessionLog.lines.value.last().message == "line=999")
        val fileText = dir.listFiles()!!.joinToString("\n") { it.readText() }
        assertTrue("line=0" in fileText)
        assertTrue("line=999" in fileText)
        assertEquals(id, SessionLog.lines.value.last().sessionId)
    }

    @Test fun queuedEntriesNeverCrossSessionFiles() {
        val dir = initLog()
        val first = SessionLog.beginSession()
        SessionLog.diagnostic(LogCategory.BT, message = "first marker")
        SessionLog.endSession()
        val second = SessionLog.beginSession()
        SessionLog.diagnostic(LogCategory.BT, message = "second marker")
        SessionLog.endSession()
        SessionLog.awaitIdle()

        val firstText = dir.listFiles()!!.filter { "session-${first.toString().padStart(3, '0')}" in it.name }.joinToString { it.readText() }
        val secondText = dir.listFiles()!!.filter { "session-${second.toString().padStart(3, '0')}" in it.name }.joinToString { it.readText() }
        assertTrue("first marker" in firstText)
        assertFalse("second marker" in firstText)
        assertTrue("second marker" in secondText)
        assertFalse("first marker" in secondText)
    }

    @Test fun pastSessionsSurviveRestartAndIdsRemainMonotonic() {
        val dir = initLog()
        val first = SessionLog.beginSession()
        SessionLog.endSession()
        SessionLog.awaitIdle()
        SessionLog.resetForTest(dir)
        val second = SessionLog.beginSession()
        assertTrue(second > first)
        assertTrue(SessionLog.sessions.value.any { it.id == first })
    }

    @Test fun exportFiltersBySessionAndWarnsForDebugLogs() {
        initLog()
        val normal = SessionLog.beginSession()
        SessionLog.diagnostic(LogCategory.BT, message = "normal marker")
        SessionLog.endSession()
        SessionLog.awaitIdle()
        SessionLog.detailed = true
        val debug = SessionLog.beginSession()
        SessionLog.log(LogCategory.AT, "RX", "AT+CGDCONT=1,IP,private.apn")
        SessionLog.endSession()
        val exports = tmp.newFolder("exports")

        val normalExport = SessionLog.export(exports, normal)
        assertNotNull(normalExport)
        assertFalse(normalExport!!.containsSensitiveDetails)
        assertTrue("normal marker" in normalExport.file.readText())

        val debugExport = SessionLog.export(exports, debug)
        assertNotNull(debugExport)
        assertTrue(debugExport!!.containsSensitiveDetails)
        assertTrue("WARNING:" in debugExport.file.readText())
        assertTrue("session-${debug.toString().padStart(3, '0')}" in debugExport.file.name)
    }

    @Test fun rotationKeepsOnlyNewestFiles() {
        val dir = initLog()
        SessionLog.maxFileBytes = 200
        SessionLog.maxFiles = 3
        SessionLog.detailed = true
        SessionLog.beginSession()
        repeat(30) { SessionLog.log(LogCategory.AT, "RX", "x".repeat(60)) }
        SessionLog.endSession()
        SessionLog.awaitIdle()
        val files = dir.listFiles { file -> file.name.endsWith(".log") }!!.toList()
        assertTrue("expected rotation+prune, files=${files.map { it.name }}", files.size <= 3)
    }

    @Test fun loggingSurvivesWithoutStorageDirectory() {
        SessionLog.resetForTest(null)
        SessionLog.beginSession()
        SessionLog.diagnostic(LogCategory.AT, message = "no dir")
        SessionLog.awaitIdle()
        assertTrue(SessionLog.lines.value.any { it.message == "no dir" })
    }
}

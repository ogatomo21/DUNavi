package net.ogatomo.dunavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtModemTest {
    @Test fun commonCommandsAndDial() {
        val modem = AtModem()
        assertEquals("\r\nOK\r\n", command(modem, "AT").text())
        assertTrue(command(modem, "ATDT*99***1#").dial)
        assertTrue(command(modem, "ATD*99#").dial)
        assertTrue(command(modem, "ATD99#").dial)
        assertTrue(command(modem, "ATD99***2#").dial)
        assertTrue(command(modem, "AT+CGDATA=\"PPP\",3").dial)
        assertEquals("\r\nERROR\r\n", command(modem, "AT+UNKNOWN").text())
    }

    @Test fun echoCanBeDisabled() {
        val modem = AtModem()
        command(modem, "ATE0")
        assertFalse(modem.accept('A'.code).output.isNotEmpty())
    }

    @Test fun dunMandatoryIdentificationAndControlCommandsAreAccepted() {
        val modem = AtModem()
        listOf("AT&C1", "AT&D2", "ATH", "ATL0", "ATM0", "ATO", "ATP", "ATT", "ATX4", "ATS0=0", "ATS3?", "ATS4?", "ATS5?").forEach {
            assertTrue("$it should be accepted", command(modem, it).text().contains("OK"))
        }
        listOf("AT+GCAP", "AT+GMI", "AT+GMM", "AT+GMR").forEach {
            assertFalse("$it should be accepted", command(modem, it).text().contains("ERROR"))
        }
    }

    @Test fun combinedLegacyInitializationCommandIsAccepted() {
        val modem = AtModem()
        assertEquals("\r\nOK\r\n", command(modem, "AT&F0E0V1Q0X4&C1&D2S0=0").text())
        assertFalse(modem.accept('A'.code).output.isNotEmpty())
    }

    @Test fun quietAndNumericResultModesAreStateful() {
        val modem = AtModem()
        assertEquals("", command(modem, "ATQ1").text())
        assertEquals("", command(modem, "AT").text())
        command(modem, "ATQ0")
        assertEquals("\r\nOK\r\n", command(modem, "ATV1").text())
        assertEquals("\r\n0\r\n", command(modem, "ATV0").text())
        assertEquals("\r\n0\r\n", command(modem, "AT").text())
    }

    @Test fun registrationQueriesAndPacketDialFormsAreCompatible() {
        val modem = AtModem()
        assertTrue(command(modem, "AT+CREG?").text().contains("+CREG: 0,1"))
        assertTrue(command(modem, "AT+CGREG?").text().contains("+CGREG: 0,1"))
        assertTrue(command(modem, "ATD*98*1#").dial)
    }

    @Test fun commonPacketDomainInitializationNoOpsAreAccepted() {
        val modem = AtModem()
        listOf("AT+CGQREQ=1", "AT+CGQMIN=1", "AT+CGEQMIN=1", "AT+CGEREP=0", "AT+CGAUTO=0", "AT+CGACT=1,1", "AT+CGCLASS=\"CG\"").forEach {
            assertEquals("\r\nOK\r\n", command(modem, it).text())
        }
        assertEquals("\r\nERROR\r\n", command(modem, "AT+UNSUPPORTED=secret").text())
        assertEquals("\r\nERROR\r\n", command(modem, "AT+CFUN=0").text())
    }

    @Test fun logsRedactApnAndDialParameters() {
        val logs = mutableListOf<String>()
        val modem = AtModem(log = { direction, message -> logs.add("AT $direction: $message") })
        command(modem, "AT+CGDCONT=1,\"IP\",\"private.apn\"")
        command(modem, "ATD09012345678")
        assertTrue(logs.any { it == "AT RX: AT+CGDCONT=<redacted>" })
        assertTrue(logs.any { it == "AT RX: ATDddddddddddd" })
        assertTrue(logs.any { it.startsWith("AT TX: ") })
        assertFalse(logs.any { "private.apn" in it || "09012345678" in it })
    }

    @Test fun packetDataDialFormsStartPpp() {
        val modem = AtModem()
        listOf(
            "ATD*99#",
            "ATDT*99***1#",
            "ATDP*99***1#",
            "ATD*99*1#",
            "ATD*98*1#",
            "ATD99#",
            "ATD99***2#",
            "ATD*991#",
        ).forEach {
            assertTrue("$it should start PPP", command(modem, it).dial)
        }
    }

    @Test fun plainNumericDialStartsPppInLegacyCompatibilityMode() {
        val modem = AtModem()
        // Bluetooth DUN gateways see plain dial strings from legacy handsets / navi units.
        assertTrue(command(modem, "ATD09012345678").dial)
        assertTrue(command(modem, "ATDT0312345678").dial)
        assertTrue(command(modem, "ATD0120,123456").dial)
        assertTrue(command(modem, "ATD09012345678W1234").dial)
        assertTrue(command(modem, "ATD09012345678W").dial)
    }

    @Test fun voiceDialRequestsDoNotStartPpp() {
        val modem = AtModem()
        // Trailing ';' = voice call request, not a data call: answer OK, stay in command mode.
        val result = command(modem, "ATD09012345678;")
        assertFalse(result.dial)
        assertTrue(result.text().contains("OK"))
    }

    @Test fun invalidDialStringsReturnError() {
        val modem = AtModem()
        listOf("ATD", "ATD;", "ATD090;12345678").forEach {
            assertFalse("$it should not start PPP", command(modem, it).dial)
            assertTrue(command(modem, it).text().contains("ERROR"))
        }
    }

    @Test fun auBtSetQueriesAndAssignmentsAreAccepted() {
        val modem = AtModem()
        listOf("AT@AUBTSET?", "AT@AUBTSET=0", "AT@AUBTSET=1", "AT@AUBTSET=5").forEach {
            assertEquals("\r\nOK\r\n", command(modem, it).text())
        }
        assertEquals("\r\nERROR\r\n", command(modem, "AT@UNKNOWN=1").text())
    }

    @Test fun detailedLogModeKeepsFullCommandStructure() {
        val logs = mutableListOf<String>()
        val modem = AtModem(detailed = true, log = { direction, message -> logs.add("AT $direction: $message") })
        assertTrue(command(modem, "ATD09012345678").dial)
        assertTrue(logs.any { it == "AT RX: ATD09012345678" })
        command(modem, "AT+CGDCONT=1,\"IP\",\"private.apn\"")
        // The modem uppercases the command line; detailed mode still shows the full structure.
        assertTrue(logs.any { "PRIVATE.APN" in it })
    }

    @Test fun logsKeepDialShapeButNotDigits() {
        val logs = mutableListOf<String>()
        val modem = AtModem(log = { direction, message -> logs.add("AT $direction: $message") })
        command(modem, "ATD09012345678")
        command(modem, "ATDT031234-5678")
        command(modem, "ATD*99***1#")
        command(modem, "ATD09012345678;")
        assertTrue(logs.any { it == "AT RX: ATDddddddddddd" })
        assertTrue(logs.any { it == "AT RX: ATDTdddddd-dddd" })
        assertTrue(logs.any { it == "AT RX: ATD*99***1#" })
        assertTrue(logs.any { it == "AT RX: ATDddddddddddd;" })
        // Raw dial strings must never reach the log.
        assertFalse(logs.any { "09012345678" in it })
    }

    private fun command(modem: AtModem, value: String): ModemResult {
        value.forEach { modem.accept(it.code) }
        return modem.accept(13)
    }

    private fun ModemResult.text() = output.toString(Charsets.US_ASCII)
}

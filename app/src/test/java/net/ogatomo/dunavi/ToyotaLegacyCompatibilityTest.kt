package net.ogatomo.dunavi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToyotaLegacyCompatibilityTest {
    @Test fun observedHarrierScenariosReachConnectWithoutError() {
        scenarioNames().forEach { name ->
            val logs = mutableListOf<String>()
            val modem = AtModem(log = { direction, message -> logs += "$direction $message" })
            val commands = resource("testFixtures/toyota/$name")
                .lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()

            commands.dropLast(1).forEach { command ->
                val result = send(modem, command)
                assertFalse("$name: $command returned ERROR", "ERROR" in result.text())
                assertFalse("$name: $command dialled too early", result.dial)
            }
            val dial = send(modem, commands.last())
            assertTrue("$name: final ATD did not enter PPP", dial.dial)
            assertTrue("$name: final ATD did not return CONNECT", "CONNECT" in dial.text())
            assertTrue("$name: proprietary command was not exercised", commands.any { it.startsWith("AT@AUBTSET") })
            assertFalse("$name: APN leaked", logs.any { "fixture.invalid" in it })
            assertFalse("$name: dial digits leaked", logs.any { "0000000000" in it })
            assertTrue("$name: CGDCONT structure lost", commands.none { it.startsWith("AT+CGDCONT") } || logs.any { "AT+CGDCONT=<redacted>" in it })
            assertTrue("$name: dial structure lost", logs.any { "ATDdddddddddd" in it })
        }
    }

    private fun scenarioNames() = resource("testFixtures/toyota/scenarios.txt")
        .lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()

    private fun resource(path: String): String = requireNotNull(javaClass.classLoader?.getResource(path)) { path }.readText()

    private fun send(modem: AtModem, command: String): ModemResult {
        command.forEach { modem.accept(it.code) }
        return modem.accept(13)
    }

    private fun ModemResult.text() = output.toString(Charsets.US_ASCII)
}

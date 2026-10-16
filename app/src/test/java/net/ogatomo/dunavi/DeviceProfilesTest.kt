package net.ogatomo.dunavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceProfilesTest {
    @Test fun atProfileKeepsStructureWithoutValues() {
        assertEquals("ATZ", atCommandName("ATZ"))
        assertEquals("AT@AUBTSET?", atCommandName("AT@AUBTSET?"))
        assertEquals("AT@AUBTSET=", atCommandName("AT@AUBTSET=0"))
        assertEquals("AT+CGDCONT=", atCommandName("AT+CGDCONT=10,\"PPP\",\"AUTO.G-BOOK.COM\""))
        assertEquals("AT+CGEQMIN=", atCommandName("AT+CGEQMIN=10"))
        assertEquals("ATD", atCommandName("ATD*99***10#"))
        assertNull(atCommandName("OK"))
    }
}

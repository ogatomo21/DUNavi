package net.ogatomo.dunavi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DunServiceNotificationTest {
    @Test fun identicalRenderedContentCanBeSkipped() {
        val state = DunUiState(phase = DunPhase.CONNECTED, device = "CAR MULTIMEDIA", sessionStage = DunSessionStage.NETWORK)

        assertEquals(notificationContent(state), notificationContent(state.copy(message = "表示されない変更")))
    }

    @Test fun visibleStateAndTrafficChangesRequireAnUpdate() {
        val state = DunUiState(phase = DunPhase.CONNECTED, device = "CAR MULTIMEDIA", sessionStage = DunSessionStage.RFCOMM)

        assertNotEquals(notificationContent(state), notificationContent(state.copy(sessionStage = DunSessionStage.NETWORK)))
        assertNotEquals(notificationContent(state), notificationContent(state.copy(uploaded = 1)))
    }
}

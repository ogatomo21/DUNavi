package net.ogatomo.dunavi

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import net.ogatomo.dunavi.ui.DUNaviTheme
import net.ogatomo.dunavi.ui.SettingSwitch
import net.ogatomo.dunavi.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingRowInvokesCallbackExactlyOncePerTap() {
        var calls = 0
        val checked = mutableStateOf(false)
        compose.setContent {
            DUNaviTheme {
                SettingSwitch("自動待受", "端末起動後に待受", checked.value) { checked.value = it; calls++ }
            }
        }
        compose.onNodeWithText("自動待受").performClick()
        compose.runOnIdle { assertEquals(1, calls); assertEquals(true, checked.value) }
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertEquals(2, calls); assertEquals(false, checked.value) }
    }

    @Test fun connectedSessionDisablesDebugSetting() {
        compose.setContent {
            DUNaviTheme {
                SettingsScreen(false, {}, false, false, {}, {}, {}, emptyList(), false, null, {}, rememberScrollState())
            }
        }
        compose.onNode(isToggleable() and hasText("デバッグモード")).performScrollTo().assertIsNotEnabled()
    }
}

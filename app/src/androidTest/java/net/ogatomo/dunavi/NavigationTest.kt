package net.ogatomo.dunavi

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun resetState() = compose.runOnUiThread { DunState.stopped() }
    @After fun cleanup() = compose.runOnUiThread { DunState.stopped() }

    @Test fun mainDestinationsAndLicenseBackStackWork() {
        compose.onNodeWithText("待受を開始").assertIsDisplayed()
        compose.onNode(hasText("ログ") and hasClickAction()).performClick()
        compose.onNodeWithText("現在を書き出す").assertExists()
        compose.onNode(hasText("設定") and hasClickAction()).performClick()
        compose.onNodeWithText("このアプリについて").performScrollTo().performClick()
        compose.onNodeWithText("net.ogatomo.dunavi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ライセンス").performScrollTo().performClick()
        compose.onNodeWithText("DUNavi").performClick()
        compose.onNodeWithText("Permission is hereby granted", substring = true).performScrollTo().assertIsDisplayed()
        repeat(3) { compose.onNodeWithContentDescription("戻る").performClick() }
        compose.onNodeWithText("このアプリについて").assertIsDisplayed()
        compose.onNode(hasText("ホーム") and hasClickAction()).performClick()
        compose.onNodeWithText("待受を開始").assertIsDisplayed()
    }

    @Test fun infoPageSurvivesActivityRecreation() {
        compose.onNode(hasText("設定") and hasClickAction()).performClick()
        compose.onNodeWithText("このアプリについて").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("バージョン情報").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("戻る").performClick()
        compose.onNodeWithText("このアプリについて").assertIsDisplayed()
    }
}

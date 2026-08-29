package dev.nytweetdeck.android

import android.util.LayoutDirection
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import dev.nytweetdeck.android.ui.AppLocaleController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocaleUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun arabicSwitchAppliesRtlAndCanReturnToJapaneseOnAquos() {
        try {
            composeRule.activity.runOnUiThread {
                AppLocaleController.apply(composeRule.activity, "ar")
            }
            composeRule.waitUntil(5_000) {
                AppLocaleController.currentLanguageTag(composeRule.activity) == "ar"
            }
            assertEquals(
                LayoutDirection.RTL,
                AppLocaleController.localizedContext(composeRule.activity)
                    .resources.configuration.layoutDirection,
            )
        } finally {
            composeRule.activity.runOnUiThread {
                AppLocaleController.apply(composeRule.activity, "ja")
            }
        }
    }
}

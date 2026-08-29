package dev.nytweetdeck.android

import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.auth.LoginActivity
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginSecurityUiTest {
    @Test
    fun loginWindowDisablesScreenshotsAndRecentTaskCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, LoginActivity::class.java).apply {
            putExtra(LoginActivity.EXTRA_PROFILE_NAME, "secure-login-test")
        }
        ActivityScenario.launch<LoginActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
                )
            }
        }
    }
}

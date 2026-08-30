package dev.nytweetdeck.android

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconUiTest {
    @Test
    fun launcherIconUsesSeparateAdaptiveLayersOnAquos() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val icon = context.packageManager.getApplicationIcon(context.packageName)

        assertTrue(icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
    }
}

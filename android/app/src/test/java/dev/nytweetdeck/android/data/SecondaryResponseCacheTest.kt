package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.xapi.GraphQlExecutor
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SecondaryResponseCacheTest {
    private fun account(id: String = "7") = AccountSecrets(id, id, "fixture", "Fixture", "bearer", "auth", "csrf", "profile")

    @Test fun secondaryCachesAreSeparatedByAccountAndLanguageAndOnlyStoreInitialPages() {
        val cache = TimelineCache(Files.createTempDirectory("nytd-secondary").toFile())
        val executor = GraphQlExecutor { _, _, _, _ -> "{}" }
        val notifications = NotificationRepository(executor, cache = cache)
        val trends = TrendRepository(executor, cache = cache)
        notifications.load(account(), language = "ja")
        trends.load(account(), language = "ja")
        assertNotNull(notifications.cached(account(), "ja"))
        assertNotNull(trends.cached(account(), "ja"))
        assertNull(notifications.cached(account("8"), "ja"))
        assertNull(trends.cached(account(), "en"))
        notifications.load(account(), cursor = "next", language = "en")
        trends.load(account(), cursor = "next", language = "en")
        assertNull(notifications.cached(account(), "en"))
        assertNull(trends.cached(account(), "en"))
    }
}

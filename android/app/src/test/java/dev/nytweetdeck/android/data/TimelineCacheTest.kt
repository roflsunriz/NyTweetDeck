package dev.nytweetdeck.android.data

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TimelineCacheTest {
    @Test
    fun roundTripsWithoutPuttingAccountIdInFileName() {
        val directory = Files.createTempDirectory("nytd-cache").toFile()
        val cache = TimelineCache(directory)

        cache.write("12345", "homeForYou", null, "{\"data\":true}")

        assertEquals("{\"data\":true}", cache.read("12345", "homeForYou", null))
        val name = requireNotNull(directory.listFiles()).single().name
        assertEquals(false, name.contains("12345"))
    }

    @Test
    fun invalidOrOversizedEntriesAreIgnoredOrRejected() {
        val directory = Files.createTempDirectory("nytd-cache").toFile()
        val cache = TimelineCache(directory)

        assertNull(cache.read("1", "homeForYou", null))
        assertThrows(IllegalArgumentException::class.java) {
            cache.write("1", "homeForYou", null, "x".repeat(TimelineCache.MAX_CACHE_ENTRY_BYTES + 1))
        }
    }
}

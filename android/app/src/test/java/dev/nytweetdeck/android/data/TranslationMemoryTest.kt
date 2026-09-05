package dev.nytweetdeck.android.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class TranslationMemoryTest {
    @Test fun retainsSuccessEvictsLeastRecentlyUsedAndSeparatesKeys() {
        val memory = TranslationMemory<Triple<String, String, String>, String>(2)
        val japanese = Triple("account", "55", "ja")
        val french = Triple("account", "55", "fr")
        val otherAccount = Triple("other", "55", "ja")
        assertEquals("Japanese", memory.getOrLoad(japanese) { "Japanese" })
        memory.getOrLoad(french) { "French" }
        assertEquals("Japanese", memory.getOrLoad(japanese) { error("cached") })
        memory.getOrLoad(otherAccount) { "Other" }
        assertEquals("Japanese", memory.getOrLoad(japanese) { error("cached") })
        assertEquals("Reload", memory.getOrLoad(french) { "Reload" })
        assertEquals(2, memory.size)
    }

    @Test fun unavailableAndFailuresCanBeRetried() {
        val memory = TranslationMemory<String, String?>(2)
        assertNull(memory.getOrLoad("note", cacheable = { it != null }) { null })
        assertEquals(0, memory.size)
        assertThrows(IllegalStateException::class.java) {
            memory.getOrLoad("note") { error("temporary") }
        }
        assertEquals(0, memory.inFlight)
        assertEquals("Translated", memory.getOrLoad("note") { "Translated" })
    }

    @Test fun concurrentReadersSharePendingResultAndLaterReadersReuseIt() {
        val memory = TranslationMemory<String, String>(2)
        val executor = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(1)
        val joined = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val first = executor.submit<String> {
                memory.getOrLoad("same") {
                    calls.incrementAndGet()
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    "Translated"
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val second = executor.submit<String> {
                memory.getOrLoad("same", onJoin = { joined.countDown() }) { error("duplicate") }
            }
            assertTrue(joined.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals("Translated", first.get(5, TimeUnit.SECONDS))
            assertEquals("Translated", second.get(5, TimeUnit.SECONDS))
            assertEquals("Translated", memory.getOrLoad("same") { error("cached") })
            assertEquals(1, calls.get())
            assertEquals(0, memory.inFlight)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}

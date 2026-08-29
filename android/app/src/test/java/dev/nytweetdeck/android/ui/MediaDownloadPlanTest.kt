package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDownloadPlanTest {
    @Test
    fun acceptsOnlyCredentialFreeOfficialHttpsMediaAndCreatesStableNames() {
        val plan = planMediaDownloads(
            "123",
            listOf(
                media("https://pbs.twimg.com/media/photo.JPEG?format=jpg"),
                media("https://video.twimg.com/ext_tw_video/clip.mp4"),
                media("https://example.com/secret.jpg"),
                media("https://token@pbs.twimg.com/media/credential.jpg"),
                media("http://pbs.twimg.com/media/plain.jpg"),
                media("https://pbs.twimg.com:443/media/port.jpg"),
                media("https://pbs.twimg.com/media/fragment.jpg#x"),
            ),
        )

        assertEquals(2, plan.size)
        assertEquals("NyTweetDeck-123-1.jpeg", plan[0].destinationFileName)
        assertEquals("NyTweetDeck-123-2.mp4", plan[1].destinationFileName)
        assertTrue(plan.all { it.url.startsWith("https://") })
    }

    @Test
    fun rejectsInvalidPostIdentityAndUnsafeExtensionFallsBackToBin() {
        assertTrue(planMediaDownloads("../123", listOf(media("https://pbs.twimg.com/a.jpg"))).isEmpty())
        val plan = planMediaDownloads("9", listOf(media("https://pbs.twimg.com/media/file.toolong")))
        assertEquals("NyTweetDeck-9-1.bin", plan.single().destinationFileName)
    }

    private fun media(url: String) = Media("id-$url", "photo", url, null)
}

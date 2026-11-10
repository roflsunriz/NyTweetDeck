package dev.nytweetdeck.android.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import dev.nytweetdeck.android.model.*
import dev.nytweetdeck.android.security.verifiedExternalHttpsUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal data class MediaWarmRequest(val url: String, val video: Boolean)

internal fun mediaWarmRequests(posts: List<Post>, quality: VideoQuality): List<MediaWarmRequest> =
    posts.flatMap { post -> post.media + post.quotedPost?.media.orEmpty() }.flatMap { media ->
        val image = if (media.type == "photo") media.url ?: media.previewUrl else media.previewUrl
        val video = if (media.type != "photo") selectVideoUrl(media, quality) else null
        listOfNotNull(image?.let { MediaWarmRequest(it, false) }, video?.let { MediaWarmRequest(it, true) })
    }.mapNotNull { request ->
        verifiedExternalHttpsUrl(request.url, setOf("twimg.com"))?.let { request.copy(url = it) }
    }.distinctBy { it.url }.take(8)

/** Shares Coil and Media3 caches with visible media. Video warming is limited to 512 KiB. */
@OptIn(UnstableApi::class)
internal class MediaPrefetch(context: Context) {
    private val context = context.applicationContext
    private val attempted = LinkedHashMap<String, Long>()
    private val recent = ArrayDeque<Long>()

    suspend fun warm(posts: List<Post>, quality: VideoQuality) {
        for (request in mediaWarmRequests(posts, quality)) {
            val now = System.nanoTime() / 1_000_000
            while (recent.firstOrNull()?.let { now - it >= 60_000 } == true) recent.removeFirst()
            if (recent.size >= 8) return
            if (attempted[request.url]?.let { now - it < 120_000 } == true) continue
            recent.addLast(now)
            attempted[request.url] = now
            while (attempted.size > 128) attempted.remove(attempted.keys.first())
            try {
                if (request.video) {
                    withContext(Dispatchers.IO) {
                        val writer = CacheWriter(
                            CachedVideoPlayback.dataSourceFactory(context).createDataSource(),
                            DataSpec.Builder().setUri(request.url).setLength(512L * 1024).build(),
                            null, null,
                        )
                        try { runInterruptible { writer.cache() } } finally { writer.cancel() }
                    }
                } else {
                    SingletonImageLoader.get(context).execute(
                        ImageRequest.Builder(context).data(request.url).size(512).build(),
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A failed warm-up is retried by normal visible loading. */ }
        }
    }
}

internal fun nearbyMediaPosts(state: DeckUiState, visibleIds: Set<String>): List<Post> {
    val posts = state.timelines.values.flatMap { it.posts } +
        state.notifications.values.flatMap { it.page?.posts.orEmpty() }
    val start = posts.indexOfFirst { it.id in visibleIds }
    return if (start < 0) emptyList() else posts.drop(start).take(4)
}

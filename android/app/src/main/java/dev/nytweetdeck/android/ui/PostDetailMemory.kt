package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.PostDetailPage
import dev.nytweetdeck.android.xapi.XApiException
import kotlinx.coroutines.*

internal data class DetailMemoryKey(val accountId: String, val postId: String, val language: String, val sort: String)

/** At most 24 two-minute pages; six speculative requests per minute and one active prefetch. */
internal class PostDetailMemory(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Entry(val page: PostDetailPage, val timestamp: Long)
    private val pages = LinkedHashMap<DetailMemoryKey, Entry>(24, .75f, true)
    private val requests = mutableMapOf<DetailMemoryKey, Deferred<PostDetailPage>>()
    private val speculativeTimes = ArrayDeque<Long>()
    private var blockedUntil = 0L

    @Synchronized
    fun cached(key: DetailMemoryKey): PostDetailPage? {
        val entry = pages[key] ?: return null
        if (now() - entry.timestamp >= 120_000) { pages.remove(key); return null }
        return entry.page
    }

    fun request(key: DetailMemoryKey, load: () -> PostDetailPage): Deferred<PostDetailPage> {
        val completion = synchronized(this) {
            requests[key]?.let { return it }
            CompletableDeferred<PostDetailPage>().also { requests[key] = it }
        }
        scope.launch(dispatcher) {
            try {
                val page = load()
                synchronized(this@PostDetailMemory) {
                    pages[key] = Entry(page, now())
                    while (pages.size > 24) pages.remove(pages.keys.first())
                }
                completion.complete(page)
            } catch (failure: Exception) {
                if (failure is XApiException && failure.statusCode == 429) synchronized(this@PostDetailMemory) {
                    blockedUntil = now() + 60_000
                }
                completion.completeExceptionally(failure)
            } finally { synchronized(this@PostDetailMemory) { requests.remove(key) } }
        }.invokeOnCompletion { failure ->
            if (failure != null) completion.completeExceptionally(failure)
        }
        return completion
    }

    @Synchronized
    fun prefetch(key: DetailMemoryKey, load: () -> PostDetailPage) {
        val currentTime = now()
        while (speculativeTimes.firstOrNull()?.let { currentTime - it >= 60_000 } == true) {
            speculativeTimes.removeFirst()
        }
        if (currentTime < blockedUntil || cached(key) != null || requests.isNotEmpty() || speculativeTimes.size >= 6) return
        speculativeTimes.addLast(currentTime)
        // A speculative failure is observed without replacing visible content or surfacing a dialog.
        val result = request(key, load)
        scope.launch(dispatcher) { runCatching { result.await() } }
    }

}

package dev.nytweetdeck.android.data

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** Process-local, bounded successful results. Concurrent readers share only the same request. */
internal class TranslationMemory<K, V>(private val capacity: Int) {
    private val values = LinkedHashMap<K, V>(16, 0.75f, true)
    private val pending = mutableMapOf<K, CompletableFuture<V>>()

    init { require(capacity > 0) }

    val size: Int get() = synchronized(this) { values.size }
    val inFlight: Int get() = synchronized(this) { pending.size }

    fun getOrLoad(
        key: K,
        cacheable: (V) -> Boolean = { true },
        onHit: () -> Unit = {},
        onJoin: () -> Unit = {},
        load: () -> V,
    ): V {
        val future: CompletableFuture<V>
        val owner: Boolean
        synchronized(this) {
            if (values.containsKey(key)) {
                onHit()
                return values.getValue(key)
            }
            val existing = pending[key]
            owner = existing == null
            future = existing ?: CompletableFuture<V>().also { pending[key] = it }
        }
        if (!owner) {
            onJoin()
            return try { future.join() } catch (exception: CompletionException) {
                throw exception.cause ?: exception
            }
        }
        try {
            val value = load()
            synchronized(this) {
                if (cacheable(value)) {
                    values[key] = value
                    while (values.size > capacity) {
                        val iterator = values.entries.iterator()
                        iterator.next()
                        iterator.remove()
                    }
                }
                future.complete(value)
                pending.remove(key)
            }
            return value
        } catch (failure: Throwable) {
            synchronized(this) {
                future.completeExceptionally(failure)
                pending.remove(key)
            }
            throw failure
        }
    }
}

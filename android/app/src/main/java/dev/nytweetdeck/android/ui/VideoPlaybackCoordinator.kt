package dev.nytweetdeck.android.ui

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * 同時に再生される動画を常に1つに保つ。再生要素は複数あってよいが、
 * 実際に音と映像を出すのは最後に再生を始めた1つだけにする。
 */
object VideoPlaybackCoordinator {
    private val activeId = AtomicReference<String?>(null)
    private val listeners = CopyOnWriteArrayList<(String?) -> Unit>()

    fun current(): String? = activeId.get()

    fun claim(id: String) {
        if (activeId.getAndSet(id) == id) return
        listeners.forEach { it(id) }
    }

    fun release(id: String) {
        if (activeId.compareAndSet(id, null)) {
            listeners.forEach { it(null) }
        }
    }

    fun observe(listener: (String?) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    internal fun resetForTesting() {
        activeId.set(null)
        listeners.clear()
    }
}

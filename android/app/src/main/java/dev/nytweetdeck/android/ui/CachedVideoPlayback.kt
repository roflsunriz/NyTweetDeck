package dev.nytweetdeck.android.ui

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

private const val VIDEO_CACHE_BYTES = 256L * 1024L * 1024L
private const val MIN_BUFFER_MS = 15_000
private const val MAX_BUFFER_MS = 60_000
private const val PLAYBACK_BUFFER_MS = 1_000
private const val REBUFFER_MS = 2_000

/** App-wide cache-backed player factory shared by inline and detailed video playback. */
@UnstableApi
internal object CachedVideoPlayback {
    @Volatile
    private var cache: SimpleCache? = null

    fun createPlayer(context: Context): ExoPlayer {
        val appContext = context.applicationContext
        val upstream = DefaultDataSource.Factory(
            appContext,
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(false)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000),
        )
        val cacheDataSource = CacheDataSource.Factory()
            .setCache(getCache(appContext))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(appContext)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(appContext).setDataSourceFactory(cacheDataSource),
            )
            .build()
    }

    @Synchronized
    private fun getCache(context: Context): SimpleCache = cache ?: SimpleCache(
        context.cacheDir.resolve("video-playback"),
        LeastRecentlyUsedCacheEvictor(VIDEO_CACHE_BYTES),
        StandaloneDatabaseProvider(context),
    ).also { cache = it }
}

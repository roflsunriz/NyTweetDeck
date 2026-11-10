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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

private const val VIDEO_CACHE_BYTES = 256L * 1024L * 1024L
private const val MIN_BUFFER_MS = 15_000
private const val MAX_BUFFER_MS = 60_000
private const val PLAYBACK_BUFFER_MS = 1_000
private const val REBUFFER_MS = 2_000
private const val VIDEO_BUFFER_BYTES = 16 * 1024 * 1024

/** App-wide cache-backed player factory shared by inline and detailed video playback. */
@UnstableApi
internal object CachedVideoPlayback {
    @Volatile
    private var cache: SimpleCache? = null

    fun createPlayer(context: Context): ExoPlayer {
        val appContext = context.applicationContext
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS)
            .setTargetBufferBytes(VIDEO_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return ExoPlayer.Builder(
            appContext,
            DefaultRenderersFactory(appContext).setEnableDecoderFallback(true),
        )
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(appContext).setDataSourceFactory(dataSourceFactory(appContext)),
            )
            .build()
    }

    internal fun dataSourceFactory(context: Context): CacheDataSource.Factory {
        val appContext = context.applicationContext
        val upstream = DefaultDataSource.Factory(
            appContext,
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(false)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000),
        )
        return CacheDataSource.Factory()
            .setCache(getCache(appContext))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    @Synchronized
    private fun getCache(context: Context): SimpleCache = cache ?: SimpleCache(
        context.cacheDir.resolve("video-playback"),
        LeastRecentlyUsedCacheEvictor(VIDEO_CACHE_BYTES),
        StandaloneDatabaseProvider(context),
    ).also { cache = it }
}

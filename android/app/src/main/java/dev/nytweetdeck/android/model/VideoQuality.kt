package dev.nytweetdeck.android.model

fun selectVideoUrl(media: Media, quality: VideoQuality): String? {
    val variants = media.variants
        .filter { it.url.isNotBlank() }
        .sortedBy { it.bitrate ?: 0L }
    if (variants.isEmpty()) return media.url
    val index = when (quality) {
        VideoQuality.AUTO,
        VideoQuality.HIGH,
        -> variants.lastIndex
        VideoQuality.MEDIUM -> (variants.lastIndex / 2)
        VideoQuality.LOW -> 0
    }
    return variants.getOrNull(index)?.url ?: media.url
}

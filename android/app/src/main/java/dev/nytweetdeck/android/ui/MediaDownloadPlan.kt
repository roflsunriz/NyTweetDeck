package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.Media
import java.net.URI
import java.util.Locale

internal data class PlannedMediaDownload(
    val url: String,
    val destinationFileName: String,
)

internal fun planMediaDownloads(postId: String, media: List<Media>): List<PlannedMediaDownload> {
    if (!postId.matches(Regex("[0-9]{1,30}"))) return emptyList()
    return media.mapNotNull { item -> item.url ?: item.previewUrl }
        .mapNotNull(::verifiedDownloadUri)
        .mapIndexed { index, uri ->
            val extension = uri.path.orEmpty().substringAfterLast('.', "bin")
                .lowercase(Locale.ROOT)
                .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
                ?: "bin"
            PlannedMediaDownload(
                url = uri.toASCIIString(),
                destinationFileName = "NyTweetDeck-$postId-${index + 1}.$extension",
            )
        }
}

private fun verifiedDownloadUri(value: String): URI? = runCatching {
    URI(value).takeIf { uri ->
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port == -1 &&
            uri.fragment == null &&
            uri.host?.lowercase(Locale.ROOT) in DOWNLOAD_MEDIA_HOSTS
    }
}.getOrNull()

private val DOWNLOAD_MEDIA_HOSTS = setOf("pbs.twimg.com", "video.twimg.com")

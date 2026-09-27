package dev.nytweetdeck.android.model

import android.net.Uri
import java.time.Instant

data class ComposerSubmission(
    val text: String,
    val mediaUris: List<Uri> = emptyList(),
    val poll: ComposerPoll? = null,
    val scheduledAt: Instant? = null,
    val place: ComposerPlace? = null,
    val paidPartnership: Boolean = false,
    val aiGenerated: Boolean = false,
)

data class ComposerPoll(val choices: List<String>, val durationMinutes: Int)

data class ComposerPlace(
    val id: String,
    val name: String,
    val country: String?,
    val geoSearchRequestId: String? = null,
)

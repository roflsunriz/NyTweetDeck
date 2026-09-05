package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.model.CommunityNote
import dev.nytweetdeck.android.text.hasTranslatableText
import java.util.Locale

internal fun isCommunityNoteTranslationCandidate(note: CommunityNote, target: String): Boolean {
    if (note.noteId == null || !hasTranslatableText(note.text.orEmpty())) return false
    val source = note.language?.lowercase(Locale.ROOT)?.substringBefore('-')
    val destination = target.lowercase(Locale.ROOT).substringBefore('-')
    // X's translatable flag describes the request language, not the independently selected target.
    return source.isNullOrBlank() || source == "und" || source != destination
}

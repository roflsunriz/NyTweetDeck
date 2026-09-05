package dev.nytweetdeck.android

import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.AccountStore
import dev.nytweetdeck.android.data.CommunityNoteRepository
import dev.nytweetdeck.android.data.PostTranslationRepository
import dev.nytweetdeck.android.data.XCommunityNoteTranslationEndpoint
import dev.nytweetdeck.android.data.XPostTranslationEndpoint
import dev.nytweetdeck.android.model.PostTranslationResult
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.NotificationResponseParser
import dev.nytweetdeck.android.xapi.XApiEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit IDs opt in to X translation; no IDs, body text or credentials are logged. */
class LiveTranslationMemoryTest {
    @Test fun notesAndPostsReuseSuccessfulLiveResults() {
        val args = InstrumentationRegistry.getArguments()
        val noteId = args.getString("noteId").orEmpty()
        val postId = args.getString("postId").orEmpty()
        val target = args.getString("noteTarget", "ja")
        assumeTrue(Regex("[0-9]{1,24}").matches(noteId) && Regex("[0-9]{1,24}").matches(postId))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountStore(context.noBackupFilesDir.resolve("accounts/accounts.json"))
        val account = store.accountSecrets().first { it.accountId == store.selectedAccountId() }
        val environment = XApiEnvironment(context)
        var noteCalls = 0
        var liveNoteCalls = 0
        var bundled = false
        val notes = CommunityNoteRepository(
            GraphQlExecutor { credentials, purpose, variables, language ->
                noteCalls++
                environment.graphQlClient().execute(credentials, purpose, variables, language).also {
                    bundled = NotificationResponseParser().parseCommunityNote(it, noteId, language).translation != null
                }
            },
            liveTranslation = XCommunityNoteTranslationEndpoint { saved, id, language ->
                liveNoteCalls++
                environment.translateCommunityNote(saved, id, language)
            },
        )
        val firstNote = notes.translateNote(account, noteId, target)
        assertNotNull(firstNote.translation)
        assertTrue(firstNote.translation!!.text.isNotBlank())
        assertSame(firstNote, notes.translateNote(account, noteId, target))
        assertEquals(1, noteCalls)
        assertEquals(if (bundled) 0 else 1, liveNoteCalls)
        var livePostCalls = 0
        val posts = PostTranslationRepository(XPostTranslationEndpoint { saved, id, source, language ->
            livePostCalls++
            environment.restClient().translatePost(saved, id, source, language)
        })
        val firstPost = posts.translate(account, postId, "en", "ja") as PostTranslationResult.Translated
        val secondPost = posts.translate(account, postId, "en", "ja") as PostTranslationResult.Translated
        assertTrue(firstPost.translation.text.isNotBlank())
        assertSame(firstPost.translation, secondPost.translation)
        assertEquals(1, livePostCalls)
        println("LIVE_MEMORY noteFallback=${!bundled} noteRequests=$noteCalls liveNoteRequests=$liveNoteCalls livePostRequests=$livePostCalls reuse=true")
    }
}

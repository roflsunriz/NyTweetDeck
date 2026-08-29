package dev.nytweetdeck.android

import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.AccountStore
import dev.nytweetdeck.android.data.PostActionRepository
import dev.nytweetdeck.android.data.PostComposerRepository
import dev.nytweetdeck.android.data.TimelineRepository
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostActionType
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.XApiEnvironment
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveReversibleMutationSmokeTest {
    @Test
    fun reversiblePostActionsAndComposerModesRestoreTheXAccount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("mutationAuthorized") == "true")
        val context = instrumentation.targetContext
        val accountFile = File(context.noBackupFilesDir, "accounts/accounts.json")
        val account = AccountStore(accountFile).selectedAccount()
            ?: error("保存済み検証アカウントがありません。")
        val environment = XApiEnvironment(context)
        val graphQl = environment.graphQlClient()
        val timeline = TimelineRepository(graphQl, TimelineResponseParser()).load(
            account = account,
            kind = "homeForYou",
            language = "ja",
        )
        val target = timeline.posts.firstOrNull { it.author.id != account.userId }
            ?: error("可逆mutationの対象ポストがありません。")
        val actions = PostActionRepository(graphQl)

        restoreAction(actions, account, target, PostActionType.LIKE, target.liked)
        restoreAction(actions, account, target, PostActionType.REPOST, target.reposted)
        restoreAction(actions, account, target, PostActionType.BOOKMARK, target.bookmarked)

        val composer = PostComposerRepository(graphQl)
        val createdIds = mutableListOf<String>()
        try {
            val marker = Instant.now().toString()
            createdIds += composer.submit(
                account, "NyTweetDeck Android verification $marker",
            ).id
            createdIds += composer.submit(
                account, "NyTweetDeck Android reply verification $marker",
                replyToPostId = target.id,
            ).id
            createdIds += composer.submit(
                account, "NyTweetDeck Android quote verification $marker",
                quotePostId = target.id,
            ).id
            assertEquals(3, createdIds.distinct().size)
            assertTrue(createdIds.all { it.matches(Regex("[0-9]{1,30}")) })
        } finally {
            createdIds.asReversed().forEach { postId -> deleteWithRetry(composer, account, postId) }
        }
    }

    private fun restoreAction(
        repository: PostActionRepository,
        account: dev.nytweetdeck.android.data.AccountSecrets,
        post: Post,
        action: PostActionType,
        original: Boolean,
    ) {
        repository.setActive(account, post.id, action, !original, "ja")
        try {
            Unit
        } finally {
            repository.setActive(account, post.id, action, original, "ja")
        }
    }

    private fun deleteWithRetry(
        repository: PostComposerRepository,
        account: dev.nytweetdeck.android.data.AccountSecrets,
        postId: String,
    ) {
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            val result = runCatching { repository.delete(account, postId, "ja") }
            if (result.isSuccess) return
            lastFailure = result.exceptionOrNull()
            if (attempt < 2) Thread.sleep(500L * (attempt + 1))
        }
        throw IllegalStateException("検証用ポストを削除できませんでした: $postId", lastFailure)
    }
}

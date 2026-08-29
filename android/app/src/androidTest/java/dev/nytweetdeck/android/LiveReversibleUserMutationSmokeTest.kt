package dev.nytweetdeck.android

import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.AccountStore
import dev.nytweetdeck.android.data.ListMembershipAction
import dev.nytweetdeck.android.data.ListMembershipRepository
import dev.nytweetdeck.android.data.TimelineRepository
import dev.nytweetdeck.android.data.UserAction
import dev.nytweetdeck.android.data.UserActionRepository
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.UserProfileParser
import dev.nytweetdeck.android.xapi.XApiEnvironment
import dev.nytweetdeck.android.xapi.XSessionCredentials
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveReversibleUserMutationSmokeTest {
    @Test
    fun userActionsAndTemporaryListMembershipRestoreTheirInitialState() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("mutationAuthorized") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = AccountStore(
            File(context.noBackupFilesDir, "accounts/accounts.json"),
        ).selectedAccount() ?: error("保存済み検証アカウントがありません。")
        val environment = XApiEnvironment(context)
        val graphQl = environment.graphQlClient()
        val target = TimelineRepository(graphQl, TimelineResponseParser()).load(
            account, "homeForYou", language = "ja",
        ).posts.firstOrNull { it.author.id != account.userId }
            ?: error("ユーザーmutationの対象がありません。")
        val credentials = XSessionCredentials(
            account.webBearerToken, account.authToken, account.csrfToken,
        )
        val profileBody = graphQl.execute(
            credentials, "userByRestId", mapOf("userId" to target.author.id), "ja",
        )
        val profile = UserProfileParser().parseProfile(profileBody, target.author.id, "{}")
        val users = UserActionRepository(environment.restClient())

        restoreUserAction(
            users, account, target.author.id, profile.following,
            UserAction.FOLLOW, UserAction.UNFOLLOW,
        )
        restoreUserAction(
            users, account, target.author.id, profile.muting,
            UserAction.MUTE, UserAction.UNMUTE,
        )
        restoreUserAction(
            users, account, target.author.id, profile.blocking,
            UserAction.BLOCK, UserAction.UNBLOCK,
        )

        verifyTemporaryListMembership(environment, graphQl, account, target.author.id)
    }

    private fun restoreUserAction(
        repository: UserActionRepository,
        account: AccountSecrets,
        userId: String,
        original: Boolean,
        enable: UserAction,
        disable: UserAction,
    ) {
        repository.execute(account, userId, if (original) disable else enable, "ja")
        try {
            Unit
        } finally {
            repository.execute(account, userId, if (original) enable else disable, "ja")
        }
    }

    private fun verifyTemporaryListMembership(
        environment: XApiEnvironment,
        graphQl: dev.nytweetdeck.android.xapi.GraphQlExecutor,
        account: AccountSecrets,
        userId: String,
    ) {
        val rest = environment.restClient()
        val name = "NyTDVerify" + Instant.now().epochSecond.toString().takeLast(8)
        val body = rest.postForm(
            account,
            "createList",
            mapOf("name" to name, "mode" to "private", "description" to "Temporary verification list"),
            "ja",
        )
        val root = Json.parseToJsonElement(body).jsonObject
        val listId = ((root["id_str"] ?: root["id"]) as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
            ?: error("一時リストIDを取得できません。")
        val membership = ListMembershipRepository(graphQl)
        var added = false
        try {
            membership.execute(account, userId, listId, ListMembershipAction.ADD, "ja")
            added = true
            membership.execute(account, userId, listId, ListMembershipAction.REMOVE, "ja")
            added = false
        } finally {
            if (added) {
                runCatching {
                    membership.execute(account, userId, listId, ListMembershipAction.REMOVE, "ja")
                }
            }
            destroyListWithRetry(rest, account, listId)
        }
        assertTrue(listId.isNotBlank())
    }

    private fun destroyListWithRetry(
        rest: dev.nytweetdeck.android.xapi.AuthenticatedRestClient,
        account: AccountSecrets,
        listId: String,
    ) {
        var lastFailure: Throwable? = null
        repeat(3) { attempt ->
            val result = runCatching {
                rest.postForm(account, "destroyList", mapOf("list_id" to listId), "ja")
            }
            if (result.isSuccess) return
            lastFailure = result.exceptionOrNull()
            if (attempt < 2) Thread.sleep(500L * (attempt + 1))
        }
        throw IllegalStateException("一時検証リストを削除できませんでした: $listId", lastFailure)
    }
}

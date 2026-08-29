package dev.nytweetdeck.android

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.data.AccountStore
import dev.nytweetdeck.android.data.DeckSettingsStore
import dev.nytweetdeck.android.data.TimelineCache
import dev.nytweetdeck.android.data.TimelineRepository
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.DeckColumn
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.ui.DeckViewModel
import dev.nytweetdeck.android.ui.NyTweetDeckApp
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.XApiEnvironment
import dev.nytweetdeck.android.xapi.XSessionVerifier
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiveMultiAccountSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun twoRealAccountsSwitchImmediatelyAndPersistWithoutLosingTheirCaches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accountFile = File(context.noBackupFilesDir, "accounts/accounts.json")
        val accountStore = AccountStore(accountFile)
        val accounts = accountStore.accountSecrets()
        assertTrue("実機検証には保存済みXアカウントが2件以上必要です。", accounts.size >= 2)
        assertEquals(accounts.size, accounts.map { it.accountId }.distinct().size)
        assertEquals(accounts.size, accounts.map { it.userId }.distinct().size)

        val initialAccountId = requireNotNull(accountStore.selectedAccountId())
        val alternateAccountId = accounts.first { it.accountId != initialAccountId }.accountId
        val environment = XApiEnvironment(context)
        val timelineRepository = TimelineRepository(
            environment.graphQlClient(),
            TimelineResponseParser(),
            TimelineCache(context.cacheDir.resolve("timelines")),
        )
        accounts.filter { it.accountId == initialAccountId || it.accountId == alternateAccountId }
            .forEach { account ->
            val loaded = timelineRepository.load(account, "homeForYou", language = "ja")
            assertTrue("保存済みアカウントの実ホームを取得できませんでした。", loaded.posts.isNotEmpty())
            assertTrue(
                "アカウント別の即時表示キャッシュを再読込できませんでした。",
                timelineRepository.cached(account, "homeForYou")?.posts?.isNotEmpty() == true,
            )
        }

        val testRoot = context.cacheDir.resolve("multi-account-${UUID.randomUUID()}")
        val home = DeckColumn("multi-account-home", ColumnKind.HOME_FOR_YOU, "Home")
        val settingsStore = DeckSettingsStore(testRoot.resolve("settings.json").toPath())
        settingsStore.save(DeckUiState(columns = listOf(home)))
        val viewModel = DeckViewModel(
            settingsStore = settingsStore,
            accountStoreFile = accountFile,
            sessionVerifier = XSessionVerifier { error("not used") },
            timelineRepository = timelineRepository,
        )

        try {
            composeRule.activity.setContent { NyTweetDeckApp(providedViewModel = viewModel) }
            composeRule.waitUntil(10_000) {
                !viewModel.state.value.isInitializing && viewModel.state.value.accounts.size >= 2
            }
            viewModel.setVisibleColumns(setOf(home.id))
            waitForReadyTimeline(viewModel, home.id, initialAccountId)
            switchFromAccountDialog(viewModel, alternateAccountId)
            waitForImmediateReadyTimeline(viewModel, home.id, alternateAccountId)
            switchFromAccountDialog(viewModel, initialAccountId)
            waitForImmediateReadyTimeline(viewModel, home.id, initialAccountId)

            assertEquals(initialAccountId, AccountStore(accountFile).selectedAccountId())
            assertTrue(
                "往復切替後に開始アカウントのキャッシュを失いました。",
                viewModel.state.value.timelines[home.id]?.posts?.isNotEmpty() == true,
            )
        } finally {
            if (AccountStore(accountFile).selectedAccountId() != initialAccountId) {
                AccountStore(accountFile).selectAccount(initialAccountId)
            }
        }
    }

    private fun switchFromAccountDialog(viewModel: DeckViewModel, accountId: String) {
        composeRule.onNodeWithTag("accounts").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("account-$accountId").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("account-$accountId").performClick()
        composeRule.onNodeWithTag("close-accounts").performClick()
        composeRule.waitUntil(5_000) { viewModel.state.value.selectedAccountId == accountId }
    }

    private fun waitForReadyTimeline(
        viewModel: DeckViewModel,
        columnId: String,
        accountId: String,
    ) {
        composeRule.waitUntil(30_000) {
            viewModel.state.value.selectedAccountId == accountId &&
                viewModel.state.value.timelines[columnId]?.posts?.isNotEmpty() == true
        }
    }

    private fun waitForImmediateReadyTimeline(
        viewModel: DeckViewModel,
        columnId: String,
        accountId: String,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        waitForReadyTimeline(viewModel, columnId, accountId)
        assertTrue(
            "アカウント切替時の保持データ表示が3秒を超えました。",
            SystemClock.elapsedRealtime() - startedAt <= 3_000L,
        )
    }
}

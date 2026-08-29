package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.AccountAuthStatus
import dev.nytweetdeck.android.model.AccountUiModel
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.DeckColumn
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.RankingMode
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTransferTest {
    @Test
    fun roundTripsPortableLayoutAndKeepsCurrentAccountState() {
        val source = DeckUiState(
            isInitializing = false,
            columns = listOf(
                DeckColumn("home-1", ColumnKind.HOME_FOR_YOU, "ホーム"),
                DeckColumn("search-1", ColumnKind.SEARCH, "NyTweetDeck", "NyTweetDeck"),
                DeckColumn("trends-1", ColumnKind.TRENDS, "AI"),
            ),
            selectedMenu = ColumnKind.TRENDS,
            useDarkTheme = false,
            compactDensity = true,
            replySort = RankingMode.LIKES,
            accounts = listOf(
                AccountUiModel(
                    accountId = "account-secret-id",
                    userId = "user-secret-id",
                    username = "secret-user",
                    displayName = "secret-display",
                ),
            ),
            selectedAccountId = "account-secret-id",
            accountAuthStatus = AccountAuthStatus.VERIFYING,
        )
        val exportedAt = Instant.parse("2026-08-27T00:00:00Z")

        val serialized = LayoutTransfer.exportSettings(source, exportedAt)
        val imported = LayoutTransfer.importSettings(
            serialized,
            source.copy(
                columns = emptyList(),
                selectedMenu = ColumnKind.HOME_FOR_YOU,
                useDarkTheme = true,
                compactDensity = false,
            ),
        )

        assertTrue(serialized.startsWith("{\"format\":\"NyTweetDeckSettings\""))
        assertTrue(serialized.contains("\"version\":1"))
        assertTrue(serialized.contains("\"activeAccountId\":null"))
        assertTrue(serialized.contains("\"selectedMenu\":\"trends\""))
        assertFalse(serialized.contains("account-secret-id"))
        assertFalse(serialized.contains("user-secret-id"))
        assertFalse(serialized.contains("secret-user"))
        assertFalse(serialized.contains("secret-display"))
        assertFalse(serialized.contains("selectedAccountId"))
        assertFalse(serialized.contains("AccountUiModel"))
        assertFalse(serialized.contains("AccountSecrets"))

        assertEquals(source.columns, imported.state.columns)
        assertEquals(source.selectedMenu, imported.state.selectedMenu)
        assertEquals(source.useDarkTheme, imported.state.useDarkTheme)
        assertEquals(source.compactDensity, imported.state.compactDensity)
        assertEquals(source.replySort, imported.state.replySort)
        assertEquals(source.accounts, imported.state.accounts)
        assertEquals(source.selectedAccountId, imported.state.selectedAccountId)
        assertEquals(source.accountAuthStatus, imported.state.accountAuthStatus)
        assertEquals(source.selectedAccountId, imported.currentAccountId)
        assertEquals(source.selectedAccountId, imported.selectedAccountId)
        assertSame(imported.state, imported.layout)
    }

    @Test
    fun importsCanonicalWebDocumentAndValidatesIgnoredFields() {
        val current = DeckUiState(
            selectedMenu = ColumnKind.LIST,
            useDarkTheme = true,
            compactDensity = false,
        )
        val serialized = canonicalWebDocument()

        val imported = LayoutTransfer.importSettings(serialized, current).state

        assertEquals(
            listOf(
                DeckColumn("search-1", ColumnKind.SEARCH, "NyTweetDeck", "NyTweetDeck"),
                DeckColumn("home-1", ColumnKind.HOME_FOR_YOU, "おすすめ"),
            ),
            imported.columns,
        )
        assertEquals(ColumnKind.LIST, imported.selectedMenu)
        assertTrue(imported.useDarkTheme)
        assertTrue(imported.compactDensity)
        assertEquals(RankingMode.LIKES, imported.replySort)
    }

    @Test
    fun acceptsByteInputOnlyWhenItIsValidUtf8AndWithinLimit() {
        val state = DeckUiState()
        val serialized = LayoutTransfer.exportSettings(state, Instant.parse("2026-08-27T00:00:00Z"))

        assertEquals(
            state,
            LayoutTransfer.importSettings(serialized.toByteArray(StandardCharsets.UTF_8), state).state,
        )
        assertThrows(LayoutTransferException::class.java) {
            LayoutTransfer.importSettings(byteArrayOf(0xC3.toByte(), 0x28), state)
        }
    }

    @Test
    fun rejectsMalformedWrongVersionAndInvalidDateDocuments() {
        val current = DeckUiState()

        assertThrowsMessage("有効なJSON") {
            LayoutTransfer.importSettings("not json", current)
        }
        assertThrowsMessage("NyTweetDeck設定ファイル") {
            LayoutTransfer.importSettings(canonicalWebDocument().replaceFirst("NyTweetDeckSettings", "Other"), current)
        }
        assertThrowsMessage("バージョン") {
            LayoutTransfer.importSettings(canonicalWebDocument().replaceFirst("\"version\": 1", "\"version\": 999"), current)
        }
        assertThrowsMessage("日時") {
            LayoutTransfer.importSettings(canonicalWebDocument().replaceFirst("2026-08-27T00:00:00.000Z", "not-a-date"), current)
        }
        assertThrowsMessage("レイアウト") {
            LayoutTransfer.importSettings(canonicalWebDocument().replaceFirst("\"version\": 8", "\"version\": 999"), current)
        }
    }

    @Test
    fun rejectsUnknownKeysAtEveryPortableObjectLevel() {
        val current = DeckUiState()
        val documents = listOf(
            canonicalWebDocument().replaceFirst("\"format\":", "\"unknownRoot\":true,\"format\":"),
            canonicalWebDocument().replaceFirst("\"columns\":", "\"unknownLayout\":true,\"columns\":"),
            canonicalWebDocument().replaceFirst("\"target\": \"NyTweetDeck\"", "\"target\": \"NyTweetDeck\",\"unknownColumn\":true"),
            canonicalWebDocument().replaceFirst("\"fontSize\":", "\"unknownDisplay\":true,\"fontSize\":"),
        )

        documents.forEach { serialized ->
            assertThrows(LayoutTransferException::class.java) {
                LayoutTransfer.importSettings(serialized, current)
            }
        }
    }

    @Test
    fun rejectsTypeAndEnumerationViolations() {
        val current = DeckUiState()
        val invalidDocuments = listOf(
            canonicalWebDocument().replaceFirst("\"locale\": \"ja\"", "\"locale\": true"),
            canonicalWebDocument().replaceFirst("\"theme\": \"system\"", "\"theme\": \"sepia\""),
            canonicalWebDocument().replaceFirst("\"kind\": \"search\"", "\"kind\": \"unknown\""),
            canonicalWebDocument().replaceFirst("\"videoVolume\": 100", "\"videoVolume\": 100.5"),
            canonicalWebDocument().replaceFirst("\"compact\"", "true"),
            canonicalWebDocument().replaceFirst("\"replySort\": \"likes\"", "\"replySort\": \"random\""),
            canonicalWebDocument().replaceFirst("\"navItems\": [\"home\", \"trends\"]", "\"navItems\": [\"home\", \"unknown\"]"),
        )

        invalidDocuments.forEach { serialized ->
            assertThrows(LayoutTransferException::class.java) {
                LayoutTransfer.importSettings(serialized, current)
            }
        }
    }

    @Test
    fun rejectsDuplicateColumnIdsAndDuplicateJsonKeys() {
        val current = DeckUiState()
        val duplicateIds = canonicalWebDocument().replaceFirst("\"home-1\"", "\"search-1\"")
        val duplicateKeys = canonicalWebDocument().replaceFirst(
            "\"format\": \"NyTweetDeckSettings\"",
            "\"format\": \"NyTweetDeckSettings\",\"format\": \"NyTweetDeckSettings\"",
        )

        assertThrows(LayoutTransferException::class.java) {
            LayoutTransfer.importSettings(duplicateIds, current)
        }
        assertThrows(LayoutTransferException::class.java) {
            LayoutTransfer.importSettings(duplicateKeys, current)
        }
    }

    @Test
    fun rejectsJsonLargerThanOneMiBBeforeParsing() {
        val oversized = "{" + "x".repeat(LayoutTransfer.MAX_JSON_SIZE_BYTES) + "}"

        val failure = assertThrows(LayoutTransferException::class.java) {
            LayoutTransfer.importSettings(oversized, DeckUiState())
        }

        assertTrue(failure.message.orEmpty().contains("1MiB"))
    }

    @Test
    fun rejectsOversizedExportWithoutLeakingAccountState() {
        val columns = (0 until 20_000).map { index ->
            DeckColumn("column-$index", ColumnKind.HOME_FOR_YOU, "Title $index")
        }

        val failure = assertThrows(LayoutTransferException::class.java) {
            LayoutTransfer.exportSettings(
                DeckUiState(columns = columns, selectedAccountId = "must-not-appear"),
                Instant.parse("2026-08-27T00:00:00Z"),
            )
        }

        assertTrue(failure.message.orEmpty().contains("1MiB"))
        assertFalse(failure.toString().contains("must-not-appear"))
    }

    @Test
    fun rejectsInvalidExportStateBeforeWriting() {
        assertThrows(LayoutTransferException::class.java) {
            LayoutTransfer.exportSettings(
                DeckUiState(columns = listOf(DeckColumn("duplicate", ColumnKind.HOME_FOR_YOU, "Home"), DeckColumn("duplicate", ColumnKind.TRENDS, "Trends"))),
                Instant.parse("2026-08-27T00:00:00Z"),
            )
        }
        assertThrows(LayoutTransferException::class.java) {
            LayoutTransfer.exportSettings(
                DeckUiState(columns = listOf(DeckColumn("bad\n-id", ColumnKind.HOME_FOR_YOU, "Home"))),
                Instant.parse("2026-08-27T00:00:00Z"),
            )
        }
        assertThrows(LayoutTransferException::class.java) {
            LayoutTransfer.exportSettings(DeckUiState(), "not-a-date")
        }
    }

    private fun canonicalWebDocument(): String = """
        {
          "format": "NyTweetDeckSettings",
          "version": 1,
          "exportedAt": "2026-08-27T00:00:00.000Z",
          "layout": {
            "version": 8,
            "columns": [
              {"id": "search-1", "kind": "search", "target": "NyTweetDeck", "label": "NyTweetDeck"},
              {"id": "home-1", "kind": "home", "target": null, "label": null}
            ],
            "navItems": ["home", "trends"],
            "locale": "ja",
            "theme": "system",
            "activeAccountId": null,
            "replySort": "likes",
            "display": {
              "fontSize": "large",
              "accentColor": "purple",
              "density": "compact",
              "reduceMotion": true,
              "mediaPreview": true,
              "videoAutoplay": true,
              "videoLoop": false,
              "videoVolume": 100,
              "autoTranslatePosts": false
            },
            "trendSearchHistory": ["AI", "Japan"]
          }
        }
    """.trimIndent()

    private fun assertThrowsMessage(fragment: String, block: () -> Unit) {
        val failure = assertThrows(LayoutTransferException::class.java, block)
        assertTrue(
            "Expected error to contain '$fragment' but was '${failure.message}'",
            failure.message.orEmpty().contains(fragment),
        )
    }
}

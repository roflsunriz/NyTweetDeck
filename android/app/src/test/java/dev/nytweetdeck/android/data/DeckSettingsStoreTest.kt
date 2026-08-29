package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.AccountAuthStatus
import dev.nytweetdeck.android.model.AccountUiModel
import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.DeckColumn
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.MainMenuItemId
import dev.nytweetdeck.android.model.RankingMode
import dev.nytweetdeck.android.model.ThemeMode
import dev.nytweetdeck.android.model.AppFontSize
import dev.nytweetdeck.android.model.AccentColor
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeckSettingsStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun roundTripsLayoutAndCreatesBackupOnFirstSave() {
        val store = DeckSettingsStore(settingsPath())
        val expected = layout()

        store.save(expected)

        assertEquals(expected, store.load())
        assertTrue(Files.isRegularFile(store.primaryPath))
        assertTrue(Files.isRegularFile(store.backupPath))
        assertArrayEquals(
            Files.readAllBytes(store.primaryPath),
            Files.readAllBytes(store.backupPath),
        )
        assertNoTemporaryFiles()
    }

    @Test
    fun backsUpThePreviousValidPrimaryBeforeSavingTheNextLayout() {
        val store = DeckSettingsStore(settingsPath())
        val first = layout()
        val second = first.copy(
            selectedMenu = ColumnKind.TRENDS,
            useDarkTheme = false,
            layoutRevision = 1,
        )
        store.save(first)

        val firstBytes = Files.readAllBytes(store.primaryPath)
        store.save(second)

        assertEquals(second, store.load())
        assertArrayEquals(firstBytes, Files.readAllBytes(store.backupPath))
    }

    @Test
    fun rejectsStaleAndSameRevisionConflictsWithoutOverwritingLatestLayout() {
        val store = DeckSettingsStore(settingsPath())
        val initial = layout()
        val latest = initial.copy(useDarkTheme = false, layoutRevision = 2)
        store.save(initial)
        store.save(latest)

        assertThrows(DeckSettingsStore.DeckSettingsConflictException::class.java) {
            store.save(initial.copy(selectedMenu = ColumnKind.TRENDS, layoutRevision = 1))
        }
        assertThrows(DeckSettingsStore.DeckSettingsConflictException::class.java) {
            store.save(latest.copy(selectedMenu = ColumnKind.TRENDS))
        }

        assertEquals(latest, store.load())
    }

    @Test
    fun treatsRepeatedSaveOfTheSameRevisionAsIdempotent() {
        val store = DeckSettingsStore(settingsPath())
        val initial = layout()
        val latest = initial.copy(useDarkTheme = false, layoutRevision = 1)
        store.save(initial)
        store.save(latest)

        val backupBeforeRetry = Files.readAllBytes(store.backupPath)
        store.save(latest)

        assertArrayEquals(backupBeforeRetry, Files.readAllBytes(store.backupPath))
        assertEquals(latest, store.load())
    }

    @Test
    fun persistsCustomMenuOrderWithoutDuplicates() {
        val store = DeckSettingsStore(settingsPath())
        val state = layout().copy(
            mainMenuItems = listOf(
                MainMenuItemId.HOME,
                MainMenuItemId.COMPOSE,
                MainMenuItemId.CHAT,
            ),
        )

        store.save(state)

        assertEquals(state.mainMenuItems, store.load().mainMenuItems)
    }

    @Test
    fun restoresThePreviousValidBackupWhenPrimaryIsCorrupted() {
        val store = DeckSettingsStore(settingsPath())
        val first = layout()
        store.save(first)
        store.save(first.copy(useDarkTheme = false, layoutRevision = 1))
        writeText(store.primaryPath, "corrupted-primary")

        val recovered = DeckSettingsStore(store.primaryPath).load()

        assertEquals(first, recovered)
        assertEquals(first, DeckSettingsStore(store.primaryPath).load())
    }

    @Test
    fun restoresTheBackupWhenPrimaryIsMissing() {
        val store = DeckSettingsStore(settingsPath())
        val expected = layout()
        store.save(expected)
        Files.delete(store.primaryPath)

        val recovered = DeckSettingsStore(store.primaryPath).load()

        assertEquals(expected, recovered)
        assertTrue(Files.isRegularFile(store.primaryPath))
    }

    @Test
    fun returnsDefaultsWhenBothPrimaryAndBackupAreInvalid() {
        val store = DeckSettingsStore(settingsPath())
        store.save(layout())
        writeText(store.primaryPath, "corrupted-primary")
        writeText(store.backupPath, "corrupted-backup")

        val recovered = DeckSettingsStore(store.primaryPath).load()

        assertEquals(DeckUiState(), recovered)
    }

    @Test
    fun rejectsAnUnknownSchemaVersion() {
        val store = DeckSettingsStore(settingsPath())
        store.save(layout())
        val current = readText(store.primaryPath)
            .replace("\"schemaVersion\":9", "\"schemaVersion\":999")
        writeText(store.primaryPath, current)
        Files.delete(store.backupPath)

        assertEquals(DeckUiState(), DeckSettingsStore(store.primaryPath).load())
    }

    @Test
    fun migratesSchemaOneHomeColumnToForYouWithoutLosingIdentity() {
        val store = DeckSettingsStore(settingsPath())
        writeText(
            store.primaryPath,
            """{"schemaVersion":1,"columns":[{"id":"legacy-home","kind":"HOME","title":"Home"}],"selectedMenu":"HOME","useDarkTheme":true,"compactDensity":false}""",
        )

        val loaded = store.load()

        assertEquals("legacy-home", loaded.columns.single().id)
        assertEquals(ColumnKind.HOME_FOR_YOU, loaded.columns.single().kind)
        assertNull(loaded.columns.single().target)
    }

    @Test
    fun migratesEveryLegacySchemaTwoThroughEightToCurrentDefaults() {
        (2..8).forEach { version ->
            writeText(settingsPath(), legacySchema(version))
            val loaded = DeckSettingsStore(settingsPath()).load()

            assertEquals("legacy-home", loaded.columns.single().id)
            assertEquals(ColumnKind.HOME_FOR_YOU, loaded.columns.single().kind)
            assertEquals(0L, loaded.layoutRevision)
            if (version >= 8) assertEquals("ar", loaded.appLanguageTag)
        }
    }

    @Test
    fun neverSerializesAccountSelectionOrAuthenticationState() {
        val store = DeckSettingsStore(settingsPath())
        val state = layout().copy(
            accounts = listOf(AccountUiModel(
                accountId = "account-fixture",
                userId = "user-fixture",
                username = "username-fixture",
                displayName = "display-fixture",
            )),
            selectedAccountId = "account-fixture",
            accountAuthStatus = AccountAuthStatus.VERIFYING,
        )

        store.save(state)
        val serialized = readText(store.primaryPath)
        val loaded = store.load()

        assertFalse(serialized.contains("account-fixture"))
        assertFalse(serialized.contains("username-fixture"))
        assertTrue(loaded.accounts.isEmpty())
        assertNull(loaded.selectedAccountId)
        assertEquals(AccountAuthStatus.IDLE, loaded.accountAuthStatus)
        assertEquals(
            state.copy(
                accounts = emptyList(),
                selectedAccountId = null,
                accountAuthStatus = AccountAuthStatus.IDLE,
            ),
            loaded,
        )
        assertFalse(readText(store.backupPath).contains("account-fixture"))
    }

    @Test
    fun rejectsInvalidInputWithoutOverwritingExistingFiles() {
        val store = DeckSettingsStore(settingsPath())
        store.save(layout())
        val primaryBefore = Files.readAllBytes(store.primaryPath)
        val backupBefore = Files.readAllBytes(store.backupPath)
        val invalid = layout().copy(
            columns = listOf(
                DeckColumn("duplicate", ColumnKind.HOME_FOR_YOU, "Home"),
                DeckColumn("duplicate", ColumnKind.TRENDS, "Trends"),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) { store.save(invalid) }

        assertArrayEquals(primaryBefore, Files.readAllBytes(store.primaryPath))
        assertArrayEquals(backupBefore, Files.readAllBytes(store.backupPath))
    }

    @Test
    fun treatsFilesLargerThanOneMiBAsInvalid() {
        val store = DeckSettingsStore(settingsPath())
        Files.write(
            store.primaryPath,
            ByteArray(DeckSettingsStore.MAX_FILE_SIZE_BYTES + 1) { 'x'.code.toByte() },
        )

        assertEquals(DeckUiState(), store.load())
    }

    private fun settingsPath() = temporaryFolder.root.toPath().resolve("deck-settings.json")

    private fun readText(path: java.nio.file.Path): String =
        String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    private fun writeText(path: java.nio.file.Path, value: String) {
        Files.write(path, value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun assertNoTemporaryFiles() {
        Files.newDirectoryStream(temporaryFolder.root.toPath()).use { files ->
            assertFalse(files.any { it.fileName.toString().endsWith(".tmp") })
        }
    }

    private fun layout() = DeckUiState(
        columns = listOf(
            DeckColumn("home-1", ColumnKind.HOME_FOR_YOU, "Home"),
            DeckColumn("notifications-1", ColumnKind.NOTIFICATIONS, "Notifications"),
        ),
        selectedMenu = ColumnKind.HOME_FOR_YOU,
        useDarkTheme = true,
        compactDensity = false,
        replySort = RankingMode.LIKES,
        themeMode = ThemeMode.SYSTEM,
        fontSize = AppFontSize.LARGE,
        accentColor = AccentColor.PURPLE,
        reduceMotion = true,
        mediaPreview = false,
        videoAutoplay = true,
        videoLoop = false,
        videoVolume = 42,
        trendSearchHistory = listOf("Android", "NyTweetDeck"),
        autoTranslatePosts = false,
        appLanguageTag = "ar",
    )

    private fun legacySchema(version: Int): String = buildString {
        append("{\"schemaVersion\":$version,\"columns\":[{\"id\":\"legacy-home\",")
        append("\"kind\":\"HOME_FOR_YOU\",\"title\":\"Home\",\"target\":null}],")
        append("\"selectedMenu\":\"HOME_FOR_YOU\",\"useDarkTheme\":true,\"compactDensity\":false")
        if (version >= 3) append(",\"mainMenuItems\":[\"HOME\"]")
        if (version >= 4) append(",\"replySort\":\"RELEVANCE\"")
        if (version >= 5) append(",\"themeMode\":\"DARK\",\"fontSize\":\"DEFAULT\"," +
            "\"accentColor\":\"BLUE\",\"reduceMotion\":false,\"mediaPreview\":true," +
            "\"videoAutoplay\":false,\"videoLoop\":true,\"videoVolume\":100")
        if (version >= 6) append(",\"trendSearchHistory\":[]")
        if (version >= 7) append(",\"autoTranslatePosts\":true")
        if (version >= 8) append(",\"appLanguageTag\":\"ar\"")
        append('}')
    }
}

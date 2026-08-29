package dev.nytweetdeck.android.data

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertThrows

class AccountStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun startsEmptyPersistsOneJsonAndRestoresAfterRestart() {
        val file = storeFile()
        val store = AccountStore(file)
        val first = account("1")

        assertTrue(store.accountSummaries().isEmpty())
        assertNull(store.selectedAccountId())
        store.addOrReplace(first)

        assertEquals("1", store.selectedAccountId())
        assertEquals(first, store.requireAccount("1"))
        assertEquals(
            AccountSummary("1", "uid-1", "user1", "Display 1"),
            store.accountSummaries().single(),
        )
        assertTrue(store.backupFile.isFile)

        val json = file.readText()
        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"accounts\":["))
        assertTrue(json.contains("\"selectedAccountId\":\"1\""))
        assertTrue(json.contains("bearer-1"))
        assertTrue(json.contains("auth-1"))
        assertTrue(json.contains("csrf-1"))
        assertTrue(json.contains("profile-1"))

        val restarted = AccountStore(file)
        assertEquals(store.snapshot(), restarted.snapshot())
        assertFalse(restarted.accountSummaries().toString().contains("bearer-1"))
        assertFalse(restarted.requireAccount("1").toString().contains("bearer-1"))
        assertFalse(restarted.requireAccount("1").toString().contains("auth-1"))
        assertFalse(restarted.requireAccount("1").toString().contains("csrf-1"))
    }

    @Test
    fun validatesAllSecretModelFieldsAndRedactsEverySecretFromToString() {
        val valid = account("1")

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(accountId = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(userId = " \t")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(username = "user\n1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(displayName = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(webBearerToken = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(authToken = "\u0000")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(csrfToken = "csrf\r\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(profileName = "profile\u0007")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(accountId = "x".repeat(201))
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(webBearerToken = "x".repeat(8_193))
        }

        val rendered = valid.toString()
        assertTrue(rendered.contains("<redacted>"))
        assertFalse(rendered.contains("bearer-1"))
        assertFalse(rendered.contains("auth-1"))
        assertFalse(rendered.contains("csrf-1"))
    }

    @Test
    fun replacesDuplicateInPlaceAndPersistsExplicitSelection() {
        val file = storeFile()
        val store = AccountStore(file)
        store.addOrReplace(account("1"))
        store.addOrReplace(account("2"))
        store.selectAccount("2")

        store.addOrReplace(account("1", username = "updated", suffix = "updated"))

        assertEquals(listOf("1", "2"), store.accountSecrets().map { it.accountId })
        assertEquals("2", store.selectedAccountId())
        assertEquals("updated", store.requireAccount("1").username)

        store.addOrReplace(account("1", username = "selected", suffix = "selected"), select = true)
        assertEquals("1", store.selectedAccountId())
        val restarted = AccountStore(file)
        assertEquals("1", restarted.selectedAccountId())
        assertEquals("selected", restarted.requireAccount("1").username)
    }

    @Test
    fun refusesTheHundredAndFirstNewAccountWithoutChangingExistingState() {
        val file = storeFile()
        val store = AccountStore(file)
        (1..AccountStore.MAX_ACCOUNTS).forEach { id ->
            store.addOrReplace(account(id.toString()))
        }
        val jsonBefore = file.readText()

        assertThrows(IllegalArgumentException::class.java) {
            store.addOrReplace(account("101"))
        }

        assertEquals(AccountStore.MAX_ACCOUNTS, store.accountSecrets().size)
        assertEquals(jsonBefore, file.readText())
        assertFalse(file.readText().contains("\"accountId\":\"101\""))
    }

    @Test
    fun fallsBackToTheFirstAccountWhenStoredSelectionIsMissingOrInvalid() {
        val file = storeFile()
        val first = account("1")
        val second = account("2")
        file.writeText(rawDocument(listOf(first, second), "missing"))

        val invalidSelection = AccountStore(file)
        assertEquals("1", invalidSelection.selectedAccountId())
        assertTrue(file.readText().contains("\"selectedAccountId\":\"1\""))

        file.writeText(rawDocument(listOf(first, second), null))
        val absentSelection = AccountStore(file)
        assertEquals("1", absentSelection.selectedAccountId())
        assertTrue(file.readText().contains("\"selectedAccountId\":\"1\""))
    }

    @Test
    fun deletesAccountsAndSelectsTheNewFirstAccountWhenNeeded() {
        val file = storeFile()
        val store = AccountStore(file)
        store.addOrReplace(account("1"))
        store.addOrReplace(account("2"))
        store.addOrReplace(account("3"))
        store.selectAccount("2")

        assertTrue(store.deleteAccount("2"))
        assertEquals(listOf("1", "3"), store.accountSecrets().map { it.accountId })
        assertEquals("1", store.selectedAccountId())
        assertFalse(store.deleteAccount("missing"))

        assertTrue(store.removeAccount("1"))
        assertEquals(listOf("3"), store.accountSecrets().map { it.accountId })
        assertEquals("3", store.selectedAccountId())
        assertTrue(store.deleteAccount("3"))
        assertTrue(store.accountSecrets().isEmpty())
        assertNull(store.selectedAccountId())
        assertTrue(file.readText().contains("\"accounts\":[],\"selectedAccountId\":null"))

        val restarted = AccountStore(file)
        assertTrue(restarted.accountSecrets().isEmpty())
        assertNull(restarted.selectedAccountId())
    }

    @Test
    fun createsBackupOnFirstSaveAndRecoversThePreviousNormalVersion() {
        val file = storeFile()
        val store = AccountStore(file)
        store.addOrReplace(account("1", username = "old", suffix = "old"))
        assertTrue(store.backupFile.isFile)
        assertEquals(file.readText(), store.backupFile.readText())

        store.addOrReplace(account("1", username = "new", suffix = "new"))
        assertTrue(file.readText().contains("\"username\":\"new\""))
        assertTrue(store.backupFile.readText().contains("\"username\":\"old\""))

        file.writeText("corrupted-secret-marker")
        val recovered = AccountStore(file)
        assertEquals("old", recovered.requireAccount("1").username)
        assertTrue(file.readText().contains("\"username\":\"old\""))
        assertFalse(file.readText().contains("corrupted-secret-marker"))
    }

    @Test
    fun restoresPrimaryFromBackupWhenPrimaryIsMissing() {
        val file = storeFile()
        val store = AccountStore(file)
        store.addOrReplace(account("1"))
        Files.delete(file.toPath())

        val recovered = AccountStore(file)
        assertEquals("1", recovered.requireAccount("1").accountId)
        assertTrue(file.isFile)
        assertTrue(recovered.backupFile.isFile)
    }

    @Test
    fun reportsUnrecoverableDataWithoutEchoingFileContents() {
        val file = storeFile()
        val marker = "corrupted-secret-marker"
        file.writeText(marker)

        val failure = assertThrows(AccountStoreException::class.java) {
            AccountStore(file)
        }

        assertFalse(failure.message.orEmpty().contains(marker))
        assertFalse(failure.toString().contains(marker))
    }

    @Test
    fun rejectsOversizedOrExcessivelyNestedDocuments() {
        val file = storeFile()
        file.writeBytes(ByteArray(AccountStore.MAX_FILE_SIZE_BYTES + 1) { 'x'.code.toByte() })
        assertThrows(AccountStoreException::class.java) { AccountStore(file) }

        file.writeText("[".repeat(66) + "null" + "]".repeat(66))
        assertThrows(AccountStoreException::class.java) { AccountStore(file) }
    }

    @Test
    fun rejectsUnsupportedSchemaMalformedAccountsAndDuplicateIds() {
        val file = storeFile()
        file.writeText("{\"schemaVersion\":2,\"accounts\":[],\"selectedAccountId\":null}")
        assertThrows(AccountStoreException::class.java) {
            AccountStore(file)
        }

        file.writeText("{\"schemaVersion\":1,\"accounts\":[{}],\"selectedAccountId\":null}")
        assertThrows(AccountStoreException::class.java) {
            AccountStore(file)
        }

        val duplicate = account("1")
        file.writeText(rawDocument(listOf(duplicate, duplicate), "1"))
        assertThrows(AccountStoreException::class.java) {
            AccountStore(file)
        }
    }

    @Test
    fun roundTripsJsonEscapingAndCleansTemporaryFiles() {
        val file = storeFile()
        val escaped = AccountSecrets(
            accountId = "quoted-id",
            userId = "uid/日本語",
            username = "user\"quoted",
            displayName = "表示名 / \"quoted\"",
            webBearerToken = "bearer-quoted",
            authToken = "auth-quoted",
            csrfToken = "csrf-quoted",
            profileName = "profile-quoted",
        )
        val store = AccountStore(file)
        store.addOrReplace(escaped)

        assertEquals(escaped, AccountStore(file).requireAccount("quoted-id"))
        assertFalse(File(file.path + ".tmp").exists())
        assertFalse(File(store.backupFile.path + ".tmp").exists())
    }

    @Test
    fun restrictsCreatedFilesToOwnerOnPosixFileSystems() {
        val file = storeFile()
        val store = AccountStore(file)
        store.addOrReplace(account("1"))

        if (Files.getFileStore(file.toPath()).supportsFileAttributeView("posix")) {
            val ownerOnly = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )
            assertEquals(ownerOnly, Files.getPosixFilePermissions(file.toPath()))
            assertEquals(ownerOnly, Files.getPosixFilePermissions(store.backupFile.toPath()))
        }
    }

    @Test
    fun selectingOrRequiringUnknownAccountDoesNotModifyState() {
        val file = storeFile()
        val store = AccountStore(file)
        store.addOrReplace(account("1"))
        val before = file.readText()

        assertThrows(IllegalArgumentException::class.java) {
            store.selectAccount("missing")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.requireAccount("missing")
        }

        assertEquals(before, file.readText())
        assertEquals("1", store.selectedAccountId())
    }

    private fun storeFile(): File =
        temporaryFolder.newFile("accounts.json").also { it.delete() }

    private fun account(
        id: String,
        username: String = "user" + id,
        suffix: String = id,
    ): AccountSecrets = AccountSecrets(
        accountId = id,
        userId = "uid-" + id,
        username = username,
        displayName = "Display " + id,
        webBearerToken = "bearer-" + suffix,
        authToken = "auth-" + suffix,
        csrfToken = "csrf-" + suffix,
        profileName = "profile-" + id,
    )

    private fun rawDocument(
        accounts: List<AccountSecrets>,
        selectedAccountId: String?,
    ): String = "{\"schemaVersion\":1,\"accounts\":[" +
        accounts.joinToString(",") { rawAccount(it) } +
        "],\"selectedAccountId\":" +
        (selectedAccountId?.let(::quote) ?: "null") +
        "}"

    private fun rawAccount(account: AccountSecrets): String =
        "{\"accountId\":" + quote(account.accountId) +
            ",\"userId\":" + quote(account.userId) +
            ",\"username\":" + quote(account.username) +
            ",\"displayName\":" + quote(account.displayName) +
            ",\"webBearerToken\":" + quote(account.webBearerToken) +
            ",\"authToken\":" + quote(account.authToken) +
            ",\"csrfToken\":" + quote(account.csrfToken) +
            ",\"profileName\":" + quote(account.profileName) +
            "}"

    private fun quote(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"") + "\""
}

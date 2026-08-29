package dev.nytweetdeck.android.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.HashSet
import java.util.LinkedHashMap

/**
 * X Web のセッションと表示用のアカウント情報をまとめた保存モデルです。
 *
 * トークンは平文で保存されますが、[AccountStore] の利用者が渡す保存先
 * （Androidでは noBackupFilesDir 配下）から外へ出さないことを前提にしています。
 */
public data class AccountSecrets(
    val accountId: String,
    val userId: String,
    val username: String,
    val displayName: String,
    val webBearerToken: String,
    val authToken: String,
    val csrfToken: String,
    val profileName: String,
) {
    init {
        AccountValueValidator.requireValue(accountId, "accountId", AccountValueValidator.ID_MAX)
        AccountValueValidator.requireValue(userId, "userId", AccountValueValidator.ID_MAX)
        AccountValueValidator.requireValue(username, "username", AccountValueValidator.NAME_MAX)
        AccountValueValidator.requireValue(displayName, "displayName", AccountValueValidator.DISPLAY_MAX)
        AccountValueValidator.requireValue(
            webBearerToken,
            "webBearerToken",
            AccountValueValidator.TOKEN_MAX,
        )
        AccountValueValidator.requireValue(authToken, "authToken", AccountValueValidator.TOKEN_MAX)
        AccountValueValidator.requireValue(csrfToken, "csrfToken", AccountValueValidator.TOKEN_MAX)
        AccountValueValidator.requireValue(profileName, "profileName", AccountValueValidator.PROFILE_MAX)
    }

    public fun toAccountSummary(): AccountStore.AccountSummary =
        AccountStore.AccountSummary(accountId, userId, username, displayName)

    /**
     * 既存の呼び出し側が profileName をまだ持たない場合の生成ヘルパーです。
     * 新規呼び出し側は profileName を明示する8引数版を使います。
     */
    public companion object {
        @JvmStatic
        public fun webSession(
            accountId: String,
            userId: String,
            username: String,
            displayName: String,
            webBearerToken: String,
            authToken: String,
            csrfToken: String,
        ): AccountSecrets = AccountSecrets(
            accountId = accountId,
            userId = userId,
            username = username,
            displayName = displayName,
            webBearerToken = webBearerToken,
            authToken = authToken,
            csrfToken = csrfToken,
            profileName = "profile-" + accountId,
        )

        @JvmStatic
        public fun webSession(
            accountId: String,
            userId: String,
            username: String,
            displayName: String,
            webBearerToken: String,
            authToken: String,
            csrfToken: String,
            profileName: String,
        ): AccountSecrets = AccountSecrets(
            accountId = accountId,
            userId = userId,
            username = username,
            displayName = displayName,
            webBearerToken = webBearerToken,
            authToken = authToken,
            csrfToken = csrfToken,
            profileName = profileName,
        )
    }

    /**
     * 認証情報をログや例外へ誤って出さないため、秘密値は常に固定文字列へ置換します。
     */
    override fun toString(): String =
        "AccountSecrets[" +
            "accountId=" + accountId +
            ", userId=" + userId +
            ", username=" + username +
            ", displayName=" + displayName +
            ", webBearerToken=<redacted>" +
            ", authToken=<redacted>" +
            ", csrfToken=<redacted>" +
            ", profileName=" + profileName +
            "]"
}

private object AccountValueValidator {
    const val ID_MAX = 200
    const val NAME_MAX = 200
    const val DISPLAY_MAX = 500
    const val TOKEN_MAX = 8_192
    const val PROFILE_MAX = 200

    fun requireValue(value: String, name: String, maxLength: Int) {
        require(value.isNotBlank()) { name + "が空です。" }
        require(value.length <= maxLength) { name + "が長すぎます。" }
        require(value.none { it.code < 0x20 || it.code == 0x7f }) {
            name + "に制御文字を含めることはできません。"
        }
    }
}

public class AccountStoreException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public data class AccountStoreState(
    val accounts: List<AccountSecrets>,
    val selectedAccountId: String?,
) {
    init {
        require(accounts.size <= AccountStore.MAX_ACCOUNTS) {
            "保存できるアカウントは100件以下です。"
        }
        val ids = HashSet<String>(accounts.size)
        accounts.forEach { account ->
            require(ids.add(account.accountId)) {
                "アカウント保存データに重複があります。"
            }
        }
        require(selectedAccountId == null || selectedAccountId in ids) {
            "選択アカウントが保存一覧にありません。"
        }
        require(accounts.isNotEmpty() || selectedAccountId == null) {
            "アカウントがない状態では選択アカウントを指定できません。"
        }
    }

    public val selectedAccount: AccountSecrets?
        get() = accounts.firstOrNull { it.accountId == selectedAccountId }
}

public class AccountStore(storeFile: File) {
    private val storeFile: File = checkedStoreFile(storeFile)
    private var currentState: AccountStoreState = loadInitial()

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
        public const val MAX_ACCOUNTS: Int = 100
        public const val MAX_FILE_SIZE_BYTES: Int = 4 * 1024 * 1024
    }

    public data class AccountSummary(
        val accountId: String,
        val userId: String,
        val username: String,
        val displayName: String,
    )

    /** 呼び出し側が渡した保存ファイルです。 */
    public val file: File
        get() = storeFile

    /** 主ファイルと同じディレクトリに置く直前正常バックアップです。 */
    public val backupFile: File
        get() = File(storeFile.path + ".bak")

    @Synchronized
    public fun snapshot(): AccountStoreState = currentState

    /** 初回ロード後の現在状態を明示的に取得する別名です。 */
    @Synchronized
    public fun load(): AccountStoreState = currentState

    @Synchronized
    public fun accountSecrets(): List<AccountSecrets> = currentState.accounts

    @Synchronized
    public fun accountSummaries(): List<AccountSummary> =
        currentState.accounts.map(AccountSecrets::toAccountSummary)

    @Synchronized
    public fun selectedAccountId(): String? = currentState.selectedAccountId

    @Synchronized
    public fun selectedAccount(): AccountSecrets? = currentState.selectedAccount

    /**
     * accountId が既存なら同じ位置で置き換え、なければ末尾へ追加します。
     * select が true の場合は置換・追加したアカウントを選択状態にします。
     */
    @Synchronized
    public fun addOrReplace(account: AccountSecrets, select: Boolean = false) {
        val updated = currentState.accounts.toMutableList()
        val existingIndex = updated.indexOfFirst { it.accountId == account.accountId }
        if (existingIndex >= 0) {
            updated[existingIndex] = account
        } else {
            require(updated.size < MAX_ACCOUNTS) {
                "保存できるアカウントは100件以下です。"
            }
            updated += account
        }

        val selectedId = if (select) {
            account.accountId
        } else {
            normalizeSelectedId(updated, currentState.selectedAccountId)
        }
        val next = AccountStoreState(updated.toList(), selectedId)
        persist(next)
        currentState = next
    }

    @Synchronized
    public fun selectAccount(accountId: String) {
        require(currentState.accounts.any { it.accountId == accountId }) {
            "指定したアカウントがありません。"
        }
        val next = AccountStoreState(currentState.accounts, accountId)
        persist(next)
        currentState = next
    }

    /**
     * アカウントを削除します。対象がなければファイルを変更せず false を返します。
     * 選択中のアカウントを削除した場合は、削除後一覧の #1 を選択します。
     */
    @Synchronized
    public fun deleteAccount(accountId: String): Boolean {
        val updated = currentState.accounts.filterNot { it.accountId == accountId }
        if (updated.size == currentState.accounts.size) {
            return false
        }
        val selectedId = normalizeSelectedId(updated, currentState.selectedAccountId)
        val next = AccountStoreState(updated, selectedId)
        persist(next)
        currentState = next
        return true
    }

    /** deleteAccount の簡潔な別名です。 */
    @Synchronized
    public fun removeAccount(accountId: String): Boolean = deleteAccount(accountId)

    @Synchronized
    public fun requireAccount(accountId: String): AccountSecrets =
        currentState.accounts.firstOrNull { it.accountId == accountId }
            ?: throw IllegalArgumentException("指定したアカウントがありません。")

    /** 外部変更・破損を検知した後に、主ファイルを再読込します。 */
    @Synchronized
    public fun reload(): AccountStoreState {
        currentState = loadInitial()
        return currentState
    }

    private fun loadInitial(): AccountStoreState {
        val primaryExists = storeFile.exists()
        val backupExists = backupFile.exists()
        if (!primaryExists) {
            return if (backupExists) {
                recoverFromBackup(null)
            } else {
                AccountStoreState(emptyList(), null)
            }
        }
        if (!storeFile.isFile) {
            throw AccountStoreException("アカウント保存データを読み込めません。")
        }

        val decoded = try {
            readDocument(storeFile)
        } catch (failure: AccountStoreException) {
            return recoverFromBackup(failure)
        }

        ensureBackupIsValid(storeFile)
        if (decoded.needsRewrite) {
            persist(decoded.state)
        }
        return decoded.state
    }

    private fun recoverFromBackup(primaryFailure: AccountStoreException?): AccountStoreState {
        if (!backupFile.exists()) {
            if (primaryFailure != null) {
                throw primaryFailure
            }
            throw AccountStoreException("アカウント保存データを復旧できません。")
        }
        if (!backupFile.isFile) {
            throw AccountStoreException("アカウント保存データを復旧できません。")
        }

        val decoded = try {
            readDocument(backupFile)
        } catch (failure: AccountStoreException) {
            throw AccountStoreException("アカウント保存データを復旧できません。", failure)
        }

        try {
            ensureParentDirectory()
            copyFileAtomically(backupFile, storeFile)
            if (decoded.needsRewrite) {
                persist(decoded.state)
            }
            return decoded.state
        } catch (failure: AccountStoreException) {
            throw AccountStoreException("アカウント保存データを復旧できません。", failure)
        }
    }

    private fun ensureBackupIsValid(primary: File) {
        if (backupFile.isFile && isValidStoreFile(backupFile)) {
            return
        }
        writeBackupFrom(primary)
    }

    private fun isValidStoreFile(file: File): Boolean =
        try {
            readDocument(file)
            true
        } catch (_: AccountStoreException) {
            false
        }

    private fun readDocument(file: File): DecodedDocument {
        try {
            if (file.length() > MAX_FILE_SIZE_BYTES) invalidDocument()
            val bytes = FileInputStream(file).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= MAX_FILE_SIZE_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.toByteArray().also {
                    if (it.size > MAX_FILE_SIZE_BYTES) invalidDocument()
                }
            }
            return decodeDocument(String(bytes, StandardCharsets.UTF_8))
        } catch (failure: StoreFormatException) {
            throw AccountStoreException("アカウント保存データを読み込めません。", failure)
        } catch (failure: IllegalArgumentException) {
            throw AccountStoreException("アカウント保存データを読み込めません。", failure)
        } catch (failure: IOException) {
            throw AccountStoreException("アカウント保存データを読み込めません。", failure)
        } catch (failure: Exception) {
            throw AccountStoreException("アカウント保存データを読み込めません。", failure)
        }
    }

    private fun decodeDocument(json: String): DecodedDocument {
        val root = JsonParser(json).parse()
        val document = root as? JsonObjectNode ?: invalidDocument()

        val schemaVersion = requiredInteger(document, "schemaVersion")
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            invalidDocument()
        }

        val accountsNode = required(document, "accounts") as? JsonArrayNode
            ?: invalidDocument()
        if (accountsNode.values.size > MAX_ACCOUNTS) {
            invalidDocument()
        }

        val accounts = ArrayList<AccountSecrets>(accountsNode.values.size)
        val ids = HashSet<String>(accountsNode.values.size)
        accountsNode.values.forEach { accountNode ->
            val account = decodeAccount(accountNode as? JsonObjectNode ?: invalidDocument())
            if (!ids.add(account.accountId)) {
                invalidDocument()
            }
            accounts += account
        }

        val selectedNode = required(document, "selectedAccountId")
        val storedSelectedId = when (selectedNode) {
            JsonNullNode -> null
            is JsonStringNode -> selectedNode.value
            else -> invalidDocument()
        }
        val selectedId = normalizeSelectedId(accounts, storedSelectedId)
        return DecodedDocument(
            state = AccountStoreState(accounts.toList(), selectedId),
            needsRewrite = storedSelectedId != selectedId,
        )
    }

    private fun decodeAccount(document: JsonObjectNode): AccountSecrets =
        AccountSecrets(
            accountId = requiredString(document, "accountId"),
            userId = requiredString(document, "userId"),
            username = requiredString(document, "username"),
            displayName = requiredString(document, "displayName"),
            webBearerToken = requiredString(document, "webBearerToken"),
            authToken = requiredString(document, "authToken"),
            csrfToken = requiredString(document, "csrfToken"),
            profileName = requiredString(document, "profileName"),
        )

    private fun persist(next: AccountStoreState) {
        ensureParentDirectory()
        val encoded = encodeDocument(next)

        if (storeFile.isFile && isValidStoreFile(storeFile)) {
            writeBackupFrom(storeFile)
        } else if (!backupFile.isFile || !isValidStoreFile(backupFile)) {
            // 初回保存や両方のファイルが失われた場合も、主ファイルより先に
            // 次の正常状態をバックアップへ置いて、途中終了から復旧できるようにします。
            writeJsonAtomically(backupFile, encoded)
        }

        writeJsonAtomically(storeFile, encoded)
    }

    private fun writeJsonAtomically(target: File, content: String) {
        val temporary = File(target.path + ".tmp")
        try {
            writeBytesSynchronously(temporary, content.toByteArray(StandardCharsets.UTF_8))
            restrictToOwner(temporary)
            atomicReplace(temporary, target)
            restrictToOwner(target)
        } catch (failure: AccountStoreException) {
            throw failure
        } catch (failure: Exception) {
            throw AccountStoreException("アカウント保存データを書き込めません。", failure)
        } finally {
            temporary.delete()
        }
    }

    private fun writeBackupFrom(source: File) {
        if (!source.isFile) {
            throw AccountStoreException("アカウント保存データのバックアップを作成できません。")
        }
        copyFileAtomically(source, backupFile)
    }

    private fun copyFileAtomically(source: File, target: File) {
        val temporary = File(target.path + ".tmp")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            restrictToOwner(temporary)
            atomicReplace(temporary, target)
            restrictToOwner(target)
        } catch (failure: AccountStoreException) {
            throw failure
        } catch (failure: Exception) {
            throw AccountStoreException("アカウント保存データを書き込めません。", failure)
        } finally {
            temporary.delete()
        }
    }

    private fun writeBytesSynchronously(target: File, bytes: ByteArray) {
        FileOutputStream(target).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun ensureParentDirectory() {
        val parent = storeFile.absoluteFile.parentFile ?: return
        if (parent.exists()) {
            if (!parent.isDirectory) {
                throw AccountStoreException("アカウント保存先がディレクトリではありません。")
            }
            return
        }
        if (!parent.mkdirs() && !parent.isDirectory) {
            throw AccountStoreException("アカウント保存先を作成できません。")
        }
    }

    private fun restrictToOwner(file: File) {
        try {
            Files.setPosixFilePermissions(
                file.toPath(),
                EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // WindowsやPOSIX権限を提供しないファイルシステムではACLへ委ねます。
        } catch (failure: IOException) {
            throw AccountStoreException("アカウント保存ファイルの権限を設定できません。", failure)
        }
    }

    private fun encodeDocument(state: AccountStoreState): String = buildString {
        append('{')
        append("\"schemaVersion\":")
        append(CURRENT_SCHEMA_VERSION)
        append(",\"accounts\":[")
        state.accounts.forEachIndexed { index, account ->
            if (index > 0) {
                append(',')
            }
            append('{')
            append("\"accountId\":")
            appendJsonString(account.accountId)
            append(",\"userId\":")
            appendJsonString(account.userId)
            append(",\"username\":")
            appendJsonString(account.username)
            append(",\"displayName\":")
            appendJsonString(account.displayName)
            append(",\"webBearerToken\":")
            appendJsonString(account.webBearerToken)
            append(",\"authToken\":")
            appendJsonString(account.authToken)
            append(",\"csrfToken\":")
            appendJsonString(account.csrfToken)
            append(",\"profileName\":")
            appendJsonString(account.profileName)
            append('}')
        }
        append("],\"selectedAccountId\":")
        if (state.selectedAccountId == null) {
            append("null")
        } else {
            appendJsonString(state.selectedAccountId)
        }
        append('}')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    private fun required(document: JsonObjectNode, name: String): JsonNode =
        document.values[name] ?: invalidDocument()

    private fun requiredString(document: JsonObjectNode, name: String): String =
        (required(document, name) as? JsonStringNode)?.value ?: invalidDocument()

    private fun requiredInteger(document: JsonObjectNode, name: String): Int =
        (required(document, name) as? JsonNumberNode)?.raw?.toIntOrNull()
            ?: invalidDocument()

    private fun invalidDocument(): Nothing =
        throw StoreFormatException()

    private fun normalizeSelectedId(
        accounts: List<AccountSecrets>,
        selectedId: String?,
    ): String? {
        if (selectedId != null && accounts.any { it.accountId == selectedId }) {
            return selectedId
        }
        return accounts.firstOrNull()?.accountId
    }

    private data class DecodedDocument(
        val state: AccountStoreState,
        val needsRewrite: Boolean,
    )
}

public typealias AccountSummary = AccountStore.AccountSummary

private fun checkedStoreFile(file: File): File {
    require(file.path.isNotBlank()) { "アカウント保存ファイルを指定してください。" }
    require(file.name.isNotBlank()) { "アカウント保存ファイル名を指定してください。" }
    return file.absoluteFile
}

private class StoreFormatException : IllegalArgumentException("アカウント保存データの形式が不正です。")

private sealed interface JsonNode

private class JsonObjectNode(
    val values: LinkedHashMap<String, JsonNode>,
) : JsonNode

private class JsonArrayNode(
    val values: List<JsonNode>,
) : JsonNode

private class JsonStringNode(
    val value: String,
) : JsonNode

private class JsonNumberNode(
    val raw: String,
) : JsonNode

private object JsonTrueNode : JsonNode

private object JsonFalseNode : JsonNode

private object JsonNullNode : JsonNode

private class JsonParser(
    private val source: String,
) {
    private var position: Int = 0

    fun parse(): JsonNode {
        skipWhitespace()
        val value = parseValue(0)
        skipWhitespace()
        if (position != source.length) {
            invalid()
        }
        return value
    }

    private fun parseValue(depth: Int): JsonNode {
        if (depth > 64) invalid()
        if (position >= source.length) {
            invalid()
        }
        return when (source[position]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> JsonStringNode(parseString())
            't' -> {
                expectLiteral("true")
                JsonTrueNode
            }
            'f' -> {
                expectLiteral("false")
                JsonFalseNode
            }
            'n' -> {
                expectLiteral("null")
                JsonNullNode
            }
            '-', in '0'..'9' -> JsonNumberNode(parseNumber())
            else -> invalid()
        }
    }

    private fun parseObject(depth: Int): JsonNode {
        position++
        skipWhitespace()
        val values = LinkedHashMap<String, JsonNode>()
        if (consume('}')) {
            return JsonObjectNode(values)
        }

        while (true) {
            if (position >= source.length || source[position] != '"') {
                invalid()
            }
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue(depth + 1)
            if (values.containsKey(key)) {
                invalid()
            }
            values[key] = value
            skipWhitespace()
            if (consume('}')) {
                return JsonObjectNode(values)
            }
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseArray(depth: Int): JsonNode {
        position++
        skipWhitespace()
        val values = ArrayList<JsonNode>()
        if (consume(']')) {
            return JsonArrayNode(values)
        }

        while (true) {
            values += parseValue(depth + 1)
            skipWhitespace()
            if (consume(']')) {
                return JsonArrayNode(values)
            }
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseString(): String {
        if (position >= source.length || source[position] != '"') {
            invalid()
        }
        position++
        val value = StringBuilder()
        while (position < source.length) {
            val character = source[position++]
            when (character) {
                '"' -> return value.toString()
                '\\' -> {
                    if (position >= source.length) {
                        invalid()
                    }
                    when (source[position++]) {
                        '"' -> value.append('"')
                        '\\' -> value.append('\\')
                        '/' -> value.append('/')
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000c')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> value.append(parseUnicodeEscape())
                        else -> invalid()
                    }
                }
                else -> {
                    if (character.code < 0x20) {
                        invalid()
                    }
                    value.append(character)
                }
            }
        }
        invalid()
    }

    private fun parseUnicodeEscape(): Char {
        if (position + 4 > source.length) {
            invalid()
        }
        var code = 0
        repeat(4) {
            val digit = Character.digit(source[position++], 16)
            if (digit < 0) {
                invalid()
            }
            code = code * 16 + digit
        }
        return code.toChar()
    }

    private fun parseNumber(): String {
        val start = position
        if (source[position] == '-') {
            position++
            if (position >= source.length) {
                invalid()
            }
        }

        when {
            source[position] == '0' -> position++
            source[position] in '1'..'9' -> {
                while (position < source.length && source[position].isDigit()) {
                    position++
                }
            }
            else -> invalid()
        }

        if (position < source.length && source[position] == '.') {
            position++
            if (position >= source.length || !source[position].isDigit()) {
                invalid()
            }
            while (position < source.length && source[position].isDigit()) {
                position++
            }
        }

        if (position < source.length && (source[position] == 'e' || source[position] == 'E')) {
            position++
            if (position < source.length && (source[position] == '+' || source[position] == '-')) {
                position++
            }
            if (position >= source.length || !source[position].isDigit()) {
                invalid()
            }
            while (position < source.length && source[position].isDigit()) {
                position++
            }
        }
        return source.substring(start, position)
    }

    private fun expect(character: Char) {
        if (position >= source.length || source[position] != character) {
            invalid()
        }
        position++
    }

    private fun expectLiteral(literal: String) {
        if (!source.startsWith(literal, position)) {
            invalid()
        }
        position += literal.length
    }

    private fun consume(character: Char): Boolean {
        if (position < source.length && source[position] == character) {
            position++
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (position < source.length) {
            when (source[position]) {
                ' ', '\t', '\n', '\r' -> position++
                else -> return
            }
        }
    }

    private fun invalid(): Nothing = throw StoreFormatException()
}

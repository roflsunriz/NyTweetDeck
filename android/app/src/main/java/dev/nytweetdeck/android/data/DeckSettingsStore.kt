package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.DeckColumn
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.MainMenuItemId
import dev.nytweetdeck.android.model.DefaultMainMenuItems
import dev.nytweetdeck.android.model.RankingMode
import dev.nytweetdeck.android.model.ThemeMode
import dev.nytweetdeck.android.model.AppFontSize
import dev.nytweetdeck.android.model.AccentColor
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale

private const val MAX_JSON_DEPTH = 64

/**
 * Persists the Android deck layout without involving Android framework APIs.
 *
 * The file contains only layout state. Accounts and authentication state are deliberately
 * not part of the document and are always reset after loading.
 */
class DeckSettingsStore(filePath: Path) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 7
        const val MAX_FILE_SIZE_BYTES: Int = 1 shl 20

        private const val MAX_COLUMN_ID_LENGTH = 200
        private const val MAX_COLUMN_TITLE_LENGTH = 500

        private val ROOT_KEYS_V1_V2 = setOf(
            "schemaVersion",
            "columns",
            "selectedMenu",
            "useDarkTheme",
            "compactDensity",
        )
        private val ROOT_KEYS_V3 = ROOT_KEYS_V1_V2 + "mainMenuItems"
        private val ROOT_KEYS_V4 = ROOT_KEYS_V3 + "replySort"
        private val ROOT_KEYS_V5 = ROOT_KEYS_V4 + setOf(
            "themeMode",
            "fontSize",
            "accentColor",
            "reduceMotion",
            "mediaPreview",
            "videoAutoplay",
            "videoLoop",
            "videoVolume",
        )
        private val ROOT_KEYS_V6 = ROOT_KEYS_V5 + "trendSearchHistory"
        private val ROOT_KEYS_V7 = ROOT_KEYS_V6 + "autoTranslatePosts"
        private val COLUMN_KEYS_V1 = setOf("id", "kind", "title")
        private val COLUMN_KEYS_V2 = setOf("id", "kind", "title", "target")
    }

    val primaryPath: Path = filePath.toAbsolutePath().normalize()
    val backupPath: Path = primaryPath.resolveSibling("${primaryPath.fileName}.bak")

    init {
        require(primaryPath.fileName != null) { "設定ファイルのパスが不正です。" }
    }

    /** Loads primary, then backup, and finally the safe default state. */
    @Synchronized
    fun load(): DeckUiState {
        readValid(primaryPath)?.let { return it.state }

        val backup = readValid(backupPath)
        if (backup != null) {
            restorePrimary(backup.bytes)
            return backup.state
        }

        return DeckUiState()
    }

    /**
     * Saves a validated layout. A valid previous primary becomes the backup; the first save
     * creates a backup from the new document so recovery is available immediately.
     */
    @Synchronized
    fun save(state: DeckUiState) {
        val validated = validateState(state)
        val bytes = encode(validated)
        require(bytes.size <= MAX_FILE_SIZE_BYTES) {
            "設定ファイルが1MiBを超えています。"
        }

        try {
            Files.createDirectories(requireNotNull(primaryPath.parent))
        } catch (exception: IOException) {
            throw DeckSettingsStoreException("設定ファイルの保存先を作成できません。", exception)
        }

        val previousPrimary = readValid(primaryPath)
        if (previousPrimary != null) {
            writeAtomically(backupPath, previousPrimary.bytes)
        } else if (readValid(backupPath) == null) {
            writeAtomically(backupPath, bytes)
        }
        writeAtomically(primaryPath, bytes)
    }

    private fun restorePrimary(bytes: ByteArray) {
        writeAtomically(primaryPath, bytes)
    }

    private fun readValid(path: Path): StoredDocument? {
        if (!Files.isRegularFile(path)) return null

        return try {
            val bytes = readBounded(path)
            StoredDocument(decode(bytes), bytes)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun readBounded(path: Path): ByteArray {
        if (Files.size(path) > MAX_FILE_SIZE_BYTES) {
            throw JsonFormatException("設定ファイルが1MiBを超えています。")
        }

        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_FILE_SIZE_BYTES) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            val bytes = output.toByteArray()
            if (bytes.size > MAX_FILE_SIZE_BYTES) {
                throw JsonFormatException("設定ファイルが1MiBを超えています。")
            }
            return bytes
        }
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        require(bytes.size <= MAX_FILE_SIZE_BYTES) {
            "設定ファイルが1MiBを超えています。"
        }

        val parent = requireNotNull(target.parent)
        var temporary: Path? = null
        try {
            Files.createDirectories(parent)
            val temp = Files.createTempFile(parent, "nytd-settings-", ".tmp")
            temporary = temp
            writeAndForce(temp, bytes)
            restrictToOwner(temp)
            atomicReplace(temp, target)
            temporary = null
            restrictToOwner(target)
        } catch (exception: IOException) {
            throw DeckSettingsStoreException("設定ファイルを書き込めません。", exception)
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary)
                } catch (_: IOException) {
                    // The original write failure is more useful to the caller.
                }
            }
        }
    }

    private fun writeAndForce(path: Path, bytes: ByteArray) {
        FileChannel.open(
            path,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
    }

    private fun atomicReplace(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun restrictToOwner(path: Path) {
        try {
            Files.setPosixFilePermissions(
                path,
                EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows and other non-POSIX file systems use their existing ACLs.
        }
    }

    private fun validateState(state: DeckUiState): DeckUiState {
        val ids = HashSet<String>(state.columns.size)
        state.columns.forEachIndexed { index, column ->
            val nonNullColumn = requireNotNull(column) {
                "カラム設定[$index]が不正です。"
            }
            validateText(
                nonNullColumn.id,
                MAX_COLUMN_ID_LENGTH,
                "カラムID[$index]",
                trimRequired = true,
            )
            require(ids.add(nonNullColumn.id)) {
                "カラムIDが重複しています。"
            }
            requireNotNull(nonNullColumn.kind) {
                "カラム種別[$index]が不正です。"
            }
            validateText(
                nonNullColumn.title,
                MAX_COLUMN_TITLE_LENGTH,
                "カラム名[$index]",
            )
            when (nonNullColumn.kind) {
                ColumnKind.SEARCH -> validateText(
                    requireNotNull(nonNullColumn.target) { "検索カラム[$index]に対象がありません。" },
                    200,
                    "検索対象[$index]",
                    trimRequired = true,
                )
                ColumnKind.USER, ColumnKind.LIST -> require(
                    nonNullColumn.target?.matches(Regex("[0-9]{1,24}")) == true,
                ) { "対象ID[$index]が不正です。" }
                else -> require(nonNullColumn.target == null) { "対象不要なカラムに対象があります。" }
            }
        }
        requireNotNull(state.selectedMenu) { "選択メニューが不正です。" }
        require(state.mainMenuItems.size <= MainMenuItemId.entries.size) { "メニュー項目数が不正です。" }
        require(state.mainMenuItems.distinct().size == state.mainMenuItems.size) { "メニュー項目が重複しています。" }
        require(state.videoVolume in 0..100) { "動画音量が範囲外です。" }
        require(state.trendSearchHistory.size <= 20) { "トレンド検索履歴が20件を超えています。" }
        require(state.trendSearchHistory.distinctBy { it.lowercase(Locale.ROOT) }.size == state.trendSearchHistory.size) {
            "トレンド検索履歴が重複しています。"
        }
        state.trendSearchHistory.forEach { history ->
            require(history.isNotBlank() && history.length <= 100 && history == history.trim()) {
                "トレンド検索履歴が不正です。"
            }
        }

        return state.copy(
            columns = state.columns.toList(),
            accounts = emptyList(),
            selectedAccountId = null,
            accountAuthStatus = dev.nytweetdeck.android.model.AccountAuthStatus.IDLE,
            timelines = emptyMap(),
            notifications = emptyMap(),
            trends = emptyMap(),
            messages = emptyMap(),
            columnScrollPositions = emptyMap(),
            pendingPostActions = emptyMap(),
            failedPostActions = emptyMap(),
            composer = dev.nytweetdeck.android.model.ComposerUiState(),
            postDetail = dev.nytweetdeck.android.model.PostDetailUiState(),
            articleReader = dev.nytweetdeck.android.model.ArticleReaderUiState(),
            communityNote = dev.nytweetdeck.android.model.CommunityNoteUiState(),
            postTranslations = emptyMap(),
            translationHealth = null,
            targetPicker = dev.nytweetdeck.android.model.TargetPickerState(),
        )
    }

    private fun validateText(
        value: String,
        maximumLength: Int,
        label: String,
        trimRequired: Boolean = false,
    ) {
        require(value.isNotBlank()) { "${label}が空です。" }
        require(value.length <= maximumLength) { "${label}が長すぎます。" }
        require(value.none { it.code < 0x20 || it.code == 0x7f }) {
            "${label}に制御文字があります。"
        }
        if (trimRequired) {
            require(value == value.trim()) { "${label}の前後に空白があります。" }
        }
    }

    private fun encode(state: DeckUiState): ByteArray {
        val json = buildString {
            append('{')
            append("\"schemaVersion\":")
            append(CURRENT_SCHEMA_VERSION)
            append(",\"columns\":[")
            state.columns.forEachIndexed { index, column ->
                if (index > 0) append(',')
                append("{\"id\":")
                appendJsonString(column.id)
                append(",\"kind\":")
                appendJsonString(column.kind.name)
                append(",\"title\":")
                appendJsonString(column.title)
                append(",\"target\":")
                if (column.target == null) append("null") else appendJsonString(column.target)
                append('}')
            }
            append("],\"selectedMenu\":")
            appendJsonString(state.selectedMenu.name)
            append(",\"useDarkTheme\":")
            append(state.useDarkTheme)
            append(",\"compactDensity\":")
            append(state.compactDensity)
            append(",\"mainMenuItems\":[")
            state.mainMenuItems.forEachIndexed { index, item ->
                if (index > 0) append(',')
                appendJsonString(item.name)
            }
            append(']')
            append(",\"replySort\":")
            appendJsonString(state.replySort.name)
            append(",\"themeMode\":")
            appendJsonString(state.themeMode.name)
            append(",\"fontSize\":")
            appendJsonString(state.fontSize.name)
            append(",\"accentColor\":")
            appendJsonString(state.accentColor.name)
            append(",\"reduceMotion\":")
            append(state.reduceMotion)
            append(",\"mediaPreview\":")
            append(state.mediaPreview)
            append(",\"videoAutoplay\":")
            append(state.videoAutoplay)
            append(",\"videoLoop\":")
            append(state.videoLoop)
            append(",\"videoVolume\":")
            append(state.videoVolume)
            append(",\"trendSearchHistory\":[")
            state.trendSearchHistory.forEachIndexed { index, history ->
                if (index > 0) append(',')
                appendJsonString(history)
            }
            append(']')
            append(",\"autoTranslatePosts\":")
            append(state.autoTranslatePosts)
            append('}')
        }
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): DeckUiState {
        val root = DeckSettingsJsonParser(decodeUtf8(bytes)).parse() as? JsonObject
            ?: invalid("設定JSONのルートがオブジェクトではありません。")
        val schemaVersion = root.requireNumber("schemaVersion").asInt("schemaVersion")
        if (schemaVersion !in 1..CURRENT_SCHEMA_VERSION) {
            invalid("未対応の設定schemaVersionです。")
        }
        requireExactKeys(
            root,
            when {
                schemaVersion < 3 -> ROOT_KEYS_V1_V2
                schemaVersion < 4 -> ROOT_KEYS_V3
                schemaVersion < 5 -> ROOT_KEYS_V4
                schemaVersion < 6 -> ROOT_KEYS_V5
                schemaVersion < 7 -> ROOT_KEYS_V6
                else -> ROOT_KEYS_V7
            },
        )

        val columns = root.requireArray("columns").values.mapIndexed { index, value ->
            val column = value as? JsonObject
                ?: invalid("カラム設定[$index]がオブジェクトではありません。")
            requireExactKeys(column, if (schemaVersion == 1) COLUMN_KEYS_V1 else COLUMN_KEYS_V2)
            val id = column.requireString("id")
            val kind = parseColumnKind(column.requireString("kind"), index)
            val title = column.requireString("title")
            val target = if (schemaVersion == 1) {
                null
            } else {
                when (val value = column.requireValue("target")) {
                    JsonNull -> null
                    is JsonString -> value.value
                    else -> invalid("カラム対象[$index]が文字列ではありません。")
                }
            }
            DeckColumn(id = id, kind = kind, title = title, target = target)
        }

        val state = DeckUiState(
            columns = columns,
            selectedMenu = parseColumnKind(root.requireString("selectedMenu"), null),
            useDarkTheme = root.requireBoolean("useDarkTheme"),
            compactDensity = root.requireBoolean("compactDensity"),
            replySort = if (schemaVersion < 4) {
                RankingMode.RELEVANCE
            } else {
                runCatching { RankingMode.valueOf(root.requireString("replySort")) }
                    .getOrElse { invalid("返信並び順が不正です。") }
            },
            themeMode = if (schemaVersion < 5) {
                if (root.requireBoolean("useDarkTheme")) ThemeMode.DARK else ThemeMode.LIGHT
            } else {
                parseEnumValue<ThemeMode>(root.requireString("themeMode"), "テーマ")
            },
            fontSize = if (schemaVersion < 5) {
                AppFontSize.DEFAULT
            } else {
                parseEnumValue<AppFontSize>(root.requireString("fontSize"), "文字サイズ")
            },
            accentColor = if (schemaVersion < 5) {
                AccentColor.BLUE
            } else {
                parseEnumValue<AccentColor>(root.requireString("accentColor"), "アクセント色")
            },
            reduceMotion = schemaVersion >= 5 && root.requireBoolean("reduceMotion"),
            mediaPreview = schemaVersion < 5 || root.requireBoolean("mediaPreview"),
            videoAutoplay = schemaVersion >= 5 && root.requireBoolean("videoAutoplay"),
            videoLoop = schemaVersion < 5 || root.requireBoolean("videoLoop"),
            videoVolume = if (schemaVersion < 5) 100 else root.requireNumber("videoVolume")
                .asInt("videoVolume"),
            trendSearchHistory = if (schemaVersion < 6) {
                emptyList()
            } else {
                root.requireArray("trendSearchHistory").values.mapIndexed { index, value ->
                    (value as? JsonString)?.value
                        ?: invalid("トレンド検索履歴[$index]が文字列ではありません。")
                }
            },
            autoTranslatePosts = schemaVersion < 7 || root.requireBoolean("autoTranslatePosts"),
            mainMenuItems = if (schemaVersion < 3) {
                DefaultMainMenuItems
            } else {
                root.requireArray("mainMenuItems").values.map { value ->
                    val name = (value as? JsonString)?.value
                        ?: invalid("メニュー項目が文字列ではありません。")
                    runCatching { MainMenuItemId.valueOf(name) }
                        .getOrElse { invalid("メニュー項目が不正です。") }
                }
            },
        )
        return validateState(state)
    }

    private fun parseColumnKind(value: String, index: Int?): ColumnKind {
        if (value == "HOME") return ColumnKind.HOME_FOR_YOU
        return try {
            ColumnKind.valueOf(value)
        } catch (_: IllegalArgumentException) {
            val suffix = index?.let { "[$it]" }.orEmpty()
            invalid("カラム種別${suffix}が不正です。")
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (exception: CharacterCodingException) {
            throw JsonFormatException("設定JSONがUTF-8ではありません。", exception)
        }
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
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

    private data class StoredDocument(
        val state: DeckUiState,
        val bytes: ByteArray,
    )

    class DeckSettingsStoreException(message: String, cause: Throwable) : RuntimeException(message, cause)

}

private sealed interface JsonValue

private data class JsonObject(val values: LinkedHashMap<String, JsonValue>) : JsonValue

private data class JsonArray(val values: List<JsonValue>) : JsonValue

private data class JsonString(val value: String) : JsonValue

private data class JsonNumber(val raw: String) : JsonValue

private data class JsonBoolean(val value: Boolean) : JsonValue

private object JsonNull : JsonValue

private class JsonFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

private fun invalid(message: String): Nothing = throw JsonFormatException(message)

private inline fun <reified T : Enum<T>> parseEnumValue(value: String, label: String): T =
    runCatching { enumValueOf<T>(value) }.getOrElse { invalid("${label}が不正です。") }

private fun requireExactKeys(value: JsonObject, expected: Set<String>) {
    if (value.values.keys.toSet() != expected) {
        invalid("設定JSONの項目が不正です。")
    }
}

private fun JsonObject.requireValue(key: String): JsonValue =
    values[key] ?: invalid("設定JSONに${key}がありません。")

private fun JsonObject.requireString(key: String): String =
    (requireValue(key) as? JsonString)?.value ?: invalid("設定JSONの${key}が文字列ではありません。")

private fun JsonObject.requireNumber(key: String): JsonNumber =
    (requireValue(key) as? JsonNumber) ?: invalid("設定JSONの${key}が数値ではありません。")

private fun JsonObject.requireArray(key: String): JsonArray =
    (requireValue(key) as? JsonArray) ?: invalid("設定JSONの${key}が配列ではありません。")

private fun JsonObject.requireBoolean(key: String): Boolean =
    (requireValue(key) as? JsonBoolean)?.value
        ?: invalid("設定JSONの${key}が真偽値ではありません。")

private fun JsonNumber.asInt(key: String): Int {
    val value = raw.toLongOrNull() ?: invalid("設定JSONの${key}が整数ではありません。")
    if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
        invalid("設定JSONの${key}が範囲外です。")
    }
    return value.toInt()
}

private class DeckSettingsJsonParser(private val source: String) {
    private var index = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = parseValue(0)
        skipWhitespace()
        if (index != source.length) {
            invalid("設定JSONの末尾に不要な文字があります。")
        }
        return value
    }

    private fun parseValue(depth: Int): JsonValue {
        if (depth > 64) {
            invalid("設定JSONが深すぎます。")
        }
        skipWhitespace()
        return when (source.getOrNull(index)) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> JsonString(parseString())
            't' -> {
                consumeLiteral("true")
                JsonBoolean(true)
            }
            'f' -> {
                consumeLiteral("false")
                JsonBoolean(false)
            }
            'n' -> {
                consumeLiteral("null")
                JsonNull
            }
            '-', in '0'..'9' -> parseNumber()
            else -> invalid("設定JSONの値が不正です。")
        }
    }

    private fun parseObject(depth: Int): JsonObject {
        expect('{')
        skipWhitespace()
        val values = LinkedHashMap<String, JsonValue>()
        if (consume('}')) return JsonObject(values)

        while (true) {
            skipWhitespace()
            if (source.getOrNull(index) != '"') {
                invalid("設定JSONのオブジェクトキーが不正です。")
            }
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue(depth + 1)
            if (values.containsKey(key)) {
                invalid("設定JSONに重複した項目があります。")
            }
            values[key] = value
            skipWhitespace()
            if (consume('}')) return JsonObject(values)
            expect(',')
        }
    }

    private fun parseArray(depth: Int): JsonArray {
        expect('[')
        skipWhitespace()
        val values = ArrayList<JsonValue>()
        if (consume(']')) return JsonArray(values)

        while (true) {
            values += parseValue(depth + 1)
            skipWhitespace()
            if (consume(']')) return JsonArray(values)
            expect(',')
            skipWhitespace()
        }
    }

    private fun parseString(): String {
        expect('"')
        val value = StringBuilder()
        while (true) {
            val character = source.getOrNull(index++) ?: invalid("設定JSONの文字列が閉じていません。")
            when (character) {
                '"' -> return value.toString()
                '\\' -> value.append(parseEscape())
                else -> {
                    if (character.code < 0x20) {
                        invalid("設定JSONの文字列に制御文字があります。")
                    }
                    value.append(character)
                }
            }
        }
    }

    private fun parseEscape(): Char {
        return when (val escaped = source.getOrNull(index++)) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                var code = 0
                repeat(4) {
                    val digit = source.getOrNull(index++)?.hexValue()
                        ?: invalid("設定JSONのUnicodeエスケープが不正です。")
                    code = code * 16 + digit
                }
                code.toChar()
            }
            else -> invalid("設定JSONのエスケープが不正です。")
        }
    }

    private fun parseNumber(): JsonNumber {
        val start = index
        consume('-')
        when {
            consume('0') -> {
                if (source.getOrNull(index)?.isDigit() == true) {
                    invalid("設定JSONの数値が不正です。")
                }
            }
            source.getOrNull(index)?.let { it in '1'..'9' } == true -> {
                index++
                while (source.getOrNull(index)?.isDigit() == true) index++
            }
            else -> invalid("設定JSONの数値が不正です。")
        }

        if (consume('.')) {
            if (source.getOrNull(index)?.isDigit() != true) {
                invalid("設定JSONの小数部が不正です。")
            }
            while (source.getOrNull(index)?.isDigit() == true) index++
        }
        if (source.getOrNull(index) == 'e' || source.getOrNull(index) == 'E') {
            index++
            if (source.getOrNull(index) == '+' || source.getOrNull(index) == '-') index++
            if (source.getOrNull(index)?.isDigit() != true) {
                invalid("設定JSONの指数部が不正です。")
            }
            while (source.getOrNull(index)?.isDigit() == true) index++
        }
        return JsonNumber(source.substring(start, index))
    }

    private fun consumeLiteral(literal: String) {
        if (!source.startsWith(literal, index)) {
            invalid("設定JSONの値が不正です。")
        }
        index += literal.length
    }

    private fun expect(character: Char) {
        if (!consume(character)) {
            invalid("設定JSONの構文が不正です。")
        }
    }

    private fun consume(character: Char): Boolean {
        if (source.getOrNull(index) != character) return false
        index++
        return true
    }

    private fun skipWhitespace() {
        while (source.getOrNull(index)?.let { it == ' ' || it == '\t' || it == '\r' || it == '\n' } == true) {
            index++
        }
    }

    private fun Char.hexValue(): Int = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        in 'A'..'F' -> code - 'A'.code + 10
        else -> invalid("設定JSONのUnicodeエスケープが不正です。")
    }

    private fun Char.isDigit(): Boolean = this in '0'..'9'
}

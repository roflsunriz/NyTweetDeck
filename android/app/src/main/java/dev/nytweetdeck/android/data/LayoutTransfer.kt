package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.ColumnKind
import dev.nytweetdeck.android.model.DeckColumn
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.MainMenuItemId
import dev.nytweetdeck.android.model.RankingMode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Imports and exports the portable NyTweetDeck layout document.
 *
 * The Android state currently exposes fewer layout fields than the web client. The document
 * still uses the web AppLayout shape so files can move between clients. Fields not represented
 * by [DeckUiState] are validated and then ignored; the Android-specific selected menu is carried
 * by the known `layout.selectedMenu` extension.
 */
public object LayoutTransfer {
    public const val FORMAT: String = "NyTweetDeckSettings"
    public const val DOCUMENT_VERSION: Int = 1
    public const val LAYOUT_VERSION: Int = 8
    public const val MAX_JSON_SIZE_BYTES: Int = 1 shl 20

    /** Compatibility alias for callers that use the settings-store terminology. */
    public const val MAX_FILE_SIZE_BYTES: Int = MAX_JSON_SIZE_BYTES

    private const val MAX_COLUMN_ID_LENGTH = 200
    private const val MAX_COLUMN_TEXT_LENGTH = 500
    private const val MAX_ACCOUNT_ID_LENGTH = 200
    private const val MAX_TARGET_LENGTH = 500
    private const val MAX_HISTORY_QUERY_LENGTH = 100
    private const val MAX_HISTORY_ENTRIES = 20
    private val ROOT_KEYS = setOf("format", "version", "exportedAt", "layout")
    private val LAYOUT_KEYS = setOf(
        "version",
        "columns",
        "navItems",
        "locale",
        "theme",
        "activeAccountId",
        "replySort",
        "display",
        "trendSearchHistory",
    )
    private val ANDROID_LAYOUT_KEYS = LAYOUT_KEYS + "selectedMenu"
    private val COLUMN_KEYS = setOf("id", "kind", "target", "label")
    private val DISPLAY_KEYS = setOf(
        "fontSize",
        "accentColor",
        "density",
        "reduceMotion",
        "mediaPreview",
        "videoAutoplay",
        "videoLoop",
        "videoVolume",
        "autoTranslatePosts",
    )

    private val NAV_ITEM_IDS = setOf(
        "compose",
        "search",
        "home",
        "notifications",
        "messages",
        "trends",
        "following",
        "chat",
        "grok",
        "premium",
        "profile",
        "communities",
        "creatorStudio",
        "business",
        "ads",
        "spaces",
    )
    private val LOCALES = setOf("ja", "en", "zh", "hi", "es", "fr", "ar", "pt", "bn", "ru", "ur")
    private val THEMES = setOf("system", "light", "dark")
    private val REPLY_SORTS = setOf("relevance", "recency", "likes")
    private val FONT_SIZES = setOf("small", "default", "large")
    private val ACCENT_COLORS = setOf("blue", "yellow", "pink", "purple", "orange", "green")
    private val DENSITIES = setOf("comfortable", "compact")

    /**
     * The Android UI currently has a fixed five-item primary menu. It is exported as the
     * canonical navItems value while the selected item is stored in the known extension above.
     */

    /** Result of importing layout fields while retaining the account state supplied by the UI. */
    public data class ImportResult(
        public val state: DeckUiState,
        public val currentAccountId: String?,
    ) {
        /** Alias useful to UI code that names the returned state a layout. */
        public val layout: DeckUiState
            get() = state

        /** The account selection is deliberately taken from the current UI state. */
        public val selectedAccountId: String?
            get() = currentAccountId
    }

    /** Exports only portable menu, column, and display settings. */
    public fun exportSettings(state: DeckUiState, exportedAt: Instant = Instant.now()): String {
        validateExportState(state)
        val serialized = buildDocument(state, exportedAt.toString())
        requireSize(serialized.toByteArray(StandardCharsets.UTF_8).size)
        return serialized
    }

    /** String overload for callers that already have an ISO-8601 timestamp. */
    public fun exportSettings(state: DeckUiState, exportedAt: String): String {
        validateExportedAt(exportedAt)
        validateExportState(state)
        val serialized = buildDocument(state, exportedAt)
        requireSize(serialized.toByteArray(StandardCharsets.UTF_8).size)
        return serialized
    }

    /** Short alias matching the web transfer module's operation name. */
    public fun export(state: DeckUiState, exportedAt: Instant = Instant.now()): String =
        exportSettings(state, exportedAt)

    /**
     * Imports a document and returns a state ready to hand to the UI.
     *
     * Only the portable layout fields are replaced. Accounts, account UI models, the selected
     * account id, and the authentication status are copied from [currentState] unchanged.
     */
    public fun importSettings(serialized: String, currentState: DeckUiState): ImportResult {
        requireSize(serialized.toByteArray(StandardCharsets.UTF_8).size)
        val imported = decodeDocument(serialized, currentState)
        val state = currentState.copy(
            columns = imported.columns,
            selectedMenu = imported.selectedMenu,
            useDarkTheme = imported.useDarkTheme,
            compactDensity = imported.compactDensity,
            mainMenuItems = imported.mainMenuItems,
            replySort = imported.replySort,
        )
        return ImportResult(state = state, currentAccountId = currentState.selectedAccountId)
    }

    /** Byte-oriented overload with strict UTF-8 validation for Android file-picker callers. */
    public fun importSettings(serialized: ByteArray, currentState: DeckUiState): ImportResult =
        importSettings(decodeUtf8(serialized), currentState)

    /** Short alias matching the web transfer module's operation name. */
    public fun importLayoutSettings(serialized: String, currentState: DeckUiState): DeckUiState =
        importSettings(serialized, currentState).state

    private fun buildDocument(state: DeckUiState, exportedAt: String): String = buildString {
        append('{')
        append("\"format\":")
        appendJsonString(FORMAT)
        append(",\"version\":")
        append(DOCUMENT_VERSION)
        append(",\"exportedAt\":")
        appendJsonString(exportedAt)
        append(",\"layout\":{")
        append("\"version\":")
        append(LAYOUT_VERSION)
        append(",\"columns\":[")
        state.columns.forEachIndexed { index, column ->
            if (index > 0) append(',')
            append('{')
            append("\"id\":")
            appendJsonString(column.id)
            append(",\"kind\":")
            appendJsonString(toWebColumnKind(column.kind))
            append(",\"target\":")
            if (column.target == null) append("null") else appendJsonString(column.target)
            append(",\"label\":")
            appendJsonString(column.title)
            append('}')
        }
        append("],\"navItems\":[")
        state.mainMenuItems.forEachIndexed { index, item ->
            if (index > 0) append(',')
            appendJsonString(toWebMenuItem(item))
        }
        append("],\"locale\":\"ja\"")
        append(",\"theme\":")
        appendJsonString(if (state.useDarkTheme) "dark" else "light")
        append(",\"activeAccountId\":null")
        append(",\"replySort\":")
        appendJsonString(state.replySort.name.lowercase(Locale.ROOT))
        append(",\"display\":{")
        append("\"fontSize\":\"default\"")
        append(",\"accentColor\":\"blue\"")
        append(",\"density\":")
        appendJsonString(if (state.compactDensity) "compact" else "comfortable")
        append(",\"reduceMotion\":false")
        append(",\"mediaPreview\":true")
        append(",\"videoAutoplay\":false")
        append(",\"videoLoop\":true")
        append(",\"videoVolume\":100")
        append(",\"autoTranslatePosts\":true")
        append('}')
        append(",\"trendSearchHistory\":[]")
        append(",\"selectedMenu\":")
        appendJsonString(toWebColumnKind(state.selectedMenu))
        append('}')
        append('}')
        append('\n')
    }

    private fun decodeDocument(serialized: String, currentState: DeckUiState): ImportedValues {
        val root = try {
            StrictJsonParser(serialized).parse()
        } catch (exception: LayoutTransferException) {
            throw exception
        } catch (_: RuntimeException) {
            invalidJson()
        }
        val document = root as? TransferJsonObject ?: invalid("設定ファイルのルートがオブジェクトではありません。")
        requireExactKeys(document, ROOT_KEYS, "設定ファイル")

        if (document.requireString("format") != FORMAT) {
            invalid("NyTweetDeck設定ファイルではありません。")
        }
        if (document.requireInteger("version") != DOCUMENT_VERSION) {
            invalid("対応していない設定ファイルのバージョンです。")
        }
        validateExportedAt(document.requireString("exportedAt"))

        val layout = document.requireObject("layout")
        val layoutKeys = layout.values.keys.toSet()
        if (layoutKeys != LAYOUT_KEYS && layoutKeys != ANDROID_LAYOUT_KEYS) {
            invalidLayout("項目が不正です。")
        }

        if (layout.requireInteger("version") != LAYOUT_VERSION) {
            invalidLayout("のバージョンが未対応です。")
        }
        val locale = parseEnum(layout.requireString("locale"), LOCALES, "言語")
        val columns = parseColumns(layout.requireArray("columns"), locale)
        val mainMenuItems = parseNavItems(layout.requireArray("navItems"))
        val theme = parseEnum(layout.requireString("theme"), THEMES, "テーマ")
        validateNullableString(layout.requireValue("activeAccountId"), MAX_ACCOUNT_ID_LENGTH, "選択アカウントID")
        val replySort = RankingMode.fromReplySort(
            parseEnum(layout.requireString("replySort"), REPLY_SORTS, "返信並び順"),
        )
        val compactDensity = parseDisplay(layout.requireObject("display"))
        parseTrendSearchHistory(layout.requireArray("trendSearchHistory"))

        val selectedMenu = if ("selectedMenu" in layout.values) {
            parseColumnKind(layout.requireString("selectedMenu"), "選択メニュー")
        } else {
            currentState.selectedMenu
        }
        val useDarkTheme = when (theme) {
            "dark" -> true
            "light" -> false
            // The Android state has no system theme enum; retain its current effective value.
            else -> currentState.useDarkTheme
        }
        return ImportedValues(
            columns,
            selectedMenu,
            useDarkTheme,
            compactDensity,
            mainMenuItems,
            replySort,
        )
    }

    private fun parseColumns(array: TransferJsonArray, locale: String): List<DeckColumn> {
        val ids = HashSet<String>(array.values.size)
        return array.values.mapIndexed { index, value ->
            val column = value as? TransferJsonObject
                ?: invalidLayout("カラム設定[$index]がオブジェクトではありません。")
            requireExactKeys(column, COLUMN_KEYS, "カラム設定[$index]")
            val id = column.requireString("id")
            validateText(id, MAX_COLUMN_ID_LENGTH, "カラムID[$index]", trimRequired = true)
            if (!ids.add(id)) {
                invalidLayout("カラムIDが重複しています。")
            }
            val kind = parseColumnKind(column.requireString("kind"), "カラム種別[$index]")
            val target = validateNullableString(column.requireValue("target"), MAX_TARGET_LENGTH, "カラム対象[$index]")
            validateColumnTarget(kind, target, index)
            val label = validateNullableString(column.requireValue("label"), MAX_COLUMN_TEXT_LENGTH, "カラム名[$index]")
            val title = label?.takeIf(String::isNotBlank)
                ?: target?.takeIf(String::isNotBlank)
                ?: defaultColumnTitle(kind, locale)
            DeckColumn(id = id, kind = kind, title = title, target = target)
        }
    }

    private fun parseNavItems(array: TransferJsonArray): List<MainMenuItemId> {
        val seen = HashSet<String>(array.values.size)
        return array.values.mapIndexed { index, value ->
            val item = value as? TransferJsonString
                ?: invalidLayout("メニュー項目[$index]が文字列ではありません。")
            if (item.value !in NAV_ITEM_IDS) {
                invalidLayout("メニュー項目[$index]の列挙値が不正です。")
            }
            if (!seen.add(item.value)) {
                invalidLayout("メニュー項目が重複しています。")
            }
            fromWebMenuItem(item.value)
        }
    }

    private fun fromWebMenuItem(value: String): MainMenuItemId = when (value) {
        "compose" -> MainMenuItemId.COMPOSE
        "search" -> MainMenuItemId.SEARCH
        "home" -> MainMenuItemId.HOME
        "notifications" -> MainMenuItemId.NOTIFICATIONS
        "messages" -> MainMenuItemId.MESSAGES
        "trends" -> MainMenuItemId.TRENDS
        "following" -> MainMenuItemId.FOLLOWING
        "chat" -> MainMenuItemId.CHAT
        "grok" -> MainMenuItemId.GROK
        "premium" -> MainMenuItemId.PREMIUM
        "profile" -> MainMenuItemId.PROFILE
        "communities" -> MainMenuItemId.COMMUNITIES
        "creatorStudio" -> MainMenuItemId.CREATOR_STUDIO
        "business" -> MainMenuItemId.BUSINESS
        "ads" -> MainMenuItemId.ADS
        "spaces" -> MainMenuItemId.SPACES
        else -> invalid("メニュー項目が不正です。")
    }

    private fun toWebMenuItem(value: MainMenuItemId): String = when (value) {
        MainMenuItemId.COMPOSE -> "compose"
        MainMenuItemId.SEARCH -> "search"
        MainMenuItemId.HOME -> "home"
        MainMenuItemId.NOTIFICATIONS -> "notifications"
        MainMenuItemId.MESSAGES -> "messages"
        MainMenuItemId.TRENDS -> "trends"
        MainMenuItemId.FOLLOWING -> "following"
        MainMenuItemId.CHAT -> "chat"
        MainMenuItemId.GROK -> "grok"
        MainMenuItemId.PREMIUM -> "premium"
        MainMenuItemId.PROFILE -> "profile"
        MainMenuItemId.COMMUNITIES -> "communities"
        MainMenuItemId.CREATOR_STUDIO -> "creatorStudio"
        MainMenuItemId.BUSINESS -> "business"
        MainMenuItemId.ADS -> "ads"
        MainMenuItemId.SPACES -> "spaces"
    }

    private fun parseDisplay(display: TransferJsonObject): Boolean {
        requireExactKeys(display, DISPLAY_KEYS, "表示設定")
        parseEnum(display.requireString("fontSize"), FONT_SIZES, "文字サイズ")
        parseEnum(display.requireString("accentColor"), ACCENT_COLORS, "アクセントカラー")
        val density = parseEnum(display.requireString("density"), DENSITIES, "表示密度")
        requireBoolean(display, "reduceMotion")
        requireBoolean(display, "mediaPreview")
        requireBoolean(display, "videoAutoplay")
        requireBoolean(display, "videoLoop")
        val volume = display.requireInteger("videoVolume")
        if (volume !in 0..100) {
            invalidLayout("表示設定の動画音量が範囲外です。")
        }
        requireBoolean(display, "autoTranslatePosts")
        return density == "compact"
    }

    private fun parseTrendSearchHistory(array: TransferJsonArray) {
        if (array.values.size > MAX_HISTORY_ENTRIES) {
            invalidLayout("トレンド検索履歴が20件を超えています。")
        }
        val seen = HashSet<String>(array.values.size)
        array.values.forEachIndexed { index, value ->
            val query = value as? TransferJsonString
                ?: invalidLayout("トレンド検索履歴[$index]が文字列ではありません。")
            validateText(
                query.value,
                MAX_HISTORY_QUERY_LENGTH,
                "トレンド検索履歴[$index]",
                trimRequired = true,
            )
            if (!seen.add(query.value.lowercase(Locale.ROOT))) {
                invalidLayout("トレンド検索履歴が重複しています。")
            }
        }
    }

    private fun validateExportState(state: DeckUiState) {
        val ids = HashSet<String>(state.columns.size)
        state.columns.forEachIndexed { index, column ->
            validateText(column.id, MAX_COLUMN_ID_LENGTH, "カラムID[$index]", trimRequired = true)
            if (!ids.add(column.id)) {
                invalid("カラムIDが重複しています。")
            }
            validateText(column.title, MAX_COLUMN_TEXT_LENGTH, "カラム名[$index]")
            column.target?.let {
                validateText(it, MAX_TARGET_LENGTH, "カラム対象[$index]", trimRequired = true)
            }
            validateColumnTarget(column.kind, column.target, index)
        }
    }

    private fun validateColumnTarget(kind: ColumnKind, target: String?, index: Int) {
        when (kind) {
            ColumnKind.SEARCH -> validateText(
                target ?: invalid("検索カラム[$index]に対象がありません。"),
                200,
                "検索対象[$index]",
                trimRequired = true,
            )
            ColumnKind.USER, ColumnKind.LIST -> if (target?.matches(Regex("[0-9]{1,24}")) != true) {
                invalid("対象ID[$index]が不正です。")
            }
            else -> if (target != null) invalid("対象不要なカラム[$index]に対象があります。")
        }
    }

    private fun validateExportedAt(value: String) {
        if (value.any { it.code < 0x20 || it.code == 0x7f } || runCatching { Instant.parse(value) }.isFailure) {
            invalid("設定ファイルの出力日時が不正です。")
        }
    }

    private fun validateText(value: String, maximumLength: Int, label: String, trimRequired: Boolean = false) {
        if (value.isBlank()) invalid("${label}が空です。")
        if (value.length > maximumLength) invalid("${label}が長すぎます。")
        if (value.any { it.code < 0x20 || it.code == 0x7f }) {
            invalid("${label}に制御文字があります。")
        }
        if (trimRequired && value != value.trim()) {
            invalid("${label}の前後に空白があります。")
        }
    }

    private fun validateNullableString(value: TransferJsonValue, maximumLength: Int, label: String): String? {
        return when (value) {
            TransferJsonNull -> null
            is TransferJsonString -> {
                if (value.value.length > maximumLength) invalid("${label}が長すぎます。")
                if (value.value.any { it.code < 0x20 || it.code == 0x7f }) {
                    invalid("${label}に制御文字があります。")
                }
                value.value
            }
            else -> invalid("${label}の型が不正です。")
        }
    }

    private fun parseEnum(value: String, allowed: Set<String>, label: String): String {
        if (value !in allowed) invalid("${label}の列挙値が不正です。")
        return value
    }

    private fun parseColumnKind(value: String, label: String): ColumnKind = when (value) {
        "home" -> ColumnKind.HOME_FOR_YOU
        "following" -> ColumnKind.HOME_FOLLOWING
        "notifications" -> ColumnKind.NOTIFICATIONS
        "messages" -> ColumnKind.MESSAGES
        "trends" -> ColumnKind.TRENDS
        "search" -> ColumnKind.SEARCH
        "history" -> ColumnKind.HISTORY
        "user" -> ColumnKind.USER
        "list" -> ColumnKind.LIST
        else -> invalid("${label}の列挙値が不正です。")
    }

    private fun toWebColumnKind(kind: ColumnKind): String = when (kind) {
        ColumnKind.HOME_FOR_YOU -> "home"
        ColumnKind.HOME_FOLLOWING -> "following"
        ColumnKind.NOTIFICATIONS -> "notifications"
        ColumnKind.MESSAGES -> "messages"
        ColumnKind.TRENDS -> "trends"
        ColumnKind.SEARCH -> "search"
        ColumnKind.HISTORY -> "history"
        ColumnKind.USER -> "user"
        ColumnKind.LIST -> "list"
    }

    private fun defaultColumnTitle(kind: ColumnKind, locale: String): String {
        if (locale == "ja") {
            return when (kind) {
                ColumnKind.HOME_FOR_YOU -> "おすすめ"
                ColumnKind.HOME_FOLLOWING -> "フォロー中"
                ColumnKind.NOTIFICATIONS -> "通知"
                ColumnKind.MESSAGES -> "メッセージ"
                ColumnKind.TRENDS -> "トレンド"
                ColumnKind.SEARCH -> "検索"
                ColumnKind.HISTORY -> "履歴"
                ColumnKind.USER -> "ユーザー"
                ColumnKind.LIST -> "リスト"
            }
        }
        return when (kind) {
            ColumnKind.HOME_FOR_YOU -> "For You"
            ColumnKind.HOME_FOLLOWING -> "Following"
            ColumnKind.NOTIFICATIONS -> "Notifications"
            ColumnKind.MESSAGES -> "Messages"
            ColumnKind.TRENDS -> "Trends"
            ColumnKind.SEARCH -> "Search"
            ColumnKind.HISTORY -> "History"
            ColumnKind.USER -> "User"
            ColumnKind.LIST -> "List"
        }
    }

    private fun requireExactKeys(value: TransferJsonObject, expected: Set<String>, label: String) {
        if (value.values.keys.toSet() != expected) invalid("${label}の項目が不正です。")
    }

    private fun requireBoolean(value: TransferJsonObject, key: String): Boolean {
        val boolean = value.requireValue(key) as? TransferJsonBoolean
            ?: invalid("表示設定の${key}が真偽値ではありません。")
        return boolean.value
    }

    private fun requireSize(size: Int) {
        if (size > MAX_JSON_SIZE_BYTES) invalid("設定JSONが1MiBを超えています。")
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        requireSize(bytes.size)
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (exception: CharacterCodingException) {
            throw LayoutTransferException("設定JSONがUTF-8ではありません。", exception)
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

    private data class ImportedValues(
        val columns: List<DeckColumn>,
        val selectedMenu: ColumnKind,
        val useDarkTheme: Boolean,
        val compactDensity: Boolean,
        val mainMenuItems: List<MainMenuItemId>,
        val replySort: RankingMode,
    )
}

public class LayoutTransferException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

public fun exportLayoutSettings(state: DeckUiState, exportedAt: Instant = Instant.now()): String =
    LayoutTransfer.exportSettings(state, exportedAt)

public fun exportLayoutSettings(state: DeckUiState, exportedAt: String): String =
    LayoutTransfer.exportSettings(state, exportedAt)

public fun importLayoutSettings(serialized: String, currentState: DeckUiState): DeckUiState =
    LayoutTransfer.importSettings(serialized, currentState).state

private const val TRANSFER_MAX_JSON_DEPTH = 64

private sealed interface TransferJsonValue

private data class TransferJsonObject(val values: LinkedHashMap<String, TransferJsonValue>) : TransferJsonValue

private data class TransferJsonArray(val values: List<TransferJsonValue>) : TransferJsonValue

private data class TransferJsonString(val value: String) : TransferJsonValue

private data class TransferJsonNumber(val raw: String) : TransferJsonValue

private data class TransferJsonBoolean(val value: Boolean) : TransferJsonValue

private object TransferJsonNull : TransferJsonValue

private fun invalid(message: String): Nothing = throw LayoutTransferException(message)

private fun invalidJson(): Nothing = invalid("設定ファイルは有効なJSONではありません。")

private fun invalidLayout(message: String): Nothing = invalid("設定ファイルのレイアウト$message")

private fun TransferJsonObject.requireValue(key: String): TransferJsonValue =
    values[key] ?: invalid("設定JSONに${key}がありません。")

private fun TransferJsonObject.requireString(key: String): String =
    (requireValue(key) as? TransferJsonString)?.value ?: invalid("設定JSONの${key}が文字列ではありません。")

private fun TransferJsonObject.requireObject(key: String): TransferJsonObject =
    (requireValue(key) as? TransferJsonObject) ?: invalid("設定JSONの${key}がオブジェクトではありません。")

private fun TransferJsonObject.requireArray(key: String): TransferJsonArray =
    (requireValue(key) as? TransferJsonArray) ?: invalid("設定JSONの${key}が配列ではありません。")

private fun TransferJsonObject.requireInteger(key: String): Int {
    val number = requireValue(key) as? TransferJsonNumber
        ?: invalid("設定JSONの${key}が整数ではありません。")
    val value = number.raw.toLongOrNull()
        ?: invalid("設定JSONの${key}が整数ではありません。")
    if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
        invalid("設定JSONの${key}が範囲外です。")
    }
    return value.toInt()
}

private class StrictJsonParser(private val source: String) {
    private var index = 0

    fun parse(): TransferJsonValue {
        skipWhitespace()
        val value = parseValue(0)
        skipWhitespace()
        if (index != source.length) invalidJson()
        return value
    }

    private fun parseValue(depth: Int): TransferJsonValue {
        if (depth > TRANSFER_MAX_JSON_DEPTH) invalidJson()
        skipWhitespace()
        return when (source.getOrNull(index)) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> TransferJsonString(parseString())
            't' -> {
                consumeLiteral("true")
                TransferJsonBoolean(true)
            }
            'f' -> {
                consumeLiteral("false")
                TransferJsonBoolean(false)
            }
            'n' -> {
                consumeLiteral("null")
                TransferJsonNull
            }
            '-', in '0'..'9' -> parseNumber()
            else -> invalidJson()
        }
    }

    private fun parseObject(depth: Int): TransferJsonObject {
        expect('{')
        skipWhitespace()
        val values = LinkedHashMap<String, TransferJsonValue>()
        if (consume('}')) return TransferJsonObject(values)

        while (true) {
            skipWhitespace()
            if (source.getOrNull(index) != '"') invalidJson()
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue(depth + 1)
            if (values.containsKey(key)) invalidJson()
            values[key] = value
            skipWhitespace()
            if (consume('}')) return TransferJsonObject(values)
            expect(',')
        }
    }

    private fun parseArray(depth: Int): TransferJsonArray {
        expect('[')
        skipWhitespace()
        val values = ArrayList<TransferJsonValue>()
        if (consume(']')) return TransferJsonArray(values)

        while (true) {
            values += parseValue(depth + 1)
            skipWhitespace()
            if (consume(']')) return TransferJsonArray(values)
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val value = StringBuilder()
        while (true) {
            val character = source.getOrNull(index++) ?: invalidJson()
            when (character) {
                '"' -> return value.toString()
                '\\' -> value.append(parseEscape())
                else -> {
                    if (character.code < 0x20) invalidJson()
                    value.append(character)
                }
            }
        }
    }

    private fun parseEscape(): Char = when (val escaped = source.getOrNull(index++)) {
        '"', '\\', '/' -> escaped
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
            var code = 0
            repeat(4) {
                val digit = source.getOrNull(index++)?.hexValue() ?: invalidJson()
                code = code * 16 + digit
            }
            code.toChar()
        }
        else -> invalidJson()
    }

    private fun parseNumber(): TransferJsonNumber {
        val start = index
        consume('-')
        when {
            consume('0') -> {
                if (source.getOrNull(index)?.isAsciiDigit() == true) invalidJson()
            }
            source.getOrNull(index)?.let { it in '1'..'9' } == true -> {
                index++
                while (source.getOrNull(index)?.isAsciiDigit() == true) index++
            }
            else -> invalidJson()
        }

        if (consume('.')) {
            if (source.getOrNull(index)?.isAsciiDigit() != true) invalidJson()
            while (source.getOrNull(index)?.isAsciiDigit() == true) index++
        }
        if (source.getOrNull(index) == 'e' || source.getOrNull(index) == 'E') {
            index++
            if (source.getOrNull(index) == '+' || source.getOrNull(index) == '-') index++
            if (source.getOrNull(index)?.isAsciiDigit() != true) invalidJson()
            while (source.getOrNull(index)?.isAsciiDigit() == true) index++
        }
        return TransferJsonNumber(source.substring(start, index))
    }

    private fun consumeLiteral(literal: String) {
        if (!source.startsWith(literal, index)) invalidJson()
        index += literal.length
    }

    private fun expect(character: Char) {
        if (!consume(character)) invalidJson()
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
        else -> invalidJson()
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}

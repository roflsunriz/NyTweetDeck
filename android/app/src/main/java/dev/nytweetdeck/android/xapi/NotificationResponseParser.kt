package dev.nytweetdeck.android.xapi

import dev.nytweetdeck.android.model.CommunityNoteDetail
import dev.nytweetdeck.android.model.CommunityNoteSource
import dev.nytweetdeck.android.model.Notification
import dev.nytweetdeck.android.model.NotificationActor
import dev.nytweetdeck.android.model.NotificationPage
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** X GraphQLの通知応答を通知、関連ポスト、ページング情報へ正規化する。 */
class NotificationResponseParser(
    private val json: Json = Json,
    private val timelineResponseParser: TimelineResponseParser = TimelineResponseParser(json),
) {
    fun parse(body: String): NotificationPage = try {
        val root = json.parseToJsonElement(body)
        val notifications = LinkedHashMap<String, Notification>()
        visit(root, notifications)
        val timeline = timelineResponseParser.parse(body)
        NotificationPage(
            notifications = notifications.values.toList(),
            posts = timeline.posts,
            nextCursor = timeline.nextCursor,
        )
    } catch (exception: Exception) {
        throw XApiException("通知応答を解析できません。", cause = exception)
    }

    /**
     * CommunityNote応答を検証し、通知詳細で表示する本文、出典、対象ポストIDを返す。
     */
    fun parseCommunityNote(
        body: String,
        expectedNoteId: String,
    ): CommunityNoteDetail = try {
        val root = json.parseToJsonElement(body) as? JsonObject
        val note = root
            ?.objectValue("data")
            ?.objectValue("birdwatch_note_by_rest_id")
            ?: throw IllegalArgumentException("コミュニティノート応答に対象ノートがありません。")
        val noteId = note.text("rest_id").orEmpty()
        if (expectedNoteId != noteId) {
            throw IllegalArgumentException("コミュニティノートIDが応答と一致しません。")
        }

        val summary = note
            .objectValue("data_v1")
            ?.objectValue("summary")
        val text = summary.text("text").orEmpty()
        if (text.isBlank()) {
            throw IllegalArgumentException("コミュニティノート本文がありません。")
        }

        val sources = buildList {
            val entities = summary?.get("entities") as? JsonArray
            entities?.forEach { entity ->
                val source = entity as? JsonObject ?: return@forEach
                val url = source
                    .objectValue("ref")
                    ?.text("url")
                    .orEmpty()
                if (!isSafeWebUrl(url)) {
                    return@forEach
                }
                val fromIndex = source.integer("fromIndex", "from_index")
                val toIndex = source.integer("toIndex", "to_index")
                if (fromIndex < 0 || toIndex <= fromIndex || toIndex > text.length) {
                    return@forEach
                }
                add(CommunityNoteSource(fromIndex, toIndex, url))
            }
        }

        val targetPostId = note
            .objectValue("tweet_results")
            ?.objectValue("result")
            ?.text("rest_id")
            .orEmpty()
        if (!POST_ID.matches(targetPostId)) {
            throw IllegalArgumentException("コミュニティノートの対象ポストIDがありません。")
        }
        CommunityNoteDetail(noteId, text, sources, targetPostId)
    } catch (exception: Exception) {
        throw XApiException("コミュニティノート応答を解析できません。", cause = exception)
    }

    private fun visit(
        node: JsonElement?,
        notifications: MutableMap<String, Notification>,
    ) {
        when (node) {
            is JsonArray -> node.forEach { visit(it, notifications) }
            is JsonObject -> {
                if (isNotification(node)) {
                    val notification = parseNotification(node)
                    notifications.putIfAbsent(notification.id, notification)
                    return
                }
                node.values.forEach { visit(it, notifications) }
            }

            else -> Unit
        }
    }

    private fun isNotification(node: JsonObject): Boolean =
        node.text("id") != null &&
            (
                node.firstObject("socialContext", "social_context") != null ||
                    node["notification_icon"] != null ||
                    node.objectValue("icon") != null ||
                    node.objectValue("message") != null ||
                    node.objectValue("rich_message") != null
                )

    private fun parseNotification(node: JsonObject): Notification {
        val socialContext = node.firstObject(
            "socialContext",
            "social_context",
            "notification_social_context",
        )
        val general = socialContext.firstObject("generalContext", "general_context")
        val topic = socialContext.firstObject("topicContext", "topic_context")
        val contextText = firstNonNull(general.text("text"), topic.text("text"))
        val messageText = node.objectValue("message").text("text")
        val richMessageText = node
            .firstObject("rich_message", "richMessage")
            .text("text")
        val displayText = firstNonNull(
            firstNonNull(contextText, messageText),
            firstNonNull(richMessageText, node.text("text")),
        ).orEmpty()

        val images = mutableListOf<String>()
        val imageNodes = general?.get("contextImageUrls") as? JsonArray
        imageNodes?.forEach { imageNode ->
            val imageUrl = (imageNode as? JsonPrimitive)?.contentOrNull
            if (imageUrl != null && isSafeImageUrl(imageUrl)) {
                images += imageUrl
            }
        }
        collectImageUrls(node, images)

        val actors = LinkedHashMap<String, NotificationActor>()
        collectActors(node, actors)
        linkedUserActor(node)?.let { actor ->
            actors.putIfAbsent(actorKey(actor), actor)
        }

        return Notification(
            id = node.text("id").orEmpty(),
            kind = notificationKind(node),
            text = displayText,
            noteId = findNoteId(node),
            postId = findPostId(node),
            actors = actors.values.toList(),
            imageUrls = images.toList(),
        )
    }

    private fun collectActors(
        node: JsonElement?,
        actors: MutableMap<String, NotificationActor>,
    ) {
        if (node == null || actors.size >= MAX_ACTORS) {
            return
        }
        when (node) {
            is JsonObject -> {
                parseActor(node)?.let { actor ->
                    actors.putIfAbsent(actorKey(actor), actor)
                    return
                }
                node.values.forEach {
                    collectActors(it, actors)
                    if (actors.size >= MAX_ACTORS) {
                        return
                    }
                }
            }

            is JsonArray -> node.forEach {
                collectActors(it, actors)
                if (actors.size >= MAX_ACTORS) {
                    return
                }
            }

            else -> Unit
        }
    }

    private fun parseActor(node: JsonObject): NotificationActor? {
        val legacy = node.objectValue("legacy")
        val core = node.objectValue("core")
        val avatar = node.objectValue("avatar")
        val typeName = node.text("__typename")
        val id = firstNonNull(node.text("rest_id"), legacy.text("id_str"))
        val username = firstNonNull(core.text("screen_name"), legacy.text("screen_name"))
        if (
            typeName != "User" &&
            (id == null || (core == null && legacy == null && avatar == null))
        ) {
            return null
        }
        if (id.isNullOrBlank() && username.isNullOrBlank()) {
            return null
        }
        val displayName = firstNonNull(core.text("name"), legacy.text("name"))
        val avatarUrl = firstNonNull(
            avatar.text("image_url"),
            legacy.text("profile_image_url_https"),
        )
        return NotificationActor(
            id = id.blankToNull(),
            username = username.blankToNull(),
            displayName = displayName.blankToNull(),
            avatarUrl = avatarUrl.takeIf(::isSafeImageUrl),
        )
    }

    private fun linkedUserActor(node: JsonObject): NotificationActor? {
        val urlNode = node.firstObject("url", "notification_url", "notificationUrl")
        val url = firstNonNull(
            firstNonNull(urlNode.text("expanded_url"), urlNode.text("expandedUrl")),
            urlNode.text("url"),
        ) ?: return null
        return try {
            val uri = URI.create(url)
            val username = firstNonNull(
                queryParameter(uri.rawQuery, "screen_name"),
                queryParameter(uri.rawQuery, "username"),
            )
            val id = firstNonNull(
                queryParameter(uri.rawQuery, "user_id"),
                queryParameter(uri.rawQuery, "id"),
            )
            if (
                (username == null || !USERNAME.matches(username)) &&
                (id == null || !POST_ID.matches(id))
            ) {
                null
            } else {
                NotificationActor(id, username, username, null)
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun queryParameter(
        query: String?,
        name: String,
    ): String? {
        if (query == null) {
            return null
        }
        query.split("&").forEach { parameter ->
            val parts = parameter.split("=", limit = 2)
            if (
                parts.size == 2 &&
                name == URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
            ) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
            }
        }
        return null
    }

    private fun actorKey(actor: NotificationActor): String =
        actor.id?.let { "id:$it" }
            ?: "username:" + requireNotNull(actor.username).lowercase(Locale.ROOT)

    private fun notificationKind(node: JsonObject): String {
        val icon = firstNonNull(
            node.text("notification_icon"),
            node.objectValue("icon").text("id"),
        )
        val normalized = icon?.lowercase(Locale.ROOT).orEmpty()
        return when {
            normalized.contains("heart") ||
                normalized.contains("favorite") ||
                normalized.contains("like") -> "like"

            normalized.contains("retweet") || normalized.contains("repost") -> "repost"
            normalized.contains("reply") || normalized.contains("mention") -> "reply"
            normalized.contains("follow") || normalized.contains("person") -> "follow"
            normalized.contains("birdwatch") || normalized.contains("community") -> "community_note"
            else -> "notification"
        }
    }

    private fun findPostId(node: JsonObject): String? {
        val urlNode = node.firstObject("url", "notification_url", "notificationUrl")
        val url = firstNonNull(
            firstNonNull(urlNode.text("expanded_url"), urlNode.text("expandedUrl")),
            urlNode.text("url"),
        )
        POST_ID_IN_URL.find(url.orEmpty())?.let { return it.groupValues[1] }
        return findPostReference(node) ?: findTweetId(node)
    }

    private fun findNoteId(node: JsonObject): String? {
        val urlNode = node.firstObject("notification_url", "notificationUrl", "url")
        val url = firstNonNull(
            firstNonNull(urlNode.text("expanded_url"), urlNode.text("expandedUrl")),
            urlNode.text("url"),
        ) ?: return null
        return NOTE_ID_IN_URL.find(url)?.groupValues?.getOrNull(1)
    }

    private fun findPostReference(node: JsonElement?): String? = when (node) {
        is JsonPrimitive -> POST_ID_IN_URL.find(node.contentOrNull.orEmpty())?.groupValues?.getOrNull(1)
        is JsonObject -> {
            node.entries.forEach { (field, value) ->
                if (field in POST_ID_FIELDS) {
                    val candidate = (value as? JsonPrimitive)?.contentOrNull
                    if (candidate != null && POST_ID.matches(candidate)) {
                        return candidate
                    }
                }
                findPostReference(value)?.let { return it }
            }
            null
        }

        is JsonArray -> {
            node.forEach { child ->
                findPostReference(child)?.let { return it }
            }
            null
        }

        else -> null
    }

    private fun findTweetId(node: JsonElement?): String? = when (node) {
        is JsonObject -> {
            if (node.text("__typename") == "Tweet" && node.text("rest_id") != null) {
                node.text("rest_id")
            } else {
                node.values.forEach { child ->
                    findTweetId(child)?.let { return it }
                }
                null
            }
        }

        is JsonArray -> {
            node.forEach { child ->
                findTweetId(child)?.let { return it }
            }
            null
        }

        else -> null
    }

    private fun collectImageUrls(
        node: JsonElement?,
        images: MutableList<String>,
    ) {
        if (node == null || images.size >= MAX_IMAGES) {
            return
        }
        when (node) {
            is JsonObject -> node.entries.forEach { (fieldName, value) ->
                val field = fieldName.lowercase(Locale.ROOT)
                if (
                    (field.contains("profile_image") ||
                        field.contains("contextimage") ||
                        field == "image_url") &&
                    value is JsonPrimitive
                ) {
                    val imageUrl = value.contentOrNull
                    if (imageUrl != null && isSafeImageUrl(imageUrl) && imageUrl !in images) {
                        images += imageUrl
                    }
                } else {
                    collectImageUrls(value, images)
                }
                if (images.size >= MAX_IMAGES) {
                    return
                }
            }

            is JsonArray -> node.forEach { child ->
                collectImageUrls(child, images)
                if (images.size >= MAX_IMAGES) {
                    return
                }
            }

            else -> Unit
        }
    }

    private fun isSafeImageUrl(value: String?): Boolean {
        if (value == null) {
            return false
        }
        return try {
            val uri = URI.create(value)
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            uri.scheme.equals("https", ignoreCase = true) &&
                (host == "twimg.com" || host.endsWith(".twimg.com"))
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun isSafeWebUrl(value: String): Boolean = try {
        val uri = URI.create(value)
        (uri.scheme.equals("https", ignoreCase = true) ||
            uri.scheme.equals("http", ignoreCase = true)) &&
            uri.host != null
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun JsonObject?.text(name: String): String? =
        (this?.get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.objectValue(name: String): JsonObject? =
        this?.get(name) as? JsonObject

    private fun JsonObject?.firstObject(vararg names: String): JsonObject? {
        names.forEach { name ->
            objectValue(name)?.let { return it }
        }
        return null
    }

    private fun JsonObject.integer(
        camelCase: String,
        snakeCase: String,
    ): Int = ((this[camelCase] ?: this[snakeCase]) as? JsonPrimitive)?.intOrNull ?: -1

    private fun String?.blankToNull(): String? = this?.takeIf(String::isNotBlank)

    private fun firstNonNull(
        first: String?,
        second: String?,
    ): String? = first ?: second

    private companion object {
        val POST_ID_IN_URL = Regex(
            """(?:status(?:es)?/|tweet(?:_id|id)[=:]|(?:tweet|post)\?(?:[^\s#]*&)?(?:id|tweet_id)=)([0-9]{1,24})""",
            RegexOption.IGNORE_CASE,
        )
        val NOTE_ID_IN_URL = Regex(
            """/i/birdwatch/n/([0-9]{1,24})""",
            RegexOption.IGNORE_CASE,
        )
        val POST_ID = Regex("""[0-9]{1,24}""")
        val USERNAME = Regex("""[A-Za-z0-9_]{1,15}""")
        val POST_ID_FIELDS = setOf("tweet_id", "tweetId", "tweet_id_str", "postId")
        const val MAX_ACTORS = 20
        const val MAX_IMAGES = 4
    }
}

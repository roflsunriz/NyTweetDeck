package dev.nytweetdeck.android.xapi

import dev.nytweetdeck.android.model.Article
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.CommunityNote
import dev.nytweetdeck.android.model.EmbeddedPost
import dev.nytweetdeck.android.model.Media
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.TimelinePage
import dev.nytweetdeck.android.model.Translation
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** X GraphQLの多様なタイムライン形式を画面向けの共通モデルへ変換する。 */
class TimelineResponseParser(
    private val json: Json = Json,
) {
    fun parse(body: String): TimelinePage = parse(body, sortChronologically = true)

    fun parseInResponseOrder(body: String): TimelinePage = parse(body, sortChronologically = false)

    private fun parse(body: String, sortChronologically: Boolean): TimelinePage = try {
        val root = json.parseToJsonElement(body)
        val posts = LinkedHashMap<String, Post>()
        val cursor = CursorHolder()
        visit(root, posts, cursor, inheritedConversationSection = null)

        val normalizedPosts = posts.values.toMutableList()
        if (sortChronologically) {
            normalizedPosts.sortWith(::compareNewestFirst)
        }
        TimelinePage(normalizedPosts.toList(), cursor.value)
    } catch (exception: XApiException) {
        throw exception
    } catch (exception: Exception) {
        throw XApiException("タイムライン応答を解析できません。", cause = exception)
    }

    private fun visit(
        node: JsonElement?,
        posts: MutableMap<String, Post>,
        cursor: CursorHolder,
        inheritedConversationSection: String?,
    ) {
        when (node) {
            is JsonArray -> node.forEach {
                visit(it, posts, cursor, inheritedConversationSection)
            }

            is JsonObject -> {
                if (isPromoted(node)) {
                    return
                }

                val conversationSection = firstNonBlank(
                    conversationSection(node),
                    inheritedConversationSection,
                )
                findCursor(node, cursor)
                val tweet = unwrapTweet(node)
                if (isTweet(tweet)) {
                    val post = parsePost(requireNotNull(tweet), node, conversationSection)
                    posts.putIfAbsent(post.id, post)
                    return
                }
                node.values.forEach {
                    visit(it, posts, cursor, conversationSection)
                }
            }

            else -> Unit
        }
    }

    private fun unwrapTweet(node: JsonElement?): JsonObject? {
        val objectNode = node as? JsonObject ?: return null
        if (objectNode.text("__typename") == "TweetWithVisibilityResults" && objectNode["tweet"] != null) {
            return objectNode["tweet"] as? JsonObject
        }
        val result = objectNode.objectValue("result")
        if (result != null && result.text("__typename") in TWEET_RESULT_TYPES) {
            return unwrapTweet(result)
        }
        return objectNode
    }

    private fun isTweet(node: JsonObject?): Boolean {
        val legacy = node?.objectValue("legacy") ?: return false
        return tweetText(node) != null && (node.text("rest_id") != null || legacy.text("id_str") != null)
    }

    private fun parsePost(
        node: JsonObject,
        responseNode: JsonObject,
        conversationSection: String?,
    ): Post {
        val retweetedTweet = findReferencedTweet(node, "retweeted_status_result", "retweetRefResult")
        val content = retweetedTweet ?: node
        val legacy = requireNotNull(content.objectValue("legacy"))
        val id = firstNonNull(content.text("rest_id"), legacy.text("id_str")).orEmpty()
        val author = parseAuthor(content)
        val outerAuthor = if (retweetedTweet == null) null else parseAuthor(node)
        val repostedBy = outerAuthor?.takeIf(::hasAuthorIdentity)
        val quotedPost = parseEmbeddedPost(
            findReferencedTweet(content, "quoted_status_result", "quotedRefResult"),
        )
        val article = parseArticle(content)
        val media = parseMedia(legacy.objectValue("extended_entities"))
        val urlEntities = urlEntities(content, legacy)
        val preTranslated = normalizeUrls(
            parsePreTranslated(content, responseNode),
            urlEntities,
        )

        return Post(
            id = id,
            text = PostTextUrlNormalizer.normalize(tweetText(content).orEmpty(), urlEntities),
            language = legacy.text("lang"),
            createdAt = parseCreatedAt(legacy.text("created_at")),
            author = author,
            repostedBy = repostedBy,
            conversationSection = conversationSection,
            replyCount = legacy.number("reply_count"),
            repostCount = legacy.number("retweet_count"),
            quoteCount = legacy.number("quote_count"),
            likeCount = legacy.number("favorite_count"),
            bookmarkCount = legacy.number("bookmark_count"),
            viewCount = parseViewCount(content.objectValue("views")),
            liked = legacy.bool("favorited"),
            reposted = legacy.bool("retweeted"),
            bookmarked = legacy.bool("bookmarked"),
            replyToPostId = legacy.text("in_reply_to_status_id_str"),
            replyToUsername = legacy.text("in_reply_to_screen_name"),
            quotedPostId = firstNonNull(legacy.text("quoted_status_id_str"), quotedPost?.id),
            quotedPost = quotedPost,
            communityNote = parseCommunityNote(content, responseNode),
            preTranslated = preTranslated,
            article = article,
            media = media.toList(),
        )
    }

    private fun conversationSection(node: JsonObject): String? = node
        .objectValue("clientEventInfo")
        ?.objectValue("details")
        ?.objectValue("conversationDetails")
        ?.text("conversationSection")
        ?.takeIf { it in CONVERSATION_SECTIONS }

    private fun isPromoted(node: JsonObject): Boolean {
        val entryId = firstNonNull(node.text("entryId"), node.text("entry_id"))
        return entryId?.lowercase(Locale.ROOT)?.startsWith("promoted") == true ||
            node["promotedMetadata"] != null ||
            node["promoted_metadata"] != null ||
            node["promotedContent"] != null ||
            node["promoted_content"] != null
    }

    private fun hasAuthorIdentity(author: Author): Boolean =
        author.id.isNotBlank() || author.username.isNotBlank() || author.displayName.isNotBlank()

    private fun findReferencedTweet(tweet: JsonObject?, vararg fields: String): JsonObject? {
        fields.forEach { field ->
            referencedTweet(tweet?.get(field))?.let { return it }
            referencedTweet(tweet?.objectValue("legacy")?.get(field))?.let { return it }
        }
        return null
    }

    private fun referencedTweet(wrapper: JsonElement?): JsonObject? {
        unwrapTweet(wrapper)?.takeIf(::isTweet)?.let { return it }
        val result = (wrapper as? JsonObject)?.objectValue("result")
        return unwrapTweet(result?.get("tweet"))?.takeIf(::isTweet)
    }

    private fun parseEmbeddedPost(node: JsonObject?): EmbeddedPost? {
        if (!isTweet(node)) {
            return null
        }
        val tweet = requireNotNull(node)
        val legacy = requireNotNull(tweet.objectValue("legacy"))
        val article = parseArticle(tweet)
        val media = parseMedia(legacy.objectValue("extended_entities"))
        val urlEntities = urlEntities(tweet, legacy)
        return EmbeddedPost(
            id = firstNonNull(tweet.text("rest_id"), legacy.text("id_str")).orEmpty(),
            text = PostTextUrlNormalizer.normalize(tweetText(tweet).orEmpty(), urlEntities),
            language = legacy.text("lang"),
            createdAt = parseCreatedAt(legacy.text("created_at")),
            author = parseAuthor(tweet),
            preTranslated = normalizeUrls(parsePreTranslated(tweet, tweet), urlEntities),
            article = article,
            media = media.toList(),
        )
    }

    private fun tweetText(tweet: JsonObject?): String? {
        val noteTweet = tweet?.firstObject("note_tweet", "noteTweet")
        val noteTweetResults = noteTweet?.firstObject("note_tweet_results", "noteTweetResults")
        val noteTweetResult = noteTweetResults?.firstObject("result")
        return firstNonBlank(noteTweetResult.text("text"), tweet?.objectValue("legacy").text("full_text"))
    }

    private fun parseArticle(tweet: JsonObject): Article? {
        val article = tweet.objectValue("article")
        val result = article?.objectValue("article_results")?.objectValue("result") ?: return null
        val id = firstNonBlank(result.text("rest_id"), result.text("id")) ?: return null
        val title = firstNonBlank(result.text("title")) ?: return null
        val body = firstNonBlank(
            result.text("plain_text"),
            articleText(result["content_state"]),
        )
        val preview = firstNonBlank(result.text("preview_text"), summarizeArticle(body))
        val coverMedia = result.objectValue("cover_media")
        val coverImage = firstNonBlank(
            coverMedia?.objectValue("media_info")?.text("original_img_url"),
            coverMedia?.objectValue("media_info")?.text("originalImgUrl"),
            coverMedia.text("media_url_https"),
        )
        return Article(
            id = id,
            title = title,
            previewText = preview,
            body = body,
            coverImageUrl = coverImage,
            url = "https://x.com/i/article/$id",
        )
    }

    private fun articleText(contentState: JsonElement?): String? {
        val blocks = (contentState as? JsonObject)?.get("blocks") as? JsonArray ?: return null
        val paragraphs = blocks.mapNotNull { block ->
            (block as? JsonObject)?.text("text")?.trim()?.takeIf(String::isNotBlank)
        }
        return paragraphs.takeIf(List<String>::isNotEmpty)?.joinToString("\n\n")
    }

    private fun summarizeArticle(body: String?): String? {
        val normalized = body?.replace(WHITESPACE, " ")?.trim() ?: return null
        return if (normalized.length <= ARTICLE_SUMMARY_LENGTH) {
            normalized
        } else {
            normalized.take(ARTICLE_SUMMARY_LENGTH - 3) + "…"
        }
    }

    private fun urlEntities(
        tweet: JsonObject,
        legacy: JsonObject,
    ): List<PostUrlEntity> = buildList {
        collectUrlEntities(
            legacy.objectValue("extended_entities")?.get("media"),
            this,
            PostUrlEntityKind.MEDIA,
        )
        collectUrlEntities(
            legacy.objectValue("entities")?.get("media"),
            this,
            PostUrlEntityKind.MEDIA,
        )
        collectUrlEntities(
            legacy.objectValue("entities")?.get("urls"),
            this,
            PostUrlEntityKind.LINK,
        )
        val noteResult = tweet.firstObject("note_tweet", "noteTweet")
            ?.firstObject("note_tweet_results", "noteTweetResults")
            ?.firstObject("result")
        val entitySet = noteResult?.firstObject("entity_set", "entitySet")
        collectUrlEntities(entitySet?.get("urls"), this, PostUrlEntityKind.LINK)
    }

    private fun collectUrlEntities(
        values: JsonElement?,
        entities: MutableList<PostUrlEntity>,
        defaultKind: PostUrlEntityKind,
    ) {
        (values as? JsonArray)?.forEach { entity ->
            val item = entity as? JsonObject ?: return@forEach
            val expanded = firstNonNull(item.text("expanded_url"), item.text("expandedUrl"))
            val kind = if (defaultKind == PostUrlEntityKind.LINK && expanded?.let(ARTICLE_URL::matches) == true) {
                PostUrlEntityKind.ARTICLE
            } else {
                defaultKind
            }
            item.text("url")?.takeIf(String::isNotBlank)?.let { shortUrl ->
                entities += PostUrlEntity(
                    shortUrl = shortUrl,
                    expandedUrl = expanded,
                    unwoundUrl = firstNonNull(item.text("unwound_url"), item.text("unwoundUrl")),
                    kind = kind,
                )
            }
        }
    }

    private fun normalizeUrls(
        translation: Translation?,
        urlEntities: List<PostUrlEntity>,
    ): Translation? = translation?.copy(text = PostTextUrlNormalizer.normalize(translation.text, urlEntities))

    private fun parseCommunityNote(
        tweet: JsonObject,
        responseNode: JsonObject,
    ): CommunityNote? {
        var pivot = tweet.firstObject("birdwatch_pivot", "birdwatchPivot")
        if (pivot == null && responseNode !== tweet) {
            pivot = responseNode.firstObject("birdwatch_pivot", "birdwatchPivot")
        }
        if (pivot == null) {
            return null
        }
        val note = pivot.objectValue("note")
        val summary = note?.firstObject("data_v1", "dataV1")?.objectValue("summary")
        val title = firstNonBlank(richText(pivot["title"]), richText(pivot["heading"]))
        val noteText = firstNonBlank(
            richText(summary),
            richText(note?.get("summary")),
            richText(pivot["subtitle"]),
            richText(pivot["text"]),
        )
        val footer = richText(pivot["footer"])
        if (title == null && noteText == null && footer == null) {
            return null
        }
        return CommunityNote(title, noteText, footer)
    }

    private fun richText(node: JsonElement?): String? {
        val value = (node as? JsonPrimitive)?.contentOrNull ?: (node as? JsonObject).text("text")
        return value?.takeIf(String::isNotBlank)
    }

    private fun parsePreTranslated(
        tweet: JsonObject,
        responseNode: JsonObject,
    ): Translation? {
        var availability = translationAvailability(tweet)
        if (availability == null && responseNode !== tweet) {
            availability = translationAvailability(responseNode)
                ?: translationAvailability(responseNode.objectValue("result"))
        }
        if (availability?.bool("is_available") != true) {
            return null
        }
        val data = availability.objectValue("data")
        val translation = data.text("translation")
        val targetLanguage = data.text("destination_language")
        if (translation.isNullOrBlank() || targetLanguage == null) {
            return null
        }
        return Translation(
            text = translation,
            sourceLanguage = data.text("source_language"),
            targetLanguage = targetLanguage,
            provider = "Grok",
        )
    }

    private fun translationAvailability(node: JsonElement?): JsonObject? =
        (node as? JsonObject)?.objectValue("grok_translated_post_with_availability")

    private fun parseAuthor(tweet: JsonObject): Author {
        val user = findAuthorUser(tweet)
            ?: return Author("", "", "", null, verified = false)
        val legacy = user.objectValue("legacy")
        val core = user.objectValue("core")
        val avatar = user.objectValue("avatar")
        val verification = user.objectValue("verification")
        return Author(
            id = firstNonNull(user.text("rest_id"), legacy.text("id_str")).orEmpty(),
            username = firstNonNull(core.text("screen_name"), legacy.text("screen_name")).orEmpty(),
            displayName = firstNonNull(core.text("name"), legacy.text("name")).orEmpty(),
            avatarUrl = firstNonNull(avatar.text("image_url"), legacy.text("profile_image_url_https")),
            verified = verification.bool("verified") || legacy.bool("verified") || user.bool("is_blue_verified"),
        )
    }

    private fun findAuthorUser(tweet: JsonObject): JsonObject? {
        val core = tweet.objectValue("core")
        return unwrapUser(core?.get("user_results"))
            ?: unwrapUser(core?.get("user_result"))
            ?: findFirstUser(core, depth = 0)
            ?: unwrapUser(tweet["author_results"])
            ?: unwrapUser(tweet["user_results"])
            ?: unwrapUser(tweet["author"])
            ?: unwrapUser(tweet.objectValue("legacy")?.get("user"))
    }

    private fun findFirstUser(node: JsonElement?, depth: Int): JsonObject? {
        if (node == null || depth >= MAX_USER_LOOKUP_DEPTH) {
            return null
        }
        return when (node) {
            is JsonObject -> {
                if (isUser(node)) {
                    node
                } else {
                    node.values.firstNotNullOfOrNull { findFirstUser(it, depth + 1) }
                }
            }

            is JsonArray -> node.firstNotNullOfOrNull { findFirstUser(it, depth + 1) }
            else -> null
        }
    }

    private fun unwrapUser(node: JsonElement?): JsonObject? {
        var current = node as? JsonObject
        repeat(MAX_USER_LOOKUP_DEPTH) {
            val user = current ?: return null
            if (user.text("__typename") == "UserUnavailable") {
                return null
            }
            if (isUser(user)) {
                return user
            }
            val next = user.firstObject("result", "user", "author") ?: return null
            if (next === user) {
                return null
            }
            current = next
        }
        return null
    }

    private fun isUser(node: JsonObject): Boolean =
        node.text("__typename") == "User" ||
            (node.text("rest_id") != null &&
                (node["core"] != null || node["legacy"] != null || node["avatar"] != null))

    private fun parseMedia(extendedEntities: JsonObject?): List<Media> {
        val mediaNodes = extendedEntities?.get("media") as? JsonArray ?: return emptyList()
        return buildList {
            mediaNodes.forEach { node ->
                val item = node as? JsonObject
                val type = item.text("type")
                val preview = item.text("media_url_https")
                val url = if (type == "photo") preview else bestVideoUrl(item?.objectValue("video_info"))
                add(
                    Media(
                        id = item.text("id_str") ?: size.toString(),
                        type = type ?: "unknown",
                        url = url ?: preview,
                        previewUrl = preview,
                    ),
                )
            }
        }
    }

    private fun bestVideoUrl(videoInfo: JsonObject?): String? {
        val variants = videoInfo?.get("variants") as? JsonArray ?: return null
        var best: JsonObject? = null
        var bestBitrate = -1L
        variants.forEach { node ->
            val variant = node as? JsonObject ?: return@forEach
            if (variant.text("content_type") != "video/mp4") {
                return@forEach
            }
            val bitrate = variant.number("bitrate")
            if (best == null || bitrate > bestBitrate) {
                best = variant
                bestBitrate = bitrate
            }
        }
        return best?.text("url")
    }

    private fun findCursor(node: JsonObject, cursor: CursorHolder) {
        val cursorType = firstNonNull(node.text("cursorType"), node.text("cursor_type"))
        if (cursorType.equals("Bottom", ignoreCase = true)) {
            cursor.value = node.text("value")
            return
        }
        val entryId = firstNonNull(node.text("entryId"), node.text("entry_id"))
        if (entryId?.lowercase(Locale.ROOT)?.contains("cursor-bottom") == true) {
            node.objectValue("content")?.text("value")?.let { cursor.value = it }
        }
    }

    private fun compareNewestFirst(left: Post, right: Post): Int {
        val leftTime = sortableTime(left)
        val rightTime = sortableTime(right)
        return when {
            leftTime != null && rightTime != null -> {
                val timeComparison = rightTime.compareTo(leftTime)
                if (timeComparison != 0) timeComparison else right.id.compareTo(left.id)
            }

            leftTime != null -> -1
            rightTime != null -> 1
            else -> right.id.compareTo(left.id)
        }
    }

    private fun parseCreatedAt(value: String?): String? {
        if (value == null) {
            return null
        }
        return try {
            OffsetDateTime.parse(value, X_DATE_FORMAT).toInstant().toString()
        } catch (_: RuntimeException) {
            try {
                Instant.parse(value).toString()
            } catch (_: RuntimeException) {
                value
            }
        }
    }

    private fun sortableTime(post: Post): Instant? = try {
        post.createdAt?.let(Instant::parse)
    } catch (_: RuntimeException) {
        null
    }

    private fun parseViewCount(views: JsonObject?): Long = views.text("count")?.toLongOrNull() ?: 0L

    private fun JsonObject?.text(name: String): String? =
        (this?.get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.number(name: String): Long =
        (this?.get(name) as? JsonPrimitive)?.longOrNull ?: 0L

    private fun JsonObject?.bool(name: String): Boolean =
        (this?.get(name) as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.objectValue(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject?.firstObject(vararg names: String): JsonObject? {
        names.forEach { name ->
            (this?.get(name) as? JsonObject)?.let { return it }
        }
        return null
    }

    private fun firstNonNull(first: String?, second: String?): String? = first ?: second

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private class CursorHolder {
        var value: String? = null
    }

    private companion object {
        val X_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH)
        val CONVERSATION_SECTIONS = setOf("HighQuality", "LowQuality", "AbusiveQuality")
        val TWEET_RESULT_TYPES = setOf("Tweet", "TweetWithVisibilityResults")
        val ARTICLE_URL = Regex("https?://(?:x|twitter)\\.com/i/article/.*")
        val WHITESPACE = Regex("\\s+")
        const val ARTICLE_SUMMARY_LENGTH = 280
        const val MAX_USER_LOOKUP_DEPTH = 8
    }
}

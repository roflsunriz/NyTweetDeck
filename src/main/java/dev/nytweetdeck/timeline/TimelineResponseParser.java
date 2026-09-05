package dev.nytweetdeck.timeline;

import dev.nytweetdeck.timeline.TimelinePage.Author;
import dev.nytweetdeck.timeline.TimelinePage.Article;
import dev.nytweetdeck.timeline.TimelinePage.CommunityNote;
import dev.nytweetdeck.timeline.TimelinePage.EmbeddedPost;
import dev.nytweetdeck.timeline.TimelinePage.Media;
import dev.nytweetdeck.timeline.TimelinePage.Post;
import dev.nytweetdeck.timeline.TimelinePage.Translation;
import dev.nytweetdeck.timeline.TimelinePage.VideoVariant;
import dev.nytweetdeck.timeline.PostTextUrlNormalizer.Kind;
import dev.nytweetdeck.timeline.PostTextUrlNormalizer.UrlEntity;
import dev.nytweetdeck.text.HtmlEntityDecoder;
import dev.nytweetdeck.xapi.http.XApiHttpException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class TimelineResponseParser {

    private static final DateTimeFormatter X_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
    private static final Set<String> CONVERSATION_SECTIONS =
            Set.of("HighQuality", "LowQuality", "AbusiveQuality");

    private final ObjectMapper objectMapper;

    public TimelineResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TimelinePage parse(String body) {
        return parse(body, true);
    }

    public TimelinePage parse(String body, String sort) {
        return parse(body, !"top".equalsIgnoreCase(sort == null ? "" : sort.trim()));
    }

    public TimelinePage parseInResponseOrder(String body) {
        return parse(body, false);
    }

    private TimelinePage parse(String body, boolean sortChronologically) {
        try {
            var root = objectMapper.readTree(body);
            var posts = new LinkedHashMap<String, Post>();
            var cursor = new CursorState();
            visit(root, posts, cursor, null);
            var sortedPosts = new ArrayList<>(posts.values());
            if (sortChronologically) {
                sortedPosts.sort(Comparator.comparing(
                                TimelineResponseParser::sortableTime,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Post::id, Comparator.reverseOrder()));
            }
            return new TimelinePage(sortedPosts, cursor.bottomTerminated ? null : cursor.value);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new XApiHttpException("タイムライン応答を解析できません。", exception);
        }
    }

    private void visit(
            JsonNode node,
            Map<String, Post> posts,
            CursorState cursor,
            String inheritedConversationSection) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                visit(child, posts, cursor, inheritedConversationSection);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }

        if (isPromoted(node)) {
            return;
        }

        var conversationSection = firstNonBlank(
                conversationSection(node), inheritedConversationSection);
        findCursor(node, cursor);
        var tweetNode = unwrapTweet(node);
        if (isTweet(tweetNode)) {
            var post = parsePost(tweetNode, node, conversationSection);
            posts.putIfAbsent(post.id(), post);
            return;
        }
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            visit(property.getValue(), posts, cursor, conversationSection);
        }
    }

    private static JsonNode unwrapTweet(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        var typename = text(node, "__typename");
        if ("TweetWithVisibilityResults".equals(typename) && node.get("tweet") != null) {
            return node.get("tweet");
        }
        var result = node.get("result");
        if (result != null && result.isObject()) {
            var resultType = text(result, "__typename");
            if ("Tweet".equals(resultType) || "TweetWithVisibilityResults".equals(resultType)) {
                return unwrapTweet(result);
            }
        }
        return node;
    }

    private static boolean isTweet(JsonNode node) {
        if (node == null) {
            return false;
        }
        var legacy = node.get("legacy");
        return legacy != null
                && legacy.isObject()
                && tweetText(node) != null
                && (text(node, "rest_id") != null || text(legacy, "id_str") != null);
    }

    private static Post parsePost(
            JsonNode node, JsonNode responseNode, String conversationSection) {
        var retweetedTweet = findReferencedTweet(
                node, "retweeted_status_result", "retweetRefResult");
        var content = retweetedTweet == null ? node : retweetedTweet;
        var legacy = content.get("legacy");
        var id = firstNonNull(text(content, "rest_id"), text(legacy, "id_str"));
        var author = parseAuthor(content);
        var outerAuthor = retweetedTweet == null ? null : parseAuthor(node);
        var repostedBy = hasAuthorIdentity(outerAuthor) ? outerAuthor : null;
        var quotedTweet = findReferencedTweet(
                content, "quoted_status_result", "quotedRefResult");
        var quotedPost = parseEmbeddedPost(quotedTweet);
        var article = parseArticle(content);
        var media = parseMedia(legacy.get("extended_entities"));
        var urlEntities = urlEntities(content, legacy);
        var preTranslated = normalizeUrls(
                parsePreTranslated(content, responseNode), urlEntities);
        return new Post(
                id,
                PostTextUrlNormalizer.normalize(tweetText(content), urlEntities),
                PostTextUrlNormalizer.links(urlEntities),
                text(legacy, "lang"),
                parseCreatedAt(text(legacy, "created_at")),
                author,
                repostedBy,
                conversationSection,
                number(legacy, "reply_count"),
                number(legacy, "retweet_count"),
                number(legacy, "quote_count"),
                number(legacy, "favorite_count"),
                number(legacy, "bookmark_count"),
                parseViewCount(content.get("views")),
                bool(legacy, "favorited"),
                bool(legacy, "retweeted"),
                bool(legacy, "bookmarked"),
                text(legacy, "in_reply_to_status_id_str"),
                text(legacy, "in_reply_to_screen_name"),
                firstNonNull(text(legacy, "quoted_status_id_str"),
                        quotedPost == null ? null : quotedPost.id()),
                quotedPost,
                parseCommunityNote(content, responseNode),
                preTranslated,
                article,
                media);
    }

    private static String conversationSection(JsonNode node) {
        var clientEventInfo = object(node, "clientEventInfo");
        var details = object(clientEventInfo, "details");
        var conversationDetails = object(details, "conversationDetails");
        var section = text(conversationDetails, "conversationSection");
        return section != null && CONVERSATION_SECTIONS.contains(section) ? section : null;
    }

    private static boolean isPromoted(JsonNode node) {
        var entryId = firstNonNull(text(node, "entryId"), text(node, "entry_id"));
        return (entryId != null && entryId.toLowerCase(Locale.ROOT).startsWith("promoted"))
                || node.get("promotedMetadata") != null
                || node.get("promoted_metadata") != null
                || node.get("promotedContent") != null
                || node.get("promoted_content") != null;
    }

    private static boolean hasAuthorIdentity(Author author) {
        return author != null
                && (!author.id().isBlank()
                        || !author.username().isBlank()
                        || !author.displayName().isBlank());
    }

    private static JsonNode findReferencedTweet(JsonNode tweet, String... fields) {
        for (var field : fields) {
            var referenced = referencedTweet(tweet == null ? null : tweet.get(field));
            if (referenced != null) {
                return referenced;
            }
            var legacy = tweet == null ? null : tweet.get("legacy");
            referenced = referencedTweet(legacy == null ? null : legacy.get(field));
            if (referenced != null) {
                return referenced;
            }
        }
        return null;
    }

    private static JsonNode referencedTweet(JsonNode wrapper) {
        var candidate = unwrapTweet(wrapper);
        if (isTweet(candidate)) {
            return candidate;
        }
        var result = wrapper == null ? null : wrapper.get("result");
        candidate = unwrapTweet(result == null ? null : result.get("tweet"));
        return isTweet(candidate) ? candidate : null;
    }

    private static EmbeddedPost parseEmbeddedPost(JsonNode node) {
        if (!isTweet(node)) {
            return null;
        }
        var legacy = node.get("legacy");
        var article = parseArticle(node);
        var media = parseMedia(legacy.get("extended_entities"));
        var urlEntities = urlEntities(node, legacy);
        return new EmbeddedPost(
                firstNonNull(text(node, "rest_id"), text(legacy, "id_str")),
                PostTextUrlNormalizer.normalize(tweetText(node), urlEntities),
                PostTextUrlNormalizer.links(urlEntities),
                text(legacy, "lang"),
                parseCreatedAt(text(legacy, "created_at")),
                parseAuthor(node),
                normalizeUrls(parsePreTranslated(node, node), urlEntities),
                article,
                media);
    }

    private static String tweetText(JsonNode tweet) {
        var noteTweet = firstObject(tweet, "note_tweet", "noteTweet");
        var noteTweetResults = firstObject(noteTweet, "note_tweet_results", "noteTweetResults");
        var noteTweetResult = firstObject(noteTweetResults, "result");
        var legacy = tweet == null ? null : tweet.get("legacy");
        return firstNonBlank(text(noteTweetResult, "text"), text(legacy, "full_text"));
    }

    private static Article parseArticle(JsonNode tweet) {
        var article = object(tweet, "article");
        var results = object(article, "article_results");
        var result = object(results, "result");
        if (result == null) {
            return null;
        }
        var id = firstNonBlank(text(result, "rest_id"), text(result, "id"));
        var title = HtmlEntityDecoder.decode(firstNonBlank(text(result, "title")));
        if (id == null || title == null) {
            return null;
        }
        var body = HtmlEntityDecoder.decode(
                firstNonBlank(text(result, "plain_text"), articleText(result.get("content_state"))));
        var preview = HtmlEntityDecoder.decode(
                firstNonBlank(text(result, "preview_text"), summarizeArticle(body)));
        var coverMedia = object(result, "cover_media");
        var mediaInfo = object(coverMedia, "media_info");
        var coverImage = firstNonBlank(
                text(mediaInfo, "original_img_url"),
                text(mediaInfo, "originalImgUrl"),
                text(coverMedia, "media_url_https"));
        return new Article(
                id,
                title,
                preview,
                body,
                coverImage,
                "https://x.com/i/article/" + id);
    }

    private static String articleText(JsonNode contentState) {
        var blocks = contentState == null ? null : contentState.get("blocks");
        if (blocks == null || !blocks.isArray()) {
            return null;
        }
        var paragraphs = new ArrayList<String>();
        for (var block : blocks) {
            var paragraph = text(block, "text");
            if (paragraph != null && !paragraph.isBlank()) {
                paragraphs.add(paragraph.strip());
            }
        }
        return paragraphs.isEmpty() ? null : String.join("\n\n", paragraphs);
    }

    private static String summarizeArticle(String body) {
        if (body == null) {
            return null;
        }
        var normalized = body.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 280 ? normalized : normalized.substring(0, 277) + "…";
    }

    private static List<UrlEntity> urlEntities(JsonNode tweet, JsonNode legacy) {
        var entities = new ArrayList<UrlEntity>();
        collectUrlEntities(
                legacy == null ? null : legacy.path("extended_entities").get("media"),
                entities,
                Kind.MEDIA);
        collectUrlEntities(
                legacy == null ? null : legacy.path("entities").get("media"),
                entities,
                Kind.MEDIA);
        collectUrlEntities(
                legacy == null ? null : legacy.path("entities").get("urls"),
                entities,
                Kind.LINK);
        var noteTweet = firstObject(tweet, "note_tweet", "noteTweet");
        var noteResults = firstObject(noteTweet, "note_tweet_results", "noteTweetResults");
        var noteResult = firstObject(noteResults, "result");
        var entitySet = firstObject(noteResult, "entity_set", "entitySet");
        collectUrlEntities(entitySet == null ? null : entitySet.get("urls"), entities, Kind.LINK);
        return entities;
    }

    private static void collectUrlEntities(
            JsonNode values, List<UrlEntity> entities, Kind defaultKind) {
        if (values == null || !values.isArray()) {
            return;
        }
        for (var entity : values) {
            var expanded = firstNonNull(text(entity, "expanded_url"), text(entity, "expandedUrl"));
            var kind = defaultKind == Kind.LINK && isArticleUrl(expanded)
                    ? Kind.ARTICLE
                    : defaultKind;
            var shortUrl = text(entity, "url");
            if (shortUrl != null && !shortUrl.isBlank()) {
                entities.add(new UrlEntity(
                        shortUrl,
                        firstNonNull(text(entity, "display_url"), text(entity, "displayUrl")),
                        expanded,
                        firstNonNull(text(entity, "unwound_url"), text(entity, "unwoundUrl")),
                        kind));
            }
        }
    }

    private static boolean isArticleUrl(String value) {
        return value != null && value.matches("https?://(?:x|twitter)\\.com/i/article/.*");
    }

    private static Translation normalizeUrls(
            Translation translation, List<UrlEntity> urlEntities) {
        if (translation == null) {
            return null;
        }
        return new Translation(
                PostTextUrlNormalizer.normalize(translation.text(), urlEntities),
                translation.sourceLanguage(),
                translation.targetLanguage(),
                translation.provider());
    }

    private static CommunityNote parseCommunityNote(JsonNode tweet, JsonNode responseNode) {
        var pivot = object(tweet, "birdwatch_pivot");
        if (pivot == null) {
            pivot = object(tweet, "birdwatchPivot");
        }
        if (pivot == null && responseNode != tweet) {
            pivot = object(responseNode, "birdwatch_pivot");
            if (pivot == null) {
                pivot = object(responseNode, "birdwatchPivot");
            }
        }
        if (pivot == null) {
            return null;
        }
        var note = object(pivot, "note");
        var data = firstObject(note, "data_v1", "dataV1");
        var summary = object(data, "summary");
        var title = firstNonBlank(richText(pivot.get("title")), richText(pivot.get("heading")));
        var noteText = firstNonBlank(
                richText(summary),
                richText(note == null ? null : note.get("summary")),
                richText(pivot.get("subtitle")),
                richText(pivot.get("text")));
        var footer = richText(pivot.get("footer"));
        if (title == null && noteText == null && footer == null) {
            return null;
        }
        return new CommunityNote(title, noteText, footer,
                note == null ? null : note.path("rest_id").asString(null),
                note == null ? null : note.path("language").asString(null),
                note != null && note.path("is_community_note_translatable").isBoolean()
                        ? note.path("is_community_note_translatable").asBoolean() : null);
    }

    private static String richText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        var value = node.isString() ? node.asString(null) : text(node, "text");
        return value == null || value.isBlank() ? null : HtmlEntityDecoder.decode(value);
    }

    private static Translation parsePreTranslated(JsonNode tweet, JsonNode responseNode) {
        var availability = translationAvailability(tweet);
        if (availability == null && responseNode != tweet) {
            availability = translationAvailability(responseNode);
            if (availability == null && responseNode != null) {
                availability = translationAvailability(responseNode.get("result"));
            }
        }
        if (availability == null
                || !availability.isObject()
                || !bool(availability, "is_available")) {
            return null;
        }
        var data = availability.get("data");
        var translation = text(data, "translation");
        var targetLanguage = text(data, "destination_language");
        if (translation == null || translation.isBlank() || targetLanguage == null) {
            return null;
        }
        return new Translation(
                translation,
                text(data, "source_language"),
                targetLanguage,
                "Grok");
    }

    private static JsonNode translationAvailability(JsonNode node) {
        var availability = node == null
                ? null
                : node.get("grok_translated_post_with_availability");
        return availability != null && availability.isObject() ? availability : null;
    }

    private static Author parseAuthor(JsonNode tweet) {
        var user = findAuthorUser(tweet);
        var legacy = user == null ? null : user.get("legacy");
        var userCore = user == null ? null : user.get("core");
        var avatar = user == null ? null : user.get("avatar");
        var verification = user == null ? null : user.get("verification");
        if (user == null) {
            return new Author("", "", "", null, false);
        }
        return new Author(
                firstNonNull(text(user, "rest_id"), text(legacy, "id_str")),
                firstNonNull(text(userCore, "screen_name"), text(legacy, "screen_name")),
                HtmlEntityDecoder.decode(
                        firstNonNull(text(userCore, "name"), text(legacy, "name"))),
                firstNonNull(
                        text(avatar, "image_url"), text(legacy, "profile_image_url_https")),
                bool(verification, "verified")
                        || bool(legacy, "verified")
                        || bool(user, "is_blue_verified"));
    }

    private static JsonNode findAuthorUser(JsonNode tweet) {
        if (tweet == null) {
            return null;
        }
        var core = tweet.get("core");
        var user = unwrapUser(core == null ? null : core.get("user_results"));
        if (user == null) {
            user = unwrapUser(core == null ? null : core.get("user_result"));
        }
        if (user == null) {
            user = findFirstUser(core, 0);
        }
        if (user == null) {
            user = unwrapUser(tweet.get("author_results"));
        }
        if (user == null) {
            user = unwrapUser(tweet.get("user_results"));
        }
        if (user == null) {
            user = unwrapUser(tweet.get("author"));
        }
        if (user == null) {
            var legacy = tweet.get("legacy");
            user = unwrapUser(legacy == null ? null : legacy.get("user"));
        }
        return user;
    }

    private static JsonNode findFirstUser(JsonNode node, int depth) {
        if (node == null || node.isNull() || depth >= 8) {
            return null;
        }
        if (node.isObject()) {
            if (isUser(node)) {
                return node;
            }
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                var user = findFirstUser(property.getValue(), depth + 1);
                if (user != null) {
                    return user;
                }
            }
        } else if (node.isArray()) {
            for (var child : node) {
                var user = findFirstUser(child, depth + 1);
                if (user != null) {
                    return user;
                }
            }
        }
        return null;
    }

    private static JsonNode unwrapUser(JsonNode node) {
        var current = node;
        for (var depth = 0; current != null && current.isObject() && depth < 8; depth++) {
            if ("UserUnavailable".equals(text(current, "__typename"))) {
                return null;
            }
            if (isUser(current)) {
                return current;
            }
            var next = firstObject(current, "result", "user", "author");
            if (next == null || next == current) {
                return null;
            }
            current = next;
        }
        return null;
    }

    private static boolean isUser(JsonNode node) {
        return "User".equals(text(node, "__typename"))
                || (text(node, "rest_id") != null
                        && (node.get("core") != null
                                || node.get("legacy") != null
                                || node.get("avatar") != null));
    }

    private static JsonNode firstObject(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (var field : fields) {
            var value = node.get(field);
            if (value != null && value.isObject()) {
                return value;
            }
        }
        return null;
    }

    private static List<Media> parseMedia(JsonNode extendedEntities) {
        var mediaNodes = extendedEntities == null ? null : extendedEntities.get("media");
        if (mediaNodes == null || !mediaNodes.isArray()) {
            return List.of();
        }
        var media = new ArrayList<Media>();
        for (JsonNode item : mediaNodes) {
            var type = text(item, "type");
            var preview = text(item, "media_url_https");
            var variants = "photo".equals(type)
                    ? List.<VideoVariant>of()
                    : videoVariants(item.get("video_info"));
            var url = "photo".equals(type) ? preview : bestVideoUrl(variants);
            if (url == null) {
                url = preview;
            }
            media.add(new Media(
                    firstNonNull(text(item, "id_str"), Integer.toString(media.size())),
                     type == null ? "unknown" : type,
                     url,
                     preview,
                     variants));
        }
        return media;
    }

    private static List<VideoVariant> videoVariants(JsonNode videoInfo) {
        var variants = videoInfo == null ? null : videoInfo.get("variants");
        if (variants == null || !variants.isArray()) {
            return List.of();
        }
        var result = new ArrayList<VideoVariant>();
        for (JsonNode variant : variants) {
            if (!"video/mp4".equals(text(variant, "content_type"))) {
                continue;
            }
            var url = text(variant, "url");
            if (url != null && !url.isBlank()) {
                result.add(new VideoVariant(url, nullableNumber(variant, "bitrate")));
            }
        }
        return List.copyOf(result);
    }

    private static String bestVideoUrl(List<VideoVariant> variants) {
        return variants.stream()
                .max(Comparator.comparingLong(variant -> variant.bitrate() == null ? 0 : variant.bitrate()))
                .map(VideoVariant::url)
                .orElse(null);
    }

    private static Long nullableNumber(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asString(null));
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    private static final class CursorState {
        private String value;
        private boolean bottomTerminated;
    }

    private static void findCursor(JsonNode node, CursorState cursor) {
        if ("TimelineTerminateTimeline".equals(text(node, "type"))
                && "Bottom".equalsIgnoreCase(text(node, "direction"))) {
            // X can return a fresh Bottom cursor alongside this terminal instruction.
            // Keep the terminal flag independent of instruction traversal order.
            cursor.bottomTerminated = true;
        }
        var cursorType = firstNonNull(text(node, "cursorType"), text(node, "cursor_type"));
        if ("Bottom".equalsIgnoreCase(cursorType)) {
            cursor.value = text(node, "value");
            return;
        }
        var entryId = firstNonNull(text(node, "entryId"), text(node, "entry_id"));
        if (entryId != null && entryId.toLowerCase(Locale.ROOT).contains("cursor-bottom")) {
            var content = node.get("content");
            if (content != null && text(content, "value") != null) {
                cursor.value = text(content, "value");
            }
        }
    }

    private static String parseCreatedAt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value, X_DATE_FORMAT).toInstant().toString();
        } catch (RuntimeException exception) {
            try {
                return Instant.parse(value).toString();
            } catch (RuntimeException ignored) {
                return value;
            }
        }
    }

    private static Instant sortableTime(Post post) {
        if (post.createdAt() == null) {
            return null;
        }
        try {
            return Instant.parse(post.createdAt());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static long parseViewCount(JsonNode views) {
        var count = views == null ? null : text(views, "count");
        if (count == null) {
            return 0;
        }
        try {
            return Long.parseLong(count);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        var value = node.get(field);
        return value == null || value.isNull() ? null : value.asString(null);
    }

    private static long number(JsonNode node, String field) {
        if (node == null || node.get(field) == null) {
            return 0;
        }
        return node.get(field).asLong(0);
    }

    private static boolean bool(JsonNode node, String field) {
        return node != null && node.get(field) != null && node.get(field).asBoolean(false);
    }

    private static String firstNonNull(String first, String second) {
        return first == null ? second : first;
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static JsonNode object(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value != null && value.isObject() ? value : null;
    }
}

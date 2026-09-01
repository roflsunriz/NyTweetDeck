package dev.nytweetdeck.timeline;

import java.util.List;

public record TimelinePage(List<Post> posts, String nextCursor) {

    public TimelinePage {
        posts = List.copyOf(posts);
    }

    public record Post(
            String id,
            String text,
            List<TextLink> links,
            String language,
            String createdAt,
            Author author,
            Author repostedBy,
            String conversationSection,
            long replyCount,
            long repostCount,
            long quoteCount,
            long likeCount,
            long bookmarkCount,
            long viewCount,
            boolean liked,
            boolean reposted,
            boolean bookmarked,
            String replyToPostId,
            String replyToUsername,
            String quotedPostId,
            EmbeddedPost quotedPost,
            CommunityNote communityNote,
            Translation preTranslated,
            Article article,
            List<Media> media) {

        public Post {
            links = List.copyOf(links);
            media = List.copyOf(media);
        }
    }

    public record Author(
            String id, String username, String displayName, String avatarUrl, boolean verified) {}

    public record EmbeddedPost(
            String id,
            String text,
            List<TextLink> links,
            String language,
            String createdAt,
            Author author,
            Translation preTranslated,
            Article article,
            List<Media> media) {

        public EmbeddedPost {
            links = List.copyOf(links);
            media = List.copyOf(media);
        }
    }

    public record CommunityNote(String title, String text, String footer) {}

    public record Translation(
            String text,
            String sourceLanguage,
            String targetLanguage,
            String provider) {}

    public record Article(
            String id,
            String title,
            String previewText,
            String body,
            String coverImageUrl,
            String url) {}

    public record Media(String id, String type, String url, String previewUrl) {}

    public record TextLink(String url, String displayText) {}
}

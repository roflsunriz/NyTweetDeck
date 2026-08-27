package dev.nytweetdeck.timeline;

import java.util.List;

public record TimelinePage(List<Post> posts, String nextCursor) {

    public TimelinePage {
        posts = List.copyOf(posts);
    }

    public record Post(
            String id,
            String text,
            String language,
            String createdAt,
            Author author,
            Author repostedBy,
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
            List<Media> media) {

        public Post {
            media = List.copyOf(media);
        }
    }

    public record Author(
            String id, String username, String displayName, String avatarUrl, boolean verified) {}

    public record EmbeddedPost(
            String id,
            String text,
            String language,
            String createdAt,
            Author author,
            List<Media> media) {

        public EmbeddedPost {
            media = List.copyOf(media);
        }
    }

    public record CommunityNote(String title, String text, String footer) {}

    public record Translation(
            String text,
            String sourceLanguage,
            String targetLanguage,
            String provider) {}

    public record Media(String id, String type, String url, String previewUrl) {}
}

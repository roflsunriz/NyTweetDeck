package dev.nytweetdeck.timeline;

import java.util.List;

public record TimelinePage(List<Post> posts, String nextCursor) {

    public TimelinePage {
        posts = List.copyOf(posts);
    }

    public record Post(
            String id,
            String text,
            String createdAt,
            Author author,
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
            String quotedPostId,
            List<Media> media) {

        public Post {
            media = List.copyOf(media);
        }
    }

    public record Author(
            String id, String username, String displayName, String avatarUrl, boolean verified) {}

    public record Media(String id, String type, String url, String previewUrl) {}
}

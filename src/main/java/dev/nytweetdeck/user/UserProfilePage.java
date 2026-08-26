package dev.nytweetdeck.user;

import java.util.List;

public record UserProfilePage(
        String id,
        String username,
        String displayName,
        String description,
        String avatarUrl,
        String bannerUrl,
        String createdAt,
        String location,
        String website,
        long followingCount,
        long followerCount,
        long mutualFollowerCount,
        List<RelatedUser> mutualFollowers,
        boolean verified,
        boolean following,
        boolean followsYou) {

    public UserProfilePage {
        mutualFollowers = List.copyOf(mutualFollowers);
    }

    public record RelatedUser(
            String id, String username, String displayName, String avatarUrl) {}
}

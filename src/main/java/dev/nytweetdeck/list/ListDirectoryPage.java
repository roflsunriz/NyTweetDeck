package dev.nytweetdeck.list;

import java.util.List;

public record ListDirectoryPage(List<ListOption> lists, String nextCursor) {

    public ListDirectoryPage {
        lists = List.copyOf(lists);
    }

    public record ListOption(
            String id,
            String name,
            String description,
            String ownerName,
            String ownerUsername,
            long memberCount,
            long subscriberCount,
            String source) {}
}

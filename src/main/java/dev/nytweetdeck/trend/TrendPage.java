package dev.nytweetdeck.trend;

import java.util.List;

public record TrendPage(List<Trend> trends, String nextCursor) {

    public TrendPage {
        trends = List.copyOf(trends);
    }

    public record Trend(
            String name,
            String description,
            String rank,
            String url,
            String domainContext,
            String metaDescription) {}
}

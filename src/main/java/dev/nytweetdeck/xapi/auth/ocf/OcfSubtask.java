package dev.nytweetdeck.xapi.auth.ocf;

import java.util.List;

public record OcfSubtask(
        String id,
        OcfSubtaskType type,
        String prompt,
        String hint,
        String nextLink,
        List<Choice> choices) {

    public OcfSubtask {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("subtask IDが空です。");
        }
        choices = List.copyOf(choices);
    }

    public record Choice(String id, String label) {}
}

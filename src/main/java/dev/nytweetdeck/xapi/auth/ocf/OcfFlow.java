package dev.nytweetdeck.xapi.auth.ocf;

import java.util.List;

public record OcfFlow(String flowToken, List<OcfSubtask> subtasks) {

    public OcfFlow {
        if (flowToken == null || flowToken.isBlank()) {
            throw new IllegalArgumentException("flow tokenが空です。");
        }
        subtasks = List.copyOf(subtasks);
    }

    @Override
    public String toString() {
        return "OcfFlow[flowToken=<redacted>, subtasks=" + subtasks + "]";
    }
}

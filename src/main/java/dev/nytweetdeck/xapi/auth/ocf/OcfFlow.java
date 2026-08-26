package dev.nytweetdeck.xapi.auth.ocf;

import java.util.List;

public record OcfFlow(String flowToken, List<OcfSubtask> subtasks, OcfAccount account) {

    public OcfFlow(String flowToken, List<OcfSubtask> subtasks) {
        this(flowToken, subtasks, null);
    }

    public OcfFlow {
        if (flowToken == null || flowToken.isBlank()) {
            throw new IllegalArgumentException("flow tokenが空です。");
        }
        subtasks = List.copyOf(subtasks);
    }

    @Override
    public String toString() {
        return "OcfFlow[flowToken=<redacted>, subtasks="
                + subtasks
                + ", account="
                + (account == null ? "null" : "<redacted>")
                + "]";
    }

    public record OcfAccount(
            String userId,
            String username,
            String displayName,
            String oauthToken,
            String oauthTokenSecret) {

        @Override
        public String toString() {
            return "OcfAccount[userId="
                    + userId
                    + ", username="
                    + username
                    + ", displayName="
                    + displayName
                    + ", oauthToken=<redacted>, oauthTokenSecret=<redacted>]";
        }
    }
}

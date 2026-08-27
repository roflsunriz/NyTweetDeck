package dev.nytweetdeck.notification;

import dev.nytweetdeck.timeline.TimelineResponseParser;
import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CommunityNoteService {

    private final AuthenticatedGraphQlClient graphQlClient;
    private final CommunityNoteResponseParser noteParser;
    private final TimelineResponseParser timelineParser;

    public CommunityNoteService(
            AuthenticatedGraphQlClient graphQlClient,
            CommunityNoteResponseParser noteParser,
            TimelineResponseParser timelineParser) {
        this.graphQlClient = graphQlClient;
        this.noteParser = noteParser;
        this.timelineParser = timelineParser;
    }

    public CommunityNoteDetail detail(String accountId, String noteId, String language) {
        validateNoteId(noteId);
        var result = graphQlClient.execute(
                accountId, "communityNote", Map.of("note_id", noteId), language);
        var note = noteParser.parse(result.rawJson(), noteId);
        var postVariables = new LinkedHashMap<String, Object>();
        postVariables.put("tweetId", note.targetPostId());
        postVariables.put("withCommunity", false);
        postVariables.put("includePromotedContent", false);
        postVariables.put("withVoice", false);
        var postResult = graphQlClient.execute(
                accountId, "postDetail", postVariables, language);
        var post = timelineParser.parse(postResult.rawJson()).posts().stream()
                .filter(candidate -> candidate.id().equals(note.targetPostId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "コミュニティノートの対象ポスト応答にポストがありません。"));
        return new CommunityNoteDetail(note.noteId(), note.text(), note.sources(), post);
    }

    private static void validateNoteId(String noteId) {
        if (noteId == null || !noteId.matches("[0-9]{1,24}")) {
            throw new IllegalArgumentException("コミュニティノートIDの形式が不正です。");
        }
    }
}

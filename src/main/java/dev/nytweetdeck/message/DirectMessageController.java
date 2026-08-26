package dev.nytweetdeck.message;

import dev.nytweetdeck.xapi.rest.AuthenticatedRestClient;
import java.util.LinkedHashMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/messages")
public class DirectMessageController {

    private final AuthenticatedRestClient restClient;
    private final DirectMessageResponseParser responseParser;

    public DirectMessageController(
            AuthenticatedRestClient restClient, DirectMessageResponseParser responseParser) {
        this.restClient = restClient;
        this.responseParser = responseParser;
    }

    @GetMapping
    public DirectMessagePage messages(
            @RequestParam String accountId, @RequestParam(required = false) String cursor) {
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("dm_users", "true");
        parameters.put("include_groups", "true");
        parameters.put("include_inbox_timelines", "true");
        parameters.put("include_ext_media_color", "true");
        parameters.put("supports_reactions", "true");
        parameters.put("supports_edit", "true");
        parameters.put("include_ext_edit_control", "true");
        parameters.put("include_ext_business_affiliations_label", "true");
        parameters.put("include_ext_parody_commentary_fan_label", "true");
        String endpoint = "dmInboxInitial";
        if (cursor != null && !cursor.isBlank()) {
            parameters.put("max_id", cursor);
            endpoint = "dmInboxTrusted";
        }
        var result = restClient.get(accountId, endpoint, parameters);
        return responseParser.parse(result.rawJson());
    }
}

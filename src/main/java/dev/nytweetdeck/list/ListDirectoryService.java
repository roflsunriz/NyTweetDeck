package dev.nytweetdeck.list;

import dev.nytweetdeck.account.AccountStore;
import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;

@Service
public class ListDirectoryService {

    private final AuthenticatedGraphQlClient graphQlClient;
    private final AccountStore accountStore;
    private final ListDirectoryParser parser;

    public ListDirectoryService(
            AuthenticatedGraphQlClient graphQlClient,
            AccountStore accountStore,
            ListDirectoryParser parser) {
        this.graphQlClient = graphQlClient;
        this.accountStore = accountStore;
        this.parser = parser;
    }

    public ListDirectoryPage list(
            String accountId, String scope, String query, String cursor) {
        var variables = new LinkedHashMap<String, Object>();
        variables.put("count", 50);
        if (cursor != null && !cursor.isBlank()) {
            variables.put("cursor", cursor);
        }
        String purpose;
        switch (scope) {
            case "mine" -> {
                purpose = "combinedLists";
                variables.put("userId", accountStore.requireAccount(accountId).userId());
            }
            case "suggested" -> purpose = "listsDiscovery";
            case "search" -> {
                purpose = "listSearch";
                if (query == null || query.isBlank() || query.length() > 100) {
                    throw new IllegalArgumentException("リスト検索語を入力してください。");
                }
                variables.put("count", 20);
                variables.put("rawQuery", query.strip());
            }
            default -> throw new IllegalArgumentException("未対応のリスト範囲です。");
        }
        var result = graphQlClient.execute(accountId, purpose, variables);
        return parser.parse(result.rawJson(), scope);
    }
}

package dev.nytweetdeck.list;

import dev.nytweetdeck.account.vault.AccountVaultSessionManager;
import dev.nytweetdeck.xapi.graphql.AuthenticatedGraphQlClient;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;

@Service
public class ListDirectoryService {

    private final AuthenticatedGraphQlClient graphQlClient;
    private final AccountVaultSessionManager vaultSessionManager;
    private final ListDirectoryParser parser;

    public ListDirectoryService(
            AuthenticatedGraphQlClient graphQlClient,
            AccountVaultSessionManager vaultSessionManager,
            ListDirectoryParser parser) {
        this.graphQlClient = graphQlClient;
        this.vaultSessionManager = vaultSessionManager;
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
                variables.put("userId", vaultSessionManager.requireAccount(accountId).userId());
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

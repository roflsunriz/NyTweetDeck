package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.ListDirectoryPage
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.ListDirectoryParser
import dev.nytweetdeck.android.xapi.XSessionCredentials

class ListDirectoryRepository(
    private val graphQlExecutor: GraphQlExecutor,
    private val parser: ListDirectoryParser = ListDirectoryParser(),
) {
    fun load(
        account: AccountSecrets,
        scope: String,
        query: String? = null,
        cursor: String? = null,
        language: String = "ja",
    ): ListDirectoryPage {
        val variables = linkedMapOf<String, Any?>("count" to 50)
        cursor?.takeIf(String::isNotBlank)?.let { variables["cursor"] = it }
        val purpose = when (scope) {
            "mine" -> {
                variables["userId"] = account.userId
                "combinedLists"
            }
            "suggested" -> "listsDiscovery"
            "search" -> {
                val normalized = query?.trim().orEmpty()
                require(normalized.isNotBlank() && normalized.length <= 100) {
                    "リスト検索語を入力してください。"
                }
                variables["count"] = 20
                variables["rawQuery"] = normalized
                "listSearch"
            }
            else -> throw IllegalArgumentException("未対応のリスト範囲です。")
        }
        val body = graphQlExecutor.execute(
            XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
            purpose,
            variables,
            language,
        )
        return parser.parse(body, scope)
    }
}

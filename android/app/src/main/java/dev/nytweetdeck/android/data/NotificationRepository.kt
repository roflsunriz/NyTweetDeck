package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.NotificationPage
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.NotificationResponseParser
import dev.nytweetdeck.android.xapi.XSessionCredentials

class NotificationRepository(
    private val graphQlExecutor: GraphQlExecutor,
    private val parser: NotificationResponseParser = NotificationResponseParser(),
) {
    fun load(account: AccountSecrets, cursor: String? = null, language: String = "ja"): NotificationPage {
        val variables = linkedMapOf<String, Any?>(
            "timeline_type" to "All",
            "count" to 20,
        )
        cursor?.takeIf(String::isNotBlank)?.let { variables["cursor"] = it }
        val body = graphQlExecutor.execute(
            XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
            "notifications",
            variables,
            language,
        )
        return parser.parse(body)
    }
}

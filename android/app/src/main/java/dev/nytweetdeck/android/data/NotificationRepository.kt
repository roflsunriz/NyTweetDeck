package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.NotificationPage
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.NotificationResponseParser
import dev.nytweetdeck.android.xapi.XSessionCredentials

class NotificationRepository(
    private val graphQlExecutor: GraphQlExecutor,
    private val parser: NotificationResponseParser = NotificationResponseParser(),
    private val cache: TimelineCache? = null,
) {
    fun cached(account: AccountSecrets, language: String = "ja"): NotificationPage? =
        cache?.read(account.accountId, "notifications", language)?.let { body ->
            runCatching { parser.parse(body) }.getOrNull()
        }

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
        val page = parser.parse(body)
        if (cursor.isNullOrBlank()) cache?.write(account.accountId, "notifications", language, body)
        return page
    }
}

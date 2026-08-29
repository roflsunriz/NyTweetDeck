package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.TimelinePage
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.TimelineQueryFactory
import dev.nytweetdeck.android.xapi.TimelineResponseParser
import dev.nytweetdeck.android.xapi.XSessionCredentials

class TimelineRepository(
    private val graphQlExecutor: GraphQlExecutor,
    private val responseParser: TimelineResponseParser = TimelineResponseParser(),
    private val cache: TimelineCache? = null,
) {
    fun load(
        account: AccountSecrets,
        kind: String,
        target: String? = null,
        cursor: String? = null,
        language: String = "ja",
    ): TimelinePage {
        val query = TimelineQueryFactory.create(kind, target, cursor)
        val body = graphQlExecutor.execute(
            credentials = XSessionCredentials(
                bearerToken = account.webBearerToken,
                authToken = account.authToken,
                csrfToken = account.csrfToken,
            ),
            purpose = query.purpose,
            variables = query.variables,
            language = language,
        )
        val page = responseParser.parse(body)
        if (cursor.isNullOrBlank()) {
            cache?.write(account.accountId, kind, target, body)
        }
        return page
    }

    fun cached(account: AccountSecrets, kind: String, target: String? = null): TimelinePage? {
        val body = cache?.read(account.accountId, kind, target) ?: return null
        return runCatching { responseParser.parse(body) }.getOrNull()
    }
}

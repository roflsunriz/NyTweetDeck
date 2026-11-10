package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.TrendPage
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.TimelineQueryFactory
import dev.nytweetdeck.android.xapi.TrendResponseParser
import dev.nytweetdeck.android.xapi.XSessionCredentials

class TrendRepository(
    private val graphQlExecutor: GraphQlExecutor,
    private val parser: TrendResponseParser = TrendResponseParser(),
    private val cache: TimelineCache? = null,
) {
    fun cached(account: AccountSecrets, language: String = "ja"): TrendPage? =
        cache?.read(account.accountId, "trends", language)?.let { body ->
            runCatching { parser.parse(body) }.getOrNull()
        }

    fun load(account: AccountSecrets, cursor: String? = null, language: String = "ja"): TrendPage {
        val query = TimelineQueryFactory.create("trends", null, cursor)
        val body = graphQlExecutor.execute(
            XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
            query.purpose,
            query.variables,
            language,
        )
        val page = parser.parse(body)
        if (cursor.isNullOrBlank()) cache?.write(account.accountId, "trends", language, body)
        return page
    }
}

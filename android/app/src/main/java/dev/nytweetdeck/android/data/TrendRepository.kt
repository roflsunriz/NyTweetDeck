package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.TrendPage
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.TimelineQueryFactory
import dev.nytweetdeck.android.xapi.TrendResponseParser
import dev.nytweetdeck.android.xapi.XSessionCredentials

class TrendRepository(
    private val graphQlExecutor: GraphQlExecutor,
    private val parser: TrendResponseParser = TrendResponseParser(),
) {
    fun load(account: AccountSecrets, cursor: String? = null, language: String = "ja"): TrendPage {
        val query = TimelineQueryFactory.create("trends", null, cursor)
        val body = graphQlExecutor.execute(
            XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
            query.purpose,
            query.variables,
            language,
        )
        return parser.parse(body)
    }
}

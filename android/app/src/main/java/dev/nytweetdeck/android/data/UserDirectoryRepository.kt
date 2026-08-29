package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.UserOption
import dev.nytweetdeck.android.xapi.GraphQlExecutor
import dev.nytweetdeck.android.xapi.UserProfileParser
import dev.nytweetdeck.android.xapi.XSessionCredentials

class UserDirectoryRepository(
    private val graphQlExecutor: GraphQlExecutor,
    private val parser: UserProfileParser = UserProfileParser(),
) {
    fun resolve(account: AccountSecrets, input: String, language: String = "ja"): UserOption {
        val username = input.trim().removePrefix("@")
        require(username.matches(Regex("[A-Za-z0-9_]{1,15}"))) {
            "有効なXユーザー名を入力してください。"
        }
        val body = graphQlExecutor.execute(
            XSessionCredentials(account.webBearerToken, account.authToken, account.csrfToken),
            "userByScreenName",
            mapOf("screen_name" to username, "withGrokTranslatedBio" to false),
            language,
        )
        return parser.resolve(body, username)
    }
}

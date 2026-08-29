package dev.nytweetdeck.android.data

import dev.nytweetdeck.android.model.DirectMessagePage
import dev.nytweetdeck.android.xapi.AuthenticatedRestClient
import dev.nytweetdeck.android.xapi.DirectMessageResponseParser

class DirectMessageRepository(
    private val restClient: AuthenticatedRestClient,
    private val parser: DirectMessageResponseParser = DirectMessageResponseParser(),
) {
    fun load(account: AccountSecrets, cursor: String? = null, language: String = "ja"): DirectMessagePage {
        val parameters = linkedMapOf(
            "dm_users" to "true",
            "include_groups" to "true",
            "include_inbox_timelines" to "true",
            "include_ext_media_color" to "true",
            "supports_reactions" to "true",
            "supports_edit" to "true",
            "include_ext_edit_control" to "true",
            "include_ext_business_affiliations_label" to "true",
            "include_ext_parody_commentary_fan_label" to "true",
        )
        val endpoint = if (cursor.isNullOrBlank()) {
            "dmInboxInitial"
        } else {
            parameters["max_id"] = cursor
            "dmInboxTrusted"
        }
        return parser.parse(restClient.get(account, endpoint, parameters, language))
    }
}

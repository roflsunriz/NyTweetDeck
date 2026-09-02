package dev.nytweetdeck.android.xapi

import java.util.Locale

data class TimelineQuery(
    val purpose: String,
    val variables: Map<String, Any?>,
)

object TimelineQueryFactory {
    fun create(kind: String, target: String?, cursor: String?, sort: String = "latest"): TimelineQuery {
        val normalizedSort = sort.trim().lowercase(Locale.ROOT).also {
            require(it == "latest" || it == "top") { "カラムの並び順が不正です。" }
        }
        val variables = linkedMapOf<String, Any?>("count" to 20)
        cursor?.takeIf(String::isNotBlank)?.let { variables["cursor"] = it }
        val purpose = when (kind) {
            "homeForYou" -> {
                variables["enableRanking"] = normalizedSort == "top"
                variables["includePromotedContent"] = false
                variables["requestContext"] = "launch"
                variables["withCommunity"] = true
                "homeForYou"
            }
            "homeFollowing" -> {
                variables["enableRanking"] = normalizedSort == "top"
                variables["includePromotedContent"] = false
                variables["requestContext"] = "launch"
                "homeFollowing"
            }
            "userPosts" -> {
                variables["userId"] = requireTarget(target, kind)
                variables["includePromotedContent"] = false
                variables["withQuickPromoteEligibilityTweetFields"] = false
                variables["withVoice"] = true
                "userPosts"
            }
            "list" -> {
                variables["listId"] = requireTarget(target, kind)
                "list"
            }
            "history", "trends", "notifications" -> kind
            "search" -> {
                variables["rawQuery"] = requireTarget(target, kind)
                variables["querySource"] = "typed_query"
                variables["product"] = if (normalizedSort == "top") "Top" else "Latest"
                variables["withGrokTranslatedBio"] = false
                variables["withQuickPromoteEligibilityTweetFields"] = false
                "search"
            }
            else -> throw IllegalArgumentException("未対応のタイムライン種別です: $kind")
        }
        return TimelineQuery(purpose, variables.toMap())
    }

    private fun requireTarget(value: String?, kind: String): String {
        require(value != null && value.isNotBlank() && value.length <= 200) {
            "${kind}には有効な対象が必要です。"
        }
        return value
    }
}

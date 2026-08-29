package dev.nytweetdeck.android.xapi

import dev.nytweetdeck.android.model.RelatedUser
import dev.nytweetdeck.android.model.UserOption
import dev.nytweetdeck.android.model.UserProfilePage
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** X Web APIのユーザー解決、プロフィール、共通フォロワー応答をAndroidモデルへ正規化する。 */
class UserProfileParser(
    private val json: Json = Json,
) {
    /** UserByScreenName応答から、要求したscreen nameと一致するユーザーを抽出する。 */
    fun resolve(
        body: String,
        screenName: String,
    ): UserOption {
        val normalizedScreenName = normalizeScreenName(screenName)
        return try {
            val user = findUserByScreenName(
                json.parseToJsonElement(body),
                normalizedScreenName,
            ) ?: throw XApiException("Xユーザーが見つかりません。", 404)
            user.toUserOption()
        } catch (exception: XApiException) {
            throw exception
        } catch (exception: Exception) {
            throw XApiException("Xユーザー情報を解析できません。", cause = exception)
        }
    }

    /**
     * UserByRestIdとFollowersYouKnowの応答を結合して、要求したユーザーのプロフィールを返す。
     * 通信・GraphQL変数の構築はこのパーサーの責務外とする。
     */
    fun parseProfile(
        profileBody: String,
        expectedUserId: String,
        mutualFollowersBody: String,
    ): UserProfilePage {
        require(REST_ID.matches(expectedUserId)) { "XユーザーID形式が不正です。" }
        return try {
            val user = findUserByRestId(
                json.parseToJsonElement(profileBody),
                expectedUserId,
            ) ?: throw XApiException("Xユーザープロフィールが見つかりません。", 404)
            val mutualFollowers = parseMutualFollowers(
                json.parseToJsonElement(mutualFollowersBody),
                expectedUserId,
            )
            user.toProfile(mutualFollowers)
        } catch (exception: XApiException) {
            throw exception
        } catch (exception: Exception) {
            throw XApiException("Xユーザープロフィールを解析できません。", cause = exception)
        }
    }

    private fun JsonObject.toUserOption(): UserOption {
        val core = objectValue("core")
        val legacy = objectValue("legacy")
        val avatar = objectValue("avatar")
        return UserOption(
            id = text("rest_id").orEmpty(),
            username = firstNonNull(core.text("screen_name"), legacy.text("screen_name")),
            displayName = firstNonNull(core.text("name"), legacy.text("name")),
            avatarUrl = firstNonNull(
                avatar.text("image_url"),
                legacy.text("profile_image_url_https"),
            ),
        )
    }

    private fun JsonObject.toProfile(mutualFollowers: List<RelatedUser>): UserProfilePage {
        val legacy = objectValue("legacy")
        val core = objectValue("core")
        val avatar = objectValue("avatar")
        val bio = objectValue("profile_bio")
        val location = objectValue("location")
        val website = objectValue("website")
        val counts = objectValue("relationship_counts")
        val perspectives = objectValue("relationship_perspectives")
        val verification = objectValue("verification")
        return UserProfilePage(
            id = text("rest_id").orEmpty(),
            username = firstNonNull(core.text("screen_name"), legacy.text("screen_name")),
            displayName = firstNonNull(core.text("name"), legacy.text("name")),
            description = firstNonNull(bio.text("description"), legacy.text("description")),
            avatarUrl = firstNonNull(
                avatar.text("image_url"),
                legacy.text("profile_image_url_https"),
            ),
            bannerUrl = firstNonNull(text("profile_banner_url"), legacy.text("profile_banner_url")),
            createdAt = firstNonNull(core.text("created_at"), legacy.text("created_at")),
            location = firstNonNull(location.text("location"), legacy.text("location")),
            website = firstNonNull(website.text("url"), expandedWebsite(legacy)),
            followingCount = firstPositive(
                firstPositive(counts.number("friends_count"), counts.number("following")),
                legacy.number("friends_count"),
            ),
            followerCount = firstPositive(
                firstPositive(counts.number("followers_count"), counts.number("followers")),
                legacy.number("followers_count"),
            ),
            mutualFollowerCount = mutualFollowers.size.toLong(),
            mutualFollowers = mutualFollowers,
            verified = verification.bool("verified") || legacy.bool("verified") || bool("is_blue_verified"),
            following = perspectives.bool("following") || legacy.bool("following"),
            followsYou = perspectives.bool("followed_by") || legacy.bool("followed_by"),
            muting = perspectives.bool("muting") || legacy.bool("muting"),
            blocking = perspectives.bool("blocking") || legacy.bool("blocking"),
        )
    }

    private fun parseMutualFollowers(
        root: JsonElement,
        excludedUserId: String,
    ): List<RelatedUser> {
        val users = LinkedHashMap<String, RelatedUser>()
        collectMutualFollowers(root, excludedUserId, users)
        return users.values.toList()
    }

    private fun collectMutualFollowers(
        node: JsonElement?,
        excludedUserId: String,
        users: MutableMap<String, RelatedUser>,
    ) {
        when (node) {
            is JsonArray -> node.forEach { collectMutualFollowers(it, excludedUserId, users) }
            is JsonObject -> {
                val id = node.text("rest_id")
                if (isUser(node) && id != null && id != excludedUserId) {
                    val core = node.objectValue("core")
                    val avatar = node.objectValue("avatar")
                    users.putIfAbsent(
                        id,
                        RelatedUser(
                            id = id,
                            username = core.text("screen_name"),
                            displayName = core.text("name"),
                            avatarUrl = avatar.text("image_url"),
                        ),
                    )
                    return
                }
                node.values.forEach { collectMutualFollowers(it, excludedUserId, users) }
            }

            else -> Unit
        }
    }

    private fun findUserByScreenName(
        node: JsonElement?,
        expectedScreenName: String,
    ): JsonObject? = findUser(node) { user ->
        user.screenName()?.equals(expectedScreenName, ignoreCase = true) == true
    }

    private fun findUserByRestId(
        node: JsonElement?,
        expectedUserId: String,
    ): JsonObject? = findUser(node) { user -> user.text("rest_id") == expectedUserId }

    private fun findUser(
        node: JsonElement?,
        matches: (JsonObject) -> Boolean,
    ): JsonObject? = when (node) {
        is JsonObject -> {
            if (isUser(node) && matches(node)) {
                node
            } else {
                node.values.firstNotNullOfOrNull { findUser(it, matches) }
            }
        }

        is JsonArray -> node.firstNotNullOfOrNull { findUser(it, matches) }
        else -> null
    }

    private fun JsonObject.screenName(): String? {
        val core = objectValue("core")
        val legacy = objectValue("legacy")
        return firstNonNull(core.text("screen_name"), legacy.text("screen_name"))
    }

    private fun expandedWebsite(legacy: JsonObject?): String? {
        val urls = legacy
            ?.objectValue("entities")
            ?.objectValue("url")
            ?.get("urls") as? JsonArray
        return (urls?.firstOrNull() as? JsonObject)?.text("expanded_url")
    }

    private fun isUser(node: JsonObject): Boolean =
        node.text("__typename") == "User" && node.text("rest_id") != null

    private fun normalizeScreenName(value: String): String {
        val normalized = value.trim().removePrefix("@")
        require(USERNAME.matches(normalized)) { "有効なXユーザー名を入力してください。" }
        return normalized
    }

    private fun JsonObject?.text(name: String): String? =
        (this?.get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.objectValue(name: String): JsonObject? =
        this?.get(name) as? JsonObject

    private fun JsonObject?.number(name: String): Long =
        (this?.get(name) as? JsonPrimitive)?.longOrNull ?: 0L

    private fun JsonObject?.bool(name: String): Boolean =
        (this?.get(name) as? JsonPrimitive)?.booleanOrNull ?: false

    private fun firstNonNull(first: String?, second: String?): String? = first ?: second

    private fun firstPositive(first: Long, second: Long): Long = if (first > 0) first else second

    private companion object {
        val REST_ID = Regex("[0-9]{1,24}")
        val USERNAME = Regex("[A-Za-z0-9_]{1,15}")
    }
}

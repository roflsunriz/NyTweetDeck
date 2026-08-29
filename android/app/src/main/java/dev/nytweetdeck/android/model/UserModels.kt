package dev.nytweetdeck.android.model

/** screen nameから解決した、NyTweetDeck内プロフィールへ遷移するためのユーザー参照。 */
data class UserOption(
    val id: String,
    val username: String?,
    val displayName: String?,
    val avatarUrl: String?,
)

/** X Web APIのプロフィール応答と共通フォロワー応答を正規化したプロフィール。 */
data class UserProfilePage(
    val id: String,
    val username: String?,
    val displayName: String?,
    val description: String?,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val createdAt: String?,
    val location: String?,
    val website: String?,
    val followingCount: Long,
    val followerCount: Long,
    val mutualFollowerCount: Long,
    val mutualFollowers: List<RelatedUser>,
    val verified: Boolean,
    val following: Boolean,
    val followsYou: Boolean,
    val muting: Boolean,
    val blocking: Boolean,
)

/** プロフィールに表示する、ログイン中のアカウントと共通するフォロワー。 */
data class RelatedUser(
    val id: String,
    val username: String?,
    val displayName: String?,
    val avatarUrl: String?,
)

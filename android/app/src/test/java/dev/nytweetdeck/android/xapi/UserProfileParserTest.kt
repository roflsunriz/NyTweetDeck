package dev.nytweetdeck.android.xapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileParserTest {
    private val parser = UserProfileParser()

    @Test
    fun resolvesTheRequestedScreenNameAndIgnoresAnEarlierNestedUser() {
        val user = parser.resolve(
            """
            {"data":{"user":{"result":{"metadata":{"decoy":{"__typename":"User","rest_id":"1",
            "core":{"screen_name":"other","name":"Other"}}},"target":{"result":{
            "__typename":"User","rest_id":"42","core":{"screen_name":"Alice","name":"Alice"},
            "avatar":{"image_url":"https://pbs.twimg.com/alice.jpg"}}}}}}}
            """.trimIndent(),
            " @alice ",
        )

        assertEquals("42", user.id)
        assertEquals("Alice", user.username)
        assertEquals("Alice", user.displayName)
        assertEquals("https://pbs.twimg.com/alice.jpg", user.avatarUrl)
    }

    @Test
    fun resolvesLegacyIdentityFieldsWhenCurrentFieldsAreAbsent() {
        val user = parser.resolve(
            """
            {"data":{"user":{"result":{"__typename":"User","rest_id":"43","legacy":{
            "screen_name":"legacy_user","name":"Legacy User",
            "profile_image_url_https":"https://pbs.twimg.com/legacy.jpg"}}}}}
            """.trimIndent(),
            "legacy_user",
        )

        assertEquals("43", user.id)
        assertEquals("legacy_user", user.username)
        assertEquals("Legacy User", user.displayName)
        assertEquals("https://pbs.twimg.com/legacy.jpg", user.avatarUrl)
    }

    @Test
    fun normalizesCurrentProfileRelationshipCountsAndMutualFollowers() {
        val profile = parser.parseProfile(
            profileBody = """
                {"data":{"user":{"result":{"metadata":{"decoy":{"__typename":"User","rest_id":"1",
                "core":{"screen_name":"wrong","name":"Wrong"}},"profile":{"__typename":"User",
                "rest_id":"42","core":{"screen_name":"alice","name":"Alice",
                "created_at":"2020-01-02T00:00:00Z"},"avatar":{"image_url":
                "https://pbs.twimg.com/alice.jpg"},"profile_bio":{"description":"Profile description"},
                "profile_banner_url":"https://pbs.twimg.com/banner.jpg","location":{"location":"Tokyo"},
                "website":{"url":"https://example.com"},"relationship_counts":{"friends_count":10,
                "followers_count":"20"},"relationship_perspectives":{"following":true,"followed_by":true},
                "verification":{"verified":true}}}}}}}
            """.trimIndent(),
            expectedUserId = "42",
            mutualFollowersBody = """
                {"data":{"user":{"result":{"timeline":{"instructions":[{"entries":[
                {"content":{"itemContent":{"user_results":{"result":{"__typename":"User","rest_id":"42",
                "core":{"screen_name":"alice","name":"Alice"}}}}}},
                {"content":{"itemContent":{"user_results":{"result":{"__typename":"User","rest_id":"7",
                "core":{"screen_name":"bob","name":"Bob"},"avatar":{"image_url":
                "https://pbs.twimg.com/bob.jpg"}}}}}},
                {"content":{"itemContent":{"user_results":{"result":{"__typename":"User","rest_id":"7",
                "core":{"screen_name":"duplicate","name":"Duplicate"}}}}}},
                {"content":{"itemContent":{"user_results":{"result":{"__typename":"User","rest_id":"8",
                "core":{"screen_name":"carol","name":"Carol"}}}}}}
                ]}]}}}}}
            """.trimIndent(),
        )

        assertEquals("42", profile.id)
        assertEquals("alice", profile.username)
        assertEquals("Alice", profile.displayName)
        assertEquals("Profile description", profile.description)
        assertEquals("https://pbs.twimg.com/alice.jpg", profile.avatarUrl)
        assertEquals("https://pbs.twimg.com/banner.jpg", profile.bannerUrl)
        assertEquals("2020-01-02T00:00:00Z", profile.createdAt)
        assertEquals("Tokyo", profile.location)
        assertEquals("https://example.com", profile.website)
        assertEquals(10L, profile.followingCount)
        assertEquals(20L, profile.followerCount)
        assertEquals(2L, profile.mutualFollowerCount)
        assertEquals(listOf("bob", "carol"), profile.mutualFollowers.map { it.username })
        assertEquals("https://pbs.twimg.com/bob.jpg", profile.mutualFollowers.first().avatarUrl)
        assertTrue(profile.verified)
        assertTrue(profile.following)
        assertTrue(profile.followsYou)
    }

    @Test
    fun fallsBackToLegacyProfileFieldsAndCurrentCountAliases() {
        val profile = parser.parseProfile(
            profileBody = """
                {"data":{"user":{"result":{"__typename":"User","rest_id":"42","is_blue_verified":true,
                "legacy":{"screen_name":"legacy_alice","name":"Legacy Alice","description":"Legacy bio",
                "profile_image_url_https":"https://pbs.twimg.com/legacy-alice.jpg",
                "profile_banner_url":"https://pbs.twimg.com/legacy-banner.jpg",
                "created_at":"Mon Jan 01 00:00:00 +0000 2024","location":"Osaka",
                "friends_count":30,"followers_count":40,"verified":false,"following":true,"followed_by":true,
                "entities":{"url":{"urls":[{"expanded_url":"https://example.org/legacy"}]}}},
                "relationship_counts":{"following":11,"followers":12}}}}}
            """.trimIndent(),
            expectedUserId = "42",
            mutualFollowersBody = """{"data":{"viewer":null}}""",
        )

        assertEquals("legacy_alice", profile.username)
        assertEquals("Legacy Alice", profile.displayName)
        assertEquals("Legacy bio", profile.description)
        assertEquals("https://pbs.twimg.com/legacy-alice.jpg", profile.avatarUrl)
        assertEquals("https://pbs.twimg.com/legacy-banner.jpg", profile.bannerUrl)
        assertEquals("Mon Jan 01 00:00:00 +0000 2024", profile.createdAt)
        assertEquals("Osaka", profile.location)
        assertEquals("https://example.org/legacy", profile.website)
        assertEquals(11L, profile.followingCount)
        assertEquals(12L, profile.followerCount)
        assertEquals(0L, profile.mutualFollowerCount)
        assertTrue(profile.verified)
        assertTrue(profile.following)
        assertTrue(profile.followsYou)
    }

    @Test
    fun usesLegacyCountsWhenCurrentCountsAreNotPositive() {
        val profile = parser.parseProfile(
            profileBody = """
                {"data":{"user":{"result":{"__typename":"User","rest_id":"42",
                "relationship_counts":{"friends_count":0,"followers_count":-1},
                "legacy":{"friends_count":31,"followers_count":41}}}}}
            """.trimIndent(),
            expectedUserId = "42",
            mutualFollowersBody = """{}""",
        )

        assertEquals(31L, profile.followingCount)
        assertEquals(41L, profile.followerCount)
        assertFalse(profile.verified)
        assertFalse(profile.following)
        assertFalse(profile.followsYou)
    }

    @Test
    fun reportsMissingResolvedOrProfileUsersAsNotFound() {
        val resolveException = assertThrows(XApiException::class.java) {
            parser.resolve("""{"data":{"viewer":null}}""", "alice")
        }
        val profileException = assertThrows(XApiException::class.java) {
            parser.parseProfile("""{"data":{"viewer":null}}""", "42", "{}")
        }

        assertEquals("Xユーザーが見つかりません。", resolveException.message)
        assertEquals(404, resolveException.statusCode)
        assertEquals("Xユーザープロフィールが見つかりません。", profileException.message)
        assertEquals(404, profileException.statusCode)
    }

    @Test
    fun validatesScreenNameAndRestIdInputsBeforeParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            parser.resolve("{}", "not valid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parser.parseProfile("{}", "not-a-rest-id", "{}")
        }
    }

    @Test
    fun convertsMalformedResponsesToXApiException() {
        val resolveException = assertThrows(XApiException::class.java) {
            parser.resolve("{not-json", "alice")
        }
        val profileException = assertThrows(XApiException::class.java) {
            parser.parseProfile("{not-json", "42", "{}")
        }

        assertEquals("Xユーザー情報を解析できません。", resolveException.message)
        assertNotNull(resolveException.cause)
        assertEquals("Xユーザープロフィールを解析できません。", profileException.message)
        assertNotNull(profileException.cause)
    }

    @Test
    fun leavesOptionalProfileFieldsNullWhenTheyAreNotProvided() {
        val profile = parser.parseProfile(
            """{"data":{"user":{"result":{"__typename":"User","rest_id":"42"}}}}""",
            "42",
            "{}",
        )

        assertNull(profile.username)
        assertNull(profile.displayName)
        assertNull(profile.description)
        assertNull(profile.avatarUrl)
        assertNull(profile.bannerUrl)
        assertNull(profile.createdAt)
        assertNull(profile.location)
        assertNull(profile.website)
    }
}

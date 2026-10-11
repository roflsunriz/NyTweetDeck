package dev.nytweetdeck.android

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import dev.nytweetdeck.android.model.Author
import dev.nytweetdeck.android.model.EmbeddedPost
import dev.nytweetdeck.android.model.Post
import dev.nytweetdeck.android.model.PostTranslation
import dev.nytweetdeck.android.model.PostTranslationUiState
import dev.nytweetdeck.android.model.TranslationLoadStatus
import dev.nytweetdeck.android.ui.PostCard
import dev.nytweetdeck.android.ui.theme.NyTweetDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PostCardInteractionUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()
    private var openedPost: String? = null
    private var openedQuote: String? = null
    private var createdQuote: String? = null
    private var toggledTranslation: String? = null
    private var openedAuthor: String? = null
    private var sharedPost: String? = null
    private var copiedDetails: String? = null
    private var translatedRequest: TranslatedShareRequest? = null
    private val fixtureTranslation = PostTranslation("101", "en", "ja", "翻訳された本文")
    private var translationSourceLabel = ""

    private data class TranslatedShareRequest(
        val postId: String,
        val absolute: String?,
        val relative: String?,
    )

    @Before
    fun showQuotedRepost() {
        composeRule.activity.setContent {
            NyTweetDeckTheme {
                translationSourceLabel = java.util.Locale.forLanguageTag(fixtureTranslation.sourceLanguage)
                    .getDisplayLanguage(androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PostCard(
                        post = fixturePost(),
                        onPostClick = { openedPost = it },
                    onQuoteClick = { openedQuote = it },
                    onCreateQuoteClick = { createdQuote = it },
                    onAuthorClick = { openedAuthor = it.id },
                    onShareClick = { sharedPost = it },
                    onShareDetailsCopy = { copiedDetails = it },
                    onShareTranslatedCopy = { postId, absolute, relative ->
                        translatedRequest = TranslatedShareRequest(postId, absolute, relative)
                    },
                    translationStates = mapOf(
                        "101" to PostTranslationUiState(
                            TranslationLoadStatus.READY,
                            fixtureTranslation,
                        ),
                    ),
                    onToggleOriginal = { toggledTranslation = it },
                    )
                }
            }
        }
    }

    @Test
    fun quoteAlwaysShowsCompleteAbsoluteTimeAndTranslationToggleShowsSourceLanguage() {
        composeRule.onNodeWithTag("post-absolute-time-9", useUnmergedTree = true).assertExists()
        org.junit.Assert.assertTrue(translationSourceLabel.isNotBlank())
        composeRule.onNodeWithTag("translation-toggle")
            .assertTextContains(translationSourceLabel, substring = true)
    }

    @Test
    fun pendingLikeAndRepostHaveNoSpinner() {
        composeRule.activity.setContent {
            NyTweetDeckTheme(reduceMotion = true) {
                PostCard(post = fixturePost().copy(liked = false),
                    pendingActions = setOf(dev.nytweetdeck.android.model.PostActionType.LIKE,
                        dev.nytweetdeck.android.model.PostActionType.REPOST))
            }
        }
        composeRule.onNodeWithTag("post-action-like-101-pending").assertDoesNotExist()
        composeRule.onNodeWithTag("post-action-repost-101-pending").assertDoesNotExist()
        composeRule.onNodeWithTag("post-action-like-101").performClick()
        composeRule.onNodeWithTag("post-action-like-101-fireworks", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun likePressShowsABurstAndUnlikeDoesNotRepeatIt() {
        org.junit.Assume.assumeTrue(android.animation.ValueAnimator.areAnimatorsEnabled())
        val post = androidx.compose.runtime.mutableStateOf(fixturePost().copy(liked = false))
        try {
            composeRule.activity.setContent {
                NyTweetDeckTheme {
                    PostCard(post = post.value, onLikeClick = { post.value = post.value.copy(liked = !post.value.liked) })
                }
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("post-action-like-101").assertIsDisplayed()
            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithTag("post-action-like-101").performClick()
            composeRule.mainClock.advanceTimeBy(48)
            org.junit.Assert.assertTrue(post.value.liked)
            composeRule.onNodeWithTag("post-action-like-101-fireworks", useUnmergedTree = true).assertExists()
            composeRule.mainClock.advanceTimeBy(800)
            composeRule.onNodeWithTag("post-action-like-101-fireworks", useUnmergedTree = true).assertDoesNotExist()
            composeRule.onNodeWithTag("post-action-like-101").performClick()
            composeRule.mainClock.advanceTimeBy(48)
            org.junit.Assert.assertFalse(post.value.liked)
            composeRule.onNodeWithTag("post-action-like-101-fireworks", useUnmergedTree = true).assertDoesNotExist()
        } finally { composeRule.mainClock.autoAdvance = true }
    }

    @Test
    fun quoteAndParentHaveIndependentTargetsAndAllActionsRemainVisible() {
        composeRule.onNodeWithTag("post-body-101", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("post-quote-body-99", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        assertEquals("99", openedQuote)
        assertNull(openedPost)
        composeRule.onNodeWithText("翻訳された本文").assertExists()
        composeRule.onNodeWithTag("translation-toggle", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        assertEquals("101", toggledTranslation)

        listOf("reply", "repost", "like", "impressions", "bookmark", "share", "download")
            .forEach { action ->
                composeRule.onNodeWithTag("post-action-$action-101").assertExists()
            }
    }

    @Test
    fun replyContextNavigatesToItsParentInsideNyTweetDeck() {
        composeRule.onNodeWithTag("post-reply-context-101", useUnmergedTree = true)
            .performClick()
        assertEquals("90", openedPost)
    }

    @Test
    fun authorHeaderUsesAnIndependentInternalTarget() {
        composeRule.onNodeWithTag("post-author-7", useUnmergedTree = true).performClick()
        assertEquals("7", openedAuthor)
        assertNull(openedPost)
    }

    @Test
    fun shareButtonOffersUrlOnlyAndDetailedCopies() {
        composeRule.onNodeWithTag("post-action-share-101").performClick()
        composeRule.onNodeWithTag("share-menu-101").assertIsDisplayed()
        composeRule.onNodeWithTag("share-menu-details-101").performClick()
        val details = copiedDetails
        assertNull(sharedPost)
        assertNull(openedPost)
        composeRule.runOnIdle {
            assert(details != null)
            assert(details!!.contains("投稿者@author"))
            assert(details.contains("改行を含む本文"))
            assert(details.contains("引用元@quoted"))
            assert(details.contains("> 引用本文"))
            assert(details.endsWith("https://x.com/i/status/101"))
        }

        composeRule.onNodeWithTag("post-action-share-101").performClick()
        composeRule.onNodeWithTag("share-menu-url-101").performClick()
        assertEquals("101", sharedPost)
        assertNull(openedPost)

        // 翻訳版は投稿IDと時刻表示を渡してアプリ層の解決へ委ねる。
        // 解決順と原文フォールバックはPostTranslationShareTestで検証する。
        translatedRequest = null
        composeRule.onNodeWithTag("post-action-share-101").performClick()
        composeRule.onNodeWithTag("share-menu-details-translated-101").performClick()
        val request = translatedRequest
        assertNull(openedPost)
        composeRule.runOnIdle {
            assert(request != null)
            assertEquals("101", request!!.postId)
            assert(request.absolute != null)
            assert(request.relative != null)
        }
    }

    @Test
    fun repostButtonOpensANonModalMenuWithQuoteCreation() {
        composeRule.onNodeWithTag("post-action-repost-101").performClick()
        composeRule.onNodeWithTag("repost-menu-101").assertIsDisplayed()
        composeRule.onNodeWithTag("repost-menu-quote-101").performClick()
        assertEquals("101", createdQuote)
        assertNull(openedPost)
    }

    private fun fixturePost(): Post {
        val author = Author("7", "author", "投稿者", null, true)
        return Post(
            id = "101",
            text = "改行を含む本文\n#NyTweetDeck",
            language = "ja",
            createdAt = "2026-08-29T00:00:00Z",
            author = author,
            repostedBy = Author("8", "reposter", "リポストした人", null, false),
            conversationSection = null,
            replyCount = 1,
            repostCount = 2,
            quoteCount = 3,
            likeCount = 4,
            bookmarkCount = 5,
            viewCount = 6,
            liked = true,
            reposted = true,
            bookmarked = true,
            replyToPostId = "90",
            replyToUsername = "parent",
            quotedPostId = "99",
            quotedPost = EmbeddedPost(
                "99",
                "引用本文",
                "ja",
                "2026-08-29T00:00:00Z",
                Author("9", "quoted", "引用元", null, false),
                null,
                null,
                emptyList(),
            ),
            communityNote = null,
            preTranslated = null,
            article = null,
            media = emptyList(),
        )
    }
}

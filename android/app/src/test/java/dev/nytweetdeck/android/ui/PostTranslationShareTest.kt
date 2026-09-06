package dev.nytweetdeck.android.ui

import dev.nytweetdeck.android.data.AccountSecrets
import dev.nytweetdeck.android.data.PostTranslationRepository
import dev.nytweetdeck.android.data.XPostTranslationEndpoint
import dev.nytweetdeck.android.model.DeckUiState
import dev.nytweetdeck.android.model.Translation
import dev.nytweetdeck.android.xapi.AuthenticatedRestClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostTranslationShareTest {
    private val account = AccountSecrets("7", "7", "nytd", "NyTD", "bearer", "auth", "csrf", "profile")

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun controller(
        endpoint: XPostTranslationEndpoint,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
    ): PostTranslationController {
        val repository = PostTranslationRepository(endpoint)
        val state = MutableStateFlow(DeckUiState(selectedAccountId = "7", translationLanguageTag = "ja"))
        return PostTranslationController(
            repository,
            CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher,
            { account },
            state,
        )
    }

    private fun success(text: String) = AuthenticatedRestClient.RestResult(
        body = """{"result":{"text":"$text"}}""",
        rateLimit = null,
        retryAfterSeconds = null,
    )

    @Test
    fun usesPretranslationWithoutNetwork() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = controller(XPostTranslationEndpoint { _, postId, _, _ ->
            calls += postId
            success("使われない")
        })
        val body = controller.translatedBodyForShare(
            postId = "123",
            text = "Original body",
            sourceLanguage = "en",
            preTranslated = Translation("プリ翻訳の本文", "en", "ja", "Grok"),
        )
        assertEquals("プリ翻訳の本文", body)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun fetchesOnceAndReusesThePool() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = controller(XPostTranslationEndpoint { _, postId, _, _ ->
            calls += postId
            success("ライブ翻訳の本文")
        })
        val first = controller.translatedBodyForShare("124", "Hello world", "en", null)
        val second = controller.translatedBodyForShare("124", "Hello world", "en", null)
        assertEquals("ライブ翻訳の本文", first)
        assertEquals("ライブ翻訳の本文", second)
        assertEquals(1, calls.size)
    }

    @Test
    fun fallsBackToOriginalOnFailure() = runBlocking {
        val controller = controller(XPostTranslationEndpoint { _, _, _, _ ->
            AuthenticatedRestClient.RestResult("""{"error":{"message":"failed"}}""", null, null)
        })
        val body = controller.translatedBodyForShare("125", "Hello world", "en", null)
        assertEquals("Hello world", body)
    }

    @Test
    fun fallsBackToOriginalOnTimeout() = runBlocking {
        val controller = controller(
            XPostTranslationEndpoint { _, _, _, _ ->
                Thread.sleep(500)
                success("遅れて届く訳文")
            },
            // 打ち切りを検証するため取得だけ実スレッドで行う。
            ioDispatcher = Dispatchers.IO,
        )
        val body = controller.translatedBodyForShare("126", "Hello world", "en", null, timeoutMs = 50L)
        assertEquals("Hello world", body)
    }

    @Test
    fun skipsSameLanguageAndUntranslatableText() = runBlocking {
        val calls = mutableListOf<String>()
        val controller = controller(XPostTranslationEndpoint { _, postId, _, _ ->
            calls += postId
            success("使われない")
        })
        assertEquals("Hello world", controller.translatedBodyForShare("127", "Hello world", "ja", null))
        assertEquals(
            "https://example.test/x",
            controller.translatedBodyForShare("128", "https://example.test/x", "en", null),
        )
        assertTrue(calls.isEmpty())
    }
}

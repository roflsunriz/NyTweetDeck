package dev.nytweetdeck.android.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTextTest {
    @Test
    fun skipsIdentifiersLinksAndNonLanguageContent() {
        listOf("", " \n\u200B", "@alice", "@alice @bob_12", "＠alice\nhttps://t.co/photo",
            "@alice https://pbs.twimg.com/media/photo.jpg", "https://example.com/path", "123 😂!", "@a,@b").forEach {
            assertFalse("Unexpected translation candidate: $it", hasTranslatableText(it))
        }
    }

    @Test
    fun preservesProseHashtagsAndAllWritingSystems() {
        listOf("@alice Hello! https://t.co/photo", "@alice ありがとう", "#写真", "你好", "مرحبا", "नमस्ते",
            "Привет", "বাংলা", "foo@example.com", "name@example", "𠮷", "@abcdefghijklmnop",
            "https://t.co/photo　ありがとう", "https://t.co/photo\u00a0Hello").forEach {
            assertTrue("Missing translation candidate: $it", hasTranslatableText(it))
        }
    }
}

package dev.nytweetdeck.android.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Node

class LocaleResourceKeySetTest {
    private val localeDirectories = mapOf(
        "ja" to "values",
        "en" to "values-en",
        "zh" to "values-zh",
        "hi" to "values-hi",
        "es" to "values-es",
        "fr" to "values-fr",
        "ar" to "values-ar",
        "pt" to "values-pt",
        "bn" to "values-bn",
        "ru" to "values-ru",
        "ur" to "values-ur",
    )

    @Test
    fun everySupportedLocaleHasTheExactBaseResourceKeySet() {
        val root = listOf(
            File("app/src/main/res"),
            File("src/main/res"),
        ).firstOrNull(File::isDirectory)
            ?: error("Android resource directory is unavailable.")
        val baseKeys = resourceKeys(File(root, "values/strings.xml"))

        localeDirectories.forEach { (languageTag, directory) ->
            assertEquals(
                "Locale $languageTag has a different string key set.",
                baseKeys,
                resourceKeys(File(root, "$directory/strings.xml")),
            )
        }
    }

    private fun resourceKeys(file: File): Set<String> {
        require(file.isFile) { "Missing resource file: $file" }
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val keys = linkedSetOf<String>()
        val nodes = document.documentElement.childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node.nodeType == Node.ELEMENT_NODE && (node.nodeName == "string" || node.nodeName == "plurals")) {
                node.attributes.getNamedItem("name")?.nodeValue?.let(keys::add)
            }
        }
        return keys
    }
}

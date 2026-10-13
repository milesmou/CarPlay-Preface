package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ChineseTranslationsTest {
    private val placeholder = Regex("%(?:\\d+\\$)?[dsf]")

    @Test fun everyTranslatableStringHasSimplifiedChineseWithMatchingArguments() {
        val english = load("values")
        val chinese = load("values-zh-rCN")
        assertEquals("Simplified Chinese resource keys", english.keys, chinese.keys)
        for ((name, source) in english) {
            assertEquals(name, placeholders(source), placeholders(chinese.getValue(name)))
        }
    }

    private fun placeholders(value: String) = placeholder.findAll(value).map { it.value }.toList().sorted()

    private fun load(locale: String): Map<String, String> {
        // Strings are spread across several files, not just strings.xml.
        val files = File("src/main/res/$locale")
            .listFiles { file -> file.extension == "xml" }
            ?.sortedBy { it.name }
            ?: error("Missing locale resources: $locale")
        val result = mutableMapOf<String, String>()
        for (file in files) {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = document.getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node.attributes.getNamedItem("translatable")?.nodeValue == "false") continue
                val key = node.attributes.getNamedItem("name").nodeValue
                check(result.put(key, node.textContent) == null) { "Duplicate translation: $locale/$key" }
            }
        }
        return result
    }
}

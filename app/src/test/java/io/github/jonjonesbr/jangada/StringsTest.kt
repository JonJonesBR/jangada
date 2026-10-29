package io.github.jonjonesbr.jangada

import io.github.jonjonesbr.jangada.linux.DockerHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Todo texto visível existe em inglês (values) e em português
 * (values-pt-rBR), com os mesmos marcadores (%1$s, %d...).
 */
class StringsTest {

    private fun load(folder: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/strings.xml"))
        val result = LinkedHashMap<String, String>()
        for (tag in listOf("string", "plurals")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                if (element.getAttribute("translatable") == "false") continue
                result[element.getAttribute("name")] = element.textContent
            }
        }
        return result
    }

    private val english = load("values")
    private val portuguese = load("values-pt-rBR")

    @Test
    fun mesmosTextosNasDuasLinguas() {
        assertTrue(english.isNotEmpty())
        assertEquals("sem tradução para o português", emptySet<String>(), english.keys - portuguese.keys)
        assertEquals("só em português (falta o inglês)", emptySet<String>(), portuguese.keys - english.keys)
    }

    @Test
    fun mesmosMarcadores() {
        val placeholder = Regex("""%(\d+\$)?[sdf]""")
        for ((name, text) in english) {
            val other = portuguese[name] ?: continue
            assertEquals(
                "marcadores diferentes em $name",
                placeholder.findAll(text).map { it.value }.sorted().toList(),
                placeholder.findAll(other).map { it.value }.sorted().toList(),
            )
        }
    }

    @Test
    fun sha256NoFormatoDoRegistro() {
        assertEquals(
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DockerHub.sha256("abc".toByteArray()),
        )
    }
}

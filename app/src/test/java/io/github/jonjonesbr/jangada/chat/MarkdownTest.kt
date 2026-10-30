package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {

    /** Posições onde o texto abre uma ênfase (* ou _). */
    private fun aberturas(text: String): List<Int> =
        text.indices.filter { (text[it] == '*' || text[it] == '_') && Markdown.findEmphasisEnd(text, it) != -1 }

    @Test
    fun underscoreDentroDePalavraNaoEEnfase() {
        assertEquals(emptyList<Int>(), aberturas("MAX_IMAGE_SIDE"))
        assertEquals(emptyList<Int>(), aberturas("ANTHROPIC_API_KEY"))
        assertEquals(emptyList<Int>(), aberturas("meu_arquivo_1.pdf"))
    }

    @Test
    fun underscoreComPalavraInteiraEEnfase() {
        assertEquals(listOf(0), aberturas("_ênfase_"))
        assertEquals(7, Markdown.findEmphasisEnd("_ênfase_", 0))
        assertEquals(listOf(3), aberturas("um _texto_ aqui"))
        assertEquals(9, Markdown.findEmphasisEnd("um _texto_ aqui", 3))
    }

    @Test
    fun asteriscoDepoisDeDigitoNaoEEnfase() {
        assertEquals(emptyList<Int>(), aberturas("2*3*4"))
    }

    @Test
    fun asteriscoSimplesEEnfase() {
        assertEquals(listOf(0), aberturas("*a*"))
        assertEquals(2, Markdown.findEmphasisEnd("*a*", 0))
    }

    @Test
    fun fechamentoColadoEmLetraNaoConta() {
        // "_foo_bar_ baz": o primeiro _ fecha colado em letra, então vale o terceiro.
        assertEquals(8, Markdown.findEmphasisEnd("_foo_bar_ baz", 0))
    }

    @Test
    fun espacoLogoAposAbrirOuAntesDeFecharNaoEEnfase() {
        assertEquals(-1, Markdown.findEmphasisEnd("_ texto_", 0))
        assertEquals(-1, Markdown.findEmphasisEnd("*texto *", 0))
    }
}

package io.github.jonjonesbr.jangada.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksTest {

    @Test
    fun palavraSoDePontuacaoNaoDevolveLink() {
        // Antes, o núcleo vazio casava com qualquer link da tela.
        val tela = { "veja https://exemplo.com/a\n" }
        assertNull(Links.linkAround("....", tela))
        assertNull(Links.linkAround("!!!!", tela))
        assertNull(Links.linkAround("),;!?", tela))
    }

    @Test
    fun palavraComumNaoAbreLinkDeOutraPalavra() {
        assertNull(Links.linkAround("texto") { "veja https://exemplo.com/a\n" })
    }

    @Test
    fun linkComPontuacaoFinalSaiSemPontuacao() {
        assertEquals("https://exemplo.com/a", Links.first("https://exemplo.com/a)."))
        assertEquals(
            "https://exemplo.com/a",
            Links.linkAround("https://exemplo.com/a).") { "veja https://exemplo.com/a).\n" },
        )
    }

    @Test
    fun linkDaTelaPelaPalavraTocada() {
        assertEquals(
            "https://exemplo.com/a",
            Links.linkAround("exemplo") { "veja https://exemplo.com/a\n" },
        )
    }

    @Test
    fun linkQuebradoEmDuasLinhasEhRemontado() {
        // Largura de 20 colunas: a primeira linha enche a tela até a borda.
        val lines = listOf("https://exemplo.com/", "login-abc123")
        val joined = Links.joinHardWrapped(lines, 20)
        assertEquals(listOf("https://exemplo.com/login-abc123"), Links.all(joined))
    }

    @Test
    fun linhaQueNaoEncostaNaBordaNaoEhJuntada() {
        val lines = listOf("https://exemplo.com/a", "outra linha")
        val joined = Links.joinHardWrapped(lines, 80)
        assertEquals(listOf("https://exemplo.com/a"), Links.all(joined))
    }
}

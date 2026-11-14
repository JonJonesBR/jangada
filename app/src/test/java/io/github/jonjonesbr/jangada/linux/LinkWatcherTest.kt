package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkWatcherTest {

    /** Interpreta um texto inteiro, como se o arquivo tivesse exatamente esses bytes. */
    private fun interpret(text: String): LinkEntry {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return interpretLinkBytes(bytes, bytes.size)
    }

    @Test
    fun aceitaHttpEHttps() {
        assertEquals(LinkEntry.Link("https://exemplo.com/a?b=1"), interpret("https://exemplo.com/a?b=1"))
        assertEquals(LinkEntry.Link("http://exemplo.org"), interpret("http://exemplo.org"))
    }

    @Test
    fun tiraEspacosEQuebraDeLinhaDasPontas() {
        assertEquals(LinkEntry.Link("https://exemplo.com"), interpret("  https://exemplo.com\n"))
    }

    @Test
    fun recusaOutrosEsquemasEVazio() {
        assertEquals(LinkEntry.NotHttp, interpret("ftp://exemplo.com"))
        assertEquals(LinkEntry.NotHttp, interpret("javascript:alert(1)"))
        assertEquals(LinkEntry.NotHttp, interpret("exemplo.com"))
        assertEquals(LinkEntry.NotHttp, interpret(""))
        assertEquals(LinkEntry.NotHttp, interpret("   \n"))
    }

    @Test
    fun limiteDeTamanhoEhExato() {
        val igual = "https://" + "a".repeat(LINK_MAX_BYTES - "https://".length)
        assertEquals(LinkEntry.Link(igual), interpret(igual))
        assertEquals(LinkEntry.TooLarge, interpret(igual + "a"))
    }

    @Test
    fun soContaOQueFoiLido() {
        // O buffer pode ter lixo depois do que foi lido: só os primeiros [total] bytes valem.
        val buffer = ("https://ok" + "lixo que nao entra").toByteArray(Charsets.UTF_8)
        assertEquals(LinkEntry.Link("https://ok"), interpretLinkBytes(buffer, "https://ok".length))
    }
}

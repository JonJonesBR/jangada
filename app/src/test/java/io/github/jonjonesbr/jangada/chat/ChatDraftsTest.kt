package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDraftsTest {

    @Test
    fun textoEmBrancoApagaORascunho() {
        assertNull(draftToStore(""))
        assertNull(draftToStore("  \n\t "))
        assertEquals("oi", draftToStore("oi"))
    }

    @Test
    fun rascunhoGrandeEhCortado() {
        val grande = "a".repeat(ChatDrafts.MAX_CHARS + 5)
        assertEquals(ChatDrafts.MAX_CHARS, draftToStore(grande)?.length)
    }

    @Test
    fun anexosIdaEVoltaSemPerder() {
        val lista = listOf(
            Triple("foto.png", "/root/projetos/anexos/foto.png", "image/png"),
            Triple("notas \"importantes\".txt", "/root/projetos/anexos/notas.txt", "text/plain"),
        )
        assertEquals(lista, decodeAttachments(encodeAttachments(lista)))
    }

    @Test
    fun semAnexosOuComDadoInvalidoViraListaVazia() {
        assertTrue(decodeAttachments(null).isEmpty())
        assertTrue(decodeAttachments("isto não é JSON").isEmpty())
        // Objeto sem o campo "path": a leitura inteira falha e não sobra nada pela metade.
        assertTrue(decodeAttachments("[{\"name\":\"a.png\",\"mime\":\"image/png\"}]").isEmpty())
        assertEquals("[]", encodeAttachments(emptyList()))
    }
}

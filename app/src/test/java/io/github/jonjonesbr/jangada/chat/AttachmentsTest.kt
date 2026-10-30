package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class AttachmentsTest {

    private fun length(text: String) = text.codePointCount(0, text.length)

    @Test
    fun nomeCurtoFicaIgual() {
        assertEquals("foto.jpg", Attachments.safeName("foto.jpg"))
        assertEquals("meu_arquivo_1.pdf", Attachments.safeName("meu arquivo/1.pdf"))
        assertEquals("anexo", Attachments.safeName("  /  "))
    }

    @Test
    fun nomeLongoMantemAExtensao() {
        val name = Attachments.safeName("relatorio-" + "a".repeat(120) + "-final.pdf")
        assertTrue(name.endsWith(".pdf"))
        assertEquals(80, length(name))
    }

    @Test
    fun nuncaPartirUmEmoji() {
        val name = Attachments.safeName("😀".repeat(100) + ".png")
        assertTrue(name.endsWith(".png"))
        assertEquals(80, length(name))
        // Nenhum caractere pela metade (surrogate sozinho).
        assertEquals(name, String(name.codePoints().toArray(), 0, length(name)))
        assertTrue(name.removeSuffix(".png").all { it.isSurrogate() })
    }

    @Test
    fun semExtensaoCortaONomeTodo() {
        val name = Attachments.safeName("x".repeat(200))
        assertEquals(80, length(name))
    }

    @Test
    fun copiaDentroDoLimite() {
        val target = File.createTempFile("anexo", ".bin").apply { deleteOnExit() }
        Attachments.copyLimited(ByteArrayInputStream(ByteArray(1000)), target, maxBytes = 1000)
        assertEquals(1000L, target.length())
    }

    @Test
    fun passarDoLimitePara() {
        val target = File.createTempFile("anexo", ".bin").apply { deleteOnExit() }
        try {
            Attachments.copyLimited(ByteArrayInputStream(ByteArray(1001)), target, maxBytes = 1000)
            fail("deveria recusar arquivo acima do limite")
        } catch (e: AttachmentTooLargeException) {
            // Esperado: quem chama apaga a cópia parcial.
        }
        assertTrue(target.length() <= 1000L)
    }
}

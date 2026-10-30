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

    /** Caminho ainda sem arquivo, como o de um anexo novo (copyLimited não sobrescreve). */
    private fun destinoNovo(): File = File.createTempFile("anexo", ".bin").apply { delete(); deleteOnExit() }

    @Test
    fun copiaDentroDoLimite() {
        val target = destinoNovo()
        Attachments.copyLimited(ByteArrayInputStream(ByteArray(1000)), target, maxBytes = 1000)
        assertEquals(1000L, target.length())
    }

    @Test
    fun copiaNaoSobrescreveArquivoExistente() {
        val target = File.createTempFile("anexo", ".bin").apply { deleteOnExit(); writeText("original") }
        try {
            Attachments.copyLimited(ByteArrayInputStream(ByteArray(10)), target)
            fail("deveria recusar destino que já existe")
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            // Esperado.
        }
        assertEquals("original", target.readText())
    }

    @Test
    fun uniqueTrataLinkQuebradoComoOcupado() {
        val dir = java.nio.file.Files.createTempDirectory("anexos").toFile().apply { deleteOnExit() }
        val link = java.nio.file.Files.createSymbolicLink(
            File(dir, "foto.jpg").toPath(),
            java.nio.file.Paths.get("/nao/existe/em/lugar/nenhum"),
        )
        assertTrue(java.nio.file.Files.isSymbolicLink(link))
        assertEquals("foto-2.jpg", Attachments.unique(dir, "foto.jpg").name)
    }

    @Test
    fun uniqueUsaNomeLivre() {
        val dir = java.nio.file.Files.createTempDirectory("anexos").toFile().apply { deleteOnExit() }
        File(dir, "nota.txt").writeText("x")
        assertEquals("nota-2.txt", Attachments.unique(dir, "nota.txt").name)
        assertEquals("livre.txt", Attachments.unique(dir, "livre.txt").name)
    }

    @Test
    fun passarDoLimitePara() {
        val target = destinoNovo()
        try {
            Attachments.copyLimited(ByteArrayInputStream(ByteArray(1001)), target, maxBytes = 1000)
            fail("deveria recusar arquivo acima do limite")
        } catch (e: AttachmentTooLargeException) {
            // Esperado: quem chama apaga a cópia parcial.
        }
        assertTrue(target.length() <= 1000L)
    }
}

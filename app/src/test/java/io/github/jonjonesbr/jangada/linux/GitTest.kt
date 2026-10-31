package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitTest {

    @Test
    fun statusLeCodigosDeDoisLetrasECaminho() {
        val changes = Git.parseStatus(" M a.kt\nM  b.kt\n?? c.txt\nA  d.kt\n D e.kt\n")
        assertEquals(listOf(" M", "M ", "??", "A ", " D"), changes.map { it.code })
        assertEquals(listOf("a.kt", "b.kt", "c.txt", "d.kt", "e.kt"), changes.map { it.path })
    }

    @Test
    fun renomeacaoUsaOCaminhoNovo() {
        val changes = Git.parseStatus("R  antigo.kt -> novo.kt\n")
        assertEquals(1, changes.size)
        assertEquals("novo.kt", changes[0].path)
        assertEquals("R ", changes[0].code)
    }

    @Test
    fun statusIgnoraLinhasVazias() {
        assertEquals(1, Git.parseStatus("\n\n M a.kt\n\n").size)
        assertTrue(Git.parseStatus("").isEmpty())
    }

    @Test
    fun caminhoComEspacoEPreservado() {
        val changes = Git.parseStatus(" M pasta com espaço/arquivo.kt\n")
        assertEquals("pasta com espaço/arquivo.kt", changes[0].path)
    }

    @Test
    fun identidadeFaltandoEReconhecida() {
        assertTrue(Git.identityMissing("Author identity unknown\n\n*** Please tell me who you are.\n"))
        assertTrue(Git.identityMissing("fatal: unable to auto-detect email address"))
        assertFalse(Git.identityMissing("nothing to commit, working tree clean"))
    }
}

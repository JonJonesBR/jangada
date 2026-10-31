package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitTest {

    /** Como o [Git.run] entrega a saída do `-z`: tudo numa linha, com o "\n" recolocado no fim. */
    private fun z(vararg entries: String) = entries.joinToString("") { "$it\u0000" } + "\n"

    @Test
    fun statusLeCodigosDeDoisLetrasECaminho() {
        val changes = Git.parseStatus(z(" M a.kt", "M  b.kt", "?? c.txt", "A  d.kt", " D e.kt"))
        assertEquals(listOf(" M", "M ", "??", "A ", " D"), changes.map { it.code })
        assertEquals(listOf("a.kt", "b.kt", "c.txt", "d.kt", "e.kt"), changes.map { it.path })
        assertTrue(changes.all { it.from == null })
    }

    @Test
    fun renomeacaoUsaOCaminhoNovoEOAntigoVemNaEntradaSeguinte() {
        val changes = Git.parseStatus(z("R  novo.kt", "antigo.kt", " M outro.kt"))
        assertEquals(2, changes.size)
        assertEquals("novo.kt", changes[0].path)
        assertEquals("antigo.kt", changes[0].from)
        assertEquals("R ", changes[0].code)
        assertEquals("outro.kt", changes[1].path)
    }

    @Test
    fun caminhoComSetaEspacoEAspasNaoEConfundido() {
        // Com -z o git não põe aspas nem " -> ": o nome chega como está.
        val changes = Git.parseStatus(z(" M a -> b.kt", "?? pasta com espaço/\"x\" \$y.kt"))
        assertEquals("a -> b.kt", changes[0].path)
        assertEquals("pasta com espaço/\"x\" \$y.kt", changes[1].path)
    }

    @Test
    fun pastaNaoRastreadaTerminaEmBarra() {
        val changes = Git.parseStatus(z("?? node_modules/", "?? a.txt"))
        assertTrue(changes[0].isUntrackedDir)
        assertFalse(changes[1].isUntrackedDir)
    }

    @Test
    fun statusIgnoraVaziosEOFinalDeLinha() {
        assertEquals(1, Git.parseStatus("\u0000\u0000 M a.kt\u0000\n").size)
        assertTrue(Git.parseStatus("").isEmpty())
        assertTrue(Git.parseStatus("\n").isEmpty())
    }

    @Test
    fun renomeacaoCortadaNoFimNaoQuebra() {
        val changes = Git.parseStatus("R  novo.kt\u0000")
        assertEquals(1, changes.size)
        assertNull(changes[0].from)
    }

    @Test
    fun listaSeparadaPorNul() {
        assertEquals(listOf("a/b.txt", "a/c d.txt"), Git.parseNul("a/b.txt\u0000a/c d.txt\u0000\n"))
        assertTrue(Git.parseNul("\n").isEmpty())
    }

    @Test
    fun branchConfiavelOuNull() {
        assertEquals("jangada/x", Git.branchName("jangada/x\n"))
        assertNull(Git.branchName("-x"))
        assertNull(Git.branchName("--upload-pack=y"))
        assertNull(Git.branchName("a b"))
        assertNull(Git.branchName("\n"))
        assertNull(Git.branchName(Git.Result(1, "")))
        assertEquals("main", Git.branchName(Git.Result(0, "main\n")))
    }

    @Test
    fun identidadeFaltandoEReconhecida() {
        assertTrue(Git.identityMissing("Author identity unknown\n\n*** Please tell me who you are.\n"))
        assertTrue(Git.identityMissing("fatal: unable to auto-detect email address"))
        assertFalse(Git.identityMissing("nothing to commit, working tree clean"))
    }
}

package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class FsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun deleteTreeNaoSegueLinksParaFora() {
        val outsideDir = tmp.newFolder("fora-pasta")
        val keepInDir = File(outsideDir, "importante.txt").apply { writeText("não apagar") }
        val outsideFile = File(tmp.root, "fora-arquivo.txt").apply { writeText("também não") }

        val root = tmp.newFolder("rootfs")
        val sub = File(root, "sub").apply { mkdirs() }
        Files.createSymbolicLink(File(sub, "link-pasta").toPath(), outsideDir.toPath())
        Files.createSymbolicLink(File(sub, "link-arquivo").toPath(), outsideFile.toPath())

        Fs.deleteTree(root.toPath())

        assertFalse(root.exists())
        assertTrue(keepInDir.exists())
        assertTrue(outsideFile.exists())
    }

    @Test
    fun deleteTreeDeUmLinkNaoApagaODestino() {
        val target = tmp.newFolder("destino")
        val content = File(target, "a.txt").apply { writeText("a") }
        val link = File(tmp.root, "link").toPath()
        Files.createSymbolicLink(link, target.toPath())

        Fs.deleteTree(link)

        assertFalse(Fs.exists(link))
        assertTrue(content.exists())
    }

    @Test
    fun deleteTreeEsvaziaPastaSemPermissaoDeEscrita() {
        val root = tmp.newFolder("rootfs")
        val readOnly = File(root, "somente-leitura").apply { mkdirs() }
        File(readOnly, "arquivo.txt").writeText("x")
        Fs.setMode(readOnly.toPath(), 0b101_101_101) // 0555

        Fs.deleteTree(root.toPath())

        assertFalse(root.exists())
    }

    @Test
    fun existsEIsDirNaoSeguemLinks() {
        val dir = tmp.newFolder("pasta")
        val broken = File(tmp.root, "quebrado").toPath()
        Files.createSymbolicLink(broken, File(tmp.root, "nao-existe").toPath())
        val linkToDir = File(tmp.root, "link-pasta").toPath()
        Files.createSymbolicLink(linkToDir, dir.toPath())

        // Link quebrado existe como entrada, mas não é pasta.
        assertTrue(Fs.exists(broken))
        assertFalse(Fs.isDir(broken))
        // Link para pasta existe, mas não é pasta (não segue o link).
        assertTrue(Fs.exists(linkToDir))
        assertFalse(Fs.isDir(linkToDir))
        // Pasta de verdade e caminho inexistente.
        assertTrue(Fs.isDir(dir.toPath()))
        assertFalse(Fs.exists(File(tmp.root, "nada").toPath()))
    }
}

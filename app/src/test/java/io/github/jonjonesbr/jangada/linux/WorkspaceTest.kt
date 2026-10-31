package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun nomeValidoTemLetrasNumerosPontoHifenESublinhado() {
        assertTrue(Workspace.isValidName("jangada"))
        assertTrue(Workspace.isValidName("meu-app_2.0"))
    }

    @Test
    fun nomeInvalidoTemOutrosCaracteresOuComecaComPonto() {
        assertFalse(Workspace.isValidName(""))
        assertFalse(Workspace.isValidName(".oculta"))
        assertFalse(Workspace.isValidName(".."))
        assertFalse(Workspace.isValidName("com espaço"))
        assertFalse(Workspace.isValidName("a/b"))
        assertFalse(Workspace.isValidName("ação"))
    }

    @Test
    fun nomeAnexosNaoEValido() {
        assertFalse(Workspace.isValidName("anexos"))
        assertTrue(Workspace.isValidName("anexos2"))
    }

    @Test
    fun nomeTemNoMaximoSessentaEQuatroCaracteres() {
        assertTrue(Workspace.isValidName("a".repeat(64)))
        assertFalse(Workspace.isValidName("a".repeat(65)))
    }

    @Test
    fun chaveDoClaudeTrocaCaracteresQueNaoSaoLetrasOuNumeros() {
        assertEquals("-root-projetos", Workspace.claudeProjectKey("/root/projetos"))
        assertEquals("-root-projetos-meu-app", Workspace.claudeProjectKey("/root/projetos/meu.app"))
    }

    @Test
    fun caminhoDaPastaEhARaizOuUmaSubpasta() {
        assertEquals("/root/projetos", Workspace.path(""))
        assertEquals("/root/projetos/jangada", Workspace.path("jangada"))
    }

    @Test
    fun rotuloDaRaizOuNomeDaPasta() {
        assertEquals("Todos", Workspace.label("", "Todos"))
        assertEquals("jangada", Workspace.label("jangada", "Todos"))
    }

    @Test
    fun branchVemDoHeadComRef() {
        val rootfs = tmp.newFolder("rootfs")
        val repo = repo(rootfs, "jangada")
        File(repo, ".git/HEAD").writeText("ref: refs/heads/main\n")
        assertEquals("main", Workspace.gitBranch(rootfs, repo))
    }

    @Test
    fun branchComBarraNoNomeEInteiro() {
        val rootfs = tmp.newFolder("rootfs")
        val repo = repo(rootfs, "jangada")
        File(repo, ".git/HEAD").writeText("ref: refs/heads/feature/pasta\n")
        assertEquals("feature/pasta", Workspace.gitBranch(rootfs, repo))
    }

    @Test
    fun headSoltoMostraOsSetePrimeirosCaracteresDoHash() {
        val rootfs = tmp.newFolder("rootfs")
        val repo = repo(rootfs, "jangada")
        File(repo, ".git/HEAD").writeText("3f2a9c1d8e7b6a5f4e3d2c1b0a9f8e7d6c5b4a39\n")
        assertEquals("3f2a9c1", Workspace.gitBranch(rootfs, repo))
    }

    @Test
    fun worktreeLeOHeadDoRepositorioDeOrigem() {
        val rootfs = tmp.newFolder("rootfs")
        val main = File(rootfs, "root/projetos/principal/.git/worktrees/tarefa").apply { mkdirs() }
        File(main, "HEAD").writeText("ref: refs/heads/feature/x\n")
        val worktree = File(rootfs, "root/projetos/tarefa").apply { mkdirs() }
        File(worktree, ".git").writeText("gitdir: /root/projetos/principal/.git/worktrees/tarefa\n")
        assertEquals("feature/x", Workspace.gitBranch(rootfs, worktree))
    }

    @Test
    fun worktreeComGitdirQueSobeDePastaOuRelativoNaoELido() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "root/projetos/principal/.git/worktrees/tarefa").apply { mkdirs() }
            .let { File(it, "HEAD").writeText("ref: refs/heads/x\n") }
        val worktree = File(rootfs, "root/projetos/tarefa").apply { mkdirs() }
        File(worktree, ".git").writeText("gitdir: /root/projetos/tarefa/../principal/.git/worktrees/tarefa\n")
        assertNull(Workspace.gitBranch(rootfs, worktree))
        File(worktree, ".git").writeText("gitdir: ../principal/.git/worktrees/tarefa\n")
        assertNull(Workspace.gitBranch(rootfs, worktree))
    }

    @Test
    fun worktreeComLinkNoCaminhoDoGitdirNaoELido() {
        val rootfs = tmp.newFolder("rootfs")
        val outside = File(tmp.newFolder("fora"), "worktrees/tarefa").apply { mkdirs() }
        File(outside, "HEAD").writeText("ref: refs/heads/segredo\n")
        val root = File(rootfs, "root/projetos").apply { mkdirs() }
        // Um link no meio do caminho (como o /root/celular) levaria para fora do Debian.
        Files.createSymbolicLink(File(root, "atalho").toPath(), outside.parentFile.parentFile.toPath())
        val worktree = File(root, "tarefa").apply { mkdirs() }
        File(worktree, ".git").writeText("gitdir: /root/projetos/atalho/worktrees/tarefa\n")
        assertNull(Workspace.gitBranch(rootfs, worktree))
    }

    @Test
    fun pastaGuardadaSoValeSeAindaForPastaDeVerdade() {
        val rootfs = tmp.newFolder("rootfs")
        val root = File(rootfs, "root/projetos").apply { mkdirs() }
        File(root, "jangada").mkdirs()
        Files.createSymbolicLink(File(root, "link").toPath(), tmp.newFolder("fora").toPath())
        assertEquals("jangada", Workspace.existing(rootfs, "jangada"))
        assertEquals("", Workspace.existing(rootfs, "apagada"))
        assertEquals("", Workspace.existing(rootfs, "link"))
        assertEquals("", Workspace.existing(rootfs, "../etc"))
        assertEquals("", Workspace.existing(rootfs, ""))
    }

    @Test
    fun pastaDoCaminho() {
        assertEquals("", Workspace.folderOf("/root/projetos"))
        assertEquals("jangada", Workspace.folderOf("/root/projetos/jangada"))
        assertEquals("", Workspace.folderOf("/root/outra"))
    }

    @Test
    fun pastaSemGitDaNull() {
        val rootfs = tmp.newFolder("rootfs")
        val plain = File(rootfs, "root/projetos/notas").apply { mkdirs() }
        assertNull(Workspace.gitBranch(rootfs, plain))
    }

    @Test
    fun gitQueEhLinkNaoELido() {
        val rootfs = tmp.newFolder("rootfs")
        val outside = tmp.newFolder("fora")
        File(outside, "HEAD").writeText("ref: refs/heads/segredo\n")
        val folder = File(rootfs, "root/projetos/link").apply { mkdirs() }
        Files.createSymbolicLink(File(folder, ".git").toPath(), outside.toPath())
        assertNull(Workspace.gitBranch(rootfs, folder))
    }

    @Test
    fun listaSoPastasDeVerdadeSemOcultasNemAnexos() {
        val rootfs = tmp.newFolder("rootfs")
        val root = File(rootfs, "root/projetos").apply { mkdirs() }
        File(root, "Zeta").mkdirs()
        File(root, "alfa").mkdirs()
        File(root, ".oculta").mkdirs()
        File(root, "anexos").mkdirs()
        File(root, "arquivo.txt").writeText("x")
        val outside = tmp.newFolder("fora")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertEquals(listOf("alfa", "Zeta"), Workspace.listIn(rootfs).map { it.name })
    }

    @Test
    fun repositorioDoWorktreeSaiDaLinhaGitdir() {
        assertEquals("jangada", Workspace.worktreeRepo("gitdir: /root/projetos/jangada/.git/worktrees/jangada--x"))
        assertEquals("jangada", Workspace.worktreeRepo("gitdir: /root/projetos/jangada/.git/worktrees/x\n"))
    }

    @Test
    fun linhaGitdirDeOutroLugarNaoEWorktree() {
        assertNull(Workspace.worktreeRepo("gitdir: /root/outra/.git/worktrees/x"))
        assertNull(Workspace.worktreeRepo("gitdir: ../principal/.git/worktrees/x"))
        assertNull(Workspace.worktreeRepo("gitdir: /root/projetos/a/b/.git/worktrees/x"))
        assertNull(Workspace.worktreeRepo("gitdir: /root/projetos/jangada/.git"))
        assertNull(Workspace.worktreeRepo("gitdir: /root/projetos/jangada/.git/worktrees/"))
        assertNull(Workspace.worktreeRepo("gitdir: /root/projetos/../x/.git/worktrees/y"))
        assertNull(Workspace.worktreeRepo("ref: refs/heads/main"))
    }

    @Test
    fun worktreeOfLeOGitdirDoArquivoEPastaComGitNaoE() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "root/projetos/principal/.git/worktrees/tarefa").mkdirs()
        val worktree = File(rootfs, "root/projetos/tarefa").apply { mkdirs() }
        File(worktree, ".git").writeText("gitdir: /root/projetos/principal/.git/worktrees/tarefa\n")
        assertEquals("principal", Workspace.worktreeOf(worktree))
        val main = repo(rootfs, "principal")
        assertNull(Workspace.worktreeOf(main))
        assertNull(Workspace.worktreeOf(File(rootfs, "root/projetos/sem-git").apply { mkdirs() }))
    }

    @Test
    fun listaMostraORepositorioDeOrigemDoWorktree() {
        val rootfs = tmp.newFolder("rootfs")
        val root = File(rootfs, "root/projetos").apply { mkdirs() }
        repo(rootfs, "principal")
        File(root, "principal/.git/HEAD").writeText("ref: refs/heads/main\n")
        val worktree = File(root, "principal--x").apply { mkdirs() }
        File(worktree, ".git").writeText("gitdir: /root/projetos/principal/.git/worktrees/principal--x\n")
        File(root, "principal/.git/worktrees/principal--x").mkdirs()
        File(root, "principal/.git/worktrees/principal--x/HEAD").writeText("ref: refs/heads/jangada/x\n")
        val folders = Workspace.listIn(rootfs).associateBy { it.name }
        assertEquals(null, folders.getValue("principal").worktreeOf)
        assertEquals("principal", folders.getValue("principal--x").worktreeOf)
        assertEquals("jangada/x", folders.getValue("principal--x").branch)
    }

    private fun repo(rootfs: File, name: String): File =
        File(rootfs, "root/projetos/$name").apply { File(this, ".git").mkdirs() }
}

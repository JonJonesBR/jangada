package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.chat.Attachments
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Pastas de trabalho do chat: cada chat trabalha na raiz (~/projetos, com
 * todos os projetos) ou numa subpasta direta dela (um repositório).
 * Quem lê arquivo daqui chama fora da thread principal.
 */
object Workspace {

    /** Raiz dos projetos dentro do Debian. */
    const val ROOT = "/root/" + LinuxEnv.WORKSPACE

    /** Pasta dos anexos do chat: sempre na raiz, mesmo quando o chat está numa subpasta. */
    const val ATTACHMENTS = ROOT + "/" + Attachments.DIR

    private const val MAX_NAME = 64
    private const val MAX_READ = 4096
    private const val MAX_BRANCH = 100
    private const val GITDIR = "gitdir:"
    /** Entre o repositório e o nome na pasta de um trabalho isolado. */
    private const val WORKTREE_SEP = "--"
    private const val HEAD_REF = "ref: refs/heads/"
    private val NAME = Regex("[A-Za-z0-9._-]+")
    private val NOT_ALNUM = Regex("[^A-Za-z0-9]")
    private val HASH = Regex("[0-9a-f]{40,64}")

    /**
     * Uma subpasta de projetos, o branch do git dela (null = não é repositório ou não deu para ler) e,
     * se for um worktree (trabalho isolado), o nome do repositório de origem.
     */
    class Folder(val name: String, val branch: String?, val worktreeOf: String?)

    /** Caminho absoluto dentro do Debian da pasta [folder] ("" = a raiz). */
    fun path(folder: String): String = if (folder.isEmpty()) ROOT else "$ROOT/$folder"

    /** O contrário de [path]: o nome da subpasta do caminho [cwd] ("" = a raiz ou outro caminho). */
    fun folderOf(cwd: String): String = cwd.removePrefix("$ROOT/").takeIf { it != cwd && isListable(it) }.orEmpty()

    /** Caminho no Android de um caminho do Debian ([guestPath] começa com "/"). */
    fun hostPath(rootfs: File, guestPath: String): File = File(rootfs, guestPath.removePrefix("/"))

    /** Nome de pasta aceito: letras, números, ponto, hífen e sublinhado; não começa com ponto; não é "anexos". */
    fun isValidName(name: String): Boolean =
        name.length in 1..MAX_NAME && !name.startsWith(".") && name != Attachments.DIR && NAME.matches(name)

    /** Maior nome de trabalho isolado para o repositório [repo]: a pasta <repo>--<nome> cabe em 64 caracteres. */
    fun maxWorktreeName(repo: String): Int = (MAX_NAME - repo.length - WORKTREE_SEP.length).coerceAtLeast(0)

    /**
     * Pasta do trabalho isolado [name] do repositório [repo] ("<repo>--<nome>"), se o nome e a
     * pasta são válidos (a pasta precisa passar em [isValidName] para aparecer na lista); senão null.
     */
    fun worktreeFolder(repo: String, name: String): String? {
        if (!isValidName(repo) || !isValidName(name)) return null
        return "$repo$WORKTREE_SEP$name".takeIf { isValidName(it) }
    }

    /** Nome de subpasta que existe e pode aparecer na lista: sem barra, não oculta e não é "anexos". */
    internal fun isListable(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith(".") && name != Attachments.DIR && '/' !in name && '\u0000' !in name

    /**
     * [folder] se ele ainda é uma pasta de verdade dentro da raiz (nem link, nem apagada); senão, a raiz ("").
     * Só confere dois caminhos (sem ler conteúdo): pode rodar na thread principal.
     */
    fun existing(rootfs: File, folder: String): String {
        if (folder.isEmpty()) return ""
        if (!isListable(folder)) return ""
        val root = hostPath(rootfs, ROOT).toPath()
        val dir = hostPath(rootfs, path(folder)).toPath()
        return if (Fs.isDir(root) && Fs.isDir(dir)) folder else ""
    }

    /** Nome da pasta de conversas do Claude Code para [path] (ex.: "/root/projetos" vira "-root-projetos"). */
    fun claudeProjectKey(path: String) = path.replace(NOT_ALNUM, "-")

    /** Nome mostrado: a raiz recebe [rootLabel]; uma subpasta, o próprio nome. */
    fun label(folder: String, rootLabel: String) = if (folder.isEmpty()) rootLabel else folder

    /** Subpastas diretas da raiz (sem ocultas nem anexos), por nome. Lê as pastas: fora da thread principal. */
    fun list(linux: LinuxEnv): List<Folder> = listIn(linux.rootfs)

    internal fun listIn(rootfs: File): List<Folder> {
        val root = hostPath(rootfs, ROOT)
        // A própria raiz não pode ser link: nunca listamos através dele.
        if (!Fs.isDir(root.toPath())) return emptyList()
        val children = root.listFiles() ?: return emptyList()
        return children
            .filter { isListable(it.name) && Fs.isDir(it.toPath()) }
            .sortedBy { it.name.lowercase() }
            .map { Folder(it.name, gitBranch(rootfs, it), worktreeOf(it)) }
    }

    /**
     * Branch do git de [dir] (pasta dentro de [rootfs]), lido dos arquivos sem rodar o git.
     * Em worktree, o .git é um arquivo com "gitdir:" apontando para a pasta do
     * repositório dentro do Debian; o branch vem do HEAD dessa pasta.
     * HEAD solto (hash) vira os 7 primeiros caracteres. Qualquer falha: null.
     */
    fun gitBranch(rootfs: File, dir: File): String? = runCatching {
        val gitDir = gitDirOf(rootfs, dir) ?: return@runCatching null
        headOf(gitDir)
    }.getOrNull()

    /**
     * Repositório de origem de [dir], se ela é um worktree (o .git é arquivo com o gitdir do
     * repositório). Qualquer falha ou pasta que não é worktree: null.
     */
    fun worktreeOf(dir: File): String? = runCatching {
        val dotGit = File(dir, ".git").toPath()
        if (Fs.isDir(dotGit)) null else readSmall(dotGit)?.lineSequence()?.firstOrNull()?.let { worktreeRepo(it.trim()) }
    }.getOrNull()

    /**
     * Nome do repositório pela linha "gitdir: /root/projetos/<repo>/.git/worktrees/<x>"; null
     * se a linha aponta para outro lugar (ou tem caminho relativo ou com "..").
     */
    internal fun worktreeRepo(gitdirLine: String): String? {
        val line = gitdirLine.trim()
        if (!line.startsWith(GITDIR)) return null
        val guest = line.removePrefix(GITDIR).trim()
        val prefix = "$ROOT/"
        if (!guest.startsWith(prefix)) return null
        val parts = guest.removePrefix(prefix).split('/')
        if (parts.size != 4 || parts[1] != ".git" || parts[2] != "worktrees") return null
        if (!isListable(parts[0]) || !isListable(parts[3])) return null
        return parts[0]
    }

    /** Pasta do git de [dir]: a .git (pasta) ou, em worktree, a que o .git (arquivo) aponta. */
    private fun gitDirOf(rootfs: File, dir: File): File? {
        val dotGit = File(dir, ".git").toPath()
        if (Fs.isDir(dotGit)) return dotGit.toFile()
        val line = readSmall(dotGit)?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        if (!line.startsWith(GITDIR)) return null
        val guest = line.removePrefix(GITDIR).trim()
        // Só caminho absoluto dentro do Debian, sem subir de pasta (relativo não é lido).
        val parts = guest.split('/').filter { it.isNotEmpty() && it != "." }
        if (!guest.startsWith("/") || parts.isEmpty() || parts.contains("..")) return null
        // Nenhum pedaço do caminho pode ser link: fora do proot, um link absoluto apontaria para o Android.
        var current = rootfs.toPath()
        for (part in parts) {
            current = current.resolve(part)
            if (!Fs.isDir(current)) return null
        }
        return current.toFile()
    }

    /** Branch (ou hash curto) do HEAD da pasta do git [gitDir]. */
    private fun headOf(gitDir: File): String? {
        val head = readSmall(File(gitDir, "HEAD").toPath())?.lineSequence()?.firstOrNull()?.trim() ?: return null
        if (head.startsWith(HEAD_REF)) return head.removePrefix(HEAD_REF).takeIf { it.isNotEmpty() && it.length <= MAX_BRANCH }
        return if (HASH.matches(head)) head.take(7) else null
    }

    /** Texto de um arquivo comum pequeno, sem seguir links; null se não for arquivo comum ou for grande. */
    private fun readSmall(path: Path): String? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.size(path) > MAX_READ) return null
        return String(Files.readAllBytes(path), Charsets.UTF_8)
    }
}

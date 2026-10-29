package io.github.jonjonesbr.jangada.linux

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * Operações de arquivo que NUNCA seguem links simbólicos.
 * O Linux guardado no app tem links que apontam para caminhos absolutos
 * (/usr, /etc...). Fora do proot esses caminhos são os do próprio Android,
 * então seguir um link por engano poderia apagar ou alterar arquivos errados.
 */
object Fs {
    private val NOFOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)

    fun isDir(path: Path): Boolean = Files.isDirectory(path, *NOFOLLOW)

    fun exists(path: Path): Boolean = Files.exists(path, *NOFOLLOW)

    /** Apaga um arquivo, link ou pasta inteira, sem atravessar links. */
    fun deleteTree(path: Path) {
        if (!exists(path)) return
        if (!isDir(path)) {
            Files.delete(path)
            return
        }
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Garante que dá para listar e apagar o conteúdo, mesmo de pastas 0555.
                runCatching { setMode(dir, 0b111_000_000) }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Aplica permissões no estilo Unix (ex.: 0755). Bits especiais são ignorados. */
    fun setMode(path: Path, mode: Int) {
        val perms = HashSet<PosixFilePermission>()
        if (mode and 0b100_000_000 != 0) perms += PosixFilePermission.OWNER_READ
        if (mode and 0b010_000_000 != 0) perms += PosixFilePermission.OWNER_WRITE
        if (mode and 0b001_000_000 != 0) perms += PosixFilePermission.OWNER_EXECUTE
        if (mode and 0b000_100_000 != 0) perms += PosixFilePermission.GROUP_READ
        if (mode and 0b000_010_000 != 0) perms += PosixFilePermission.GROUP_WRITE
        if (mode and 0b000_001_000 != 0) perms += PosixFilePermission.GROUP_EXECUTE
        if (mode and 0b000_000_100 != 0) perms += PosixFilePermission.OTHERS_READ
        if (mode and 0b000_000_010 != 0) perms += PosixFilePermission.OTHERS_WRITE
        if (mode and 0b000_000_001 != 0) perms += PosixFilePermission.OTHERS_EXECUTE
        Files.setPosixFilePermissions(path, perms)
    }
}

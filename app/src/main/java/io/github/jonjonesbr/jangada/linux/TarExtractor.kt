package io.github.jonjonesbr.jangada.linux

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Leitor de arquivos .tar (formatos ustar, GNU e PAX) que extrai uma imagem
 * de sistema Linux para dentro de [root].
 *
 * - Links simbólicos são recriados como links; links "duros" viram cópias,
 *   porque o Android nem sempre permite hard links na pasta do app.
 * - Arquivos de dispositivo são ignorados (o proot usa os do Android).
 * - Entradas que tentariam escapar de [root] são ignoradas.
 * - Entende os arquivos ".wh." das camadas de imagens Docker/OCI.
 */
class TarExtractor(root: File, private val log: (String) -> Unit = {}) {

    private val rootPath: Path = root.toPath().toAbsolutePath().normalize()
    private val rootCanonical: String = root.canonicalPath
    private val dirModes = ArrayList<Pair<Path, Int>>()
    private val header = ByteArray(BLOCK)

    var entries = 0
        private set

    fun extract(input: InputStream) {
        Files.createDirectories(rootPath)
        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String> = emptyMap()
        val globalPax = HashMap<String, String>()

        while (true) {
            if (!readBlock(input, header)) break
            if (header.all { it.toInt() == 0 }) break // fim do arquivo

            val type = header[156].toInt().toChar()
            var size = parseNumber(header, 124, 12)

            when (type) {
                'x' -> { pax = readMetadata(input, size, type)?.let { parsePax(it) } ?: emptyMap(); continue }
                'g' -> { readMetadata(input, size, type)?.let { globalPax.putAll(parsePax(it)) }; continue }
                'L' -> { longName = readMetadata(input, size, type)?.trimEnd('\u0000'); continue }
                'K' -> { longLink = readMetadata(input, size, type)?.trimEnd('\u0000'); continue }
            }

            var name = cString(header, 0, 100)
            if (cString(header, 257, 5) == "ustar") {
                val prefix = cString(header, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
            }
            name = pax["path"] ?: globalPax["path"] ?: longName ?: name
            val link = pax["linkpath"] ?: longLink ?: cString(header, 157, 100)
            (pax["size"] ?: globalPax["size"])?.toLongOrNull()?.let { size = it }
            val mode = parseNumber(header, 100, 8).toInt()
            longName = null
            longLink = null
            pax = emptyMap()

            if (name.split('/').all { it.isEmpty() || it == "." }) { // a própria raiz ("./")
                skip(input, size)
                continue
            }
            val target = resolve(name)
            if (target == null) {
                log("ignorado (caminho inválido): $name")
                skip(input, size)
                continue
            }
            if (!isInsideRoot(target.parent)) {
                log("ignorado (fora da raiz): $name")
                skip(input, size)
                continue
            }
            entries++

            val fileName = target.fileName?.toString() ?: ""
            if (fileName.startsWith(WHITEOUT)) {
                handleWhiteout(target, fileName)
                skip(input, size)
                continue
            }

            when (type) {
                '5' -> {
                    if (Fs.exists(target) && !Fs.isDir(target)) Fs.deleteTree(target)
                    Files.createDirectories(target)
                    dirModes += target to mode
                    skip(input, size)
                }
                '2' -> {
                    replaceWithFile(target)
                    Files.createSymbolicLink(target, Paths.get(link))
                    skip(input, size)
                }
                '1' -> {
                    val source = resolve(link)
                    // A origem pode passar por um link de pasta que aponta para fora da raiz.
                    if (source != null && !isInsideRoot(source.parent)) {
                        log("ignorado (fora da raiz): $name -> $link")
                        skip(input, size)
                        continue
                    }
                    replaceWithFile(target)
                    when {
                        source == null || !Fs.exists(source) -> log("link sem origem: $name -> $link")
                        Files.isSymbolicLink(source) ->
                            Files.createSymbolicLink(target, Files.readSymbolicLink(source))
                        else -> {
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS)
                            Fs.setMode(target, fileMode(mode))
                        }
                    }
                    skip(input, size)
                }
                '0', '\u0000', '7' -> {
                    replaceWithFile(target)
                    Files.newOutputStream(target).use { out -> copyExactly(input, out, size) }
                    skipPadding(input, size)
                    Fs.setMode(target, fileMode(mode))
                }
                else -> skip(input, size) // dispositivos, FIFOs etc.
            }
        }

        // Permissões das pastas por último: uma pasta 0555 aplicada antes
        // impediria a criação do que vem dentro dela.
        for ((dir, mode) in dirModes.asReversed()) {
            runCatching { Fs.setMode(dir, (mode and 0x1FF) or 0b111_000_000) }
        }
    }

    private fun fileMode(mode: Int) = (mode and 0x1FF) or 0b110_000_000

    private fun handleWhiteout(target: Path, fileName: String) {
        val dir = target.parent
        if (fileName == OPAQUE_WHITEOUT) {
            if (Fs.isDir(dir)) Files.list(dir).use { s -> s.forEach { Fs.deleteTree(it) } }
        } else {
            Fs.deleteTree(dir.resolve(fileName.removePrefix(WHITEOUT)))
        }
    }

    /** Remove o que houver no caminho (exceto pastas, que podem ter conteúdo útil). */
    private fun replaceWithFile(target: Path) {
        Files.createDirectories(target.parent)
        if (Fs.exists(target)) Fs.deleteTree(target)
    }

    /** Converte um nome do tar em caminho dentro da raiz, ou null se for suspeito. */
    private fun resolve(name: String): Path? {
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.fold(rootPath) { acc, part -> acc.resolve(part) }
    }

    private fun isInsideRoot(dir: Path?): Boolean {
        if (dir == null) return false
        // Resolve links já existentes no caminho antes de criar qualquer pasta.
        val canonical = dir.toFile().canonicalPath
        return canonical == rootCanonical || canonical.startsWith(rootCanonical + File.separator)
    }

    private fun readBlock(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("tar truncado")
            }
            off += n
        }
        return true
    }

    private fun readString(input: InputStream, size: Long): String {
        val out = java.io.ByteArrayOutputStream()
        copyExactly(input, out, size)
        skipPadding(input, size)
        return out.toString("UTF-8")
    }

    /** Lê um registro de metadados (x, g, L, K); os maiores que o teto são pulados. */
    private fun readMetadata(input: InputStream, size: Long, type: Char): String? {
        if (size > MAX_METADATA) {
            log("registro '$type' grande demais ignorado ($size bytes)")
            skip(input, size)
            return null
        }
        return readString(input, size)
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("tar truncado")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, size: Long) {
        copyExactly(input, NullOutput, size)
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val rest = ((BLOCK - (size % BLOCK)) % BLOCK).toInt()
        if (rest > 0) copyExactly(input, NullOutput, rest.toLong())
    }

    private object NullOutput : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    companion object {
        private const val BLOCK = 512
        /** Teto para registros de metadados (PAX e GNU L/K), que ficam inteiros na memória. */
        private const val MAX_METADATA = 64L * 1024
        private const val WHITEOUT = ".wh."
        private const val OPAQUE_WHITEOUT = ".wh..wh..opq"

        internal fun cString(buf: ByteArray, off: Int, len: Int): String {
            var end = off
            while (end < off + len && buf[end].toInt() != 0) end++
            return String(buf, off, end - off, Charsets.UTF_8)
        }

        /** Número octal em texto, ou binário (base 256) quando o primeiro bit está ligado. */
        internal fun parseNumber(buf: ByteArray, off: Int, len: Int): Long {
            if (buf[off].toInt() and 0x80 != 0) {
                var value = (buf[off].toLong() and 0x7F)
                for (i in off + 1 until off + len) value = (value shl 8) or (buf[i].toLong() and 0xFF)
                return value
            }
            val text = cString(buf, off, len).trim()
            return if (text.isEmpty()) 0 else text.toLong(8)
        }

        /** Registros PAX: "<tamanho> <chave>=<valor>\n" repetidos. */
        internal fun parsePax(data: String): Map<String, String> {
            val bytes = data.toByteArray(Charsets.UTF_8)
            val result = HashMap<String, String>()
            var pos = 0
            while (pos < bytes.size) {
                var space = pos
                while (space < bytes.size && bytes[space] != ' '.code.toByte()) space++
                if (space >= bytes.size) break
                val length = String(bytes, pos, space - pos).toIntOrNull() ?: break
                if (length <= 0 || pos + length > bytes.size) break
                val record = String(bytes, space + 1, pos + length - space - 2, Charsets.UTF_8)
                val eq = record.indexOf('=')
                if (eq > 0) result[record.substring(0, eq)] = record.substring(eq + 1)
                pos += length
            }
            return result
        }
    }
}

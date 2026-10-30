package io.github.jonjonesbr.jangada.linux

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class TarExtractorTest {

    private lateinit var work: File
    private lateinit var root: File

    @Before
    fun setUp() {
        work = Files.createTempDirectory("jangada-tar").toFile()
        root = File(work, "rootfs")
    }

    @After
    fun tearDown() {
        Fs.deleteTree(work.toPath())
    }

    @Test
    fun extraiPastasArquivosELinks() {
        val tar = Tar()
            .dir("etc/")
            .file("etc/hostname", "debian\n")
            .file("usr/bin/tool", "#!/bin/sh\n", mode = "755".toInt(8))
            .symlink("usr/bin/alias", "tool")
            .hardlink("usr/bin/copy", "usr/bin/tool")
        TarExtractor(root).extract(tar.bytes())

        assertEquals("debian\n", File(root, "etc/hostname").readText())
        assertTrue(File(root, "usr/bin/tool").canExecute())
        val alias = File(root, "usr/bin/alias").toPath()
        assertTrue(Files.isSymbolicLink(alias))
        assertEquals("tool", Files.readSymbolicLink(alias).toString())
        assertEquals("#!/bin/sh\n", File(root, "usr/bin/copy").readText())
        assertFalse(Files.isSymbolicLink(File(root, "usr/bin/copy").toPath()))
    }

    @Test
    fun ignoraCaminhoComPontoPonto() {
        val tar = Tar().file("../fora.txt", "x").file("a/../../fora2.txt", "x").file("dentro.txt", "ok")
        TarExtractor(root).extract(tar.bytes())

        assertFalse(File(work, "fora.txt").exists())
        assertFalse(File(work, "fora2.txt").exists())
        assertEquals("ok", File(root, "dentro.txt").readText())
    }

    @Test
    fun naoEscreveAtravesDeLinkQueSaiDaRaiz() {
        val outside = File(work, "fora").apply { mkdirs() }
        val tar = Tar()
            .symlink("escape", outside.absolutePath)
            .file("escape/pwned.txt", "x")
        TarExtractor(root).extract(tar.bytes())

        assertFalse(File(outside, "pwned.txt").exists())
    }

    @Test
    fun hardlinkCujaOrigemPassaPorLinkParaForaNaoCopia() {
        val outside = File(work, "fora").apply { mkdirs() }
        File(outside, "segredo.txt").writeText("segredo")
        val tar = Tar()
            .symlink("pasta", outside.absolutePath)
            .hardlink("copia.txt", "pasta/segredo.txt")
        TarExtractor(root).extract(tar.bytes())

        assertFalse(File(root, "copia.txt").exists())
    }

    @Test
    fun arquivosWhiteoutApagamDaCamadaAnterior() {
        TarExtractor(root).extract(Tar().file("dir/a.txt", "a").file("dir/b.txt", "b").bytes())
        TarExtractor(root).extract(Tar().file("dir/.wh.a.txt", "").bytes())

        assertFalse(File(root, "dir/a.txt").exists())
        assertTrue(File(root, "dir/b.txt").exists())
        assertFalse(File(root, "dir/.wh.a.txt").exists())
    }

    @Test
    fun whiteoutOpacoEsvaziaAPasta() {
        TarExtractor(root).extract(Tar().file("dir/a.txt", "a").file("dir/b.txt", "b").bytes())
        TarExtractor(root).extract(Tar().file("dir/.wh..wh..opq", "").file("dir/c.txt", "c").bytes())

        assertEquals(listOf("c.txt"), File(root, "dir").list()!!.sorted())
    }

    @Test
    fun nomeLongoPorPax() {
        val long = "pasta/" + "n".repeat(150) + ".txt"
        TarExtractor(root).extract(Tar().pax(mapOf("path" to long)).file("curto.txt", "pax").bytes())

        assertEquals("pax", File(root, long).readText())
        assertFalse(File(root, "curto.txt").exists())
    }

    @Test
    fun nomeLongoPorRegistroGnuL() {
        val long = "pasta/" + "g".repeat(150) + ".txt"
        // Cabeçalho aceita só 100 caracteres; o nome completo vem no registro 'L'.
        TarExtractor(root).extract(Tar().gnuRecord('L', long).file(long.take(100), "gnu").bytes())

        assertEquals("gnu", File(root, long).readText())
        assertFalse(File(root, long.take(100)).exists())
    }

    @Test
    fun registroGnuLMaiorQue64KbEhPuladoSemTravar() {
        val huge = "h".repeat(70 * 1024)
        val logs = mutableListOf<String>()
        // O registro 'L' gigante é pulado; a entrada seguinte usa o nome do próprio cabeçalho.
        val tar = Tar().gnuRecord('L', huge).file("curto.txt", "ok")
        TarExtractor(root) { logs += it }.extract(tar.bytes())

        assertEquals("ok", File(root, "curto.txt").readText())
        assertTrue(logs.any { it.contains("grande demais") })
    }

    @Test
    fun linkSimbolicoComDestinoLongoPorRegistroGnuK() {
        val target = "lib/" + "v".repeat(130) + ".so"
        val tar = Tar()
            .gnuRecord('K', target)
            .entry("link.so", '2', ByteArray(0), link = target.take(100))
        TarExtractor(root).extract(tar.bytes())

        val link = File(root, "link.so").toPath()
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(target, Files.readSymbolicLink(link).toString())
    }

    @Test
    fun numerosOctalEBase256() {
        val octal = "0000644\u0000".toByteArray()
        assertEquals(420L, TarExtractor.parseNumber(octal, 0, 8))

        val binary = ByteArray(12)
        binary[0] = 0x80.toByte()
        binary[10] = 0x01
        binary[11] = 0x00
        assertEquals(256L, TarExtractor.parseNumber(binary, 0, 12))
    }

    @Test
    fun registrosPax() {
        val data = record("path=a/b.txt") + record("linkpath=c") + record("size=5")
        assertEquals(mapOf("path" to "a/b.txt", "linkpath" to "c", "size" to "5"), TarExtractor.parsePax(data))
    }

    private fun record(body: String): String {
        // "<tamanho> <chave>=<valor>\n", onde o tamanho conta a si mesmo.
        var length = body.length + 3
        while ("$length $body\n".toByteArray().size != length) length++
        return "$length $body\n"
    }

    /** Monta um .tar (ustar) na memória. */
    private class Tar {
        private val out = ByteArrayOutputStream()

        fun dir(name: String) = entry(name, '5', ByteArray(0), mode = "755".toInt(8))
        fun file(name: String, text: String, mode: Int = "644".toInt(8)) = entry(name, '0', text.toByteArray(), mode = mode)
        fun symlink(name: String, target: String) = entry(name, '2', ByteArray(0), link = target)
        fun hardlink(name: String, target: String) = entry(name, '1', ByteArray(0), link = target)

        /** Registro GNU ('L' = nome longo, 'K' = destino de link longo) antes da entrada real. */
        fun gnuRecord(type: Char, text: String) = entry("././@LongLink", type, (text + "\u0000").toByteArray())

        fun pax(records: Map<String, String>): Tar {
            val text = records.entries.joinToString("") { (k, v) ->
                val body = "$k=$v"
                var length = body.length + 3
                while ("$length $body\n".toByteArray().size != length) length++
                "$length $body\n"
            }
            return entry("PaxHeader", 'x', text.toByteArray())
        }

        fun entry(name: String, type: Char, data: ByteArray, link: String = "", mode: Int = "644".toInt(8)): Tar {
            val header = ByteArray(512)
            put(header, 0, name, 100)
            put(header, 100, "%07o".format(mode), 8)
            put(header, 108, "%07o".format(0), 8)
            put(header, 116, "%07o".format(0), 8)
            put(header, 124, "%011o".format(data.size), 12)
            put(header, 136, "%011o".format(0), 12)
            header[156] = type.code.toByte()
            put(header, 157, link, 100)
            put(header, 257, "ustar", 6)
            put(header, 263, "00", 2)
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            val sum = header.sumOf { it.toInt() and 0xFF }
            put(header, 148, "%06o".format(sum), 7)
            out.write(header)
            out.write(data)
            val pad = (512 - data.size % 512) % 512
            out.write(ByteArray(pad))
            return this
        }

        fun bytes(): ByteArrayInputStream {
            val copy = ByteArrayOutputStream()
            out.writeTo(copy)
            copy.write(ByteArray(1024))
            return ByteArrayInputStream(copy.toByteArray())
        }

        private fun put(buf: ByteArray, offset: Int, text: String, size: Int) {
            val bytes = text.toByteArray()
            require(bytes.size <= size) { "campo grande demais: $text" }
            bytes.copyInto(buf, offset)
        }
    }
}

package io.github.jonjonesbr.jangada.linux

import android.os.FileObserver
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Recebe os links que os programas do Debian pedem para abrir.
 * O comando `jangada-open` (também instalado como xdg-open e BROWSER)
 * grava o link num arquivo nesta pasta; aqui o app lê e abre no Android.
 * Assim o link chega inteiro, sem depender do que aparece na tela.
 */
@Suppress("DEPRECATION") // FileObserver(String, Int) é o construtor que existe desde o Android 8.
class LinkWatcher(
    private val dir: File,
    private val onUrl: (String) -> Unit,
) : FileObserver(dir.absolutePath, CLOSE_WRITE or MOVED_TO) {

    override fun onEvent(event: Int, path: String?) {
        if (path == null || path.startsWith(".")) return // arquivos temporários
        val entry = File(dir, path).toPath()
        val url = try {
            readLink(entry, path)
        } catch (e: IOException) {
            // Entrada sumiu ou não pode ser lida: segue sem derrubar o app.
            Log.w(TAG, "não consegui ler o link $path", e)
            null
        } finally {
            deleteEntry(entry, path)
        }
        if (url != null) onUrl(url)
    }

    /** Apaga a entrada sempre, sem seguir links. Falha ao apagar não derruba o app. */
    private fun deleteEntry(entry: Path, name: String) {
        try {
            Fs.deleteTree(entry)
        } catch (e: IOException) {
            Log.w(TAG, "não consegui apagar a entrada $name", e)
        }
    }

    /**
     * Devolve o link da entrada, ou null se ela não for um arquivo comum pequeno
     * com um endereço http:// ou https://. Nunca segue links simbólicos.
     */
    private fun readLink(entry: Path, name: String): String? {
        val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attrs.isRegularFile || attrs.size() > MAX_BYTES) {
            Log.w(TAG, "entrada ignorada (não é arquivo comum ou é grande demais): $name")
            return null
        }
        // Lê com limite: o arquivo pode ter crescido depois da conferência acima.
        val text = Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val n = input.read(buffer, total, buffer.size - total)
                if (n < 0) break
                total += n
            }
            if (total > MAX_BYTES) null else String(buffer, 0, total, Charsets.UTF_8).trim()
        }
        if (text == null) {
            Log.w(TAG, "entrada ignorada (maior que $MAX_BYTES bytes): $name")
            return null
        }
        if (!text.startsWith("https://") && !text.startsWith("http://")) {
            Log.w(TAG, "entrada ignorada (não é um link http ou https): $name")
            return null
        }
        return text
    }

    private companion object {
        const val TAG = "LinkWatcher"
        /** Um link cabe folgado em 4 KB; arquivos maiores não são links. */
        const val MAX_BYTES = 4096
    }
}

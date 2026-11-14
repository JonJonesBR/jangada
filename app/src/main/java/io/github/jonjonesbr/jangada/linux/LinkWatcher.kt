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
        if (!attrs.isRegularFile || attrs.size() > LINK_MAX_BYTES) {
            Log.w(TAG, "entrada ignorada (não é arquivo comum ou é grande demais): $name")
            return null
        }
        // Lê com limite: o arquivo pode ter crescido depois da conferência acima.
        val buffer = ByteArray(LINK_MAX_BYTES + 1)
        val total = Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS).use { input ->
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            read
        }
        return when (val result = interpretLinkBytes(buffer, total)) {
            is LinkEntry.Link -> result.url
            LinkEntry.TooLarge -> {
                Log.w(TAG, "entrada ignorada (maior que $LINK_MAX_BYTES bytes): $name")
                null
            }
            LinkEntry.NotHttp -> {
                Log.w(TAG, "entrada ignorada (não é um link http ou https): $name")
                null
            }
        }
    }

    private companion object {
        const val TAG = "LinkWatcher"
    }
}

/** Um link cabe folgado em 4 KB; arquivos maiores não são links. */
internal const val LINK_MAX_BYTES = 4096

/** O que dá para tirar de uma entrada lida: o link, ou o motivo de ignorar. */
internal sealed class LinkEntry {
    data class Link(val url: String) : LinkEntry()
    object TooLarge : LinkEntry()
    object NotHttp : LinkEntry()
}

/**
 * Interpreta os bytes lidos da entrada (só os [total] primeiros valem). Acima do limite, não é link;
 * senão, o texto sem espaços nas pontas tem de começar com http:// ou https://.
 */
internal fun interpretLinkBytes(bytes: ByteArray, total: Int): LinkEntry {
    if (total > LINK_MAX_BYTES) return LinkEntry.TooLarge
    val text = String(bytes, 0, total, Charsets.UTF_8).trim()
    return if (text.startsWith("https://") || text.startsWith("http://")) LinkEntry.Link(text) else LinkEntry.NotHttp
}

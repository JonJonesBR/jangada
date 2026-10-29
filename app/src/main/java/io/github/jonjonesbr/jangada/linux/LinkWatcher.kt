package io.github.jonjonesbr.jangada.linux

import android.os.FileObserver
import java.io.File

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
        val file = File(dir, path)
        val url = runCatching { file.readText().trim() }.getOrNull()
        file.delete()
        if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) onUrl(url)
    }
}

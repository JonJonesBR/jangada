package io.github.jonjonesbr.jangada.linux

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import io.github.jonjonesbr.jangada.R
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Instalação do Linux em três passos, rodando em segundo plano.
 * A tela só observa o [state]; girar o celular ou trocar de tela não
 * interrompe nada. Cada passo concluído fica marcado, então "Tentar de
 * novo" continua de onde parou.
 */
object Setup {

    enum class Step { DOWNLOAD, EXTRACT, PACKAGES }

    sealed interface State {
        data object Idle : State
        /** [progress] de 0 a 1, ou null quando não dá para medir. */
        data class Running(val step: Step, val progress: Float?, val detail: String) : State
        data class Failed(val step: Step, val messageRes: Int) : State
        data object Done : State
    }

    @Volatile
    var state: State = State.Idle
        private set

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    /** Volta ao começo (antes de reparar ou reinstalar o Linux). */
    fun reset() {
        if (state !is State.Running) state = State.Idle
    }

    fun start(context: Context) {
        if (state is State.Running) return
        val app = context.applicationContext
        publish(State.Running(Step.DOWNLOAD, null, ""))
        thread(name = "jangada-setup") { runSafely(app) }
    }

    private fun runSafely(context: Context) {
        val env = LinuxEnv(context)
        var step = Step.DOWNLOAD
        try {
            env.installBootstrap()
            if (!env.isRootfsReady) {
                checkFreeSpace(context.filesDir)
                step = Step.DOWNLOAD
                val archives = download(context, env)
                step = Step.EXTRACT
                extract(env, archives)
                archives.forEach { it.delete() }
            }
            step = Step.PACKAGES
            installBasePackages(env)
            publish(State.Done)
        } catch (e: Throwable) {
            log("ERRO: ${e.javaClass.simpleName}: ${e.message}")
            publish(State.Failed(step, friendlyMessage(e, step)))
        }
    }

    private fun download(context: Context, env: LinuxEnv): List<File> {
        log("Imagem: ${LinuxEnv.IMAGE}@${LinuxEnv.IMAGE_DIGEST} (${LinuxEnv.ARCHITECTURE})")
        val hub = DockerHub(LinuxEnv.IMAGE_REPOSITORY)
        val layers = hub.layers(LinuxEnv.IMAGE_DIGEST, LinuxEnv.ARCHITECTURE)
        layers.firstOrNull { !it.mediaType.contains("gzip") }?.let {
            throw IOException("formato de camada não suportado: ${it.mediaType}")
        }
        val total = layers.sumOf { it.size }
        log("Camadas: ${layers.size}, ${mb(total)} MB")
        var before = 0L
        var lastPercent = -1
        return layers.mapIndexed { index, layer ->
            val file = File(context.cacheDir, "layer-$index.tar.gz")
            log("Baixando ${layer.digest}")
            hub.download(layer, file) { done, _ ->
                val percent = ((before + done) * 100 / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    publish(State.Running(Step.DOWNLOAD, percent / 100f, "${mb(before + done)} / ${mb(total)} MB"))
                }
            }
            before += layer.size
            file
        }
    }

    private fun extract(env: LinuxEnv, archives: List<File>) {
        env.clearMarkers()
        if (env.rootfs.exists()) {
            log("Apagando instalação incompleta")
            Fs.deleteTree(env.rootfs.toPath())
        }
        val total = archives.sumOf { it.length() }.coerceAtLeast(1)
        var before = 0L
        var lastPercent = -1
        for (archive in archives) {
            val counting = CountingInputStream(archive.inputStream().buffered(BUFFER)) { read ->
                val percent = ((before + read) * 100 / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    publish(State.Running(Step.EXTRACT, percent / 100f, "$percent%"))
                }
            }
            val extractor = TarExtractor(env.rootfs) { log(it) }
            GZIPInputStream(counting, BUFFER).use { extractor.extract(it) }
            log("Extraídos ${extractor.entries} itens de ${archive.name}")
            before += archive.length()
        }
        env.applyFixups()
        env.markRootfsReady()
    }

    private fun installBasePackages(env: LinuxEnv) {
        if (env.isReady) return
        publish(State.Running(Step.PACKAGES, null, ""))
        val script = "set -e; apt-get update; " +
            "apt-get install -y --no-install-recommends ${LinuxEnv.BASE_PACKAGES}; apt-get clean"
        val exit = env.run(script) { line ->
            log(line)
            publish(State.Running(Step.PACKAGES, null, line.take(120)))
        }
        if (exit != 0) throw IOException("apt terminou com código $exit")
        env.markBaseReady()
    }

    private fun checkFreeSpace(dir: File) {
        val free = StatFs(dir.path).availableBytes
        log("Espaço livre: ${mb(free)} MB")
        if (free < REQUIRED_BYTES) throw NotEnoughSpace()
    }

    private fun friendlyMessage(e: Throwable, step: Step): Int = when {
        e is NotEnoughSpace -> R.string.setup_error_space
        e is DockerHub.HttpError && e.code == 429 -> R.string.setup_error_rate_limit
        e is UnknownHostException -> R.string.setup_error_offline
        step == Step.PACKAGES -> R.string.setup_error_packages
        e is IOException && step == Step.DOWNLOAD -> R.string.setup_error_download
        else -> R.string.setup_error_generic
    }

    private fun log(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        }
    }

    private fun publish(newState: State) {
        state = newState
        main.post { listeners.forEach { it(newState) } }
    }

    private fun mb(bytes: Long) = "%.1f".format(java.util.Locale.getDefault(), bytes / 1_048_576.0)

    private class NotEnoughSpace : IOException("pouco espaço livre")

    private class CountingInputStream(
        input: InputStream,
        private val onRead: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) onRead(++count)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                count += n
                onRead(count)
            }
            return n
        }
    }

    private const val BUFFER = 64 * 1024
    private const val MAX_LOG_LINES = 400
    private const val REQUIRED_BYTES = 700L * 1024 * 1024
}

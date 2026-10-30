package io.github.jonjonesbr.jangada.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * O que os dois chats ([ChatEngine] e [AcpChat]) têm em comum: um agente
 * rodando no Linux, com quem se conversa por JSON, uma linha por mensagem.
 *
 * - [launch] prepara e inicia o agente numa thread própria (nada de arquivo
 *   na thread principal). O que for enviado antes de ele subir espera na fila.
 * - Cada execução do agente tem sua [Connection]: fila de escrita, processo
 *   e um aviso de "fechada". Assim, um agente antigo nunca recebe nem
 *   entrega mensagens de uma conversa nova.
 */
abstract class ProcessChat(protected val linux: LinuxEnv, private val tag: String) : AgentChat {

    protected val context: Context = linux.context

    override val items = mutableListOf<ChatItem>()

    /**
     * Turno que não deve avisar ao acabar: parado pelo usuário (stop/interrupt)
     * ou com o agente já fechado. Zera sozinho quando um turno novo começa.
     * Só a thread principal mexe.
     */
    protected var silentTurn = false

    /** Turno em andamento: vira false também ao acabar sozinho (aí avisa, na thread principal). */
    @Volatile override var busy = false
        protected set(value) {
            val ended = field && !value
            field = value
            if (value) silentTurn = false
            if (ended && !silentTurn) main.post { turnEnded() }
        }
    @Volatile override var running = false
        protected set
    @Volatile override var interrupted = false
        protected set

    /** Chamado (na thread principal) sempre que a conversa muda. */
    override var onChanged: (() -> Unit)? = null

    /** Avisos para o usuário (na thread principal); ver [ChatAttention]. */
    override var onAttention: ((ChatAttention) -> Unit)? = null

    protected val main = Handler(Looper.getMainLooper())

    private class Connection {
        /** Uma fila só: o início do agente e depois cada mensagem, em ordem. */
        val writer: ExecutorService = Executors.newSingleThreadExecutor()
        @Volatile var process: Process? = null
        var closed = false
    }

    /** Execução atual do agente (só a thread principal mexe). */
    private var connection: Connection? = null

    /** Últimas linhas de erro do agente, mostradas se ele fechar sozinho. */
    private val stderrTail = ArrayDeque<String>()
    private var lastPost = 0L

    /**
     * Inicia o agente. [prepare] roda fora da thread principal, antes do
     * processo, e devolve o script que o inicia (dentro do Linux).
     */
    protected fun launch(interactive: Boolean, prepare: () -> String) {
        val c = Connection()
        connection = c
        running = true
        synchronized(stderrTail) { stderrTail.clear() }
        changed()
        c.writer.execute {
            val proc = try {
                val script = prepare()
                linux.startProcess(script, interactive = interactive)
            } catch (e: Exception) {
                Log.w(tag, "falha ao iniciar", e)
                main.post {
                    if (connection !== c) return@post
                    finish(c)
                    settleItems()
                    onStopped()
                    add(ChatItem.Info(context.getString(R.string.chat_error_start, e.message.orEmpty()), isError = true))
                    attention(ChatAttention.FAILED)
                }
                return@execute
            }
            val stillOpen = synchronized(c) {
                if (!c.closed) c.process = proc
                !c.closed
            }
            if (!stillOpen) {
                // Fecharam o chat enquanto o agente subia.
                runCatching { proc.outputStream.close() }
                proc.destroy()
                return@execute
            }
            thread(name = "$tag-stdout") { readOutput(c, proc) }
            thread(name = "$tag-stderr") {
                // Ao fechar o chat, a leitura é interrompida de propósito: não é erro.
                runCatching {
                    // A saída do agente não vai para o logcat: fica só em stderrTail (Detalhes).
                    proc.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach {
                            synchronized(stderrTail) {
                                stderrTail.addLast(it)
                                while (stderrTail.size > STDERR_LINES) stderrTail.removeFirst()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Encerra o agente. [then] roda (na thread principal) depois que o
     * processo terminou de fato — importante antes de abrir a mesma
     * conversa no terminal, para os dois não gravarem ao mesmo tempo.
     */
    override fun stop(then: (() -> Unit)?) {
        val c = connection
        connection = null
        running = false
        // Parado pelo usuário: o turno interrompido não avisa que terminou.
        silentTurn = true
        busy = false
        settleItems()
        onStopped()
        changed()
        if (c == null) {
            then?.let { main.post(it) }
            return
        }
        val proc = synchronized(c) {
            c.closed = true
            c.process
        }
        c.writer.shutdown()
        thread(name = "$tag-stop") {
            if (proc != null) runCatching {
                // Fechar a entrada já faz o agente sair; se demorar, força.
                proc.outputStream.close()
                if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                    proc.destroy()
                    proc.waitFor(2, TimeUnit.SECONDS)
                }
            }
            then?.let { main.post(it) }
        }
    }

    /**
     * Escreve uma linha JSON no agente (entra na fila, atrás do início).
     * Devolve false quando não há execução do agente para receber a linha.
     */
    protected fun send(json: JSONObject): Boolean {
        val c = connection ?: return false
        return runCatching {
            c.writer.execute {
                val out = c.process?.outputStream ?: return@execute
                runCatching {
                    out.write((json.toString() + "\n").toByteArray())
                    out.flush()
                }.onFailure { Log.w(tag, "falha ao escrever", it) }
            }
        }.isSuccess
    }

    /** Uma mensagem do agente, já como JSON. Roda na thread principal. */
    protected abstract fun handle(json: JSONObject)

    /** Limpezas próprias de cada chat quando o agente para, por qualquer motivo (thread principal). */
    protected open fun onStopped() {}

    /**
     * O agente fechou sozinho (thread principal, antes de [onStopped]).
     * [stderr] traz as últimas linhas de erro dele. O padrão é mostrar o
     * motivo quando não foi normal.
     */
    protected open fun onExited(code: Int, stderr: String) {
        if (code != 0) add(ChatItem.Info(exitMessage(code), isError = true, details = detailsOf(stderr)))
    }

    private fun readOutput(c: Connection, proc: Process) {
        try {
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    // Linha que não é JSON é ignorada (não vai para o logcat).
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    main.post { if (connection === c) handle(json) }
                }
            }
        } catch (e: java.io.IOException) {
            // Leitura interrompida porque o chat foi fechado: normal.
        } finally {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            main.post {
                if (connection !== c) return@post
                finish(c)
                onExited(code, synchronized(stderrTail) { stderrTail.joinToString("\n") })
                // Saiu com erro sem ser parado pelo usuário: um aviso só (se o cartão de login já avisou, não repete).
                if (code != 0 && items.lastOrNull() !is ChatItem.Login) attention(ChatAttention.FAILED)
                settleItems()
                onStopped()
                changed()
            }
        }
    }

    /** A execução [c] acabou: solta a fila e volta ao estado parado. */
    private fun finish(c: Connection) {
        connection = null
        c.writer.shutdown()
        running = false
        // O agente saiu: o fim do turno não avisa aqui (quem avisa é a falha, em readOutput).
        silentTurn = true
        busy = false
    }

    /** O turno acabou sozinho: o aviso sai depois da mensagem atual (um erro do turno pode vir logo em seguida). */
    private fun turnEnded() {
        if (!running || busy) return
        val last = items.lastOrNull()
        // Cartão de login já avisou: o agente espera o usuário, não terminou.
        if (last is ChatItem.Login) return
        attention(if (last is ChatItem.Info && last.isError) ChatAttention.FAILED else ChatAttention.FINISHED)
    }

    /** Avisa o usuário (na thread principal). */
    private fun attention(kind: ChatAttention) {
        onAttention?.invoke(kind)
    }

    /**
     * Quando o agente para, nada fica pendente: pedidos de permissão sem
     * resposta viram cancelados e nenhuma resposta fica com o cursor piscando.
     * Roda na thread principal, antes de [onStopped].
     */
    private fun settleItems() {
        for (item in items) {
            if (item is ChatItem.Permission && item.chosen == null) {
                item.chosen = PermissionOption("", "", PermissionOption.Kind.REJECT_ONCE)
            } else if (item is ChatItem.Assistant) {
                item.streaming = false
            }
        }
    }

    /** O que houve com o código de saída e o que fazer agora. O erro cru fica em [detailsOf]. */
    protected fun exitMessage(code: Int): String {
        val reason = when (code) {
            0 -> context.getString(R.string.chat_exit_normal)
            127 -> context.getString(R.string.chat_exit_not_found)
            126 -> context.getString(R.string.chat_exit_no_permission)
            else -> context.getString(R.string.chat_exit_code, code)
        }
        return if (code == 0) reason else reason + "\n" + context.getString(R.string.chat_error_hint)
    }

    /** Últimas linhas de erro do agente, para o link "Detalhes" (null se não houver nenhuma). */
    protected fun detailsOf(stderr: String): String? = stderr.trim().ifEmpty { null }

    protected fun add(item: ChatItem) {
        items += item
        changed()
        // Pedido de permissão e cartão de login: o usuário precisa responder.
        when (item) {
            is ChatItem.Permission -> attention(ChatAttention.PERMISSION)
            is ChatItem.Login -> attention(ChatAttention.LOGIN)
            else -> Unit
        }
    }

    protected fun changed(throttle: Boolean = false) {
        val now = System.currentTimeMillis()
        if (throttle && now - lastPost < THROTTLE_MS) {
            main.removeCallbacks(notifyRunnable)
            main.postDelayed(notifyRunnable, THROTTLE_MS)
            return
        }
        lastPost = now
        onChanged?.invoke()
    }

    private val notifyRunnable = Runnable {
        lastPost = System.currentTimeMillis()
        onChanged?.invoke()
    }

    private companion object {
        const val THROTTLE_MS = 150L
        const val STDERR_LINES = 12
    }
}

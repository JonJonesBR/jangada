package io.github.jonjonesbr.jangada.chat

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import kotlin.concurrent.thread

/** Um item da conversa mostrada no chat. */
sealed class ChatItem {
    class User(val text: String) : ChatItem()
    class Assistant(var text: String, var streaming: Boolean) : ChatItem()
    class Tool(val id: String, val name: String, val summary: String) : ChatItem() {
        var done = false
        var failed = false
    }
    class Permission(val requestId: String, val toolName: String, val summary: String, val input: JSONObject) : ChatItem() {
        /** null = esperando resposta; true = permitido; false = recusado. */
        var allowed: Boolean? = null
    }
    class Info(val text: String, val isError: Boolean = false) : ChatItem()
}

/**
 * Conversa com o Claude Code sem a tela de terminal: roda o `claude` no
 * modo de integração (JSON por linha, o mesmo protocolo do Claude Agent
 * SDK) e transforma as mensagens em [ChatItem]s.
 *
 * - Pedidos de permissão chegam como "can_use_tool" e viram cartões com
 *   Permitir/Recusar.
 * - A conversa fica salva pelo próprio Claude Code; [sessionId] permite
 *   continuar a mesma conversa na tela de terminal (e vice-versa).
 */
class ChatEngine(
    private val linux: LinuxEnv,
    private val askPermissions: Boolean,
) {
    val items = mutableListOf<ChatItem>()

    @Volatile var sessionId: String? = null
        private set
    @Volatile var model: String? = null
        private set
    /** O Claude está trabalhando num pedido. */
    @Volatile var busy = false
        private set
    @Volatile var running = false
        private set

    /** Chamado (na thread principal) sempre que a conversa muda. */
    var onChanged: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var process: Process? = null
    private var stdin: OutputStream? = null
    private var draft: ChatItem.Assistant? = null
    private val tools = HashMap<String, ChatItem.Tool>()
    private var requestCounter = 0
    private var lastPost = 0L

    /**
     * Inicia o Claude. [resume] continua uma conversa específica;
     * [continueLast] continua a mais recente da pasta de projetos.
     */
    fun start(resume: String? = null, continueLast: Boolean = false) {
        if (running) return
        val args = StringBuilder("cd ~/${LinuxEnv.WORKSPACE} && exec claude")
        args.append(" --output-format stream-json --verbose --input-format stream-json")
        args.append(" --include-partial-messages")
        if (askPermissions) {
            args.append(" --permission-prompt-tool stdio --permission-mode default")
        } else {
            args.append(" --permission-prompt-tool stdio --permission-mode auto")
        }
        when {
            resume != null -> args.append(" --resume=").append(resume)
            continueLast -> args.append(" --continue")
        }

        val proc = try {
            linux.startProcess("mkdir -p ~/${LinuxEnv.WORKSPACE}; $args")
        } catch (e: Exception) {
            add(ChatItem.Info("Não consegui abrir o Claude Code: ${e.message}", isError = true))
            return
        }
        process = proc
        stdin = proc.outputStream
        running = true
        changed()

        thread(name = "chat-stdout") { readOutput(proc) }
        thread(name = "chat-stderr") {
            proc.errorStream.bufferedReader().useLines { lines -> lines.forEach { Log.d(TAG, "stderr: $it") } }
        }
        send(JSONObject().put("type", "control_request").put("request_id", nextRequestId())
            .put("request", JSONObject().put("subtype", "initialize").put("hooks", JSONObject.NULL)))
    }

    /** Envia uma mensagem do usuário. */
    fun sendMessage(text: String) {
        if (!running) start(resume = sessionId, continueLast = sessionId == null)
        add(ChatItem.User(text))
        busy = true
        send(
            JSONObject()
                .put("type", "user")
                .put("message", JSONObject().put("role", "user").put("content", text))
                .put("parent_tool_use_id", JSONObject.NULL)
                .put("session_id", "default"),
        )
        changed()
    }

    /** Interrompe o que o Claude está fazendo (como o Esc no terminal). */
    fun interrupt() {
        if (!running) return
        send(JSONObject().put("type", "control_request").put("request_id", nextRequestId())
            .put("request", JSONObject().put("subtype", "interrupt")))
    }

    /** Responde a um pedido de permissão. */
    fun answer(item: ChatItem.Permission, allow: Boolean) {
        if (item.allowed != null) return
        item.allowed = allow
        val response = if (allow) {
            JSONObject().put("behavior", "allow").put("updatedInput", item.input)
        } else {
            JSONObject().put("behavior", "deny").put("message", "O usuário recusou esta ação.")
        }
        send(
            JSONObject().put("type", "control_response").put(
                "response",
                JSONObject().put("subtype", "success").put("request_id", item.requestId).put("response", response),
            ),
        )
        changed()
    }

    fun stop() {
        running = false
        busy = false
        runCatching { stdin?.close() }
        process?.destroy()
        process = null
        changed()
    }

    /**
     * Mostra o que já foi conversado (por exemplo, no terminal) lendo o
     * arquivo da sessão que o próprio Claude Code grava (.jsonl).
     */
    fun loadHistory(file: java.io.File) {
        val loaded = mutableListOf<ChatItem>()
        runCatching {
            file.forEachLine { line ->
                val json = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                if (json.optBoolean("isMeta") || json.optBoolean("isSidechain")) return@forEachLine
                val message = json.optJSONObject("message") ?: return@forEachLine
                when (json.optString("type")) {
                    "user" -> {
                        val content = message.opt("content")
                        if (content is String && content.isNotBlank() && !content.trimStart().startsWith("<")) {
                            loaded += ChatItem.User(content)
                        } else if (content is JSONArray) {
                            for (i in 0 until content.length()) {
                                val block = content.optJSONObject(i) ?: continue
                                if (block.optString("type") == "text" && !block.optString("text").trimStart().startsWith("<")) {
                                    loaded += ChatItem.User(block.optString("text"))
                                }
                            }
                        }
                    }
                    "assistant" -> {
                        val content = message.optJSONArray("content") ?: return@forEachLine
                        for (i in 0 until content.length()) {
                            val block = content.optJSONObject(i) ?: continue
                            when (block.optString("type")) {
                                "text" -> block.optString("text").takeIf { it.isNotBlank() }
                                    ?.let { loaded += ChatItem.Assistant(it, streaming = false) }
                                "tool_use" -> loaded += ChatItem.Tool(
                                    block.optString("id"), block.optString("name"),
                                    ToolText.summary(block.optString("name"), block.optJSONObject("input")),
                                ).apply { done = true }
                            }
                        }
                    }
                }
            }
        }
        items.addAll(0, loaded.takeLast(MAX_HISTORY))
        changed()
    }

    // ---- Leitura das mensagens do Claude ----

    private fun readOutput(proc: Process) {
        try {
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val json = runCatching { JSONObject(line) }.getOrNull()
                    if (json == null) {
                        Log.d(TAG, "stdout: $line")
                        continue
                    }
                    main.post { handle(json) }
                }
            }
        } finally {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            main.post {
                if (process === proc) {
                    running = false
                    busy = false
                    process = null
                    if (code != 0) add(ChatItem.Info("O Claude Code fechou (código $code).", isError = true))
                    changed()
                }
            }
        }
    }

    /** Roda na thread principal. */
    private fun handle(json: JSONObject) {
        json.optString("session_id").takeIf { it.isNotEmpty() && it != "default" }?.let { sessionId = it }
        when (json.optString("type")) {
            "system" -> if (json.optString("subtype") == "init") {
                model = json.optString("model").ifEmpty { null }
                changed()
            }
            "stream_event" -> handleStreamEvent(json.optJSONObject("event"))
            "assistant" -> handleAssistant(json.optJSONObject("message"), json.has("parent_tool_use_id") && !json.isNull("parent_tool_use_id"))
            "user" -> handleToolResults(json.optJSONObject("message"))
            "result" -> {
                busy = false
                draft?.streaming = false
                draft = null
                if (json.optBoolean("is_error")) {
                    val text = json.optString("result").ifEmpty { json.optString("subtype") }
                    add(ChatItem.Info(friendlyError(text), isError = true))
                }
                changed()
            }
            "control_request" -> handleControlRequest(json)
        }
    }

    private fun handleStreamEvent(event: JSONObject?) {
        event ?: return
        when (event.optString("type")) {
            "message_start" -> {
                draft?.streaming = false
                draft = null
            }
            "content_block_delta" -> {
                val delta = event.optJSONObject("delta") ?: return
                if (delta.optString("type") != "text_delta") return
                val piece = delta.optString("text")
                val current = draft ?: ChatItem.Assistant("", streaming = true).also {
                    draft = it
                    items += it
                }
                current.text += piece
                changed(throttle = true)
            }
        }
    }

    private fun handleAssistant(message: JSONObject?, fromSubagent: Boolean) {
        val content = message?.optJSONArray("content") ?: return
        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "tool_use" -> {
                    val id = block.optString("id")
                    if (tools.containsKey(id)) continue
                    val tool = ChatItem.Tool(id, block.optString("name"), ToolText.summary(block.optString("name"), block.optJSONObject("input")))
                    tools[id] = tool
                    items += tool
                }
            }
        }
        if (text.isNotEmpty() && !fromSubagent) {
            val current = draft
            if (current != null) {
                current.text = text.toString()
                current.streaming = false
            } else {
                items += ChatItem.Assistant(text.toString(), streaming = false)
            }
        }
        draft = null
        changed()
    }

    private fun handleToolResults(message: JSONObject?) {
        val content = message?.opt("content") as? JSONArray ?: return
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") != "tool_result") continue
            tools[block.optString("tool_use_id")]?.let {
                it.done = true
                it.failed = block.optBoolean("is_error")
            }
        }
        changed()
    }

    private fun handleControlRequest(json: JSONObject) {
        val requestId = json.optString("request_id")
        val request = json.optJSONObject("request") ?: return
        if (request.optString("subtype") != "can_use_tool") {
            // Pedidos que este app não usa: responde com erro para o Claude seguir.
            send(JSONObject().put("type", "control_response").put("response",
                JSONObject().put("subtype", "error").put("request_id", requestId).put("error", "não suportado")))
            return
        }
        val toolName = request.optString("tool_name")
        val input = request.optJSONObject("input") ?: JSONObject()
        val summary = request.optString("description").ifEmpty { ToolText.summary(toolName, input) }
        add(ChatItem.Permission(requestId, toolName, summary, input))
    }

    private fun friendlyError(text: String): String = when {
        text.contains("login", ignoreCase = true) || text.contains("auth", ignoreCase = true) ->
            "$text\n\nFaça login pelo Terminal (botão no topo) e volte para o chat."
        else -> text
    }

    // ---- Utilidades ----

    private fun add(item: ChatItem) {
        items += item
        changed()
    }

    private fun changed(throttle: Boolean = false) {
        val now = System.currentTimeMillis()
        if (throttle && now - lastPost < 80) {
            main.removeCallbacks(notifyRunnable)
            main.postDelayed(notifyRunnable, 80)
            return
        }
        lastPost = now
        onChanged?.invoke()
    }

    private val notifyRunnable = Runnable {
        lastPost = System.currentTimeMillis()
        onChanged?.invoke()
    }

    /** Escreve uma linha JSON no Claude; uma fila só, para manter a ordem. */
    private fun send(json: JSONObject) {
        val out = stdin ?: return
        writer.execute {
            runCatching {
                out.write((json.toString() + "\n").toByteArray())
                out.flush()
            }.onFailure { Log.w(TAG, "falha ao escrever", it) }
        }
    }

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun nextRequestId() = "req_${++requestCounter}_${System.nanoTime().toString(16)}"

    private companion object {
        const val TAG = "JangadaChat"
        const val MAX_HISTORY = 300
    }
}

/** Textos curtos e em português para as ferramentas do agente. */
object ToolText {
    fun label(name: String): String = when (name) {
        "Bash" -> "Rodar comando"
        "Read" -> "Ler arquivo"
        "Write" -> "Criar arquivo"
        "Edit", "MultiEdit" -> "Editar arquivo"
        "NotebookEdit" -> "Editar notebook"
        "Glob" -> "Procurar arquivos"
        "Grep" -> "Procurar no texto"
        "WebFetch" -> "Abrir página"
        "WebSearch" -> "Pesquisar na web"
        "TodoWrite" -> "Lista de tarefas"
        "Task", "Agent" -> "Subagente"
        else -> name
    }

    fun summary(name: String, input: JSONObject?): String {
        input ?: return ""
        for (key in listOf("command", "file_path", "path", "url", "query", "pattern", "description", "prompt")) {
            val value = input.optString(key)
            if (value.isNotEmpty()) return value.take(400)
        }
        return input.toString().take(200)
    }
}

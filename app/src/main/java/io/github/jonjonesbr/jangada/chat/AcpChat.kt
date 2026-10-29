package io.github.jonjonesbr.jangada.chat

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Chat com qualquer agente que fale o Agent Client Protocol (ACP):
 * OpenCode, Codex (via codex-acp), Pi, oh-my-pi, Qwen Code, Hermes,
 * Command Code... É JSON-RPC por linha no stdin/stdout do agente.
 *
 * O app não oferece ao agente acesso a arquivos ou terminal pelo
 * protocolo: cada agente usa as próprias ferramentas, dentro do Linux,
 * e pede licença pelo "session/request_permission".
 */
class AcpChat(
    private val linux: LinuxEnv,
    /** Comando que inicia o agente em modo ACP (ex.: "opencode acp"). */
    private val command: String,
    private val appVersion: String,
) : AgentChat {

    override val items = mutableListOf<ChatItem>()
    @Volatile override var sessionId: String? = null
        private set
    /** Nome do modelo atual (quando o agente informa); senão, o nome do agente. */
    override val model: String?
        get() = configOptions.firstOrNull { it.category == "model" }?.currentName ?: agentTitle
    @Volatile override var busy = false
        private set
    @Volatile override var running = false
        private set
    @Volatile override var interrupted = false
        private set
    override val supportsHistory: Boolean get() = canList
    override var onChanged: (() -> Unit)? = null

    /**
     * Ajuste da conversa que o agente oferece (modelo, nível de raciocínio,
     * modo...). Vem do "configOptions" do ACP ou, em agentes mais antigos,
     * de "models" e "modes".
     */
    class ConfigOption(
        val id: String,
        val name: String,
        val category: String,
        var current: String,
        val choices: List<Pair<String, String>>,
        val source: Source,
    ) {
        enum class Source { CONFIG, MODEL, MODE }
        val currentName get() = choices.firstOrNull { it.first == current }?.second ?: current
    }

    @Volatile var configOptions: List<ConfigOption> = emptyList()
        private set

    /** Título da conversa informado pelo agente. */
    @Volatile var title: String? = null
        private set
    @Volatile var canDelete = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    private var stdin: OutputStream? = null
    private var nextId = 1
    private val pending = HashMap<Int, (JSONObject?, JSONObject?) -> Unit>()
    private val waitingPrompts = mutableListOf<Pair<String, List<Attachment>>>()
    private val tools = HashMap<String, ChatItem.Tool>()
    private var draft: ChatItem.Assistant? = null
    private var draftMessageId: String? = null
    private var userDraft: ChatItem.User? = null
    private var plan: ChatItem.Plan? = null
    private var ready = false
    private var loadingHistory = false
    private var canLoad = false
    private var canList = false
    private var imageSupport = false
    private var agentTitle: String? = null
    private var lastPost = 0L

    /** Inicia o agente; com [resume], recarrega essa conversa (se o agente suportar). */
    fun start(resume: String? = null) {
        if (running) return
        val proc = try {
            linux.startProcess("mkdir -p ~/${LinuxEnv.WORKSPACE} && cd ~/${LinuxEnv.WORKSPACE} && exec $command", interactive = true)
        } catch (e: Exception) {
            add(ChatItem.Info("Não consegui abrir o agente: ${e.message}", isError = true))
            return
        }
        process = proc
        stdin = proc.outputStream
        running = true
        changed()
        thread(name = "acp-stdout") { readOutput(proc) }
        thread(name = "acp-stderr") {
            runCatching {
                proc.errorStream.bufferedReader().useLines { lines -> lines.forEach { Log.d(TAG, "stderr: $it") } }
            }
        }

        val capabilities = JSONObject()
            .put("fs", JSONObject().put("readTextFile", false).put("writeTextFile", false))
            .put("terminal", false)
        request(
            "initialize",
            JSONObject().put("protocolVersion", 1).put("clientCapabilities", capabilities)
                .put("clientInfo", JSONObject().put("name", "jangada").put("title", "Jangada").put("version", appVersion)),
        ) { result, error ->
            if (result == null) {
                add(ChatItem.Info("O agente não respondeu ao início: ${error?.optString("message")}", isError = true))
                return@request
            }
            val caps = result.optJSONObject("agentCapabilities") ?: JSONObject()
            canLoad = caps.optBoolean("loadSession")
            imageSupport = caps.optJSONObject("promptCapabilities")?.optBoolean("image") == true
            val sessionCaps = caps.optJSONObject("sessionCapabilities")
            canList = sessionCaps?.has("list") == true && !sessionCaps.isNull("list")
            canDelete = sessionCaps?.has("delete") == true && !sessionCaps.isNull("delete")
            agentTitle = result.optJSONObject("agentInfo")?.let { info ->
                listOf(info.optString("title").ifEmpty { info.optString("name") }, info.optString("version"))
                    .filter { it.isNotEmpty() }.joinToString(" ")
            }
            openSession(resume)
        }
    }

    private fun openSession(resume: String?) {
        val params = JSONObject().put("cwd", "/root/${LinuxEnv.WORKSPACE}").put("mcpServers", JSONArray())
        if (resume != null && canLoad) {
            loadingHistory = true
            sessionId = resume
            request("session/load", params.put("sessionId", resume)) { result, error ->
                loadingHistory = false
                result?.let { parseConfig(it) }
                finishDrafts()
                if (error != null) {
                    add(ChatItem.Info("Não consegui reabrir essa conversa: ${error.optString("message")}", isError = true))
                    sessionId = null
                    newSession()
                    return@request
                }
                interrupted = items.lastOrNull().let { it is ChatItem.Tool || it is ChatItem.User }
                becomeReady()
            }
        } else {
            newSession()
        }
    }

    private fun newSession() {
        val params = JSONObject().put("cwd", "/root/${LinuxEnv.WORKSPACE}").put("mcpServers", JSONArray())
        request("session/new", params) { result, error ->
            if (result == null) {
                add(ChatItem.Info(friendlyError(error), isError = true))
                return@request
            }
            sessionId = result.optString("sessionId")
            parseConfig(result)
            becomeReady()
        }
    }

    private fun becomeReady() {
        ready = true
        changed()
        val queued = waitingPrompts.toList()
        waitingPrompts.clear()
        queued.forEach { (text, files) -> prompt(text, files) }
    }

    override fun sendMessage(text: String, attachments: List<Attachment>) {
        if (!running) start(resume = sessionId)
        items += ChatItem.User(text, attachments.map { it.name })
        busy = true
        interrupted = false
        changed()
        if (ready) prompt(text, attachments) else waitingPrompts += text to attachments
    }

    private fun prompt(text: String, attachments: List<Attachment>) {
        val id = sessionId ?: return
        val blocks = JSONArray()
        val note = Attachments.filesNote(attachments, includeImages = !imageSupport)
        blocks.put(JSONObject().put("type", "text").put("text", (text + note).ifBlank { " " }))
        if (imageSupport) {
            for (image in attachments.filter { it.imageBase64 != null }) {
                blocks.put(JSONObject().put("type", "image").put("mimeType", image.mimeType).put("data", image.imageBase64))
            }
        }
        draft = null
        request("session/prompt", JSONObject().put("sessionId", id).put("prompt", blocks)) { result, error ->
            busy = false
            finishDrafts()
            if (error != null) add(ChatItem.Info(friendlyError(error), isError = true))
            else if (result?.optString("stopReason") == "refusal") add(ChatItem.Info("O agente recusou o pedido.", isError = true))
            changed()
        }
    }

    override fun interrupt() {
        val id = sessionId ?: return
        notify("session/cancel", JSONObject().put("sessionId", id))
        // Pedidos de permissão em aberto são respondidos como cancelados.
        for (item in items) {
            if (item is ChatItem.Permission && item.chosen == null) {
                item.chosen = PermissionOption("", "", PermissionOption.Kind.REJECT_ONCE)
                respond(item.requestId, JSONObject().put("outcome", JSONObject().put("outcome", "cancelled")))
            }
        }
        changed()
    }

    override fun answer(item: ChatItem.Permission, option: PermissionOption) {
        if (item.chosen != null) return
        item.chosen = option
        respond(
            item.requestId,
            JSONObject().put("outcome", JSONObject().put("outcome", "selected").put("optionId", option.id)),
        )
        changed()
    }

    /** Muda um ajuste da conversa (modelo, raciocínio, modo...). */
    fun setOption(option: ConfigOption, value: String) {
        val id = sessionId ?: return
        when (option.source) {
            ConfigOption.Source.CONFIG -> request(
                "session/set_config_option",
                JSONObject().put("sessionId", id).put("configId", option.id).put("value", value),
            ) { result, error ->
                if (result != null && result.has("configOptions")) parseConfig(result)
                else if (error == null) option.current = value
                else add(ChatItem.Info("Não consegui mudar ${option.name}: ${error.optString("message")}", isError = true))
                changed()
            }
            ConfigOption.Source.MODEL -> request("session/set_model", JSONObject().put("sessionId", id).put("modelId", value)) { _, error ->
                if (error == null) option.current = value
                else add(ChatItem.Info("Não consegui mudar o modelo: ${error.optString("message")}", isError = true))
                changed()
            }
            ConfigOption.Source.MODE -> request("session/set_mode", JSONObject().put("sessionId", id).put("modeId", value)) { _, error ->
                if (error == null) option.current = value
                changed()
            }
        }
    }

    /** Lê os ajustes que o agente oferece numa resposta (session/new, load, set...). */
    private fun parseConfig(result: JSONObject) {
        val config = result.optJSONArray("configOptions")
        if (config != null) {
            configOptions = (0 until config.length()).mapNotNull { i ->
                val o = config.optJSONObject(i) ?: return@mapNotNull null
                if (o.optString("type", "select") != "select") return@mapNotNull null
                ConfigOption(
                    o.optString("id"), o.optString("name"), o.optString("category"),
                    o.optString("currentValue"), selectChoices(o.optJSONArray("options")), ConfigOption.Source.CONFIG,
                )
            }
            changed()
            return
        }
        val list = mutableListOf<ConfigOption>()
        result.optJSONObject("models")?.let { models ->
            val available = models.optJSONArray("availableModels") ?: JSONArray()
            val choices = (0 until available.length()).mapNotNull { i ->
                available.optJSONObject(i)?.let { it.optString("modelId") to it.optString("name").ifEmpty { it.optString("modelId") } }
            }
            if (choices.isNotEmpty()) list += ConfigOption("model", "Modelo", "model", models.optString("currentModelId"), choices, ConfigOption.Source.MODEL)
        }
        result.optJSONObject("modes")?.let { modes ->
            val available = modes.optJSONArray("availableModes") ?: JSONArray()
            val choices = (0 until available.length()).mapNotNull { i ->
                available.optJSONObject(i)?.let { it.optString("id") to it.optString("name").ifEmpty { it.optString("id") } }
            }
            if (choices.isNotEmpty()) list += ConfigOption("mode", "Modo", "mode", modes.optString("currentModeId"), choices, ConfigOption.Source.MODE)
        }
        if (list.isNotEmpty()) {
            configOptions = list
            changed()
        }
    }

    /** Opções de um seletor; grupos (ex.: "Recomendados") viram uma lista só. */
    private fun selectChoices(options: JSONArray?): List<Pair<String, String>> {
        options ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until options.length()) {
            val o = options.optJSONObject(i) ?: continue
            val group = o.optJSONArray("options")
            if (group != null) out += selectChoices(group)
            else out += o.optString("value") to o.optString("name").ifEmpty { o.optString("value") }
        }
        return out
    }

    /** Conversas anteriores na pasta de projetos (se o agente oferecer). */
    fun listSessions(callback: (List<ChatHistory.Conversation>) -> Unit) {
        if (!canList) return callback(emptyList())
        request("session/list", JSONObject().put("cwd", "/root/${LinuxEnv.WORKSPACE}")) { result, _ ->
            val sessions = result?.optJSONArray("sessions") ?: JSONArray()
            val list = (0 until sessions.length()).mapNotNull { i ->
                val s = sessions.optJSONObject(i) ?: return@mapNotNull null
                val updated = runCatching { java.time.Instant.parse(s.optString("updatedAt")).toEpochMilli() }.getOrDefault(0L)
                ChatHistory.Conversation(s.optString("sessionId"), s.optString("title").ifEmpty { "—" }, updated)
            }
            callback(list)
        }
    }

    fun deleteSession(id: String, callback: () -> Unit) {
        if (!canDelete) return callback()
        request("session/delete", JSONObject().put("sessionId", id)) { _, _ -> callback() }
    }

    override fun stop(then: (() -> Unit)?) {
        running = false
        busy = false
        val proc = process
        process = null
        runCatching { stdin?.close() }
        changed()
        if (proc == null) {
            then?.let { main.post(it) }
            return
        }
        thread(name = "acp-stop") {
            runCatching {
                if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroy()
                    proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            then?.let { main.post(it) }
        }
    }

    // ---- Mensagens do agente ----

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
        } catch (e: java.io.IOException) {
            // Leitura interrompida porque o chat foi fechado: normal.
        } finally {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            main.post {
                if (process === proc) {
                    running = false
                    busy = false
                    process = null
                    ready = false
                    add(ChatItem.Info("O agente fechou (código $code).", isError = code != 0))
                }
            }
        }
    }

    private fun handle(json: JSONObject) {
        val method = json.optString("method")
        val hasId = json.has("id") && !json.isNull("id")
        when {
            method.isNotEmpty() && hasId -> handleRequest(json.get("id"), method, json.optJSONObject("params"))
            method.isNotEmpty() -> if (method == "session/update") handleUpdate(json.optJSONObject("params"))
            hasId -> {
                val callback = pending.remove(json.optInt("id")) ?: return
                callback(json.optJSONObject("result") ?: if (json.has("result")) JSONObject() else null, json.optJSONObject("error"))
            }
        }
    }

    private fun handleRequest(id: Any, method: String, params: JSONObject?) {
        if (method != "session/request_permission" || params == null) {
            send(JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", -32601).put("message", "Method not found")))
            return
        }
        val toolCall = params.optJSONObject("toolCall") ?: JSONObject()
        val known = tools[toolCall.optString("toolCallId")]
        val title = toolCall.optString("title").ifEmpty { known?.name ?: "Ação" }
        val summary = summaryOf(toolCall).ifEmpty { known?.summary.orEmpty() }
        val options = params.optJSONArray("options") ?: JSONArray()
        val parsed = (0 until options.length()).mapNotNull { i ->
            val o = options.optJSONObject(i) ?: return@mapNotNull null
            val kind = when (o.optString("kind")) {
                "allow_always" -> PermissionOption.Kind.ALLOW_ALWAYS
                "reject_once" -> PermissionOption.Kind.REJECT_ONCE
                "reject_always" -> PermissionOption.Kind.REJECT_ALWAYS
                else -> PermissionOption.Kind.ALLOW_ONCE
            }
            PermissionOption(o.optString("optionId"), o.optString("name"), kind)
        }
        add(ChatItem.Permission(id, title, summary, parsed))
    }

    private fun handleUpdate(params: JSONObject?) {
        val update = params?.optJSONObject("update") ?: return
        when (update.optString("sessionUpdate")) {
            "agent_message_chunk" -> {
                userDraft = null
                val text = textOf(update.optJSONObject("content")) ?: return
                val messageId = update.optString("messageId").ifEmpty { null }
                val current = draft
                if (current == null || (messageId != null && messageId != draftMessageId) || items.lastOrNull() !== current) {
                    current?.streaming = false
                    draft = ChatItem.Assistant(text, streaming = !loadingHistory).also { items += it }
                    draftMessageId = messageId
                } else {
                    current.text += text
                }
                changed(throttle = true)
            }
            "user_message_chunk" -> {
                draft?.streaming = false
                draft = null
                val text = textOf(update.optJSONObject("content")) ?: return
                val current = userDraft
                if (current != null && items.lastOrNull() === current) {
                    val merged = ChatItem.User(current.text + text)
                    items[items.size - 1] = merged
                    userDraft = merged
                } else {
                    userDraft = ChatItem.User(text).also { items += it }
                }
                changed(throttle = true)
            }
            "tool_call" -> {
                draft?.streaming = false
                draft = null
                userDraft = null
                val id = update.optString("toolCallId")
                val tool = tools[id] ?: ChatItem.Tool(id, update.optString("title"), summaryOf(update)).also {
                    tools[id] = it
                    items += it
                }
                applyStatus(tool, update.optString("status"))
                changed()
            }
            "tool_call_update" -> {
                val tool = tools[update.optString("toolCallId")] ?: return
                update.optString("title").takeIf { it.isNotEmpty() }?.let { tool.name = it }
                summaryOf(update).takeIf { it.isNotEmpty() }?.let { tool.summary = it }
                applyStatus(tool, update.optString("status"))
                changed()
            }
            "plan" -> {
                val entries = update.optJSONArray("entries") ?: return
                val list = (0 until entries.length()).mapNotNull { i ->
                    entries.optJSONObject(i)?.let { PlanEntry(it.optString("content"), it.optString("status")) }
                }
                val current = plan
                if (current != null && items.contains(current)) current.entries = list
                else plan = ChatItem.Plan(list).also { items += it }
                changed()
            }
            "session_info_update" -> update.optString("title").takeIf { it.isNotEmpty() }?.let { title = it; changed() }
            "config_option_update" -> parseConfig(update)
            "current_mode_update" -> {
                val mode = configOptions.firstOrNull { it.source == ConfigOption.Source.MODE || it.category == "mode" }
                update.optString("currentModeId").takeIf { it.isNotEmpty() }?.let { mode?.current = it; changed() }
            }
            "notice" -> add(ChatItem.Info(listOf(update.optString("title"), update.optString("description"))
                .filter { it.isNotEmpty() }.joinToString(": ")))
        }
    }

    private fun applyStatus(tool: ChatItem.Tool, status: String) {
        when (status) {
            "completed" -> tool.done = true
            "failed" -> { tool.done = true; tool.failed = true }
        }
        if (loadingHistory) tool.done = true
    }

    private fun finishDrafts() {
        draft?.streaming = false
        draft = null
        userDraft = null
    }

    private fun textOf(content: JSONObject?): String? =
        content?.takeIf { it.optString("type") == "text" }?.optString("text")

    /** Resumo curto de uma ação: o comando, o arquivo ou o endereço envolvido. */
    private fun summaryOf(call: JSONObject): String {
        call.optJSONObject("rawInput")?.let { input ->
            ToolText.summary("", input).takeIf { it.isNotEmpty() }?.let { return it }
        }
        call.optJSONArray("locations")?.optJSONObject(0)?.optString("path")?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun friendlyError(error: JSONObject?): String {
        val message = error?.optString("message").orEmpty()
        val data = error?.opt("data")?.toString().orEmpty()
        val auth = error?.optInt("code") == -32000 || message.contains("auth", true) || data.contains("auth", true)
        return if (auth) "O agente precisa de login. Toque em >_ no topo, entre pela tela do agente e volte ao chat."
        else listOf(message, data).filter { it.isNotEmpty() && it != "null" }.joinToString("\n").ifEmpty { "Erro do agente." }
    }

    // ---- JSON-RPC ----

    private fun request(method: String, params: JSONObject, callback: (JSONObject?, JSONObject?) -> Unit) {
        val id = nextId++
        pending[id] = callback
        send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
    }

    private fun notify(method: String, params: JSONObject) =
        send(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))

    private fun respond(id: Any, result: JSONObject) =
        send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))

    private fun send(json: JSONObject) {
        val out = stdin ?: return
        writer.execute {
            runCatching {
                out.write((json.toString() + "\n").toByteArray())
                out.flush()
            }.onFailure { Log.w(TAG, "falha ao escrever", it) }
        }
    }

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

    private companion object {
        const val TAG = "JangadaAcp"
    }
}

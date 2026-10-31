package io.github.jonjonesbr.jangada.chat

import android.util.Log
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import org.json.JSONArray
import org.json.JSONObject

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
    linux: LinuxEnv,
    private val askPermissions: Boolean,
    /** Apelido do modelo ("opus", "sonnet", "haiku") ou vazio para o padrão da conta. */
    private val modelAlias: String = "",
    /** Esforço ("low" ... "max") ou vazio para o padrão. */
    private val effort: String = "",
) : ProcessChat(linux, TAG) {

    @Volatile override var sessionId: String? = null
        private set
    @Volatile override var model: String? = null
        private set
    override val supportsHistory = true

    /** Chaves liberadas com "Sempre permitir nesta conversa" (ver [permissionKey]). */
    private val alwaysAllowed = HashSet<String>()

    private var draft: ChatItem.Assistant? = null
    private val tools = HashMap<String, ChatItem.Tool>()
    /** Lista de tarefas (TodoWrite) da conversa: um único plano, atualizado a cada chamada. */
    private var plan: ChatItem.Plan? = null
    private var requestCounter = 0
    /** Maior janela de contexto que o Claude informou (0 = ainda não sabemos). */
    private var contextWindow = 0L

    /**
     * Inicia o Claude. [resume] continua uma conversa específica;
     * [continueLast], a mais recente da pasta de projetos. Com [history],
     * mostra o que já foi conversado: os itens de [previous] (se for a mesma
     * conversa) ou o arquivo que o Claude Code grava (terminal, histórico).
     * Tudo o que lê arquivo roda fora da thread principal.
     */
    fun start(
        resume: String? = null,
        continueLast: Boolean = false,
        history: Boolean = false,
        previous: AgentChat? = null,
    ) {
        if (running) return
        contextUsage = null
        val previousId = previous?.sessionId
        val previousItems = previous?.items?.toList().orEmpty()
        if (resume != null) sessionId = resume
        launch(interactive = false) {
            runCatching { linux.refreshIntegration() }.onFailure { Log.w(TAG, "integração", it) }
            // "Continuar a última" vira o id da conversa mais recente: mais seguro que
            // o --continue do Claude, que nem sempre enxerga conversas do outro modo.
            val id = resume ?: if (continueLast) ChatHistory.list(linux, limit = 1).firstOrNull()?.id else null
            if (id != null) sessionId = id
            if (history && id != null) {
                if (id == previousId && previousItems.isNotEmpty()) {
                    main.post { showHistory(previousItems, markInterrupted = false) }
                } else {
                    ChatHistory.file(linux, id)?.let { file ->
                        val loaded = ChatHistory.read(file, MAX_HISTORY)
                        main.post { showHistory(loaded, markInterrupted = true) }
                    }
                }
            }
            command(id)
        }
        send(JSONObject().put("type", "control_request").put("request_id", nextRequestId())
            .put("request", JSONObject().put("subtype", "initialize").put("hooks", JSONObject.NULL)))
    }

    private fun command(resume: String?): String {
        val args = StringBuilder("mkdir -p ~/${LinuxEnv.WORKSPACE}; cd ~/${LinuxEnv.WORKSPACE} && exec claude")
        args.append(" --output-format stream-json --verbose --input-format stream-json")
        args.append(" --include-partial-messages")
        if (askPermissions) {
            args.append(" --permission-prompt-tool stdio --permission-mode default")
        } else {
            args.append(" --permission-prompt-tool stdio --permission-mode auto")
        }
        if (modelAlias.isNotEmpty()) args.append(" --model ").append(AgentRecipe.q(modelAlias))
        if (effort.isNotEmpty()) args.append(" --effort ").append(AgentRecipe.q(effort))
        if (resume != null) args.append(" --resume=").append(AgentRecipe.q(resume))
        return args.toString()
    }

    /** Põe o que já foi conversado antes das mensagens novas (thread principal). */
    private fun showHistory(loaded: List<ChatItem>, markInterrupted: Boolean) {
        items.addAll(0, loaded)
        // Continua atualizando o plano que já está na conversa, em vez de criar outro
        // (a menos que um plano novo já tenha chegado antes do histórico).
        if (plan == null) loaded.filterIsInstance<ChatItem.Plan>().lastOrNull()?.let { plan = it }
        // Terminou numa ação ou numa mensagem sua, sem resposta depois: foi interrompida.
        // O raciocínio não conta (ele vem antes da ação ou da resposta, e pode ser a última coisa gravada).
        if (markInterrupted) interrupted = loaded.lastOrNull { it !is ChatItem.Thinking }.let { it is ChatItem.Tool || it is ChatItem.User }
        changed()
    }

    /**
     * Envia uma mensagem do usuário. Fotos vão como imagem; outros arquivos
     * vão como caminho (o Claude lê com as ferramentas dele).
     */
    override fun sendMessage(text: String, attachments: List<Attachment>) {
        if (!running) start(resume = sessionId)
        val item = ChatItem.User(text, attachments.map { it.name })
        add(item)
        // Turno em andamento: a mensagem espera na fila e vai quando o turno acabar (ver "result").
        if (busy) {
            item.queued = true
            queue += QueuedMessage(text, attachments, item)
            changed()
            return
        }
        busy = true
        interrupted = false
        deliver(text, attachments)
        changed()
    }

    /** Escreve a mensagem do usuário no Claude (fotos vão como imagem; outros arquivos, como caminho). */
    private fun deliver(text: String, attachments: List<Attachment>) {
        val fullText = text + Attachments.filesNote(attachments)
        val content: Any = if (attachments.none { it.imageBase64 != null }) {
            fullText
        } else {
            JSONArray().apply {
                for (image in attachments.filter { it.imageBase64 != null }) {
                    put(
                        JSONObject().put("type", "image").put(
                            "source",
                            JSONObject().put("type", "base64").put("media_type", image.mimeType)
                                .put("data", image.imageBase64),
                        ),
                    )
                }
                put(JSONObject().put("type", "text").put("text", fullText.ifBlank { " " }))
            }
        }
        send(
            JSONObject()
                .put("type", "user")
                .put("message", JSONObject().put("role", "user").put("content", content))
                .put("parent_tool_use_id", JSONObject.NULL)
                .put("session_id", "default"),
        )
    }

    /** Envia a próxima mensagem da fila; sem fila, o turno acabou (só aí [busy] volta a falso). */
    private fun sendQueued() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            busy = false
            return
        }
        next.item.queued = false
        deliver(next.text, next.attachments)
        changed()
    }

    /** Interrompe o que o Claude está fazendo (como o Esc no terminal). */
    override fun interrupt() {
        if (!running) return
        // Interrompido pelo usuário: o fim desse turno não gera aviso.
        silentTurn = true
        // O que estava na fila não chegou a ser enviado: descarta.
        dropQueue()
        // Pedidos de permissão em aberto são recusados antes do interrupt (o CLI lê na ordem).
        for (item in items) {
            if (item is ChatItem.Permission && item.chosen == null) refuse(item, reply = true)
        }
        send(JSONObject().put("type", "control_request").put("request_id", nextRequestId())
            .put("request", JSONObject().put("subtype", "interrupt")))
        changed()
    }

    /**
     * Marca um pedido de permissão como recusado. Com [reply], responde "negado" ao CLI;
     * sem ele (o CLI mesmo cancelou o pedido), só mostra no cartão.
     */
    private fun refuse(item: ChatItem.Permission, reply: Boolean) {
        item.chosen = item.options.firstOrNull { it.kind == PermissionOption.Kind.REJECT_ONCE }
            ?: PermissionOption("deny", "", PermissionOption.Kind.REJECT_ONCE)
        if (reply) respondPermission(item.requestId as String, allow = false, input = item.input)
    }

    /** Responde a um pedido de permissão. */
    override fun answer(item: ChatItem.Permission, option: PermissionOption) {
        if (item.chosen != null) return
        if (!running) {
            // O agente parou: não há a quem responder; enviar uma mensagem retoma a conversa.
            add(ChatItem.Info(context.getString(R.string.chat_permission_agent_stopped)))
            return
        }
        item.chosen = option
        val allow = option.allows
        if (option.kind == PermissionOption.Kind.ALLOW_ALWAYS) alwaysAllowed += permissionKey(item.toolName, item.input)
        respondPermission(item.requestId as String, allow, item.input)
        changed()
    }

    private fun respondPermission(requestId: String, allow: Boolean, input: JSONObject) {
        val response = if (allow) {
            JSONObject().put("behavior", "allow").put("updatedInput", input)
        } else {
            JSONObject().put("behavior", "deny").put("message", context.getString(R.string.chat_denied_message))
        }
        send(
            JSONObject().put("type", "control_response").put(
                "response",
                JSONObject().put("subtype", "success").put("request_id", requestId).put("response", response),
            ),
        )
    }

    override fun handle(json: JSONObject) {
        json.optString("session_id").takeIf { it.isNotEmpty() && it != "default" }?.let { sessionId = it }
        // Mensagens de subagentes trazem o id da ferramenta que os chamou: não são a resposta principal.
        val fromSubagent = json.has("parent_tool_use_id") && !json.isNull("parent_tool_use_id")
        when (json.optString("type")) {
            "system" -> when (json.optString("subtype")) {
                "init" -> {
                    model = json.optString("model").ifEmpty { null }
                    changed()
                }
                // A conversa foi compactada: o uso volta a ser medido no próximo turno.
                "compact_boundary" -> {
                    contextUsage = null
                    changed()
                }
            }
            "stream_event" -> if (!fromSubagent) handleStreamEvent(json.optJSONObject("event"))
            "assistant" -> {
                handleAssistant(json.optJSONObject("message"), fromSubagent)
                if (!fromSubagent) updateUsage(json.optJSONObject("message")?.optJSONObject("usage"))
            }
            "user" -> handleToolResults(json.optJSONObject("message"))
            "result" -> {
                endStreaming()
                if (json.optBoolean("is_error")) {
                    val result = json.optString("result")
                    val subtype = json.optString("subtype")
                    val text = result.ifEmpty { subtype }
                    if (Regex("login|log in|auth|api key|credential", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                        // Sem login: o cartão leva à tela do Claude, onde o login acontece.
                        if (items.none { it is ChatItem.Login && it.state == ChatItem.Login.State.WAITING }) {
                            add(ChatItem.Login(listOf(AuthOption("screen", "", "", AuthOption.Kind.AGENT_SCREEN))))
                        }
                    } else if (result.isEmpty()) {
                        // Sem texto do agente: o subtipo (ex.: error_max_turns) vira mensagem amigável.
                        add(errorBySubtype(subtype))
                    } else {
                        add(ChatItem.Info(friendlyError(result), isError = true))
                    }
                }
                updateWindow(json.optJSONObject("modelUsage"))
                changed()
                // Com mensagem na fila, o turno continua (busy segue true); o aviso de fim só sai no último.
                sendQueued()
            }
            "control_request" -> handleControlRequest(json)
            "control_cancel_request" -> handleControlCancel(json.optString("request_id"))
        }
    }

    /**
     * Tokens que a conversa ocupa agora, pelo uso da última resposta. A janela é a que o
     * Claude informou; sem ela, 1M para modelos com "[1m]" no nome e 200 mil no resto.
     */
    private fun updateUsage(usage: JSONObject?) {
        usage ?: return
        val used = usage.optLong("input_tokens") + usage.optLong("cache_creation_input_tokens") +
            usage.optLong("cache_read_input_tokens") + usage.optLong("output_tokens")
        if (used <= 0) return
        val size = contextWindow.takeIf { it > 0 } ?: if (model?.contains("[1m]") == true) 1_000_000L else 200_000L
        contextUsage = ContextUsage(used, size)
        changed()
    }

    /** O "result" traz a janela de cada modelo usado: guarda a maior e recalcula com o uso atual. */
    private fun updateWindow(modelUsage: JSONObject?) {
        modelUsage ?: return
        val largest = modelUsage.keys().asSequence()
            .maxOfOrNull { modelUsage.optJSONObject(it)?.optLong("contextWindow") ?: 0L } ?: 0L
        if (largest <= 0) return
        contextWindow = largest
        contextUsage?.let { contextUsage = ContextUsage(it.used, largest) }
    }

    /** O CLI cancelou um pedido de permissão em aberto: o cartão fica recusado, sem resposta. */
    private fun handleControlCancel(requestId: String) {
        val item = items.filterIsInstance<ChatItem.Permission>()
            .firstOrNull { it.requestId == requestId && it.chosen == null } ?: return
        refuse(item, reply = false)
        changed()
    }

    /** Nenhuma resposta fica com o cursor piscando depois que o Claude parou de escrever. */
    private fun endStreaming() {
        draft = null
        for (item in items) {
            if (item is ChatItem.Assistant) item.streaming = false
        }
    }

    private fun handleStreamEvent(event: JSONObject?) {
        event ?: return
        when (event.optString("type")) {
            "message_start" -> endStreaming()
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
                "thinking" -> {
                    // Bloco redigido vem vazio: não há o que mostrar.
                    val thinking = block.optString("thinking")
                    if (thinking.isNotBlank() && !fromSubagent) {
                        // Se a resposta já começou a aparecer (streaming), o raciocínio entra antes dela.
                        val at = draft?.let { items.indexOf(it) } ?: -1
                        if (at >= 0) items.add(at, ChatItem.Thinking(thinking)) else items += ChatItem.Thinking(thinking)
                    }
                }
                "tool_use" -> {
                    val id = block.optString("id")
                    if (block.optString("name") == TODO_TOOL) {
                        // A lista de tarefas de um subagente não substitui a da conversa.
                        if (!fromSubagent) updatePlan(todoEntries(block.optJSONObject("input")))
                        continue
                    }
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

    /** Põe o plano na conversa ou, se já existe, troca só as tarefas dele. */
    private fun updatePlan(entries: List<PlanEntry>) {
        val current = plan
        if (current != null && items.contains(current)) current.entries = entries
        else plan = ChatItem.Plan(entries).also { items += it }
    }

    private fun handleToolResults(message: JSONObject?) {
        val content = message?.opt("content") as? JSONArray ?: return
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") != "tool_result") continue
            tools[block.optString("tool_use_id")]?.let {
                it.done = true
                it.failed = block.optBoolean("is_error")
                it.output = ToolOutput.fromClaude(block.opt("content"))
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
        if (permissionKey(toolName, input) in alwaysAllowed) {
            respondPermission(requestId, allow = true, input = input)
            return
        }
        // O resumo é sempre o comando ou caminho; a descrição do agente vai como texto extra.
        val summary = ToolText.summary(toolName, input, limit = 4000)
        val detail = request.optString("description").takeIf { it.isNotEmpty() && it != summary }.orEmpty()
        add(
            ChatItem.Permission(
                requestId, ToolText.label(context, toolName), summary,
                listOf(
                    PermissionOption("allow", "", PermissionOption.Kind.ALLOW_ONCE),
                    PermissionOption("always", "", PermissionOption.Kind.ALLOW_ALWAYS),
                    PermissionOption("deny", "", PermissionOption.Kind.REJECT_ONCE),
                ),
                input, toolName, detail,
                diff = DiffText.fromClaude(toolName, input),
            ),
        )
    }

    private fun friendlyError(text: String): String = when {
        text.contains("login", ignoreCase = true) || text.contains("auth", ignoreCase = true) ->
            context.getString(R.string.chat_claude_login_hint, text)
        else -> text
    }

    /** Erro do resultado sem texto: mensagem amigável; o subtipo do CLI vai em Detalhes. */
    private fun errorBySubtype(subtype: String): ChatItem.Info {
        val details = subtype.ifEmpty { null }
        return if (subtype == "error_max_turns") {
            ChatItem.Info(context.getString(R.string.chat_error_max_turns), isError = true, details = details)
        } else {
            val text = context.getString(R.string.chat_acp_error) + "\n" + context.getString(R.string.chat_error_hint)
            ChatItem.Info(text, isError = true, details = details)
        }
    }

    // ---- Utilidades ----

    private fun nextRequestId() = "req_${++requestCounter}_${System.nanoTime().toString(16)}"

    companion object {
        private const val TAG = "JangadaChat"
        const val MAX_HISTORY = 300

        /** Ferramentas que executam comando: "sempre permitir" vale só para o mesmo comando. */
        private val SHELL_TOOLS = setOf("Bash", "BashOutput", "KillShell")

        fun isShellTool(name: String): Boolean = name in SHELL_TOOLS

        /** Ferramenta de lista de tarefas do Claude Code: vira um [ChatItem.Plan], não um cartão. */
        const val TODO_TOOL = "TodoWrite"

        /**
         * Tarefas do TodoWrite ({"todos":[{"content","status","activeForm"}]}) como entradas
         * de plano. Status: pending, in_progress ou completed (os mesmos do ACP).
         */
        fun todoEntries(input: JSONObject?): List<PlanEntry> {
            val todos = input?.optJSONArray("todos") ?: return emptyList()
            return (0 until todos.length()).mapNotNull { i ->
                val todo = todos.optJSONObject(i) ?: return@mapNotNull null
                val text = todo.optString("content").ifEmpty { todo.optString("activeForm") }
                PlanEntry(text, todo.optString("status").ifEmpty { "pending" })
            }
        }

        /**
         * Chave do "sempre permitir": o comando exato (ou os dados da chamada, se não há
         * comando) para ferramentas de shell; o nome da ferramenta para as demais.
         */
        fun permissionKey(toolName: String, input: JSONObject): String {
            if (!isShellTool(toolName)) return toolName
            return "$toolName\u0000${input.optString("command").ifEmpty { input.toString() }}"
        }
    }
}

/** Nome amigável do modelo: "claude-haiku-4-5-20251001" -> "Haiku 4.5". */
fun friendlyModel(id: String?): String? {
    if (id.isNullOrBlank()) return null
    val parts = id.removePrefix("claude-").substringBefore('[').split('-')
    val name = parts.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: return id
    val version = parts.drop(1).takeWhile { it.length <= 2 && it.all(Char::isDigit) }
    return if (version.isEmpty()) name else "$name ${version.joinToString(".")}"
}

/** Textos curtos (traduzidos) para as ferramentas do agente. */
object ToolText {
    fun label(context: android.content.Context, name: String): String =
        labelRes(name)?.let { context.getString(it) } ?: name

    /** Texto de cada ferramenta conhecida do Claude Code; null mantém o nome original. */
    fun labelRes(name: String): Int? = when (name) {
        "Bash" -> R.string.tool_bash
        "Read" -> R.string.tool_read
        "Write" -> R.string.tool_write
        "Edit", "MultiEdit" -> R.string.tool_edit
        "NotebookEdit" -> R.string.tool_notebook
        "Glob" -> R.string.tool_glob
        "Grep" -> R.string.tool_grep
        "WebFetch" -> R.string.tool_web_fetch
        "WebSearch" -> R.string.tool_web_search
        "TodoWrite" -> R.string.tool_todo
        "Task", "Agent" -> R.string.tool_agent
        else -> null
    }

    fun summary(name: String, input: JSONObject?, limit: Int = 400): String {
        input ?: return ""
        for (key in listOf("command", "file_path", "path", "url", "query", "pattern", "description", "prompt")) {
            val value = input.optString(key)
            if (value.isNotEmpty()) return value.take(limit)
        }
        return input.toString().take(200)
    }
}

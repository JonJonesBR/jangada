package io.github.jonjonesbr.jangada.chat

import android.os.SystemClock
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
    /** Pasta de trabalho (caminho dentro do Debian). */
    override val cwd: String,
    /** Compactação automática que vale para este trabalho (ver AutoCompact); "default" não mexe em nada. */
    private val autoCompact: String = AutoCompact.DEFAULT,
) : ProcessChat(linux, TAG) {

    @Volatile override var sessionId: String? = null
        private set
    /** Com a sessão guardada, a próxima mensagem usa `--resume` e continua a conversa. */
    override val canResume: Boolean get() = sessionId != null
    /** Modo de permissão desta conversa (começa pelo ajuste geral e muda pelo chip de modo). */
    @Volatile private var permissionMode: String = if (askPermissions) "default" else "auto"
    override val modeChoices: List<Pair<String, String>> = listOf(
        "default" to "Default", "acceptEdits" to "Accept edits", "auto" to "Auto", "plan" to "Plan",
    )
    override val currentMode: String get() = permissionMode
    override fun setMode(id: String) {
        if (modeChoices.none { it.first == id } || id == permissionMode) return
        permissionMode = id
        // Com o CLI rodando, troca na hora; parado, o próximo início já usa o modo novo.
        if (running) {
            send(JSONObject().put("type", "control_request").put("request_id", nextRequestId())
                .put("request", JSONObject().put("subtype", "set_permission_mode").put("mode", id)))
        }
        changed()
    }
    @Volatile override var model: String? = null
        private set
    override val supportsHistory = true
    /** Comandos "/" que o Claude anunciou ao iniciar (ver [claudeCommands]). */
    @Volatile override var commands: List<SlashCommand> = emptyList()
        private set
    /** Tarefas em segundo plano que o Claude informou (ver [updateBackground]). Só a thread principal escreve; a lista é trocada inteira. */
    @Volatile override var background: List<BackgroundTask> = emptyList()
        private set

    /** Chaves liberadas com "Sempre permitir nesta conversa" (ver [permissionKey]). */
    private val alwaysAllowed = HashSet<String>()

    /** Já houve mensagem enviada nesta execução do Claude (o histórico que chega depois não é interrupção). */
    private var sentThisRun = false
    private var draft: ChatItem.Assistant? = null
    private val tools = HashMap<String, ChatItem.Tool>()
    /** Lista de tarefas (TodoWrite) da conversa: um único plano, atualizado a cada chamada. */
    private var plan: ChatItem.Plan? = null
    private var requestCounter = 0
    /** Maior janela de contexto que o Claude informou (0 = ainda não sabemos). */
    private var contextWindow = 0L
    private val limitsStore by lazy { PlanLimitsStore(context) }

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
        sentThisRun = false
        val previousId = previous?.sessionId
        val previousItems = previous?.items?.toList().orEmpty()
        if (resume != null) sessionId = resume
        launch(interactive = false) {
            runCatching { linux.refreshIntegration(trustFolder = cwd) }.onFailure { Log.w(TAG, "integração", it) }
            // "Continuar a última" vira o id da conversa mais recente: mais seguro que
            // o --continue do Claude, que nem sempre enxerga conversas do outro modo.
            var id = resume ?: if (continueLast) ChatHistory.list(linux, cwd, limit = 1).firstOrNull()?.id else null
            // Conversa sem arquivo (apagada, ou o agente fechou antes de gravar a primeira mensagem): o
            // --resume faria o Claude sair com código 1 ("No conversation found") a cada mensagem. Começa uma nova.
            if (id != null && ChatHistory.file(linux, cwd, id)?.length().let { it == null || it == 0L }) {
                id = null
                sessionId = null
            }
            if (id != null) sessionId = id
            if (history && id != null) {
                if (id == previousId && previousItems.isNotEmpty()) {
                    main.post { showHistory(previousItems, markInterrupted = false) }
                } else {
                    ChatHistory.file(linux, cwd, id)?.let { file ->
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
        // O prefixo põe a variável de ambiente antes do cd e do exec: o Claude a lê ao iniciar.
        val args = StringBuilder(AutoCompact.claudeEnvPrefix(autoCompact) + AgentRecipe.claudeIn(cwd, exec = true, job = jobId))
        args.append(" --output-format stream-json --verbose --input-format stream-json")
        args.append(" --include-partial-messages")
        args.append(" --permission-prompt-tool stdio --permission-mode ").append(permissionMode)
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
        // Se o usuário já enviou algo antes deste histórico chegar, a conversa foi continuada: não é interrupção.
        if (markInterrupted && !sentThisRun) interrupted = loaded.lastOrNull { it !is ChatItem.Thinking }.let { it is ChatItem.Tool || it is ChatItem.User }
        changed()
    }

    /**
     * Envia uma mensagem do usuário. Fotos vão como imagem; outros arquivos
     * vão como caminho (o Claude lê com as ferramentas dele).
     */
    override fun sendMessage(text: String, attachments: List<Attachment>) {
        if (!running) start(resume = sessionId)
        sentThisRun = true
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
            // Turno acabou: redesenha já (sem isso a tela ficava em "Trabalhando…" quando nada chegava depois, como no /compact).
            changed()
            return
        }
        next.item.queued = false
        // Turno novo: busy segue true (sem piscar), mas o "silencioso" de um Parar anterior não vale para ele.
        busy = true
        interrupted = false
        restartTurnClock()
        deliver(next.text, next.attachments)
        changed()
    }

    /** Interrompe o que o Claude está fazendo (como o Esc no terminal). */
    override fun interrupt() {
        if (!running) return
        if (forceStopIfStuck()) return
        // Interrompido pelo usuário: o fim desse turno não gera aviso.
        silentTurn = true
        // O CLI ainda manda o "result" do turno cancelado; o que for enviado até lá espera por ele.
        if (busy) cancelling = true
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

    /** Para uma tarefa em segundo plano (subagente ou comando). O CLI avisa o agente e manda a lista nova. */
    override fun stopBackground(taskId: String) {
        if (!running || background.none { it.id == taskId }) return
        send(JSONObject().put("type", "control_request").put("request_id", nextRequestId())
            .put("request", JSONObject().put("subtype", "stop_task").put("task_id", taskId)))
    }

    /**
     * Marca um pedido de permissão como recusado. Com [reply], responde "negado" ao CLI;
     * sem ele (o CLI mesmo cancelou o pedido), só mostra no cartão.
     */
    private fun refuse(item: ChatItem.Permission, reply: Boolean) {
        item.chosen = item.options.firstOrNull { it.kind == PermissionOption.Kind.REJECT_ONCE }
            ?: PermissionOption("deny", "", PermissionOption.Kind.REJECT_ONCE)
        if (reply) respondPermission(item.requestId as String, allow = false, input = item.input, denyMessage = denyMessageFor(item))
    }

    /** Mensagem de recusa: no plano, pede ao modelo que continue planejando (não a padrão). */
    private fun denyMessageFor(item: ChatItem.Permission): String =
        if (item.toolName == PLAN_TOOL) context.getString(R.string.chat_plan_keep_message)
        else context.getString(R.string.chat_denied_message)

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
        // Perguntas e plano nunca viram "sempre permitir": cada um é uma decisão própria.
        if (option.kind == PermissionOption.Kind.ALLOW_ALWAYS && item.toolName != QUESTION_TOOL && item.toolName != PLAN_TOOL) {
            alwaysAllowed += permissionKey(item.toolName, item.input)
        }
        respondPermission(item.requestId as String, allow, item.input, denyMessageFor(item))
        changed()
    }

    /** Responde a um AskUserQuestion: allow com as respostas dentro de updatedInput. */
    fun answerQuestions(item: ChatItem.Permission, answers: Map<String, String>) {
        if (item.chosen != null) return
        if (!running) {
            add(ChatItem.Info(context.getString(R.string.chat_permission_agent_stopped)))
            return
        }
        item.chosen = item.options.first { it.kind == PermissionOption.Kind.ALLOW_ONCE }
        item.answers = answers
        respondPermission(item.requestId as String, true, answersInput(item.input, answers))
        changed()
    }

    private fun respondPermission(
        requestId: String,
        allow: Boolean,
        input: JSONObject,
        denyMessage: String = context.getString(R.string.chat_denied_message),
    ) {
        val response = if (allow) {
            JSONObject().put("behavior", "allow").put("updatedInput", input)
        } else {
            JSONObject().put("behavior", "deny").put("message", denyMessage)
        }
        send(
            JSONObject().put("type", "control_response").put(
                "response",
                JSONObject().put("subtype", "success").put("request_id", requestId).put("response", response),
            ),
        )
    }

    /**
     * Comandos "/" do Claude a partir do "init". Os que só funcionam no terminal não funcionam no chat,
     * e os internos (começam com "__") não interessam ao usuário; cada nome aparece uma vez.
     */
    private fun claudeCommands(json: JSONObject): List<SlashCommand> {
        val terminalOnly = jsonNames(json.optJSONArray("terminal_slash_commands")).toSet()
        return jsonNames(json.optJSONArray("slash_commands"))
            .filter { it.isNotEmpty() && !it.startsWith("__") && it !in terminalOnly }
            .distinct()
            .map { SlashCommand(it, "") }
    }

    /** Textos de um array JSON, sem a barra inicial; array ausente vira lista vazia. */
    private fun jsonNames(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.optString(it).removePrefix("/") }
    }

    override fun handle(json: JSONObject) {
        json.optString("session_id").takeIf { it.isNotEmpty() && it != "default" }?.let { sessionId = it }
        // Mensagens de subagentes trazem o id da ferramenta que os chamou: não são a resposta principal.
        val fromSubagent = json.has("parent_tool_use_id") && !json.isNull("parent_tool_use_id")
        when (json.optString("type")) {
            "system" -> when (json.optString("subtype")) {
                "init" -> {
                    model = json.optString("model").ifEmpty { null }
                    if (json.optJSONArray("slash_commands") != null) commands = claudeCommands(json)
                    changed()
                }
                // A conversa foi compactada: o uso volta a ser medido no próximo turno.
                "compact_boundary" -> {
                    contextUsage = null
                    add(ChatItem.Info(context.getString(R.string.chat_compacted)))
                    changed()
                }
                // O conjunto inteiro de tarefas em segundo plano (troca tudo, não soma).
                "background_tasks_changed" -> {
                    updateBackground(json.optJSONArray("tasks"))
                    changed()
                }
                "task_progress" -> updateActivity(json.optString("task_id"), json.optString("description"))
            }
            "stream_event" -> {
                if (!fromSubagent) {
                    startOwnTurn()
                    handleStreamEvent(json.optJSONObject("event"))
                }
            }
            "assistant" -> {
                if (!fromSubagent) startOwnTurn()
                handleAssistant(json.optJSONObject("message"), fromSubagent)
                if (!fromSubagent) updateUsage(json.optJSONObject("message")?.optJSONObject("usage"))
            }
            "user" -> handleToolResults(json.optJSONObject("message"))
            "result" -> {
                endStreaming()
                val cancelled = cancelling
                cancelling = false
                val failed = json.optBoolean("is_error")
                val subtype = json.optString("subtype")
                // Turno parado pela própria pessoa: o CLI manda error_during_execution, que não é erro para mostrar.
                // Outro erro que chegue durante o Parar (API, rede, login) continua aparecendo.
                val stoppedByUser = cancelled && subtype == "error_during_execution"
                if (failed && !stoppedByUser) {
                    val result = json.optString("result")
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
                // Turno com erro (inclusive login): a fila não segue sozinha, a pessoa revisa antes.
                // O erro de um turno que a pessoa mesma parou não conta: a fila já foi descartada no Parar.
                if (failed && !cancelled) dropQueue()
                changed()
                // Com mensagem na fila, o turno continua (busy segue true); o aviso de fim só sai no último.
                sendQueued()
            }
            "control_request" -> handleControlRequest(json)
            "control_cancel_request" -> handleControlCancel(json.optString("request_id"))
            "rate_limit_event" -> updateLimits(json.optJSONObject("rate_limit_info"))
        }
    }

    /** Guarda os limites do plano que o Claude informou (a conta toda, não só esta conversa). */
    private fun updateLimits(info: JSONObject?) {
        info ?: return
        val limits = PlanLimitsParser.fromClaude(info, System.currentTimeMillis()) ?: return
        // Evento com só uma janela (sem unifiedWindows): as outras guardadas continuam valendo.
        val old = limitsStore.load(LIMITS_AGENT_ID)
        val merged = old?.let { PlanLimitsParser.merge(it, limits) } ?: limits
        limitsStore.save(LIMITS_AGENT_ID, merged)
        warnLimits(crossedWindows(old, merged))
        changed()
    }

    /**
     * Monta a lista de tarefas em segundo plano a partir do sinal do CLI (null = nenhuma). Ignora as
     * entradas `ambient` e mostra só as de cima: a que está dentro de outra tarefa já é parte dela.
     * Mantém o que o agente fazia (activity) quando a tarefa continua na lista.
     */
    private fun updateBackground(tasks: JSONArray?) {
        val live = tasks?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }
            .orEmpty()
            .filter { !it.optBoolean("ambient") }
        val ids = live.map { it.optString("task_id") }.toSet()
        val previous = background.associateBy { it.id }
        background = live
            .filter { task ->
                val parent = task.optString("parent_task_id")
                parent.isEmpty() || parent !in ids
            }
            .map { task ->
                val id = task.optString("task_id")
                BackgroundTask(id, task.optString("description"), previous[id]?.activity)
            }
    }

    /** O que uma tarefa em segundo plano está fazendo agora (task_progress). Ignorada se não está na lista. */
    private fun updateActivity(id: String, activity: String) {
        if (activity.isEmpty() || background.none { it.id == id }) return
        background = background.map { if (it.id == id) BackgroundTask(it.id, it.description, activity) else it }
        changed(throttle = true)
    }

    /**
     * O agente começou um turno sozinho (ex.: um subagente em segundo plano terminou e ele foi avisado):
     * vira um turno como os outros, com "Trabalhando…", fila e aviso de fim (o "result" fecha).
     */
    private fun startOwnTurn() {
        if (running && !busy) busy = true
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

    /** O conjunto é por processo do CLI: ao parar, não há tarefas em segundo plano. */
    override fun onStopped() {
        background = emptyList()
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
                    // O nome do Claude já é a ferramenta ("Bash", "Read"): serve para as duas coisas.
                    tool.toolName = block.optString("name")
                    tool.mark(ToolState.PENDING, SystemClock.elapsedRealtime())
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
                it.mark(if (block.optBoolean("is_error")) ToolState.FAILED else ToolState.DONE, SystemClock.elapsedRealtime())
                it.output = ToolOutput.fromClaude(block.opt("content"))
            }
        }
        changed()
    }

    override fun onHandleFailure(json: JSONObject) {
        when (json.optString("type")) {
            // Fim do turno que falhou no meio: o turno termina (sem isso, "Trabalhando…" para sempre).
            "result" -> {
                endStreaming()
                cancelling = false
                changed()
                sendQueued()
            }
            // Pedido do CLI (permissão): responde erro, para ele não esperar uma resposta que não vem.
            "control_request" -> send(JSONObject().put("type", "control_response").put("response",
                JSONObject().put("subtype", "error").put("request_id", json.optString("request_id")).put("error", "falha no app")))
        }
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
        // Perguntas e plano têm cartão próprio; nunca são aprovados sozinhos.
        if (toolName == QUESTION_TOOL) {
            val questions = parseQuestions(input)
            if (questions.isNotEmpty()) {
                add(
                    ChatItem.Permission(
                        requestId, context.getString(R.string.chat_question_title), summary = "",
                        options = listOf(
                            PermissionOption("allow", "", PermissionOption.Kind.ALLOW_ONCE),
                            PermissionOption("deny", "", PermissionOption.Kind.REJECT_ONCE),
                        ),
                        input = input, toolName = toolName, questions = questions,
                    ),
                )
                return
            }
        }
        if (toolName == PLAN_TOOL) {
            add(
                ChatItem.Permission(
                    requestId, context.getString(R.string.chat_plan_title), summary = input.optString("plan"),
                    options = listOf(
                        PermissionOption("allow", "", PermissionOption.Kind.ALLOW_ONCE),
                        PermissionOption("deny", "", PermissionOption.Kind.REJECT_ONCE),
                    ),
                    input = input, toolName = toolName, markdown = true,
                ),
            )
            return
        }
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
        /** Id do agente Claude nas preferências de limites (igual a TerminalService.CLAUDE). */
        const val LIMITS_AGENT_ID = "claude-code"

        /** Ferramentas que executam comando: "sempre permitir" vale só para o mesmo comando. */
        private val SHELL_TOOLS = setOf("Bash", "BashOutput", "KillShell")

        fun isShellTool(name: String): Boolean = name in SHELL_TOOLS

        /** Ferramenta de lista de tarefas do Claude Code: vira um [ChatItem.Plan], não um cartão. */
        const val TODO_TOOL = "TodoWrite"

        /** Pergunta do agente com opções (cartão de perguntas). */
        const val QUESTION_TOOL = "AskUserQuestion"
        /** Plano para aprovar (cartão de plano, em Markdown). */
        const val PLAN_TOOL = "ExitPlanMode"

        /**
         * Perguntas do AskUserQuestion ({"questions":[{"question","header","multiSelect","options":[{"label","description"}]}]}).
         * Ignora pergunta sem texto ou sem opções, e opção sem rótulo. Array ausente: lista vazia.
         */
        fun parseQuestions(input: JSONObject): List<Question> {
            val array = input.optJSONArray("questions") ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val question = array.optJSONObject(i) ?: return@mapNotNull null
                val text = question.optString("question")
                if (text.isEmpty()) return@mapNotNull null
                val optionsArray = question.optJSONArray("options") ?: return@mapNotNull null
                val options = (0 until optionsArray.length()).mapNotNull { j ->
                    val option = optionsArray.optJSONObject(j) ?: return@mapNotNull null
                    val label = option.optString("label")
                    if (label.isEmpty()) null else QuestionOption(label, option.optString("description"))
                }
                if (options.isEmpty()) return@mapNotNull null
                Question(text, question.optString("header"), options, question.optBoolean("multiSelect"))
            }
        }

        /** Cópia do input original com o campo "answers" (pergunta → resposta); o original não muda. */
        fun answersInput(input: JSONObject, answers: Map<String, String>): JSONObject {
            val copy = JSONObject(input.toString())
            val map = JSONObject()
            for ((question, answer) in answers) map.put(question, answer)
            return copy.put("answers", map)
        }

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

    /**
     * Texto de cada ferramenta conhecida; null mantém o nome original. Os nomes do Claude Code vêm
     * capitalizados ("Bash"); os do ACP, minúsculos ("shell").
     */
    fun labelRes(name: String): Int? = when (name) {
        "Bash", "shell", "run_command" -> R.string.tool_bash
        "Read", "read", "read_file", "view_file" -> R.string.tool_read
        "Write", "write", "write_to_file" -> R.string.tool_write
        "Edit", "MultiEdit", "edit", "patch", "replace_file_content", "edit_file" -> R.string.tool_edit
        "NotebookEdit" -> R.string.tool_notebook
        "Glob", "glob", "list_dir", "find_by_name" -> R.string.tool_glob
        "Grep", "grep", "grep_search" -> R.string.tool_grep
        "WebFetch", "webfetch" -> R.string.tool_web_fetch
        "WebSearch", "websearch" -> R.string.tool_web_search
        "TodoWrite", "todowrite" -> R.string.tool_todo
        "Task", "Agent", "subagent" -> R.string.tool_agent
        "AskUserQuestion", "question" -> R.string.tool_question
        "ExitPlanMode" -> R.string.tool_plan
        else -> null
    }

    /** Primeiro valor não vazio entre as chaves, sem diferenciar maiúsculas (o agy manda "Path", "CommandLine"). */
    fun pick(input: JSONObject, keys: List<String>): String? {
        val byLower = input.keys().asSequence().associateBy { it.lowercase() }
        for (key in keys) {
            val real = byLower[key] ?: continue
            input.optString(real).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    fun summary(name: String, input: JSONObject?, limit: Int = 400): String {
        input ?: return ""
        if (name == ChatEngine.PLAN_TOOL) input.optString("plan").takeIf { it.isNotEmpty() }?.let { return it.take(limit) }
        if (name == ChatEngine.QUESTION_TOOL) {
            val texts = ChatEngine.parseQuestions(input).map { it.text }
            if (texts.isNotEmpty()) return texts.joinToString("\n").take(limit)
        }
        pick(input, listOf("command", "commandline", "file_path", "filepath", "path", "absolutepath", "url", "query", "pattern", "description", "prompt"))
            ?.let { return it.take(limit) }
        // O JSONObject do Android escreve "\/": desfaz, para o caminho aparecer limpo.
        return input.toString().replace("\\/", "/").take(200)
    }
}

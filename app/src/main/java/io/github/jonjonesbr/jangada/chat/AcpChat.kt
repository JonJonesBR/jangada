package io.github.jonjonesbr.jangada.chat

import android.os.SystemClock
import android.util.Log
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    linux: LinuxEnv,
    /** Comando que inicia o agente em modo ACP (ex.: "opencode acp"). */
    private val command: String,
    private val appVersion: String,
    /** Pasta de trabalho (caminho dentro do Debian). */
    override val cwd: String,
    /** Id do agente na receita (ex.: "codex"); só alguns mostram limites do plano. */
    private val agentId: String = "",
    /** Compactação automática deste trabalho (ver AutoCompact), lida a cada fim de turno. */
    private val autoCompact: () -> String = { AutoCompact.DEFAULT },
) : ProcessChat(linux, TAG) {

    /** Pode compactar sozinho de novo? Some ao compactar e volta quando o contexto cai abaixo do percentual. */
    private var autoCompactArmed = true

    private val limitsStore by lazy { PlanLimitsStore(context) }

    @Volatile override var sessionId: String? = null
        private set
    /** Só retoma se o agente sabe recarregar a sessão (loadSession); senão, a mensagem abre outra conversa. */
    override val canResume: Boolean get() = canLoad && sessionId != null
    /** Nome do modelo atual (quando o agente informa); senão, o nome do agente. */
    override val model: String?
        get() = configOptions.firstOrNull { it.category == "model" }?.currentName ?: agentTitle
    override val supportsHistory: Boolean get() = canList

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

    private val modeOption: ConfigOption?
        get() = configOptions.firstOrNull { it.source == ConfigOption.Source.MODE || it.category == "mode" }
    override val modeChoices: List<Pair<String, String>> get() = modeOption?.choices.orEmpty()
    override val currentMode: String? get() = modeOption?.current
    override fun setMode(id: String) {
        val option = modeOption ?: return
        if (option.choices.any { it.first == id }) setOption(option, id)
    }

    @Volatile var canDelete = false
        private set

    /** Comandos "/" que o agente anunciou (podem mudar durante a conversa). */
    @Volatile override var commands: List<SlashCommand> = emptyList()
        private set

    private var nextId = 1
    private val pending = HashMap<Int, (JSONObject?, JSONObject?) -> Unit>()
    private val tools = HashMap<String, ChatItem.Tool>()
    private var draft: ChatItem.Assistant? = null
    private var draftMessageId: String? = null
    private var userDraft: ChatItem.User? = null
    /** Raciocínio que está chegando (agent_thought_chunk), como o [draft] da resposta. */
    private var thoughtDraft: ChatItem.Thinking? = null
    private var plan: ChatItem.Plan? = null
    private var ready = false
    private var loadingHistory = false
    override val reloading: Boolean get() = loadingHistory
    private var canLoad = false
    private var canList = false
    private var imageSupport = false
    private var agentTitle: String? = null
    /** Jeitos de entrar na conta que o agente informou no início. */
    private var authOptions: List<AuthOption> = emptyList()
    /** Conversa a reabrir depois do login. */
    private var resumeAfterLogin: String? = null
    private var loginCard: ChatItem.Login? = null
    /** Tempo limite do login em andamento (para tirar da fila se ele terminar antes). */
    private var loginTimeout: Runnable? = null
    /** Tempo limite da resposta ao initialize (ver INIT_TIMEOUT_MS). */
    private var initTimeout: Runnable? = null
    /** Tempo limite do session/load ou session/new (ver SESSION_TIMEOUT_MS). */
    private var sessionTimeout: Runnable? = null
    /** O agente fechou sozinho e [onExited] já mostrou o motivo (vale só até o fim de [onStopped]). */
    private var exitShown = false

    /**
     * A conversa já está na tela (itens guardados): o replay do `session/load` é ignorado, para não
     * duplicar nem refazer na tela tudo o que já foi conversado. O agente ainda recarrega o contexto.
     */
    private var skipReplay = false

    /**
     * Inicia o agente; com [resume], recarrega essa conversa (se o agente suportar). [previous] é o
     * chat anterior do mesmo par: se era a mesma conversa, os itens dele voltam para a tela na hora.
     */
    fun start(resume: String? = null, previous: AgentChat? = null) {
        if (running) return
        contextUsage = null
        // Processo novo: há agentes que numeram as ações de novo ("call_1"); uma ação nova não pode
        // atualizar a antiga de mesmo id (as antigas já foram encerradas em settleItems).
        tools.clear()
        // A conversa pedida já é a deste chat: se o agente falhar ao subir, o próximo envio tenta
        // recarregar a mesma, em vez de abrir uma nova sem aviso (como no ChatEngine).
        if (resume != null) sessionId = resume
        if (resume != null && items.isEmpty() && previous != null && previous.sessionId == resume) {
            items += previous.items.toList()
            // O plano copiado continua sendo o da conversa: o próximo "plan" o atualiza, sem outro cartão.
            plan = items.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan
        }
        skipReplay = resume != null && items.isNotEmpty()
        launch(interactive = true) {
            runCatching { linux.refreshIntegration(trustFolder = cwd) }.onFailure { Log.w(TAG, "integração", it) }
            "${AgentRecipe.enterDir(cwd)} && exec ${AgentRecipe.wrap(command, jobId)}"
        }

        val capabilities = JSONObject()
            .put("fs", JSONObject().put("readTextFile", false).put("writeTextFile", false))
            .put("terminal", false)
            // O app sabe abrir o agente num terminal para o login (tela de terminal do Jangada).
            .put("auth", JSONObject().put("terminal", true))
        // Só o OpenCode manda as sessões filhas, e só quando o app diz que sabe mostrá-las.
        if (agentId == OPENCODE_ID) capabilities.put("_meta", JSONObject().put(ChildAgents.CAPABILITY, true))
        // Agente que não responde ao início: sem isso, a fila ficaria presa para sempre. Cancelado na resposta e no stop.
        val timeout = Runnable {
            initTimeout = null
            if (ready) return@Runnable
            add(ChatItem.Info(context.getString(R.string.chat_acp_init_timeout), isError = true))
            stop()
            // A mensagem enviada não vai ter resposta: avisa (a pessoa pode ter saído da tela enquanto esperava).
            attention(ChatAttention.FAILED)
        }
        initTimeout = timeout
        main.postDelayed(timeout, INIT_TIMEOUT_MS)
        request(
            "initialize",
            JSONObject().put("protocolVersion", 1).put("clientCapabilities", capabilities)
                .put("clientInfo", JSONObject().put("name", "jangada").put("title", "Jangada").put("version", appVersion)),
        ) { result, error ->
            clearInitTimeout()
            if (result == null) {
                // Parou durante o início: o motivo já aparece pela saída do agente.
                if (isStopped(error)) return@request
                add(ChatItem.Info(context.getString(R.string.chat_acp_no_init, error?.optString("message").orEmpty()), isError = true))
                // Sem initialize o agente não serve: para (senão as mensagens ficariam esperando); o próximo envio o reinicia.
                stop()
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
            authOptions = parseAuth(result.optJSONArray("authMethods"))
            openSession(resume)
        }
    }

    private fun parseAuth(methods: JSONArray?): List<AuthOption> {
        methods ?: return emptyList()
        return (0 until methods.length()).mapNotNull { i ->
            val m = methods.optJSONObject(i) ?: return@mapNotNull null
            val terminal = m.optString("type") == "terminal"
            val args = m.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            val env = m.optJSONObject("env")?.let { e -> e.keys().asSequence().associateWith { e.optString(it) } } ?: emptyMap()
            AuthOption(
                m.optString("id"), m.optString("name").ifEmpty { m.optString("id") }, m.optString("description"),
                if (terminal) AuthOption.Kind.TERMINAL else AuthOption.Kind.AGENT, args, env,
            )
        }
    }

    /** Mostra o cartão de login (uma vez) com os jeitos de entrar. */
    private fun requireLogin(resume: String?) {
        // O login espera a pessoa: o tempo limite da conversa não vale.
        clearSessionTimeout()
        resumeAfterLogin = resume
        if (loginCard?.state == ChatItem.Login.State.WAITING) return
        val options = authOptions.ifEmpty {
            listOf(AuthOption("screen", "", "", AuthOption.Kind.AGENT_SCREEN))
        }
        loginCard = ChatItem.Login(options).also { add(it) }
    }

    /** Login pedido pelo app ao agente (ele abre o navegador; o app abre o link). */
    fun login(card: ChatItem.Login, option: AuthOption) {
        if (option.kind != AuthOption.Kind.AGENT) return
        card.state = ChatItem.Login.State.WORKING
        card.message = null
        changed()
        // Sem resposta a tempo, o cartão mostra que o login não terminou.
        clearLoginTimeout()
        val timeout = Runnable {
            loginTimeout = null
            if (card.state == ChatItem.Login.State.WORKING) {
                card.state = ChatItem.Login.State.FAILED
                card.message = context.getString(R.string.chat_login_timeout)
                changed()
            }
        }
        loginTimeout = timeout
        main.postDelayed(timeout, LOGIN_TIMEOUT_MS)
        request("authenticate", JSONObject().put("methodId", option.id)) { _, error ->
            main.removeCallbacks(timeout)
            if (loginTimeout === timeout) loginTimeout = null
            // Cancelado ou já com tempo esgotado: a resposta atrasada não mexe mais no cartão.
            if (card.state != ChatItem.Login.State.WORKING) return@request
            if (error != null) {
                card.state = ChatItem.Login.State.FAILED
                card.message = error.optString("message")
                changed()
                return@request
            }
            card.state = ChatItem.Login.State.DONE
            changed()
            openSession(resumeAfterLogin)
        }
    }

    /** Cancela o login em andamento (botão Cancelar): o cartão volta a esperar. */
    fun cancelLogin(card: ChatItem.Login) {
        if (card.state != ChatItem.Login.State.WORKING) return
        clearLoginTimeout()
        card.state = ChatItem.Login.State.WAITING
        card.message = null
        changed()
    }

    private fun clearLoginTimeout() {
        loginTimeout?.let { main.removeCallbacks(it) }
        loginTimeout = null
    }

    private fun clearInitTimeout() {
        initTimeout?.let { main.removeCallbacks(it) }
        initTimeout = null
    }

    /**
     * Agente que não responde ao abrir ou recarregar a conversa: sem isso, "Recarregando conversa…"
     * ficaria na tela para sempre, sem o Parar. Cancelado quando fica pronto, no login e no stop.
     */
    private fun armSessionTimeout() {
        clearSessionTimeout()
        val timeout = Runnable {
            sessionTimeout = null
            if (ready) return@Runnable
            add(ChatItem.Info(context.getString(R.string.chat_acp_session_timeout), isError = true))
            stop()
            attention(ChatAttention.FAILED)
        }
        sessionTimeout = timeout
        main.postDelayed(timeout, SESSION_TIMEOUT_MS)
    }

    private fun clearSessionTimeout() {
        sessionTimeout?.let { main.removeCallbacks(it) }
        sessionTimeout = null
    }

    /** Comando para entrar pela tela de terminal (método "terminal" do agente). */
    fun terminalLoginScript(option: AuthOption): String {
        val exports = option.env.entries.joinToString("") { (k, v) -> "export $k=${io.github.jonjonesbr.jangada.agents.AgentRecipe.q(v)}; " }
        val args = option.args.joinToString(" ") { io.github.jonjonesbr.jangada.agents.AgentRecipe.q(it) }
        return "${AgentRecipe.enterDir(cwd)} && $exports$command $args"
    }

    private fun openSession(resume: String?) {
        armSessionTimeout()
        val params = JSONObject().put("cwd", cwd).put("mcpServers", JSONArray())
        if (resume != null && canLoad) {
            loadingHistory = true
            sessionId = resume
            // Mostra já o aviso "Recarregando conversa…" (com o replay ignorado, nada mais redesenha a tela).
            changed()
            request("session/load", params.put("sessionId", resume)) { result, error ->
                loadingHistory = false
                result?.let { parseConfig(it) }
                finishDrafts()
                if (error != null) {
                    // Parou no meio da recarga: mantém o sessionId para retomar a mesma conversa depois.
                    if (isStopped(error)) return@request
                    if (AcpParse.isAuthError(error)) {
                        requireLogin(resume)
                        return@request
                    }
                    add(ChatItem.Info(context.getString(R.string.chat_acp_reopen_failed, error.optString("message")), isError = true))
                    sessionId = null
                    newSession(forgot = true)
                    return@request
                }
                // O raciocínio e as mensagens na fila (ainda não enviadas) não contam como "parou no meio".
                interrupted = items.lastOrNull { it !is ChatItem.Thinking && (it !is ChatItem.User || !it.queued) }
                    .let { it is ChatItem.Tool || it is ChatItem.User }
                becomeReady()
            }
        } else {
            // Sem loadSession, a conversa anterior não volta: o agente começa do zero.
            newSession(forgot = resume != null)
        }
    }

    /** [forgot]: a conversa anterior ficou na tela, mas o agente novo não tem o contexto dela. */
    private fun newSession(forgot: Boolean = false) {
        val params = JSONObject().put("cwd", cwd).put("mcpServers", JSONArray())
        request("session/new", params) { result, error ->
            if (result == null) {
                if (isStopped(error)) return@request
                if (AcpParse.isAuthError(error)) {
                    requireLogin(null)
                } else {
                    add(ChatItem.Info(friendlyError(error), isError = true))
                    // Falha sem login: para o agente, para o envio não ficar parado; o próximo envio o reinicia.
                    stop()
                }
                return@request
            }
            sessionId = result.optString("sessionId")
            parseConfig(result)
            if (forgot) add(ChatItem.Info(context.getString(R.string.chat_acp_new_context)))
            becomeReady()
        }
    }

    private fun becomeReady() {
        clearSessionTimeout()
        ready = true
        changed()
        refreshLimits()
        sendNext()
    }

    /** Leitura dos limites em andamento (só a thread principal mexe): uma por vez. */
    private var limitsReading = false
    /** Pediram outra leitura durante a atual: ela roda de novo ao terminar. */
    private var limitsAgain = false

    /**
     * Codex: lê os limites do plano nas sessões que ele grava (numa thread própria, uma por vez) e
     * guarda o que achar. [delayMs] dá tempo ao Codex de gravar o fim do turno. Não faz nada para os
     * outros agentes. Roda na thread principal.
     */
    private fun refreshLimits(delayMs: Long = 0L) {
        if (agentId == OPENCODE_ID) return refreshOpenCodeLimits()
        if (agentId != CODEX_ID) return
        if (limitsReading) {
            limitsAgain = true
            return
        }
        limitsReading = true
        val sessions = File(linux.rootfs, "root/.codex/sessions")
        val started = runCatching {
            thread(name = "jangada-limites") {
                var limits: PlanLimits? = null
                try {
                    if (delayMs > 0) Thread.sleep(delayMs)
                    limits = CodexLimits.read(sessions, System.currentTimeMillis())
                } catch (e: Exception) {
                    Log.w(TAG, "limites do Codex", e)
                } finally {
                    val found = limits
                    main.post {
                        limitsReading = false
                        if (found != null) {
                            // Ler, comparar e gravar na thread principal: dois chats do Codex lendo ao mesmo
                            // tempo não avisam os dois (o segundo já compara com o que o primeiro gravou).
                            val now = System.currentTimeMillis()
                            val old = limitsStore.load(agentId)
                            limitsStore.save(agentId, found)
                            // Sessão antiga pode trazer uma janela já renovada: essa não é um aviso.
                            warnLimits(crossedWindows(old, found).filter { it.resetsAtMs == null || it.resetsAtMs > now })
                            changed()
                        }
                        // Outro fim de turno chegou durante a leitura: lê de novo (só se o chat segue aberto).
                        if (limitsAgain) {
                            limitsAgain = false
                            if (running) refreshLimits()
                        }
                    }
                }
            }
        }
        if (started.isFailure) limitsReading = false
    }

    /** Última leitura dos limites do OpenCode Go (ms; só a thread principal mexe). */
    private var openCodeLimitsAt = 0L
    /** A última leitura disse que não há login no console do OpenCode: só tenta de novo ao abrir a conversa. */
    private var openCodeNoGo = false

    /**
     * OpenCode Go: pergunta ao próprio opencode (`opencode api experimental.usage.limits`, com servidor
     * privado que sai no fim) os limites do console em que ele está logado. Abre outro opencode, então
     * no máximo uma vez a cada [OPENCODE_LIMITS_EVERY_MS], e sem login no console (data null) só de novo
     * na próxima conversa. Falha ou formato inesperado: não mostra nada. Thread principal.
     */
    private fun refreshOpenCodeLimits() {
        val now = System.currentTimeMillis()
        val first = openCodeLimitsAt == 0L
        if (limitsReading || (!first && (openCodeNoGo || now - openCodeLimitsAt < OPENCODE_LIMITS_EVERY_MS))) return
        limitsReading = true
        openCodeLimitsAt = now
        val started = runCatching {
            thread(name = "jangada-limites-opencode") {
                var limits: PlanLimits? = null
                var answered = false
                try {
                    val out = StringBuilder()
                    val code = linux.runBash(OPENCODE_LIMITS_SCRIPT) { out.append(it).append('\n') }
                    answered = code == 0
                    if (answered) limits = PlanLimitsParser.fromOpenCodeUsage(out.toString(), System.currentTimeMillis())
                } catch (e: Exception) {
                    Log.w(TAG, "limites do OpenCode", e)
                } finally {
                    val found = limits
                    val noGo = answered && found == null
                    main.post {
                        limitsReading = false
                        openCodeNoGo = noGo
                        if (found != null) {
                            val old = limitsStore.load(agentId)
                            limitsStore.save(agentId, found)
                            val at = System.currentTimeMillis()
                            warnLimits(crossedWindows(old, found).filter { it.resetsAtMs == null || it.resetsAtMs > at })
                            changed()
                        }
                    }
                }
            }
        }
        if (started.isFailure) limitsReading = false
    }

    override fun sendMessage(text: String, attachments: List<Attachment>) {
        if (!running) start(resume = sessionId)
        val item = ChatItem.User(text, attachments.map { it.name })
        add(item)
        // Turno em andamento (ou agente ainda subindo): a mensagem espera na fila, sem um segundo session/prompt.
        if (busy || !ready) {
            item.queued = true
            queue += QueuedMessage(text, attachments, item)
            busy = true
            changed()
            return
        }
        busy = true
        interrupted = false
        changed()
        prompt(text, attachments)
    }

    /** Envia a próxima mensagem da fila; sem fila, o turno acabou (só aí [busy] volta a falso). */
    private fun sendNext() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            busy = false
            // Turno acabou: redesenha já (sem isso a tela ficava em "Trabalhando…" quando nada chegava depois).
            changed()
            return
        }
        next.item.queued = false
        // Turno novo: busy segue true (sem piscar), mas o "silencioso" de um Parar anterior não vale para ele;
        // e a conversa já não está "parada no meio" (a carga do histórico pode ter marcado isso).
        busy = true
        interrupted = false
        restartTurnClock()
        prompt(next.text, next.attachments)
        changed()
    }

    private fun prompt(text: String, attachments: List<Attachment>) {
        val id = sessionId
        if (id == null) {
            // Sem sessão não há para onde enviar: mostra o erro e encerra o turno (sem ficar "pensando").
            add(ChatItem.Info(friendlyError(null), isError = true))
            dropQueue()
            busy = false
            changed()
            return
        }
        val blocks = JSONArray()
        val note = Attachments.filesNote(attachments, includeImages = !imageSupport)
        blocks.put(JSONObject().put("type", "text").put("text", (text + note).ifBlank { " " }))
        if (imageSupport) {
            for (image in attachments.filter { it.imageBase64 != null }) {
                blocks.put(JSONObject().put("type", "image").put("mimeType", image.mimeType).put("data", image.imageBase64))
            }
        }
        draft = null
        thoughtDraft = null
        request("session/prompt", JSONObject().put("sessionId", id).put("prompt", blocks)) { result, error ->
            val cancelled = cancelling
            cancelling = false
            finishDrafts()
            // O agente caiu ou foi parado no meio da resposta: a saída já foi mostrada (onExited) e a fila
            // não tem para onde ir. Descarta (os itens ficam para editar e reenviar) e não mostra outro cartão.
            if (isStopped(error)) {
                dropQueue()
                // Quem parou (stop/finish) já desligou o busy; garante para não ficar em "Trabalhando…".
                busy = false
                changed()
                return@request
            }
            // Fim do turno: o Codex gravou o que gastou, então lê os limites de novo.
            refreshLimits(LIMITS_DELAY_MS)
            // Parado pelo usuário: o agente confirma com stopReason "cancelled" e as ações em andamento
            // não terminam sozinhas. Um aviso só (o turno termina silencioso; sem ele não havia explicação).
            if (AcpParse.cancelled(result) && (cancelled || silentTurn)) {
                markRunningToolsCancelled()
                add(ChatItem.Info(context.getString(R.string.chat_interrupted)))
            }
            val refused = error == null && result?.optString("stopReason") == "refusal"
            if (error != null) {
                // Erro de login sempre mostra o cartão de login; outros erros não viram cartão se a pessoa tocou em Parar,
                // nem quando o agente fechou sozinho no meio da resposta (onExited já avisou).
                val limit = AcpParse.usageLimit(error)
                if (limit != null) add(usageLimitInfo(limit, error))
                else if (AcpParse.isAuthError(error)) requireLogin(sessionId)
                else if (!cancelled && !(isStopped(error) && exitShown)) add(ChatItem.Info(friendlyError(error), isError = true))
            }
            else if (refused) add(ChatItem.Info(context.getString(R.string.chat_acp_refused), isError = true))
            // Erro, login ou recusa: a fila não segue sozinha, a pessoa revisa antes.
            // O erro de um turno que a pessoa mesma parou não conta: a fila já foi descartada no Parar.
            if ((error != null || refused) && !cancelled) dropQueue()
            // Turno que acabou bem com o contexto cheio: o /compact entra na fila antes da próxima mensagem.
            if (!cancelled && error == null && !refused) autoCompactIfDue()
            changed()
            // O turno acabou: a próxima mensagem da fila (se houver) vai agora.
            sendNext()
        }
    }

    /**
     * Compactação automática (ver AutoCompact), no fim de um turno (thread principal). Manda /compact
     * como mensagem na fila: o turno segue, sem o aviso de fim, e o /compact vai à frente de quem
     * esperava. Só uma vez seguida: depois, espera o contexto cair abaixo do percentual.
     */
    private fun autoCompactIfDue() {
        val percentSetting = AutoCompact.percentOf(autoCompact())
        val used = contextUsage?.percent
        if (AutoCompact.rearms(percentSetting, used)) autoCompactArmed = true
        val hasCompact = commands.any { it.name == COMPACT_NAME }
        if (used == null || !AutoCompact.shouldCompact(percentSetting, used, autoCompactArmed, hasCompact, queue.isNotEmpty())) return
        autoCompactArmed = false
        add(ChatItem.Info(context.getString(R.string.chat_auto_compacting, used)))
        // Mesmo caminho de uma mensagem enviada com turno em andamento: entra na fila e sai em sendNext().
        val item = ChatItem.User(COMPACT_TEXT)
        item.queued = true
        add(item)
        queue += QueuedMessage(COMPACT_TEXT, emptyList(), item)
    }

    override fun interrupt() {
        val id = sessionId
        if (id == null || !ready) {
            // Ainda sem sessão pronta (iniciando, recarregando, esperando login ou falhou): nenhum
            // session/prompt saiu, então não há resposta que encerre o turno; solta a fila aqui.
            if (busy) {
                silentTurn = true
                dropQueue()
                busy = false
                changed()
            }
            return
        }
        if (forceStopIfStuck()) return
        // Interrompido pelo usuário: o fim desse turno não gera aviso.
        silentTurn = true
        // Pronto e ocupado = há um session/prompt em aberto; o que for enviado até a resposta dele espera.
        if (busy) cancelling = true
        // Antes do cancelamento: a resposta cancelada não pode puxar a próxima mensagem da fila.
        dropQueue()
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
        if (!running) {
            // O agente parou: não há a quem responder; enviar uma mensagem retoma a conversa.
            add(ChatItem.Info(context.getString(R.string.chat_permission_agent_stopped)))
            return
        }
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
                else add(ChatItem.Info(context.getString(R.string.chat_acp_option_failed, option.name, error.optString("message")), isError = true))
                changed()
            }
            ConfigOption.Source.MODEL -> request("session/set_model", JSONObject().put("sessionId", id).put("modelId", value)) { _, error ->
                if (error == null) option.current = value
                else add(ChatItem.Info(context.getString(R.string.chat_acp_model_failed, error.optString("message")), isError = true))
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
                    o.optString("currentValue"), AcpParse.selectChoices(o.optJSONArray("options")), ConfigOption.Source.CONFIG,
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
            if (choices.isNotEmpty()) list += ConfigOption("model", context.getString(R.string.chat_setting_model), "model", models.optString("currentModelId"), choices, ConfigOption.Source.MODEL)
        }
        result.optJSONObject("modes")?.let { modes ->
            val available = modes.optJSONArray("availableModes") ?: JSONArray()
            val choices = (0 until available.length()).mapNotNull { i ->
                available.optJSONObject(i)?.let { it.optString("id") to it.optString("name").ifEmpty { it.optString("id") } }
            }
            if (choices.isNotEmpty()) list += ConfigOption("mode", context.getString(R.string.chat_setting_mode), "mode", modes.optString("currentModeId"), choices, ConfigOption.Source.MODE)
        }
        if (list.isNotEmpty()) {
            configOptions = list
            changed()
        }
    }

    /** Lista de comandos "/" que o agente anuncia; cada anúncio substitui o anterior. */
    private fun parseCommands(update: JSONObject) {
        val list = update.optJSONArray("availableCommands") ?: return
        commands = (0 until list.length()).mapNotNull { i ->
            val c = list.optJSONObject(i) ?: return@mapNotNull null
            val name = c.optString("name").removePrefix("/")
            if (name.isEmpty()) null else SlashCommand(name, c.optString("description"))
        }
        changed()
    }

    /**
     * Conversas anteriores na pasta de projetos (se o agente oferecer).
     * [callback] recebe null quando a lista falha (ex.: agente parado): não é o mesmo que "sem conversas".
     */
    fun listSessions(callback: (List<ChatHistory.Conversation>?) -> Unit) {
        if (!canList) return callback(emptyList())
        request("session/list", JSONObject().put("cwd", cwd)) { result, _ ->
            if (result == null) return@request callback(null)
            val sessions = result.optJSONArray("sessions") ?: JSONArray()
            val list = (0 until sessions.length()).mapNotNull { i ->
                val s = sessions.optJSONObject(i) ?: return@mapNotNull null
                val updated = runCatching { java.time.Instant.parse(s.optString("updatedAt")).toEpochMilli() }.getOrDefault(0L)
                ChatHistory.Conversation(s.optString("sessionId"), s.optString("title").ifEmpty { "—" }, updated)
            }
            callback(list)
        }
    }

    /** [callback] recebe null se a conversa foi apagada; senão, o motivo da falha. */
    fun deleteSession(id: String, callback: (error: String?) -> Unit) {
        if (!canDelete) return callback(null)
        request("session/delete", JSONObject().put("sessionId", id)) { _, error ->
            if (error == null) callback(null)
            else callback(error.optString("message").ifEmpty { context.getString(R.string.chat_acp_error) })
        }
    }

    /**
     * Pedidos sem resposta e mensagens na fila morrem com o agente. Quem
     * esperava uma resposta (listar, apagar, login...) recebe a falha e conclui.
     */
    override fun onStopped() {
        val waiting = pending.values.toList()
        pending.clear()
        ready = false
        loadingHistory = false
        draft = null
        userDraft = null
        thoughtDraft = null
        clearLoginTimeout()
        clearInitTimeout()
        clearSessionTimeout()
        for (callback in waiting) callback(null, stoppedError())
        exitShown = false
        loginCard?.takeIf { it.state == ChatItem.Login.State.WORKING }?.let {
            it.state = ChatItem.Login.State.FAILED
            it.message = context.getString(R.string.chat_acp_agent_stopped)
        }
    }

    override fun onExited(code: Int, stderr: String, midTurn: Boolean) {
        // A saída já foi contada aqui: a resposta pendente que onStopped encerra não repete o aviso.
        exitShown = true
        when {
            // Agente que fecha logo no início reclamando de login: mostra o cartão de login.
            !ready && code != 0 && Regex("auth|login|sign.?in|credential", RegexOption.IGNORE_CASE).containsMatchIn(stderr) ->
                requireLogin(sessionId)
            // O cartão de login já avisou: o erro de saída não repete.
            items.lastOrNull() is ChatItem.Login -> Unit
            // Saiu bem no meio da resposta: o aviso de resposta pela metade no lugar do de saída normal.
            code == 0 && midTurn -> add(ChatItem.Info(midTurnMessage()))
            else -> add(ChatItem.Info(exitMessage(code), isError = code != 0, details = detailsOf(stderr).takeIf { code != 0 }))
        }
    }

    // ---- Mensagens do agente ----

    override fun handle(json: JSONObject) {
        val method = json.optString("method")
        val hasId = json.has("id") && !json.isNull("id")
        when {
            method.isNotEmpty() && hasId -> handleRequest(json.get("id"), method, json.optJSONObject("params"))
            method.isNotEmpty() -> when (method) {
                "session/update" -> handleUpdate(json.optJSONObject("params"))
                // Notificação das sessões filhas (subagentes), que só o OpenCode manda.
                ChildAgents.METHOD -> handleChildUpdate(json.optJSONObject("params"))
                else -> Unit
            }
            hasId -> {
                val callback = pending.remove(json.optInt("id")) ?: return
                callback(json.optJSONObject("result") ?: if (json.has("result")) JSONObject() else null, json.optJSONObject("error"))
            }
        }
    }

    override fun onHandleFailure(json: JSONObject) {
        // Pedido do agente (permissão) que falhou no meio: responde erro, para ele não esperar para sempre.
        if (json.optString("method").isNotEmpty() && json.has("id") && !json.isNull("id")) {
            send(JSONObject().put("jsonrpc", "2.0").put("id", json.get("id"))
                .put("error", JSONObject().put("code", -32603).put("message", "Internal error")))
        }
    }

    private fun handleRequest(id: Any, method: String, params: JSONObject?) {
        if (method != "session/request_permission" || params == null) {
            send(JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", -32601).put("message", "Method not found")))
            return
        }
        val toolCall = params.optJSONObject("toolCall") ?: JSONObject()
        // O agente pode repetir o mesmo pedido: fica o primeiro cartão (responder duas vezes seria erro).
        if (AcpParse.repeatedPermission(items, id)) return
        // Pedido de subagente (opencode): o id do tool e o título vêm prefixados com a filha; sem o prefixo, acha o tool.
        val childId = AcpParse.permissionChildSession(toolCall)
        val childMetaTitle = AcpParse.permissionChildTitle(toolCall)
        val toolCallId = toolCall.optString("toolCallId")
        val known = tools[toolCallId] ?: tools[AcpParse.unprefixedToolId(toolCallId, childId)]
        val rawTitle = AcpParse.withoutChildPrefix(toolCall.optString("title"), childMetaTitle)
        val title = rawTitle.ifEmpty { known?.name ?: context.getString(R.string.chat_action) }
        // O comando inteiro (sem corte): o cartão e o risco dependem dele.
        val summary = AcpParse.permissionSummary(toolCall).ifEmpty { known?.summary.orEmpty() }
        val parsed = AcpParse.permissionOptions(params.optJSONArray("options"))
        add(
            ChatItem.Permission(
                id, title, summary, parsed,
                // A ferramenta de verdade (o título é o rótulo humano).
                toolName = toolCall.optString("name"),
                diff = DiffText.fromAcpContent(toolCall.optJSONArray("content")),
                workdir = AcpParse.permissionWorkdir(toolCall),
            ).apply {
                requestedAtMs = SystemClock.elapsedRealtime()
                childSessionId = childId
                childTitle = childMetaTitle
            },
        )
    }

    private fun handleUpdate(params: JSONObject?) {
        val update = params?.optJSONObject("update") ?: return
        val kind = update.optString("sessionUpdate")
        // Replay da conversa que já está na tela: só o que muda o estado do agente (comandos, opções, contexto) vale.
        if (loadingHistory && skipReplay && kind in REPLAYED) return
        when (kind) {
            "agent_message_chunk" -> {
                userDraft = null
                thoughtDraft = null
                val text = textOf(update.optJSONObject("content")) ?: return
                val messageId = update.optString("messageId").ifEmpty { null }
                val current = draft
                // Raciocínio no meio da mesma mensagem (mesmo messageId) não parte a resposta em várias bolhas.
                val continues = current != null && (items.lastOrNull() === current ||
                    (messageId != null && messageId == draftMessageId && items.lastOrNull { it !is ChatItem.Thinking } === current))
                if (current == null || (messageId != null && messageId != draftMessageId) || !continues) {
                    current?.streaming = false
                    draft = ChatItem.Assistant(text, streaming = !loadingHistory).also { items += it }
                    draftMessageId = messageId
                } else {
                    current.text += text
                }
                changed(throttle = true)
            }
            "agent_thought_chunk" -> {
                val text = textOf(update.optJSONObject("content")) ?: return
                val current = thoughtDraft
                if (current != null && items.lastOrNull() === current) {
                    current.text += text
                } else {
                    thoughtDraft = ChatItem.Thinking(text).also { items += it }
                }
                changed(throttle = true)
            }
            "user_message_chunk" -> {
                draft?.streaming = false
                draft = null
                thoughtDraft = null
                val text = textOf(update.optJSONObject("content")) ?: return
                // Eco da mensagem que o app já mostrou (o agy devolve no turno ao vivo): não duplica.
                if (!loadingHistory && userDraft == null && items.lastOrNull { it is ChatItem.User }.let { it is ChatItem.User && it.text == text }) return
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
                thoughtDraft = null
                val id = update.optString("toolCallId")
                val title = update.optString("title")
                val tool = tools[id] ?: ChatItem.Tool(id, title, summaryOf(update)).also {
                    // O título é o rótulo humano; o nome é a ferramenta de verdade (ex.: "shell").
                    it.toolName = AcpParse.toolName(update)
                    it.kind = update.optString("kind")
                    it.mark(ToolState.PENDING, SystemClock.elapsedRealtime())
                    tools[id] = it
                    items += it
                }
                ToolOutput.fromAcp(update)?.let { tool.output = it }
                applyStatus(tool, update)
                applySubagent(tool, update)
                changed()
            }
            "tool_call_update" -> {
                val tool = tools[update.optString("toolCallId")] ?: return
                update.optString("name").takeIf { it.isNotEmpty() }?.let { tool.toolName = it }
                update.optString("title").takeIf { it.isNotEmpty() }?.let { tool.name = it }
                update.optString("kind").takeIf { it.isNotEmpty() }?.let { tool.kind = it }
                summaryOf(update).takeIf { it.isNotEmpty() }?.let { tool.summary = it }
                ToolOutput.fromAcp(update)?.let { tool.output = it }
                applyStatus(tool, update)
                applySubagent(tool, update)
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
            // Título da sessão: o app não o mostra, então nada a fazer.
            "session_info_update" -> Unit
            "config_option_update" -> parseConfig(update)
            // Janela de contexto: quanto já ocupa e quanto cabe (o agente manda a cada mudança).
            "usage_update" -> {
                val used = update.optLong("used")
                val size = update.optLong("size")
                if (used > 0 && size > 0) contextUsage = ContextUsage(used, size)
                changed()
            }
            "available_commands_update" -> parseCommands(update)
            "current_mode_update" -> {
                val mode = configOptions.firstOrNull { it.source == ConfigOption.Source.MODE || it.category == "mode" }
                update.optString("currentModeId").takeIf { it.isNotEmpty() }?.let { mode?.current = it; changed() }
            }
            "notice" -> add(ChatItem.Info(listOf(update.optString("title"), update.optString("description"))
                .filter { it.isNotEmpty() }.joinToString(": ")))
        }
    }

    /**
     * Notificação de uma sessão filha (subagente): cria o cartão dela ou aplica a mensagem, e
     * redesenha quando algo visível mudou. Na thread principal, como o resto do [handle].
     */
    private fun handleChildUpdate(params: JSONObject?) {
        params ?: return
        // Recarga da conversa que já está na tela: o replay não refaz os cartões.
        if (loadingHistory && skipReplay) return
        val now = SystemClock.elapsedRealtime()
        val id = params.optString("childSessionId")
        val card = items.firstOrNull { it is ChatItem.Child && it.sessionId == id } as? ChatItem.Child
        val visible = if (card != null) ChildAgents.apply(card, params, now) else ChildAgents.addCard(items, params, now)
        if (visible) changed(throttle = true)
    }

    /** Subagentes da conversa (cartões próprios), na faixa de status e no estado do chat. */
    override val childAgents: ChildSummary?
        get() = ChildAgents.summary(items.toList().filterIsInstance<ChatItem.Child>())

    private fun applyStatus(tool: ChatItem.Tool, update: JSONObject) {
        val now = SystemClock.elapsedRealtime()
        val state = AcpParse.toolState(update.optString("status"))
        // Status desconhecido: o estado anterior fica (agente fora do protocolo não apaga o que já veio).
        if (state != null) {
            // "failed" pode ser o próprio usuário (Parar ou recusa): o texto do agente diz qual é.
            tool.mark(if (state == ToolState.FAILED) AcpParse.failureState(update) else state, now)
        }
        // Replay de uma conversa já terminada: a ação está concluída mesmo que o agente não repita o status.
        if (loadingHistory && !tool.done) tool.mark(ToolState.DONE, now)
    }

    private fun finishDrafts() {
        draft?.streaming = false
        draft = null
        userDraft = null
        thoughtDraft = null
    }

    /** Marca como canceladas as ações ainda em andamento (o turno parou antes de elas responderem). */
    private fun markRunningToolsCancelled() {
        val now = SystemClock.elapsedRealtime()
        for (item in items) {
            if (item is ChatItem.Tool && item.state == ToolState.RUNNING) item.mark(ToolState.CANCELLED, now)
        }
    }

    private fun textOf(content: JSONObject?): String? =
        content?.takeIf { it.optString("type") == "text" }?.optString("text")

    /**
     * Ação de subagente: guarda o agente filho e a sessão filha (do rawOutput) e liga os cartões
     * dela ao tool do pai. Roda antes do changed(), para a tela já ver a ligação.
     */
    private fun applySubagent(tool: ChatItem.Tool, update: JSONObject) {
        if (tool.toolName.lowercase() !in SUBAGENT_TOOLS) return
        AcpParse.subagentAgent(update).takeIf { it.isNotEmpty() }?.let { tool.subagent = it }
        AcpParse.subagentSession(update).takeIf { it.isNotEmpty() }?.let { tool.childSessionId = it }
        ChildAgents.refresh(items)
    }

    /** Resumo curto de uma ação: o comando, o arquivo ou o endereço envolvido. */
    private fun summaryOf(call: JSONObject): String {
        call.optJSONObject("rawInput")?.let { input ->
            ToolText.summary("", input).takeIf { it.isNotEmpty() }?.let { return it }
        }
        call.optJSONArray("locations")?.optJSONObject(0)?.optString("path")?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    /** Aviso de limite do plano esgotado, com o que fazer e o link do OpenCode Go; a mensagem original vai em Detalhes. */
    private fun usageLimitInfo(limit: AcpParse.UsageLimit, error: JSONObject?): ChatItem.Info = ChatItem.Info(
        text = context.getString(
            if (limit == AcpParse.UsageLimit.GO) R.string.chat_acp_go_limit else R.string.chat_acp_free_limit,
        ),
        isError = true,
        details = error?.optString("message")?.takeIf { it.isNotBlank() },
        url = if (limit == AcpParse.UsageLimit.FREE) OPENCODE_GO_URL else null,
        urlLabel = if (limit == AcpParse.UsageLimit.FREE) context.getString(R.string.chat_acp_go_link) else null,
    )

    private fun friendlyError(error: JSONObject?): String {
        val message = error?.optString("message").orEmpty()
        val data = error?.opt("data")?.toString().orEmpty()
        // Mesmo critério de login dos outros lugares (AcpParse.isAuthError).
        return if (AcpParse.isAuthError(error)) context.getString(R.string.chat_acp_login_hint)
        else listOf(message, data).filter { it.isNotEmpty() && it != "null" }.joinToString("\n")
            .ifEmpty { context.getString(R.string.chat_acp_error) + "\n" + context.getString(R.string.chat_error_hint) }
    }

    // ---- JSON-RPC ----

    private fun request(method: String, params: JSONObject, callback: (JSONObject?, JSONObject?) -> Unit) {
        if (!running) {
            // Sem agente rodando o pedido nem sai: quem espera recebe a falha na hora.
            callback(null, stoppedError())
            return
        }
        val id = nextId++
        pending[id] = callback
        send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
    }

    /** Falha entregue a quem esperava resposta de um agente que parou (marcada com [STOPPED]). */
    private fun stoppedError(): JSONObject =
        JSONObject().put("message", context.getString(R.string.chat_acp_agent_stopped)).put(STOPPED, true)

    private fun isStopped(error: JSONObject?): Boolean = error?.optBoolean(STOPPED) == true

    private fun notify(method: String, params: JSONObject) =
        send(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))

    private fun respond(id: Any, result: JSONObject) =
        send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))

    internal companion object {
        const val TAG = "JangadaAcp"
        /** Página do OpenCode Go (assinatura), aberta pelo aviso de limite free esgotado. */
        const val OPENCODE_GO_URL = "https://opencode.ai/go"
        /** Atualizações que compõem a conversa: no replay de uma conversa já na tela, são descartadas. */
        val REPLAYED = setOf(
            "agent_message_chunk", "agent_thought_chunk", "user_message_chunk", "tool_call", "tool_call_update", "plan",
        )
        /** Id do Codex na receita: só ele lê os limites do plano pelas sessões. */
        const val CODEX_ID = "codex"
        /** Id do OpenCode na receita: lê os limites do Go pela API do próprio opencode. */
        const val OPENCODE_ID = "opencode"
        /** Ferramentas do pai que abrem um subagente (o nome do agente filho vem no rawInput delas). */
        val SUBAGENT_TOOLS = setOf("subagent", "task")
        /** Intervalo mínimo entre leituras dos limites do OpenCode Go (cada uma abre outro opencode). */
        const val OPENCODE_LIMITS_EVERY_MS = 10 * 60_000L
        /** Pergunta os limites ao opencode, com servidor próprio (sai no fim) e tempo limite. */
        const val OPENCODE_LIMITS_SCRIPT = "timeout 40 opencode api experimental.usage.limits --standalone </dev/null 2>/dev/null"
        /** Comando do agente que compacta a conversa (o mesmo do "Compactar agora") e o texto que o manda. */
        const val COMPACT_NAME = "compact"
        const val COMPACT_TEXT = "/compact"
        /** Espera depois do fim do turno antes de ler as sessões (o Codex grava o arquivo em paralelo). */
        const val LIMITS_DELAY_MS = 1_000L
        /** Quanto o cartão de login espera o navegador antes de dar o login como não terminado. */
        const val LOGIN_TIMEOUT_MS = 120_000L
        /**
         * Quanto o início espera a resposta ao initialize antes de dar o agente como preso e parar.
         * Conta desde o start: inclui a subida do proot e, no primeiro uso, agentes lentos (Hermes em
         * Python) num celular com pouca memória; folga maior, porque parar um agente lento custa mais.
         */
        const val INIT_TIMEOUT_MS = 120_000L
        /** Abrir ou recarregar a conversa: mais folga que o início (o replay de uma conversa longa demora). Var: os testes no emulador encurtam. */
        @Volatile internal var SESSION_TIMEOUT_MS = 300_000L
        /** Campo interno que marca a falha "o agente parou" (não vem do agente). */
        const val STOPPED = "jangadaStopped"
    }
}

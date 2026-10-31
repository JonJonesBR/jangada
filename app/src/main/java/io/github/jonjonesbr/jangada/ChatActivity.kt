package io.github.jonjonesbr.jangada

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import io.github.jonjonesbr.jangada.agents.AgentCatalog
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.chat.AcpChat
import io.github.jonjonesbr.jangada.chat.AgentChat
import io.github.jonjonesbr.jangada.chat.Attachment
import io.github.jonjonesbr.jangada.chat.AttachmentTooLargeException
import io.github.jonjonesbr.jangada.chat.AuthOption
import io.github.jonjonesbr.jangada.chat.Attachments
import io.github.jonjonesbr.jangada.chat.ChatEngine
import io.github.jonjonesbr.jangada.chat.ChatHistory
import io.github.jonjonesbr.jangada.chat.ChatItem
import io.github.jonjonesbr.jangada.chat.ChatView
import io.github.jonjonesbr.jangada.chat.Markdown
import io.github.jonjonesbr.jangada.chat.chatState
import io.github.jonjonesbr.jangada.chat.PermissionOption
import io.github.jonjonesbr.jangada.chat.SlashCommand
import io.github.jonjonesbr.jangada.chat.ToolText
import io.github.jonjonesbr.jangada.chat.friendlyModel
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.Git
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Workspace
import io.github.jonjonesbr.jangada.linux.Worktrees
import io.github.jonjonesbr.jangada.ui.Sheet
import io.github.jonjonesbr.jangada.ui.askWorktreeName
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.concurrent.thread

/**
 * Chat com um agente (Modo Simples): balões, permissões com botões,
 * anexos e a troca para a tela de terminal pelo botão do topo.
 * O Claude Code usa o protocolo dele; os outros agentes, o ACP.
 */
class ChatActivity : JangadaActivity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var status: View
    private lateinit var input: EditText
    private lateinit var subtitle: TextView
    private lateinit var titleBlock: View
    private lateinit var continueChip: TextView
    private lateinit var compactChip: TextView
    private lateinit var attachmentsRow: LinearLayout
    private lateinit var prepareActions: LinearLayout
    private lateinit var sendButton: View
    private lateinit var settings: AppSettings
    private lateinit var linux: LinuxEnv
    private lateinit var agentId: String
    private var recipe: AgentRecipe? = null
    private val adapter = ChatAdapter()
    private val handler = Handler(Looper.getMainLooper())

    private var service: TerminalService? = null
    /** A tela está ligada ao serviço (bindService feito e ainda não desfeito). */
    private var bound = false
    /** A tela está iniciada (entre onStart e onStop). */
    private var started = false
    /** A tela está em primeiro plano (entre onResume e onPause). */
    private var resumed = false
    /** Turno em andamento na última leitura da conversa (para ver o fim do turno). */
    private var lastBusy = false
    /** Primeira leitura depois de [attach]: só guarda o estado, sem anunciar nada. */
    private var primed = false
    /** Pedidos de permissão já vistos nesta conversa (pela identidade do item). */
    private val seenPermissions: MutableSet<ChatItem> = Collections.newSetFromMap(IdentityHashMap<ChatItem, Boolean>())
    /** Erros da conversa já vistos (pela identidade do item), como os pedidos acima. */
    private val seenErrors: MutableSet<ChatItem> = Collections.newSetFromMap(IdentityHashMap<ChatItem, Boolean>())
    /** Um pedido de abrir o chat (feito ao ligar no serviço) espera o adaptador ficar pronto. */
    private var opening = false
    /** As pastas dos ajustes estão sendo lidas (evita abrir o diálogo duas vezes). */
    private var loadingSettings = false
    /** O trabalho isolado desta tela está sendo tirado: os chats da pasta fecham e não reabrem. */
    private var removingWorktree = false
    /** Remoção pedida sem o serviço ligado (tela parada): roda quando ele voltar. */
    private var pendingRemoval: Pair<WorktreeJob, Boolean>? = null
    /** Diálogo "já há agentes demais" aberto (evita abrir dois). */
    private var jobsFullDialog: Dialog? = null
    /** Pasta do trabalho desta tela (nome dentro de ~/projetos; "" = a raiz); validada em onCreate. */
    private var folder = ""
    private var engine: AgentChat? = null
    private var enterAt = -1
    private val pendingAttachments = mutableListOf<Attachment>()
    /** Texto pedido por [EXTRA_SEND] (ex.: pedir o commit), enviado quando o chat deste trabalho estiver ligado. */
    private var pendingSend: String? = null
    /** Seletor de arquivos do sistema (vários arquivos, qualquer tipo). */
    private val attachPicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) prepareAttachments(uris)
    }

    /** Adaptador ACP ainda não pronto (instalando, ou falhou): campo de texto e Enviar desligados. */
    private var preparing = false

    /** Sugestões de comandos "/" do agente, acima da caixa de texto. */
    private var commandPopup: ListPopupWindow? = null
    /** Comandos que o popup mostra agora (mesma ordem da lista). */
    private var commandMatches: List<SlashCommand> = emptyList()

    private val isClaude get() = agentId == TerminalService.CLAUDE
    private val agentName get() = recipe?.name ?: getString(R.string.chat_title)
    /** Pasta de trabalho do chat (caminho dentro do Debian). */
    private val chatCwd: String get() = engine?.cwd ?: Workspace.path(folder)
    /** Chave do trabalho desta tela (agente + pasta), como no serviço. */
    private val chatKey: String get() = TerminalService.chatKey(agentId, folder)
    /** Nomes dos agentes pelo id (o catálogo é lido uma vez, para as linhas de trabalho). */
    private val agentNames by lazy { AgentCatalog.load(this).associate { it.id to it.name } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        settings = AppSettings(this)
        linux = LinuxEnv(this)
        agentId = intent.getStringExtra(EXTRA_AGENT_ID) ?: TerminalService.CLAUDE
        recipe = AgentCatalog.load(this).firstOrNull { it.id == agentId }

        list = findViewById(R.id.chat_list)
        empty = findViewById(R.id.chat_empty)
        status = findViewById(R.id.chat_status)
        input = findViewById(R.id.chat_input)
        subtitle = findViewById(R.id.chat_subtitle)
        titleBlock = findViewById(R.id.chat_title_block)
        continueChip = findViewById(R.id.chat_continue)
        compactChip = findViewById(R.id.chat_compact)
        attachmentsRow = findViewById(R.id.chat_attachments)
        prepareActions = findViewById(R.id.chat_prepare_actions)
        sendButton = findViewById(R.id.chat_send)
        list.adapter = adapter
        // Pasta do trabalho: a pedida (terminal, lista de trabalhos, compartilhar) ou a última usada.
        // Pasta que não existe mais (apagada fora do app) ou que virou link volta para a raiz.
        val requested = intent.getStringExtra(EXTRA_FOLDER) ?: settings.chatFolder
        intent.removeExtra(EXTRA_FOLDER)
        folder = Workspace.existing(linux.rootfs, requested)
        updateTitle()
        empty.text = getString(R.string.chat_empty, agentName)
        input.hint = getString(R.string.chat_hint_agent, agentName)

        // Pedido de envio (Mudanças). Girar a tela não repete o extra: vale o que ficou guardado.
        pendingSend = if (savedInstanceState != null) {
            savedInstanceState.getString(STATE_PENDING_SEND)
        } else {
            intent.getStringExtra(EXTRA_SEND)
        }
        intent.removeExtra(EXTRA_SEND)
        findViewById<View>(R.id.chat_home).setOnClickListener { goHome() }
        sendButton.setOnClickListener { send() }
        findViewById<View>(R.id.chat_attach).setOnClickListener { pickAttachment() }
        findViewById<View>(R.id.chat_stop).setOnClickListener { engine?.interrupt() }
        findViewById<View>(R.id.chat_terminal).setOnClickListener { switchToTerminal() }
        findViewById<View>(R.id.chat_jobs).setOnClickListener { showJobs() }
        findViewById<View>(R.id.chat_new).setOnClickListener { newConversation() }
        findViewById<View>(R.id.chat_history).setOnClickListener { showHistory() }
        continueChip.setOnClickListener { engine?.sendMessage(getString(R.string.chat_continue_prompt)) }
        compactChip.setOnClickListener { engine?.sendMessage("/compact") }
        // Título e subtítulo formam um só alvo de toque (ajustes da conversa).
        findViewById<View>(R.id.chat_title_block).setOnClickListener { showChatSettings() }
        onBackPressedDispatcher.addCallback(this) { goHome() }

        // "Enter envia" (configurações): o Enter do teclado manda a mensagem.
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                enterAt = if (count == 1 && before == 0 && s?.getOrNull(start) == '\n') start else -1
            }
            override fun afterTextChanged(s: Editable?) {
                val at = enterAt
                enterAt = -1
                if (at >= 0 && settings.enterSends && s != null && at < s.length) {
                    s.delete(at, at + 1)
                    send()
                }
                updateCommandSuggestions()
            }
        })
        takeShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pedido (aviso, por exemplo) de outro trabalho com esta tela no topo: como no ⇄, abre a
        // tela dele e sai desta, para não empilhar telas de chat. Os extras vão junto.
        val otherAgent = intent.getStringExtra(EXTRA_AGENT_ID) ?: agentId
        val otherFolder = intent.getStringExtra(EXTRA_FOLDER)?.let { Workspace.existing(linux.rootfs, it) } ?: folder
        if (otherAgent != agentId || otherFolder != folder) {
            if (isFinishing) return
            startActivity(
                Intent(intent)
                    .setClass(this, ChatActivity::class.java)
                    .setFlags(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(EXTRA_FOLDER, otherFolder),
            )
            finish()
            overridePendingTransition(0, 0)
            return
        }
        // Mesmo trabalho: a pasta já é a desta tela.
        intent.removeExtra(EXTRA_FOLDER)
        setIntent(intent)
        // O pedido de envio vale só para este trabalho (a troca de trabalho acima leva o extra junto).
        intent.getStringExtra(EXTRA_SEND)?.let { pendingSend = it }
        intent.removeExtra(EXTRA_SEND)
        takeShared(intent)
        flushPendingSend()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_SEND, pendingSend)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        service?.visibleChat = chatKey
        applyTextScale()
        // Ao voltar com "/..." na caixa, as sugestões reaparecem (depois da janela estar pronta).
        input.post { updateCommandSuggestions() }
    }

    override fun onPause() {
        resumed = false
        // Só larga o aviso se ele ainda for o deste trabalho.
        if (service?.visibleChat == chatKey) service?.visibleChat = null
        dismissCommandSuggestions()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        started = true
        val intent = Intent(this, TerminalService::class.java)
        startService(intent)
        // Mesmo quando bindService devolve false, a ligação fica registrada e precisa do unbindService.
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    override fun onStop() {
        started = false
        engine?.onChanged = null
        // Sempre desfaz o bind feito em onStart, mesmo que o serviço ainda não tenha respondido.
        if (bound) {
            bound = false
            runCatching { unbindService(connection) }
        }
        service = null
        super.onStop()
    }

    override fun onDestroy() {
        // Nada que esperava a tela (instalação, por exemplo) roda depois que ela sai.
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            // Pode chegar depois de a tela ter parado: nesse caso, só desfaz o bind.
            if (!started) {
                if (bound) {
                    bound = false
                    runCatching { unbindService(this) }
                }
                return
            }
            val svc = (binder as TerminalService.LocalBinder).service
            service = svc
            // Se a tela já estava em primeiro plano quando o serviço chegou, o onResume não pôde avisar.
            if (resumed) svc.visibleChat = chatKey
            // Tirando o trabalho isolado: não reabre o chat da pasta que vai sumir. Uma remoção que
            // esperava o serviço (a tela estava parada) começa agora.
            if (removingWorktree) {
                pendingRemoval?.let { (job, force) ->
                    pendingRemoval = null
                    removeWorktree(svc, job, force)
                }
                return
            }
            // Um pedido anterior (de antes de a tela parar e voltar) ainda espera o adaptador: ele abre o chat.
            if (opening) return
            val resume = intent.getStringExtra(EXTRA_RESUME)
            val fresh = intent.getBooleanExtra(EXTRA_NEW, false)
            intent.removeExtra(EXTRA_RESUME)
            intent.removeExtra(EXTRA_NEW)
            // O chat aberto deste agente e pasta é reaproveitado.
            val current = svc.chat(agentId, folder)
            when {
                resume != null -> openWhenReady {
                    withRoom(svc) { attach(svc.openChat(agentId, folder = folder, resume = resume, restart = true)) }
                }
                fresh -> openWhenReady { withRoom(svc) { attach(svc.openChat(agentId, folder = folder, restart = true)) } }
                current != null -> attach(current)
                else -> openWhenReady {
                    withRoom(svc) {
                        // No Claude, continua a conversa mais recente (se houver; o chat confere fora da thread principal).
                        // Com o terminal do Claude aberto, ele pode estar gravando essa mesma conversa: abre uma nova.
                        // Cada pasta tem as próprias conversas: só conta o terminal da mesma pasta.
                        val terminalBusy = isClaude && svc.hasClaudeTerminal(folder)
                        if (terminalBusy) Toast.makeText(this@ChatActivity, R.string.chat_terminal_busy_new, Toast.LENGTH_LONG).show()
                        attach(svc.openChat(agentId, folder = folder, continueLast = isClaude && !terminalBusy))
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /**
     * Abre o chat ao ligar no serviço, depois do adaptador pronto. Se a tela
     * para e volta durante a preparação, a nova ligação não cria um segundo
     * pedido: o primeiro (que pode ser uma conversa a retomar) segue valendo.
     */
    private fun openWhenReady(open: () -> Unit) {
        opening = true
        ensureAdapter {
            opening = false
            open()
        }
    }

    /**
     * Roda [open] se este trabalho pode abrir. Um par que já tem chat sempre pode; um novo
     * precisa de vaga. Sem vaga, mostra os trabalhos abertos para fechar um antes.
     */
    private fun withRoom(svc: TerminalService, open: () -> Unit) {
        // A pasta do trabalho isolado está saindo: nenhum chat novo nela.
        if (removingWorktree) return
        if (svc.canOpenChat(agentId, folder)) open() else showJobsFull(svc, open)
    }

    /**
     * Já há [TerminalService.MAX_CHATS] agentes rodando. Tocar num deles o fecha e então
     * [open] roda. Cancelar (ou voltar) sai desta tela se ela não tem conversa para mostrar.
     */
    private fun showJobsFull(svc: TerminalService, open: () -> Unit) {
        // A tela que para e volta pede de novo: um painel só.
        if (jobsFullDialog?.isShowing == true) return
        // Só os que rodam: um trabalho parado não ocupa vaga.
        val rows = svc.runningChats().map { slot ->
            Sheet.Row(
                title = jobName(agentNameOf(slot.agentId), slot.folder),
                value = stateText(this, chatState(slot.chat)),
                description = getString(R.string.sheet_desc_close_job),
                destructive = true,
            ) {
                svc.closeChat(slot.agentId, slot.folder) {
                    if (!isDestroyed) open()
                }
            }
        }
        // Cancelar, voltar ou tocar fora (sem escolher trabalho) sai desta tela se ela não tem conversa.
        jobsFullDialog = Sheet(this)
            .title(getString(R.string.chat_jobs_full_title, TerminalService.MAX_CHATS))
            .message(getString(R.string.chat_jobs_full))
            .rows(rows)
            .onDismiss { if (engine == null) finish() }
            .show()
    }

    /** Nome do agente pelo id (para as linhas de trabalho); o catálogo é lido uma vez. */
    private fun agentNameOf(id: String): String = agentNames[id] ?: getString(R.string.chat_title)

    /**
     * Alguns agentes falam ACP por um adaptador separado (Codex, Pi). Na
     * primeira vez, ele é instalado aqui mesmo, com aviso na tela. Se outra
     * tela já estiver instalando o mesmo adaptador, esta só espera o fim.
     */
    private fun ensureAdapter(then: () -> Unit) {
        // Cada pedido roda o que vem depois uma vez só, e nunca depois de a tela ser destruída
        // (a tela pode parar e voltar durante a instalação, e as esperas se cruzariam).
        var ran = false
        val once: () -> Unit = {
            if (!ran && !isDestroyed) {
                ran = true
                then()
            }
        }
        val spec = recipe?.acp
        val bin = spec?.bin
        val script = spec?.installScript()
        // bin pode ser um nome (instalado em /usr/local/bin) ou um caminho absoluto no Linux.
        val binFile = bin?.let { if (it.startsWith("/")) File(linux.rootfs, it.removePrefix("/")) else File(linux.rootfs, "usr/local/bin/$it") }
        if (isClaude || binFile == null || script == null || binFile.exists()) {
            once()
            return
        }
        showPreparing()
        if (!startInstall()) {
            waitForInstall {
                showReady()
                ensureAdapter(once)
            }
            return
        }
        val id = agentId
        thread(name = "acp-adapter") {
            val log = StringBuilder()
            val code = try {
                runCatching { linux.runBash(script) { line -> log.append(line).append('\n') } }.getOrDefault(-1)
            } finally {
                finishInstall(id)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (code == 0) {
                    showReady()
                    once()
                } else {
                    showPrepareFailure(log.toString(), once)
                }
            }
        }
    }

    /** Adaptador em instalação: texto de "preparando" e campo de texto desligado. */
    private fun showPreparing() {
        setPreparing(true)
        empty.visibility = View.VISIBLE
        list.visibility = View.GONE
        prepareActions.visibility = View.GONE
        empty.text = getString(R.string.chat_preparing, agentName)
    }

    /** Adaptador pronto (ou já instalado): volta ao estado normal da tela. */
    private fun showReady() {
        setPreparing(false)
        prepareActions.visibility = View.GONE
        empty.text = getString(R.string.chat_empty, agentName)
    }

    /** Falha: últimas linhas do log, com Tentar de novo e Detalhes. O campo segue desligado até dar certo. */
    private fun showPrepareFailure(log: String, then: () -> Unit) {
        setPreparing(true)
        empty.visibility = View.VISIBLE
        list.visibility = View.GONE
        empty.text = getString(R.string.chat_prepare_failed, agentName) + "\n\n" + log.lines().takeLast(6).joinToString("\n")
        prepareActions.visibility = View.VISIBLE
        findViewById<Button>(R.id.chat_prepare_retry).setOnClickListener { ensureAdapter(then) }
        findViewById<Button>(R.id.chat_prepare_details).setOnClickListener { showPrepareDetails(log) }
    }

    /** Log completo da instalação, em texto selecionável (para copiar e mandar para o suporte). */
    private fun showPrepareDetails(log: String) = showDetails(
        R.string.chat_prepare_details,
        log.trimEnd().ifBlank { getString(R.string.chat_prepare_failed, agentName) },
    )

    /** Texto longo (log ou erro do agente) numa caixa que se pode selecionar e copiar. */
    private fun showDetails(title: Int, body: String) {
        val view = TextView(this).apply {
            text = body
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(getColor(R.color.setup_text))
            setTextIsSelectable(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Marca de uma ação: … em andamento, ✕ se falhou, ✓ se terminou. */
    private fun toolMark(tool: ChatItem.Tool) = when {
        !tool.done -> "…"
        tool.failed -> "✕"
        else -> "✓"
    }

    /** Linha de uma ação: marca, rótulo e o comando ou arquivo envolvido. */
    private fun toolLineText(tool: ChatItem.Tool) = "${toolMark(tool)} ${ToolText.label(this, tool.name)}  ${tool.summary}"

    /** Modo Resumo: as ações do grupo, uma por linha; a que tem saída abre num toque. */
    private fun showToolGroup(item: ChatItem.ToolGroup) {
        // Cada ação: rótulo e resumo, com a marca (…, ✕, ✓) como ícone; a que tem saída abre num toque.
        val rows = item.tools.map { tool ->
            Sheet.Row(
                title = ToolText.label(this, tool.name) + "  " + tool.summary,
                icon = toolMark(tool),
                enabled = tool.output != null,
            ) {
                tool.output?.let { showDetails(R.string.chat_tool_output, "${toolLineText(tool)}\n\n$it") }
            }
        }
        Sheet(this).title(getString(R.string.chat_tool_group_title)).rows(rows).show()
    }

    private fun setPreparing(on: Boolean) {
        preparing = on
        input.isEnabled = !on
        sendButton.isEnabled = !on
        sendButton.alpha = if (on) 0.4f else 1f
    }

    /** Marca o adaptador deste agente como em instalação; false se outra tela já a está fazendo. */
    private fun startInstall(): Boolean = synchronized(installing) { installing.add(agentId) }

    private fun finishInstall(id: String) {
        synchronized(installing) { installing.remove(id) }
    }

    private fun isInstalling(): Boolean = synchronized(installing) { agentId in installing }

    /** Confere a cada segundo até a outra instalação terminar, e então segue. */
    private fun waitForInstall(then: () -> Unit) {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isDestroyed) return
                if (isInstalling()) handler.postDelayed(this, 1000) else then()
            }
        }, 1000)
    }

    private fun attach(chat: AgentChat) {
        engine = chat
        // A última pasta usada é a deste trabalho (a tela só abre trabalhos da própria pasta).
        settings.chatFolder = folder
        // O aviso deste trabalho fica calado enquanto a tela o mostra (a pasta pode ter acabado de mudar).
        if (resumed) service?.visibleChat = chatKey
        updateTitle()
        // Parada, a tela não se registra no chat (o onStop já desfez isso); ao voltar, o onStart a liga de novo.
        if (started) chat.onChanged = { refresh() }
        // Nova ligação com a conversa: o próximo redesenho só guarda o estado (ver announceChanges).
        primed = false
        seenPermissions.clear()
        seenErrors.clear()
        refresh()
        input.requestFocus()
        flushPendingSend()
    }

    /**
     * Envia o pedido de [EXTRA_SEND] pelo mesmo caminho do botão Enviar, quando o chat deste
     * trabalho está ligado. Com texto já digitado (ou anexos) na caixa, o pedido vai para o fim dela,
     * em linha nova, e não é enviado.
     */
    private fun flushPendingSend() {
        val text = pendingSend ?: return
        if (engine == null || service == null || preparing) return
        pendingSend = null
        val typed = input.text.toString().trimEnd()
        // Texto digitado ou anexos pendentes: o pedido não pode levá-los junto ao agente sem o usuário ver.
        if (typed.isNotBlank() || pendingAttachments.isNotEmpty()) {
            input.setText(if (typed.isBlank()) text else typed + "\n" + text)
            input.setSelection(input.text.length)
            return
        }
        input.setText(text)
        input.setSelection(input.text.length)
        send()
    }

    /** Título: o agente e, numa subpasta, a pasta (repositório) em que ele trabalha. */
    private fun updateTitle() {
        findViewById<TextView>(R.id.chat_title).text = if (folder.isEmpty()) agentName else "$agentName · $folder"
    }

    /**
     * Anúncios para o leitor de tela e vibração: o turno terminou (busy passou de true para false),
     * apareceu um pedido de permissão novo ou um erro novo. Só com a tela em primeiro plano; o que
     * chegou em segundo plano ou já estava na conversa ao ligar a tela conta como visto, sem anúncio.
     * Parar, erro e login não contam como resposta pronta. Vários pedidos de uma vez: só o primeiro.
     */
    private fun announceChanges(chat: AgentChat) {
        val finished = lastBusy && !chat.busy
        lastBusy = chat.busy
        val items = chat.items.toList()
        val freshPermission = items.filterIsInstance<ChatItem.Permission>()
            .filter { it.chosen == null }.filter { seenPermissions.add(it) }.firstOrNull()
        val freshError = items.filterIsInstance<ChatItem.Info>().filter { it.isError }.filter { seenErrors.add(it) }.firstOrNull()
        if (!primed) {
            primed = true
            return
        }
        if (!resumed) return
        val messages = mutableListOf<String>()
        if (finished) {
            val last = items.lastOrNull()
            val failed = (last is ChatItem.Info && last.isError) || last is ChatItem.Login || chat.turnSilenced
            if (!failed) {
                messages += getString(R.string.chat_announce_finished)
                if (settings.hapticOnFinish) {
                    list.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
                    )
                }
            }
        }
        freshError?.let { error -> messages += error.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty() }
        freshPermission?.let { request ->
            val what = listOf(request.title, request.summary).filter { it.isNotBlank() }.joinToString(": ")
            messages += getString(R.string.chat_announce_permission, what)
        }
        // Uma só fala: dois anúncios seguidos fariam o segundo cortar o primeiro.
        val text = messages.filter { it.isNotBlank() }.joinToString(". ")
        if (text.isNotEmpty()) list.announceForAccessibility(text)
    }

    /** Tamanho do texto (ajustes do chat): a caixa e as linhas da conversa seguem a escolha. */
    private fun applyTextScale() {
        val scale = settings.chatTextScale
        input.textSize = INPUT_TEXT_SP * scale
        if (adapter.chatScale != scale) {
            adapter.chatScale = scale
            adapter.notifyDataSetChanged()
        }
    }

    /** Caminho para mostrar ao usuário: "~/projetos/jangada" em vez de "/root/projetos/jangada". */
    private fun displayPath(cwd: String) = cwd.replaceFirst("/root/", "~/")

    private fun refresh() {
        val chat = engine ?: return
        val mode = settings.chatView
        adapter.viewMode = mode
        adapter.chatScale = settings.chatTextScale
        // A lista mostra uma cópia; assim ela nunca vê a conversa mudar
        // "por baixo" entre um redesenho e outro (o que derrubava o app).
        adapter.snapshot = ArrayList(ChatView.arrange(chat.items, mode))
        adapter.notifyDataSetChanged()
        announceChanges(chat)
        // Vazia é o que a tela mostra (só raciocínio, no Normal, não deve deixar a lista em branco).
        val isEmpty = adapter.snapshot.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
        status.visibility = if (chat.busy) View.VISIBLE else View.GONE
        continueChip.visibility = if (chat.interrupted && !chat.busy) View.VISIBLE else View.GONE
        // Uso do contexto (quando o agente informa): entre o modelo e a seta dos ajustes.
        val usage = chat.contextUsage?.let { " · " + getString(R.string.chat_context_percent, it.percent) } ?: ""
        subtitle.text = if (isClaude) {
            val effort = settings.chatEffort.takeIf { it.isNotEmpty() }?.let { " · " + effortLabel(it) } ?: ""
            (friendlyModel(chat.model) ?: getString(R.string.chat_model_default)) + effort + usage + " ▾"
        } else {
            val acp = chat as? AcpChat
            val options = acp?.configOptions.orEmpty()
            val effort = options.firstOrNull { it.category == "thought_level" }?.let { " · " + it.currentName } ?: ""
            // O diálogo de ajustes sempre abre (tem ao menos a visualização).
            (chat.model ?: displayPath(chat.cwd)) + effort + usage + " ▾"
        }
        // Contexto quase cheio: oferece compactar (só se o agente aceita e não está no meio de um turno).
        val percent = chat.contextUsage?.percent ?: 0
        val canCompact = isClaude || (chat as? AcpChat)?.commands?.any { it.name == "compact" } == true
        // Um chip por vez: "Continuar" vem antes (a resposta parou no meio); compactar aparece no fim do próximo turno.
        val showCompact = percent >= COMPACT_PERCENT && !chat.busy && canCompact && continueChip.visibility != View.VISIBLE
        compactChip.visibility = if (showCompact) View.VISIBLE else View.GONE
        // O leitor de tela lê agente e modelo, e avisa que o toque abre os ajustes.
        titleBlock.contentDescription = getString(
            R.string.chat_title_description, agentName, subtitle.text.toString().removeSuffix(" ▾"), getString(R.string.chat_settings_title),
        )
        // Comandos que o agente acabou de anunciar também podem entrar na sugestão.
        updateCommandSuggestions()
    }

    // ---- Sugestões de comandos "/" (agentes ACP) ----

    /**
     * Mostra até 6 comandos do agente que começam como o texto digitado, enquanto o
     * texto começa com "/" e não tem espaço. Fecha quando não há o que sugerir.
     */
    private fun updateCommandSuggestions() {
        val text = input.text.toString()
        val acp = engine as? AcpChat
        // Só com a tela em primeiro plano: em segundo plano o popup fica para o próximo onResume.
        val matches = if (resumed && acp != null && text.startsWith("/") && text.none { it.isWhitespace() } && input.width > 0) {
            val query = text.drop(1)
            acp.commands.filter { it.name.startsWith(query, ignoreCase = true) }.take(COMMAND_SUGGESTIONS)
        } else {
            emptyList()
        }
        if (matches.isEmpty()) {
            dismissCommandSuggestions()
            return
        }
        val popup = commandPopup ?: ListPopupWindow(this).also {
            it.setAnchorView(input)
            // Não modal (não rouba o foco): a pessoa continua digitando na caixa.
            it.isModal = false
            // Fica junto da caixa de texto, nunca por cima do teclado.
            it.inputMethodMode = ListPopupWindow.INPUT_METHOD_NEEDED
            it.setBackgroundDrawable(getDrawable(R.drawable.card_bg))
            it.setOnItemClickListener { _, _, position, _ -> applyCommand(commandMatches[position]) }
            commandPopup = it
        }
        // Se a lista já está aberta com esses mesmos comandos, não redesenha.
        if (popup.isShowing && matches == commandMatches) return
        commandMatches = matches
        popup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, matches.map { commandLabel(it) }))
        popup.width = input.width
        // Altura fixa e deslocada para cima: o popup fica sobre a caixa, não embaixo (teclado).
        val height = matches.size * dp(COMMAND_ROW_DP)
        popup.height = height
        popup.setVerticalOffset(-(input.height + height))
        popup.show()
    }

    private fun commandLabel(command: SlashCommand): String =
        if (command.description.isBlank()) "/${command.name}" else "/${command.name} — ${command.description}"

    /** Toque numa sugestão: preenche "/nome " na caixa (o espaço fecha o popup). */
    private fun applyCommand(command: SlashCommand) {
        dismissCommandSuggestions()
        input.setText("/${command.name} ")
        input.setSelection(input.text.length)
    }

    private fun dismissCommandSuggestions() {
        commandPopup?.dismiss()
        commandMatches = emptyList()
    }

    private fun send() {
        dismissCommandSuggestions()
        if (preparing) return
        val text = input.text.toString().trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        val svc = service ?: return
        val running = engine?.takeIf { it.running }
        if (running != null) {
            deliver(running)
            return
        }
        // Chat parado: só abre depois que o adaptador estiver pronto (instala na primeira vez).
        // O texto fica no campo até lá; se a instalação falhar, nada se perde.
        ensureAdapter {
            if (isDestroyed) return@ensureAdapter
            // Trabalho novo precisa de vaga; o que já existe (parado) é reaberto.
            withRoom(svc) {
                // A conversa parada só é retomada na pasta dela.
                val resume = engine?.takeIf { it.cwd == Workspace.path(folder) }?.sessionId
                val chat = engine?.takeIf { it.running }
                    ?: svc.openChat(agentId, folder = folder, resume = resume, restart = true).also { attach(it) }
                deliver(chat)
            }
        }
    }

    /** Manda o texto e os anexos pendentes para o chat e limpa o campo. */
    private fun deliver(chat: AgentChat) {
        val text = input.text.toString().trim()
        val files = pendingAttachments.toList()
        input.setText("")
        pendingAttachments.clear()
        renderAttachments()
        chat.sendMessage(text, files)
    }

    // ---- Anexos ----

    private fun pickAttachment() {
        runCatching { attachPicker.launch("*/*") }
    }

    /** Prepara os arquivos fora da thread principal e os põe na fila de anexos. */
    private fun prepareAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        thread(name = "attachments") {
            val failures = mutableListOf<Throwable>()
            val prepared = uris.mapNotNull { uri ->
                runCatching { Attachments.prepare(this, linux, uri) }.onFailure { failures += it }.getOrNull()
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                when {
                    failures.any { it is AttachmentTooLargeException } -> Toast.makeText(
                        this, getString(R.string.chat_attach_too_large, Attachments.MAX_ATTACHMENT_BYTES / (1024 * 1024)), Toast.LENGTH_LONG
                    ).show()
                    prepared.size < uris.size -> Toast.makeText(this, R.string.chat_attach_failed, Toast.LENGTH_SHORT).show()
                }
                pendingAttachments += prepared
                renderAttachments()
            }
        }
    }

    /**
     * Conteúdo vindo de "Compartilhar" (pela ShareActivity): o texto vai para o
     * campo, sem enviar, e os arquivos viram anexos. Os extras são retirados do
     * Intent para não repetir ao girar a tela.
     */
    private fun takeShared(source: Intent) {
        val text = source.getStringExtra(EXTRA_SHARED_TEXT)
        @Suppress("DEPRECATION")
        val uris = source.getParcelableArrayListExtra<Uri>(EXTRA_SHARED_URIS)
        source.removeExtra(EXTRA_SHARED_TEXT)
        source.removeExtra(EXTRA_SHARED_URIS)
        if (!text.isNullOrBlank()) {
            val current = input.text.toString().trimEnd()
            input.setText(if (current.isBlank()) text.trim() else current + "\n" + text.trim())
            input.setSelection(input.text.length)
        }
        if (!uris.isNullOrEmpty()) prepareAttachments(uris)
    }

    private fun renderAttachments() {
        attachmentsRow.removeAllViews()
        (attachmentsRow.parent as View).visibility = if (pendingAttachments.isEmpty()) View.GONE else View.VISIBLE
        for (attachment in pendingAttachments.toList()) {
            attachmentsRow.addView(TextView(this).apply {
                text = (if (attachment.imageBase64 != null) "🖼 " else "📎 ") + attachment.name + "  ✕"
                contentDescription = getString(R.string.chat_remove_attachment, attachment.name)
                setTextColor(getColor(R.color.setup_text))
                textSize = 13f
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(48)
                setBackgroundResource(R.drawable.card_bg)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(6) }
                setOnClickListener {
                    pendingAttachments.remove(attachment)
                    renderAttachments()
                }
            })
        }
    }

    // ---- Conversas ----

    private fun newConversation() {
        val svc = service ?: return
        reopenChat(svc) { svc.openChat(agentId, folder = folder, restart = true) }
    }

    /**
     * Fecha o chat deste trabalho, espera o agente sair e liga a tela no que [open] abrir.
     * Com o agente parado, reabrir soma um rodando: precisa de vaga.
     */
    private fun reopenChat(svc: TerminalService, open: () -> AgentChat) {
        withRoom(svc) {
            svc.closeChat(agentId, folder) {
                if (!isDestroyed) attach(open())
            }
        }
    }

    /** Conversas anteriores: toque para retomar, 🗑 para apagar (a aberta já vira uma nova). */
    private fun showHistory() {
        val chat = engine
        if (isClaude) {
            val cwd = chatCwd
            thread(name = "chat-history") {
                val found = ChatHistory.list(linux, cwd)
                runOnUiThread { if (!isDestroyed) showHistoryDialog(found, canDelete = true) }
            }
        } else if (chat is AcpChat && chat.supportsHistory) {
            chat.listSessions { sessions ->
                if (isDestroyed) return@listSessions
                // Lista nula = falhou (agente parado ou erro): não mostra "sem conversas".
                if (sessions == null) runOnUiThread { Toast.makeText(this, R.string.chat_history_list_failed, Toast.LENGTH_LONG).show() }
                else showHistoryDialog(sessions, chat.canDelete)
            }
        } else {
            Toast.makeText(this, getString(R.string.chat_history_unsupported, agentName), Toast.LENGTH_LONG).show()
        }
    }

    private fun showHistoryDialog(found: List<ChatHistory.Conversation>, canDelete: Boolean) {
        val svc = service ?: return
        val conversations = found.toMutableList()
        if (conversations.isEmpty()) {
            Toast.makeText(this, R.string.chat_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val rows = conversations.map { conversation ->
            val isCurrent = conversation.id == engine?.sessionId
            Sheet.Row(
                title = conversation.title,
                description = if (conversation.updatedAt > 0) DateUtils.getRelativeTimeSpanString(conversation.updatedAt) else null,
                checked = isCurrent,
            ) {
                if (!isCurrent) reopenChat(svc) {
                    svc.openChat(agentId, folder = folder, resume = conversation.id, restart = true)
                }
            }
        }.toMutableList()
        // Apagar fica numa linha própria (sem gesto escondido, que não é acessível).
        if (canDelete) rows += Sheet.Row(title = getString(R.string.chat_history_delete_row), destructive = true) {
            showDeleteHistory(conversations)
        }
        Sheet(this).title(getString(R.string.chat_history)).rows(rows).show()
    }

    /** Apagar conversas: cada uma em vermelho; tocar numa pede a confirmação e, depois, volta a lista. */
    private fun showDeleteHistory(conversations: MutableList<ChatHistory.Conversation>) {
        if (conversations.isEmpty()) return
        val rows = conversations.map { conversation ->
            Sheet.Row(title = conversation.title, destructive = true) {
                confirmDelete(conversation, conversation.id == engine?.sessionId) {
                    conversations.remove(conversation)
                    runOnUiThread { if (!isDestroyed) showDeleteHistory(conversations) }
                }
            }
        }
        Sheet(this).title(getString(R.string.chat_history_delete_title)).rows(rows).show()
    }

    private fun confirmDelete(conversation: ChatHistory.Conversation, isCurrent: Boolean, done: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_delete_title)
            .setMessage(conversation.title)
            .setPositiveButton(R.string.chat_delete) { _, _ ->
                val svc = service ?: return@setPositiveButton
                // Depois de apagar: se era a conversa aberta, abre uma nova; a lista volta à tela principal.
                val reopenAndDone = {
                    // O chat já foi fechado: abrir outro precisa de vaga (se o apagado estava parado).
                    if (isCurrent && !isDestroyed) withRoom(svc) { attach(svc.openChat(agentId, folder = folder, restart = true)) }
                    done()
                }
                if (isClaude) {
                    // Fecha o chat primeiro (o Claude para de gravar); só então apaga os arquivos.
                    if (isCurrent) {
                        svc.closeChat(agentId, folder) { deleteClaudeFiles(conversation.id, reopenAndDone) }
                    } else {
                        deleteClaudeFiles(conversation.id, done)
                    }
                } else {
                    // ACP: o agente apaga pela própria conexão, então apaga antes de fechar a conversa.
                    val acp = engine as? AcpChat
                    val afterDelete = { if (isCurrent) svc.closeChat(agentId, folder) { reopenAndDone() } else done() }
                    if (acp == null) afterDelete()
                    else acp.deleteSession(conversation.id) { error ->
                        // Falhou: a conversa continua na lista (e aberta, se for a atual).
                        if (error == null) afterDelete()
                        else runOnUiThread { if (!isDestroyed) Toast.makeText(this, getString(R.string.chat_history_delete_failed, error), Toast.LENGTH_LONG).show() }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Apaga a conversa do Claude fora da thread principal (a pasta de anexos pode ser grande); [then] volta à principal. */
    private fun deleteClaudeFiles(id: String, then: () -> Unit) {
        val cwd = chatCwd
        thread(name = "chat-delete") {
            // Um erro aqui (arquivo preso, por exemplo) não pode derrubar o app.
            runCatching { ChatHistory.delete(linux, cwd, id) }
            runOnUiThread { if (!isDestroyed) then() }
        }
    }

    /**
     * Ajustes da conversa (toque no nome do modelo): modelo e esforço do
     * Claude; nos agentes ACP, os ajustes que cada um oferece (modelo,
     * nível de raciocínio, modo...).
     */
    private fun showChatSettings() {
        // As pastas (e o branch de cada uma) são lidas fora da thread principal; o diálogo abre depois.
        if (loadingSettings) return
        loadingSettings = true
        loadFolders { folders ->
            loadingSettings = false
            showSettingsDialog(folders)
        }
    }

    /** Lê as pastas (com o branch de cada uma) fora da thread principal; [then] roda na principal, se a tela existir. */
    private fun loadFolders(then: (List<Workspace.Folder>) -> Unit) {
        thread(name = "chat-folders") {
            val folders = runCatching { Workspace.list(linux) }.getOrDefault(emptyList())
            runOnUiThread {
                if (!isDestroyed && !isFinishing) then(folders)
            }
        }
    }

    /**
     * Trabalhos abertos (agente · pasta · estado), com ● no início do desta tela. Tocar em
     * outro abre a tela dele; "Novo trabalho…" escolhe uma pasta para o mesmo agente.
     */
    private fun showJobs() {
        val svc = service ?: return
        val slots = svc.chats.values.toList()
        val rows = slots.map { slot ->
            Sheet.Row(
                title = jobName(agentNameOf(slot.agentId), slot.folder),
                value = stateText(this, chatState(slot.chat)),
                checked = slot.agentId == agentId && slot.folder == folder,
            ) {
                if (slot.agentId != agentId || slot.folder != folder) openJobScreen(slot.agentId, slot.folder)
            }
        } + Sheet.Row(title = getString(R.string.chat_jobs_new), icon = "＋") {
            loadFolders { folders -> chooseFolder(folders) }
        }
        Sheet(this).title(getString(R.string.chat_jobs)).rows(rows).show()
    }

    /** Nome de um trabalho na lista: o agente e, se não for a raiz, a pasta. */
    private fun jobName(agent: String, jobFolder: String): String =
        if (jobFolder.isEmpty()) agent else "$agent · $jobFolder"

    private fun showSettingsDialog(folders: List<Workspace.Folder>) {
        // Cada linha leva a sua ação; a ordem da lista é a da tela.
        val rows = mutableListOf<Sheet.Row>()
        rows += Sheet.Row(
            title = getString(R.string.chat_setting_folder),
            value = folderLabel(folders),
            icon = "📁",
            description = getString(R.string.sheet_desc_folder),
        ) { chooseFolder(folders) }
        val current = folders.firstOrNull { it.name == folder }
        if (current?.branch != null) rows += Sheet.Row(
            title = getString(R.string.chat_setting_changes),
            icon = "⎇",
            description = getString(R.string.sheet_desc_changes),
        ) { openChanges() }
        if (current?.worktreeOf != null) rows += Sheet.Row(title = getString(R.string.chat_worktree_finish), icon = "⏏") {
            showWorktreeFinish(folders)
        }
        if (isClaude) {
            rows += Sheet.Row(title = getString(R.string.chat_setting_model), value = modelLabel(settings.chatModel), icon = "🧠") {
                chooseClaude(R.string.chat_setting_model, MODELS, settings.chatModel, ::modelLabel) { settings.chatModel = it }
            }
            rows += Sheet.Row(title = getString(R.string.chat_setting_effort), value = effortLabel(settings.chatEffort), icon = "⚡") {
                chooseClaude(R.string.chat_setting_effort, EFFORTS, settings.chatEffort, ::effortLabel) { settings.chatEffort = it }
            }
        }
        rows += viewRow()
        if (!isClaude) {
            // Sem ajustes do agente (ou sem chat pronto), o painel mostra a pasta e a visualização, que são do app.
            val acp = engine as? AcpChat
            for (option in acp?.configOptions.orEmpty()) {
                rows += Sheet.Row(title = option.name, value = option.currentName, icon = "⚙") { chooseOption(acp, option) }
            }
        }
        Sheet(this).title(getString(R.string.chat_settings_title)).rows(rows).show()
    }

    /** Escolha de uma opção do agente ACP (modelo, nível de raciocínio, modo...). */
    private fun chooseOption(acp: AcpChat?, option: AcpChat.ConfigOption) {
        Sheet(this)
            .title(option.name)
            .rows(option.choices.map { (id, name) ->
                Sheet.Row(title = name, checked = id == option.current) { acp?.setOption(option, id) }
            })
            .show()
    }

    /** Mudanças (git) da pasta do trabalho: arquivos alterados, diff e commit. */
    private fun openChanges() {
        startActivity(
            Intent(this, ChangesActivity::class.java)
                .putExtra(ChangesActivity.EXTRA_FOLDER, folder)
                .putExtra(EXTRA_AGENT_ID, agentId),
        )
    }

    /** Linha "Pasta: ..." dos ajustes: o nome (ou "Todos os projetos") e o branch, se a pasta tiver git. */
    private fun folderLabel(folders: List<Workspace.Folder>): String {
        val label = Workspace.label(folder, getString(R.string.chat_folder_root))
        val branch = folders.firstOrNull { it.name == folder }?.branch
        return if (branch == null) label else "$label · $branch"
    }

    /**
     * Escolha da pasta: a raiz, cada subpasta (com o branch), criar uma nova e, se há
     * repositório para isso, um trabalho isolado (worktree).
     */
    private fun chooseFolder(folders: List<Workspace.Folder>) {
        val rows = mutableListOf<Sheet.Row>()
        rows += Sheet.Row(title = getString(R.string.chat_folder_root), checked = folder.isEmpty()) { pickFolder("") }
        for (item in folders) {
            rows += Sheet.Row(title = item.name, description = folderDescription(item), checked = item.name == folder) {
                pickFolder(item.name)
            }
        }
        rows += Sheet.Row(title = getString(R.string.chat_folder_new), icon = "＋") { newFolderDialog() }
        if (folders.any { Worktrees.canStart(it) }) rows += Sheet.Row(
            title = getString(R.string.chat_worktree_new),
            icon = "⎇",
            description = getString(R.string.sheet_desc_worktree),
        ) { chooseWorktreeBase(folders) }
        Sheet(this).title(getString(R.string.chat_setting_folder)).rows(rows).show()
    }

    /** Linha de apoio de uma pasta na escolha: o branch e, num worktree, de qual repositório ele veio. */
    private fun folderDescription(item: Workspace.Folder): String? =
        listOfNotNull(item.branch, item.worktreeOf?.let { getString(R.string.chat_worktree_of, it) })
            .joinToString(" · ")
            .takeIf { it.isNotEmpty() }

    /** Repositório que pode ser a base de um trabalho isolado: pasta git que não é worktree. */
    private fun canBeWorktreeBase(item: Workspace.Folder) = item.branch != null && item.worktreeOf == null

    /** Escolha do repositório base do novo trabalho isolado. */
    private fun chooseWorktreeBase(folders: List<Workspace.Folder>) {
        val bases = folders.filter { Worktrees.canStart(it) }
        Sheet(this)
            .title(getString(R.string.chat_worktree_pick_base))
            .rows(bases.map { base ->
                Sheet.Row(title = base.name, description = folderDescription(base)) { newWorktreeDialog(base) }
            })
            .show()
    }

    /** Nome do novo trabalho isolado (diálogo compartilhado com a tela inicial); depois, cria o trabalho. */
    private fun newWorktreeDialog(base: Workspace.Folder) {
        askWorktreeName(base.name) { name, done -> createWorktree(base.name, name, done) }
    }

    /**
     * Cria a cópia de trabalho (Worktrees.create) fora da thread principal. Deu certo, fecha o
     * diálogo e abre o trabalho nela; falhou, mostra a saída e o diálogo volta para tentar de novo.
     */
    private fun createWorktree(baseName: String, name: String, done: (Boolean) -> Unit) {
        thread(name = "chat-worktree-new") {
            val made = Worktrees.create(linux, baseName, name)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                when (made) {
                    is Worktrees.Result.Created -> {
                        done(true)
                        pickFolder(made.folder)
                    }
                    is Worktrees.Result.Failed -> {
                        done(false)
                        showDetails(R.string.git_failed_title, made.output.ifBlank { getString(R.string.git_failed_title) })
                    }
                }
            }
        }
    }

    /**
     * Trocar de pasta abre o trabalho deste agente na pasta escolhida. O trabalho desta
     * tela continua rodando na lista de trabalhos.
     */
    private fun pickFolder(name: String) {
        if (name == folder) return
        openJobScreen(agentId, name)
    }

    /**
     * Trabalho isolado (worktree) desta tela: a pasta dele, o repositório de origem e as branches
     * lidas pelo git (`symbolic-ref`) dos dois lados; null = HEAD solto (ou nome que não serve de argumento).
     */
    private class WorktreeJob(val folder: String, val base: String, val baseBranch: String?, val branch: String?)

    /** Roda [block] na thread principal, se a tela ainda existir (nada de diálogo em tela morta). */
    private fun onUi(block: () -> Unit) = runOnUiThread { if (!isDestroyed && !isFinishing) block() }

    /** Branch do HEAD de [path] pelo git; null com HEAD solto ou nome não confiável. Fora da thread principal. */
    private fun headBranch(path: String): String? = Git.branchName(Git.run(linux, path, "symbolic-ref", "-q", "--short", "HEAD"))

    /**
     * Encerrar trabalho isolado: lê (numa thread) as branches dos dois lados, os commits desta
     * cópia que não estão no HEAD do repositório base e os arquivos sem commit, e mostra o que dá para fazer.
     */
    private fun showWorktreeFinish(folders: List<Workspace.Folder>) {
        if (removingWorktree) return
        val worktree = folders.firstOrNull { it.name == folder } ?: return
        val baseName = worktree.worktreeOf ?: return
        if (folders.none { it.name == baseName && canBeWorktreeBase(it) }) {
            AlertDialog.Builder(this)
                .setTitle(R.string.chat_worktree_title)
                .setMessage(R.string.chat_worktree_base_missing)
                .setPositiveButton(R.string.chat_worktree_keep, null)
                .show()
            return
        }
        val jobFolder = folder
        val path = Workspace.path(jobFolder)
        val basePath = Workspace.path(baseName)
        thread(name = "chat-worktree-check") {
            val branch = headBranch(path)
            val baseBranch = headBranch(basePath)
            // A contagem usa o hash do HEAD do base (o banco de objetos é o mesmo), sem nome de branch no comando.
            val baseHead = Git.run(linux, basePath, "rev-parse", "--verify", "-q", "HEAD")
            val baseSha = baseHead.output.trim().takeIf { baseHead.code == 0 && SHA.matches(it) }
            val commits = if (baseSha == null) Git.Result(-1, baseHead.shown) else Git.run(linux, path, "rev-list", "--count", "$baseSha..HEAD")
            val status = Git.status(linux, path)
            onUi {
                val failed = listOf(commits, status).firstOrNull { it.code != 0 }
                if (failed != null) {
                    showDetails(R.string.git_failed_title, failed.shown.ifBlank { getString(R.string.git_failed_title) })
                } else {
                    val job = WorktreeJob(jobFolder, baseName, baseBranch, branch)
                    showWorktreeDialog(job, commits.output.trim().toIntOrNull() ?: 0, Git.parseStatus(status.output).size)
                }
            }
        }
    }

    /**
     * O resumo do trabalho e as três saídas: juntar, descartar ou manter. Com HEAD solto de um
     * dos lados, juntar é recusado já aqui, com o motivo no texto.
     */
    private fun showWorktreeDialog(job: WorktreeJob, commits: Int, files: Int) {
        val why = when {
            job.branch == null -> getString(R.string.chat_worktree_detached)
            job.baseBranch == null -> getString(R.string.chat_worktree_base_detached, job.base)
            else -> null
        }
        val summary = getString(R.string.chat_worktree_summary, job.baseBranch ?: job.base, commits, files)
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.chat_worktree_title)
            .setMessage(if (why == null) summary else "$summary\n\n$why")
            .setNeutralButton(R.string.chat_worktree_discard) { _, _ -> confirmDiscard(job, commits, files) }
            .setNegativeButton(R.string.chat_worktree_keep, null)
        val baseBranch = job.baseBranch
        if (why == null && baseBranch != null) {
            builder.setPositiveButton(getString(R.string.chat_worktree_merge, baseBranch)) { _, _ -> mergeWorktree(job) }
        }
        builder.show()
    }

    /**
     * Junta o trabalho na branch do repositório base (`git merge --no-ff`). Recusa se há
     * arquivos sem commit aqui ou no base, ou se o base trocou de branch desde o resumo;
     * conflito desfaz a junção e mostra a saída. Deu certo, tira o trabalho sem perguntar de novo.
     */
    private fun mergeWorktree(job: WorktreeJob) {
        val branch = job.branch ?: return
        val baseBranch = job.baseBranch ?: return
        val path = Workspace.path(job.folder)
        val basePath = Workspace.path(job.base)
        thread(name = "chat-worktree-merge") {
            val mine = Git.status(linux, path)
            val theirs = Git.status(linux, basePath)
            val failed = listOf(mine, theirs).firstOrNull { it.code != 0 }
            when {
                failed != null -> onUi { showDetails(R.string.git_failed_title, failed.shown.ifBlank { getString(R.string.git_failed_title) }) }
                Git.parseStatus(mine.output).isNotEmpty() -> onUi { showUncommitted() }
                Git.parseStatus(theirs.output).isNotEmpty() -> onUi { showWorktreeMessage(getString(R.string.chat_worktree_base_dirty, job.base)) }
                // A branch vem do HEAD (não é confiável): Git.branchName já recusou nomes com "-" no início.
                headBranch(basePath) != baseBranch || headBranch(path) != branch ->
                    onUi { showWorktreeMessage(getString(R.string.chat_worktree_moved, job.base)) }
                else -> {
                    val merged = Git.run(linux, basePath, "merge", "--no-ff", "--no-edit", branch)
                    if (merged.code != 0) {
                        // Sem junção em andamento (falhou antes de começar), o --abort só erra: tudo bem.
                        Git.run(linux, basePath, "merge", "--abort")
                        onUi {
                            val explain = getString(R.string.chat_worktree_merge_failed, job.base) + "\n\n" + merged.shown
                            val body = if (Git.identityMissing(merged.output)) getString(R.string.changes_identity) + "\n\n" + explain else explain
                            showDetails(R.string.chat_worktree_title, body)
                        }
                    } else {
                        onUi {
                            Toast.makeText(this, getString(R.string.chat_worktree_merged, baseBranch), Toast.LENGTH_SHORT).show()
                            startRemoval(job, force = false)
                        }
                    }
                }
            }
        }
    }

    /** Aviso curto do trabalho isolado, só com OK. */
    private fun showWorktreeMessage(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_worktree_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Recusa a junção: há arquivos sem commit no trabalho. Oferece abrir Mudanças. */
    private fun showUncommitted() {
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_worktree_title)
            .setMessage(R.string.chat_worktree_uncommitted)
            .setPositiveButton(R.string.chat_worktree_open_changes) { _, _ -> openChanges() }
            .setNegativeButton(R.string.chat_worktree_keep, null)
            .show()
    }

    /** Confirmação destrutiva: diz quantos commits e arquivos vão embora. */
    private fun confirmDiscard(job: WorktreeJob, commits: Int, files: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_worktree_discard)
            .setMessage(getString(R.string.chat_worktree_discard_confirm, commits, files))
            .setPositiveButton(R.string.chat_worktree_discard) { _, _ -> startRemoval(job, force = true) }
            .setNegativeButton(R.string.chat_worktree_keep, null)
            .show()
    }

    /**
     * Começa a tirar o trabalho isolado. A tela deixa de mandar mensagens e não reabre o chat
     * da pasta; sem o serviço ligado (tela parada), a remoção espera ele voltar.
     */
    private fun startRemoval(job: WorktreeJob, force: Boolean) {
        if (removingWorktree) return
        removingWorktree = true
        setPreparing(true)
        Toast.makeText(this, R.string.chat_worktree_closing, Toast.LENGTH_SHORT).show()
        val svc = service
        if (svc == null) pendingRemoval = job to force else removeWorktree(svc, job, force)
    }

    /**
     * Tira o trabalho isolado: antes, fecha as sessões de terminal e os chats (de todos os
     * agentes, rodando ou parados) dessa pasta; quando todos saírem, remove o worktree e a branch.
     * Os avisos de saída chegam na thread principal: o contador não precisa de trava.
     */
    private fun removeWorktree(svc: TerminalService, job: WorktreeJob, force: Boolean) {
        svc.sessionFolder.filterValues { it == job.folder }.keys.toList().forEach { svc.closeSession(it) }
        val slots = svc.chats.values.filter { it.folder == job.folder }
        if (slots.isEmpty()) {
            removeFromGit(job, force)
            return
        }
        var waiting = slots.size
        for (slot in slots) {
            svc.closeChat(slot.agentId, slot.folder) {
                waiting--
                if (waiting == 0) removeFromGit(job, force)
            }
        }
    }

    /**
     * `git worktree remove` e `git branch -d` (ou -D) no repositório base; ao fim, abre o chat
     * dele. Se a remoção falha, mostra o erro e oferece reabrir o trabalho na pasta.
     */
    private fun removeFromGit(job: WorktreeJob, force: Boolean) {
        val basePath = Workspace.path(job.base)
        val removeArgs = listOfNotNull("worktree", "remove", if (force) "--force" else null, Workspace.path(job.folder))
        thread(name = "chat-worktree-remove") {
            val removed = Git.run(linux, basePath, *removeArgs.toTypedArray())
            if (removed.code != 0) {
                onUi { showRemoveFailed(job, removed) }
                return@thread
            }
            val branch = job.branch
            val deleted = if (branch == null) null else Git.run(linux, basePath, "branch", if (force) "-D" else "-d", branch)
            onUi {
                removingWorktree = false
                if (deleted != null && deleted.code != 0) {
                    Toast.makeText(this, getString(R.string.chat_worktree_branch_kept, branch), Toast.LENGTH_LONG).show()
                }
                openJobScreen(agentId, job.base)
            }
        }
    }

    /**
     * O worktree não saiu, mas os chats da pasta já foram fechados: a tela não pode ficar sem
     * chat e sem saída. Mostra o erro e oferece reabrir o trabalho aqui ou ir para o base.
     */
    private fun showRemoveFailed(job: WorktreeJob, result: Git.Result) {
        removingWorktree = false
        val body = getString(R.string.chat_worktree_remove_failed) + "\n\n" + result.shown.ifBlank { getString(R.string.git_failed_title) }
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_worktree_title)
            .setMessage(body)
            .setCancelable(false)
            .setPositiveButton(R.string.chat_worktree_reopen) { _, _ -> openJobScreen(agentId, job.folder) }
            .setNegativeButton(getString(R.string.chat_worktree_open_base, job.base)) { _, _ -> openJobScreen(agentId, job.base) }
            .show()
    }

    /**
     * Abre a tela de um trabalho (agente e pasta) e sai desta, sem animação. O trabalho
     * desta tela segue rodando; a tela nova o encontra aberto ou o abre.
     */
    private fun openJobScreen(agent: String, jobFolder: String) {
        if (isDestroyed || isFinishing) return
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(EXTRA_AGENT_ID, agent)
                .putExtra(EXTRA_FOLDER, jobFolder),
        )
        finish()
        overridePendingTransition(0, 0)
    }

    /** Nova pasta: o nome (validado) cria a pasta na raiz dos projetos e a escolhe. */
    private fun newFolderDialog() {
        val field = EditText(this).apply {
            hint = getString(R.string.chat_folder_new_hint)
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.chat_folder_new_title)
            .setView(field)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // O OK é ligado depois de mostrar o diálogo: com nome inválido, ele fica aberto.
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val name = field.text.toString().trim()
                if (!Workspace.isValidName(name)) {
                    field.error = getString(R.string.chat_folder_invalid)
                    return@setOnClickListener
                }
                createFolder(name, dialog, ok)
            }
        }
        dialog.show()
    }

    /** Cria a pasta fora da thread principal; se ela já existe como pasta de verdade, só usa. */
    private fun createFolder(name: String, dialog: AlertDialog, ok: View) {
        ok.isEnabled = false
        val host = Workspace.hostPath(linux.rootfs, Workspace.path(name))
        val root = Workspace.hostPath(linux.rootfs, Workspace.ROOT)
        thread(name = "chat-folder-new") {
            // Nunca cria através de link: a raiz dos projetos tem que ser pasta de verdade.
            val made = Fs.isDir(root.toPath()) &&
                (Fs.isDir(host.toPath()) || runCatching { host.mkdir() }.getOrDefault(false))
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (made) {
                    dialog.dismiss()
                    pickFolder(name)
                } else {
                    ok.isEnabled = true
                    Toast.makeText(this, R.string.chat_folder_create_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Linha "Visualização" dos ajustes: o modo atual como valor, e o que ele mostra como descrição. */
    private fun viewRow() = Sheet.Row(
        title = getString(R.string.chat_setting_view),
        value = getString(viewLabelRes(settings.chatView)),
        icon = "👁",
        description = getString(R.string.sheet_desc_view),
    ) { chooseView() }

    /** Escolha do modo de visualização; muda só a tela, a conversa continua a mesma. */
    private fun chooseView() {
        val modes = listOf(AppSettings.VIEW_DETAILED, AppSettings.VIEW_NORMAL, AppSettings.VIEW_SUMMARY)
        Sheet(this)
            .title(getString(R.string.chat_setting_view))
            .rows(modes.map { mode ->
                Sheet.Row(
                    title = getString(viewLabelRes(mode)),
                    description = getString(viewHintRes(mode)),
                    checked = mode == settings.chatView,
                ) {
                    settings.chatView = mode
                    refresh()
                }
            })
            .show()
    }

    /** Nome curto do modo (linha de ajustes e escolha). */
    private fun viewLabelRes(mode: String) = when (mode) {
        AppSettings.VIEW_DETAILED -> R.string.chat_view_detailed
        AppSettings.VIEW_SUMMARY -> R.string.chat_view_summary
        else -> R.string.chat_view_normal
    }

    /** Descrição curta do modo, mostrada ao lado do nome na escolha. */
    private fun viewHintRes(mode: String) = when (mode) {
        AppSettings.VIEW_DETAILED -> R.string.chat_view_detailed_hint
        AppSettings.VIEW_SUMMARY -> R.string.chat_view_summary_hint
        else -> R.string.chat_view_normal_hint
    }

    /** Escolha de modelo/esforço do Claude: reabre a mesma conversa com o novo ajuste. */
    private fun chooseClaude(titleRes: Int, values: Array<String>, current: String, label: (String) -> String, save: (String) -> Unit) {
        Sheet(this)
            .title(getString(titleRes))
            .rows(values.map { value ->
                Sheet.Row(title = label(value), checked = value == current) {
                    if (value != current) {
                        save(value)
                        val svc = service
                        if (svc != null) {
                            val id = engine?.sessionId
                            reopenChat(svc) { svc.openChat(agentId, folder = folder, resume = id, restart = true) }
                        }
                    }
                }
            })
            .show()
    }

    private fun modelLabel(alias: String) = when (alias) {
        "" -> getString(R.string.chat_model_default)
        else -> alias.replaceFirstChar { it.uppercase() }
    }

    private fun effortLabel(value: String) = getString(
        when (value) {
            "low" -> R.string.effort_low
            "medium" -> R.string.effort_medium
            "high" -> R.string.effort_high
            "xhigh" -> R.string.effort_xhigh
            "max" -> R.string.effort_max
            else -> R.string.chat_effort_default
        },
    )

    /**
     * Mesma conversa na tela de terminal (Claude Code). Para os agentes
     * ACP, o terminal abre a tela do próprio agente. Espera o chat fechar
     * antes, para os dois não gravarem as configurações ao mesmo tempo.
     */
    private fun switchToTerminal() {
        val svc = service ?: return
        val recipe = recipe
        val id = engine?.sessionId
        val wasBusy = engine?.busy == true
        val script: String
        if (isClaude) {
            // Sem id (conversa nova, ainda sem mensagens), abre o Claude numa conversa nova.
            // Se ele estava no meio de uma resposta, o terminal já pede para continuar.
            val resume = when {
                id == null -> ""
                wasBusy -> " --resume ${AgentRecipe.q(id)} " + AgentRecipe.q(getString(R.string.chat_continue_prompt))
                else -> " --resume ${AgentRecipe.q(id)}"
            }
            val model = settings.chatModel.takeIf { it.isNotEmpty() }?.let { " --model $it" } ?: ""
            val effort = settings.chatEffort.takeIf { it.isNotEmpty() }?.let { " --effort $it" } ?: ""
            script = claudeLaunchScript(model + effort + resume)
        } else {
            script = recipe?.launchScript(Workspace.path(folder)) ?: return
            Toast.makeText(this, getString(R.string.chat_terminal_note, agentName), Toast.LENGTH_LONG).show()
        }
        openTerminal(svc, script, conversation = if (isClaude) id else null)
    }

    /** Comando que abre o Claude Code na pasta de trabalho do chat; [args] vai depois do comando. */
    private fun claudeLaunchScript(args: String): String =
        AgentRecipe.claudeIn(Workspace.path(folder), exec = false) + args

    /**
     * Fecha o chat e abre a tela de terminal com o [script], na pasta do chat;
     * esta tela termina em seguida.
     */
    private fun openTerminal(svc: TerminalService, script: String, conversation: String? = null, login: Boolean = false) {
        svc.closeChat(agentId, folder) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TITLE, agentName)
                    .putExtra(MainActivity.EXTRA_AGENT, agentId)
                    .putExtra(MainActivity.EXTRA_SCRIPT, script)
                    .putExtra(MainActivity.EXTRA_FOLDER, folder)
                    .apply {
                        if (conversation != null) putExtra(MainActivity.EXTRA_CONVERSATION, conversation)
                        if (login) putExtra(MainActivity.EXTRA_LOGIN, true)
                    },
            )
            finish()
        }
    }

    /**
     * Login: pelo navegador (o agente faz e o app só espera) ou pela tela
     * do agente no terminal. Neste caso, o chat fecha; depois do login, o
     * botão Chat no topo do terminal traz de volta para cá.
     */
    private fun startLogin(card: ChatItem.Login, option: AuthOption) {
        if (option.kind == AuthOption.Kind.AGENT) {
            (engine as? AcpChat)?.login(card, option)
            return
        }
        val svc = service ?: return
        val script = when {
            option.kind == AuthOption.Kind.TERMINAL -> (engine as? AcpChat)?.terminalLoginScript(option)
            isClaude -> claudeLaunchScript("")
            else -> recipe?.launchScript(Workspace.path(folder))
        } ?: return
        Toast.makeText(this, getString(R.string.login_come_back), Toast.LENGTH_LONG).show()
        openTerminal(svc, script, login = true)
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(agentName, text))
        Toast.makeText(this, R.string.chat_copied, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** Fundo com o efeito de toque do tema, para linhas clicáveis da conversa. */
    private val selectableBackground by lazy {
        TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
    }

    /** Texto do botão. "Sempre permitir" diz o que libera; só o Claude Code (com ferramenta) muda. */
    private fun optionLabel(item: ChatItem.Permission, option: PermissionOption): String = when (option.kind) {
        PermissionOption.Kind.ALLOW_ONCE -> getString(R.string.chat_allow)
        PermissionOption.Kind.ALLOW_ALWAYS -> when {
            ChatEngine.isShellTool(item.toolName) -> getString(R.string.chat_allow_always_command)
            item.toolName.isNotEmpty() -> getString(R.string.chat_allow_always_tool, item.title)
            else -> getString(R.string.chat_allow_always)
        }
        PermissionOption.Kind.REJECT_ONCE -> getString(R.string.chat_deny)
        PermissionOption.Kind.REJECT_ALWAYS -> getString(R.string.chat_deny_always)
    }

    /** Monta cada item da conversa (sem XML: são poucos tipos e bem simples). */
    private inner class ChatAdapter : BaseAdapter() {
        var snapshot: List<ChatItem> = emptyList()
        /** Modo de visualização da tela (AppSettings.chatView), lido uma vez por redesenho. */
        var viewMode = AppSettings.VIEW_NORMAL
        /** Escala do texto da conversa (AppSettings.chatTextScale), lida uma vez por redesenho. */
        var chatScale = 1f

        override fun getCount() = snapshot.size
        override fun getItem(position: Int): Any = snapshot[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = false

        /** Respostas já separadas em partes e formatadas, por item (cache do adaptador). */
        private val answers = WeakHashMap<ChatItem.Assistant, Rendered>()

        /** Um tipo de linha para cada tipo de item (o Android reaproveita só dentro do mesmo tipo). */
        override fun getViewTypeCount() = 9

        override fun getItemViewType(position: Int): Int = when (snapshot[position]) {
            is ChatItem.User -> 0
            is ChatItem.Assistant -> 1
            is ChatItem.Tool -> 2
            is ChatItem.Permission -> 3
            is ChatItem.Plan -> 4
            is ChatItem.Login -> 5
            is ChatItem.Info -> 6
            is ChatItem.Thinking -> 7
            is ChatItem.ToolGroup -> 8
        }

        /**
         * Reaproveita a linha (e a view de texto dentro dela, quando o tipo
         * permite). Cada uso refaz todos os campos e listeners: nada do item
         * anterior fica na tela.
         */
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = snapshot[position]
            val type = getItemViewType(position)
            val row = (convertView as? LinearLayout)?.takeIf { it.tag == type } ?: LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                tag = type
            }
            row.setPadding(0, dp(4), 0, dp(4))
            // A view antiga é do mesmo tipo, e o construtor de cada tipo redefine todos os campos e listeners
            // dela (setOnClickListener(null) aqui a deixaria clicável e engoliria toques).
            val old = row.getChildAt(0)
            row.removeAllViews()
            row.addView(
                when (item) {
                    is ChatItem.User -> userBubble(item, old as? TextView)
                    is ChatItem.Assistant -> assistantView(item, old as? LinearLayout)
                    is ChatItem.Tool -> toolLine(item, old as? TextView)
                    is ChatItem.Permission -> permissionCard(item)
                    is ChatItem.Plan -> planView(item, old as? TextView)
                    is ChatItem.Login -> loginCard(item)
                    is ChatItem.Info -> infoText(item, old as? TextView)
                    is ChatItem.Thinking -> thinkingView(item, old as? TextView)
                    is ChatItem.ToolGroup -> toolGroupLine(item, old as? TextView)
                },
            )
            return row
        }

        /** Mensagem do usuário; na fila ou descartada, uma linha pequena embaixo diz o que houve. */
        private fun userBubble(item: ChatItem.User, old: TextView?) = (old ?: TextView(this@ChatActivity)).apply {
            val body = item.text + item.attachments.joinToString("") { "\n📎 $it" }
            val note = when {
                item.queued -> getString(R.string.chat_queued)
                item.dropped -> getString(R.string.chat_dropped)
                else -> null
            }
            val shown: CharSequence = if (note == null) body else SpannableStringBuilder(body).apply {
                append("\n")
                val start = length
                append(note)
                setSpan(RelativeSizeSpan(0.8f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = shown
            // Na fila ou não enviada, a bolha fica mais apagada; as views são reaproveitadas, então isto vale em todo uso.
            alpha = if (item.queued || item.dropped) 0.6f else 1f
            setTextColor(getColor(R.color.key_text))
            textSize = 16f * chatScale
            setBackgroundResource(R.drawable.bubble_user)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END; leftMargin = dp(40) }
            setOnLongClickListener { copy(item.text); true }
            contentDescription = null
            if (item.dropped) {
                // Não enviada: toque devolve o texto para a caixa, para editar e enviar de novo.
                setOnClickListener {
                    // Já há texto na caixa: não apaga, acrescenta numa linha nova.
                    val typed = input.text.toString().trimEnd()
                    input.setText(if (typed.isEmpty()) item.text else typed + "\n" + item.text)
                    input.setSelection(input.text.length)
                    input.requestFocus()
                }
                isClickable = true
            } else {
                // Mesmo cuidado de toolLine: uma view reaproveitada não pode ficar clicável à toa.
                setOnClickListener(null)
                isClickable = false
            }
        }

        /** Resposta: texto com Markdown e blocos de código com botão Copiar. */
        private fun assistantView(item: ChatItem.Assistant, old: LinearLayout?): View {
            val box = (old ?: LinearLayout(this@ChatActivity)).apply {
                removeAllViews()
                orientation = LinearLayout.VERTICAL
                setOnLongClickListener { copy(item.text); true }
            }
            val answer = answerOf(item)
            for ((index, part) in answer.parts.withIndex()) {
                val body = answer.bodies[index]
                val last = index == answer.parts.size - 1
                if (body == null) box.addView(codeBlock(part)) else box.addView(prose(body, item, last))
            }
            return box
        }

        /** Resposta já separada e formatada. Só guarda no cache o que não está em streaming. */
        private fun answerOf(item: ChatItem.Assistant): Rendered {
            if (item.streaming) return renderAnswer(item)
            answers[item]?.takeIf { it.text == item.text }?.let { return it }
            return renderAnswer(item).also { answers[item] = it }
        }

        /** Markdown de cada parte de texto (null nas partes de código); os links já ficam marcados. */
        private fun renderAnswer(item: ChatItem.Assistant): Rendered {
            val parts = splitCode(item.text)
            val bodies = parts.map { part ->
                if (part.isCode) {
                    null
                } else {
                    SpannableStringBuilder(Markdown.render(part.text.trim('\n'), getColor(R.color.code_bg))).also {
                        if (!item.streaming) Linkify.addLinks(it, Linkify.WEB_URLS)
                    }
                }
            }
            return Rendered(item.text, parts, bodies)
        }

        private fun prose(body: CharSequence, item: ChatItem.Assistant, last: Boolean) = TextView(this@ChatActivity).apply {
            text = if (item.streaming && last) SpannableStringBuilder(body).append(" ▍") else body
            setTextColor(getColor(R.color.setup_text))
            textSize = 16f * chatScale
            setLineSpacing(0f, 1.2f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setLinkTextColor(getColor(R.color.accent_text))
            // Links abrem ao toque; sem link, o toque segue para o clique longo (copiar).
            movementMethod = if (body is Spanned && body.getSpans(0, body.length, URLSpan::class.java).isNotEmpty()) {
                LinkMovementMethod.getInstance()
            } else {
                null
            }
            setOnLongClickListener { copy(item.text); true }
        }

        private fun codeBlock(part: Part): View {
            val block = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.compose_bg)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(4); bottomMargin = dp(4) }
            }
            val header = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }
            header.addView(TextView(this@ChatActivity).apply {
                text = part.language
                textSize = 12f * chatScale
                setTextColor(getColor(R.color.setup_muted))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(TextView(this@ChatActivity).apply {
                setText(R.string.chat_copy_code)
                textSize = 13f * chatScale
                setTextColor(getColor(R.color.accent_text))
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { copy(part.text) }
            })
            block.addView(header)
            block.addView(HorizontalScrollView(this@ChatActivity).apply {
                addView(TextView(this@ChatActivity).apply {
                    text = part.text.trimEnd('\n')
                    typeface = Typeface.MONOSPACE
                    textSize = 13f * chatScale
                    setTextColor(getColor(R.color.setup_text))
                    setHorizontallyScrolling(true)
                    setPadding(dp(12), 0, dp(12), dp(10))
                })
            })
            return block
        }

        private fun toolLine(item: ChatItem.Tool, old: TextView?) = (old ?: TextView(this@ChatActivity)).apply {
            val output = item.output
            val line = toolLineText(item)
            // Falha: a primeira linha do erro já aparece; o resto (e a saída de quem deu certo) abre num toque.
            val firstError = output?.takeIf { item.failed }?.lineSequence()?.firstOrNull { it.isNotBlank() }
            // Detalhado: o começo da saída fica embaixo da linha (a primeira linha de uma falha já é o erro).
            val excerpt = output?.takeIf { viewMode == AppSettings.VIEW_DETAILED }
                ?.lineSequence()?.dropWhile { it.isBlank() }?.take(TOOL_EXCERPT_LINES)?.toList()?.takeIf { it.isNotEmpty() }
            text = when {
                output == null -> line
                excerpt != null -> "$line  ›\n" + excerpt.joinToString("\n")
                else -> "$line  ›" + firstError?.let { "\n$it" }.orEmpty()
            }
            maxLines = when {
                excerpt != null -> excerpt.size + 2
                firstError != null -> 3
                else -> 2
            }
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.MONOSPACE
            textSize = 12f * chatScale
            setTextColor(getColor(if (item.failed) R.color.setup_error else R.color.setup_muted))
            setPadding(dp(4), dp(2), dp(4), dp(2))
            gravity = Gravity.CENTER_VERTICAL
            if (output != null) {
                minHeight = dp(32)
                setBackgroundResource(selectableBackground)
                setOnClickListener { showDetails(R.string.chat_tool_output, "$line\n\n$output") }
                contentDescription = getString(R.string.chat_tool_output_description, listOfNotNull(line, firstError).joinToString(". "))
            } else {
                // setOnClickListener(null) deixaria a linha clicável (engolindo toques): desliga de vez.
                setOnClickListener(null)
                isClickable = false
                minHeight = 0
                background = null
                contentDescription = null
            }
        }

        /** Raciocínio do agente (só no modo Detalhado): itálico e discreto; o toque lê tudo. */
        private fun thinkingView(item: ChatItem.Thinking, old: TextView?) = (old ?: TextView(this@ChatActivity)).apply {
            text = getString(R.string.chat_thinking) + ": " + item.text
            // Linha reaproveitada: o estilo é refeito sempre, para não herdar o de outra linha.
            setTypeface(null, Typeface.ITALIC)
            textSize = 13f * chatScale
            setTextColor(getColor(R.color.setup_muted))
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), dp(2), dp(4), dp(2))
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(selectableBackground)
            setOnClickListener { showDetails(R.string.chat_thinking, item.text) }
            contentDescription = getString(R.string.chat_thinking_description)
        }

        /** Modo Resumo: ações seguidas numa linha só (contagem e as mais usadas); o toque lista cada uma. */
        private fun toolGroupLine(item: ChatItem.ToolGroup, old: TextView?) = (old ?: TextView(this@ChatActivity)).apply {
            val tools = item.tools
            // A última ação ainda em andamento aparece embaixo, para ver o que está rodando.
            val running = tools.lastOrNull { !it.done }
            val mark = when {
                running != null -> "…"
                tools.any { it.failed } -> "✕"
                else -> "✓"
            }
            val counts = tools.groupBy { ToolText.label(this@ChatActivity, it.name) }
                .entries.sortedByDescending { it.value.size }
                .take(3)
                .joinToString(", ") { "${it.value.size}× ${it.key}" }
            val summary = resources.getQuantityString(R.plurals.chat_tool_group, tools.size, tools.size) + " · " + counts
            val second = running?.let { "\n" + ToolText.label(this@ChatActivity, it.name) + "  " + it.summary }
            text = "$mark $summary  ›" + second.orEmpty()
            // A linha de cima pode quebrar em duas; com a ação em andamento embaixo, cabem três.
            maxLines = if (second != null) 3 else 2
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.MONOSPACE
            textSize = 12f * chatScale
            setTextColor(getColor(if (tools.any { it.failed }) R.color.setup_error else R.color.setup_muted))
            setPadding(dp(4), dp(2), dp(4), dp(2))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setBackgroundResource(selectableBackground)
            setOnClickListener { showToolGroup(item) }
            contentDescription = getString(R.string.chat_tool_group_description, summary + running?.let { ". " + ToolText.label(this@ChatActivity, it.name) + " " + it.summary }.orEmpty())
        }

        private fun permissionCard(item: ChatItem.Permission): View {
            val card = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(dp(14), dp(12), dp(14), dp(10))
            }
            card.addView(TextView(this@ChatActivity).apply {
                text = getString(R.string.chat_wants_agent, agentName, item.title)
                setTextColor(getColor(R.color.setup_text))
                textSize = 15f * chatScale
                setTypeface(typeface, Typeface.BOLD)
            })
            if (item.summary.isNotEmpty()) {
                card.addView(TextView(this@ChatActivity).apply {
                    text = item.summary
                    typeface = Typeface.MONOSPACE
                    textSize = 12f * chatScale
                    maxLines = 8
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.setup_muted))
                    setPadding(0, dp(6), 0, dp(4))
                    // Texto cortado (reticências na última linha): tocar mostra o pedido inteiro.
                    setOnClickListener {
                        val lay = this.layout
                        if (lay != null && lay.getEllipsisCount(lay.lineCount - 1) > 0) {
                            showDetails(R.string.chat_permission_full, item.summary)
                        }
                    }
                })
            }
            item.diff?.let { card.addView(diffView(it)) }
            if (item.detail.isNotEmpty()) {
                card.addView(TextView(this@ChatActivity).apply {
                    text = item.detail
                    textSize = 12f * chatScale
                    setTextColor(getColor(R.color.setup_muted))
                    setPadding(0, 0, 0, dp(4))
                })
            }
            val chosen = item.chosen
            if (chosen == null) {
                val buttons = LinearLayout(this@ChatActivity).apply { orientation = LinearLayout.VERTICAL }
                // Permitir primeiro e em destaque; as outras opções como botões de texto.
                for (option in item.options.sortedBy { it.kind.ordinal }) {
                    val primary = option.kind == PermissionOption.Kind.ALLOW_ONCE
                    val button = if (primary) Button(this@ChatActivity) else Button(this@ChatActivity, null, android.R.attr.borderlessButtonStyle)
                    button.text = optionLabel(item, option)
                    button.isAllCaps = false
                    button.textSize = 14f * chatScale
                    if (primary) {
                        button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.key_active))
                        button.setTextColor(getColor(R.color.setup_on_accent))
                    } else {
                        button.setTextColor(getColor(if (option.allows) R.color.accent_text else R.color.setup_error))
                    }
                    button.setOnClickListener { engine?.answer(item, option) }
                    buttons.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                card.addView(buttons)
            } else {
                card.addView(TextView(this@ChatActivity).apply {
                    text = optionLabel(item, chosen)
                    setTextColor(getColor(if (chosen.allows) R.color.setup_done else R.color.setup_error))
                    textSize = 13f * chatScale
                    setPadding(0, dp(4), 0, 0)
                })
            }
            return card
        }

        /** Trecho de edição (linhas "- " em vermelho e "+ " em verde) com o fundo dos blocos de código. */
        private fun diffView(diff: String) = TextView(this@ChatActivity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f * chatScale
            setTextColor(getColor(R.color.setup_text))
            setBackgroundResource(R.drawable.compose_bg)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(2); bottomMargin = dp(6) }
            setLineSpacing(0f, 1.1f)
            text = SpannableStringBuilder().apply {
                for ((index, line) in diff.lines().withIndex()) {
                    if (index > 0) append('\n')
                    val start = length
                    append(line)
                    val color = when {
                        line.startsWith("- ") -> R.color.setup_error
                        line.startsWith("+ ") -> R.color.setup_done
                        else -> R.color.setup_muted
                    }
                    setSpan(ForegroundColorSpan(getColor(color)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }

        /** Cartão que guia o primeiro login do agente. */
        private fun loginCard(item: ChatItem.Login): View {
            val card = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(dp(16), dp(14), dp(16), dp(12))
            }
            card.addView(TextView(this@ChatActivity).apply {
                text = getString(R.string.login_title, agentName)
                setTextColor(getColor(R.color.setup_text))
                textSize = 16f * chatScale
                setTypeface(typeface, Typeface.BOLD)
            })
            val viaBrowser = item.options.any { it.kind == AuthOption.Kind.AGENT }
            card.addView(TextView(this@ChatActivity).apply {
                text = getString(if (viaBrowser) R.string.login_browser_hint else R.string.login_screen_hint)
                setTextColor(getColor(R.color.setup_muted))
                textSize = 14f * chatScale
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(6), 0, dp(8))
            })
            when (item.state) {
                ChatItem.Login.State.WORKING -> {
                    card.addView(TextView(this@ChatActivity).apply {
                        setText(R.string.login_waiting)
                        setTextColor(getColor(R.color.accent_text))
                        textSize = 14f * chatScale
                    })
                    card.addView(
                        Button(this@ChatActivity, null, android.R.attr.borderlessButtonStyle).apply {
                            setText(R.string.chat_login_cancel)
                            isAllCaps = false
                            textSize = 14f * chatScale
                            setTextColor(getColor(R.color.accent_text))
                            setOnClickListener { (engine as? AcpChat)?.cancelLogin(item) }
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    )
                }
                ChatItem.Login.State.DONE -> card.addView(TextView(this@ChatActivity).apply {
                    setText(R.string.login_done)
                    setTextColor(getColor(R.color.setup_done))
                    textSize = 14f * chatScale
                })
                else -> {
                    item.message?.let { message ->
                        card.addView(TextView(this@ChatActivity).apply {
                            text = message
                            setTextColor(getColor(R.color.setup_error))
                            textSize = 13f * chatScale
                            setPadding(0, 0, 0, dp(6))
                        })
                    }
                    for ((index, option) in item.options.withIndex()) {
                        val primary = index == 0
                        val button = if (primary) Button(this@ChatActivity) else Button(this@ChatActivity, null, android.R.attr.borderlessButtonStyle)
                        button.text = when (option.kind) {
                            AuthOption.Kind.AGENT -> option.name
                            AuthOption.Kind.TERMINAL -> getString(R.string.login_in_terminal, option.name)
                            AuthOption.Kind.AGENT_SCREEN -> getString(R.string.login_open_screen, agentName)
                        }
                        button.isAllCaps = false
                        button.textSize = 14f * chatScale
                        if (primary) {
                            button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.key_active))
                            button.setTextColor(getColor(R.color.setup_on_accent))
                        } else {
                            button.setTextColor(getColor(R.color.accent_text))
                        }
                        button.setOnClickListener { startLogin(item, option) }
                        card.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                }
            }
            return card
        }

        private fun planView(item: ChatItem.Plan, old: TextView?) = (old ?: TextView(this@ChatActivity)).apply {
            text = getString(R.string.chat_plan) + "\n" + item.entries.joinToString("\n") { entry ->
                val mark = when (entry.status) { "completed" -> "☑"; "in_progress" -> "◐"; else -> "☐" }
                "$mark ${entry.text}"
            }
            textSize = 14f * chatScale
            setTextColor(getColor(R.color.setup_muted))
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        /** Aviso da conversa; com [ChatItem.Info.details], um link "Detalhes" abre o erro completo. */
        private fun infoText(item: ChatItem.Info, old: TextView?) = (old ?: TextView(this@ChatActivity)).apply {
            val details = item.details
            text = if (details == null) {
                item.text
            } else {
                SpannableStringBuilder(item.text).apply {
                    append("\n")
                    val start = length
                    append(getString(R.string.chat_error_details))
                    setSpan(
                        object : ClickableSpan() {
                            override fun onClick(widget: View) = showDetails(R.string.chat_error_details, details)

                            override fun updateDrawState(ds: TextPaint) {
                                ds.isUnderlineText = true
                                ds.color = getColor(R.color.accent_text)
                            }
                        },
                        start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            movementMethod = if (details != null) LinkMovementMethod.getInstance() else null
            gravity = Gravity.CENTER
            textSize = 13f * chatScale
            setTextColor(getColor(if (item.isError) R.color.setup_error else R.color.setup_muted))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnLongClickListener { copy(item.text); true }
        }
    }

    /** Pedaço de uma resposta: texto comum, bloco de código (```) ou tabela. */
    private class Part(val text: String, val isCode: Boolean, val language: String = "")

    /** Resposta pronta para mostrar: as partes e, para cada uma, o texto formatado (null = bloco de código). */
    private class Rendered(val text: String, val parts: List<Part>, val bodies: List<CharSequence?>)

    private fun splitCode(text: String): List<Part> {
        val parts = mutableListOf<Part>()
        val buffer = StringBuilder()
        val table = StringBuilder()
        var inCode = false
        var language = ""
        fun flushTable() {
            if (table.isEmpty()) return
            if (buffer.isNotBlank()) parts += Part(buffer.toString(), isCode = false)
            buffer.clear()
            parts += Part(formatTable(table.toString()), isCode = true, language = "")
            table.clear()
        }
        for (line in text.split('\n')) {
            if (!inCode && line.trimStart().startsWith("|")) {
                table.append(line).append('\n')
                continue
            }
            flushTable()
            if (line.trimStart().startsWith("```")) {
                if (inCode) {
                    parts += Part(buffer.toString(), isCode = true, language = language)
                } else if (buffer.isNotBlank()) {
                    parts += Part(buffer.toString(), isCode = false)
                }
                buffer.clear()
                language = if (inCode) "" else line.trimStart().removePrefix("```").trim()
                inCode = !inCode
                continue
            }
            buffer.append(line).append('\n')
        }
        flushTable()
        if (buffer.isNotBlank() || parts.isEmpty()) parts += Part(buffer.toString(), isCode = inCode, language = language)
        return parts
    }

    /** Tabela Markdown alinhada em colunas (fonte de terminal, com rolagem lateral). */
    private fun formatTable(source: String): String {
        val rows = source.lines().filter { it.isNotBlank() }
            .filterNot { row -> row.all { it == '|' || it == '-' || it == ':' || it == ' ' } }
            .map { row ->
                row.trim().removePrefix("|").removeSuffix("|").split('|')
                    .map { it.trim().replace("**", "").replace("`", "") }
            }
        if (rows.isEmpty()) return source
        val columns = rows.maxOf { it.size }
        val widths = (0 until columns).map { c -> rows.maxOf { it.getOrNull(c)?.length ?: 0 }.coerceAtMost(40) }
        return rows.mapIndexed { index, row ->
            val line = (0 until columns).joinToString(" │ ") { c -> cell(row.getOrNull(c) ?: "").padEnd(widths[c]) }
            if (index == 0 && rows.size > 1) line + "\n" + widths.joinToString("─┼─") { "─".repeat(it) } else line
        }.joinToString("\n")
    }

    companion object {
        /** Célula com no máximo 40 caracteres; o corte aparece como reticências. */
        private fun cell(text: String) = if (text.length > 40) text.take(39) + "…" else text

        /** Agentes com o adaptador sendo instalado agora, em qualquer tela do chat (evita instalar em paralelo). */
        private val installing = HashSet<String>()
        /** Quantas sugestões de comando "/" aparecem de uma vez. */
        const val COMMAND_SUGGESTIONS = 6
        /** Altura de cada linha do popup de comandos. */
        const val COMMAND_ROW_DP = 48
        /** Tamanho padrão da caixa de texto do chat (sp), antes da escala. */
        const val INPUT_TEXT_SP = 16f
        /** Modo Detalhado: linhas do começo da saída de cada ação, embaixo da linha dela. */
        const val TOOL_EXCERPT_LINES = 6
        /** A partir deste percentual do contexto usado, aparece o chip de compactar. */
        const val COMPACT_PERCENT = 80
        /** Hash de commit (SHA-1 ou SHA-256) como o rev-parse devolve. */
        private val SHA = Regex("[0-9a-f]{40,64}")
        const val EXTRA_AGENT_ID = "agent_id"
        const val EXTRA_RESUME = "resume"
        const val EXTRA_NEW = "new"
        /** Pasta do trabalho (agente + pasta) que a tela abre; sem ela, a última pasta usada. */
        const val EXTRA_FOLDER = "folder"
        /** Arquivos recebidos pelo "Compartilhar" (ArrayList<Uri>). */
        const val EXTRA_SHARED_URIS = "shared_uris"
        /** Texto recebido pelo "Compartilhar" (vai para o campo de mensagem, sem enviar). */
        const val EXTRA_SHARED_TEXT = "shared_text"
        /** Texto que a tela envia ao agente assim que o chat deste trabalho estiver ligado (ex.: pedir o commit). */
        const val EXTRA_SEND = "send_text"
        private const val STATE_PENDING_SEND = "pending_send"
        private val MODELS = arrayOf("", "opus", "sonnet", "haiku")
        private val EFFORTS = arrayOf("", "low", "medium", "high", "xhigh", "max")
    }
}

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
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.Formatter
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
import android.widget.AbsListView
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.jonjonesbr.jangada.agents.AgentCatalog
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.chat.AcpChat
import io.github.jonjonesbr.jangada.chat.AgentChat
import io.github.jonjonesbr.jangada.chat.AgentStatus
import io.github.jonjonesbr.jangada.chat.ModeText
import io.github.jonjonesbr.jangada.chat.Attachment
import io.github.jonjonesbr.jangada.chat.AttachmentTooLargeException
import io.github.jonjonesbr.jangada.chat.AutoCompact
import io.github.jonjonesbr.jangada.chat.autoCompactShort
import io.github.jonjonesbr.jangada.chat.autoCompactText
import io.github.jonjonesbr.jangada.chat.PlanLimits
import io.github.jonjonesbr.jangada.chat.PlanLimitsParser
import io.github.jonjonesbr.jangada.chat.PlanLimitsStore
import io.github.jonjonesbr.jangada.chat.formatLimitReset
import io.github.jonjonesbr.jangada.chat.limitName
import io.github.jonjonesbr.jangada.chat.limitsSummary
import io.github.jonjonesbr.jangada.chat.AuthOption
import io.github.jonjonesbr.jangada.chat.Attachments
import io.github.jonjonesbr.jangada.chat.BackgroundTask
import io.github.jonjonesbr.jangada.chat.ChatDrafts
import io.github.jonjonesbr.jangada.chat.ChatExport
import io.github.jonjonesbr.jangada.chat.ChatEngine
import io.github.jonjonesbr.jangada.chat.ChatHistory
import io.github.jonjonesbr.jangada.chat.ChatItem
import io.github.jonjonesbr.jangada.chat.ChildAgents
import io.github.jonjonesbr.jangada.chat.ChildState
import io.github.jonjonesbr.jangada.chat.ChildSummary
import io.github.jonjonesbr.jangada.chat.OpenCodeStats
import io.github.jonjonesbr.jangada.chat.ChatState
import io.github.jonjonesbr.jangada.chat.ChatView
import io.github.jonjonesbr.jangada.chat.Markdown
import io.github.jonjonesbr.jangada.chat.chatState
import io.github.jonjonesbr.jangada.chat.PermissionOption
import io.github.jonjonesbr.jangada.chat.PermissionRisk
import io.github.jonjonesbr.jangada.chat.SlashCommand
import io.github.jonjonesbr.jangada.chat.ToolState
import io.github.jonjonesbr.jangada.chat.ToolText
import io.github.jonjonesbr.jangada.chat.friendlyModel
import io.github.jonjonesbr.jangada.linux.Git
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.OpenCodeAuth
import io.github.jonjonesbr.jangada.linux.Workspace
import io.github.jonjonesbr.jangada.linux.Worktrees
import io.github.jonjonesbr.jangada.ui.Sheet
import io.github.jonjonesbr.jangada.ui.askCloneRepo
import io.github.jonjonesbr.jangada.ui.askNewProject
import io.github.jonjonesbr.jangada.ui.askWorktreeName
import io.github.jonjonesbr.jangada.ui.dpToPx
import io.github.jonjonesbr.jangada.ui.showCloneFailure
import io.github.jonjonesbr.jangada.ui.showCloneProgress
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap
import kotlin.concurrent.thread

/**
 * Chat com um agente (Modo Simples): balões, permissões com botões,
 * anexos e a troca para a tela de terminal pelo botão do topo.
 * O Claude Code usa o protocolo dele; os outros agentes, o ACP.
 */
class ChatActivity : JangadaActivity() {

    private lateinit var list: ListView
    private lateinit var listFrame: View
    private lateinit var jumpEnd: View
    private lateinit var empty: TextView
    private lateinit var status: View
    private lateinit var statusText: TextView
    /** Só gira enquanto o agente trabalha: em pedido de permissão ou travamento a faixa não pode parecer ativa. */
    private lateinit var statusSpinner: View
    private lateinit var stopButton: View
    private lateinit var input: EditText
    /** Nome do agente acima do subtítulo; some no modo compacto (ver [applyCompact]). */
    private lateinit var titleView: TextView
    private lateinit var subtitle: TextView
    private lateinit var titleBlock: View
    private lateinit var continueChip: TextView
    /** Atalhos acima da caixa (ver [updateQuickbar]): a barra e os cinco chips, criados no layout. */
    private lateinit var quickbar: View
    private lateinit var chipFolder: TextView
    private lateinit var chipModel: TextView
    private lateinit var chipMode: TextView
    private lateinit var chipView: TextView
    private lateinit var chipContext: TextView
    private lateinit var chipLimits: TextView
    private lateinit var chipChanges: TextView
    /** Faixa fixa do pedido de permissão pendente (acima dos atalhos; ver [updatePermissionBar]). */
    private lateinit var permissionBar: View
    private lateinit var permissionTitle: TextView
    private lateinit var permissionText: TextView
    private lateinit var permissionDetails: Button
    private lateinit var attachmentsRow: LinearLayout
    private lateinit var prepareActions: LinearLayout
    private lateinit var sendButton: View
    private lateinit var settings: AppSettings
    private lateinit var linux: LinuxEnv
    private lateinit var agentId: String
    private var recipe: AgentRecipe? = null
    private val adapter = ChatAdapter()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Relógio da faixa de status (ver [updateStatus]): a cada segundo reescreve só o texto dela, para o
     * tempo ("há 0:12") andar sem mensagem nova, e o tempo dos cartões de subagente que ainda rodam
     * (ver `ChatAdapter.refreshChildTimes`). Nunca redesenha a lista da conversa. Roda entre
     * onResume e onPause; um só para a tela, não um por item. Só fica agendado enquanto o tempo anda.
     */
    private val statusClock = object : Runnable {
        override fun run() {
            // Cada updateStatus decide se o relógio continua (startStatusClock) ou para (stopStatusClock).
            engine?.let { updateStatus(it) }
            adapter.refreshChildTimes()
        }
    }

    /** Liga o relógio da faixa (um só para a tela; o updateStatus o mantém enquanto o tempo anda). */
    private fun startStatusClock() {
        handler.removeCallbacks(statusClock)
        handler.postDelayed(statusClock, STATUS_CLOCK_MS)
    }

    /** Para o relógio da faixa (ocioso ou agente perdido: não há tempo andando). */
    private fun stopStatusClock() {
        handler.removeCallbacks(statusClock)
    }

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

    /** Há uma tarefa de contas do OpenCode rodando (uma por vez). Lida e escrita só na thread principal. */
    private var openCodeBusy = false
    /** O trabalho isolado desta tela está sendo tirado: os chats da pasta fecham e não reabrem. */
    private var removingWorktree = false
    /** Remoção pedida sem o serviço ligado (tela parada): roda quando ele voltar. */
    private var pendingRemoval: Pair<WorktreeJob, Boolean>? = null
    /** Diálogo "já há agentes demais" aberto (evita abrir dois). */
    private var jobsFullDialog: Dialog? = null
    /** Painel de pouca memória aberto (evita abrir dois). */
    private var memoryLowDialog: Dialog? = null
    /** Pasta do trabalho desta tela (nome dentro de ~/projetos; "" = a raiz); validada em onCreate. */
    private var folder = ""
    /** Branch do git da pasta (null = não é repositório, ou ainda não foi lido); lido fora da thread principal. */
    private var folderBranch: String? = null
    /** Repositório de origem, se a pasta é um trabalho isolado (worktree). */
    private var folderWorktreeOf: String? = null
    /** Arquivos alterados da pasta (git status); null = não lido ou sem git. */
    private var changedFiles: Int? = null
    /** Número da leitura de mudanças mais nova: resultado de uma leitura anterior é descartado. */
    private var changesGeneration = 0
    /** O branch e o repositório de origem da pasta já foram lidos (o menu espera por isso). */
    private var folderInfoRead = false
    /** busy do último redesenho (a contagem de mudanças é relida quando o turno acaba). */
    private var changesBusy = false
    /** Tela baixa (teclado aberto ou paisagem): os atalhos saem e a faixa de permissão encolhe. */
    private var compactLayout = false
    /** Pedido de permissão que a faixa fixa mostra agora (o primeiro pendente); null = faixa escondida. */
    private var barPermission: ChatItem.Permission? = null
    private var engine: AgentChat? = null
    private var enterAt = -1
    private val pendingAttachments = mutableListOf<Attachment>()
    /** Texto pedido por [EXTRA_SEND] (ex.: pedir o commit), enviado quando o chat deste trabalho estiver ligado. */
    private var pendingSend: String? = null
    /** Seletor de arquivos do sistema (vários arquivos, qualquer tipo). */
    private val attachPicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) prepareAttachments(uris)
    }
    /** Texto da conversa à espera de o usuário escolher onde salvar (ver [exportConversation]). */
    private var pendingExport: String? = null
    /** Onde salvar a conversa exportada (arquivo Markdown escolhido pelo usuário). */
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri == null || text == null) return@registerForActivityResult
        thread(name = "chat-export") {
            val saved = runCatching {
                val stream = contentResolver.openOutputStream(uri) ?: error("no output stream")
                stream.use { it.write(text.toByteArray()) }
            }.isSuccess
            runOnUiThread {
                if (!isDestroyed) Toast.makeText(
                    this,
                    if (saved) R.string.chat_export_done else R.string.chat_export_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /** Adaptador ACP ainda não pronto (instalando, ou falhou): campo de texto e Enviar desligados. */
    private var preparing = false

    /** Sugestões de comandos "/" (do app e do agente), acima da caixa de texto. */
    private var commandPopup: ListPopupWindow? = null
    /** Sugestões que o popup mostra agora (mesma ordem da lista). */
    private var commandMatches: List<Suggestion> = emptyList()

    /** Comandos "/" que o próprio app atende (além dos do agente); o popup os põe primeiro. */
    private enum class AppCommand(val command: String, val descRes: Int) {
        NEW("new", R.string.chat_cmd_new),
        CLEAR("clear", R.string.chat_cmd_clear),
        RESET("reset", R.string.chat_cmd_reset),
        RESUME("resume", R.string.chat_cmd_resume),
        COMPACT("compact", R.string.chat_cmd_compact),
    }

    /** Uma linha do popup: um comando do app ou um comando do agente (o outro fica nulo). */
    private data class Suggestion(val label: String, val app: AppCommand?, val agent: SlashCommand?)

    private val isClaude get() = agentId == TerminalService.CLAUDE
    private val agentName get() = recipe?.name ?: getString(R.string.chat_title)
    /** Pasta de trabalho do chat (caminho dentro do Debian). */
    private val chatCwd: String get() = engine?.cwd ?: Workspace.path(folder)
    /** Chave do trabalho desta tela (agente + pasta), como no serviço. */
    private val chatKey: String get() = TerminalService.chatKey(agentId, folder)
    /** Nomes dos agentes pelo id (o catálogo é lido uma vez, para as linhas de trabalho). */
    private val agentNames by lazy { AgentCatalog.load(this).associate { it.id to it.name } }
    /** Rascunhos da caixa de texto, um por trabalho (o texto não enviado sobrevive a sair da tela). */
    private val drafts by lazy { ChatDrafts(this) }
    private val limitsStore by lazy { PlanLimitsStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        settings = AppSettings(this)
        linux = LinuxEnv(this)
        agentId = intent.getStringExtra(EXTRA_AGENT_ID) ?: TerminalService.CLAUDE
        recipe = AgentCatalog.load(this).firstOrNull { it.id == agentId }

        list = findViewById(R.id.chat_list)
        listFrame = findViewById(R.id.chat_list_frame)
        jumpEnd = findViewById(R.id.chat_jump_end)
        empty = findViewById(R.id.chat_empty)
        status = findViewById(R.id.chat_status)
        statusText = findViewById(R.id.chat_status_text)
        statusSpinner = findViewById(R.id.chat_status_spinner)
        stopButton = findViewById(R.id.chat_stop)
        input = findViewById(R.id.chat_input)
        titleView = findViewById(R.id.chat_title)
        subtitle = findViewById(R.id.chat_subtitle)
        titleBlock = findViewById(R.id.chat_title_block)
        continueChip = findViewById(R.id.chat_continue)
        quickbar = findViewById(R.id.chat_quickbar)
        chipFolder = findViewById(R.id.chat_chip_folder)
        chipModel = findViewById(R.id.chat_chip_model)
        chipMode = findViewById(R.id.chat_chip_mode)
        chipView = findViewById(R.id.chat_chip_view)
        chipContext = findViewById(R.id.chat_chip_context)
        chipLimits = findViewById(R.id.chat_chip_limits)
        chipChanges = findViewById(R.id.chat_chip_changes)
        permissionBar = findViewById(R.id.chat_permission_bar)
        permissionTitle = findViewById(R.id.chat_permission_title)
        permissionText = findViewById(R.id.chat_permission_text)
        permissionDetails = findViewById(R.id.chat_permission_details)
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
        // Rascunho deste trabalho (o que ficou na caixa ao sair). Girar a tela não entra aqui: o próprio
        // campo restaura o texto. Vem antes do "Enter envia" (mais abaixo), para uma quebra de linha
        // do rascunho não mandar a mensagem sozinha.
        if (savedInstanceState == null) {
            drafts.get(chatKey).takeIf { it.isNotEmpty() }?.let {
                input.setText(it)
                input.setSelection(input.text.length)
            }
        }

        // Pedido de envio (Mudanças). Girar a tela não repete o extra: vale o que ficou guardado.
        pendingSend = if (savedInstanceState != null) {
            savedInstanceState.getString(STATE_PENDING_SEND)
        } else {
            intent.getStringExtra(EXTRA_SEND)
        }
        intent.removeExtra(EXTRA_SEND)
        // Tela recriada (idioma, tamanho da fonte, o Android a fechou em segundo plano): os anexos voltam.
        val kept = keptAttachments.remove(chatKey)
        if (savedInstanceState != null && !kept.isNullOrEmpty()) {
            pendingAttachments += kept
            renderAttachments()
        }
        // Anexos que estavam na caixa ao sair da tela: só quando a tela não foi recriada (ela já os trouxe).
        if (savedInstanceState == null && pendingAttachments.isEmpty()) restoreDraftAttachments()
        findViewById<View>(R.id.chat_home).setOnClickListener { goHome() }
        sendButton.setOnClickListener { send() }
        findViewById<View>(R.id.chat_attach).setOnClickListener { pickAttachment() }
        stopButton.setOnClickListener { engine?.interrupt() }
        jumpEnd.setOnClickListener { scrollToEnd() }
        // O botão de ir ao fim acompanha a rolagem.
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                updateJumpButton()
                barPermission?.let { permissionBar.visibility = if (permissionCardVisible(it)) View.GONE else View.VISIBLE }
            }
        })
        // Tocar na faixa com tarefas em segundo plano abre o painel para pará-las (isClickable em updateStatus).
        status.setOnClickListener { engine?.let { showBackgroundSheet(it) } }
        findViewById<View>(R.id.chat_terminal).setOnClickListener { switchToTerminal() }
        findViewById<View>(R.id.chat_menu).setOnClickListener { showMenu() }
        continueChip.setOnClickListener { sendAwake(getString(R.string.chat_continue_prompt)) }
        // Agente e pasta formam um só alvo de toque: tocar abre os trabalhos (trocar de pasta ou de agente).
        titleBlock.setOnClickListener { showJobs() }
        chipFolder.setOnClickListener { showJobs() }
        chipModel.setOnClickListener { showModelSheet() }
        chipMode.setOnClickListener { showModeSheet() }
        chipView.setOnClickListener { chooseView() }
        chipContext.setOnClickListener { engine?.let { showContextSheet(it) } }
        chipLimits.setOnClickListener { showLimitsSheet() }
        chipChanges.setOnClickListener { openChanges() }
        permissionDetails.setOnClickListener { showBarPermission() }
        // A faixa inteira também leva ao cartão (o botão Detalhes é só o convite).
        permissionBar.setOnClickListener { showBarPermission() }
        // Teclado aberto (adjustResize) ou janela baixa (paisagem): a conversa tem prioridade.
        // Só a altura não bastava: com o teclado aberto a janela ainda passa de 420dp em muitos celulares,
        // e com fonte grande a caixa come a tela. Não depende do que está dentro da janela, então não há vai e volta.
        findViewById<View>(android.R.id.content).addOnLayoutChangeListener { v, _, top, _, bottom, _, _, _, _ ->
            val ime = ViewCompat.getRootWindowInsets(v)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val compact = ime || (bottom - top) < dp(COMPACT_HEIGHT_DP)
            if (compact != compactLayout) {
                compactLayout = compact
                // Mexer na visibilidade dentro do próprio layout não vale: adia para depois dele.
                v.post {
                    if (isDestroyed) return@post
                    applyCompact()
                }
            }
        }
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
        // Tela recriada: a intent volta com os extras originais (o removeExtra vale só para a cópia daqui), e
        // os anexos dela já vieram guardados (keptAttachments). Ler de novo os duplicaria.
        if (savedInstanceState == null) takeShared(intent)
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
        // As imagens em base64 não cabem no Bundle: os anexos ficam guardados no processo (ver keptAttachments).
        if (pendingAttachments.isNotEmpty()) keptAttachments[chatKey] = pendingAttachments.toList()
        else keptAttachments.remove(chatKey)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        service?.visibleChat = chatKey
        applyTextScale()
        // O branch e as mudanças podem ter mudado no terminal enquanto a tela estava em segundo plano
        // (as mudanças são lidas quando o branch chega: só pasta git conta).
        readFolderInfo()
        // Ao voltar com "/..." na caixa, as sugestões reaparecem (depois da janela estar pronta).
        input.post { updateCommandSuggestions() }
        // Faixa de status: um relógio só, a cada segundo, para o tempo andar (ver statusClock).
        startStatusClock()
    }

    override fun onPause() {
        resumed = false
        stopStatusClock()
        // Só larga o aviso se ele ainda for o deste trabalho.
        if (service?.visibleChat == chatKey) service?.visibleChat = null
        // O texto não enviado vira rascunho deste trabalho: ir à tela inicial, trocar de trabalho,
        // abrir o terminal ou ir para outro app não o perde. Os anexos pendentes também (nome e caminho;
        // a imagem é refeita ao voltar, em [restoreDraftAttachments]).
        drafts.put(chatKey, input.text.toString())
        drafts.putAttachments(chatKey, pendingAttachments.map { Triple(it.name, it.guestPath, it.mimeType) })
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
        stopStatusClock()
        handler.removeCallbacksAndMessages(null)
        // Saiu de vez (não vai ser recriada): os anexos guardados não voltam, e a memória é liberada.
        if (isFinishing) keptAttachments.remove(chatKey)
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
            // A pasta está sendo excluída ou renomeada (tela Projetos): o chat dela não abre agora.
            if (svc.folderBusy(folder)) {
                Toast.makeText(this@ChatActivity, R.string.projects_folder_busy, Toast.LENGTH_LONG).show()
                finish()
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
     * precisa de vaga (um agente parado pode dormir para liberá-la). Sem vaga, mostra os
     * trabalhos abertos para dormir ou fechar um antes. Com vaga, um agente novo ainda confere
     * a memória do celular (ver [checkMemory]).
     */
    private fun withRoom(svc: TerminalService, open: () -> Unit) {
        // A pasta do trabalho isolado está saindo: nenhum chat novo nela.
        if (removingWorktree) return
        // Par rodando é substituído (não soma) e par dormindo só acorda: nenhum deles sobe agente novo.
        val existing = svc.chat(agentId, folder)
        val startsNew = existing?.running != true && existing?.asleep != true
        // Sem vaga, um parado dorme para liberá-la (freeSlotFor): troca um por outro, sem somar
        // agente, como ao escolher na lista de [showJobsFull]. Aí a memória não é conferida.
        val hadRoom = svc.canOpenChat(agentId, folder)
        when {
            !svc.makeRoomFor(agentId, folder) -> showJobsFull(svc, open)
            startsNew && hadRoom -> checkMemory(svc, open)
            else -> open()
        }
    }

    /**
     * Confere a memória antes de subir um agente novo. Com folga e memória de sobra para o pico que o
     * agente costuma usar (ou sem histórico dele), abre direto; senão mostra o painel de [showMemoryLow]
     * e o usuário decide.
     */
    private fun checkMemory(svc: TerminalService, open: () -> Unit) {
        val info = svc.memoryWatch.memoryInfo()
        val usable = (info.availMem - info.threshold).coerceAtLeast(0L)
        val peak = svc.memoryWatch.peaks.typicalBytes(agentId)
        val level = MemoryPolicy.level(info.availMem, info.threshold, info.lowMemory)
        if (level == MemoryPolicy.Level.OK && (peak == null || usable >= peak)) {
            open()
            return
        }
        val size = { bytes: Long -> Formatter.formatShortFileSize(this, bytes) }
        // O texto do pico só quando é ele que não cabe; com o pico cabendo e o nível apertado, o aviso geral.
        val message = if (peak != null && usable < peak) {
            getString(R.string.memory_low_text_peak, size(usable), agentNameOf(agentId), size(peak))
        } else {
            getString(R.string.memory_low_text, size(info.availMem))
        }
        showMemoryLow(svc, message, open)
    }

    /**
     * Painel de pouca memória: pôr os parados para dormir e abrir (só aparece se há quem dorme), ou
     * abrir mesmo assim. Cancelar sai desta tela se ela não tem conversa para mostrar.
     */
    private fun showMemoryLow(svc: TerminalService, message: String, open: () -> Unit) {
        if (memoryLowDialog?.isShowing == true) return
        val rows = mutableListOf<Sheet.Row>()
        if (svc.memoryWatch.idleCandidates(chatKey) > 0) {
            rows += Sheet.Row(
                title = getString(R.string.memory_low_sleep_open),
                description = getString(R.string.memory_low_sleep_open_desc),
            ) {
                svc.memoryWatch.sleepIdleNow(chatKey)
                if (!isDestroyed) open()
            }
        }
        rows += Sheet.Row(
            title = getString(R.string.memory_low_open),
            description = getString(R.string.memory_low_open_desc),
        ) {
            if (!isDestroyed) open()
        }
        memoryLowDialog = Sheet(this)
            .title(getString(R.string.memory_low_title))
            .message(message)
            .rows(rows)
            .onDismiss { if (engine == null) finish() }
            .show()
    }

    /**
     * Já há [TerminalService.maxChats] agentes rodando (ou mais). Tocar num deles o põe para dormir (a conversa
     * continua na lista) ou, se ele não pode dormir, o fecha; então [open] roda. Cancelar (ou voltar)
     * sai desta tela se ela não tem conversa para mostrar.
     */
    private fun showJobsFull(svc: TerminalService, open: () -> Unit) {
        // A tela que para e volta pede de novo: um painel só.
        if (jobsFullDialog?.isShowing == true) return
        // Só os que rodam: um trabalho parado não ocupa vaga.
        val rows = svc.runningChats().map { slot ->
            // Dormir só vale com como retomar a conversa e sem turno em andamento.
            val canSleep = slot.chat.canResume && chatState(slot.chat) == ChatState.READY
            Sheet.Row(
                title = jobName(agentNameOf(slot.agentId), slot.folder),
                value = stateText(this, chatState(slot.chat)),
                description = getString(if (canSleep) R.string.sheet_desc_sleep_job else R.string.sheet_desc_close_job),
                destructive = !canSleep,
            ) {
                val slept = slot.chat.sleep()
                if (slept) {
                    if (!isDestroyed) open()
                } else if (!canSleep) {
                    svc.closeChat(slot.agentId, slot.folder) {
                        if (!isDestroyed) open()
                    }
                }
                // Podia dormir e não dormiu (o agente ficou ocupado depois de a lista abrir): não fecha,
                // para não perder o turno. A lista da tela inicial mostra o estado de novo.
            }
        }
        // Cancelar, voltar ou tocar fora (sem escolher trabalho) sai desta tela se ela não tem conversa.
        // O título conta os que rodam, não o limite: se o limite baixou nos ajustes, podem ser mais.
        val running = rows.size
        jobsFullDialog = Sheet(this)
            .title(resources.getQuantityString(R.plurals.chat_jobs_full_title, running, running))
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
        listFrame.visibility = View.GONE
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
        listFrame.visibility = View.GONE
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
    /** Abre no navegador o endereço de um aviso (só https); sem navegador, avisa. */
    private fun openInfoUrl(url: String) {
        if (!url.startsWith("https://")) return
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        }.isSuccess
        if (!opened) Toast.makeText(this, url, Toast.LENGTH_LONG).show()
    }

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

    /**
     * Marca de uma ação: … em andamento, ✕ se falhou, – se cancelada ou recusada (o usuário parou),
     * ✓ se terminou.
     */
    private fun toolMark(tool: ChatItem.Tool) = when {
        tool.state == ToolState.FAILED -> "✕"
        tool.state == ToolState.CANCELLED || tool.state == ToolState.REJECTED -> "–"
        tool.done -> "✓"
        else -> "…"
    }

    /** Rótulo humano da ferramenta: o nome do protocolo (ex.: "shell") quando o título é genérico. */
    private fun toolLabel(tool: ChatItem.Tool): String =
        ToolText.label(this, tool.toolName.ifEmpty { tool.name })

    /** Linha de uma ação: marca, rótulo e o comando ou arquivo envolvido. */
    private fun toolLineText(tool: ChatItem.Tool) = "${toolMark(tool)} ${toolLabel(tool)}  ${tool.summary}"

    /** Modo Resumo: as ações do grupo, uma por linha; a que tem saída abre num toque. */
    private fun showToolGroup(item: ChatItem.ToolGroup) {
        // Cada ação: rótulo e resumo, com a marca (…, ✕, ✓) como ícone; a que tem saída abre num toque.
        val rows = item.tools.map { tool ->
            Sheet.Row(
                title = toolLabel(tool) + "  " + tool.summary,
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
        readChanges()
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

    /**
     * Título: o agente na primeira linha; na segunda, a pasta em que ele trabalha ("~/projetos" na
     * raiz, senão a pasta e o branch do git) e a seta que indica o toque para trocar de trabalho.
     */
    private fun updateTitle() {
        titleView.text = agentName
        val branch = folderBranch
        val place = when {
            folder.isEmpty() -> displayPath(Workspace.ROOT)
            branch != null -> "$folder · $branch"
            else -> folder
        }
        subtitle.text = "$place ▾"
        titleBlock.contentDescription = when {
            folder.isEmpty() -> getString(R.string.chat_title_description_plain, agentName, getString(R.string.chat_folder_root))
            branch != null -> getString(R.string.chat_title_description, agentName, folder, branch)
            else -> getString(R.string.chat_title_description_plain, agentName, folder)
        }
    }

    /**
     * Lê o branch e o repositório de origem da pasta (só arquivos do git, sem o programa) fora da
     * thread principal, e atualiza o título. Chamado ao voltar para a tela.
     */
    private fun readFolderInfo(then: (() -> Unit)? = null) {
        val dir = Workspace.hostPath(linux.rootfs, Workspace.path(folder))
        thread(name = "chat-folder-info") {
            val branch = Workspace.gitBranch(linux.rootfs, dir)
            val worktreeOf = Workspace.worktreeOf(dir)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                folderBranch = branch
                folderWorktreeOf = worktreeOf
                folderInfoRead = true
                updateTitle()
                updateQuickbar()
                readChanges()
                then?.invoke()
            }
        }
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
            messages += when {
                // Pergunta e plano não são "pedido de permissão"; o plano inteiro não é lido (cortaria as falas seguintes).
                request.questions.isNotEmpty() -> getString(R.string.chat_question_wants, agentName)
                request.toolName == ChatEngine.PLAN_TOOL -> getString(R.string.chat_plan_wants, agentName)
                else -> {
                    val what = listOf(request.title, request.summary.take(ANNOUNCE_MAX)).filter { it.isNotBlank() }.joinToString(": ")
                    getString(R.string.chat_announce_permission, what)
                }
            }
        }
        // Uma só fala: dois anúncios seguidos fariam o segundo cortar o primeiro.
        val text = messages.filter { it.isNotBlank() }.joinToString(". ")
        if (text.isNotEmpty()) list.announceForAccessibility(text)
    }

    /** Tamanho do texto (ajustes do chat): a caixa e as linhas da conversa seguem a escolha. */
    private fun applyTextScale() {
        val scale = settings.chatTextScale
        input.textSize = INPUT_TEXT_SP * scale
        for (chip in listOf(chipFolder, chipModel, chipMode, chipView, chipContext, chipLimits, chipChanges)) chip.textSize = CHIP_TEXT_SP * scale
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
        // O turno que acabou pode ter editado arquivos: a contagem do atalho de mudanças é lida de novo.
        if (changesBusy && !chat.busy) readChanges()
        changesBusy = chat.busy
        // Vazia é o que a tela mostra (só raciocínio, no Normal, não deve deixar a lista em branco).
        val isEmpty = adapter.snapshot.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        listFrame.visibility = if (isEmpty) View.GONE else View.VISIBLE
        list.post { updateJumpButton() }
        updateStatus(chat)
        continueChip.visibility = if (chat.interrupted && !chat.busy) View.VISIBLE else View.GONE
        updatePermissionBar(chat)
        updateQuickbar()
        // Comandos que o agente acabou de anunciar também podem entrar na sugestão.
        updateCommandSuggestions()
    }

    /** A lista mostra o fim da conversa: o último item visível e inteiro até a borda de baixo. */
    private fun atEnd(): Boolean {
        val count = list.count
        if (count == 0) return true
        if (list.lastVisiblePosition < count - 1) return false
        val last = list.getChildAt(list.childCount - 1) ?: return true
        // Folga de alguns pixels para arredondamentos da rolagem.
        return last.bottom <= list.height - list.paddingBottom + END_SLACK_PX
    }

    /** O botão de ir ao fim aparece só quando a lista está visível e longe do fim. */
    private fun updateJumpButton() {
        jumpEnd.visibility = if (listFrame.visibility == View.VISIBLE && !atEnd()) View.VISIBLE else View.GONE
    }

    /**
     * Rola até o fim da conversa: a borda de baixo do último item encostada na de baixo da lista.
     * Mede em vez de estimar (o smoothScrollToPosition erra com itens mais altos que a tela, e com o
     * último item já à vista alinhava o topo dele, subindo a lista). Com o último item na tela e pouco
     * a rolar, rola suave; senão salta até ele e acerta o fim (ver [showEndOfLast]).
     */
    private fun scrollToEnd() {
        val last = list.count - 1
        if (last < 0) return
        // Para uma rolagem em andamento (fling), que brigaria com a nova.
        list.smoothScrollBy(0, 0)
        if (list.lastVisiblePosition == last) {
            val delta = endDelta()
            if (delta in 1 until list.height) list.smoothScrollBy(delta, JUMP_SCROLL_MS) else showEndOfLast()
            return
        }
        list.setSelection(last)
        // Depois do layout do setSelection: um item mais alto que a tela fica com o topo à vista; mostra o fim.
        list.post { showEndOfLast() }
    }

    /**
     * Rola, sem animação, até o fim do último item. O scrollListBy anda no máximo quase uma altura da
     * lista por chamada (o AbsListView descarta o resto), então vai em passos até não faltar nada.
     */
    private fun showEndOfLast() {
        if (isDestroyed) return
        val step = list.height - list.paddingTop - list.paddingBottom - 1
        if (step <= 0) return
        var previous = Int.MAX_VALUE
        // Limite só por segurança; cada passo anda quase uma tela.
        repeat(END_MAX_STEPS) {
            val delta = endDelta()
            // Sem nada a rolar, ou sem avanço desde o passo anterior: terminou.
            if (delta <= 0 || delta >= previous) return
            previous = delta
            list.scrollListBy(minOf(delta, step))
        }
    }

    /** Quanto o último item passa da borda de baixo da lista; 0 se ele não está na tela ou já cabe. */
    private fun endDelta(): Int {
        val last = list.count - 1
        if (last < 0 || list.lastVisiblePosition != last) return 0
        val child = list.getChildAt(list.childCount - 1) ?: return 0
        return child.bottom - (list.height - list.paddingBottom)
    }

    /**
     * Aplica o modo compacto (teclado aberto ou tela baixa): faixas de status e de permissão em uma
     * linha, atalhos escondidos, caixa de texto de até 3 linhas e sem o nome do agente no título
     * (fica o subtítulo com a pasta). Fora dele, tudo volta ao normal.
     */
    private fun applyCompact() {
        engine?.let {
            updatePermissionBar(it)
            updateStatus(it)
        }
        updateQuickbar()
        input.maxLines = if (compactLayout) 3 else 6
        titleView.visibility = if (compactLayout) View.GONE else View.VISIBLE
    }

    /**
     * Faixa de status com o que o chat está fazendo agora ([AgentStatus]): pensando, executando uma ação
     * (com o tempo dela e, se houver, outras ao mesmo tempo), esperando sua permissão, recarregando, sem
     * eventos (possível travamento) ou com a conexão perdida. Ocioso não tem texto: a faixa só aparece
     * para mostrar tarefas em segundo plano (subagentes, comandos), mesmo depois do turno.
     * O relógio gira só quando o agente trabalha de fato, e Parar some quando ele sumiu.
     * No modo compacto (ver [applyCompact]) a faixa tem uma linha só: as partes vão separadas por " · ".
     */
    private fun updateStatus(chat: AgentChat) {
        val background = chat.background
        val children = chat.childAgents
        val runningChildren = children?.running ?: 0
        val state = AgentStatus.of(
            items = chat.items.toList(),
            busy = chat.busy,
            reloading = chat.reloading,
            running = chat.running,
            lastEventAtMs = chat.lastEventAtMs,
            turnStartedAtMs = chat.turnStartedAtMs,
            lastExitCode = chat.lastExitCode,
            now = SystemClock.elapsedRealtime(),
            stallMs = settings.chatStallSeconds * 1000L,
        )
        status.visibility = if (state != AgentStatus.State.Idle || background.isNotEmpty() || runningChildren > 0) View.VISIBLE else View.GONE
        // Tocar na faixa só faz algo com tarefas em segundo plano (ver showBackgroundSheet).
        status.isClickable = background.isNotEmpty()
        // O relógio só gira se o agente está mesmo trabalhando: pedido de permissão, travamento ou queda
        // não podem dar a impressão de que algo anda (nem uma ação em andamento sem eventos, que é o travamento).
        val working = state is AgentStatus.State.Thinking || state is AgentStatus.State.Reloading ||
            (state is AgentStatus.State.Running && !state.stalled)
        statusSpinner.visibility = if (working) View.VISIBLE else View.GONE
        // O relógio só corre enquanto o tempo anda: ocioso ou com o agente perdido, ele para — mas
        // com subagente rodando ele segue, para o tempo dos cartões deles andar.
        val clock = (state != AgentStatus.State.Idle && state !is AgentStatus.State.Lost) || runningChildren > 0
        if (clock && resumed) startStatusClock() else stopStatusClock()
        // Parar interrompe o turno; com o agente perdido não há o que parar.
        stopButton.visibility = if (chat.busy && state !is AgentStatus.State.Lost) View.VISIBLE else View.GONE
        val lines = statusLines(state, background, children)
        statusText.maxLines = if (compactLayout) 1 else 3
        statusText.text = lines.joinToString(if (compactLayout) " · " else "\n")
    }

    /** Texto da faixa para o [state], as tarefas em [background] e os [children]; vazio quando não há o que dizer. */
    private fun statusLines(state: AgentStatus.State, background: List<BackgroundTask>, children: ChildSummary?): List<String> {
        val lines = mutableListOf<String>()
        when (state) {
            AgentStatus.State.Idle -> Unit
            AgentStatus.State.Reloading -> lines += getString(R.string.chat_reloading)
            is AgentStatus.State.Thinking ->
                lines += getString(R.string.chat_status_thinking, AgentStatus.elapsed(state.sinceMs))
            is AgentStatus.State.Running -> {
                val time = AgentStatus.elapsed(state.sinceMs)
                var line = if (state.others > 0) {
                    getString(R.string.chat_status_running_many, state.label, state.others, time)
                } else {
                    getString(R.string.chat_status_running, state.label, time)
                }
                // Sem mensagem do agente há um tempo: o tempo da ação pode esconder que ela parou.
                if (state.silentMs >= SILENT_HINT_MS) {
                    line += " · " + getString(R.string.chat_status_silent_suffix, AgentStatus.elapsed(state.silentMs))
                }
                if (state.stalled) line += " · " + getString(R.string.chat_status_stalled_suffix)
                lines += line
            }
            is AgentStatus.State.Waiting ->
                lines += getString(R.string.chat_status_waiting, state.count, AgentStatus.elapsed(state.sinceMs))
            is AgentStatus.State.Stalled -> {
                lines += getString(R.string.chat_status_stalled, AgentStatus.elapsed(state.silentMs))
                // A dica não cabe na linha única do modo compacto.
                if (!compactLayout) lines += getString(R.string.chat_status_stalled_tip)
            }
            is AgentStatus.State.Lost -> lines += getString(R.string.chat_status_lost, state.code)
        }
        if (background.size == 1) {
            val task = background[0]
            // Sem descrição nenhuma, o rótulo genérico: a faixa nunca fica "Em segundo plano: " vazio.
            val what = listOfNotNull(task.description.ifBlank { null }, task.activity?.ifBlank { null })
                .distinct().joinToString(" · ").ifEmpty { getString(R.string.chat_background_task) }
            lines += getString(R.string.chat_background_one, what)
        } else if (background.size > 1) {
            // Descrições em branco não entram na lista (nada de ", ,"); se nenhuma tem texto, o rótulo genérico.
            val names = background.map { it.description.trim() }.filter { it.isNotEmpty() }
            val what = names.joinToString(", ").ifEmpty { getString(R.string.chat_background_tasks) }
            lines += getString(R.string.chat_background_many, background.size, what)
        }
        // Subagentes: quantos e quantos ainda rodam (a faixa aparece mesmo com o agente ocioso).
        if (children != null) {
            lines += resources.getQuantityString(R.plurals.chat_child_summary, children.total, children.total, children.running)
        }
        // Com o agente ocioso, avisa que dá para tocar e parar. No compacto não cabe a dica.
        if (background.isNotEmpty() && state == AgentStatus.State.Idle && !compactLayout) {
            lines += getString(R.string.chat_background_tap)
        }
        return lines
    }

    /** Painel das tarefas em segundo plano: parar uma (a descrição é o que ela faz agora) ou todas. */
    private fun showBackgroundSheet(chat: AgentChat) {
        val tasks = chat.background
        if (tasks.isEmpty()) return
        val rows = tasks.map { task ->
            Sheet.Row(
                title = getString(R.string.chat_background_stop_one, task.description.ifBlank { task.activity ?: task.id }),
                description = task.activity,
                destructive = true,
            ) { chat.stopBackground(task.id) }
        }.toMutableList()
        if (tasks.size > 1) {
            rows += Sheet.Row(title = getString(R.string.chat_background_stop_all), destructive = true) {
                tasks.forEach { chat.stopBackground(it.id) }
            }
        }
        Sheet(this)
            .title(getString(R.string.chat_background_title))
            .message(getString(R.string.chat_background_message))
            .rows(rows)
            .show()
    }

    /**
     * Faixa fixa com o primeiro pedido de permissão pendente (o cartão completo continua na conversa
     * e é o único lugar com Permitir/Recusar). Some quando não há pedido.
     */
    private fun updatePermissionBar(chat: AgentChat) {
        // Cópia da lista: ela muda na thread principal enquanto se percorre.
        val pending = chat.items.toList().filterIsInstance<ChatItem.Permission>().filter { it.chosen == null }
        val first = pending.firstOrNull()
        barPermission = first
        permissionBar.visibility = if (first == null || permissionCardVisible(first)) View.GONE else View.VISIBLE
        if (first == null) return
        permissionTitle.text = getString(R.string.chat_permission_bar_count, 1, pending.size)
        // Tela baixa: sem a linha do título, e o comando em até duas linhas (a faixa não empurra a conversa para fora).
        permissionTitle.visibility = if (compactLayout) View.GONE else View.VISIBLE
        permissionText.maxLines = 2
        // O comando inteiro fica no cartão; aqui cabe só o começo, para a faixa não crescer.
        permissionText.text = first.summary.ifBlank { first.title }
    }

    /** O cartão do pedido está inteiro na tela: a faixa seria só repetição. */
    private fun permissionCardVisible(item: ChatItem.Permission): Boolean {
        val index = adapter.snapshot.indexOfFirst { it === item }
        if (index < 0) return false
        val child = list.getChildAt(index - list.firstVisiblePosition) ?: return false
        return child.top >= 0 && child.bottom <= list.height
    }

    /**
     * Detalhes da faixa: rola a lista até o cartão do pedido. Se o cartão não está na lista
     * (a visualização não o mostra), abre o pedido inteiro numa caixa.
     */
    private fun showBarPermission() {
        val item = barPermission ?: return
        // Posição no que a lista mostra (no Resumo os itens são reagrupados), não na conversa.
        val index = adapter.snapshot.indexOfFirst { it === item }
        if (index >= 0) {
            // O começo do cartão no topo da lista (um cartão alto, com o diff, não fica cortado em cima).
            list.smoothScrollToPositionFromTop(index, 0)
        } else {
            val body = listOf(item.title, item.summary, item.detail, item.diff.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
            showDetails(R.string.chat_permission_full, body)
        }
    }

    /**
     * Atalhos acima da caixa: pasta, modelo, visualização, contexto e mudanças. As views são criadas
     * no layout; aqui só mudam texto, descrição e visibilidade (sem recriar nada a cada redesenho).
     */
    private fun updateQuickbar() {
        val chat = engine ?: return
        quickbar.visibility = if (compactLayout) View.GONE else View.VISIBLE
        val branch = folderBranch

        // Pasta (e branch): a raiz mostra ~/projetos.
        val name = if (folder.isEmpty()) displayPath(Workspace.ROOT) else folder
        val label = if (branch == null) name else "$name · $branch"
        chipFolder.text = "📁 $label"
        chipFolder.contentDescription = when {
            folder.isEmpty() -> getString(R.string.chat_chip_folder_plain, getString(R.string.chat_folder_root))
            branch != null -> getString(R.string.chat_chip_folder_description, folder, branch)
            else -> getString(R.string.chat_chip_folder_plain, folder)
        }
        chipFolder.visibility = View.VISIBLE

        // Modelo e esforço: no Claude sempre; no ACP, só quando o agente oferece opções.
        val acp = chat as? AcpChat
        val options = acp?.configOptions.orEmpty()
        val showModel = isClaude || options.isNotEmpty()
        chipModel.visibility = if (showModel) View.VISIBLE else View.GONE
        if (isClaude) {
            // Modelo e esforço no padrão: só "Modelo padrão". O esforço no padrão não aparece no chip.
            val model = friendlyModel(chat.model) ?: getString(R.string.chat_chip_model_default)
            val effort = settings.chatEffort.takeIf { it.isNotEmpty() }?.let { effortLabel(it) }
            chipModel.text = if (effort == null) model else "$model · $effort"
            chipModel.contentDescription = getString(
                R.string.chat_chip_model_description, model, effort ?: getString(R.string.chat_effort_default),
            )
        } else if (acp != null && showModel) {
            val model = acp.model ?: getString(R.string.chat_chip_model_default)
            val thought = options.firstOrNull { it.category == "thought_level" }?.currentName
            chipModel.text = listOfNotNull(model, thought).joinToString(" · ")
            chipModel.contentDescription = if (thought == null) {
                getString(R.string.chat_chip_model_plain, model)
            } else {
                getString(R.string.chat_chip_model_description, model, thought)
            }
        }

        // Modo de permissão: só quando o agente oferece; um toque abre a escolha.
        val modeId = chat.currentMode
        val modeName = modeId?.let { id -> ModeText.label(this, id, chat.modeChoices.firstOrNull { it.first == id }?.second.orEmpty()) }
        chipMode.visibility = if (modeName == null) View.GONE else View.VISIBLE
        if (modeName != null) {
            chipMode.text = "🛡 $modeName"
            chipMode.setTextColor(getColor(if (modeId != null && ModeText.isRisky(modeId)) R.color.setup_error_text else R.color.setup_text))
            chipMode.contentDescription = getString(R.string.chat_chip_mode_description, modeName)
        }

        // Visualização: o modo atual, como nos ajustes.
        val viewLabel = getString(viewLabelRes(settings.chatView))
        chipView.text = "👁 $viewLabel"
        chipView.contentDescription = getString(R.string.chat_chip_view_description, viewLabel)

        // Contexto: só quando o agente informa. Com 80% ou mais, o chip fica em destaque.
        val usage = chat.contextUsage
        chipContext.visibility = if (usage == null) View.GONE else View.VISIBLE
        if (usage != null) {
            val percent = usage.percent
            val high = percent >= COMPACT_PERCENT
            chipContext.text = getString(if (high) R.string.chat_chip_context_high else R.string.chat_chip_context, percent)
            chipContext.setTextColor(getColor(if (high) R.color.setup_error_text else R.color.setup_text))
            chipContext.contentDescription = getString(R.string.chat_chip_context_description, percent)
        }

        // Limites do plano: o último valor conhecido do agente (vale para a conta), só para os que informam.
        val limits = limitsStore.load(agentId)?.let { PlanLimitsParser.current(it, System.currentTimeMillis()) }
        updateLimitsChip(limits)

        // Mudanças: só numa pasta git com arquivos alterados (a contagem vem de readChanges).
        val changes = changedFiles ?: 0
        val showChanges = branch != null && changes > 0
        chipChanges.visibility = if (showChanges) View.VISIBLE else View.GONE
        if (showChanges) {
            chipChanges.text = resources.getQuantityString(R.plurals.chat_quick_changes, changes, changes)
            chipChanges.contentDescription = resources.getQuantityString(R.plurals.chat_quick_changes_description, changes, changes)
        }
    }

    /**
     * Conta os arquivos alterados da pasta (git status) fora da thread principal. Só a leitura mais
     * nova vale: uma anterior que termina depois é descartada pelo contador de geração.
     */
    private fun readChanges() {
        val generation = ++changesGeneration
        // Sem branch conhecido a pasta não é repositório git: não há o que contar (e o git nem roda).
        if (folderBranch == null) {
            if (changedFiles != null) {
                changedFiles = null
                updateQuickbar()
            }
            return
        }
        thread(name = "chat-changes") {
            val result = Git.status(linux, Workspace.path(folder))
            val count = if (result.code != 0) {
                null
            } else {
                val entries = Git.parseStatus(result.output)
                // Com a saída cortada, a última entrada pode estar pela metade: não entra na conta.
                (if (result.cut) entries.size - 1 else entries.size).coerceAtLeast(0)
            }
            runOnUiThread {
                if (isDestroyed || isFinishing || generation != changesGeneration) return@runOnUiThread
                changedFiles = count
                updateQuickbar()
            }
        }
    }

    /** Toque no chip de modelo: no Claude, as linhas Modelo e Esforço; no ACP, as opções que o agente oferece. */
    private fun showModelSheet() {
        if (isClaude) {
            Sheet(this)
                .title(getString(R.string.chat_model_effort_title))
                .rows(listOf(claudeModelRow(), claudeEffortRow()))
                .show()
        } else {
            Sheet(this).title(agentName).rows(optionRows(engine as? AcpChat)).show()
        }
    }

    /**
     * Toque no chip de contexto: quanto da memória da conversa já está em uso, e compactar (quando o
     * agente aceita). Compactar fica indisponível enquanto há um turno rodando.
     */
    private fun showContextSheet(chat: AgentChat) {
        val percent = chat.contextUsage?.percent ?: return
        val canCompact = isClaude || (chat as? AcpChat)?.commands?.any { it.name == "compact" } == true
        val rows = mutableListOf<Sheet.Row>()
        if (canCompact) rows += Sheet.Row(title = getString(R.string.chat_compact_now), enabled = !chat.busy) { sendAwake("/compact") }
        rows += autoCompactRow()
        Sheet(this)
            .title(getString(R.string.chat_context_title))
            .message(getString(R.string.chat_context_explain, percent))
            .rows(rows)
            .show()
    }

    /**
     * Chip de limites: uso da janela de 5 horas e da semanal (as que existirem). Com 80% ou mais, em
     * destaque. Sem essas janelas, o chip some (janelas "outras" só aparecem no painel).
     */
    private fun updateLimitsChip(limits: PlanLimits?) {
        val summary = limitsSummary(this, limits)
        if (summary == null) {
            chipLimits.visibility = View.GONE
            return
        }
        chipLimits.visibility = View.VISIBLE
        chipLimits.text = summary.text
        chipLimits.setTextColor(getColor(if (summary.high) R.color.setup_error_text else R.color.setup_text))
        chipLimits.contentDescription = getString(R.string.chat_chip_limits_description, summary.names)
    }

    /** Toque no chip de limites: uma linha por janela, com quando renova, e quando foi atualizado. */
    private fun showLimitsSheet() {
        val now = System.currentTimeMillis()
        val limits = limitsStore.load(agentId)?.let { PlanLimitsParser.current(it, now) } ?: return
        val lines = limits.windows.joinToString("\n") { w ->
            listOfNotNull(limitName(this, w), w.resetsAtMs?.let { getString(R.string.chat_limit_resets, formatLimitReset(this, it)) })
                .joinToString(" · ")
        }
        // O texto relativo vem com maiúscula ("Há 2 minutos"); no meio da frase fica com minúscula.
        val updated = DateUtils.getRelativeTimeSpanString(limits.updatedAtMs, now, DateUtils.SECOND_IN_MILLIS)
            .toString().let { it.take(1).lowercase(Locale.getDefault()) + it.drop(1) }
        val text = "$lines\n\n${getString(R.string.chat_limits_updated, updated)}\n\n${getString(R.string.chat_limits_explain)}"
        Sheet(this).title(getString(R.string.chat_limits_title)).message(text).show()
    }

    // ---- Sugestões de comandos "/" (do app e do agente) ----

    /**
     * Mostra até 6 sugestões (comandos do app, depois os do agente) que começam como o texto
     * digitado, enquanto o texto começa com "/" e não tem espaço. Fecha quando não há o que sugerir.
     */
    private fun updateCommandSuggestions() {
        val text = input.text.toString()
        val chat = engine
        // Só com a tela em primeiro plano: em segundo plano o popup fica para o próximo onResume.
        val matches = if (resumed && chat != null && text.startsWith("/") && text.none { it.isWhitespace() } && input.width > 0) {
            suggestionsFor(chat, text.drop(1))
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
            it.setOnItemClickListener { _, _, position, _ -> pickSuggestion(commandMatches[position]) }
            commandPopup = it
        }
        // Se a lista já está aberta com esses mesmos comandos, não redesenha.
        if (popup.isShowing && matches == commandMatches) return
        commandMatches = matches
        popup.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, matches.map { it.label }))
        popup.width = input.width
        // Altura fixa e deslocada para cima: o popup fica sobre a caixa, não embaixo (teclado).
        val height = matches.size * dp(COMMAND_ROW_DP)
        popup.height = height
        popup.setVerticalOffset(-(input.height + height))
        popup.show()
    }

    private fun commandLabel(command: SlashCommand): String =
        if (command.description.isBlank()) "/${command.name}" else "/${command.name} — ${command.description}"

    /**
     * Sugestões para o que vem depois da "/": primeiro os comandos do app que este agente oferece,
     * depois os do agente (sem os nomes que o app já atende).
     */
    private fun suggestionsFor(chat: AgentChat, query: String): List<Suggestion> {
        val appCommands = AppCommand.entries
            .filter { offersApp(it, chat) && it.command.startsWith(query, ignoreCase = true) }
            .map { Suggestion("/${it.command} — ${getString(it.descRes)}", it, null) }
        val agentCommands = chat.commands
            .filter { it.name.startsWith(query, ignoreCase = true) && it.name.lowercase() !in APP_COMMAND_NAMES }
            .map { Suggestion(commandLabel(it), null, it) }
        return (appCommands + agentCommands).take(COMMAND_SUGGESTIONS)
    }

    /** Os comandos do app que o popup oferece com este agente. */
    private fun offersApp(app: AppCommand, chat: AgentChat): Boolean = when (app) {
        AppCommand.NEW, AppCommand.CLEAR, AppCommand.RESET -> true
        AppCommand.RESUME -> chat.supportsHistory
        AppCommand.COMPACT -> chat.commands.any { it.name.equals("compact", ignoreCase = true) }
    }

    /** Toque numa sugestão: comando do app o executa aqui; comando do agente preenche a caixa. */
    private fun pickSuggestion(suggestion: Suggestion) {
        dismissCommandSuggestions()
        val agent = suggestion.agent
        val app = suggestion.app
        if (agent != null) {
            applyCommand(agent)
        } else if (app != null) {
            when (app) {
                AppCommand.NEW, AppCommand.CLEAR, AppCommand.RESET -> startNewConversationFromBox()
                AppCommand.RESUME -> openHistoryFromBox()
                AppCommand.COMPACT -> {
                    // Sem o serviço ligado, nada enviaria: o texto fica na caixa.
                    if (service == null) return
                    input.setText("")
                    drafts.put(chatKey, "")
                    drafts.putAttachments(chatKey, emptyList())
                    sendAwake("/compact")
                }
            }
        }
    }

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

    /** /new no campo (ou na sugestão): começa uma conversa nova, se o agente não está respondendo. */
    private fun startNewConversationFromBox() {
        // Mesmo critério do ＋ do menu ⋮ (ver [busyBlocks]).
        if (busyBlocks(R.string.chat_new_wait)) return
        // Sem o serviço ligado, nada abriria: o texto fica na caixa.
        if (service == null) return
        input.setText("")
        drafts.put(chatKey, "")
        drafts.putAttachments(chatKey, emptyList())
        newConversation()
    }

    /** /resume no campo (ou na sugestão): abre o Histórico, sem mandar o comando ao agente. */
    private fun openHistoryFromBox() {
        input.setText("")
        drafts.put(chatKey, "")
        drafts.putAttachments(chatKey, emptyList())
        showHistory()
    }

    private fun send() {
        dismissCommandSuggestions()
        if (preparing) return
        val text = input.text.toString().trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        // /new, /clear e /reset viram a Nova conversa do menu: o agente zeraria a conversa por dentro, mas a tela continuaria com as mensagens antigas. Vale para todos os agentes.
        if (pendingAttachments.isEmpty() && text.lowercase() in NEW_CONVERSATION_COMMANDS) {
            startNewConversationFromBox()
            return
        }
        // /resume vira o Histórico do menu: o agente trocaria de conversa por dentro (ou abriria uma lista
        // que o chat não mostra), e a tela continuaria com a conversa atual.
        if (pendingAttachments.isEmpty() && text.lowercase() == "/resume") {
            openHistoryFromBox()
            return
        }
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
            // Trabalho novo precisa de vaga; o que já existe (parado ou dormindo) é reaberto.
            withRoom(svc) { deliver(wake(svc)) }
        }
    }

    /**
     * O chat deste trabalho rodando: o atual, ou um novo que retoma a mesma conversa. Não confere
     * vaga: quem chama confere (ver [withRoom]).
     */
    private fun wake(svc: TerminalService): AgentChat {
        // A conversa parada só é retomada na pasta dela.
        val resume = engine?.takeIf { it.cwd == Workspace.path(folder) }?.sessionId
        return engine?.takeIf { it.running }
            ?: svc.openChat(agentId, folder = folder, resume = resume, restart = true).also { attach(it) }
    }

    /**
     * Manda um texto que não vem da caixa (chip de continuar, Compactar agora). Se o agente dorme
     * ou parou, confere a vaga e o acorda como no envio, para não passar do limite de agentes.
     */
    private fun sendAwake(text: String) {
        val svc = service ?: return
        val chat = engine ?: return
        if (chat.running) {
            chat.sendMessage(text)
            list.post { scrollToEnd() }
            return
        }
        withRoom(svc) {
            wake(svc).sendMessage(text)
            list.post { scrollToEnd() }
        }
    }

    /** Manda o texto e os anexos pendentes para o chat e limpa o campo. */
    private fun deliver(chat: AgentChat) {
        val text = input.text.toString().trim()
        val files = pendingAttachments.toList()
        input.setText("")
        // Enviado: o rascunho sai já, para não voltar se o app morrer antes do onPause.
        drafts.put(chatKey, "")
        drafts.putAttachments(chatKey, emptyList())
        pendingAttachments.clear()
        renderAttachments()
        chat.sendMessage(text, files)
        // Enviou: a lista vai até a mensagem, e a resposta aparece embaixo dela.
        list.post { scrollToEnd() }
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

    /**
     * Trocar de conversa, modelo, esforço ou compactação reinicia o agente: com um turno em andamento
     * (a fila e as permissões pendentes só existem com turno), cortaria a resposta; com tarefas em
     * segundo plano, pararia o que o agente ainda faz (o `sleep` também recusa). Nesses casos mostra
     * o aviso e devolve true (não reabre nem salva nada). [busyMessage] é o aviso do turno em andamento.
     */
    private fun busyBlocks(busyMessage: Int = R.string.chat_wait_turn): Boolean {
        val chat = engine ?: return false
        val message = when {
            chat.busy -> busyMessage
            chat.running && chat.background.isNotEmpty() -> R.string.chat_wait_background
            else -> return false
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        return true
    }

    /** Conversas anteriores: toque para retomar, 🗑 para apagar (a aberta já vira uma nova). */
    private fun showHistory() {
        val chat = engine
        if (isClaude) {
            val cwd = chatCwd
            thread(name = "chat-history") {
                // Falha de leitura (pasta sumiu, sem permissão) vira lista vazia, sem derrubar o app.
                val found = runCatching { ChatHistory.list(linux, cwd) }.getOrDefault(emptyList())
                runOnUiThread { if (!isDestroyed) showHistoryDialog(found, canDelete = true) }
            }
        } else if (chat is AcpChat && chat.supportsHistory && !chat.running) {
            // Dormindo ou parado: quem lista as conversas é o próprio agente.
            Toast.makeText(this, getString(R.string.chat_history_asleep, agentName), Toast.LENGTH_LONG).show()
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
                if (!isCurrent && !busyBlocks()) reopenChat(svc) {
                    svc.openChat(agentId, folder = folder, resume = conversation.id, restart = true)
                }
            }
        }.toMutableList()
        // Apagar fica numa linha própria (sem gesto escondido, que não é acessível).
        if (canDelete) rows += Sheet.Row(title = getString(R.string.chat_history_delete_row), destructive = true) {
            showDeleteHistory(conversations)
        }
        // Busca nas conversas (só o Claude: a lista dos outros agentes é do próprio agente).
        if (isClaude) rows.add(0, Sheet.Row(title = getString(R.string.chat_history_search), icon = "🔍") { askSearch() })
        Sheet(this).title(getString(R.string.chat_history)).rows(rows).show()
    }

    /** Busca nas conversas do Claude: pede as palavras e, com texto, procura (ver [searchHistory]). */
    private fun askSearch() {
        val field = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.chat_history_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_history_search)
            .setView(field)
            .setPositiveButton(R.string.chat_history_search_go) { _, _ ->
                val text = field.text.toString().trim()
                if (text.isNotEmpty()) searchHistory(text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Lê os arquivos da pasta fora da thread principal; falha conta como nada achado. */
    private fun searchHistory(text: String) {
        val cwd = chatCwd
        thread(name = "chat-search") {
            val found = runCatching {
                ChatHistory.search(linux, cwd, text, cancelled = { isDestroyed || isFinishing })
            }.getOrDefault(emptyList())
            runOnUiThread { if (!isDestroyed) showSearchResults(text, found) }
        }
    }

    /** Resultados da busca: tocar numa conversa faz o mesmo que na linha do Histórico. */
    private fun showSearchResults(query: String, found: List<ChatHistory.Match>) {
        val svc = service ?: return
        if (found.isEmpty()) {
            Toast.makeText(this, R.string.chat_history_search_none, Toast.LENGTH_SHORT).show()
            return
        }
        val rows = found.map { match ->
            Sheet.Row(title = match.conversation.title, description = match.snippet) {
                val conversation = match.conversation
                val isCurrent = conversation.id == engine?.sessionId
                if (!isCurrent && !busyBlocks()) reopenChat(svc) {
                    svc.openChat(agentId, folder = folder, resume = conversation.id, restart = true)
                }
            }
        }
        Sheet(this).title(getString(R.string.chat_history_search_results, query)).rows(rows).show()
    }

    /** Anexos guardados no rascunho deste trabalho: refaz os que ainda estão em ~/projetos/anexos. */
    private fun restoreDraftAttachments() {
        val saved = drafts.getAttachments(chatKey)
        if (saved.isEmpty()) return
        thread(name = "chat-draft-attachments") {
            val restored = saved.mapNotNull { (name, path, mime) ->
                runCatching { Attachments.restore(linux, name, path, mime) }.getOrNull()
            }
            runOnUiThread {
                // Se o usuário já anexou algo (ou a tela fechou), não mexe.
                if (!isDestroyed && pendingAttachments.isEmpty()) {
                    pendingAttachments += restored
                    renderAttachments()
                }
            }
        }
    }

    /**
     * Salva a conversa deste trabalho como Markdown, no arquivo que o usuário escolher. O texto vai
     * para [pendingExport] antes de abrir o seletor de arquivos (ver [exportPicker]).
     */
    private fun exportConversation() {
        val items = engine?.items?.toList().orEmpty()
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.chat_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val place = if (folder.isEmpty()) displayPath(Workspace.ROOT) else folder
        pendingExport = ChatExport.markdown("$agentName · $place", items, getString(R.string.chat_export_you), agentName)
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        runCatching { exportPicker.launch("conversa-$stamp.md") }.onFailure {
            // Sem app de arquivos para escolher o destino: nada foi salvo.
            pendingExport = null
            Toast.makeText(this, R.string.chat_export_failed, Toast.LENGTH_SHORT).show()
        }
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
        val all = if (isClaude) listOf(
            Sheet.Row(title = getString(R.string.chat_history_delete_all), destructive = true) { confirmDeleteAll(conversations.map { it.id }) },
        ) else emptyList()
        Sheet(this).title(getString(R.string.chat_history_delete_title)).rows(all + rows).show()
    }

    /** Apaga todas as conversas do Claude da pasta (fecha o chat aberto antes) e abre uma conversa nova. */
    private fun confirmDeleteAll(ids: List<String>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_history_delete_all)
            .setMessage(R.string.chat_history_delete_all_confirm)
            .setPositiveButton(R.string.chat_delete) { _, _ ->
                val svc = service ?: return@setPositiveButton
                val cwd = chatCwd
                svc.closeChat(agentId, folder) {
                    thread(name = "chat-delete-all") {
                        // Um erro num arquivo não pode derrubar o app nem impedir os outros.
                        for (id in ids) runCatching { ChatHistory.delete(linux, cwd, id) }
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            Toast.makeText(this, R.string.chat_history_deleted_all, Toast.LENGTH_SHORT).show()
                            withRoom(svc) { attach(svc.openChat(agentId, folder = folder, restart = true)) }
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        val usage = svc.memoryWatch.usageByKey()
        val rows = slots.map { slot ->
            Sheet.Row(
                title = jobName(agentNameOf(slot.agentId), slot.folder),
                value = stateText(this, chatState(slot.chat)),
                description = jobMemoryText(this, slot.chat, usage[TerminalService.chatKey(slot.agentId, slot.folder)]),
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

    /**
     * Menu (⋮) do topo: as ações que saíram da barra, cada uma com uma linha de explicação. Mudanças
     * só aparece numa pasta git; encerrar, só num trabalho isolado (worktree).
     */
    private fun showMenu() {
        // O branch e o trabalho isolado decidem o que cabe no menu: se ainda não foram lidos, lê antes.
        if (!folderInfoRead) {
            readFolderInfo { showMenu() }
            return
        }
        val rows = mutableListOf<Sheet.Row>()
        rows += Sheet.Row(title = getString(R.string.chat_new), description = getString(R.string.chat_menu_new_desc), icon = "＋") {
            if (!busyBlocks(R.string.chat_new_wait)) newConversation()
        }
        rows += Sheet.Row(title = getString(R.string.chat_menu_history), description = getString(R.string.chat_menu_history_desc), icon = "🕘") {
            showHistory()
        }
        rows += Sheet.Row(title = getString(R.string.chat_jobs), description = getString(R.string.chat_menu_jobs_desc), icon = "⇄") {
            showJobs()
        }
        if (folderBranch != null) rows += Sheet.Row(
            title = getString(R.string.chat_menu_changes),
            description = getString(R.string.sheet_desc_changes),
            icon = "⎇",
        ) { openChanges() }
        if (folderWorktreeOf != null) rows += Sheet.Row(
            title = getString(R.string.chat_worktree_finish),
            description = getString(R.string.chat_menu_finish_desc),
            icon = "⏏",
        ) { loadFolders { folders -> showWorktreeFinish(folders) } }
        rows += autoCompactRow()
        rows += Sheet.Row(
            title = getString(R.string.chat_export),
            description = getString(R.string.chat_export_desc),
            icon = "⇩",
        ) { exportConversation() }
        rows += Sheet.Row(
            title = getString(R.string.chat_menu_settings),
            description = getString(R.string.chat_menu_settings_desc),
            icon = "⚙",
        ) { showChatSettings() }
        Sheet(this).title(agentName).rows(rows).show()
    }

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
            rows += claudeModelRow()
            rows += claudeEffortRow()
        }
        rows += viewRow()
        // O OpenCode tem contas e chaves próprias: conectadas pelo app, sem passar pelo chat.
        if (recipe?.id == OpenCodeAuth.AGENT_ID) rows += Sheet.Row(
            title = getString(R.string.opencode_auth_row),
            description = getString(R.string.opencode_auth_row_desc),
            icon = "🔑",
        ) { openOpenCodeAccounts() }
        if (recipe?.id == OpenCodeAuth.AGENT_ID) rows += Sheet.Row(
            title = getString(R.string.opencode_usage_row),
            description = getString(R.string.opencode_usage_row_desc, OpenCodeStats.DAYS),
            icon = "📊",
        ) { openOpenCodeUsage() }
        // Sem ajustes do agente (ou sem chat pronto), o painel mostra a pasta e a visualização, que são do app.
        if (!isClaude) rows += optionRows(engine as? AcpChat)
        // Agente pronto que não oferece modos: diz que ele pergunta antes de agir (em vez de sumir sem explicação).
        if (!isClaude && engine is AcpChat && engine?.modeChoices.isNullOrEmpty() && engine?.running == true) {
            rows += Sheet.Row(title = getString(R.string.chat_mode_none), icon = "🛡", enabled = false) {}
        }
        Sheet(this).title(getString(R.string.chat_settings_title)).rows(rows).show()
    }

    /** Linha do modelo do Claude (ajustes e painel do chip de modelo). */
    private fun claudeModelRow() = Sheet.Row(title = getString(R.string.chat_setting_model), value = modelLabel(settings.chatModel), icon = "🧠") {
        chooseClaude(R.string.chat_setting_model, MODELS, settings.chatModel, ::modelLabel) { settings.chatModel = it }
    }

    /** Linha do esforço do Claude (ajustes e painel do chip de modelo). */
    private fun claudeEffortRow() = Sheet.Row(title = getString(R.string.chat_setting_effort), value = effortLabel(settings.chatEffort), icon = "⚡") {
        chooseClaude(R.string.chat_setting_effort, EFFORTS, settings.chatEffort, ::effortLabel) { settings.chatEffort = it }
    }

    /** Linhas dos ajustes que o agente ACP oferece (modelo, raciocínio, modo...). */
    private fun optionRows(acp: AcpChat?): List<Sheet.Row> = acp?.configOptions.orEmpty().map { option ->
        Sheet.Row(title = option.name, value = option.currentName, icon = "⚙") { chooseOption(acp, option) }
    }

    /** Escolha do modo de permissão direto do chat: nomes simples e uma frase sobre cada um. */
    private fun showModeSheet() {
        val chat = engine ?: return
        val choices = chat.modeChoices
        if (choices.isEmpty()) return
        Sheet(this)
            .title(getString(R.string.chat_mode_title))
            .message(getString(R.string.chat_mode_note))
            .rows(choices.map { (id, name) ->
                Sheet.Row(
                    title = ModeText.label(this, id, name),
                    description = ModeText.description(this, id),
                    checked = id == chat.currentMode,
                    destructive = ModeText.isRisky(id),
                ) { chooseMode(chat, id, name) }
            })
            .show()
    }

    private fun chooseMode(chat: AgentChat, id: String, name: String) {
        if (id == chat.currentMode) return
        if (!ModeText.isRisky(id)) return chat.setMode(id)
        AlertDialog.Builder(this)
            .setTitle(ModeText.label(this, id, name))
            .setMessage(R.string.chat_mode_risky_confirm)
            .setPositiveButton(R.string.chat_mode_risky_yes) { _, _ -> chat.setMode(id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            rows += Sheet.Row(title = item.name, description = Workspace.describe(this, item), checked = item.name == folder) {
                pickFolder(item.name)
            }
        }
        rows += Sheet.Row(
            title = getString(R.string.project_new),
            icon = "＋",
            description = getString(R.string.project_new_desc),
        ) { newProject() }
        rows += Sheet.Row(
            title = getString(R.string.clone_new),
            icon = "⇣",
            description = getString(R.string.clone_new_desc),
        ) { startClone() }
        if (folders.any { Worktrees.canStart(it) }) rows += Sheet.Row(
            title = getString(R.string.chat_worktree_new),
            icon = "⎇",
            description = getString(R.string.sheet_desc_worktree),
        ) { chooseWorktreeBase(folders) }
        Sheet(this).title(getString(R.string.chat_setting_folder)).rows(rows).show()
    }

    /** Escolha do repositório base do novo trabalho isolado. */
    private fun chooseWorktreeBase(folders: List<Workspace.Folder>) {
        val bases = folders.filter { Worktrees.canStart(it) }
        Sheet(this)
            .title(getString(R.string.chat_worktree_pick_base))
            .rows(bases.map { base ->
                Sheet.Row(title = base.name, description = Workspace.describe(this, base)) { newWorktreeDialog(base) }
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
        if (folders.none { it.name == baseName && Worktrees.canBeBase(it) }) {
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
     * Tira o trabalho isolado: o serviço trava a pasta e fecha as sessões de terminal e os chats
     * (de todos os agentes, rodando ou parados) dela (closeFolder, no máximo 5 s de espera);
     * depois, remove o worktree e a branch. A pasta fica travada até o git terminar, para nenhum
     * chat reabrir nela no meio. Pasta já ocupada (tela Projetos mexendo nela): avisa e desiste.
     */
    private fun removeWorktree(svc: TerminalService, job: WorktreeJob, force: Boolean) {
        val started = svc.closeFolder(job.folder) { removeFromGit(svc, job, force) }
        if (!started) {
            removingWorktree = false
            setPreparing(false)
            Toast.makeText(this, R.string.projects_folder_busy, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * `git worktree remove` e `git branch -d` (ou -D) no repositório base; ao fim, solta a pasta
     * (sempre, mesmo com a tela fechada) e abre o chat do base. Se a remoção falha, mostra o erro
     * e oferece reabrir o trabalho na pasta.
     */
    private fun removeFromGit(svc: TerminalService, job: WorktreeJob, force: Boolean) {
        val basePath = Workspace.path(job.base)
        val removeArgs = listOfNotNull("worktree", "remove", if (force) "--force" else null, Workspace.path(job.folder))
        thread(name = "chat-worktree-remove") {
            val removed = runCatching { Git.run(linux, basePath, *removeArgs.toTypedArray()) }
            val branch = job.branch
            val deleted = removed.getOrNull()?.takeIf { it.code == 0 }?.let {
                if (branch == null) null else runCatching { Git.run(linux, basePath, "branch", if (force) "-D" else "-d", branch) }.getOrNull()
            }
            runOnUiThread {
                svc.releaseFolder(job.folder)
                if (isDestroyed || isFinishing) return@runOnUiThread
                val result = removed.getOrNull()
                if (result == null || result.code != 0) {
                    showRemoveFailed(job, result)
                    return@runOnUiThread
                }
                removingWorktree = false
                if (branch != null && (deleted == null || deleted.code != 0)) {
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
    private fun showRemoveFailed(job: WorktreeJob, result: Git.Result?) {
        removingWorktree = false
        val shown = result?.shown.orEmpty()
        val body = getString(R.string.chat_worktree_remove_failed) + "\n\n" + shown.ifBlank { getString(R.string.git_failed_title) }
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

    /**
     * Novo projeto: pasta nova na raiz dos projetos (com git init, se marcado) e o trabalho abre
     * nela. Se o git falhou, a pasta fica e um aviso sai antes de trocar de pasta.
     */
    private fun newProject() {
        askNewProject(linux) { created, gitOutput, hostOutput ->
            if (hostOutput != null) {
                Toast.makeText(this, R.string.host_new_failed, Toast.LENGTH_LONG).show()
            } else if (gitOutput != null) {
                Toast.makeText(this, R.string.project_git_failed, Toast.LENGTH_LONG).show()
            }
            pickFolder(created)
        }
    }

    /**
     * Clonar um repositório para uma pasta nova de ~/projetos (deste agente): acompanha o clone e,
     * terminando bem, o trabalho abre nela. Falhou, mostra o motivo e fica nesta pasta.
     */
    private fun startClone() {
        if (service == null) return
        askCloneRepo(linux.rootfs) { url, name ->
            // A tela pode ter saído e voltado com o diálogo aberto: usa o serviço ligado agora.
            val svc = service ?: return@askCloneRepo
            val task = svc.runClone(agentId, url, name)
            if (task == null) {
                Toast.makeText(this, R.string.clone_busy, Toast.LENGTH_LONG).show()
                return@askCloneRepo
            }
            showCloneProgress(svc, task) { done ->
                if (done.state == TerminalService.CloneTask.State.OK) pickFolder(done.folder)
                else showCloneFailure(done)
            }
        }
    }

    /** Contas e chaves do OpenCode: lê as contas fora da thread principal e abre o painel. */
    /**
     * Consumo do OpenCode por modelo nos últimos dias (`opencode stats`, ver [OpenCodeStats]): tokens e
     * custo. Lido fora da thread principal; falha ou formato inesperado avisa e não mostra nada.
     */
    private fun openOpenCodeUsage() {
        runOpenCodeTask(getString(R.string.opencode_usage_loading), {
            val out = StringBuilder()
            linux.runBash(OpenCodeStats.SCRIPT) { out.append(it).append('\n') }
            OpenCodeStats.parse(out.toString())
        }) { usage ->
            if (usage == null) {
                openCodeToast(getString(R.string.opencode_usage_failed))
                return@runOpenCodeTask
            }
            val locale = resources.configuration.locales[0]
            val k = getString(R.string.opencode_usage_thousand)
            val m = getString(R.string.opencode_usage_million)
            fun count(v: Long) = OpenCodeStats.shortCount(v, k, m, locale)
            val summary = getString(
                R.string.opencode_usage_summary,
                OpenCodeStats.DAYS, usage.sessions, count(usage.tokens), OpenCodeStats.dollars(usage.cost, locale),
            )
            val rows = usage.models.map { model ->
                Sheet.Row(
                    title = model.model,
                    description = getString(
                        R.string.opencode_usage_model,
                        count(model.input), count(model.output + model.reasoning), count(model.cacheRead),
                    ),
                    value = OpenCodeStats.dollars(model.cost, locale),
                ) {}
            }
            val sheet = Sheet(this).title(getString(R.string.opencode_usage_title)).message(summary)
            if (rows.isEmpty()) sheet.message(summary + "\n\n" + getString(R.string.opencode_usage_empty))
            sheet.rows(rows).show()
        }
    }

    private fun openOpenCodeAccounts() {
        runOpenCodeTask(getString(R.string.opencode_auth_loading), { OpenCodeAuth.list(linux) }) { accounts ->
            if (accounts == null) openCodeToast(getString(R.string.opencode_auth_read_failed))
            else showOpenCodeAccounts(accounts)
        }
    }

    /** Painel das contas: uma linha por conta (toque remove), Adicionar chave e, havendo contas, Voltar ao plano free. */
    private fun showOpenCodeAccounts(accounts: List<OpenCodeAuth.Account>) {
        val sheet = Sheet(this).title(getString(R.string.opencode_auth_title))
        if (accounts.isEmpty()) sheet.message(getString(R.string.opencode_auth_none))
        accounts.forEach { account ->
            val provider = OpenCodeAuth.displayName(account.integration)
            sheet.row(Sheet.Row(
                title = account.label.ifBlank { provider },
                description = if (account.active) provider else "$provider · ${getString(R.string.opencode_auth_inactive)}",
                icon = "🔑",
            ) { confirmRemoveOpenCodeAccount(account) })
        }
        sheet.row(Sheet.Row(
            title = getString(R.string.opencode_auth_add),
            description = getString(R.string.opencode_auth_add_desc),
            icon = "+",
        ) { showAddOpenCodeKey() })
        if (accounts.isNotEmpty()) sheet.row(Sheet.Row(
            title = getString(R.string.opencode_auth_free),
            description = getString(R.string.opencode_auth_free_desc),
            icon = "↺",
            destructive = true,
        ) { confirmOpenCodeFree() })
        sheet.show()
    }

    /** Escolhe o provedor da chave: os sugeridos ou "Outro…", com o id digitado. */
    private fun showAddOpenCodeKey() {
        val rows = OpenCodeAuth.SUGGESTED.map { (id, name) ->
            Sheet.Row(title = name, value = id, icon = "🔑") { askOpenCodeKey(id, name) }
        }
        Sheet(this)
            .title(getString(R.string.opencode_auth_pick_title))
            .rows(rows)
            .row(Sheet.Row(
                title = getString(R.string.opencode_auth_other),
                description = getString(R.string.opencode_auth_other_desc),
                icon = "…",
            ) { askOtherOpenCodeProvider() })
            .show()
    }

    /** Campo para digitar o id de outro provedor; id fora do padrão mantém o diálogo aberto com o erro. */
    private fun askOtherOpenCodeProvider() {
        val field = EditText(this).apply {
            hint = getString(R.string.opencode_auth_other_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val content = LinearLayout(this).apply {
            setPadding(dpToPx(20), dpToPx(8), dpToPx(20), 0)
            addView(field)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.opencode_auth_other_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = field.text.toString().trim()
                if (OpenCodeAuth.isValidIntegration(id)) {
                    dialog.dismiss()
                    askOpenCodeKey(id, OpenCodeAuth.displayName(id))
                } else {
                    field.error = getString(R.string.opencode_auth_id_invalid)
                }
            }
        }
        dialog.show()
    }

    /**
     * Pede a chave de API (campo de senha) e conecta a integração [id]. A chave só existe no campo e
     * depois no processo do login (ver [OpenCodeAuth.login]); o campo é esvaziado ao sair daqui.
     */
    private fun askOpenCodeKey(id: String, name: String) {
        val field = EditText(this).apply {
            hint = getString(R.string.opencode_auth_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            // A chave não vai para o gerenciador de senhas do Android.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val content = LinearLayout(this).apply {
            setPadding(dpToPx(20), dpToPx(8), dpToPx(20), 0)
            addView(field)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.opencode_auth_key_title, name))
            .setView(content)
            .setPositiveButton(R.string.opencode_auth_connect, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = field.text.toString().trim()
                if (!OpenCodeAuth.isValidKey(key)) {
                    field.error = getString(R.string.opencode_auth_key_invalid)
                    return@setOnClickListener
                }
                field.text.clear()
                dialog.dismiss()
                if (!OpenCodeAuth.canCheckKey(id)) {
                    connectOpenCodeKey(id, name, key)
                    return@setOnClickListener
                }
                // Antes de conectar, pergunta ao provedor se a chave vale (o opencode aceita qualquer texto).
                runOpenCodeTask(getString(R.string.opencode_auth_checking, name), { OpenCodeAuth.checkKey(linux, id, key) }) { check ->
                    if (check == OpenCodeAuth.KeyCheck.INVALID) confirmRejectedKey(id, name, key)
                    else connectOpenCodeKey(id, name, key)
                }
            }
        }
        dialog.show()
    }

    /** Conecta a integração [id] com a chave já conferida (ou que não deu para conferir). */
    private fun connectOpenCodeKey(id: String, name: String, key: String) {
        runOpenCodeTask(getString(R.string.opencode_auth_connecting, name), { OpenCodeAuth.login(linux, id, key) }) { ok ->
            openCodeToast(getString(if (ok == true) R.string.opencode_auth_added else R.string.opencode_auth_add_failed))
        }
    }

    /**
     * O provedor recusou a chave: oferece digitar de novo ou conectar mesmo assim (a resposta do
     * provedor pode estar errada, ou a chave ainda não ter sido liberada).
     */
    private fun confirmRejectedKey(id: String, name: String, key: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.opencode_auth_key_rejected_title)
            .setMessage(getString(R.string.opencode_auth_key_rejected, name))
            .setPositiveButton(R.string.opencode_auth_key_retype) { _, _ -> askOpenCodeKey(id, name) }
            .setNeutralButton(R.string.opencode_auth_key_anyway) { _, _ -> connectOpenCodeKey(id, name, key) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Confirma a remoção de uma conta; a conta sai só com o OK. */
    private fun confirmRemoveOpenCodeAccount(account: OpenCodeAuth.Account) {
        val name = account.label.ifBlank { OpenCodeAuth.displayName(account.integration) }
        if (account.label.isBlank()) {
            // Sem rótulo, o logout do OpenCode pergunta qual conta no terminal: remove por lá.
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.opencode_auth_remove_title, name))
                .setMessage(R.string.opencode_auth_remove_no_label)
                .setPositiveButton(R.string.opencode_auth_open_terminal) { _, _ ->
                    val svc = service ?: return@setPositiveButton
                    if (busyBlocks()) return@setPositiveButton
                    Toast.makeText(this, getString(R.string.login_come_back), Toast.LENGTH_LONG).show()
                    openTerminal(svc, "opencode auth logout ${AgentRecipe.q(account.integration)} --standalone", login = true)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.opencode_auth_remove_title, name))
            .setMessage(R.string.opencode_auth_remove_message)
            .setPositiveButton(R.string.opencode_auth_remove) { _, _ ->
                runOpenCodeTask(getString(R.string.opencode_auth_removing), { OpenCodeAuth.remove(linux, account) }) { ok ->
                    openCodeToast(getString(if (ok == true) R.string.opencode_auth_removed else R.string.opencode_auth_remove_failed))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Confirma o plano free: remove todas as contas. */
    private fun confirmOpenCodeFree() {
        AlertDialog.Builder(this)
            .setTitle(R.string.opencode_auth_free_title)
            .setMessage(R.string.opencode_auth_free_message)
            .setPositiveButton(R.string.opencode_auth_free) { _, _ ->
                runOpenCodeTask(getString(R.string.opencode_auth_removing), { OpenCodeAuth.removeAll(linux) }) { left ->
                    openCodeToast(
                        when {
                            left == null -> getString(R.string.opencode_auth_read_failed)
                            left > 0 -> getString(R.string.opencode_auth_free_partial, left)
                            else -> getString(R.string.opencode_auth_free_done)
                        },
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Roda [work] (um comando do OpenCode) fora da thread principal, com o aviso [busy] enquanto roda,
     * e entrega o resultado na principal ([then] recebe null se falhou). Uma tarefa de contas por vez.
     */
    private fun <T> runOpenCodeTask(busy: String, work: () -> T, then: (T?) -> Unit) {
        if (openCodeBusy) {
            openCodeToast(getString(R.string.opencode_auth_busy))
            return
        }
        openCodeBusy = true
        // O login leva de 16 s a 1 min: um aviso que fica na tela até acabar (um Toast some em 3 s).
        val progress = AlertDialog.Builder(this).setMessage(busy).setCancelable(false).show()
        thread(name = "opencode-auth") {
            // Um erro inesperado não derruba o app: o resultado vira null.
            val result = runCatching { work() }.getOrNull()
            onUi {
                openCodeBusy = false
                runCatching { progress.dismiss() }
                then(result)
            }
        }
    }

    private fun openCodeToast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

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
                    if (value != current && !busyBlocks()) {
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

    /**
     * Linha da compactação automática deste trabalho (painel do chip de contexto e menu ⋮). O valor é
     * curto (o efetivo); quando ele vem do ajuste geral, a descrição diz isso.
     */
    private fun autoCompactRow(): Sheet.Row {
        val job = settings.autoCompactOfJob(chatKey)
        val fromGeneral = job == AutoCompact.GLOBAL
        return Sheet.Row(
            title = getString(R.string.auto_compact_title),
            description = if (fromGeneral) getString(R.string.auto_compact_from_general) else null,
            value = autoCompactShort(this, if (fromGeneral) settings.autoCompact else job),
            icon = "🗜",
        ) { chooseAutoCompact() }
    }

    /**
     * Escolha da compactação deste trabalho: o ajuste geral, o padrão do agente, desligada (só no Claude)
     * e os percentuais. Nos agentes ACP, a nota explica que o app manda /compact.
     */
    private fun chooseAutoCompact() {
        val job = settings.autoCompactOfJob(chatKey)
        val rows = mutableListOf(
            Sheet.Row(
                title = getString(R.string.auto_compact_global, autoCompactShort(this, settings.autoCompact)),
                checked = job == AutoCompact.GLOBAL,
            ) { saveAutoCompact(AutoCompact.GLOBAL) },
        )
        val values = listOfNotNull(AutoCompact.DEFAULT, AutoCompact.OFF.takeIf { isClaude }) + AutoCompact.PERCENTS.map { it.toString() }
        rows += values.map { value ->
            Sheet.Row(title = autoCompactText(this, value), checked = job == value) { saveAutoCompact(value) }
        }
        val sheet = Sheet(this).title(getString(R.string.auto_compact_title))
        if (!isClaude) sheet.message(getString(R.string.auto_compact_acp_note))
        sheet.rows(rows).show()
    }

    /**
     * Guarda a compactação do trabalho. O Claude só lê o ajuste ao iniciar: se o valor que vale mudou,
     * reabre a mesma conversa (como o modelo e o esforço). Nos ACP, o valor é lido a cada fim de turno.
     */
    private fun saveAutoCompact(value: String) {
        // Só o Claude reabre a conversa; no ACP, mudar o valor não corta nada. Escolher o valor atual não conta.
        if (isClaude && settings.autoCompactOfJob(chatKey) != value && busyBlocks()) return
        val before = settings.autoCompactFor(chatKey)
        settings.setAutoCompactOfJob(chatKey, value)
        if (!isClaude || settings.autoCompactFor(chatKey) == before) return
        val svc = service ?: return
        val id = engine?.sessionId
        reopenChat(svc) { svc.openChat(agentId, folder = folder, resume = id, restart = true) }
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
    private fun claudeLaunchScript(args: String): String {
        // Compactação automática do trabalho: vale só para este Claude (o shell da aba segue aberto depois).
        val autoCompact = settings.autoCompactFor(chatKey)
        return AutoCompact.claudeEnvPrefix(autoCompact) + AgentRecipe.claudeIn(Workspace.path(folder), exec = false) + args +
            AutoCompact.claudeEnvSuffix(autoCompact)
    }

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
        // Texto enorme (resposta de centenas de KB) pode estourar o limite do Binder: avisa em vez de cair.
        val copied = runCatching {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(agentName, text))
        }.isSuccess
        Toast.makeText(this, if (copied) R.string.chat_copied else R.string.chat_copy_failed, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** Fundo com o efeito de toque do tema, para linhas clicáveis da conversa. */
    private val selectableBackground by lazy {
        TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
    }

    /** Texto do botão. "Sempre permitir" diz o que libera; só o Claude Code (com ferramenta) muda. */
    private fun optionLabel(item: ChatItem.Permission, option: PermissionOption): String = when {
        item.toolName == ChatEngine.PLAN_TOOL && option.kind == PermissionOption.Kind.ALLOW_ONCE -> getString(R.string.chat_plan_approve)
        item.toolName == ChatEngine.PLAN_TOOL && option.kind == PermissionOption.Kind.REJECT_ONCE -> getString(R.string.chat_plan_keep)
        item.questions.isNotEmpty() && option.kind == PermissionOption.Kind.ALLOW_ONCE -> getString(R.string.chat_question_answer)
        item.questions.isNotEmpty() && option.kind == PermissionOption.Kind.REJECT_ONCE -> getString(R.string.chat_question_skip)
        else -> optionLabelDefault(item, option)
    }

    private fun optionLabelDefault(item: ChatItem.Permission, option: PermissionOption): String = when (option.kind) {
        PermissionOption.Kind.ALLOW_ONCE -> getString(R.string.chat_allow)
        PermissionOption.Kind.ALLOW_ALWAYS -> when {
            ChatEngine.isShellTool(item.toolName) -> getString(R.string.chat_allow_always_command)
            item.toolName.isNotEmpty() -> getString(R.string.chat_allow_always_tool, item.title)
            else -> getString(R.string.chat_allow_always)
        }
        PermissionOption.Kind.REJECT_ONCE -> getString(R.string.chat_deny)
        PermissionOption.Kind.REJECT_ALWAYS -> getString(R.string.chat_deny_always)
        // Tipo desconhecido: o nome que o agente deu (o app não sabe o que a opção faz).
        PermissionOption.Kind.OTHER -> option.label.ifEmpty { option.id }
    }

    /** Motivo de risco do pedido, traduzido (linha "⚠ Alto risco: …"). */
    private fun riskReason(reason: PermissionRisk.Reason): String = getString(
        when (reason) {
            PermissionRisk.Reason.PUSH -> R.string.chat_risk_push
            PermissionRisk.Reason.MERGE -> R.string.chat_risk_merge
            PermissionRisk.Reason.TAG -> R.string.chat_risk_tag
            PermissionRisk.Reason.RELEASE -> R.string.chat_risk_release
            PermissionRisk.Reason.API -> R.string.chat_risk_api
            PermissionRisk.Reason.WORKFLOW -> R.string.chat_risk_workflow
            PermissionRisk.Reason.DELETE -> R.string.chat_risk_delete
            PermissionRisk.Reason.OUTSIDE -> R.string.chat_risk_outside
            PermissionRisk.Reason.SHELL_PIPE -> R.string.chat_risk_shell_pipe
        },
    )

    /** Pedidos de permissão ainda sem resposta (cópia da lista: ela muda na thread principal). */
    private fun pendingPermissions(): List<ChatItem.Permission> =
        engine?.items?.filterIsInstance<ChatItem.Permission>()?.filter { it.chosen == null }.orEmpty()

    /** Só o primeiro pedido pendente responde; os outros só mostram o pedido e esperam a vez. */
    private fun isFirstPending(item: ChatItem.Permission): Boolean = pendingPermissions().firstOrNull() === item

    /**
     * Permitir do cartão. Em alto risco o primeiro toque só arma a confirmação (por [CONFIRM_PERMISSION_MS]);
     * o segundo, dentro do prazo, responde. Fora do prazo, o toque recomeça a confirmação.
     * Recusar não passa por aqui (responde em um toque).
     */
    private fun askPermission(
        item: ChatItem.Permission,
        option: PermissionOption,
        analysis: PermissionRisk.Analysis,
    ) {
        if (!analysis.high) {
            engine?.answer(item, option)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < item.confirmUntilMs) {
            item.confirmUntilMs = 0L
            engine?.answer(item, option)
            return
        }
        item.confirmUntilMs = now + CONFIRM_PERMISSION_MS
        adapter.notifyDataSetChanged()
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

        /** Linha de estado de cada cartão de subagente montado (para o tempo andar sem redesenhar). */
        private val stateViews = WeakHashMap<ChatItem.Child, TextView>()

        /** Um tipo de linha para cada tipo de item (o Android reaproveita só dentro do mesmo tipo). */
        override fun getViewTypeCount() = 10

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
            is ChatItem.Child -> 9
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
                    is ChatItem.Child -> childCard(item)
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
                setTextColor(getColor(R.color.setup_muted_text))
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
            setTextColor(getColor(if (item.failed) R.color.setup_error_text else R.color.setup_muted_text))
            setPadding(dp(4), dp(2), dp(4), dp(2))
            gravity = Gravity.CENTER_VERTICAL
            if (output != null) {
                // Alvo de toque de 48dp (mínimo recomendado pelo Android).
                minHeight = dp(48)
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
            setTextColor(getColor(R.color.setup_muted_text))
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
            val running = tools.lastOrNull { it.state == ToolState.RUNNING } ?: tools.lastOrNull { !it.done }
            // Só falha pinta o grupo de vermelho; cancelado/recusado é neutro.
            val failed = tools.any { it.state == ToolState.FAILED }
            val mark = when {
                failed -> "✕"
                running != null -> "…"
                // Cancelado ou recusado não é sucesso nem falha: marca neutra.
                tools.any { it.state == ToolState.CANCELLED || it.state == ToolState.REJECTED } -> "–"
                else -> "✓"
            }
            val counts = tools.groupBy { toolLabel(it) }
                .entries.sortedByDescending { it.value.size }
                .take(3)
                .joinToString(", ") { "${it.value.size}× ${it.key}" }
            val summary = resources.getQuantityString(R.plurals.chat_tool_group, tools.size, tools.size) + " · " + counts
            val second = running?.let { "\n" + toolLabel(it) + "  " + it.summary }
            text = "$mark $summary  ›" + second.orEmpty()
            // A linha de cima pode quebrar em duas; com a ação em andamento embaixo, cabem três.
            maxLines = if (second != null) 3 else 2
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.MONOSPACE
            textSize = 12f * chatScale
            setTextColor(getColor(if (failed) R.color.setup_error_text else R.color.setup_muted_text))
            setPadding(dp(4), dp(2), dp(4), dp(2))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setBackgroundResource(selectableBackground)
            setOnClickListener { showToolGroup(item) }
            contentDescription = getString(R.string.chat_tool_group_description, summary + running?.let { ". " + toolLabel(it) + " " + it.summary }.orEmpty())
        }

        /**
         * Cartão de um subagente (sessão filha do opencode): estado com o tempo, descrição, ações e,
         * ao terminar, o resultado. Só exibição: o toque abre e fecha as ações, sem mexer na conversa.
         */
        private fun childCard(item: ChatItem.Child): View {
            val card = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(dp(14), dp(12), dp(14), dp(10))
            }
            // Cabeçalho: "Subagente · explore" (o agente filho só quando o pai informa).
            val head = if (item.agent.isEmpty()) getString(R.string.chat_child_title)
            else getString(R.string.chat_child_title) + " · " + item.agent
            // Estado e tempo: "… Em andamento · 0:12"; o tempo para quando ele termina.
            val state = childStateLine(item, engine?.items?.toList().orEmpty())
            card.addView(childText(head, 15f, R.color.setup_text).apply {
                setTypeface(typeface, Typeface.BOLD)
                contentDescription = getString(R.string.chat_child_description, head, state)
            })
            // A linha de estado é guardada para o relógio da faixa reescrever só ela (ver refreshChildTimes).
            val stateView = childText(state, 12f, R.color.setup_muted_text).apply { tag = item }
            stateViews[item] = stateView
            card.addView(stateView)
            if (item.description.isNotEmpty()) {
                card.addView(childText(item.description, 14f, R.color.setup_text).apply {
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                })
            }
            if (item.actions.isNotEmpty()) {
                val count = resources.getQuantityString(R.plurals.chat_child_actions, item.actions.size, item.actions.size)
                // Fechado, a ação em andamento (ou a última) já aparece na linha.
                val current = item.actions.lastOrNull { it.state == ToolState.RUNNING } ?: item.actions.last()
                val detail = ToolText.label(this@ChatActivity, current.name) + "  " + current.summary
                card.addView(childText("$count · $detail", 12f, R.color.setup_muted_text).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(48)
                    setPadding(dp(4), 0, dp(4), 0)
                    setBackgroundResource(selectableBackground)
                    setOnClickListener {
                        item.expanded = !item.expanded
                        notifyDataSetChanged()
                    }
                    val hint = getString(if (item.expanded) R.string.chat_child_hide_actions else R.string.chat_child_show_actions)
                    contentDescription = count + ". " + hint
                })
                if (item.expanded) {
                    for (action in item.actions) {
                        card.addView(childText(actionMark(action.state) + " " + ToolText.label(this@ChatActivity, action.name) + "  " + action.summary, 12f, R.color.setup_muted_text).apply {
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(dp(4), dp(2), dp(4), dp(2))
                        })
                    }
                }
            }
            if (item.state != ChildState.RUNNING) {
                // O erro vem antes do resultado; o resultado aparece inteiro com o cartão aberto.
                item.error?.takeIf { it.isNotBlank() }?.let {
                    card.addView(childText(it, 13f, R.color.setup_error_text).apply { setPadding(0, dp(6), 0, 0) })
                }
                if (item.result.isNotEmpty()) {
                    card.addView(childText(item.result, 13f, R.color.setup_text).apply {
                        maxLines = if (item.expanded) Int.MAX_VALUE else 6
                        ellipsize = TextUtils.TruncateAt.END
                        setTextIsSelectable(true)
                        setPadding(0, dp(6), 0, 0)
                    })
                }
            }
            return card
        }

        /** Linha de texto do cartão de subagente. */
        private fun childText(body: String, size: Float, color: Int) = TextView(this@ChatActivity).apply {
            text = body
            textSize = size * chatScale
            setTextColor(getColor(color))
        }

        /** Linha de estado do cartão de subagente: marca, estado e o tempo decorrido. */
        private fun childStateLine(item: ChatItem.Child, items: List<ChatItem>): String {
            val until = if (item.endedAtMs > 0) item.endedAtMs else SystemClock.elapsedRealtime()
            // Sem o início informado, o tempo conta de zero (e não desde o boot do aparelho).
            val since = if (item.startedAtMs > 0) item.startedAtMs else until
            // Rodando, mas esperando a permissão do usuário: diz isso, para não parecer travado.
            val label = when {
                ChildAgents.waitingPermission(item, items) -> R.string.chat_child_state_waiting
                else -> when (item.state) {
                    ChildState.RUNNING -> R.string.chat_child_state_running
                    ChildState.DONE -> R.string.chat_child_state_done
                    ChildState.FAILED -> R.string.chat_child_state_failed
                    ChildState.INTERRUPTED -> R.string.chat_child_state_interrupted
                }
            }
            return childMark(item.state) + " " + getString(label) + " · " + AgentStatus.elapsed(until - since)
        }

        /**
         * Reescreve só a linha de estado dos cartões que ainda rodam (chamado pelo relógio da faixa,
         * uma vez por segundo): nada de redesenhar a lista por causa do tempo.
         */
        fun refreshChildTimes() {
            val items = engine?.items?.toList().orEmpty()
            for ((card, view) in stateViews) {
                // A view pode ter sido reaproveitada para outro cartão (o tag diz de quem ela é).
                if (card.state != ChildState.RUNNING || view.tag !== card) continue
                view.text = childStateLine(card, items)
            }
        }

        /** Marca de um subagente: … rodando, ✓ terminou, ✕ falhou, – interrompido. */
        private fun childMark(state: ChildState) = when (state) {
            ChildState.RUNNING -> "…"
            ChildState.DONE -> "✓"
            ChildState.FAILED -> "✕"
            ChildState.INTERRUPTED -> "–"
        }

        /** Marca de uma ação do subagente (mesmo critério das ações do chat). */
        private fun actionMark(state: ToolState) = when (state) {
            ToolState.DONE -> "✓"
            ToolState.FAILED -> "✕"
            ToolState.CANCELLED, ToolState.REJECTED -> "–"
            ToolState.PENDING, ToolState.RUNNING -> "…"
        }

        /**
         * Cartão do pedido de permissão: o único lugar com Permitir e Recusar (a faixa fixa só mostra o
         * pedido). Mostra o comando inteiro, encadeado, a pasta de trabalho, o risco e a fila de pedidos;
         * em alto risco o Permitir pede um segundo toque.
         */
        private fun permissionCard(item: ChatItem.Permission): View {
            if (item.questions.isNotEmpty()) return questionCard(item)
            // Plano: cartão próprio (Markdown, aprovação no cartão), como sempre foi.
            if (item.markdown || item.toolName == ChatEngine.PLAN_TOOL) return planCard(item)
            val card = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(dp(14), dp(12), dp(14), dp(10))
            }
            card.addView(TextView(this@ChatActivity).apply {
                text = getString(R.string.chat_permission_wants, agentName)
                setTextColor(getColor(R.color.setup_text))
                textSize = 15f * chatScale
                setTypeface(typeface, Typeface.BOLD)
            })
            // Pedido de um subagente (sessão filha): diz qual, logo abaixo de "X quer permissão".
            item.childSessionId?.let { childId ->
                val label = ChildAgents.label(childId, engine?.items?.toList().orEmpty(), item.childTitle)
                card.addView(TextView(this@ChatActivity).apply {
                    text = if (label.isEmpty()) getString(R.string.chat_child_title)
                    else getString(R.string.chat_permission_child, label)
                    setTextColor(getColor(R.color.setup_muted_text))
                    textSize = 12f * chatScale
                    setTextIsSelectable(true)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            // De que ferramenta é o pedido: o nome do protocolo vira o rótulo humano (ex.: "shell" → Rodar comando).
            card.addView(TextView(this@ChatActivity).apply {
                text = ToolText.label(this@ChatActivity, item.toolName.ifEmpty { item.title })
                setTextColor(getColor(R.color.setup_muted_text))
                textSize = 13f * chatScale
                setPadding(0, dp(2), 0, 0)
            })
            val command = item.summary.ifBlank { item.title }
            // Edição/escrita não traz cwd: o próprio comando é o caminho do arquivo (vale para "fora do projeto").
            val analysis = PermissionRisk.analyze(
                command,
                PermissionRisk.workPath(item.workdir, item.toolName, command),
                engine?.cwd,
            )
            val expanded = item.expanded
            // Leigo: só o essencial (o que o agente quer fazer, em até 2 linhas). Detalhes abre o resto.
            if (!expanded && command.isNotBlank()) {
                card.addView(TextView(this@ChatActivity).apply {
                    text = command
                    textSize = 14f * chatScale
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.setup_text))
                    setPadding(0, dp(6), 0, dp(2))
                })
            }
            if (expanded && analysis.lines.isNotEmpty()) {
                card.addView(TextView(this@ChatActivity).apply {
                    text = analysis.lines.joinToString("\n")
                    typeface = Typeface.MONOSPACE
                    textSize = 12f * chatScale
                    setTextColor(getColor(R.color.setup_muted_text))
                    setTextIsSelectable(true)
                    setPadding(0, dp(6), 0, dp(2))
                })
            }
            if (expanded) {
                item.diff?.let { card.addView(diffView(it)) }
                if (item.detail.isNotEmpty()) {
                    card.addView(TextView(this@ChatActivity).apply {
                        text = item.detail
                        textSize = 12f * chatScale
                        setTextColor(getColor(R.color.setup_muted_text))
                        setPadding(0, 0, 0, dp(4))
                    })
                }
                if (!item.workdir.isNullOrBlank()) {
                    card.addView(TextView(this@ChatActivity).apply {
                        text = getString(R.string.chat_permission_workdir, item.workdir)
                        typeface = Typeface.MONOSPACE
                        textSize = 11f * chatScale
                        setTextColor(getColor(R.color.setup_muted_text))
                        setPadding(0, dp(2), 0, dp(2))
                    })
                }
            }
            if (item.chosen == null) {
                card.addView(Button(this@ChatActivity, null, android.R.attr.borderlessButtonStyle).apply {
                    text = getString(if (expanded) R.string.chat_permission_hide_full else R.string.chat_permission_show_full)
                    isAllCaps = false
                    textSize = 13f * chatScale
                    minHeight = dp(48)
                    setTextColor(getColor(R.color.accent_text))
                    setOnClickListener {
                        item.expanded = !item.expanded
                        // Só o adaptador: um refresh() da tela pularia a rolagem da conversa.
                        this@ChatAdapter.notifyDataSetChanged()
                    }
                })
            }
            if (analysis.high) {
                card.addView(TextView(this@ChatActivity).apply {
                    text = getString(
                        R.string.chat_permission_risk,
                        analysis.reasons.joinToString(", ") { riskReason(it) },
                    )
                    textSize = 13f * chatScale
                    setTextColor(getColor(R.color.setup_error_text))
                    setPadding(0, dp(4), 0, dp(2))
                })
            }
            val chosen = item.chosen
            if (chosen != null) {
                card.addView(TextView(this@ChatActivity).apply {
                    text = optionLabel(item, chosen)
                    setTextColor(getColor(if (chosen.allows) R.color.setup_done_text else R.color.setup_error_text))
                    textSize = 13f * chatScale
                    setPadding(0, dp(4), 0, 0)
                })
                return card
            }
            // Fila: só o primeiro pedido pendente tem botões; os outros esperam a resposta dele.
            val pending = pendingPermissions()
            val position = pending.indexOfFirst { it === item } + 1
            if (pending.size > 1 && position > 0) {
                card.addView(TextView(this@ChatActivity).apply {
                    text = getString(R.string.chat_permission_queue, position, pending.size)
                    textSize = 12f * chatScale
                    setTextColor(getColor(R.color.setup_muted_text))
                    setPadding(0, dp(2), 0, 0)
                })
            }
            if (position > 1) {
                card.addView(TextView(this@ChatActivity).apply {
                    text = getString(R.string.chat_permission_wait_previous, 1, pending.size)
                    textSize = 12f * chatScale
                    setTextColor(getColor(R.color.setup_muted_text))
                    setPadding(0, dp(4), 0, 0)
                })
            } else {
                addPermissionButtons(card, item, analysis)
            }
            return card
        }

        /**
         * Permitir e Recusar lado a lado (só o primeiro pedido pendente), com as demais opções como
         * botões de texto embaixo. "Sempre" some no ACP e em alto risco.
         */
        private fun addPermissionButtons(
            card: LinearLayout,
            item: ChatItem.Permission,
            analysis: PermissionRisk.Analysis,
        ) {
            val allow = item.options.firstOrNull { it.kind == PermissionOption.Kind.ALLOW_ONCE }
            val deny = item.options.firstOrNull { it.kind == PermissionOption.Kind.REJECT_ONCE }
            if (allow != null || deny != null) {
                val row = LinearLayout(this@ChatActivity).apply { orientation = LinearLayout.HORIZONTAL }
                if (allow != null) {
                    val confirming = item.confirmUntilMs > SystemClock.elapsedRealtime()
                    val button = Button(this@ChatActivity).apply {
                        text = if (confirming) getString(R.string.chat_permission_confirm) else optionLabel(item, allow)
                        isAllCaps = false
                        textSize = 14f * chatScale
                        minHeight = dp(48)
                        backgroundTintList = ColorStateList.valueOf(getColor(R.color.key_active))
                        setTextColor(getColor(R.color.setup_on_accent))
                        setOnClickListener { askPermission(item, allow, analysis) }
                    }
                    row.addView(
                        button,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) },
                    )
                }
                if (deny != null) {
                    val button = Button(this@ChatActivity, null, android.R.attr.borderlessButtonStyle).apply {
                        text = getString(R.string.chat_deny)
                        isAllCaps = false
                        textSize = 14f * chatScale
                        minHeight = dp(48)
                        setTextColor(getColor(R.color.setup_error_text))
                        setOnClickListener { engine?.answer(item, deny) }
                    }
                    row.addView(
                        button,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                }
                card.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            val hideAlways = engine is AcpChat || analysis.high
            val extra = item.options.filter { option ->
                when (option.kind) {
                    PermissionOption.Kind.ALLOW_ONCE, PermissionOption.Kind.REJECT_ONCE -> false
                    PermissionOption.Kind.ALLOW_ALWAYS -> !hideAlways
                    // Tipo fora do protocolo no ACP pode liberar amplo (allow_session), e em alto risco
                    // não passaria pelo segundo toque: não vira botão.
                    PermissionOption.Kind.OTHER -> engine !is AcpChat && !analysis.high
                    PermissionOption.Kind.REJECT_ALWAYS -> true
                }
            }
            if (extra.isEmpty()) return
            val more = LinearLayout(this@ChatActivity).apply { orientation = LinearLayout.VERTICAL }
            for (option in extra) {
                val button = Button(this@ChatActivity, null, android.R.attr.borderlessButtonStyle).apply {
                    text = optionLabel(item, option)
                    isAllCaps = false
                    textSize = 14f * chatScale
                    setTextColor(getColor(if (option.allows) R.color.accent_text else R.color.setup_error_text))
                    setOnClickListener { engine?.answer(item, option) }
                }
                more.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            card.addView(more)
        }

        /** Plano do ExitPlanMode: resumo em Markdown e aprovação no cartão (sem risco nem fila). */
        private fun planCard(item: ChatItem.Permission): View {
            val card = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(dp(14), dp(12), dp(14), dp(10))
            }
            card.addView(TextView(this@ChatActivity).apply {
                text = getString(R.string.chat_plan_wants, agentName)
                setTextColor(getColor(R.color.setup_text))
                textSize = 15f * chatScale
                setTypeface(typeface, Typeface.BOLD)
            })
            if (item.summary.isNotEmpty()) {
                card.addView(TextView(this@ChatActivity).apply {
                    text = Markdown.render(item.summary, getColor(R.color.code_bg))
                    textSize = 14f * chatScale
                    maxLines = 25
                    setTextColor(getColor(R.color.setup_text))
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(6), 0, dp(4))
                    // Texto cortado (reticências na última linha): tocar mostra o plano inteiro.
                    setOnClickListener {
                        val lay = this.layout
                        if (lay != null && lay.getEllipsisCount(lay.lineCount - 1) > 0) {
                            showDetails(R.string.chat_permission_full, item.summary)
                        }
                    }
                })
            }
            // Fila: fora a vez, só o cabeçalho e o resumo; a aprovação é do primeiro pedido pendente.
            if (item.chosen == null && !isFirstPending(item)) {
                card.addView(TextView(this@ChatActivity).apply {
                    text = getString(R.string.chat_permission_wait_previous, 1, pendingPermissions().size)
                    textSize = 12f * chatScale
                    setTextColor(getColor(R.color.setup_muted_text))
                    setPadding(0, dp(4), 0, 0)
                })
                return card
            }
            if (item.detail.isNotEmpty()) {
                card.addView(TextView(this@ChatActivity).apply {
                    text = item.detail
                    textSize = 12f * chatScale
                    setTextColor(getColor(R.color.setup_muted_text))
                    setPadding(0, 0, 0, dp(4))
                })
            }
            val chosen = item.chosen
            if (chosen != null) {
                card.addView(TextView(this@ChatActivity).apply {
                    text = optionLabel(item, chosen)
                    setTextColor(getColor(if (chosen.allows) R.color.setup_done_text else R.color.setup_error_text))
                    textSize = 13f * chatScale
                    setPadding(0, dp(4), 0, 0)
                })
                return card
            }
            val buttons = LinearLayout(this@ChatActivity).apply { orientation = LinearLayout.VERTICAL }
            // Permitir primeiro e em destaque; as outras opções como botões de texto.
            for (option in item.options.sortedBy { it.kind.ordinal }) {
                // Tipo fora do protocolo no ACP pode liberar amplo (allow_session): não vira botão.
                if (option.kind == PermissionOption.Kind.OTHER && engine is AcpChat) continue
                val primary = option.kind == PermissionOption.Kind.ALLOW_ONCE
                val button = if (primary) Button(this@ChatActivity) else Button(this@ChatActivity, null, android.R.attr.borderlessButtonStyle)
                button.text = optionLabel(item, option)
                button.isAllCaps = false
                button.textSize = 14f * chatScale
                if (primary) {
                    button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.key_active))
                    button.setTextColor(getColor(R.color.setup_on_accent))
                } else {
                    button.setTextColor(getColor(if (option.allows) R.color.accent_text else R.color.setup_error_text))
                }
                button.setOnClickListener { engine?.answer(item, option) }
                buttons.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            card.addView(buttons)
            return card
        }

        /**
         * Cartão das perguntas do AskUserQuestion. Todo o estado (opções marcadas e texto livre) fica
         * em [ChatItem.Permission.selected] e [ChatItem.Permission.otherText]: a lista redesenha a qualquer
         * momento, então a tela nunca guarda seleção só na View.
         */
        private fun questionCard(item: ChatItem.Permission): View {
            val card = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(dp(14), dp(12), dp(14), dp(10))
            }
            card.addView(TextView(this@ChatActivity).apply {
                text = getString(R.string.chat_question_wants, agentName)
                setTextColor(getColor(R.color.setup_text))
                textSize = 15f * chatScale
                setTypeface(typeface, Typeface.BOLD)
            })
            val chosen = item.chosen
            val answers = item.answers
            if (chosen != null && !chosen.allows) {
                card.addView(TextView(this@ChatActivity).apply {
                    text = getString(R.string.chat_question_skipped)
                    setTextColor(getColor(R.color.setup_error_text))
                    textSize = 13f * chatScale
                    setPadding(0, dp(4), 0, 0)
                })
                return card
            }
            if (chosen != null) {
                // Já respondida: pergunta e resposta, sem as opções.
                for (question in item.questions) {
                    card.addView(TextView(this@ChatActivity).apply {
                        text = question.text + "\n→ " + answers?.get(question.text).orEmpty()
                        setTextColor(getColor(R.color.setup_text))
                        textSize = 14f * chatScale
                        setPadding(0, dp(6), 0, dp(2))
                    })
                }
                return card
            }
            // Fila: fora a vez, só o título da pergunta; opções e o botão Responder ficam para o primeiro.
            if (!isFirstPending(item)) {
                for (question in item.questions) {
                    if (question.header.isNotEmpty()) {
                        card.addView(TextView(this@ChatActivity).apply {
                            text = question.header
                            setTextColor(getColor(R.color.setup_muted_text))
                            textSize = 12f * chatScale
                            setPadding(0, dp(8), 0, 0)
                        })
                    }
                    card.addView(TextView(this@ChatActivity).apply {
                        text = question.text
                        setTextColor(getColor(R.color.setup_text))
                        textSize = 15f * chatScale
                        setPadding(0, dp(4), 0, dp(2))
                    })
                }
                card.addView(TextView(this@ChatActivity).apply {
                    text = getString(R.string.chat_permission_wait_previous, 1, pendingPermissions().size)
                    textSize = 12f * chatScale
                    setTextColor(getColor(R.color.setup_muted_text))
                    setPadding(0, dp(4), 0, 0)
                })
                return card
            }
            item.questions.forEachIndexed { q, question ->
                if (question.header.isNotEmpty()) {
                    card.addView(TextView(this@ChatActivity).apply {
                        text = question.header
                        setTextColor(getColor(R.color.setup_muted_text))
                        textSize = 12f * chatScale
                        setPadding(0, dp(8), 0, 0)
                    })
                }
                card.addView(TextView(this@ChatActivity).apply {
                    text = question.text
                    setTextColor(getColor(R.color.setup_text))
                    textSize = 15f * chatScale
                    setPadding(0, dp(4), 0, dp(2))
                })
                val marked = item.selected[q].orEmpty()
                val otherText = item.otherText[q]
                question.options.forEachIndexed { o, option ->
                    val on = marked.contains(o)
                    val mark = when {
                        question.multiSelect -> if (on) "☑ " else "☐ "
                        else -> if (on) "◉ " else "○ "
                    }
                    card.addView(questionRow(mark + option.label, option.description, on, option.label) {
                        if (question.multiSelect) {
                            val set = item.selected.getOrPut(q) { HashSet() }
                            if (!set.add(o)) set.remove(o)
                        } else {
                            item.selected[q] = mutableSetOf(o)
                            item.otherText.remove(q)
                        }
                        this@ChatAdapter.notifyDataSetChanged()
                    })
                }
                if (otherText != null) {
                    val mark = if (question.multiSelect) "☑ " else "◉ "
                    card.addView(questionRow(mark + otherText, "", true, otherText) { editOther(item, q, question.multiSelect) })
                }
                card.addView(questionRow(getString(R.string.chat_question_other), "", false, getString(R.string.chat_question_other)) {
                    editOther(item, q, question.multiSelect)
                })
            }
            val buttons = LinearLayout(this@ChatActivity).apply { orientation = LinearLayout.VERTICAL }
            val answer = Button(this@ChatActivity).apply {
                text = getString(R.string.chat_question_answer)
                isAllCaps = false
                textSize = 14f * chatScale
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.key_active))
                setTextColor(getColor(R.color.setup_on_accent))
                setOnClickListener { sendQuestionAnswers(item) }
            }
            val skip = Button(this@ChatActivity, null, android.R.attr.borderlessButtonStyle).apply {
                text = getString(R.string.chat_question_skip)
                isAllCaps = false
                textSize = 14f * chatScale
                setTextColor(getColor(R.color.setup_error_text))
                setOnClickListener { engine?.answer(item, item.options.first { it.kind == PermissionOption.Kind.REJECT_ONCE }) }
            }
            buttons.addView(answer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            buttons.addView(skip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(buttons)
            return card
        }

        /** Uma opção do cartão de perguntas: linha clicável com a marca, o rótulo e a descrição (se houver). */
        private fun questionRow(label: String, description: String, on: Boolean, spoken: String, onClick: () -> Unit) =
            LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(selectableBackground)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                isClickable = true
                isFocusable = true
                contentDescription = getString(
                    if (on) R.string.chat_question_selected else R.string.chat_question_not_selected,
                    spoken,
                )
                addView(TextView(this@ChatActivity).apply {
                    text = label
                    setTextColor(getColor(R.color.setup_text))
                    textSize = 14f * chatScale
                })
                if (description.isNotEmpty()) {
                    addView(TextView(this@ChatActivity).apply {
                        text = description
                        setTextColor(getColor(R.color.setup_muted_text))
                        textSize = 12f * chatScale
                    })
                }
                setOnClickListener { onClick() }
            }

        /** Abre o campo de "Outra resposta" da pergunta [q], preenchido com o texto atual. */
        private fun editOther(item: ChatItem.Permission, q: Int, multiSelect: Boolean) {
            val field = EditText(this@ChatActivity).apply {
                setText(item.otherText[q].orEmpty())
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            val content = LinearLayout(this@ChatActivity).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(field)
            }
            AlertDialog.Builder(this@ChatActivity)
                .setTitle(R.string.chat_question_other)
                .setView(content)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val text = field.text.toString().trim()
                    if (text.isEmpty()) return@setPositiveButton
                    item.otherText[q] = text
                    if (!multiSelect) item.selected.remove(q)
                    this@ChatAdapter.notifyDataSetChanged()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /** Responder: junta as escolhas de cada pergunta; se faltar alguma, avisa e não envia. */
        private fun sendQuestionAnswers(item: ChatItem.Permission) {
            val answers = LinkedHashMap<String, String>()
            for ((q, question) in item.questions.withIndex()) {
                val parts = mutableListOf<String>()
                question.options.forEachIndexed { o, option ->
                    if (item.selected[q]?.contains(o) == true) parts += option.label
                }
                item.otherText[q]?.let { parts += it }
                if (parts.isEmpty()) {
                    Toast.makeText(this@ChatActivity, R.string.chat_question_missing, Toast.LENGTH_SHORT).show()
                    return
                }
                answers[question.text] = parts.joinToString(", ")
            }
            (engine as? ChatEngine)?.answerQuestions(item, answers)
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
                        line.startsWith("- ") -> R.color.setup_error_text
                        line.startsWith("+ ") -> R.color.setup_done_text
                        else -> R.color.setup_muted_text
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
                setTextColor(getColor(R.color.setup_muted_text))
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
                    setTextColor(getColor(R.color.setup_done_text))
                    textSize = 14f * chatScale
                })
                else -> {
                    item.message?.let { message ->
                        card.addView(TextView(this@ChatActivity).apply {
                            text = message
                            setTextColor(getColor(R.color.setup_error_text))
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
            setTextColor(getColor(R.color.setup_muted_text))
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        /** Aviso da conversa; com [ChatItem.Info.details], um link "Detalhes" abre o erro completo. */
        private fun infoText(item: ChatItem.Info, old: TextView?) = (old ?: TextView(this@ChatActivity)).apply {
            val details = item.details
            val url = item.url
            val hasLink = details != null || url != null
            text = if (!hasLink) {
                item.text
            } else {
                SpannableStringBuilder(item.text).apply {
                    // Cada link numa linha própria: o do endereço (ex.: assinar o plano) e o de Detalhes.
                    fun link(label: String, action: () -> Unit) {
                        append("\n")
                        val start = length
                        append(label)
                        setSpan(
                            object : ClickableSpan() {
                                override fun onClick(widget: View) = action()

                                override fun updateDrawState(ds: TextPaint) {
                                    ds.isUnderlineText = true
                                    ds.color = getColor(R.color.accent_text)
                                }
                            },
                            start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    if (url != null) link(item.urlLabel ?: url) { openInfoUrl(url) }
                    if (details != null) link(getString(R.string.chat_error_details)) { showDetails(R.string.chat_error_details, details) }
                }
            }
            movementMethod = if (hasLink) LinkMovementMethod.getInstance() else null
            gravity = Gravity.CENTER
            textSize = 13f * chatScale
            setTextColor(getColor(if (item.isError) R.color.setup_error_text else R.color.setup_muted_text))
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
        // Tamanho da cerca que abriu o bloco: ````md com ``` dentro só fecha com 4 crases ou mais.
        var fence = 0
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
            val ticks = line.trimStart().takeWhile { it == '`' }.length
            // Dentro do bloco, só fecha uma cerca do mesmo tamanho ou maior, sem nada depois.
            val closes = inCode && ticks >= fence && line.trim().length == ticks
            if (if (inCode) closes else ticks >= 3) {
                if (inCode) {
                    parts += Part(buffer.toString(), isCode = true, language = language)
                } else if (buffer.isNotBlank()) {
                    parts += Part(buffer.toString(), isCode = false)
                }
                buffer.clear()
                language = if (inCode) "" else line.trimStart().drop(ticks).trim()
                fence = if (inCode) 0 else ticks
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
        /** Folga, em pixels, para dar a lista como no fim mesmo com arredondamento da rolagem. */
        const val END_SLACK_PX = 4
        /** Duração, em ms, da rolagem suave até o fim do último item. */
        const val JUMP_SCROLL_MS = 250
        /** Intervalo do relógio da faixa de status (só reescreve o texto dela; ver [statusClock]). */
        const val STATUS_CLOCK_MS = 1000L
        /** Janela do segundo toque do Permitir em alto risco. */
        const val CONFIRM_PERMISSION_MS = 10_000L
        /** Sem mensagem do agente há este tempo, a ação em andamento também mostra "sem eventos há…". */
        const val SILENT_HINT_MS = 30_000L
        /** Passos no máximo para chegar ao fim do último item (cada um anda quase uma tela). */
        const val END_MAX_STEPS = 100
        /** Tamanho dos atalhos acima da caixa (sp), antes da escala. */
        const val CHIP_TEXT_SP = 14f
        /** Abaixo desta altura da janela (teclado aberto, paisagem) os atalhos somem e a faixa de permissão encolhe. */
        const val COMPACT_HEIGHT_DP = 420
        /** Modo Detalhado: linhas do começo da saída de cada ação, embaixo da linha dela. */
        const val TOOL_EXCERPT_LINES = 6
        /** A partir deste percentual do contexto usado, o chip de contexto fica em destaque (e o painel oferece compactar). */
        const val COMPACT_PERCENT = 80
        /** Comandos digitados na caixa que começam uma conversa nova (ver [send]). */
        private val NEW_CONVERSATION_COMMANDS = setOf("/new", "/clear", "/reset")
        /** Comandos do agente que o app já atende (ou oferece como os seus): não aparecem duplicados no popup. */
        private val APP_COMMAND_NAMES = setOf("new", "clear", "reset", "resume", "compact")
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
        /** Tamanho máximo do pedido lido no anúncio do leitor de tela. */
        private const val ANNOUNCE_MAX = 120

        /**
         * Anexos ainda não enviados de cada trabalho (pela chatKey), guardados quando a tela salva o estado
         * e devolvidos quando ela é recriada. Só a thread principal mexe.
         */
        private val keptAttachments = HashMap<String, List<Attachment>>()
        private val MODELS = arrayOf("", "opus", "sonnet", "haiku")
        private val EFFORTS = arrayOf("", "low", "medium", "high", "xhigh", "max")
    }
}

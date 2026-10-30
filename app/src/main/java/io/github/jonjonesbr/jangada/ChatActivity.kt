package io.github.jonjonesbr.jangada

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
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
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.TypedValue
import android.view.Gravity
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
import io.github.jonjonesbr.jangada.chat.Markdown
import io.github.jonjonesbr.jangada.chat.PermissionOption
import io.github.jonjonesbr.jangada.chat.SlashCommand
import io.github.jonjonesbr.jangada.chat.ToolText
import io.github.jonjonesbr.jangada.chat.friendlyModel
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import java.io.File
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
    /** Um pedido de abrir o chat (feito ao ligar no serviço) espera o adaptador ficar pronto. */
    private var opening = false
    private var engine: AgentChat? = null
    private var enterAt = -1
    private val pendingAttachments = mutableListOf<Attachment>()
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
        attachmentsRow = findViewById(R.id.chat_attachments)
        prepareActions = findViewById(R.id.chat_prepare_actions)
        sendButton = findViewById(R.id.chat_send)
        list.adapter = adapter
        findViewById<TextView>(R.id.chat_title).text = agentName
        empty.text = getString(R.string.chat_empty, agentName)
        input.hint = getString(R.string.chat_hint_agent, agentName)

        findViewById<View>(R.id.chat_home).setOnClickListener { goHome() }
        sendButton.setOnClickListener { send() }
        findViewById<View>(R.id.chat_attach).setOnClickListener { pickAttachment() }
        findViewById<View>(R.id.chat_stop).setOnClickListener { engine?.interrupt() }
        findViewById<View>(R.id.chat_terminal).setOnClickListener { switchToTerminal() }
        findViewById<View>(R.id.chat_new).setOnClickListener { newConversation() }
        findViewById<View>(R.id.chat_history).setOnClickListener { showHistory() }
        continueChip.setOnClickListener { engine?.sendMessage(getString(R.string.chat_continue_prompt)) }
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
        setIntent(intent)
        takeShared(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        service?.chatScreenVisible = true
        // Ao voltar com "/..." na caixa, as sugestões reaparecem (depois da janela estar pronta).
        input.post { updateCommandSuggestions() }
    }

    override fun onPause() {
        resumed = false
        service?.chatScreenVisible = false
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
            if (resumed) svc.chatScreenVisible = true
            // Um pedido anterior (de antes de a tela parar e voltar) ainda espera o adaptador: ele abre o chat.
            if (opening) return
            val resume = intent.getStringExtra(EXTRA_RESUME)
            val fresh = intent.getBooleanExtra(EXTRA_NEW, false)
            intent.removeExtra(EXTRA_RESUME)
            intent.removeExtra(EXTRA_NEW)
            val current = svc.chat?.takeIf { svc.chatAgentId == agentId }
            when {
                resume != null -> openWhenReady { attach(svc.openChat(agentId, resume = resume, restart = true)) }
                fresh -> openWhenReady { attach(svc.openChat(agentId, restart = true)) }
                current != null -> attach(current)
                else -> openWhenReady {
                    // No Claude, continua a conversa mais recente (se houver; o chat confere fora da thread principal).
                    // Com o terminal do Claude aberto, ele pode estar gravando essa mesma conversa: abre uma nova.
                    val terminalBusy = isClaude && svc.hasClaudeTerminal()
                    if (terminalBusy) Toast.makeText(this@ChatActivity, R.string.chat_terminal_busy_new, Toast.LENGTH_LONG).show()
                    attach(svc.openChat(agentId, continueLast = isClaude && !terminalBusy))
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
        // Parada, a tela não se registra no chat (o onStop já desfez isso); ao voltar, o onStart a liga de novo.
        if (started) chat.onChanged = { refresh() }
        refresh()
        input.requestFocus()
    }

    private fun refresh() {
        val chat = engine ?: return
        // A lista mostra uma cópia; assim ela nunca vê a conversa mudar
        // "por baixo" entre um redesenho e outro (o que derrubava o app).
        adapter.snapshot = ArrayList(chat.items)
        adapter.notifyDataSetChanged()
        val isEmpty = chat.items.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
        status.visibility = if (chat.busy) View.VISIBLE else View.GONE
        continueChip.visibility = if (chat.interrupted && !chat.busy) View.VISIBLE else View.GONE
        subtitle.text = if (isClaude) {
            val effort = settings.chatEffort.takeIf { it.isNotEmpty() }?.let { " · " + effortLabel(it) } ?: ""
            (friendlyModel(chat.model) ?: getString(R.string.chat_model_default)) + effort + " ▾"
        } else {
            val acp = chat as? AcpChat
            val options = acp?.configOptions.orEmpty()
            val effort = options.firstOrNull { it.category == "thought_level" }?.let { " · " + it.currentName } ?: ""
            (chat.model ?: "~/${LinuxEnv.WORKSPACE}") + effort + if (options.isNotEmpty()) " ▾" else ""
        }
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
            val chat = engine?.takeIf { it.running }
                ?: svc.openChat(agentId, resume = engine?.sessionId, restart = true).also { attach(it) }
            deliver(chat)
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
        svc.closeChat {
            if (!isDestroyed) attach(svc.openChat(agentId, restart = true))
        }
    }

    /** Conversas anteriores: toque para retomar, 🗑 para apagar (a aberta já vira uma nova). */
    private fun showHistory() {
        val chat = engine
        if (isClaude) {
            thread(name = "chat-history") {
                val found = ChatHistory.list(linux)
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
        val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        var dialog: AlertDialog? = null
        val historyAdapter = object : BaseAdapter() {
            override fun getCount() = conversations.size
            override fun getItem(position: Int): Any = conversations[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val conversation = conversations[position]
                val isCurrent = conversation.id == engine?.sessionId
                val row = LinearLayout(this@ChatActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(20), dp(10), dp(8), dp(10))
                }
                row.addView(TextView(this@ChatActivity).apply {
                    val date = if (conversation.updatedAt > 0) "\n" + format.format(java.util.Date(conversation.updatedAt)) else ""
                    text = (if (isCurrent) "● " else "") + conversation.title + date
                    textSize = 15f
                    maxLines = 3
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        dialog?.dismiss()
                        if (!isCurrent) svc.closeChat {
                            if (!isDestroyed) attach(svc.openChat(agentId, resume = conversation.id, restart = true))
                        }
                    }
                })
                if (canDelete) row.addView(TextView(this@ChatActivity).apply {
                    text = "🗑"
                    textSize = 20f
                    contentDescription = getString(R.string.chat_delete)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener {
                        confirmDelete(conversation, isCurrent) {
                            conversations.remove(conversation)
                            notifyDataSetChanged()
                            if (conversations.isEmpty()) dialog?.dismiss()
                        }
                    }
                })
                return row
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.chat_history)
            .setView(ListView(this).apply { adapter = historyAdapter })
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
                    if (isCurrent && !isDestroyed) attach(svc.openChat(agentId, restart = true))
                    done()
                }
                if (isClaude) {
                    // Fecha o chat primeiro (o Claude para de gravar); só então apaga os arquivos.
                    if (isCurrent) {
                        svc.closeChat { deleteClaudeFiles(conversation.id, reopenAndDone) }
                    } else {
                        deleteClaudeFiles(conversation.id, done)
                    }
                } else {
                    // ACP: o agente apaga pela própria conexão, então apaga antes de fechar a conversa.
                    val acp = engine as? AcpChat
                    val afterDelete = { if (isCurrent) svc.closeChat { reopenAndDone() } else done() }
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
        thread(name = "chat-delete") {
            // Um erro aqui (arquivo preso, por exemplo) não pode derrubar o app.
            runCatching { ChatHistory.delete(linux, id) }
            runOnUiThread { if (!isDestroyed) then() }
        }
    }

    /**
     * Ajustes da conversa (toque no nome do modelo): modelo e esforço do
     * Claude; nos agentes ACP, os ajustes que cada um oferece (modelo,
     * nível de raciocínio, modo...).
     */
    private fun showChatSettings() {
        if (isClaude) {
            val rows = arrayOf(
                getString(R.string.chat_setting_model) + ": " + modelLabel(settings.chatModel),
                getString(R.string.chat_setting_effort) + ": " + effortLabel(settings.chatEffort),
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.chat_settings_title)
                .setItems(rows) { _, which -> if (which == 0) chooseClaude(MODELS, settings.chatModel, ::modelLabel) { settings.chatModel = it } else chooseClaude(EFFORTS, settings.chatEffort, ::effortLabel) { settings.chatEffort = it } }
                .show()
            return
        }
        val acp = engine as? AcpChat
        val options = acp?.configOptions.orEmpty()
        if (acp == null || options.isEmpty()) {
            Toast.makeText(this, getString(R.string.chat_settings_unsupported, agentName), Toast.LENGTH_LONG).show()
            return
        }
        val rows = options.map { "${it.name}: ${it.currentName}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_settings_title)
            .setItems(rows) { _, which ->
                val option = options[which]
                val labels = option.choices.map { it.second }.toTypedArray()
                val current = option.choices.indexOfFirst { it.first == option.current }
                AlertDialog.Builder(this)
                    .setTitle(option.name)
                    .setSingleChoiceItems(labels, current) { dialog, choice ->
                        dialog.dismiss()
                        acp.setOption(option, option.choices[choice].first)
                    }
                    .show()
            }
            .show()
    }

    /** Escolha de modelo/esforço do Claude: reabre a mesma conversa com o novo ajuste. */
    private fun chooseClaude(values: Array<String>, current: String, label: (String) -> String, save: (String) -> Unit) {
        val labels = values.map(label).toTypedArray()
        AlertDialog.Builder(this)
            .setSingleChoiceItems(labels, values.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                if (values[which] == current) return@setSingleChoiceItems
                save(values[which])
                val svc = service ?: return@setSingleChoiceItems
                val id = engine?.sessionId
                svc.closeChat {
                    if (!isDestroyed) attach(svc.openChat(agentId, resume = id, restart = true))
                }
            }
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
            script = recipe?.launchScript() ?: return
            Toast.makeText(this, getString(R.string.chat_terminal_note, agentName), Toast.LENGTH_LONG).show()
        }
        openTerminal(svc, script, conversation = if (isClaude) id else null)
    }

    /** Comando que abre o Claude Code na pasta de trabalho; [args] vai depois do comando. */
    private fun claudeLaunchScript(args: String) =
        "mkdir -p ~/${LinuxEnv.WORKSPACE} && cd ~/${LinuxEnv.WORKSPACE} && claude$args"

    /** Fecha o chat e abre a tela de terminal com o [script]; esta tela termina em seguida. */
    private fun openTerminal(svc: TerminalService, script: String, conversation: String? = null, login: Boolean = false) {
        svc.closeChat {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TITLE, agentName)
                    .putExtra(MainActivity.EXTRA_AGENT, agentId)
                    .putExtra(MainActivity.EXTRA_SCRIPT, script)
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
            else -> recipe?.launchScript()
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

        override fun getCount() = snapshot.size
        override fun getItem(position: Int): Any = snapshot[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = false

        /** Respostas já separadas em partes e formatadas, por item (cache do adaptador). */
        private val answers = WeakHashMap<ChatItem.Assistant, Rendered>()

        /** Um tipo de linha para cada tipo de item (o Android reaproveita só dentro do mesmo tipo). */
        override fun getViewTypeCount() = 7

        override fun getItemViewType(position: Int): Int = when (snapshot[position]) {
            is ChatItem.User -> 0
            is ChatItem.Assistant -> 1
            is ChatItem.Tool -> 2
            is ChatItem.Permission -> 3
            is ChatItem.Plan -> 4
            is ChatItem.Login -> 5
            is ChatItem.Info -> 6
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
                },
            )
            return row
        }

        private fun userBubble(item: ChatItem.User, old: TextView?) = (old ?: TextView(this@ChatActivity)).apply {
            val files = item.attachments.joinToString("") { "\n📎 $it" }
            text = item.text + files
            setTextColor(getColor(R.color.key_text))
            textSize = 16f
            setBackgroundResource(R.drawable.bubble_user)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END; leftMargin = dp(40) }
            setOnLongClickListener { copy(item.text); true }
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
            textSize = 16f
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
                textSize = 12f
                setTextColor(getColor(R.color.setup_muted))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            header.addView(TextView(this@ChatActivity).apply {
                setText(R.string.chat_copy_code)
                textSize = 13f
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
                    textSize = 13f
                    setTextColor(getColor(R.color.setup_text))
                    setHorizontallyScrolling(true)
                    setPadding(dp(12), 0, dp(12), dp(10))
                })
            })
            return block
        }

        private fun toolLine(item: ChatItem.Tool, old: TextView?) = (old ?: TextView(this@ChatActivity)).apply {
            val mark = when {
                !item.done -> "…"
                item.failed -> "✕"
                else -> "✓"
            }
            text = "$mark ${ToolText.label(this@ChatActivity, item.name)}  ${item.summary}"
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(getColor(if (item.failed) R.color.setup_error else R.color.setup_muted))
            setPadding(dp(4), dp(2), dp(4), dp(2))
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
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
            })
            if (item.summary.isNotEmpty()) {
                card.addView(TextView(this@ChatActivity).apply {
                    text = item.summary
                    typeface = Typeface.MONOSPACE
                    textSize = 12f
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
                    textSize = 12f
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
                    textSize = 13f
                    setPadding(0, dp(4), 0, 0)
                })
            }
            return card
        }

        /** Trecho de edição (linhas "- " em vermelho e "+ " em verde) com o fundo dos blocos de código. */
        private fun diffView(diff: String) = TextView(this@ChatActivity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
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
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            val viaBrowser = item.options.any { it.kind == AuthOption.Kind.AGENT }
            card.addView(TextView(this@ChatActivity).apply {
                text = getString(if (viaBrowser) R.string.login_browser_hint else R.string.login_screen_hint)
                setTextColor(getColor(R.color.setup_muted))
                textSize = 14f
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(6), 0, dp(8))
            })
            when (item.state) {
                ChatItem.Login.State.WORKING -> {
                    card.addView(TextView(this@ChatActivity).apply {
                        setText(R.string.login_waiting)
                        setTextColor(getColor(R.color.accent_text))
                        textSize = 14f
                    })
                    card.addView(
                        Button(this@ChatActivity, null, android.R.attr.borderlessButtonStyle).apply {
                            setText(R.string.chat_login_cancel)
                            isAllCaps = false
                            setTextColor(getColor(R.color.accent_text))
                            setOnClickListener { (engine as? AcpChat)?.cancelLogin(item) }
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    )
                }
                ChatItem.Login.State.DONE -> card.addView(TextView(this@ChatActivity).apply {
                    setText(R.string.login_done)
                    setTextColor(getColor(R.color.setup_done))
                    textSize = 14f
                })
                else -> {
                    item.message?.let { message ->
                        card.addView(TextView(this@ChatActivity).apply {
                            text = message
                            setTextColor(getColor(R.color.setup_error))
                            textSize = 13f
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
            textSize = 14f
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
            textSize = 13f
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
        const val EXTRA_AGENT_ID = "agent_id"
        const val EXTRA_RESUME = "resume"
        const val EXTRA_NEW = "new"
        /** Arquivos recebidos pelo "Compartilhar" (ArrayList<Uri>). */
        const val EXTRA_SHARED_URIS = "shared_uris"
        /** Texto recebido pelo "Compartilhar" (vai para o campo de mensagem, sem enviar). */
        const val EXTRA_SHARED_TEXT = "shared_text"
        private val MODELS = arrayOf("", "opus", "sonnet", "haiku")
        private val EFFORTS = arrayOf("", "low", "medium", "high", "xhigh", "max")
    }
}

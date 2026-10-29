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
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.TextWatcher
import android.text.util.Linkify
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import io.github.jonjonesbr.jangada.agents.AgentCatalog
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.chat.AcpChat
import io.github.jonjonesbr.jangada.chat.AgentChat
import io.github.jonjonesbr.jangada.chat.Attachment
import io.github.jonjonesbr.jangada.chat.Attachments
import io.github.jonjonesbr.jangada.chat.ChatHistory
import io.github.jonjonesbr.jangada.chat.ChatItem
import io.github.jonjonesbr.jangada.chat.Markdown
import io.github.jonjonesbr.jangada.chat.PermissionOption
import io.github.jonjonesbr.jangada.chat.ToolText
import io.github.jonjonesbr.jangada.chat.friendlyModel
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import java.io.File
import kotlin.concurrent.thread

/**
 * Chat com um agente (Modo Simples): balões, permissões com botões,
 * anexos e a troca para a tela de terminal pelo botão do topo.
 * O Claude Code usa o protocolo dele; os outros agentes, o ACP.
 */
class ChatActivity : ComponentActivity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var status: View
    private lateinit var input: EditText
    private lateinit var subtitle: TextView
    private lateinit var continueChip: TextView
    private lateinit var attachmentsRow: LinearLayout
    private lateinit var settings: AppSettings
    private lateinit var linux: LinuxEnv
    private lateinit var agentId: String
    private var recipe: AgentRecipe? = null
    private val adapter = ChatAdapter()

    private var service: TerminalService? = null
    private var engine: AgentChat? = null
    private var enterAt = -1
    private val pendingAttachments = mutableListOf<Attachment>()

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
        continueChip = findViewById(R.id.chat_continue)
        attachmentsRow = findViewById(R.id.chat_attachments)
        list.adapter = adapter
        findViewById<TextView>(R.id.chat_title).text = agentName
        empty.text = getString(R.string.chat_empty, agentName)
        input.hint = getString(R.string.chat_hint_agent, agentName)

        findViewById<View>(R.id.chat_home).setOnClickListener { goHome() }
        findViewById<View>(R.id.chat_send).setOnClickListener { send() }
        findViewById<View>(R.id.chat_attach).setOnClickListener { pickAttachment() }
        findViewById<View>(R.id.chat_stop).setOnClickListener { engine?.interrupt() }
        findViewById<View>(R.id.chat_terminal).setOnClickListener { switchToTerminal() }
        findViewById<View>(R.id.chat_new).setOnClickListener { newConversation() }
        findViewById<View>(R.id.chat_history).setOnClickListener { showHistory() }
        continueChip.setOnClickListener { engine?.sendMessage(getString(R.string.chat_continue_prompt)) }
        subtitle.setOnClickListener { showChatSettings() }
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
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, TerminalService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        engine?.onChanged = null
        service?.let { unbindService(connection) }
        service = null
        super.onStop()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as TerminalService.LocalBinder).service
            service = svc
            val resume = intent.getStringExtra(EXTRA_RESUME)
            val fresh = intent.getBooleanExtra(EXTRA_NEW, false)
            intent.removeExtra(EXTRA_RESUME)
            intent.removeExtra(EXTRA_NEW)
            val current = svc.chat?.takeIf { svc.chatAgentId == agentId }
            when {
                resume != null -> ensureAdapter { attach(svc.openChat(agentId, resume = resume, restart = true)) }
                fresh -> ensureAdapter { attach(svc.openChat(agentId, restart = true)) }
                current != null -> attach(current)
                else -> ensureAdapter {
                    attach(svc.openChat(agentId, continueLast = isClaude && hasPreviousClaudeConversation()))
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /**
     * Alguns agentes falam ACP por um adaptador separado (Codex, Pi). Na
     * primeira vez, ele é instalado aqui mesmo, com aviso na tela.
     */
    private fun ensureAdapter(then: () -> Unit) {
        val spec = recipe?.acp
        val bin = spec?.bin
        val script = spec?.installScript()
        if (isClaude || bin == null || script == null || File(linux.rootfs, "usr/local/bin/$bin").exists()) {
            then()
            return
        }
        empty.visibility = View.VISIBLE
        list.visibility = View.GONE
        empty.text = getString(R.string.chat_preparing, agentName)
        thread(name = "acp-adapter") {
            val log = StringBuilder()
            val code = runCatching { linux.runBash(script) { line -> log.append(line).append('\n') } }.getOrDefault(-1)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (code == 0) {
                    empty.text = getString(R.string.chat_empty, agentName)
                    then()
                } else {
                    empty.text = getString(R.string.chat_prepare_failed, agentName) + "\n\n" + log.lines().takeLast(6).joinToString("\n")
                }
            }
        }
    }

    private fun attach(chat: AgentChat) {
        engine = chat
        chat.onChanged = { refresh() }
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
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        val svc = service ?: return
        val files = pendingAttachments.toList()
        input.setText("")
        pendingAttachments.clear()
        renderAttachments()
        val chat = engine?.takeIf { it.running }
            ?: svc.openChat(agentId, resume = engine?.sessionId, restart = true).also { attach(it) }
        chat.sendMessage(text, files)
    }

    // ---- Anexos ----

    private fun pickAttachment() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        runCatching { startActivityForResult(Intent.createChooser(intent, getString(R.string.chat_attach)), REQUEST_ATTACH) }
    }

    @Deprecated("Resposta do seletor de arquivos.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ATTACH || resultCode != RESULT_OK || data == null) return
        val uris = buildList {
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri) }
            if (isEmpty()) data.data?.let { add(it) }
        }
        thread(name = "attachments") {
            val prepared = uris.mapNotNull { uri -> runCatching { Attachments.prepare(this, linux, uri) }.getOrNull() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (prepared.size < uris.size) Toast.makeText(this, R.string.chat_attach_failed, Toast.LENGTH_SHORT).show()
                pendingAttachments += prepared
                renderAttachments()
            }
        }
    }

    private fun renderAttachments() {
        attachmentsRow.removeAllViews()
        (attachmentsRow.parent as View).visibility = if (pendingAttachments.isEmpty()) View.GONE else View.VISIBLE
        for (attachment in pendingAttachments.toList()) {
            attachmentsRow.addView(TextView(this).apply {
                text = (if (attachment.imageBase64 != null) "🖼 " else "📎 ") + attachment.name + "  ✕"
                setTextColor(getColor(R.color.setup_text))
                textSize = 13f
                maxLines = 1
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
            showHistoryDialog(ChatHistory.list(linux), canDelete = true)
        } else if (chat is AcpChat && chat.supportsHistory) {
            chat.listSessions { sessions -> if (!isDestroyed) showHistoryDialog(sessions, chat.canDelete) }
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
                val acp = engine as? AcpChat
                val remove = { then: () -> Unit ->
                    if (isClaude) {
                        ChatHistory.delete(linux, conversation.id)
                        then()
                    } else {
                        acp?.deleteSession(conversation.id, then) ?: then()
                    }
                }
                if (isCurrent) {
                    remove {
                        svc.closeChat {
                            if (!isDestroyed) attach(svc.openChat(agentId, restart = true))
                            done()
                        }
                    }
                } else {
                    remove(done)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            else -> R.string.chat_model_default
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
                wasBusy -> "--resume $id " + AgentRecipe.q(getString(R.string.chat_continue_prompt))
                else -> "--resume $id"
            }
            val model = settings.chatModel.takeIf { it.isNotEmpty() }?.let { " --model $it" } ?: ""
            val effort = settings.chatEffort.takeIf { it.isNotEmpty() }?.let { " --effort $it" } ?: ""
            script = "mkdir -p ~/${LinuxEnv.WORKSPACE} && cd ~/${LinuxEnv.WORKSPACE} && claude$model$effort $resume"
        } else {
            script = recipe?.launchScript() ?: return
            Toast.makeText(this, getString(R.string.chat_terminal_note, agentName), Toast.LENGTH_LONG).show()
        }
        svc.closeChat {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TITLE, agentName)
                    .putExtra(MainActivity.EXTRA_AGENT, agentId)
                    .putExtra(MainActivity.EXTRA_SCRIPT, script)
                    .apply { if (isClaude && id != null) putExtra(MainActivity.EXTRA_CONVERSATION, id) },
            )
            finish()
        }
    }

    private fun hasPreviousClaudeConversation(): Boolean = ChatHistory.list(linux, limit = 1).isNotEmpty()

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

    private fun optionLabel(option: PermissionOption): String = getString(
        when (option.kind) {
            PermissionOption.Kind.ALLOW_ONCE -> R.string.chat_allow
            PermissionOption.Kind.ALLOW_ALWAYS -> R.string.chat_allow_always
            PermissionOption.Kind.REJECT_ONCE -> R.string.chat_deny
            PermissionOption.Kind.REJECT_ALWAYS -> R.string.chat_deny_always
        },
    )

    /** Monta cada item da conversa (sem XML: são poucos tipos e bem simples). */
    private inner class ChatAdapter : BaseAdapter() {
        var snapshot: List<ChatItem> = emptyList()

        override fun getCount() = snapshot.size
        override fun getItem(position: Int): Any = snapshot[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            when (val item = snapshot[position]) {
                is ChatItem.User -> row.addView(userBubble(item))
                is ChatItem.Assistant -> row.addView(assistantView(item))
                is ChatItem.Tool -> row.addView(toolLine(item))
                is ChatItem.Permission -> row.addView(permissionCard(item))
                is ChatItem.Plan -> row.addView(planView(item))
                is ChatItem.Info -> row.addView(infoText(item))
            }
            return row
        }

        private fun userBubble(item: ChatItem.User) = TextView(this@ChatActivity).apply {
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
        private fun assistantView(item: ChatItem.Assistant): View {
            val box = LinearLayout(this@ChatActivity).apply { orientation = LinearLayout.VERTICAL }
            val parts = splitCode(item.text)
            for ((index, part) in parts.withIndex()) {
                val last = index == parts.size - 1
                if (part.isCode) box.addView(codeBlock(part)) else box.addView(prose(part.text, item, last))
            }
            box.setOnLongClickListener { copy(item.text); true }
            return box
        }

        private fun prose(text: String, item: ChatItem.Assistant, last: Boolean) = TextView(this@ChatActivity).apply {
            val body = Markdown.render(text.trim('\n'), getColor(R.color.code_bg))
            this.text = if (item.streaming && last) SpannableStringBuilder(body).append(" ▍") else body
            setTextColor(getColor(R.color.setup_text))
            textSize = 16f
            setLineSpacing(0f, 1.2f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            if (!item.streaming) Linkify.addLinks(this, Linkify.WEB_URLS)
            setLinkTextColor(getColor(R.color.key_active))
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
                setTextColor(getColor(R.color.key_active))
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

        private fun toolLine(item: ChatItem.Tool) = TextView(this@ChatActivity).apply {
            val mark = when {
                !item.done -> "…"
                item.failed -> "✕"
                else -> "✓"
            }
            text = "$mark ${ToolText.label(item.name)}  ${item.summary}"
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
                })
            }
            val chosen = item.chosen
            if (chosen == null) {
                val buttons = LinearLayout(this@ChatActivity).apply { orientation = LinearLayout.VERTICAL }
                // Permitir primeiro e em destaque; as outras opções como botões de texto.
                for (option in item.options.sortedBy { it.kind.ordinal }) {
                    val primary = option.kind == PermissionOption.Kind.ALLOW_ONCE
                    val button = if (primary) Button(this@ChatActivity) else Button(this@ChatActivity, null, android.R.attr.borderlessButtonStyle)
                    button.text = optionLabel(option)
                    button.isAllCaps = false
                    if (primary) {
                        button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.key_active))
                        button.setTextColor(getColor(R.color.setup_on_accent))
                    } else {
                        button.setTextColor(getColor(if (option.allows) R.color.key_active else R.color.setup_error))
                    }
                    button.setOnClickListener { engine?.answer(item, option) }
                    buttons.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                card.addView(buttons)
            } else {
                card.addView(TextView(this@ChatActivity).apply {
                    text = optionLabel(chosen)
                    setTextColor(getColor(if (chosen.allows) R.color.setup_done else R.color.setup_error))
                    textSize = 13f
                    setPadding(0, dp(4), 0, 0)
                })
            }
            return card
        }

        private fun planView(item: ChatItem.Plan) = TextView(this@ChatActivity).apply {
            text = getString(R.string.chat_plan) + "\n" + item.entries.joinToString("\n") { entry ->
                val mark = when (entry.status) { "completed" -> "☑"; "in_progress" -> "◐"; else -> "☐" }
                "$mark ${entry.text}"
            }
            textSize = 14f
            setTextColor(getColor(R.color.setup_muted))
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        private fun infoText(item: ChatItem.Info) = TextView(this@ChatActivity).apply {
            text = item.text
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(getColor(if (item.isError) R.color.setup_error else R.color.setup_muted))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnLongClickListener { copy(item.text); true }
        }
    }

    /** Pedaço de uma resposta: texto comum, bloco de código (```) ou tabela. */
    private class Part(val text: String, val isCode: Boolean, val language: String = "", val isTable: Boolean = false)

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
            parts += Part(formatTable(table.toString()), isCode = true, language = "", isTable = true)
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
            val line = (0 until columns).joinToString(" │ ") { c -> (row.getOrNull(c) ?: "").take(40).padEnd(widths[c]) }
            if (index == 0 && rows.size > 1) line + "\n" + widths.joinToString("─┼─") { "─".repeat(it) } else line
        }.joinToString("\n")
    }

    companion object {
        const val EXTRA_AGENT_ID = "agent_id"
        const val EXTRA_RESUME = "resume"
        const val EXTRA_NEW = "new"
        const val CLAUDE_AGENT_ID = TerminalService.CLAUDE
        private const val REQUEST_ATTACH = 7
        private val MODELS = arrayOf("", "opus", "sonnet", "haiku")
        private val EFFORTS = arrayOf("", "low", "medium", "high", "xhigh", "max")
    }
}

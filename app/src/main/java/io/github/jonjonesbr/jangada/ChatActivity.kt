package io.github.jonjonesbr.jangada

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.text.util.Linkify
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import io.github.jonjonesbr.jangada.chat.ChatEngine
import io.github.jonjonesbr.jangada.chat.ChatHistory
import io.github.jonjonesbr.jangada.chat.ChatItem
import io.github.jonjonesbr.jangada.chat.Markdown
import io.github.jonjonesbr.jangada.chat.ToolText
import io.github.jonjonesbr.jangada.chat.friendlyModel
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import java.io.File

/**
 * Chat com o Claude Code (Modo Simples): balões, permissões com botões
 * e a mesma conversa disponível no terminal pelo botão do topo.
 */
class ChatActivity : ComponentActivity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var status: View
    private lateinit var input: EditText
    private lateinit var subtitle: TextView
    private lateinit var settings: AppSettings
    private val adapter = ChatAdapter()

    private var service: TerminalService? = null
    private var engine: ChatEngine? = null
    private var enterAt = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        settings = AppSettings(this)

        list = findViewById(R.id.chat_list)
        empty = findViewById(R.id.chat_empty)
        status = findViewById(R.id.chat_status)
        input = findViewById(R.id.chat_input)
        subtitle = findViewById(R.id.chat_subtitle)
        list.adapter = adapter

        findViewById<View>(R.id.chat_home).setOnClickListener { goHome() }
        findViewById<View>(R.id.chat_send).setOnClickListener { send() }
        findViewById<View>(R.id.chat_stop).setOnClickListener { engine?.interrupt() }
        findViewById<View>(R.id.chat_terminal).setOnClickListener { switchToTerminal() }
        findViewById<View>(R.id.chat_new).setOnClickListener { newConversation() }
        findViewById<View>(R.id.chat_history).setOnClickListener { showHistory() }
        subtitle.setOnClickListener { chooseModel() }
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
            val continueLast = intent.getBooleanExtra(EXTRA_CONTINUE, hasPreviousConversation())
            intent.removeExtra(EXTRA_CONTINUE)
            attach(svc.chat ?: svc.openChat(continueLast = continueLast))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private fun attach(chat: ChatEngine) {
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
        val model = friendlyModel(chat.model) ?: getString(R.string.chat_model_default)
        subtitle.text = "$model ▾"
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val svc = service ?: return
        input.setText("")
        val chat = engine?.takeIf { it.running } ?: svc.openChat(continueLast = engine?.sessionId == null && hasPreviousConversation(), resume = engine?.sessionId).also { attach(it) }
        chat.sendMessage(text)
    }

    private fun newConversation() {
        val svc = service ?: return
        svc.closeChat {
            if (!isDestroyed) attach(svc.openChat(continueLast = false))
        }
    }

    /** Lista as conversas anteriores (as que o Claude Code guardou) para retomar uma. */
    private fun showHistory() {
        val svc = service ?: return
        val conversations = ChatHistory.list(LinuxEnv(this))
        if (conversations.isEmpty()) {
            Toast.makeText(this, R.string.chat_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val current = engine?.sessionId
        val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        val labels = conversations.map { conversation ->
            val mark = if (conversation.id == current) "● " else ""
            "$mark${conversation.title}\n${format.format(java.util.Date(conversation.updatedAt))}"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.chat_history)
            .setItems(labels) { _, which ->
                val chosen = conversations[which]
                if (chosen.id == current) return@setItems
                svc.closeChat {
                    if (!isDestroyed) attach(svc.openChat(continueLast = false, resume = chosen.id, restart = true))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Troca o modelo e continua a mesma conversa com ele. */
    private fun chooseModel() {
        val aliases = arrayOf("", "opus", "sonnet", "haiku")
        val labels = arrayOf(getString(R.string.chat_model_default), "Opus", "Sonnet", "Haiku")
        val current = aliases.indexOf(settings.chatModel).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.chat_model_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                if (aliases[which] == settings.chatModel) return@setSingleChoiceItems
                settings.chatModel = aliases[which]
                val svc = service ?: return@setSingleChoiceItems
                val id = engine?.sessionId
                svc.closeChat {
                    if (!isDestroyed) attach(svc.openChat(continueLast = id == null && hasPreviousConversation(), resume = id, restart = true))
                }
            }
            .show()
    }

    /**
     * Mesma conversa na tela de terminal. Espera o Claude do chat terminar
     * de sair antes de abrir o do terminal, para os dois não gravarem as
     * configurações ao mesmo tempo.
     */
    private fun switchToTerminal() {
        val svc = service ?: return
        val id = engine?.sessionId
        // Sem id (conversa nova, ainda sem mensagens), abre o Claude numa conversa nova.
        val resume = if (id != null) "--resume $id" else ""
        val model = settings.chatModel.takeIf { it.isNotEmpty() }?.let { " --model $it" } ?: ""
        svc.closeChat {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TITLE, getString(R.string.chat_title))
                    .putExtra(MainActivity.EXTRA_AGENT, CLAUDE_AGENT_ID)
                    .putExtra(
                        MainActivity.EXTRA_SCRIPT,
                        "mkdir -p ~/${LinuxEnv.WORKSPACE} && cd ~/${LinuxEnv.WORKSPACE} && claude$model $resume",
                    ),
            )
            finish()
        }
    }

    /** Existe conversa anterior do Claude Code na pasta de projetos? */
    private fun hasPreviousConversation(): Boolean {
        val dir = File(LinuxEnv(this).rootfs, "root/.claude/projects/-root-${LinuxEnv.WORKSPACE}")
        return dir.listFiles()?.any { it.name.endsWith(".jsonl") } == true
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Claude", text))
        Toast.makeText(this, R.string.chat_copied, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** Monta cada item da conversa (sem XML: são poucos tipos e bem simples). */
    private inner class ChatAdapter : BaseAdapter() {
        var snapshot: List<ChatItem> = emptyList()
        private val items get() = snapshot

        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val row = LinearLayout(this@ChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            when (item) {
                is ChatItem.User -> row.addView(userBubble(item.text))
                is ChatItem.Assistant -> row.addView(assistantText(item))
                is ChatItem.Tool -> row.addView(toolLine(item))
                is ChatItem.Permission -> row.addView(permissionCard(item))
                is ChatItem.Info -> row.addView(infoText(item))
            }
            return row
        }

        private fun userBubble(text: String) = TextView(this@ChatActivity).apply {
            this.text = text
            setTextColor(getColor(R.color.key_text))
            textSize = 16f
            setBackgroundResource(R.drawable.bubble_user)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = (resources.displayMetrics.widthPixels * 0.82).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.END; leftMargin = dp(40) }
            setOnLongClickListener { copy(text); true }
        }

        private fun assistantText(item: ChatItem.Assistant) = TextView(this@ChatActivity).apply {
            val body = Markdown.render(item.text, getColor(R.color.code_bg))
            text = if (item.streaming) android.text.SpannableStringBuilder(body).append(" ▍") else body
            setTextColor(getColor(R.color.setup_text))
            textSize = 16f
            setLineSpacing(0f, 1.2f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            if (!item.streaming) Linkify.addLinks(this, Linkify.WEB_URLS)
            setLinkTextColor(getColor(R.color.key_active))
            setOnLongClickListener { copy(item.text); true }
        }

        private fun toolLine(item: ChatItem.Tool) = TextView(this@ChatActivity).apply {
            val mark = when {
                !item.done -> "…"
                item.failed -> "✕"
                else -> "✓"
            }
            text = "$mark ${ToolText.label(item.name)}  ${item.summary}"
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
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
                text = getString(R.string.chat_wants, ToolText.label(item.toolName))
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
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.setup_muted))
                    setPadding(0, dp(6), 0, dp(4))
                })
            }
            val answered = item.allowed
            if (answered == null) {
                val buttons = LinearLayout(this@ChatActivity).apply { orientation = LinearLayout.HORIZONTAL }
                buttons.addView(Button(this@ChatActivity).apply {
                    setText(R.string.chat_allow)
                    isAllCaps = false
                    backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.key_active))
                    setTextColor(getColor(R.color.setup_on_accent))
                    setOnClickListener { engine?.answer(item, allow = true) }
                })
                buttons.addView(Button(this@ChatActivity, null, android.R.attr.borderlessButtonStyle).apply {
                    setText(R.string.chat_deny)
                    isAllCaps = false
                    setTextColor(getColor(R.color.setup_error))
                    setOnClickListener { engine?.answer(item, allow = false) }
                })
                card.addView(buttons)
            } else {
                card.addView(TextView(this@ChatActivity).apply {
                    setText(if (answered) R.string.chat_allowed else R.string.chat_denied)
                    setTextColor(getColor(if (answered) R.color.setup_done else R.color.setup_error))
                    textSize = 13f
                    setPadding(0, dp(4), 0, 0)
                })
            }
            return card
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

    companion object {
        const val EXTRA_CONTINUE = "continue"
        const val CLAUDE_AGENT_ID = "claude-code"
    }
}

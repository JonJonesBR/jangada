package io.github.jonjonesbr.jangada

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.Lifecycle
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.terminal.ExtraKeysBar
import io.github.jonjonesbr.jangada.terminal.JangadaViewClient
import io.github.jonjonesbr.jangada.terminal.Links
import io.github.jonjonesbr.jangada.terminal.TerminalFonts
import io.github.jonjonesbr.jangada.terminal.TerminalThemes

/**
 * A tela do terminal. As sessões moram no [TerminalService], então
 * continuam rodando quando a tela fecha; aqui só mostramos a atual.
 * Se o Linux ainda não está instalado, manda para a tela de preparação.
 */
class MainActivity : ComponentActivity() {

    lateinit var terminalView: TerminalView
        private set
    lateinit var extraKeys: ExtraKeysBar
        private set

    /** Faixa "já entrou? toque em Chat" que aparece ao voltar do login guiado. */
    private lateinit var loginBanner: View
    /** O login guiado pediu a faixa (até o usuário fechá-la). Só aparece com o botão Chat da aba atual. */
    private var loginBannerWanted = false
    private lateinit var composeBar: View
    private lateinit var composeText: EditText
    /** Texto começando com / ou !: sem corretor e enviado como digitação. */
    private var commandMode = false
    /** Enviando um comando tecla por tecla. */
    private var typing = false
    private var enterAt = -1
    private val appSettings by lazy { AppSettings(this) }
    private val agents by lazy { io.github.jonjonesbr.jangada.agents.AgentCatalog.load(this) }

    private var service: TerminalService? = null
    /** true desde o bindService até o unbindService (feitos em onStart e onStop). */
    private var bound = false
    private var terminalTypeface: Typeface = Typeface.MONOSPACE
    private var session: TerminalSession? = null
    private var screenVisible = false
    private var fontSizePx = 0
    private var minFontPx = 0
    private var maxFontPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val linux = LinuxEnv(this)
        if (linux.isDeviceSupported && !linux.isReady) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        // Atualiza o proot que veio com esta versão do app (só depois de instalar ou atualizar).
        if (linux.isReady) runCatching { linux.installBootstrapIfChanged() }

        setContentView(R.layout.activity_main)
        terminalView = findViewById(R.id.terminal_view)
        extraKeys = findViewById(R.id.extra_keys)

        minFontPx = sp(6f)
        maxFontPx = sp(32f)
        val savedFontPx = appSettings.fontSizePx
        fontSizePx = (if (savedFontPx > 0) savedFontPx else sp(12f)).coerceIn(minFontPx, maxFontPx)

        terminalView.setTerminalViewClient(JangadaViewClient(this))
        terminalView.setTextSize(fontSizePx) // precisa vir antes de setTypeface()
        terminalTypeface = TerminalFonts.load(this)
        terminalView.setTypeface(terminalTypeface)
        terminalView.keepScreenOn = appSettings.keepScreenOn
        if (!appSettings.showExtraKeys) extraKeys.visibility = View.GONE

        extraKeys.attach(
            terminalView,
            onPaste = ::pasteFromClipboard,
            onOpenLink = ::openLastLink,
            onKeyboard = ::showKeyboard,
            onCompose = { setComposeVisible(composeBar.visibility != View.VISIBLE, focus = true) },
        )

        // Caixa de texto: digita com corretor, voz e sem atraso; ➤ manda tudo de uma vez.
        composeBar = findViewById(R.id.compose_bar)
        composeText = findViewById(R.id.compose_text)
        findViewById<View>(R.id.compose_send).setOnClickListener { sendCompose() }
        composeText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                enterAt = if (count == 1 && before == 0 && s?.getOrNull(start) == '\n') start else -1
            }
            override fun afterTextChanged(s: Editable?) {
                val at = enterAt
                enterAt = -1
                // Opção "Enter envia": o Enter do teclado manda o texto em vez de pular linha.
                if (at >= 0 && s != null && s.length > 1 && appSettings.enterSends && at < s.length) {
                    s.delete(at, at + 1)
                    sendCompose()
                    return
                }
                val text = s?.toString().orEmpty()
                // Enter do teclado com a caixa vazia vai para o terminal (confirma
                // menus e listas dos agentes), em vez de pular linha na caixa.
                if (text == "\n") {
                    s?.clear()
                    session?.write("\r")
                    return
                }
                setCommandMode(text.startsWith("/") || text.startsWith("!"))
            }
        })
        // No modo comando a caixa tem uma linha só; o Enter do teclado envia.
        composeText.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (commandMode && (actionId == EditorInfo.IME_ACTION_SEND || enter)) {
                sendCompose()
                true
            } else {
                false
            }
        }
        setComposeVisible(appSettings.composeVisible, focus = false)

        // Sessão do Claude Code: botão para ver a mesma conversa no chat.
        findViewById<View>(R.id.terminal_chat).setOnClickListener { switchToChat() }

        loginBanner = findViewById(R.id.login_banner)
        findViewById<View>(R.id.login_banner_close).setOnClickListener {
            loginBannerWanted = false
            loginBanner.visibility = View.GONE
        }
        showLoginBanner()

        // Voltar (ou o botão Início) leva à tela inicial; as sessões continuam rodando.
        findViewById<View>(R.id.home_button).setOnClickListener { goHome() }
        onBackPressedDispatcher.addCallback(this) { goHome() }
    }

    /**
     * Fecha esta sessão e abre o chat do mesmo agente. Para o Claude Code,
     * continua a mesma conversa: a que foi usada nesta sessão; se ela era
     * nova e ficou sem mensagens, o chat também abre numa conversa nova.
     */
    private fun switchToChat() {
        loginBanner.visibility = View.GONE
        val svc = service ?: return
        val current = session ?: return
        val agent = svc.sessionAgent[current.mHandle] ?: return
        val intent = Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_AGENT_ID, agent)
        if (agent == TerminalService.CLAUDE) {
            val conversation = svc.claudeConversationOf(current.mHandle)
            if (conversation != null) intent.putExtra(ChatActivity.EXTRA_RESUME, conversation)
            else intent.putExtra(ChatActivity.EXTRA_NEW, true)
        } else {
            intent.putExtra(ChatActivity.EXTRA_NEW, true)
        }
        svc.closeSession(current.mHandle)
        startActivity(intent)
        finish()
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showLoginBanner()
        service?.let { handleIntent(it) }
    }

    /** Mostra a faixa de volta quando a tela abre vinda do login guiado. O pedido é consumido, como os outros extras. */
    private fun showLoginBanner() {
        if (!::loginBanner.isInitialized) return
        if (intent.getBooleanExtra(EXTRA_LOGIN, false)) {
            intent.removeExtra(EXTRA_LOGIN)
            loginBannerWanted = true
            updateLoginBanner()
        }
    }

    /** A faixa aparece só se foi pedida e a aba atual tem o botão Chat (some ao trocar para aba sem Chat). */
    private fun updateLoginBanner() {
        if (!::loginBanner.isInitialized) return
        loginBanner.visibility = if (loginBannerWanted && currentHasChat()) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        if (!::terminalView.isInitialized) return
        val intent = Intent(this, TerminalService::class.java)
        startService(intent)
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        screenVisible = true
        applyTerminalTheme()
    }

    /** Cores do terminal escolhidas nas configurações (vale para todas as abas). */
    private fun applyTerminalTheme() {
        if (!::terminalView.isInitialized) return
        TerminalThemes.apply(appSettings.terminalTheme)
        service?.sessions?.forEach { it.emulator?.mColors?.reset() }
        session?.emulator?.mColors?.reset()
        terminalView.invalidate()
    }

    override fun onPause() {
        screenVisible = false
        super.onPause()
    }

    override fun onStop() {
        service?.let {
            it.screenClient = null
            it.screenLinkOpener = null
            it.onExit = null
            it.removeSessionsListener(tabsListener)
        }
        service = null
        // Desfaz o bind sempre que foi feito, mesmo se a conexão ainda não chegou.
        if (bound) {
            bound = false
            unbindService(connection)
        }
        super.onStop()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as TerminalService.LocalBinder).service
            // A tela já saiu da frente antes da conexão: desfaz o bind e não registra nada.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (bound) {
                    bound = false
                    unbindService(this)
                }
                return
            }
            service = svc
            svc.screenClient = sessionClient
            svc.screenLinkOpener = { url -> if (screenVisible) { openUrl(url); true } else false }
            svc.onExit = { finishAndRemoveTask() }
            svc.addSessionsListener(tabsListener)
            handleIntent(svc)
            terminalView.post { if (composeBar.visibility == View.VISIBLE) focusCompose() else showKeyboard() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** Decide qual sessão mostrar a partir do pedido que abriu a tela. */
    private fun handleIntent(svc: TerminalService) {
        val intent = intent
        val script = intent.getStringExtra(EXTRA_SCRIPT)
        val handle = intent.getStringExtra(EXTRA_SESSION)
        val newShell = intent.getBooleanExtra(EXTRA_NEW_SHELL, false)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: TerminalService.DEFAULT_TITLE
        // Consome o pedido, para não repetir ao voltar para a tela.
        intent.removeExtra(EXTRA_SCRIPT)
        intent.removeExtra(EXTRA_SESSION)
        intent.removeExtra(EXTRA_NEW_SHELL)

        val agent = intent.getStringExtra(EXTRA_AGENT)
        val conversation = intent.getStringExtra(EXTRA_CONVERSATION)
        intent.removeExtra(EXTRA_AGENT)
        intent.removeExtra(EXTRA_CONVERSATION)
        val target = when {
            script != null -> svc.createSession(title, script).also { created ->
                if (agent != null) svc.sessionAgent[created.mHandle] = agent
                if (conversation != null) svc.sessionConversation[created.mHandle] = conversation
            }
            newShell -> svc.createSession()
            handle != null -> svc.session(handle)
            else -> null
        } ?: session?.takeIf { it in svc.sessions } ?: svc.sessions.lastOrNull() ?: svc.createSession()
        show(target)
    }

    private val tabsListener: () -> Unit = { renderTabs() }

    /**
     * Abas das sessões abertas. No Modo Pro aparecem sempre; no Simples,
     * só quando há mais de uma. Toque troca; segurar ou o ✕ fecha; ＋ abre outra.
     */
    private fun renderTabs() {
        val svc = service ?: return
        val scroll = findViewById<View>(R.id.terminal_tabs_scroll)
        val tabs = findViewById<LinearLayout>(R.id.terminal_tabs)
        val sessions = svc.sessions.toList()
        scroll.visibility = if (!appSettings.simpleMode || sessions.size > 1) View.VISIBLE else View.GONE
        tabs.removeAllViews()
        for (item in sessions) {
            val selected = item == session
            val name = item.mSessionName ?: TerminalService.DEFAULT_TITLE
            val color = getColor(if (selected) R.color.key_text else R.color.term_muted)
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44f)
                setBackgroundResource(if (selected) R.drawable.tab_bg_selected else R.drawable.tab_bg)
                isClickable = true
                setOnClickListener { show(item) }
                setOnLongClickListener { confirmClose(item); true }
            }
            tab.addView(TextView(this).apply {
                text = name
                textSize = 13f
                maxLines = 1
                setTextColor(color)
                setPadding(sp(12f), 0, sp(4f), 0)
            })
            // ✕ com área de toque de 44dp; pede a mesma confirmação do toque longo.
            tab.addView(TextView(this).apply {
                text = "✕"
                contentDescription = getString(R.string.tab_close, name)
                textSize = 13f
                gravity = Gravity.CENTER
                minWidth = dp(44f)
                minHeight = dp(44f)
                setTextColor(color)
                setOnClickListener { confirmClose(item) }
            })
            tabs.addView(tab, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply { marginEnd = sp(6f) })
        }
        tabs.addView(TextView(this).apply {
            text = "＋"
            contentDescription = getString(R.string.tab_new)
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(44f)
            minHeight = dp(44f)
            setTextColor(getColor(R.color.key_active))
            setPadding(sp(12f), sp(2f), sp(12f), sp(2f))
            setOnClickListener { show(svc.createSession()) }
        })
    }

    private fun confirmClose(item: TerminalSession) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.tab_close_title)
            .setMessage(item.mSessionName ?: TerminalService.DEFAULT_TITLE)
            .setPositiveButton(R.string.session_close) { _, _ ->
                val svc = service ?: return@setPositiveButton
                svc.closeSession(item.mHandle)
                if (item == session) {
                    val next = svc.sessions.lastOrNull()
                    if (next != null) show(next) else goHome()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun show(newSession: TerminalSession) {
        session = newSession
        TerminalThemes.apply(appSettings.terminalTheme)
        findViewById<TextView>(R.id.session_title_bar).text = newSession.mSessionName ?: TerminalService.DEFAULT_TITLE
        findViewById<View>(R.id.terminal_chat).visibility = if (currentHasChat()) View.VISIBLE else View.GONE
        updateLoginBanner()
        renderTabs()
        terminalView.attachSession(newSession)
        terminalView.onScreenUpdated()
    }

    /** A aba atual é de um agente com chat (então o botão Chat aparece). */
    private fun currentHasChat(): Boolean {
        val agent = session?.let { service?.sessionAgent?.get(it.mHandle) } ?: return false
        return agents.firstOrNull { it.id == agent }?.hasChat == true
    }

    /** Recria a sessão depois de um "exit" (Enter na tela de processo encerrado). */
    fun startSession() {
        val svc = service ?: return
        val old = session
        show(if (old != null) svc.replaceSession(old) else svc.createSession())
    }

    private fun setComposeVisible(visible: Boolean, focus: Boolean) {
        composeBar.visibility = if (visible) View.VISIBLE else View.GONE
        appSettings.composeVisible = visible
        if (!focus) return
        if (visible) focusCompose() else showKeyboard()
    }

    private fun focusCompose() {
        composeText.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(composeText, 0)
    }

    /**
     * Modo comando (texto começa com / ou !): tira corretor, maiúscula
     * automática e sugestões, e usa fonte de terminal. Volta ao normal
     * quando o texto deixa de começar assim (ou é enviado).
     */
    private fun setCommandMode(enabled: Boolean) {
        if (enabled == commandMode) return
        commandMode = enabled
        val cursor = composeText.selectionEnd
        if (enabled) {
            composeText.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            composeText.imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            composeText.typeface = terminalTypeface
        } else {
            composeText.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            composeText.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            composeText.typeface = Typeface.DEFAULT
            composeText.maxLines = 5
        }
        composeText.setSelection(cursor.coerceIn(0, composeText.length()))
    }

    /**
     * Envia o texto da caixa e aperta Enter.
     * - Mensagem normal: vai de uma vez, como colagem (sem atraso).
     * - Comando (/ ou !): vai tecla por tecla, como se fosse digitado,
     *   para o agente abrir o menu de comandos ou o modo shell.
     * - Caixa vazia: só Enter (útil para confirmar menus).
     */
    private fun sendCompose() {
        val current = session ?: return
        if (typing) return
        val text = composeText.text.toString()
        val asCommand = commandMode
        composeText.setText("")
        when {
            text.isEmpty() -> current.write("\r")
            asCommand -> typeSlowly(current, text) { current.write("\r") }
            else -> {
                current.emulator?.paste(text)
                terminalView.postDelayed({ current.write("\r") }, PASTE_ENTER_DELAY_MS)
            }
        }
    }

    private fun typeSlowly(target: TerminalSession, text: String, then: () -> Unit) {
        val codePoints = text.codePoints().toArray()
        var index = 0
        typing = true
        val step = object : Runnable {
            override fun run() {
                if (index < codePoints.size) {
                    target.write(String(Character.toChars(codePoints[index])))
                    index++
                    terminalView.postDelayed(this, KEY_DELAY_MS)
                } else {
                    terminalView.postDelayed({
                        typing = false
                        then()
                    }, COMMAND_ENTER_DELAY_MS)
                }
            }
        }
        terminalView.post(step)
    }

    fun showKeyboard() {
        terminalView.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(terminalView, 0)
    }

    fun changeFontSize(increase: Boolean) {
        val step = sp(1f).coerceAtLeast(1)
        fontSizePx = (fontSizePx + if (increase) step else -step).coerceIn(minFontPx, maxFontPx)
        terminalView.setTextSize(fontSizePx)
        appSettings.fontSizePx = fontSizePx
    }

    fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.cannot_open_link)
        }
    }

    /** Abre o link mais recente da tela — útil no login dos agentes (OAuth). */
    fun openLastLink() {
        val url = session?.emulator?.let { Links.lastOnScreen(it) }
        if (url == null) toast(R.string.no_link_found) else openUrl(url)
    }

    fun pasteFromClipboard() {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()
        if (!text.isNullOrEmpty()) session?.emulator?.paste(text)
    }

    private fun copyToClipboard(text: String?) {
        if (text.isNullOrEmpty()) return
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private fun sp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics).toInt()

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (changedSession == session) terminalView.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {}

        // O próprio emulador escreve "[Process completed - press Enter]";
        // o Enter é tratado em JangadaViewClient e recria a sessão.
        override fun onSessionFinished(finishedSession: TerminalSession) {}

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = copyToClipboard(text)

        override fun onPasteTextFromClipboard(session: TerminalSession?) = pasteFromClipboard()

        override fun onBell(session: TerminalSession) {
            terminalView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        override fun onColorsChanged(session: TerminalSession) = terminalView.invalidate()

        override fun onTerminalCursorStateChange(state: Boolean) {}

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

        override fun getTerminalCursorStyle(): Int? = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

        override fun logError(tag: String?, message: String?) { Log.e(tag, message.orEmpty()) }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message.orEmpty()) }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag, message.orEmpty()) }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag, message.orEmpty()) }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message.orEmpty()) }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag, message.orEmpty(), e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
    }

    companion object {
        private const val PASTE_ENTER_DELAY_MS = 80L
        private const val KEY_DELAY_MS = 12L
        private const val COMMAND_ENTER_DELAY_MS = 150L
        const val EXTRA_TITLE = "title"
        const val EXTRA_SCRIPT = "script"
        const val EXTRA_SESSION = "session"
        const val EXTRA_NEW_SHELL = "new_shell"
        const val EXTRA_AGENT = "agent"
        const val EXTRA_CONVERSATION = "conversation"
        const val EXTRA_LOGIN = "io.github.jonjonesbr.jangada.LOGIN"
    }
}

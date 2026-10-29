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
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.terminal.ExtraKeysBar
import io.github.jonjonesbr.jangada.terminal.JangadaViewClient
import io.github.jonjonesbr.jangada.terminal.Links
import io.github.jonjonesbr.jangada.terminal.TerminalFonts

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

    private lateinit var composeBar: View
    private lateinit var composeText: EditText
    /** Texto começando com / ou !: sem corretor e enviado como digitação. */
    private var commandMode = false
    /** Enviando um comando tecla por tecla. */
    private var typing = false

    private var service: TerminalService? = null
    private var terminalTypeface: Typeface = Typeface.MONOSPACE
    private var session: TerminalSession? = null
    private var screenVisible = false
    private var fontSizePx = 0
    private var minFontPx = 0
    private var maxFontPx = 0

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val linux = LinuxEnv(this)
        if (linux.isDeviceSupported && !linux.isReady) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        // Atualiza o proot que veio com esta versão do app.
        if (linux.isReady) runCatching { linux.installBootstrap() }

        setContentView(R.layout.activity_main)
        terminalView = findViewById(R.id.terminal_view)
        extraKeys = findViewById(R.id.extra_keys)

        minFontPx = sp(6f)
        maxFontPx = sp(32f)
        fontSizePx = prefs.getInt(PREF_FONT_SIZE, sp(12f)).coerceIn(minFontPx, maxFontPx)

        terminalView.setTerminalViewClient(JangadaViewClient(this))
        terminalView.setTextSize(fontSizePx) // precisa vir antes de setTypeface()
        terminalTypeface = TerminalFonts.load(this)
        terminalView.setTypeface(terminalTypeface)
        terminalView.keepScreenOn = true

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
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
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
        setComposeVisible(prefs.getBoolean(PREF_COMPOSE, true), focus = false)

        // Voltar (ou o botão Início) leva à tela inicial; as sessões continuam rodando.
        findViewById<View>(R.id.home_button).setOnClickListener { goHome() }
        onBackPressedDispatcher.addCallback(this) { goHome() }
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        service?.let { handleIntent(it) }
    }

    override fun onStart() {
        super.onStart()
        if (!::terminalView.isInitialized) return
        val intent = Intent(this, TerminalService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        screenVisible = true
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
            unbindService(connection)
        }
        service = null
        super.onStop()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as TerminalService.LocalBinder).service
            service = svc
            svc.screenClient = sessionClient
            svc.screenLinkOpener = { url -> if (screenVisible) { openUrl(url); true } else false }
            svc.onExit = { finishAndRemoveTask() }
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

        val target = when {
            script != null -> svc.createSession(title, script)
            newShell -> svc.createSession()
            handle != null -> svc.session(handle)
            else -> null
        } ?: session?.takeIf { it in svc.sessions } ?: svc.sessions.lastOrNull() ?: svc.createSession()
        show(target)
    }

    private fun show(newSession: TerminalSession) {
        session = newSession
        findViewById<TextView>(R.id.session_title_bar).text = newSession.mSessionName ?: TerminalService.DEFAULT_TITLE
        terminalView.attachSession(newSession)
        terminalView.onScreenUpdated()
    }

    /** Recria a sessão depois de um "exit" (Enter na tela de processo encerrado). */
    fun startSession() {
        val svc = service ?: return
        val old = session
        show(if (old != null) svc.replaceSession(old) else svc.createSession())
    }

    private fun setComposeVisible(visible: Boolean, focus: Boolean) {
        composeBar.visibility = if (visible) View.VISIBLE else View.GONE
        prefs.edit().putBoolean(PREF_COMPOSE, visible).apply()
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
        prefs.edit().putInt(PREF_FONT_SIZE, fontSizePx).apply()
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
        private const val PREF_FONT_SIZE = "font_size_px"
        private const val PREF_COMPOSE = "compose_visible"
        private const val PASTE_ENTER_DELAY_MS = 80L
        private const val KEY_DELAY_MS = 12L
        private const val COMMAND_ENTER_DELAY_MS = 150L
        const val EXTRA_TITLE = "title"
        const val EXTRA_SCRIPT = "script"
        const val EXTRA_SESSION = "session"
        const val EXTRA_NEW_SHELL = "new_shell"
    }
}

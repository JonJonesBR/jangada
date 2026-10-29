package io.github.jonjonesbr.jangada

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import io.github.jonjonesbr.jangada.terminal.ExtraKeysBar
import io.github.jonjonesbr.jangada.terminal.JangadaViewClient
import io.github.jonjonesbr.jangada.terminal.Links
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import java.io.File

/**
 * O terminal. Quando o Linux já está instalado, abre direto no Debian;
 * se ainda não está, manda para a tela de preparação.
 */
class MainActivity : ComponentActivity() {

    lateinit var terminalView: TerminalView
        private set
    lateinit var extraKeys: ExtraKeysBar
        private set

    private var session: TerminalSession? = null
    private lateinit var linux: LinuxEnv
    private var fontSizePx = 0
    private var minFontPx = 0
    private var maxFontPx = 0

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        linux = LinuxEnv(this)
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
        terminalView.setTypeface(Typeface.MONOSPACE)
        terminalView.keepScreenOn = true

        extraKeys.attach(
            terminalView,
            onPaste = ::pasteFromClipboard,
            onOpenLink = ::openLastLink,
            onKeyboard = ::showKeyboard,
        )

        // Voltar não encerra o shell: só manda o app para o fundo.
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }

        startSession()
        terminalView.post { showKeyboard() }
    }

    override fun onDestroy() {
        session?.finishIfRunning()
        super.onDestroy()
    }

    /** Cria (ou recria) a sessão: Debian quando instalado, shell do Android como reserva. */
    fun startSession() {
        session?.finishIfRunning()
        val newSession = if (linux.isReady) debianSession() else androidSession()
        session = newSession
        terminalView.attachSession(newSession)
    }

    private fun debianSession(): TerminalSession {
        val argv = linux.prootArgv(linux.loginCommand())
        return TerminalSession(argv[0], filesDir.absolutePath, argv, linux.hostEnv(), TRANSCRIPT_ROWS, sessionClient)
    }

    private fun androidSession(): TerminalSession {
        val home = File(filesDir, "home").apply { mkdirs() }
        val env = arrayOf(
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "HOME=${home.absolutePath}",
            "TMPDIR=${cacheDir.absolutePath}",
            "PATH=/system/bin:/system/xbin",
            "LANG=en_US.UTF-8",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
        )
        return TerminalSession("/system/bin/sh", home.absolutePath, arrayOf("-sh"), env, TRANSCRIPT_ROWS, sessionClient)
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
        val transcript = session?.emulator?.screen?.transcriptTextWithFullLinesJoined
        val url = Links.last(transcript)
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
        // o Enter é tratado em JangadaViewClient e reinicia o shell.
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
        private const val TRANSCRIPT_ROWS = 5000
        private const val PREF_FONT_SIZE = "font_size_px"
    }
}

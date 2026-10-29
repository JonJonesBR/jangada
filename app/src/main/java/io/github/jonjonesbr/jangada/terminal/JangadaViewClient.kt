package io.github.jonjonesbr.jangada.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient
import io.github.jonjonesbr.jangada.MainActivity

/** Liga os gestos e o teclado da tela do terminal ao app. */
class JangadaViewClient(private val activity: MainActivity) : TerminalViewClient {

    /** Pinça para aumentar ou diminuir a fonte. */
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            activity.changeFontSize(scale > 1f)
            return 1.0f
        }
        return scale
    }

    /** Toque num link abre o navegador; toque em qualquer outro lugar abre o teclado. */
    override fun onSingleTapUp(e: MotionEvent?) {
        val event = e ?: return
        val view = activity.terminalView
        val emulator = view.currentSession?.emulator ?: return
        val pos = view.getColumnAndRow(event, true)
        val url = Links.first(emulator.screen.getWordAtLocation(pos[0], pos[1]))
        if (url != null) {
            activity.openUrl(url)
            return
        }
        if (!emulator.isMouseTrackingActive) activity.showKeyboard()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && session != null && !session.isRunning) {
            activity.startSession()
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    /** false = deixa a própria tela iniciar a seleção de texto (copiar). */
    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = activity.extraKeys.consumeCtrl()
    override fun readAltKey(): Boolean = activity.extraKeys.consumeAlt()
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        val isEnter = codePoint == '\r'.code || codePoint == '\n'.code
        if (isEnter && session != null && !session.isRunning) {
            activity.startSession()
            return true
        }
        return false
    }

    override fun onEmulatorSet() {}

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

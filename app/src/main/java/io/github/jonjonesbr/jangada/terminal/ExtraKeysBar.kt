package io.github.jonjonesbr.jangada.terminal

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.view.TerminalView
import io.github.jonjonesbr.jangada.R

/**
 * Duas fileiras de teclas que faltam no teclado do celular.
 * Pensadas para os agentes: ESC interrompe, ⇧⇥ troca o modo do Claude Code,
 * "/" abre comandos, "@" cita arquivos, "!" roda comandos do shell,
 * ✎ abre a caixa de texto (com corretor e voz) e ⌨ o teclado do terminal.
 */
class ExtraKeysBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private var terminalView: TerminalView? = null
    private var ctrlOn = false
    private var altOn = false
    private var ctrlKey: TextView? = null
    private var altKey: TextView? = null

    private val textColor = context.getColor(R.color.key_text)
    private val activeColor = context.getColor(R.color.key_active)
    private val rippleRes = TypedValue().let {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        it.resourceId
    }

    init {
        orientation = VERTICAL
    }

    fun attach(
        view: TerminalView,
        onPaste: () -> Unit,
        onOpenLink: () -> Unit,
        onKeyboard: () -> Unit,
        onCompose: () -> Unit,
    ) {
        terminalView = view
        removeAllViews()

        val top = newRow()
        key(top, "ESC", R.string.key_desc_esc) { sendKey(KeyEvent.KEYCODE_ESCAPE) }
        key(top, "⇧⇥", R.string.key_desc_shift_tab) { sendKey(KeyEvent.KEYCODE_TAB, shift = true) }
        key(top, "/", R.string.key_desc_slash) { sendText("/") }
        key(top, "-", R.string.key_desc_hyphen) { sendText("-") }
        key(top, "↑", R.string.key_desc_up) { sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        key(top, "@", R.string.key_desc_at) { sendText("@") }
        key(top, "|", R.string.key_desc_pipe) { sendText("|") }
        key(top, "✎", R.string.key_desc_compose) { onCompose() }
        key(top, "⌨", R.string.key_desc_keyboard) { onKeyboard() }

        val bottom = newRow()
        ctrlKey = key(bottom, "CTRL", R.string.key_desc_ctrl) { ctrlOn = !ctrlOn; refresh() }
        altKey = key(bottom, "ALT", R.string.key_desc_alt) { altOn = !altOn; refresh() }
        key(bottom, "⇥", R.string.key_desc_tab) { sendKey(KeyEvent.KEYCODE_TAB) }
        key(bottom, "←", R.string.key_desc_left) { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        key(bottom, "↓", R.string.key_desc_down) { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        key(bottom, "→", R.string.key_desc_right) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        key(bottom, "!", R.string.key_desc_bang) { sendText("!") }
        key(bottom, context.getString(R.string.key_paste), R.string.key_paste) { onPaste() }
        key(bottom, context.getString(R.string.key_link), R.string.key_desc_link) { onOpenLink() }

        refresh()
    }

    /** Lido pela tela do terminal a cada tecla: CTRL vale para uma tecla só. */
    fun consumeCtrl(): Boolean {
        val on = ctrlOn
        if (on) { ctrlOn = false; refresh() }
        return on
    }

    fun consumeAlt(): Boolean {
        val on = altOn
        if (on) { altOn = false; refresh() }
        return on
    }

    private fun refresh() {
        ctrlKey?.setTextColor(if (ctrlOn) activeColor else textColor)
        ctrlKey?.isSelected = ctrlOn
        altKey?.setTextColor(if (altOn) activeColor else textColor)
        altKey?.isSelected = altOn
    }

    private fun sendKey(keyCode: Int, shift: Boolean = false) {
        val view = terminalView ?: return
        var meta = 0
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        view.onKeyDown(keyCode, KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    private fun sendText(text: String) {
        val view = terminalView ?: return
        for (ch in text) {
            view.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, ch.code, false, false)
        }
    }

    private fun newRow(): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))
        return row
    }

    /** Tecla com descrição para o leitor de tela (o rótulo pode ser só um símbolo). */
    private fun key(row: LinearLayout, label: String, description: Int, onClick: () -> Unit): TextView {
        val tv = TextView(context).apply {
            text = label
            contentDescription = context.getString(description)
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(textColor)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            // Mesmo tamanho para todas; só encolhe o que não cabe (ex.: "Colar").
            setAutoSizeTextTypeUniformWithConfiguration(8, 14, 1, TypedValue.COMPLEX_UNIT_SP)
            isFocusable = false
            setBackgroundResource(rippleRes)
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
        row.addView(tv, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        return tv
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}

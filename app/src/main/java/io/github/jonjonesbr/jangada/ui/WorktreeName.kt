package io.github.jonjonesbr.jangada.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.util.TypedValue
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.linux.Workspace

/**
 * Pede o nome de um trabalho isolado (worktree) do repositório [repo], com a explicação de como
 * ele funciona. Nome inválido mantém o diálogo aberto com o erro no campo.
 *
 * Nome válido: o OK fica desligado e [onName] roda com [done]. Quem cria chama [done] com true
 * (o diálogo fecha) ou com false (o OK volta, para tentar de novo).
 */
fun Activity.askWorktreeName(repo: String, onName: (name: String, done: (Boolean) -> Unit) -> Unit) {
    val explain = TextView(this).apply {
        text = getString(R.string.chat_worktree_explain, repo)
        textSize = 14f
        setTextColor(getColor(R.color.setup_muted))
        setPadding(0, 0, 0, dpToPx(8))
    }
    val max = Workspace.maxWorktreeName(repo)
    val field = EditText(this).apply {
        hint = getString(R.string.chat_worktree_name_hint)
        setSingleLine(true)
        filters = arrayOf(InputFilter.LengthFilter(max.coerceAtLeast(1)))
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(20), dpToPx(8), dpToPx(20), 0)
        addView(explain)
        addView(field)
    }
    val dialog = AlertDialog.Builder(this)
        .setTitle(R.string.chat_worktree_new)
        .setView(content)
        .setPositiveButton(android.R.string.ok, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    // Como em Nova pasta: o OK é ligado depois de mostrar o diálogo; nome inválido o mantém aberto.
    dialog.setOnShowListener {
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        ok.setOnClickListener {
            val name = field.text.toString().trim()
            // A pasta <repo>--<nome> também precisa ser um nome válido (até 64 caracteres), senão não aparece na lista.
            if (Workspace.worktreeFolder(repo, name) == null) {
                field.error = getString(R.string.chat_worktree_name_invalid, max)
                return@setOnClickListener
            }
            ok.isEnabled = false
            onName(name) { made ->
                if (made) {
                    dialog.dismiss()
                } else {
                    ok.isEnabled = true
                }
            }
        }
    }
    dialog.show()
}

private fun Activity.dpToPx(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

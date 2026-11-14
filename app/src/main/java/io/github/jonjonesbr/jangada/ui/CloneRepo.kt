package io.github.jonjonesbr.jangada.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.Projects
import io.github.jonjonesbr.jangada.linux.Workspace
import java.io.File

/** Mesmo limite de [Workspace] para o nome de uma pasta (igual ao de [askNewProject]). */
private const val CLONE_NAME_MAX = 64

/** Quanto da saída do git aparece no diálogo de falha (o fim, onde está o erro). */
private const val CLONE_LOG_TAIL = 4000

/** Intervalo em que o diálogo de progresso lê a linha atual do git. */
private const val CLONE_TICK_MS = 300L

/**
 * Pede o endereço de um repositório e o nome da pasta nova em ~/projetos. O nome acompanha o
 * endereço enquanto o usuário não o editou à mão. Endereço ou nome inválidos (ou pasta que já
 * existe) mantêm o diálogo aberto com o erro no campo. Com os dois certos, o diálogo fecha e
 * [onOk] roda: é ali que o clone começa.
 */
fun Activity.askCloneRepo(rootfs: File, onOk: (url: String, folder: String) -> Unit) {
    val explain = TextView(this).apply {
        text = getString(R.string.clone_explain)
        textSize = 14f
        setTextColor(getColor(R.color.setup_muted_text))
        setPadding(0, 0, 0, dpToPx(8))
    }
    val urlField = EditText(this).apply {
        hint = getString(R.string.clone_url_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setSingleLine(true)
    }
    val nameField = EditText(this).apply {
        hint = getString(R.string.project_new_hint)
        setSingleLine(true)
        filters = arrayOf(InputFilter.LengthFilter(CLONE_NAME_MAX))
    }
    // O nome só acompanha o endereço enquanto o usuário não o editou (ou o apagou). "filling" evita que o
    // próprio preenchimento automático conte como edição.
    var nameEdited = false
    var filling = false
    nameField.doAfterTextChanged {
        // Campo apagado: volta a acompanhar o endereço.
        if (!filling) nameEdited = !it.isNullOrEmpty()
    }
    urlField.doAfterTextChanged { editable ->
        urlField.error = null
        if (nameEdited) return@doAfterTextChanged
        filling = true
        nameField.setText(Projects.nameFromUrl(editable?.toString().orEmpty().trim()))
        filling = false
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(20), dpToPx(8), dpToPx(20), 0)
        addView(explain)
        addView(urlField)
        addView(nameField)
    }
    val dialog = AlertDialog.Builder(this)
        .setTitle(R.string.clone_title)
        .setView(content)
        .setPositiveButton(android.R.string.ok, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    // O OK é ligado depois de mostrar o diálogo: com dado errado, ele fica aberto.
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = urlField.text.toString().trim()
            val name = nameField.text.toString().trim()
            when {
                Projects.hasPassword(url) -> urlField.error = getString(R.string.clone_url_password)
                !Projects.isCloneUrl(url) -> urlField.error = getString(R.string.clone_url_invalid)
                !Workspace.isValidName(name) -> nameField.error = getString(R.string.chat_folder_invalid)
                // Só confere caminhos (sem ler nada). Um link com esse nome conta como existente.
                Fs.exists(Workspace.hostPath(rootfs, Workspace.path(name)).toPath()) ->
                    nameField.error = getString(R.string.project_exists)
                else -> {
                    dialog.dismiss()
                    onOk(url, name)
                }
            }
        }
    }
    dialog.show()
}

/**
 * Mostra o progresso de um clone (a linha atual do git) até ele terminar. Cancelar pede para o git
 * parar, e o diálogo continua até ele sair. Em segundo plano, o diálogo some e o aviso vem depois
 * (ver [TerminalService.unwatchClone]). A tela sair da frente (outro app, tela apagada, fechar)
 * conta como Em segundo plano: daí ela não poderia abrir o chat. Terminando com o diálogo aberto,
 * [onDone] roda e a tela decide o que fazer com o resultado.
 */
fun ComponentActivity.showCloneProgress(
    service: TerminalService,
    task: TerminalService.CloneTask,
    onDone: (TerminalService.CloneTask) -> Unit,
) {
    // A tela já saiu da frente: ninguém acompanha, o aviso vem do serviço.
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        service.unwatchClone(task)
        return
    }
    val line = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dpToPx(12), 0, 0)
    }
    val bar = ProgressBar(this).apply { isIndeterminate = true }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(20), dpToPx(8), dpToPx(20), 0)
        addView(bar)
        addView(line)
    }
    val dialog = AlertDialog.Builder(this)
        .setTitle(getString(if (task.source != null) R.string.projects_copy_progress_title else R.string.clone_progress_title, task.folder))
        .setView(content)
        .setCancelable(false)
        .setNegativeButton(R.string.clone_cancel, null)
        .setPositiveButton(R.string.clone_background, null)
        .create()
    // Handler só deste diálogo: removeCallbacksAndMessages(null) não mexe em mais nada da tela.
    val handler = Handler(Looper.getMainLooper())
    // Só a thread principal mexe nestas variáveis: com "stopped" true, nada mais é agendado.
    var stopped = false
    var observer: LifecycleEventObserver? = null

    /** Para de acompanhar (uma vez só): sem mais leituras, sem ouvir a tela, e o diálogo fecha. */
    fun stop(): Boolean {
        if (stopped) return false
        stopped = true
        handler.removeCallbacksAndMessages(null)
        observer?.let { lifecycle.removeObserver(it) }
        runCatching { dialog.dismiss() }
        return true
    }

    val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            if (task.state == TerminalService.CloneTask.State.RUNNING) {
                line.text = task.lastLine
                handler.postDelayed(this, CLONE_TICK_MS)
                return
            }
            stop()
            task.reported = true
            onDone(task)
        }
    }
    observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP && stop()) service.unwatchClone(task)
    }.also { lifecycle.addObserver(it) }
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            // O git sai aos poucos: o botão não pede de novo; o diálogo fecha quando o clone terminar.
            it.isEnabled = false
            service.cancelClone(task.folder)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            // Escolha do usuário: ao voltar à tela, o progresso não reaparece (o resultado vem pelo aviso).
            task.sentToBackground = true
            if (stop()) service.unwatchClone(task)
        }
    }
    dialog.show()
    handler.post(tick)
}

/**
 * A tela voltou com um clone (ou cópia) ainda rodando, que ela deixou de acompanhar ao sair da
 * frente: mostra o progresso de novo. Só o primeiro que [matches] aceita e o serviço devolve
 * (ver [TerminalService.rewatchClone]). Thread principal.
 */
fun ComponentActivity.resumeCloneProgress(
    service: TerminalService,
    matches: (TerminalService.CloneTask) -> Boolean,
    onDone: (TerminalService.CloneTask) -> Unit,
) {
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
    val task = service.clones.values.firstOrNull { matches(it) && service.rewatchClone(it) } ?: return
    showCloneProgress(service, task, onDone)
}

/**
 * Mostra por que um clone não saiu. Pasta que já existia: um aviso curto. Falha: a saída do git
 * (o fim dela) e, se o erro é conhecido, a dica do que fazer. Cancelado não mostra nada, porque
 * foi o próprio usuário quem parou.
 */
fun Activity.showCloneFailure(task: TerminalService.CloneTask) {
    if (task.cancelled) return
    if (task.state == TerminalService.CloneTask.State.EXISTS) {
        Toast.makeText(this, R.string.project_exists, Toast.LENGTH_LONG).show()
        return
    }
    val output = task.logText()
    val hint = Projects.authHint(output)?.let { getString(it) }
    val detail = output.takeLast(CLONE_LOG_TAIL)
    val message = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextIsSelectable(true)
        text = listOfNotNull(hint, detail).joinToString("\n\n")
    }
    val scroll = ScrollView(this).apply {
        setPadding(dpToPx(20), dpToPx(8), dpToPx(20), 0)
        addView(message)
    }
    AlertDialog.Builder(this)
        .setTitle(if (task.source != null) R.string.projects_copy_failed_title else R.string.clone_failed_title)
        .setView(scroll)
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

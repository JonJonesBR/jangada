package io.github.jonjonesbr.jangada.ui

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.linux.Hosting
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Projects
import io.github.jonjonesbr.jangada.linux.Workspace
import kotlin.concurrent.thread

/** Mesmo limite de [Workspace] para o nome de uma pasta. */
private const val NAME_MAX = 64

/** Host escolhido no grupo (null = "Só neste celular", que não tem host como tag). */
private fun RadioGroup.checkedHost(): Hosting.Host? =
    findViewById<RadioButton>(checkedRadioButtonId)?.tag as? Hosting.Host

/**
 * Pede o nome de um projeto novo (pasta em ~/projetos) e se ele começa com `git init`, com a
 * explicação de como funciona. Nome inválido mantém o diálogo aberto com o erro no campo.
 *
 * Abaixo da caixa do git, "Guardar também em:" lista os hosts (GitHub, GitLab) prontos para criar
 * um repositório privado junto; sem host pronto, a opção não aparece. Sem git, só "Só neste celular".
 *
 * A criação roda fora da thread principal; o OK, o Cancelar e o toque fora ficam desligados enquanto isso. Pasta criada: o
 * diálogo fecha e [onCreated] roda com o nome da pasta, a saída do git (null = sem erro do git) e a
 * saída do host (null = sem erro, ou sem host escolhido).
 */
fun Activity.askNewProject(
    linux: LinuxEnv,
    onCreated: (folder: String, gitOutput: String?, hostOutput: String?) -> Unit,
) {
    val explain = TextView(this).apply {
        text = getString(R.string.project_new_explain)
        textSize = 14f
        setTextColor(getColor(R.color.setup_muted_text))
        setPadding(0, 0, 0, dpToPx(8))
    }
    val field = EditText(this).apply {
        hint = getString(R.string.project_new_hint)
        setSingleLine(true)
        filters = arrayOf(InputFilter.LengthFilter(NAME_MAX))
    }
    val gitBox = CheckBox(this).apply {
        text = getString(R.string.project_new_git)
        isChecked = true
    }
    // Guardar também em: some até saber quais hosts estão prontos (ver a detecção abaixo).
    val where = TextView(this).apply {
        text = getString(R.string.host_new_where)
        textSize = 14f
        setTextColor(getColor(R.color.setup_muted_text))
        setPadding(0, dpToPx(12), 0, dpToPx(4))
        visibility = View.GONE
    }
    // "Só neste celular" é a escolha inicial. Sem tag: não há host.
    val local = RadioButton(this).apply {
        id = View.generateViewId()
        text = getString(R.string.host_new_local)
        isChecked = true
    }
    val hosts = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL
        visibility = View.GONE
        addView(local)
    }
    val hostButtons: Map<Hosting.Host, RadioButton> = Hosting.Host.entries.associateWith { host ->
        RadioButton(this).apply {
            id = View.generateViewId()
            tag = host
            text = getString(R.string.host_new_on, getString(host.label))
            visibility = View.GONE
        }
    }
    hostButtons.values.forEach { hosts.addView(it) }
    // Sem git não há repositório para mandar ao site: os hosts ficam desligados e volta "Só neste celular".
    gitBox.setOnCheckedChangeListener { _, checked ->
        hostButtons.values.forEach { it.isEnabled = checked }
        if (!checked) local.isChecked = true
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpToPx(20), dpToPx(8), dpToPx(20), 0)
        addView(explain)
        addView(field)
        addView(gitBox)
        addView(where)
        addView(hosts)
    }
    val dialog = AlertDialog.Builder(this)
        .setTitle(R.string.project_new_title)
        .setView(content)
        .setPositiveButton(android.R.string.ok, null)
        .setNegativeButton(android.R.string.cancel, null)
        .create()
    // O OK é ligado depois de mostrar o diálogo: com nome inválido, ele fica aberto.
    dialog.setOnShowListener {
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        ok.setOnClickListener {
            val name = field.text.toString().trim()
            if (!Workspace.isValidName(name)) {
                field.error = getString(R.string.chat_folder_invalid)
                return@setOnClickListener
            }
            // Criando: nada fecha o diálogo (Cancelar, toque fora, voltar), senão a pasta nasceria
            // e o trabalho abriria mesmo depois de "cancelar".
            ok.isEnabled = false
            cancel.isEnabled = false
            dialog.setCancelable(false)
            // Lido aqui, na thread principal: a outra thread não toca nas caixas.
            val gitInit = gitBox.isChecked
            val host = if (gitInit) hosts.checkedHost() else null
            // Com host, a criação pode levar até alguns minutos (timeout do gh/glab): o título diz o que roda.
            if (host != null) dialog.setTitle(getString(R.string.host_creating, getString(host.label)))
            thread(name = "jangada-project-new") {
                // Nenhuma exceção pode derrubar o app: vira falha comum.
                val result = runCatching { Projects.create(linux, name, gitInit, host) }
                    .getOrElse { Projects.Result.Failed }
                runOnUiThread {
                    // A tela está fechando: só fecha o diálogo, sem mexer em mais nada.
                    if (isDestroyed || isFinishing) {
                        dialog.dismiss()
                        return@runOnUiThread
                    }
                    when (result) {
                        is Projects.Result.Created -> {
                            dialog.dismiss()
                            onCreated(result.folder, result.gitOutput, result.hostOutput)
                        }
                        Projects.Result.Exists -> {
                            dialog.setTitle(R.string.project_new_title)
                            ok.isEnabled = true
                            cancel.isEnabled = true
                            dialog.setCancelable(true)
                            field.error = getString(R.string.project_exists)
                        }
                        Projects.Result.Failed -> {
                            dialog.setTitle(R.string.project_new_title)
                            ok.isEnabled = true
                            cancel.isEnabled = true
                            dialog.setCancelable(true)
                            Toast.makeText(this, R.string.chat_folder_create_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
    dialog.show()
    // Estado dos hosts fora da thread principal. Só mostra os prontos; sem nenhum, a seção não aparece.
    thread(name = "jangada-host-detect") {
        val states = runCatching { Hosting.detect(linux) }
            .getOrElse { emptyMap<Hosting.Host, Hosting.Status>() }
        runOnUiThread {
            if (isDestroyed || isFinishing || !dialog.isShowing) return@runOnUiThread
            val ready = Hosting.Host.entries.filter { states[it] == Hosting.Status.READY }
            if (ready.isEmpty()) return@runOnUiThread
            for (host in ready) {
                hostButtons.getValue(host).apply {
                    isEnabled = gitBox.isChecked
                    visibility = View.VISIBLE
                }
            }
            where.visibility = View.VISIBLE
            hosts.visibility = View.VISIBLE
        }
    }
}

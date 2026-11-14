package io.github.jonjonesbr.jangada

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.Git
import io.github.jonjonesbr.jangada.linux.Hosting
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Projects
import io.github.jonjonesbr.jangada.linux.Workspace
import io.github.jonjonesbr.jangada.ui.Sheet
import java.io.File
import kotlin.concurrent.thread

/**
 * Mudanças de uma pasta do projeto (git): arquivos alterados, diff de cada um e
 * commit de tudo. Cada chamada ao git roda numa thread; as views mudam só na principal.
 */
class ChangesActivity : JangadaActivity() {

    /** O que o git disse numa leitura da pasta. */
    private class State(
        val isRepo: Boolean,
        val branch: String?,
        val hasHead: Boolean,
        val changes: List<Git.Change>,
        /** Commits à frente do upstream; null sem upstream. */
        val ahead: Int?,
        /** A saída do status foi cortada: a lista não está completa. */
        val cut: Boolean = false,
        /** Endereço do origin; null = sem remoto. */
        val remote: String? = null,
        /** A branch tem upstream (só então o git conta os commits à frente). */
        val hasUpstream: Boolean = false,
        /** O git status passou do tempo limite (ver Git.run): é repositório, mas não deu para ler. */
        val timedOut: Boolean = false,
    )

    private lateinit var linux: LinuxEnv
    /** Pasta do projeto ("" = a raiz) e o caminho dela dentro do Debian. */
    private var folder = ""
    private var cwd = ""
    private lateinit var subtitle: TextView
    private lateinit var summary: TextView
    /** Linha do repositório remoto, logo abaixo do resumo. */
    private lateinit var remoteRow: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var commitBar: View
    private lateinit var messageField: EditText
    private lateinit var commitButton: Button
    private lateinit var askAgentButton: Button
    /** Agente da tela de chat que abriu esta tela (null = nenhum: sem o botão de pedir o commit). */
    private var agentId: String? = null
    private val adapter = ChangesAdapter()
    private var changes: List<Git.Change> = emptyList()
    private var isRepo = false
    /** Há pelo menos um commit (sem ele, o diff compara com o indexado). */
    private var hasHead = false
    /** Endereço do origin (null = sem remoto) e se a branch tem upstream. */
    private var remote: String? = null
    private var hasUpstream = false
    /** Uma ação do remoto (vincular, trocar, desvincular) ou um envio está rodando. */
    private var remoteBusy = false
    /** Diálogo de progresso do envio, da criação no host ou da checagem dos hosts; fecha também se a tela for destruída no meio. */
    private var pushDialog: AlertDialog? = null
    /** Os hosts (GitHub, GitLab) estão sendo verificados para o painel de vincular: um toque por vez. */
    private var detecting = false
    /** Uma leitura está rodando; outro pedido nesse meio tempo só marca [reloadWanted]. */
    private var loading = false
    /** Pedido de leitura que chegou durante outra: roda quando ela terminar (uma thread por vez). */
    private var reloadWanted = false
    /** Um diff (ou a lista de uma pasta) está sendo lido: um toque por vez. */
    private var reading = false
    /** Um commit está rodando (não deixa mandar dois). */
    private var committing = false

    /** Fundo com o efeito de toque do tema, para as linhas da lista. */
    private val selectableBackground by lazy {
        TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_changes)
        linux = LinuxEnv(this)
        // Pasta pedida; uma que sumiu (ou virou link) volta para a raiz, como no chat.
        folder = Workspace.existing(linux.rootfs, intent.getStringExtra(EXTRA_FOLDER).orEmpty())
        cwd = Workspace.path(folder)
        subtitle = findViewById(R.id.changes_subtitle)
        summary = findViewById(R.id.changes_summary)
        remoteRow = findViewById(R.id.changes_remote)
        list = findViewById(R.id.changes_list)
        empty = findViewById(R.id.changes_empty)
        commitBar = findViewById(R.id.changes_commit_bar)
        messageField = findViewById(R.id.changes_message)
        commitButton = findViewById(R.id.changes_commit)
        askAgentButton = findViewById(R.id.changes_ask_agent)
        agentId = intent.getStringExtra(ChatActivity.EXTRA_AGENT_ID)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> changes.getOrNull(position)?.let { openChange(it) } }
        findViewById<View>(R.id.changes_back).setOnClickListener { finish() }
        findViewById<View>(R.id.changes_refresh).setOnClickListener { load() }
        remoteRow.setOnClickListener { showRemote() }
        commitButton.setOnClickListener { commit() }
        askAgentButton.setOnClickListener { askAgentToCommit() }
        messageField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateCommit()
        })
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        runCatching { pushDialog?.dismiss() }
        pushDialog = null
        super.onDestroy()
    }

    /**
     * Lê a pasta no git fora da thread principal e mostra o resultado. Só uma leitura roda por
     * vez (onResume, atualizar e o fim do commit podem pedir juntos); um pedido no meio de outra
     * descarta o resultado dela e lê de novo, então a tela nunca desenha uma leitura velha por cima.
     */
    private fun load() {
        if (loading) {
            reloadWanted = true
            return
        }
        loading = true
        thread(name = "changes-load") {
            val read = readState()
            runOnUiThread {
                loading = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (reloadWanted) {
                    reloadWanted = false
                    load()
                    return@runOnUiThread
                }
                show(read)
            }
        }
    }

    /** Lê o estado da pasta (chamar fora da thread principal). */
    private fun readState(): State {
        val hostDir = Workspace.hostPath(linux.rootfs, cwd)
        // Sem .git dentro da pasta, o git subiria para um repositório pai: não é repositório desta pasta.
        if (!Fs.exists(File(hostDir, ".git").toPath())) return State(false, null, false, emptyList(), null)
        val branch = Workspace.gitBranch(linux.rootfs, hostDir)
        val status = Git.status(linux, cwd)
        if (status.code == Git.TIMED_OUT) return State(false, branch, false, emptyList(), null, timedOut = true)
        if (status.code != 0) return State(false, branch, false, emptyList(), null)
        // Saída cortada: a última entrada pode ter vindo pela metade.
        val parsed = Git.parseStatus(status.output).let { if (status.cut) it.dropLast(1) else it }
        val hasHead = Git.run(linux, cwd, "rev-parse", "--verify", "-q", "HEAD").code == 0
        // Sem upstream o git erra: não é um problema a mostrar, só não há contagem.
        val ahead = Git.run(linux, cwd, "rev-list", "--count", "@{u}..HEAD")
            .takeIf { it.code == 0 }?.output?.trim()?.toIntOrNull()
        // Sem origin o git erra (código diferente de 0): então não há remoto.
        val remote = Git.run(linux, cwd, "remote", "get-url", "origin")
            .takeIf { it.code == 0 }?.output?.trim()?.lineSequence()?.firstOrNull()?.takeIf { it.isNotEmpty() }
        return State(
            isRepo = true,
            branch = branch,
            hasHead = hasHead,
            changes = parsed,
            ahead = ahead,
            cut = status.cut,
            remote = remote,
            hasUpstream = ahead != null,
        )
    }

    /** Mostra a leitura: título, resumo, lista ou o aviso de vazio, e o campo de commit. */
    private fun show(read: State) {
        isRepo = read.isRepo
        hasHead = read.hasHead
        changes = read.changes
        remote = read.remote
        hasUpstream = read.hasUpstream
        askAgentButton.visibility = if (read.isRepo && changes.isNotEmpty() && agentId != null) View.VISIBLE else View.GONE
        val label = Workspace.label(folder, getString(R.string.chat_folder_root))
        subtitle.text = read.branch?.let { "$label · $it" } ?: label
        if (!read.isRepo) {
            summary.visibility = View.GONE
            remoteRow.visibility = View.GONE
            list.visibility = View.GONE
            commitBar.visibility = View.GONE
            empty.text = getString(if (read.timedOut) R.string.changes_status_timeout else R.string.changes_not_repo)
            empty.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
            updateCommit()
            return
        }
        val parts = mutableListOf(resources.getQuantityString(R.plurals.changes_count, changes.size, changes.size))
        if (read.cut) parts += getString(R.string.changes_list_cut)
        read.ahead?.let { parts += resources.getQuantityString(R.plurals.changes_ahead, it, it) }
        summary.text = parts.joinToString(" · ")
        summary.visibility = View.VISIBLE
        updateRemoteRow()
        commitBar.visibility = View.VISIBLE
        val any = changes.isNotEmpty()
        list.visibility = if (any) View.VISIBLE else View.GONE
        empty.text = getString(R.string.changes_none)
        empty.visibility = if (any) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
        updateCommit()
    }

    /**
     * Abre o chat do agente desta tela com o pedido de commit na caixa e o envia (ver ChatActivity.EXTRA_SEND).
     * A tela de mudanças sai: o chat fica no lugar dela.
     */
    private fun askAgentToCommit() {
        val agent = agentId ?: return
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_AGENT_ID, agent)
                .putExtra(ChatActivity.EXTRA_FOLDER, folder)
                .putExtra(ChatActivity.EXTRA_SEND, getString(R.string.changes_ask_agent_prompt))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    /** Linha do remoto: "não vinculado", ou o endereço (sem credenciais) e, se for o caso, "ainda não enviado". */
    private fun updateRemoteRow() {
        val current = remote
        remoteRow.text = if (current == null) {
            getString(R.string.remote_none)
        } else {
            buildString {
                append("☁ ").append(Projects.displayUrl(current))
                if (!hasUpstream && hasHead) append(" · ").append(getString(R.string.remote_not_pushed))
            }
        }
        remoteRow.visibility = View.VISIBLE
    }

    /**
     * Painel do remoto. Com origin: enviar, trocar o endereço e desvincular (só tira o vínculo;
     * nada é apagado no site). Sem origin: antes confere os hosts (ver [detectHosts]).
     */
    private fun showRemote() {
        val current = remote
        if (current == null) {
            detectHosts()
            return
        }
        val rows = listOf(
            Sheet.Row(
                title = getString(R.string.remote_push),
                description = getString(if (hasHead) R.string.remote_push_desc else R.string.remote_push_no_commit),
                value = Projects.displayUrl(current),
                icon = "⇡",
            ) {
                if (hasHead) push() else Toast.makeText(this, R.string.remote_push_no_commit, Toast.LENGTH_SHORT).show()
            },
            Sheet.Row(
                title = getString(R.string.remote_pull),
                description = getString(R.string.remote_pull_desc),
                icon = "⇣",
            ) { pull() },
            Sheet.Row(title = getString(R.string.remote_change), icon = "✎") { askRemoteUrl(current) },
            Sheet.Row(
                title = getString(R.string.remote_unlink),
                description = getString(R.string.remote_unlink_desc),
                icon = "✕",
                destructive = true,
            ) { confirmUnlink() },
        )
        Sheet(this).title(getString(R.string.remote_title)).rows(rows).show()
    }

    /**
     * Confere GitHub e GitLab numa thread (pode demorar: o gh e o glab consultam o login) e abre o
     * painel de vincular. Um toque por vez.
     */
    private fun detectHosts() {
        if (detecting) return
        detecting = true
        // A checagem leva alguns segundos: o progresso aparece e fecha em todo caminho (também no onDestroy).
        val progress = busyDialog(getString(R.string.host_checking))
        pushDialog = progress
        thread(name = "changes-host-detect") {
            val states = runCatching { Hosting.detect(linux) }
                .getOrElse { emptyMap<Hosting.Host, Hosting.Status>() }
            runOnUiThread {
                runCatching { progress.dismiss() }
                if (pushDialog === progress) pushDialog = null
                detecting = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                // Um vínculo pode ter terminado nesse meio tempo: aí o painel é o do remoto vinculado.
                if (remote != null) showRemote() else showLinkSheet(states)
            }
        }
    }

    /**
     * Painel de vincular. Numa pasta (não na raiz), cada host mostra o próximo passo: criar o
     * repositório (se a ferramenta está pronta), entrar (instalada, sem login) ou instalar a
     * ferramenta. Depois, sempre, vincular um endereço que já existe.
     */
    private fun showLinkSheet(states: Map<Hosting.Host, Hosting.Status>) {
        val rows = mutableListOf<Sheet.Row>()
        // A raiz (folder vazio) e nomes que o gh leria como opção ficam só com o vincular.
        if (Hosting.canCreate(folder)) {
            for (host in Hosting.Host.entries) {
                val label = getString(host.label)
                when (states[host] ?: Hosting.Status.MISSING) {
                    Hosting.Status.READY -> rows += Sheet.Row(
                        title = getString(R.string.host_create, label),
                        description = getString(R.string.host_create_desc),
                        icon = "＋",
                    ) { chooseVisibility(host) }
                    Hosting.Status.LOGGED_OUT -> {
                        val title = getString(R.string.host_login, label)
                        rows += Sheet.Row(
                            title = title,
                            description = getString(R.string.host_login_desc),
                            icon = "🔑",
                        ) { openTerminal(title, Hosting.loginScript(host)) }
                    }
                    Hosting.Status.MISSING -> {
                        val title = getString(R.string.host_install, label)
                        rows += Sheet.Row(
                            title = title,
                            description = getString(R.string.host_install_desc, host.tool),
                            icon = "⇣",
                        ) { openTerminal(title, Hosting.installScript(host)) }
                    }
                }
            }
        }
        rows += Sheet.Row(
            title = getString(R.string.remote_link),
            description = getString(R.string.remote_link_desc),
            icon = "🔗",
        ) { askRemoteUrl(null) }
        Sheet(this).title(getString(R.string.remote_title)).rows(rows).show()
    }

    /** Visibilidade do repositório novo no [host]: privado (o primeiro, marcado) ou público. */
    private fun chooseVisibility(host: Hosting.Host) {
        Sheet(this)
            .title(getString(R.string.host_create, getString(host.label)))
            .rows(
                listOf(
                    Sheet.Row(
                        title = getString(R.string.host_private),
                        description = getString(R.string.host_private_desc),
                        icon = "🔒",
                        checked = true,
                    ) { createOnHost(host, true) },
                    Sheet.Row(
                        title = getString(R.string.host_public),
                        description = getString(R.string.host_public_desc),
                        icon = "🌐",
                    ) { createOnHost(host, false) },
                ),
            )
            .show()
    }

    /**
     * Cria o repositório no [host] para esta pasta, numa thread, com o progresso na tela. O
     * [Hosting.create] vincula o origin e, se já há commit, envia a branch. Erro: a saída do host
     * (com a dica, se reconhecida), ou o aviso de que pode ter sido criado no site sem o vínculo aqui.
     */
    private fun createOnHost(host: Hosting.Host, isPrivate: Boolean) {
        if (remoteBusy || !Hosting.canCreate(folder)) return
        remoteBusy = true
        val label = getString(host.label)
        val progress = busyDialog(getString(R.string.host_creating, label))
        pushDialog = progress
        // Lidos aqui, na thread principal: a outra thread não toca nestes campos.
        val name = folder
        val withPush = hasHead
        thread(name = "changes-host-create") {
            val out = runCatching { Hosting.create(linux, name, host, isPrivate, withPush) }
                .getOrElse { Git.Result(-1, it.message ?: "") }
            runOnUiThread {
                runCatching { progress.dismiss() }
                if (pushDialog === progress) pushDialog = null
                remoteBusy = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                when (out.code) {
                    0 -> Toast.makeText(this, getString(R.string.host_created, label), Toast.LENGTH_LONG).show()
                    Hosting.NO_REMOTE -> showText(
                        R.string.host_failed,
                        listOfNotNull(getString(R.string.host_no_remote), out.shown.takeIf { it.isNotBlank() })
                            .joinToString("\n\n"),
                    )
                    else -> {
                        val hint = Projects.authHint(out.output)?.let { getString(it) }
                        val body = listOfNotNull(hint, out.shown.takeIf { it.isNotBlank() }).joinToString("\n\n")
                        showText(R.string.host_failed, body.ifBlank { getString(R.string.host_failed) })
                    }
                }
                load()
            }
        }
    }

    /** Diálogo de progresso sem botões: não some sozinho; quem chama o fecha. */
    private fun busyDialog(title: CharSequence): AlertDialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setView(ProgressBar(this).apply {
            isIndeterminate = true
            setPadding(0, dp(16), 0, dp(16))
        })
        .setCancelable(false)
        .show()

    /** Abre o terminal com [script] (instalar ou entrar num host), na pasta desta tela. Ao voltar, a tela relê. */
    private fun openTerminal(title: String, script: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TITLE, title)
                .putExtra(MainActivity.EXTRA_SCRIPT, script)
                .putExtra(MainActivity.EXTRA_FOLDER, folder),
        )
    }

    /**
     * Diálogo com o endereço do remoto: vincular ([current] null, `git remote add`) ou trocar
     * (`git remote set-url`). Endereço inválido mantém o diálogo aberto com o erro no campo.
     */
    private fun askRemoteUrl(current: String?) {
        val explain = TextView(this).apply {
            text = getString(R.string.remote_link_explain)
            textSize = 14f
            setTextColor(getColor(R.color.setup_muted_text))
            setPadding(0, 0, 0, dp(8))
        }
        val field = EditText(this).apply {
            hint = getString(R.string.clone_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(current ?: "")
        }
        // Digitar de novo tira o erro do campo.
        field.doAfterTextChanged { field.error = null }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(explain)
            addView(field)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (current == null) R.string.remote_link else R.string.remote_change)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // O OK é ligado depois de mostrar o diálogo: com endereço errado, ele fica aberto.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = field.text.toString().trim()
                if (Projects.hasPassword(url)) {
                    field.error = getString(R.string.clone_url_password)
                } else if (!Projects.isCloneUrl(url)) {
                    field.error = getString(R.string.clone_url_invalid)
                } else {
                    dialog.dismiss()
                    runRemote("remote", if (current == null) "add" else "set-url", "origin", url)
                }
            }
        }
        dialog.show()
    }

    /**
     * Um comando do git que mexe no remoto, numa thread. Sucesso: aviso e a tela relê o estado.
     * Erro: a saída do git, e a tela também relê (o comando pode ter mudado algo pela metade).
     */
    private fun runRemote(vararg args: String) {
        if (remoteBusy) return
        remoteBusy = true
        thread(name = "changes-remote") {
            val out = runCatching { Git.run(linux, cwd, *args) }.getOrElse { Git.Result(-1, it.message ?: "") }
            runOnUiThread {
                remoteBusy = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (out.code == 0) {
                    Toast.makeText(this, R.string.remote_saved, Toast.LENGTH_SHORT).show()
                } else {
                    showText(R.string.git_failed_title, out.shown.ifBlank { getString(R.string.git_failed_title) })
                }
                load()
            }
        }
    }

    /**
     * `git push -u origin HEAD` numa thread, com um diálogo de progresso que não se fecha sozinho.
     * O diálogo fecha em todo caminho, até com a tela já saindo. Erro: a dica (se conhecida) e a saída.
     */
    private fun push() {
        if (remoteBusy) return
        remoteBusy = true
        val progress = busyDialog(getString(R.string.remote_pushing))
        pushDialog = progress
        thread(name = "changes-push") {
            val out = runCatching { Git.run(linux, cwd, *Projects.SAFE_NETWORK, "push", "-u", "origin", "HEAD") }
                .getOrElse { Git.Result(-1, it.message ?: "") }
            runOnUiThread {
                runCatching { progress.dismiss() }
                if (pushDialog === progress) pushDialog = null
                remoteBusy = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (out.code == 0) {
                    Toast.makeText(this, R.string.remote_pushed, Toast.LENGTH_SHORT).show()
                } else {
                    val hint = Projects.authHint(out.output)?.let { getString(it) }
                    val body = listOfNotNull(hint, out.shown.takeIf { it.isNotBlank() }).joinToString("\n\n")
                    showText(R.string.remote_push_failed, body.ifBlank { getString(R.string.remote_push_failed) })
                }
                load()
            }
        }
    }

    /**
     * `git pull --ff-only` numa thread, como o [push]: só avança a branch, nunca junta sozinho.
     * Se a branch daqui e a do remoto se separaram, a explicação diz para pedir ao agente.
     */
    private fun pull() {
        if (remoteBusy) return
        remoteBusy = true
        val progress = busyDialog(getString(R.string.remote_pulling))
        pushDialog = progress
        thread(name = "changes-pull") {
            val out = runCatching { Git.run(linux, cwd, *Projects.SAFE_NETWORK, "pull", "--ff-only") }
                .getOrElse { Git.Result(-1, it.message ?: "") }
            runOnUiThread {
                runCatching { progress.dismiss() }
                if (pushDialog === progress) pushDialog = null
                remoteBusy = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (out.code == 0) {
                    Toast.makeText(this, R.string.remote_pulled, Toast.LENGTH_SHORT).show()
                } else {
                    val hint = (Projects.authHint(out.output) ?: Projects.pullHint(out.output))?.let { getString(it) }
                    val body = listOfNotNull(hint, out.shown.takeIf { it.isNotBlank() }).joinToString("\n\n")
                    showText(R.string.remote_pull_failed, body.ifBlank { getString(R.string.remote_pull_failed) })
                }
                load()
            }
        }
    }

    /** Confirma antes de tirar o vínculo com o origin. */
    private fun confirmUnlink() {
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_unlink_confirm)
            .setPositiveButton(R.string.remote_unlink) { _, _ -> runRemote("remote", "remove", "origin") }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Commit só com repositório, mudanças e mensagem; e sem outro commit rodando. */
    private fun updateCommit() {
        commitButton.isEnabled = isRepo && !committing && changes.isNotEmpty() && messageField.text.isNotBlank()
    }

    /** `git add -A` e `git commit -m`, numa thread. Erro: a saída; sucesso: o hash e a lista recarregada. */
    private fun commit() {
        val message = messageField.text.toString().trim()
        if (committing || message.isEmpty() || changes.isEmpty()) return
        committing = true
        updateCommit()
        thread(name = "changes-commit") {
            val added = Git.run(linux, cwd, "add", "-A")
            // A mensagem vai como um argumento só, entre aspas simples: nada nela vira comando.
            val done = if (added.code == 0) Git.run(linux, cwd, "commit", "-m", message) else added
            val hash = if (done.code == 0) Git.run(linux, cwd, "rev-parse", "--short", "HEAD").output.trim() else ""
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                committing = false
                if (done.code == 0) {
                    messageField.setText("")
                    Toast.makeText(this, getString(R.string.changes_committed, hash), Toast.LENGTH_SHORT).show()
                    load()
                } else {
                    val identity = Git.identityMissing(done.output)
                    val body = if (identity) getString(R.string.changes_identity) + "\n\n" + done.shown else done.shown
                    showText(R.string.git_failed_title, body.ifBlank { getString(R.string.git_failed_title) })
                    // O add -A pode ter mudado o índice: a lista mostra o estado de agora.
                    load()
                    updateCommit()
                }
            }
        }
    }

    /** Toque numa linha: pasta não rastreada mostra os arquivos dela; o resto, o diff. */
    private fun openChange(change: Git.Change) {
        if (change.isUntrackedDir) showFolderFiles(change.path) else showDiff(change)
    }

    /**
     * Diff do arquivo, numa thread. Renomeação compara os dois caminhos (-M). Sem commit
     * ainda, compara com o indexado e com o não indexado.
     */
    private fun showDiff(change: Git.Change) {
        if (reading) return
        reading = true
        val head = hasHead
        val paths = listOfNotNull(change.from, change.path).toTypedArray()
        thread(name = "changes-diff") {
            val result = when {
                change.code == "??" -> Git.run(linux, cwd, "diff", "--no-index", "--", "/dev/null", change.path)
                head -> Git.run(linux, cwd, "diff", "-M", "HEAD", "--", *paths)
                else -> {
                    val staged = Git.run(linux, cwd, "diff", "--cached", "-M", "--", *paths)
                    val unstaged = Git.run(linux, cwd, "diff", "--", *paths)
                    Git.Result(0, staged.output + unstaged.output, staged.cut || unstaged.cut)
                }
            }
            runOnUiThread {
                reading = false
                if (!isDestroyed && !isFinishing) showDiffDialog(change.path, result.output, result.cut)
            }
        }
    }

    /**
     * Pasta não rastreada: os arquivos dela (sem os ignorados), até [MAX_FOLDER_FILES]. Tocar
     * num arquivo mostra o conteúdo dele como diff.
     */
    private fun showFolderFiles(dir: String) {
        if (reading) return
        reading = true
        thread(name = "changes-folder") {
            val result = Git.run(linux, cwd, "ls-files", "--others", "--exclude-standard", "-z", "--", dir)
            // Saída cortada: a última entrada pode ter vindo pela metade.
            val all = Git.parseNul(result.output).let { if (result.cut) it.dropLast(1) else it }
            runOnUiThread {
                reading = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (result.code != 0) {
                    showText(R.string.git_failed_title, result.shown.ifBlank { getString(R.string.git_failed_title) })
                    return@runOnUiThread
                }
                val files = all.take(MAX_FOLDER_FILES)
                val rows = files.map { path ->
                    Sheet.Row(title = path.removePrefix(dir).ifEmpty { path }) { showDiff(Git.Change(path, "??")) }
                }.toMutableList()
                if (result.cut || all.size > files.size) rows += Sheet.Row(title = getString(R.string.changes_diff_cut), enabled = false) {}
                Sheet(this).title(dir).rows(rows).show()
            }
        }
    }

    /** O diff numa caixa selecionável e rolável, com cor por tipo de linha; corta em [MAX_DIFF_LINES]. */
    private fun showDiffDialog(path: String, output: String, cut: Boolean) {
        val lines = output.lines().dropLastWhile { it.isEmpty() }
        val spanned = SpannableStringBuilder()
        for (line in lines.take(MAX_DIFF_LINES)) {
            val start = spanned.length
            spanned.append(line).append('\n')
            spanned.setSpan(ForegroundColorSpan(getColor(diffColor(line))), start, spanned.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (lines.size > MAX_DIFF_LINES || cut) spanned.append(getString(R.string.changes_diff_cut)).append('\n')
        if (lines.isEmpty()) spanned.append(getString(R.string.changes_diff_empty))
        val view = TextView(this).apply {
            text = spanned
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(getColor(R.color.setup_muted_text))
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            // Linhas longas rolam para o lado, sem quebrar o código.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        AlertDialog.Builder(this)
            .setTitle(path)
            .setView(ScrollView(this).apply {
                addView(HorizontalScrollView(this@ChangesActivity).apply { addView(view) })
            })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Cor de uma linha do diff. Os cabeçalhos "+++" e "---" ficam cinza, como o resto. */
    private fun diffColor(line: String): Int = when {
        line.startsWith("+++") || line.startsWith("---") -> R.color.setup_muted_text
        line.startsWith("+") -> R.color.setup_done_text
        line.startsWith("-") -> R.color.setup_error
        line.startsWith("@@") -> R.color.accent_text
        else -> R.color.setup_muted_text
    }

    /** Texto longo (saída do git) numa caixa que se pode selecionar e copiar. */
    private fun showText(title: Int, body: String) {
        val view = TextView(this).apply {
            text = body
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(getColor(R.color.setup_text))
            setTextIsSelectable(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Rótulo do tipo de mudança, pelo código do git. */
    private fun labelOf(code: String): Int = when {
        code == "??" -> R.string.changes_untracked
        'D' in code -> R.string.changes_deleted
        'A' in code -> R.string.changes_added
        'R' in code -> R.string.changes_renamed
        else -> R.string.changes_modified
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** Uma linha por arquivo: rótulo do tipo e caminho; 48dp de altura no mínimo. */
    private inner class ChangesAdapter : BaseAdapter() {
        override fun getCount() = changes.size
        override fun getItem(position: Int): Any = changes[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val change = changes[position]
            return LinearLayout(this@ChangesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(16), dp(6), dp(16), dp(6))
                setBackgroundResource(selectableBackground)
                addView(TextView(this@ChangesActivity).apply {
                    text = getString(labelOf(change.code))
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(getColor(R.color.accent_text))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginEnd = dp(12) }
                })
                addView(TextView(this@ChangesActivity).apply {
                    text = change.from?.let { "$it → ${change.path}" } ?: change.path
                    textSize = 14f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setTextColor(getColor(R.color.setup_text))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        }
    }

    companion object {
        /** Pasta do projeto a mostrar ("" = a raiz dos projetos). */
        const val EXTRA_FOLDER = "folder"
        /** Acima disso o diff é cortado (a tela não precisa de mais). */
        private const val MAX_DIFF_LINES = 3000
        /** Arquivos mostrados de uma pasta não rastreada. */
        private const val MAX_FOLDER_FILES = 500
    }
}

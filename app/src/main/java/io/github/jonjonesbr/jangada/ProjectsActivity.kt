package io.github.jonjonesbr.jangada

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.text.InputFilter
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import io.github.jonjonesbr.jangada.chat.ChatDrafts
import io.github.jonjonesbr.jangada.chat.ChatHistory
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.Git
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.ProjectOps
import io.github.jonjonesbr.jangada.linux.ProjectSize
import io.github.jonjonesbr.jangada.linux.Projects
import io.github.jonjonesbr.jangada.linux.Workspace
import io.github.jonjonesbr.jangada.ui.Sheet
import io.github.jonjonesbr.jangada.ui.showCloneFailure
import io.github.jonjonesbr.jangada.ui.resumeCloneProgress
import io.github.jonjonesbr.jangada.ui.showCloneProgress
import java.io.File
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * Projetos: as pastas de ~/projetos, com Renomear, Duplicar e Excluir. Tocar num projeto abre o painel de
 * ações. Antes de mexer numa pasta, o serviço fecha os chats e terminais dela (closeFolder), e a
 * pasta fica ocupada até o fim da operação. As regras estão em [ProjectOps]. Leituras de disco
 * rodam em threads; as views mudam só na thread principal.
 */
class ProjectsActivity : JangadaActivity() {

    private val linux by lazy { LinuxEnv(this) }

    /** Serviço ligado (entre onStart e onStop): dono dos chats e terminais abertos. */
    private var service: TerminalService? = null

    /** true desde o bindService até o unbindService (feitos em onStart e onStop). */
    private var bound = false

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    /** Última leitura de ~/projetos. */
    private var listing: List<Workspace.Folder> = emptyList()

    /** Conta as leituras: só a mais nova atualiza a tela. */
    private var loadSeq = 0

    /** A primeira leitura de ~/projetos já chegou (antes disso, a lista vazia não é mostrada). */
    private var loaded = false

    /** Tamanho medido de cada projeto (chave: nome). Só a thread principal mexe; não é apagado ao recarregar. */
    private val sizes = HashMap<String, ProjectSize.Sizes>()

    /** Geração da medição: cada nova medição (ou sair da tela) soma 1 e a antiga para sozinha. Só a principal escreve. */
    @Volatile private var measuring = 0

    /** O painel de um projeto está sendo preparado (contando as conversas). Só a principal mexe. */
    private var actionsPending = false

    /** Pasta do clone ou cópia que falhou (extra do aviso), esperando o serviço para mostrar o log. */
    private var pendingCloneFailure: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        empty = label(getString(R.string.projects_empty), 15f, R.color.setup_muted_text, top = 8)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            addView(list)
            addView(empty)
        }
        val back = TextView(this).apply {
            setText(R.string.projects_back)
            setTextColor(getColor(R.color.bar_text))
            textSize = 15f
            minHeight = dp(48)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = getString(R.string.projects_title)
            setTextColor(getColor(R.color.bar_text))
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.bar_bg))
            addView(back)
            addView(title)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.setup_bg))
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(
            ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
        // Aviso de cópia que falhou: a tela abre com o log (ver showPendingCloneFailure).
        if (savedInstanceState == null) takeCloneFailure(intent)
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, TerminalService::class.java), connection, Context.BIND_AUTO_CREATE)
        reload()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeCloneFailure(intent)
        showPendingCloneFailure()
    }

    /** Guarda a pasta do aviso de clone que falhou e tira o extra do intent (para não mostrar de novo). */
    private fun takeCloneFailure(intent: Intent?) {
        val target = intent ?: return
        val folder = target.getStringExtra(TerminalService.EXTRA_CLONE_FAILED) ?: return
        pendingCloneFailure = folder
        target.removeExtra(TerminalService.EXTRA_CLONE_FAILED)
    }

    /** Com o serviço ligado, mostra o log do clone que falhou (se ele ainda estiver como falho). */
    private fun showPendingCloneFailure() {
        val folder = pendingCloneFailure ?: return
        val svc = service ?: return
        pendingCloneFailure = null
        val task = svc.clones[folder] ?: return
        if (task.state != TerminalService.CloneTask.State.RUNNING && task.state != TerminalService.CloneTask.State.OK) {
            showCloneFailure(task)
        }
    }

    override fun onStop() {
        // Para a medição em andamento (a thread vê a geração mudada e para).
        measuring++
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
            // Com o serviço na mão, a lista mostra quais projetos têm trabalho aberto.
            render()
            showPendingCloneFailure()
            // Cópia que seguia com a tela fora: o progresso volta.
            resumeCloneProgress(svc, { it.source != null }) { done ->
                if (done.state == TerminalService.CloneTask.State.OK) toast(R.string.projects_copied) else showCloneFailure(done)
                reload()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            render()
        }
    }

    /** Relê ~/projetos numa thread e redesenha a lista na principal. */
    private fun reload() {
        val seq = ++loadSeq
        thread(name = "jangada-projects-list") {
            // Nenhuma exceção de leitura pode derrubar o app: vira lista vazia.
            val items = runCatching { Workspace.list(linux) }.getOrElse { emptyList() }
            runOnUiThread {
                if (isDestroyed || seq != loadSeq) return@runOnUiThread
                listing = items
                loaded = true
                render()
                measureAll(items)
            }
        }
    }

    /**
     * Mede o tamanho de cada projeto, um de cada vez, numa thread. Uma nova leitura (ou sair da tela)
     * troca a geração e esta medição para. Erro numa medida não derruba nada: o projeto fica sem tamanho.
     */
    private fun measureAll(items: List<Workspace.Folder>) {
        val gen = ++measuring
        thread(name = "jangada-projects-size") {
            for (item in items) {
                if (measuring != gen || isDestroyed) return@thread
                val size = runCatching {
                    ProjectSize.measure(Workspace.hostPath(linux.rootfs, Workspace.path(item.name)).toPath()) {
                        measuring != gen || isDestroyed
                    }
                }.getOrNull() ?: continue
                runOnUiThread {
                    if (!isDestroyed && measuring == gen) {
                        sizes[item.name] = size
                        render()
                    }
                }
            }
        }
    }

    /** Desenha uma linha por projeto (a lista já lida) e a explicação quando não há nenhum. */
    private fun render() {
        list.removeAllViews()
        empty.visibility = if (loaded && listing.isEmpty()) View.VISIBLE else View.GONE
        val open = openFolders()
        listing.forEach { list.addView(row(it, it.name in open)) }
    }

    /** Pastas com chat ou terminal aberto (quem sabe é o serviço). */
    private fun openFolders(): Set<String> {
        val svc = service ?: return emptySet()
        return svc.chats.values.map { it.folder }.toSet() + svc.sessionFolder.values
    }

    private fun row(item: Workspace.Folder, open: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_bg)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isClickable = true
        isFocusable = true
        setOnClickListener { openActions(item) }
        addView(label(item.name, 16f, R.color.setup_text, bold = true, top = 0))
        Workspace.describe(this@ProjectsActivity, item)?.let { addView(label(it, 14f, R.color.setup_muted_text)) }
        sizes[item.name]?.let {
            addView(label(Formatter.formatShortFileSize(this@ProjectsActivity, it.total), 14f, R.color.setup_muted_text))
        }
        if (open) addView(label(getString(R.string.projects_open_work), 14f, R.color.setup_done_text))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    /**
     * Painel de ações de um projeto. Renomear só some de vez quando a regra recusa (trabalho isolado
     * ou repositório com trabalhos ligados); Excluir, idem. A razão aparece na linha desligada.
     */
    private fun openActions(item: Workspace.Folder) {
        // As conversas do Claude são contadas fora da principal; o painel abre quando a contagem chega.
        // Dois toques seguidos não abrem dois painéis.
        if (actionsPending) return
        actionsPending = true
        thread(name = "jangada-projects-info") {
            val convs = runCatching { ProjectSize.claudeConversations(Projects.claudeHistoryDir(linux, item.name)) }.getOrNull()
            runOnUiThread {
                actionsPending = false
                if (!isDestroyed) showActions(item, convs)
            }
        }
    }

    private fun showActions(item: Workspace.Folder, convs: ProjectSize.Conversations?) {
        val name = item.name
        // Só os dois motivos que valem para renomear: outros (nome em uso, nome inválido) aparecem no diálogo.
        val renameBlock = ProjectOps.renameRefusal(name, "${name}x", listing)
            ?.takeIf { it == ProjectOps.Refusal.IS_WORKTREE || it == ProjectOps.Refusal.HAS_WORKTREES }
        val deleteBlock = ProjectOps.deleteRefusal(name, listing)
        val copyBlock = ProjectOps.duplicateRefusal(name, listing)
        val sheet = Sheet(this).title(name)
        sizes[name]?.let { s ->
            sheet.message(
                getString(
                    R.string.projects_size_detail,
                    Formatter.formatShortFileSize(this, s.total),
                    Formatter.formatShortFileSize(this, s.git),
                    Formatter.formatShortFileSize(this, s.deps),
                ),
            )
        }
        val rows = mutableListOf(
            Sheet.Row(
                getString(R.string.projects_rename),
                renameBlock?.let { blockReason(it, name, renaming = true) },
                enabled = renameBlock == null,
            ) { askRename(item) },
            Sheet.Row(
                getString(R.string.projects_copy),
                if (copyBlock == ProjectOps.Refusal.IS_WORKTREE) getString(R.string.projects_copy_worktree)
                else copyBlock?.let { getString(R.string.projects_gone) } ?: getString(R.string.projects_copy_desc),
                enabled = copyBlock == null,
            ) { askCopy(item) },
        )
        if (convs != null && convs.count > 0) {
            rows += Sheet.Row(
                getString(R.string.projects_conversations),
                resources.getQuantityString(
                    R.plurals.projects_conversations_desc,
                    convs.count,
                    convs.count,
                    Formatter.formatShortFileSize(this, convs.bytes),
                ),
            ) { openConversations(item, convs) }
        }
        // Dependências (node_modules, .venv…) se refazem: apagar libera espaço sem perder código.
        val deps = sizes[name]?.deps ?: 0L
        if (deps > 0L) {
            rows += Sheet.Row(
                getString(R.string.projects_deps_clean),
                getString(R.string.projects_deps_clean_desc, Formatter.formatShortFileSize(this, deps)),
                destructive = true,
            ) { askCleanDeps(item) }
        }
        rows += Sheet.Row(
            getString(R.string.projects_delete),
            deleteBlock?.let { blockReason(it, name, renaming = false) },
            enabled = deleteBlock == null,
            destructive = true,
        ) { askDelete(item) }
        sheet.rows(rows).show()
    }

    /**
     * Painel com as conversas do Claude do projeto (até 50, as mais recentes). Tocar abre a conversa
     * no chat. Por último, apaga as paradas há mais de 30 dias (se o projeto estiver com trabalho aberto, a linha fica desligada).
     */
    private fun openConversations(item: Workspace.Folder, convs: ProjectSize.Conversations) {
        val name = item.name
        thread(name = "jangada-projects-history") {
            val found = runCatching { ChatHistory.list(linux, Workspace.path(name)) }.getOrElse { emptyList() }
            runOnUiThread { if (!isDestroyed) showConversations(item, convs, found) }
        }
    }

    private fun showConversations(item: Workspace.Folder, convs: ProjectSize.Conversations, found: List<ChatHistory.Conversation>) {
        val name = item.name
        val rows = found.map { conversation ->
            Sheet.Row(conversation.title, DateUtils.getRelativeTimeSpanString(conversation.updatedAt)) {
                startActivity(
                    Intent(this@ProjectsActivity, ChatActivity::class.java)
                        .putExtra(ChatActivity.EXTRA_AGENT_ID, TerminalService.CLAUDE)
                        .putExtra(ChatActivity.EXTRA_FOLDER, name)
                        .putExtra(ChatActivity.EXTRA_RESUME, conversation.id),
                )
            }
        }.toMutableList()
        val busy = name in openFolders()
        if (found.isNotEmpty()) {
            val busyNote = if (busy) getString(R.string.projects_conversations_clean_open) else null
            rows += Sheet.Row(getString(R.string.projects_conversations_one), busyNote, enabled = !busy, destructive = true) {
                showDeleteOne(item, found)
            }
            rows += Sheet.Row(
                resources.getQuantityString(R.plurals.projects_conversations_all, found.size, found.size), busyNote,
                enabled = !busy, destructive = true,
            ) { confirmDeleteAll(item, found.map { it.id }) }
        }
        val stale = ProjectSize.staleIds(convs.all, System.currentTimeMillis())
        if (stale.isNotEmpty()) {
            rows += Sheet.Row(
                resources.getQuantityString(R.plurals.projects_conversations_clean, stale.size, stale.size),
                if (busy) getString(R.string.projects_conversations_clean_open) else null,
                enabled = !busy,
                destructive = true,
            ) { confirmClean(item, stale) }
        }
        val sheet = Sheet(this).title(getString(R.string.projects_conversations_title, name))
        if (found.isEmpty()) sheet.message(getString(R.string.chat_history_empty))
        sheet.rows(rows).show()
    }

    /** Lista para apagar uma conversa: tocar numa pede a confirmação. */
    private fun showDeleteOne(item: Workspace.Folder, found: List<ChatHistory.Conversation>) {
        val rows = found.map { conversation ->
            Sheet.Row(conversation.title, DateUtils.getRelativeTimeSpanString(conversation.updatedAt), destructive = true) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.chat_delete_title)
                    .setMessage(conversation.title)
                    .setPositiveButton(R.string.chat_delete) { _, _ -> cleanStale(item, listOf(conversation.id)) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        Sheet(this).title(getString(R.string.chat_history_delete_title)).rows(rows).show()
    }

    /** Pede a confirmação de apagar todas as conversas do projeto. */
    private fun confirmDeleteAll(item: Workspace.Folder, ids: List<String>) {
        AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.projects_conversations_all, ids.size, ids.size))
            .setMessage(resources.getQuantityString(R.plurals.projects_conversations_all_confirm, ids.size, ids.size))
            .setPositiveButton(R.string.projects_conversations_clean_ok) { _, _ -> cleanStale(item, ids) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Pede a confirmação de apagar as conversas paradas. */
    private fun confirmClean(item: Workspace.Folder, stale: List<String>) {
        AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.projects_conversations_clean, stale.size, stale.size))
            .setMessage(resources.getQuantityString(R.plurals.projects_conversations_clean_confirm, stale.size, stale.size))
            .setPositiveButton(R.string.projects_conversations_clean_ok) { _, _ -> cleanStale(item, stale) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Apaga as conversas (uma a uma, fora da principal) e avisa se alguma ficou. */
    private fun cleanStale(item: Workspace.Folder, stale: List<String>) {
        val name = item.name
        // Um chat pode ter aberto no projeto depois do painel: não apaga conversa de trabalho aberto.
        if (name in openFolders() || service?.folderBusy(name) == true) {
            Toast.makeText(applicationContext, R.string.projects_conversations_clean_open, Toast.LENGTH_LONG).show()
            return
        }
        thread(name = "jangada-projects-history") {
            var failed = 0
            for (id in stale) {
                // ChatHistory.delete não lança quando o arquivo não sai: confere se ainda existe.
                val gone = runCatching {
                    ChatHistory.delete(linux, Workspace.path(name), id)
                    !File(ChatHistory.dir(linux, Workspace.path(name)), "$id.jsonl").exists()
                }.getOrDefault(false)
                if (!gone) failed++
            }
            runOnUiThread {
                if (!isDestroyed) {
                    Toast.makeText(
                        applicationContext,
                        if (failed == 0) R.string.chat_history_deleted_all else R.string.projects_conversations_clean_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /** Texto da linha desligada: o motivo da regra (trabalhos isolados listados, quando há). */
    private fun blockReason(refusal: ProjectOps.Refusal, name: String, renaming: Boolean): String {
        val dependents = ProjectOps.dependentsOf(name, listing).joinToString(", ")
        return when (refusal) {
            ProjectOps.Refusal.IS_WORKTREE -> getString(R.string.projects_rename_worktree)
            ProjectOps.Refusal.HAS_WORKTREES ->
                if (renaming) getString(R.string.projects_rename_has_worktrees, dependents)
                else getString(R.string.projects_delete_has_worktrees, dependents)
            else -> getString(R.string.projects_gone)
        }
    }

    /** Acha as pastas de dependências do projeto (fora da principal) e pede a confirmação de apagar. */
    private fun askCleanDeps(item: Workspace.Folder) {
        val name = item.name
        if (name in openFolders() || service?.folderBusy(name) == true) {
            toast(R.string.projects_conversations_clean_open)
            return
        }
        val root = Workspace.hostPath(linux.rootfs, Workspace.path(name)).toPath()
        thread(name = "jangada-projects-info") {
            val found = runCatching { ProjectSize.depDirs(root) }.getOrDefault(emptyList())
            runOnUiThread { if (!isDestroyed) showCleanDeps(item, root, found) }
        }
    }

    private fun showCleanDeps(item: Workspace.Folder, root: Path, found: List<Path>) {
        if (found.isEmpty()) {
            toast(R.string.projects_deps_none)
            return
        }
        val shown = found.take(15).joinToString("\n") { root.relativize(it).toString() } +
            if (found.size > 15) "\n\u2026" else ""
        AlertDialog.Builder(this)
            .setTitle(R.string.projects_deps_clean)
            .setMessage(getString(R.string.projects_deps_confirm, shown))
            .setPositiveButton(R.string.projects_conversations_clean_ok) { _, _ -> cleanDeps(item, found) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Apaga as pastas de dependências (uma a uma, fora da principal) e remede o projeto. */
    private fun cleanDeps(item: Workspace.Folder, found: List<Path>) {
        val name = item.name
        // Um chat pode ter aberto no projeto depois do aviso: não apaga com trabalho aberto.
        if (name in openFolders() || service?.folderBusy(name) == true) {
            toast(R.string.projects_conversations_clean_open)
            return
        }
        thread(name = "jangada-projects-info") {
            var failed = 0
            for (dir in found) {
                // Fs.deleteTree não segue links; conta como falha se a pasta continuar existindo.
                val gone = runCatching { Fs.deleteTree(dir) }.isSuccess && !Fs.exists(dir)
                if (!gone) failed++
            }
            runOnUiThread {
                if (!isDestroyed) {
                    toast(if (failed == 0) R.string.projects_deps_cleaned else R.string.projects_deps_clean_failed)
                    sizes.remove(name)
                    reload()
                }
            }
        }
    }

    /** Pede a confirmação de excluir. Os dados (arquivos sem commit, conversas do Claude) são lidos fora da principal. */
    private fun askDelete(item: Workspace.Folder) {
        if (service == null) return noService()
        thread(name = "jangada-projects-info") {
            val dirty = dirtyCount(item)
            val history = runCatching { Projects.claudeHistoryDir(linux, item.name) != null }.getOrDefault(false)
            runOnUiThread { if (!isDestroyed) showDelete(item, dirty, history) }
        }
    }

    /** Arquivos sem commit da pasta (0 se não é repositório ou o git não respondeu). Fora da principal. */
    private fun dirtyCount(item: Workspace.Folder): Int {
        if (item.branch == null) return 0
        return runCatching {
            val status = Git.status(linux, Workspace.path(item.name))
            if (status.code == 0) Git.parseStatus(status.output).size else 0
        }.getOrDefault(0)
    }

    private fun showDelete(item: Workspace.Folder, dirty: Int, history: Boolean) {
        val name = item.name
        // Num trabalho isolado, a branch que o app criou (jangada/...) também é apagada: a tela diz qual.
        val branch = item.branch?.takeIf { item.worktreeOf != null && it.startsWith(WORKTREE_BRANCH) }
        val explain = label(
            if (branch != null) getString(R.string.projects_delete_worktree, branch)
            else getString(R.string.projects_delete_message),
            14f,
            R.color.setup_muted_text,
            top = 0,
        )
        val field = EditText(this).apply {
            hint = getString(R.string.projects_confirm_hint)
            setSingleLine(true)
        }
        val historyBox = CheckBox(this).apply {
            text = getString(R.string.projects_delete_history)
            isChecked = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(explain)
            if (dirty > 0) {
                addView(label(resources.getQuantityString(R.plurals.projects_dirty_files, dirty, dirty), 14f, R.color.setup_error_text, top = 8))
            }
            addView(field)
            if (history) addView(historyBox)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.projects_delete_title, name))
            .setView(content)
            .setPositiveButton(R.string.projects_delete_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        // O Excluir só liga com o nome exato digitado; o clique é ligado depois de mostrar o diálogo.
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.isEnabled = false
            field.doAfterTextChanged { ok.isEnabled = ProjectOps.confirmMatches(it?.toString().orEmpty(), name) }
            ok.setOnClickListener {
                val wantsHistory = history && historyBox.isChecked
                dialog.dismiss()
                startDelete(item, wantsHistory)
            }
        }
        dialog.show()
    }

    /** Fecha os chats da pasta e exclui o projeto. A regra é conferida de novo no disco (a lista pode ter mudado). */
    private fun startDelete(item: Workspace.Folder, alsoHistory: Boolean) {
        val svc = service ?: return noService()
        val name = item.name
        val started = closeAndRun(
            svc,
            name,
            work = {
                val now = Workspace.list(linux)
                val fresh = now.firstOrNull { it.name == name }
                if (fresh == null || ProjectOps.deleteRefusal(name, now) != null) {
                    Removal(Projects.Deleted.Failed(""), historyFailed = false)
                } else {
                    val deleted = Projects.delete(linux, fresh)
                    val historyFailed = alsoHistory && deleted is Projects.Deleted.Ok && !Projects.deleteClaudeHistory(linux, name)
                    Removal(deleted, historyFailed)
                }
            },
            then = { res ->
                val removal = res.getOrNull() ?: Removal(Projects.Deleted.Failed(""), historyFailed = false)
                val deleted = removal.deleted
                // Rascunhos e ajustes do projeto somem junto (mesmo com a tela fechada).
                if (deleted is Projects.Deleted.Ok) {
                    ChatDrafts(applicationContext).removeFolder(name)
                    AppSettings(applicationContext).forgetFolder(name)
                }
                if (!isDestroyed && !isFinishing) {
                    when (deleted) {
                        is Projects.Deleted.Ok -> toast(if (removal.historyFailed) R.string.projects_deleted_history_kept else R.string.projects_deleted)
                        is Projects.Deleted.Failed -> {
                            val output = deleted.output.ifBlank { getString(R.string.projects_delete_failed) }
                            AlertDialog.Builder(this)
                                .setTitle(R.string.projects_delete_failed_title)
                                .setMessage(output)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    }
                    reload()
                }
            },
        )
        if (!started) toast(R.string.projects_folder_busy) else toast(R.string.projects_closing)
    }

    /** Pede o novo nome. O erro aparece no campo a cada tecla; o botão só liga com um nome que serve. */
    private fun askRename(item: Workspace.Folder) {
        if (service == null) return noService()
        val name = item.name
        val field = EditText(this).apply {
            setText(name)
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(NAME_MAX))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label(getString(R.string.projects_rename_message), 14f, R.color.setup_muted_text, top = 0))
            addView(field)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.projects_rename_title, name))
            .setView(content)
            .setPositiveButton(R.string.projects_rename_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun check() {
                val typed = field.text.toString().trim()
                val refusal = ProjectOps.renameRefusal(name, typed, listing)
                ok.isEnabled = refusal == null
                field.error = when (refusal) {
                    ProjectOps.Refusal.INVALID_NAME -> if (typed.isEmpty()) null else getString(R.string.chat_folder_invalid)
                    ProjectOps.Refusal.EXISTS -> getString(R.string.project_exists)
                    else -> null
                }
            }
            field.doAfterTextChanged { check() }
            check()
            ok.setOnClickListener {
                val newName = field.text.toString().trim()
                dialog.dismiss()
                startRename(name, newName)
            }
        }
        dialog.show()
    }

    /** Fecha os chats da pasta e renomeia o projeto (com as conversas do Claude). */
    private fun startRename(old: String, new: String) {
        val svc = service ?: return noService()
        val started = closeAndRun(
            svc,
            old,
            work = {
                val now = Workspace.list(linux)
                when (ProjectOps.renameRefusal(old, new, now)) {
                    null -> Projects.rename(linux, old, new)
                    ProjectOps.Refusal.EXISTS -> Projects.Renamed.EXISTS
                    else -> Projects.Renamed.FAILED
                }
            },
            then = { res ->
                val outcome = res.getOrNull() ?: Projects.Renamed.FAILED
                // Rascunhos e ajustes seguem o nome novo (mesmo com a tela fechada).
                if (outcome == Projects.Renamed.OK || outcome == Projects.Renamed.HISTORY_KEPT) {
                    ChatDrafts(applicationContext).moveFolder(old, new)
                    AppSettings(applicationContext).moveFolder(old, new)
                }
                if (!isDestroyed && !isFinishing) {
                    toast(
                        when (outcome) {
                            Projects.Renamed.OK -> R.string.projects_renamed
                            Projects.Renamed.HISTORY_KEPT -> R.string.projects_renamed_history_kept
                            Projects.Renamed.EXISTS -> R.string.project_exists
                            Projects.Renamed.FAILED -> R.string.projects_rename_failed
                        },
                    )
                    reload()
                }
            },
        )
        if (!started) toast(R.string.projects_folder_busy) else toast(R.string.projects_closing)
    }

    /**
     * Pede o nome da cópia (sugere "<nome>-copia") e duplica pelo serviço, com o mesmo andamento
     * do clone (Cancelar, Em segundo plano). A cópia leva tudo, também o que não está no git.
     */
    private fun askCopy(item: Workspace.Folder) {
        if (service == null) return noService()
        val name = item.name
        val taken = listing.map { it.name }
        val field = EditText(this).apply {
            setText(ProjectOps.copyName(name, getString(R.string.projects_copy_suffix), taken).orEmpty())
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(NAME_MAX))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label(getString(R.string.projects_copy_message), 14f, R.color.setup_muted_text, top = 0))
            addView(field)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.projects_copy_title, name))
            .setView(content)
            .setPositiveButton(R.string.projects_copy_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun check() {
                val typed = field.text.toString().trim()
                val valid = Workspace.isValidName(typed)
                val exists = taken.any { it.equals(typed, ignoreCase = true) }
                ok.isEnabled = valid && !exists
                field.error = when {
                    typed.isEmpty() -> null
                    !valid -> getString(R.string.chat_folder_invalid)
                    exists -> getString(R.string.project_exists)
                    else -> null
                }
            }
            field.doAfterTextChanged { check() }
            check()
            ok.setOnClickListener {
                val newName = field.text.toString().trim()
                dialog.dismiss()
                startCopy(name, newName)
            }
        }
        dialog.show()
    }

    /** Começa a cópia no serviço e acompanha; terminando com a tela aberta, recarrega a lista. */
    private fun startCopy(source: String, folder: String) {
        val svc = service ?: return noService()
        val task = svc.runCopy(source, folder)
        if (task == null) {
            toast(R.string.projects_folder_busy)
            return
        }
        showCloneProgress(svc, task) { done ->
            if (done.state == TerminalService.CloneTask.State.OK) toast(R.string.projects_copied) else showCloneFailure(done)
            reload()
        }
    }

    /**
     * Fecha os chats e terminais de [folder] pelo serviço, roda [work] numa thread e entrega o
     * resultado a [then] na principal. A pasta fica ocupada até o fim, com sucesso ou falha
     * (releaseFolder é chamado sempre, mesmo com a tela fechada). Falso se a pasta já está ocupada:
     * nesse caso nada começa.
     */
    private fun <T> closeAndRun(svc: TerminalService, folder: String, work: () -> T, then: (Result<T>) -> Unit): Boolean =
        svc.closeFolder(folder) {
            thread(name = "jangada-projeto-op") {
                val result = runCatching(work)
                runOnUiThread {
                    svc.releaseFolder(folder)
                    then(result)
                }
            }
        }

    private fun noService() = toast(R.string.projects_no_service)

    private fun toast(message: Int) = Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false, top: Int = 4) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(top), 0, 0)
    }

    /** Resultado de excluir: o que o git/a pasta fizeram, e se as conversas do Claude ficaram. */
    private class Removal(val deleted: Projects.Deleted, val historyFailed: Boolean)

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        /** Mesmo limite de [Workspace] para o nome de uma pasta. */
        const val NAME_MAX = 64

        /** Prefixo das branches dos trabalhos isolados criados pelo app (ver Worktrees). */
        const val WORKTREE_BRANCH = "jangada/"
    }
}

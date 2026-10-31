package io.github.jonjonesbr.jangada

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.Git
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Workspace
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
    )

    private lateinit var linux: LinuxEnv
    /** Pasta do projeto ("" = a raiz) e o caminho dela dentro do Debian. */
    private var folder = ""
    private var cwd = ""
    private lateinit var subtitle: TextView
    private lateinit var summary: TextView
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
        if (status.code != 0) return State(false, branch, false, emptyList(), null)
        // Saída cortada: a última entrada pode ter vindo pela metade.
        val parsed = Git.parseStatus(status.output).let { if (status.cut) it.dropLast(1) else it }
        val hasHead = Git.run(linux, cwd, "rev-parse", "--verify", "-q", "HEAD").code == 0
        // Sem upstream o git erra: não é um problema a mostrar, só não há contagem.
        val ahead = Git.run(linux, cwd, "rev-list", "--count", "@{u}..HEAD")
            .takeIf { it.code == 0 }?.output?.trim()?.toIntOrNull()
        return State(true, branch, hasHead, parsed, ahead, status.cut)
    }

    /** Mostra a leitura: título, resumo, lista ou o aviso de vazio, e o campo de commit. */
    private fun show(read: State) {
        isRepo = read.isRepo
        hasHead = read.hasHead
        changes = read.changes
        askAgentButton.visibility = if (read.isRepo && changes.isNotEmpty() && agentId != null) View.VISIBLE else View.GONE
        val label = Workspace.label(folder, getString(R.string.chat_folder_root))
        subtitle.text = read.branch?.let { "$label · $it" } ?: label
        if (!read.isRepo) {
            summary.visibility = View.GONE
            list.visibility = View.GONE
            commitBar.visibility = View.GONE
            empty.text = getString(R.string.changes_not_repo)
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
                val labels = files.map { it.removePrefix(dir).ifEmpty { it } }.toMutableList()
                if (result.cut || all.size > files.size) labels += getString(R.string.changes_diff_cut)
                AlertDialog.Builder(this)
                    .setTitle(dir)
                    .setItems(labels.toTypedArray()) { _, which ->
                        files.getOrNull(which)?.let { showDiff(Git.Change(it, "??")) }
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
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
            setTextColor(getColor(R.color.setup_muted))
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
        line.startsWith("+++") || line.startsWith("---") -> R.color.setup_muted
        line.startsWith("+") -> R.color.setup_done
        line.startsWith("-") -> R.color.setup_error
        line.startsWith("@@") -> R.color.accent_text
        else -> R.color.setup_muted
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

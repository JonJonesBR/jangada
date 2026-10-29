package io.github.jonjonesbr.jangada

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import io.github.jonjonesbr.jangada.TerminalService.AgentTask
import io.github.jonjonesbr.jangada.agents.AgentCatalog
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.agents.Versions
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Tela inicial: o catálogo de agentes e as sessões em andamento.
 * Instalar e atualizar acontecem aqui mesmo, em segundo plano, com
 * progresso no cartão; o terminal só abre quando você toca em Abrir.
 */
class HomeActivity : ComponentActivity() {

    private lateinit var linux: LinuxEnv
    private lateinit var recipes: List<AgentRecipe>
    private lateinit var agentsList: LinearLayout
    private lateinit var sessionsList: LinearLayout
    private lateinit var runningTitle: TextView

    /** null = ainda verificando; senão, os comandos encontrados no Debian. */
    private var installed: Set<String>? = null
    /** Versão instalada por comando (chega depois do [installed]). */
    private val installedVersions = ConcurrentHashMap<String, String>()
    /** Versão mais nova publicada, por id do agente (quando há fonte). */
    private val latestVersions = ConcurrentHashMap<String, String>()
    private var service: TerminalService? = null

    private val prefs by lazy { getSharedPreferences("agents", MODE_PRIVATE) }
    private val simpleMode get() = AppSettings(this).simpleMode
    private var askedStorage = false
    private var allFilesOpened = false
    private var paused = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        linux = LinuxEnv(this)
        if (linux.isDeviceSupported && !linux.isReady) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_home)
        agentsList = findViewById(R.id.agents)
        sessionsList = findViewById(R.id.sessions)
        runningTitle = findViewById(R.id.running_title)
        recipes = AgentCatalog.load(this)

        findViewById<Button>(R.id.open_terminal).setOnClickListener { openTerminal() }
        findViewById<Button>(R.id.storage_allow).setOnClickListener { requestStorage() }
        findViewById<View>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        if (!linux.isDeviceSupported) findViewById<View>(R.id.unsupported).visibility = View.VISIBLE
        renderAgents()
        fetchLatestVersions()
    }

    override fun onStart() {
        super.onStart()
        if (!::agentsList.isInitialized) return
        bindService(Intent(this, TerminalService::class.java), connection, Context.BIND_AUTO_CREATE)
        refreshInstalled()
        renderStorage()
    }

    // ---- Arquivos do celular ----

    private fun renderStorage() {
        val granted = linux.hasStorageAccess
        findViewById<TextView>(R.id.storage_text).setText(if (granted) R.string.storage_ready else R.string.storage_ask)
        findViewById<View>(R.id.storage_allow).visibility = if (granted) View.GONE else View.VISIBLE
        findViewById<View>(R.id.storage_card).visibility = if (linux.isReady) View.VISIBLE else View.GONE
    }

    /**
     * Pede acesso à memória do celular: primeiro a permissão comum do
     * Android; se ela não liberar a escrita (algumas versões 11+), abre a
     * tela "Acesso a todos os arquivos" do Jangada. O resultado é conferido
     * quando a tela volta (onResume), sem depender de callbacks.
     */
    private fun requestStorage() {
        askedStorage = true
        allFilesOpened = false
        requestPermissions(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            REQUEST_STORAGE,
        )
        // Se o Android nem mostrar a pergunta (negada antes), segue sozinho.
        window.decorView.postDelayed({ if (askedStorage && !paused) afterStorageRequest() }, 800)
    }

    override fun onResume() {
        super.onResume()
        paused = false
        if (!::agentsList.isInitialized) return
        if (askedStorage) afterStorageRequest() else renderStorage()
        renderAgents()
    }

    override fun onPause() {
        paused = true
        super.onPause()
    }

    private fun afterStorageRequest() {
        if (linux.hasStorageAccess) {
            askedStorage = false
            runCatching { linux.refreshIntegration() }
            if (service?.sessions?.isNotEmpty() == true) {
                Toast.makeText(this, R.string.storage_new_sessions, Toast.LENGTH_LONG).show()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !allFilesOpened) {
            allFilesOpened = true // continua "askedStorage" para conferir na volta
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { startActivity(intent) }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
            return
        } else {
            askedStorage = false
            Toast.makeText(this, R.string.storage_denied, Toast.LENGTH_LONG).show()
        }
        renderStorage()
    }

    override fun onStop() {
        service?.let {
            it.onSessionsChanged = null
            it.onTasksChanged = null
            unbindService(connection)
        }
        service = null
        super.onStop()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as TerminalService.LocalBinder).service
            service = svc
            svc.onSessionsChanged = { renderSessions() }
            svc.onTasksChanged = { onTasksChanged() }
            renderSessions()
            renderAgents()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    // ---- O que está instalado e em que versão ----

    /** Pergunta ao Debian quais agentes existem e em que versão (em segundo plano). */
    private fun refreshInstalled() {
        if (!linux.isReady) return
        thread(name = "jangada-detect") {
            val found = HashSet<String>()
            var published = false
            runCatching {
                linux.runBash(AgentCatalog.detectScript(recipes)) { line ->
                    when {
                        line.startsWith(FOUND) -> found += line.removePrefix(FOUND).trim()
                        line.startsWith(VERSION) -> {
                            val rest = line.removePrefix(VERSION)
                            val command = rest.substringBefore(' ')
                            Versions.parse(rest.substringAfter(' ', ""))?.let { installedVersions[command] = it }
                            if (!published) {
                                published = true
                                publishInstalled(found.toSet())
                            } else {
                                runOnUiThread { if (!isDestroyed) renderAgents() }
                            }
                        }
                    }
                }
            }
            publishInstalled(found.toSet())
        }
    }

    private fun publishInstalled(found: Set<String>) = runOnUiThread {
        if (isDestroyed) return@runOnUiThread
        installed = found
        renderAgents()
    }

    /** Busca no npm a versão mais nova de cada agente que tem essa fonte. */
    private fun fetchLatestVersions() {
        thread(name = "jangada-latest") {
            for (recipe in recipes) {
                val pkg = recipe.npm ?: continue
                Versions.latestOnNpm(pkg)?.let { latestVersions[recipe.id] = it }
            }
            runOnUiThread { if (!isDestroyed) renderAgents() }
        }
    }

    private fun onTasksChanged() {
        val svc = service ?: return
        // Tarefas que acabaram de dar certo: anota a data e confere de novo o que está instalado.
        var finished = false
        for ((id, task) in svc.tasks) {
            if (task.state == AgentTask.State.OK && !task.acknowledged) {
                task.acknowledged = true
                prefs.edit().putLong(updatedKey(id), System.currentTimeMillis()).apply()
                finished = true
            }
        }
        if (finished) refreshInstalled()
        renderAgents()
    }

    // ---- Cartões ----

    private fun renderAgents() {
        agentsList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (recipe in recipes) agentsList.addView(buildCard(inflater, recipe))
    }

    private fun buildCard(inflater: LayoutInflater, recipe: AgentRecipe): View {
        val card = inflater.inflate(R.layout.item_agent, agentsList, false)
        card.findViewById<TextView>(R.id.name).text = recipe.name
        card.findViewById<TextView>(R.id.vendor).text = recipe.vendor
        card.findViewById<TextView>(R.id.description).text = recipe.description()
        recipe.note()?.let {
            card.findViewById<TextView>(R.id.note).apply { text = it; visibility = View.VISIBLE }
        }

        val status = card.findViewById<TextView>(R.id.status)
        val primary = card.findViewById<Button>(R.id.primary)
        val update = card.findViewById<Button>(R.id.update)
        val progress = card.findViewById<ProgressBar>(R.id.task_progress)
        val taskLine = card.findViewById<TextView>(R.id.task_line)
        val taskError = card.findViewById<TextView>(R.id.task_error)
        val taskActions = card.findViewById<View>(R.id.task_actions)

        val known = installed
        val isInstalled = known?.contains(recipe.command) == true
        val version = installedVersions[recipe.command]
        val latest = latestVersions[recipe.id]
        val task = service?.tasks?.get(recipe.id)
        val enabled = known != null && linux.isDeviceSupported

        // Situação mostrada na etiqueta do cartão.
        when {
            task?.state == AgentTask.State.RUNNING ->
                status.setText(if (task.updating) R.string.agent_updating else R.string.agent_installing)
            known == null -> status.setText(R.string.agent_checking)
            !isInstalled -> status.setText(R.string.agent_not_installed)
            version != null -> status.text = getString(R.string.agent_version, version)
            else -> status.setText(R.string.agent_installed)
        }
        status.setTextColor(getColor(if (isInstalled) R.color.setup_done else R.color.setup_muted))

        // Tarefa em andamento: barra de progresso e a última linha da saída.
        if (task?.state == AgentTask.State.RUNNING) {
            progress.visibility = View.VISIBLE
            taskLine.visibility = View.VISIBLE
            taskLine.text = task.lastLine
            primary.visibility = View.GONE
            update.visibility = View.GONE
        } else {
            // Agentes com chat: no Modo Simples o botão principal é "Conversar" (chat)
            // e o terminal fica como alternativa; no Modo Pro, o contrário.
            val chatFirst = recipe.chat && simpleMode
            primary.setText(
                when {
                    !isInstalled -> R.string.agent_install
                    chatFirst -> R.string.agent_talk
                    else -> R.string.agent_open
                },
            )
            primary.isEnabled = enabled
            primary.alpha = if (enabled) 1f else 0.5f
            primary.setOnClickListener {
                when {
                    !isInstalled -> startTask(recipe, updating = false)
                    chatFirst -> openChat()
                    else -> openAgent(recipe)
                }
            }
            val alternative = card.findViewById<Button>(R.id.alternative)
            if (isInstalled && recipe.chat) {
                alternative.visibility = View.VISIBLE
                alternative.setText(if (chatFirst) R.string.agent_terminal else R.string.agent_chat)
                alternative.setOnClickListener { if (chatFirst) openAgent(recipe) else openChat() }
            }

            val updateLabel = updateLabel(recipe, isInstalled, version, latest)
            if (updateLabel != null && enabled) {
                update.visibility = View.VISIBLE
                update.text = updateLabel
                update.setOnClickListener { startTask(recipe, updating = true) }
            }
        }

        // Tarefa que falhou: explica e oferece as saídas.
        if (task?.state == AgentTask.State.FAILED && (task.updating || !isInstalled)) {
            taskError.visibility = View.VISIBLE
            taskError.setText(if (task.updating) R.string.task_update_failed else R.string.task_install_failed)
            taskActions.visibility = View.VISIBLE
            card.findViewById<Button>(R.id.task_retry).setOnClickListener { startTask(recipe, task.updating) }
            card.findViewById<Button>(R.id.task_terminal).setOnClickListener {
                openTerminal(recipe.name, recipe.installScript(task.updating))
            }
            card.findViewById<Button>(R.id.task_details).setOnClickListener { showLog(recipe, task) }
        }

        val site = card.findViewById<TextView>(R.id.site)
        val homepage = recipe.homepage
        if (homepage == null) site.visibility = View.GONE
        else site.setOnClickListener { openUrl(homepage) }
        return card
    }

    /**
     * Quando mostrar "Atualizar":
     * - com fonte de versão (npm): só se existir uma versão mais nova;
     * - sem fonte: só se não foi atualizado pelo app na última semana.
     */
    private fun updateLabel(recipe: AgentRecipe, isInstalled: Boolean, version: String?, latest: String?): String? {
        if (!isInstalled) return null
        if (recipe.npm != null) {
            if (version == null || latest == null) return null
            return if (Versions.isNewer(latest, version)) getString(R.string.agent_update_to, latest) else null
        }
        val updatedAt = prefs.getLong(updatedKey(recipe.id), 0L)
        return if (System.currentTimeMillis() - updatedAt > UPDATE_QUIET_MS) getString(R.string.agent_update) else null
    }

    private fun startTask(recipe: AgentRecipe, updating: Boolean) {
        val svc = service ?: return
        startService(Intent(this, TerminalService::class.java))
        svc.runAgentTask(recipe, updating)
    }

    private fun showLog(recipe: AgentRecipe, task: AgentTask) {
        val text = TextView(this).apply {
            this.text = task.logText().ifEmpty { "—" }
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle(recipe.name)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(R.string.setup_copy_log) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(recipe.name, task.logText()))
                Toast.makeText(this, R.string.setup_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Sessões ----

    private fun renderSessions() {
        sessionsList.removeAllViews()
        val sessions = service?.sessions.orEmpty()
        val chat = service?.chat
        runningTitle.visibility = if (sessions.isEmpty() && chat == null) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        if (chat != null) {
            val row = inflater.inflate(R.layout.item_session, sessionsList, false)
            row.findViewById<TextView>(R.id.session_title).text =
                getString(R.string.chat_title) + " · " + getString(R.string.chat_session)
            row.setOnClickListener { openChat() }
            row.findViewById<Button>(R.id.session_close).setOnClickListener { service?.closeChat() }
            sessionsList.addView(row)
        }
        for (session in sessions) {
            val row = inflater.inflate(R.layout.item_session, sessionsList, false)
            row.findViewById<TextView>(R.id.session_title).text = session.mSessionName ?: TerminalService.DEFAULT_TITLE
            row.setOnClickListener { showSession(session.mHandle) }
            row.findViewById<Button>(R.id.session_close).setOnClickListener {
                service?.closeSession(session.mHandle)
            }
            sessionsList.addView(row)
        }
    }

    /** Abrir um agente que já tem sessão aberta volta para ela, em vez de abrir outra. */
    private fun openAgent(recipe: AgentRecipe) {
        val existing = service?.sessions?.lastOrNull { it.mSessionName == recipe.name }
        if (existing != null) showSession(existing.mHandle)
        else openTerminal(recipe.name, recipe.launchScript(), agent = recipe.id)
    }

    private fun openChat() {
        startActivity(Intent(this, ChatActivity::class.java))
    }

    private fun showSession(handle: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, handle))
    }

    private fun openTerminal(title: String? = null, script: String? = null, agent: String? = null) {
        val intent = Intent(this, MainActivity::class.java)
        if (script != null) {
            intent.putExtra(MainActivity.EXTRA_TITLE, title)
            intent.putExtra(MainActivity.EXTRA_SCRIPT, script)
            if (agent != null) intent.putExtra(MainActivity.EXTRA_AGENT, agent)
        } else {
            intent.putExtra(MainActivity.EXTRA_NEW_SHELL, true)
        }
        startActivity(intent)
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun updatedKey(id: String) = "updated_$id"

    private companion object {
        const val FOUND = "JANGADA_FOUND "
        const val VERSION = "JANGADA_VERSION "
        const val UPDATE_QUIET_MS = 7L * 24 * 60 * 60 * 1000
        const val REQUEST_STORAGE = 42
    }
}

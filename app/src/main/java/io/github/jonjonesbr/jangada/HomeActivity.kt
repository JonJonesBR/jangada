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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import io.github.jonjonesbr.jangada.TerminalService.AgentTask
import io.github.jonjonesbr.jangada.agents.AgentCatalog
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.agents.Versions
import io.github.jonjonesbr.jangada.chat.ChatState
import io.github.jonjonesbr.jangada.chat.chatState
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Workspace
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Tela inicial: o catálogo de agentes e as sessões em andamento.
 * Instalar e atualizar acontecem aqui mesmo, em segundo plano, com
 * progresso no cartão; o terminal só abre quando você toca em Abrir.
 */
class HomeActivity : JangadaActivity() {

    private lateinit var linux: LinuxEnv
    private lateinit var recipes: List<AgentRecipe>
    private lateinit var agentsList: LinearLayout
    private lateinit var sessionsList: LinearLayout
    private lateinit var runningTitle: TextView
    /** Fileira de filtros da lista de trabalhos (aparece com 4 ou mais linhas). */
    private lateinit var filterRow: View
    private lateinit var filterChips: LinearLayout
    /** Filtro da lista de trabalhos; não é salvo. */
    private var jobFilter: JobFilter = JobFilter.All
    /** Cartão de boas-vindas; null depois de tirado da tela. */
    private var welcomeCard: View? = null

    /** null = ainda verificando; senão, os comandos encontrados no Debian. */
    private var installed: Set<String>? = null
    /** Versão instalada por comando. Trocada inteira na thread principal a cada rodada bem-sucedida. */
    private var installedVersions: Map<String, String> = emptyMap()
    /** Espaço ocupado por cada agente instalado, em KB (por comando). Trocado como [installedVersions]. */
    private var installedSizes: Map<String, Long> = emptyMap()
    /** Versão mais nova publicada, por id do agente (quando há fonte). Fica no cache do processo. */
    private val latestVersions = LatestVersionCache.versions
    private var service: TerminalService? = null
    /** true desde o bindService até o unbindService (feitos em onStart e onStop). */
    private var bound = false

    /** Cartão de cada agente (id -> view), para mexer só no que mudou. */
    private val cards = HashMap<String, View>()
    /** Estado de cada tarefa na última vez que os cartões foram desenhados. */
    private val drawnTasks = HashMap<String, TaskMark>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var renderScheduled = false
    private val renderLater = Runnable {
        renderScheduled = false
        if (!isDestroyed) renderAgents()
    }
    /** Detecção do Debian em andamento; [detectAgain] pede mais uma rodada quando ela termina. */
    private val detecting = AtomicBoolean(false)
    private val detectAgain = AtomicBoolean(false)

    private val prefs by lazy { AgentPrefs.prefs(this) }
    private val sessionsListener: () -> Unit = { renderSessions() }
    /** Redesenha a lista de trabalhos enquanto há chat aberto (o estado muda sem aviso). */
    private val jobsTicker = Runnable { renderSessions() }
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
        filterRow = findViewById(R.id.job_filter)
        filterChips = findViewById(R.id.job_filter_chips)
        recipes = AgentCatalog.load(this)

        findViewById<Button>(R.id.open_terminal).setOnClickListener { openTerminal() }
        findViewById<Button>(R.id.storage_allow).setOnClickListener { requestStorage() }
        findViewById<View>(R.id.open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // Boas-vindas: só depois de instalar o Linux e até o usuário tocar em "Entendi".
        welcomeCard = findViewById<View>(R.id.welcome_card)
        findViewById<Button>(R.id.welcome_dismiss).setOnClickListener {
            AppSettings(this).welcomeDismissed = true
            removeWelcome()
        }
        if (!linux.isReady || AppSettings(this).welcomeDismissed) removeWelcome()
        if (!linux.isDeviceSupported) findViewById<View>(R.id.unsupported).visibility = View.VISIBLE
        renderAgents()
        fetchLatestVersions()
        showLastCrash()
    }

    /** Tira o cartão de boas-vindas da tela, sem recriar a tela. */
    private fun removeWelcome() {
        val card = welcomeCard ?: return
        (card.parent as? ViewGroup)?.removeView(card)
        welcomeCard = null
    }

    /** Se o app fechou sozinho da última vez, mostra os detalhes para copiar. */
    private fun showLastCrash() {
        val file = JangadaApp.crashFile(this)
        if (!file.isFile) return
        val details = runCatching { file.readText() }.getOrDefault("")
        file.delete()
        if (details.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_title)
            .setMessage(getString(R.string.crash_message) + "\n\n" + details.lineSequence().take(6).joinToString("\n"))
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Jangada crash", details))
                Toast.makeText(this, R.string.setup_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (!::agentsList.isInitialized) return
        bound = bindService(Intent(this, TerminalService::class.java), connection, Context.BIND_AUTO_CREATE)
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
        service?.homeScreenVisible = true
        if (!::agentsList.isInitialized) return
        if (askedStorage) afterStorageRequest() else renderStorage()
        // Sem serviço, não há o que mudar nos cartões: onServiceConnected desenha quando ligar.
        if (service != null) {
            renderAgents()
            renderSessions()
        }
    }

    override fun onPause() {
        paused = true
        service?.homeScreenVisible = false
        mainHandler.removeCallbacks(jobsTicker)
        super.onPause()
    }

    private fun afterStorageRequest() {
        if (linux.hasStorageAccess) {
            askedStorage = false
            thread(name = "jangada-integration") { runCatching { linux.refreshIntegration() } }
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
            it.removeSessionsListener(sessionsListener)
            it.onTasksChanged = null
        }
        service = null
        // Desfaz o bind sempre que foi feito, mesmo se a conexão ainda não chegou.
        if (bound) {
            bound = false
            unbindService(connection)
        }
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(renderLater)
        mainHandler.removeCallbacks(jobsTicker)
        super.onDestroy()
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
            // Se a tela já estava em primeiro plano quando o serviço chegou, o onResume não pôde avisar.
            if (!paused) svc.homeScreenVisible = true
            svc.addSessionsListener(sessionsListener)
            svc.onTasksChanged = { onTasksChanged() }
            renderSessions()
            renderAgents()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    // ---- O que está instalado e em que versão ----

    /**
     * Pergunta ao Debian quais agentes existem e em que versão (em segundo plano).
     * Só uma detecção roda por vez; se pedirem outra durante a atual, ela roda de novo ao terminar.
     */
    private fun refreshInstalled() {
        if (!linux.isReady) return
        // O pedido é anotado antes de checar, para não se perder se a rodada atual acabar agora.
        detectAgain.set(true)
        if (detecting.getAndSet(true)) return
        thread(name = "jangada-detect") {
            while (true) {
                detectAgain.set(false)
                try {
                    detectOnce()
                } finally {
                    detecting.set(false)
                }
                // Pediram outra enquanto esta terminava: assume a vez e roda mais uma.
                if (!detectAgain.get() || !detecting.compareAndSet(false, true)) break
            }
        }
    }

    /**
     * A detecção falhou: mantém o que já está na tela. Se ainda não havia nada (primeira rodada),
     * usa a última lista gravada, para os cartões não ficarem em "Verificando" para sempre.
     */
    private fun keepLastKnown() {
        runOnUiThread {
            if (isDestroyed || installed != null) return@runOnUiThread
            installed = AgentPrefs.installedCommands(this) ?: emptySet()
            renderAgents()
        }
    }

    /**
     * Uma rodada da detecção: manda o script ao Debian e, só se ele terminar com sucesso,
     * publica o resultado. Se o proot falhar, mantém a lista e as prefs da rodada anterior.
     */
    private fun detectOnce() {
        // Mapas próprios da rodada: só substituem os atuais no fim, se ela deu certo.
        val found = HashSet<String>()
        val versions = HashMap<String, String>()
        val sizes = HashMap<String, Long>()
        val code = runCatching {
            linux.runBash(AgentCatalog.detectScript(recipes)) { line ->
                when {
                    line.startsWith(FOUND) -> found += line.removePrefix(FOUND).trim()
                    line.startsWith(SIZE) -> {
                        val parts = line.removePrefix(SIZE).trim().split(' ')
                        parts.getOrNull(1)?.toLongOrNull()?.let { sizes[parts[0]] = it }
                    }
                    line.startsWith(VERSION) -> {
                        val rest = line.removePrefix(VERSION)
                        val command = rest.substringBefore(' ')
                        Versions.parse(rest.substringAfter(' ', ""))?.let { versions[command] = it }
                    }
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "detecção dos agentes falhou", error)
            keepLastKnown()
            return
        }
        if (code != 0) {
            Log.w(TAG, "detecção dos agentes terminou com código $code")
            keepLastKnown()
            return
        }
        // Guarda os comandos instalados: a ShareActivity filtra os agentes por eles, sem precisar do Debian.
        AgentPrefs.saveInstalledCommands(this, found)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            installed = found
            installedVersions = versions
            installedSizes = sizes
            renderAgents()
        }
    }

    /**
     * Busca no npm a versão mais nova de cada agente que tem essa fonte.
     * Só consulta se o cache expirou; se nenhuma consulta der certo (sem rede), não marca o cache,
     * para tentar de novo na próxima vez.
     */
    private fun fetchLatestVersions() {
        if (!LatestVersionCache.expired(SystemClock.elapsedRealtime())) return
        thread(name = "jangada-latest") {
            var fetched = 0
            for (recipe in recipes) {
                val pkg = recipe.npm ?: continue
                Versions.latestOnNpm(pkg)?.let {
                    latestVersions[recipe.id] = it
                    fetched++
                }
            }
            if (fetched > 0) LatestVersionCache.checkedAt = SystemClock.elapsedRealtime()
            runOnUiThread { scheduleRender() }
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
        // Mudou o estado de alguma tarefa: refaz os cartões. Senão, só a linha da saída.
        if (taskStatesChanged(svc)) renderAgents() else updateRunningLines(svc)
    }

    /** true se alguma tarefa começou, terminou ou foi cancelada desde o último desenho. */
    private fun taskStatesChanged(svc: TerminalService): Boolean {
        if (svc.tasks.size != drawnTasks.size) return true
        for ((id, task) in svc.tasks) {
            val drawn = drawnTasks[id] ?: return true
            if (drawn.task !== task || drawn.state != task.state || drawn.cancelled != task.cancelled) return true
        }
        return false
    }

    /** Atualiza só a última linha da saída nos cartões com instalação em andamento. */
    private fun updateRunningLines(svc: TerminalService) {
        for ((id, task) in svc.tasks) {
            if (task.state != AgentTask.State.RUNNING) continue
            cards[id]?.findViewById<TextView>(R.id.task_line)?.text = task.lastLine
        }
    }

    /** Agenda um redesenho dos cartões; chamadas seguidas dentro de 300 ms viram uma só. */
    private fun scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        mainHandler.postDelayed(renderLater, RENDER_GAP_MS)
    }

    // ---- Cartões ----

    private fun renderAgents() {
        agentsList.removeAllViews()
        cards.clear()
        // O estado é anotado antes de desenhar: se mudar no meio, a próxima chamada corrige.
        drawnTasks.clear()
        for ((id, task) in service?.tasks.orEmpty()) {
            drawnTasks[id] = TaskMark(task, task.state, task.cancelled)
        }
        val inflater = LayoutInflater.from(this)
        for (recipe in recipes) {
            val card = buildCard(inflater, recipe)
            cards[recipe.id] = card
            agentsList.addView(card)
        }
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
        val taskCancel = card.findViewById<Button>(R.id.task_cancel)

        val known = installed
        val isInstalled = known?.contains(recipe.command) == true
        val version = installedVersions[recipe.command]
        val latest = latestVersions[recipe.id]
        val task = service?.tasks?.get(recipe.id)
        val enabled = known != null && linux.isDeviceSupported

        // Situação mostrada na etiqueta do cartão.
        when {
            task?.state == AgentTask.State.RUNNING ->
                status.setText(
                    when {
                        task.uninstalling -> R.string.agent_uninstalling
                        task.updating -> R.string.agent_updating
                        else -> R.string.agent_installing
                    },
                )
            known == null -> status.setText(R.string.agent_checking)
            !isInstalled -> status.setText(R.string.agent_not_installed)
            version != null -> status.text = getString(R.string.agent_version, version)
            else -> status.setText(R.string.agent_installed)
        }
        // Espaço ocupado, ao lado da versão (ajuda a decidir o que desinstalar).
        installedSizes[recipe.command]?.takeIf { isInstalled && task?.state != AgentTask.State.RUNNING }?.let { kb ->
            status.text = "${status.text} · ${formatSize(kb)}"
        }
        status.setTextColor(getColor(if (isInstalled) R.color.setup_done else R.color.setup_muted))

        // Tarefa em andamento: barra de progresso e a última linha da saída.
        if (task?.state == AgentTask.State.RUNNING) {
            progress.visibility = View.VISIBLE
            taskLine.visibility = View.VISIBLE
            taskLine.text = task.lastLine
            primary.visibility = View.GONE
            update.visibility = View.GONE
            taskCancel.visibility = View.VISIBLE
            taskCancel.setOnClickListener { confirmCancel(recipe) }
        } else {
            // Agentes com chat: no Modo Simples o botão principal é "Conversar" (chat)
            // e o terminal fica como alternativa; no Modo Pro, o contrário.
            val chatFirst = recipe.hasChat && simpleMode
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
                    chatFirst -> openChat(recipe.id)
                    else -> openAgent(recipe)
                }
            }
            val alternative = card.findViewById<Button>(R.id.alternative)
            if (isInstalled && recipe.hasChat) {
                alternative.visibility = View.VISIBLE
                alternative.setText(if (chatFirst) R.string.agent_terminal else R.string.agent_chat)
                alternative.setOnClickListener { if (chatFirst) openAgent(recipe) else openChat(recipe.id) }
            }

            val updateLabel = updateLabel(recipe, isInstalled, version, latest)
            if (updateLabel != null && enabled) {
                update.visibility = View.VISIBLE
                update.text = updateLabel
                update.setOnClickListener { startTask(recipe, updating = true) }
            }
        }

        // Tarefa que falhou: explica e oferece as saídas. Cancelada pelo usuário não mostra erro.
        if (task?.state == AgentTask.State.FAILED && !task.cancelled && (task.uninstalling || task.updating || !isInstalled)) {
            taskError.visibility = View.VISIBLE
            taskError.setText(
                when {
                    task.uninstalling -> R.string.task_uninstall_failed
                    task.updating -> R.string.task_update_failed
                    else -> R.string.task_install_failed
                },
            )
            taskActions.visibility = View.VISIBLE
            card.findViewById<Button>(R.id.task_retry).setOnClickListener {
                if (task.uninstalling) startUninstall(recipe) else startTask(recipe, task.updating)
            }
            card.findViewById<Button>(R.id.task_terminal).setOnClickListener {
                openTerminal(recipe.name, if (task.uninstalling) recipe.uninstallScript() else recipe.installScript(this, task.updating))
            }
            card.findViewById<Button>(R.id.task_details).setOnClickListener { showLog(recipe, task) }
        }

        // ⋯ : site, reinstalar e desinstalar.
        card.findViewById<TextView>(R.id.site).setOnClickListener { showAgentMenu(recipe, isInstalled) }
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

    private fun showAgentMenu(recipe: AgentRecipe, isInstalled: Boolean) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        recipe.homepage?.let { url -> actions += getString(R.string.agent_site) to { openUrl(url) } }
        if (isInstalled) {
            actions += getString(R.string.agent_reinstall) to { startTask(recipe, updating = false) }
            actions += getString(R.string.agent_uninstall) to { confirmUninstall(recipe) }
        }
        if (actions.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(recipe.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun confirmUninstall(recipe: AgentRecipe) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.uninstall_title, recipe.name))
            .setMessage(R.string.uninstall_message)
            .setPositiveButton(R.string.agent_uninstall) { _, _ -> startUninstall(recipe) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmCancel(recipe: AgentRecipe) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.task_cancel_title, recipe.name))
            .setPositiveButton(R.string.task_cancel_confirm) { _, _ -> service?.cancelTask(recipe.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startUninstall(recipe: AgentRecipe) {
        val svc = service ?: return
        startService(Intent(this, TerminalService::class.java))
        svc.runAgentTask(recipe, updating = false, uninstalling = true)
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

    /** Filtro da lista de trabalhos: todos, só os que esperam você, ou uma pasta ("" = a raiz). */
    private sealed class JobFilter {
        object All : JobFilter()
        object Waiting : JobFilter()
        data class Folder(val name: String) : JobFilter()
    }

    /**
     * Lista de trabalhos: uma linha por chat aberto (agente, pasta e estado), depois os
     * terminais. Com 4 ou mais linhas, aparece a fileira de filtros acima.
     */
    private fun renderSessions() {
        sessionsList.removeAllViews()
        val svc = service
        val jobs = svc?.chats?.values?.toList().orEmpty().map { it to chatState(it.chat) }
        val sessions = svc?.sessions.orEmpty()
        runningTitle.visibility = if (jobs.isEmpty() && sessions.isEmpty()) View.GONE else View.VISIBLE
        val folders = (jobs.map { it.first.folder } + sessions.map { folderOf(it.mHandle) }).distinct()
        // Com 4 ou mais linhas, os filtros aparecem. Sem eles, volta a "Todos" (e a pasta que sumiu também).
        val filtering = jobs.size + sessions.size >= FILTER_MIN_ROWS
        val chosen = jobFilter
        if (!filtering || chosen is JobFilter.Folder && chosen.name !in folders) jobFilter = JobFilter.All
        filterRow.visibility = if (filtering) View.VISIBLE else View.GONE
        if (filtering) drawFilters(folders)
        val filter = jobFilter

        val inflater = LayoutInflater.from(this)
        for ((slot, state) in jobs) {
            if (!inFilter(filter, slot.folder, waiting = state == ChatState.WAITING)) continue
            val row = inflater.inflate(R.layout.item_session, sessionsList, false)
            val name = recipes.firstOrNull { it.id == slot.agentId }?.name ?: getString(R.string.chat_title)
            row.findViewById<TextView>(R.id.session_title).text = jobTitle(this, name, slot.folder, state)
            row.setOnClickListener { openChat(slot.agentId, slot.folder) }
            row.findViewById<Button>(R.id.session_close).setOnClickListener { service?.closeChat(slot.agentId, slot.folder) }
            sessionsList.addView(row)
        }
        for (session in sessions) {
            // Terminal nunca entra em "Esperando você".
            if (!inFilter(filter, folderOf(session.mHandle), waiting = false)) continue
            val row = inflater.inflate(R.layout.item_session, sessionsList, false)
            row.findViewById<TextView>(R.id.session_title).text =
                (session.mSessionName ?: TerminalService.DEFAULT_TITLE) + chatFolderSuffix(folderOf(session.mHandle))
            row.setOnClickListener { showSession(session.mHandle) }
            row.findViewById<Button>(R.id.session_close).setOnClickListener {
                service?.closeSession(session.mHandle)
            }
            sessionsList.addView(row)
        }
        // Com chat aberto, a lista se redesenha sozinha a cada 2 s (ver jobsTicker).
        mainHandler.removeCallbacks(jobsTicker)
        if (!paused && jobs.isNotEmpty()) mainHandler.postDelayed(jobsTicker, JOBS_REFRESH_MS)
    }

    /** Pasta de uma sessão de terminal ("" = a raiz). */
    private fun folderOf(handle: String): String = service?.sessionFolder?.get(handle).orEmpty()

    /** Se a linha (da pasta [folder]; [waiting] = espera você) entra no filtro escolhido. */
    private fun inFilter(filter: JobFilter, folder: String, waiting: Boolean): Boolean = when (filter) {
        JobFilter.All -> true
        JobFilter.Waiting -> waiting
        is JobFilter.Folder -> folder == filter.name
    }

    /** Fileira de filtros: Todos, Esperando você e uma pasta por chip. */
    private fun drawFilters(folders: List<String>) {
        filterChips.removeAllViews()
        addFilterChip(getString(R.string.home_filter_all), JobFilter.All)
        addFilterChip(getString(R.string.home_filter_waiting), JobFilter.Waiting)
        for (folder in folders) {
            addFilterChip(if (folder.isEmpty()) getString(R.string.home_filter_root) else folder, JobFilter.Folder(folder))
        }
    }

    /** Um chip do filtro: o escolhido fica em negrito; toque troca o filtro e redesenha. */
    private fun addFilterChip(label: String, value: JobFilter) {
        val chip = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(getColor(R.color.accent_text))
            typeface = if (jobFilter == value) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundResource(R.drawable.card_bg)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) }
            setOnClickListener {
                jobFilter = value
                renderSessions()
            }
        }
        filterChips.addView(chip)
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** " · pasta" depois do nome da sessão; vazio na raiz dos projetos. */
    private fun chatFolderSuffix(folder: String) = if (folder.isEmpty()) "" else " · $folder"

    /** Abrir um agente que já tem sessão aberta na pasta atual volta para ela, em vez de abrir outra. */
    private fun openAgent(recipe: AgentRecipe) {
        // A pasta do chat vale para o terminal; apagada ou trocada por link, volta para a raiz.
        val folder = Workspace.existing(linux.rootfs, AppSettings(this).chatFolder)
        val existing = service?.let { svc ->
            svc.sessions.lastOrNull { it.mSessionName == recipe.name && svc.sessionFolder[it.mHandle].orEmpty() == folder }
        }
        if (existing != null) showSession(existing.mHandle)
        else openTerminal(recipe.name, recipe.launchScript(Workspace.path(folder)), agent = recipe.id, folder = folder)
    }

    /** Abre a tela de chat de um agente; com [folder], a do trabalho dessa pasta (sem ela, a última usada). */
    private fun openChat(agentId: String, folder: String? = null) {
        val intent = Intent(this, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_AGENT_ID, agentId)
        if (folder != null) intent.putExtra(ChatActivity.EXTRA_FOLDER, folder)
        startActivity(intent)
    }

    private fun showSession(handle: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, handle))
    }

    private fun openTerminal(title: String? = null, script: String? = null, agent: String? = null, folder: String = "") {
        val intent = Intent(this, MainActivity::class.java)
        if (script != null) {
            intent.putExtra(MainActivity.EXTRA_TITLE, title)
            intent.putExtra(MainActivity.EXTRA_SCRIPT, script)
            intent.putExtra(MainActivity.EXTRA_FOLDER, folder)
            if (agent != null) intent.putExtra(MainActivity.EXTRA_AGENT, agent)
        } else {
            intent.putExtra(MainActivity.EXTRA_NEW_SHELL, true)
        }
        startActivity(intent)
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun formatSize(kb: Long): String =
        if (kb >= 1024 * 1024) String.format(java.util.Locale.getDefault(), "%.1f GB", kb / 1048576.0)
        else "${(kb + 512) / 1024} MB"

    private fun updatedKey(id: String) = "updated_$id"

    /** Estado de uma tarefa como foi desenhado no cartão. */
    private class TaskMark(val task: AgentTask, val state: AgentTask.State, val cancelled: Boolean)

    private companion object {
        const val TAG = "Jangada"
        const val FOUND = "JANGADA_FOUND "
        const val VERSION = "JANGADA_VERSION "
        const val SIZE = "JANGADA_SIZE "
        const val UPDATE_QUIET_MS = 7L * 24 * 60 * 60 * 1000
        const val RENDER_GAP_MS = 300L
        const val REQUEST_STORAGE = 42
        /** Com esta quantidade de linhas (trabalhos + terminais), aparecem os filtros. */
        const val FILTER_MIN_ROWS = 4
        /** Intervalo em que a lista de trabalhos se redesenha enquanto há chat aberto. */
        const val JOBS_REFRESH_MS = 2_000L
    }
}

/** O estado de um trabalho de chat, em texto ("Trabalhando", "Esperando você"...). */
internal fun stateText(context: Context, state: ChatState): String = context.getString(
    when (state) {
        ChatState.WORKING -> R.string.chat_state_working
        ChatState.WAITING -> R.string.chat_state_waiting
        ChatState.READY -> R.string.chat_state_ready
        ChatState.STOPPED -> R.string.chat_state_stopped
    },
)

/** Linha de um trabalho: agente, pasta (se não for a raiz) e estado. */
internal fun jobTitle(context: Context, agentName: String, folder: String, state: ChatState): String =
    agentName + (if (folder.isEmpty()) "" else " · $folder") + " · " + stateText(context, state)

/**
 * Versões mais novas do npm, guardadas na memória do processo por 1 hora,
 * para não consultar a rede a cada abertura da tela inicial.
 * [checkedAt] é o horário de [SystemClock.elapsedRealtime] da última consulta bem-sucedida (-1 = nunca).
 */
private object LatestVersionCache {
    private const val TTL_MS = 60L * 60 * 1000
    val versions = ConcurrentHashMap<String, String>()
    @Volatile var checkedAt = -1L

    /** true se nunca consultou ou se a última consulta tem mais de [TTL_MS]. */
    fun expired(now: Long): Boolean = checkedAt < 0 || now - checkedAt > TTL_MS
}

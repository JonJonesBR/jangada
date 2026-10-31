package io.github.jonjonesbr.jangada

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.github.jonjonesbr.jangada.agents.AgentCatalog
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.chat.ChatAttention
import io.github.jonjonesbr.jangada.chat.ChatHistory
import io.github.jonjonesbr.jangada.linux.LinkWatcher
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Setup
import io.github.jonjonesbr.jangada.linux.Workspace
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Mantém as sessões vivas fora da tela. Enquanto existe uma sessão, o
 * serviço fica em primeiro plano (com notificação fixa) e segura a CPU
 * acordada, então o Android não congela o app: downloads, logins e o
 * trabalho dos agentes continuam com a tela apagada ou em outro app.
 */
class TerminalService : Service() {

    inner class LocalBinder : Binder() {
        val service: TerminalService get() = this@TerminalService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    /** Serviço iniciado por startService (fica vivo mesmo sem tela ligada a ele). */
    private var started = false
    private var linkWatcher: LinkWatcher? = null
    /** Horários (elapsedRealtime) das aberturas diretas de link na janela; só a thread principal mexe. */
    private val directLinkOpens = ArrayDeque<Long>()
    /** Limpeza de restos antigos (onCreate); closeEverything espera por ela antes de apagar o Linux. */
    @Volatile private var legacyCleanup: Thread? = null
    /** Vigia a rede do celular, para regravar o DNS do Debian quando ela muda. */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** Último aviso de progresso da instalação do Linux (no máximo 1 por segundo). */
    private var lastSetupNotify = 0L

    /**
     * Telas visíveis agora. Ao virar true, o aviso correspondente some (o usuário
     * já viu). Mudadas só na thread principal.
     */
    var chatScreenVisible: Boolean = false
        set(value) {
            field = value
            if (value) notifications().cancel(NOTIFICATION_ATTENTION)
        }

    var homeScreenVisible: Boolean = false
        set(value) {
            field = value
            if (value) notifications().cancel(NOTIFICATION_TASK)
        }

    /** Acompanha a instalação do Linux (os eventos chegam na thread principal). */
    private val setupListener: (Setup.State) -> Unit = { state ->
        when (state) {
            is Setup.State.Running -> {
                val now = System.currentTimeMillis()
                if (now - lastSetupNotify >= 1_000) {
                    lastSetupNotify = now
                    updateNotification()
                }
            }
            is Setup.State.Done, is Setup.State.Failed -> finishSetup(state)
            else -> {}
        }
    }

    val sessions = mutableListOf<TerminalSession>()

    /** Cliente da tela que está aberta agora (null com o app fora da tela). */
    var screenClient: TerminalSessionClient? = null

    /** Abre um link pela tela aberta; devolve false se não há tela visível. */
    var screenLinkOpener: ((String) -> Boolean)? = null

    /** Chamado quando o usuário encerra tudo pela notificação. */
    var onExit: (() -> Unit)? = null

    /** Telas avisadas quando a lista de sessões (ou o chat) muda. */
    private val sessionListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun addSessionsListener(listener: () -> Unit) { sessionListeners += listener }
    fun removeSessionsListener(listener: () -> Unit) { sessionListeners -= listener }

    /** Instalação ou atualização de um agente rodando em segundo plano. */
    class AgentTask(val recipe: AgentRecipe, val updating: Boolean, val uninstalling: Boolean = false) {
        enum class State { RUNNING, OK, FAILED }

        @Volatile var state = State.RUNNING
        @Volatile var lastLine = ""
        /** A tela inicial já registrou o resultado desta tarefa. */
        @Volatile var acknowledged = false
        /** Processo do instalador em andamento (para cancelar). */
        @Volatile var process: Process? = null
        /** true quando o usuário cancelou; o estado final fica FAILED. */
        @Volatile var cancelled = false
        /** Thread que roda o instalador (o fechamento espera por ela). */
        @Volatile var worker: Thread? = null
        private val log = StringBuilder()

        fun append(line: String) = synchronized(log) {
            log.append(line).append('\n')
            if (log.length > MAX_LOG) log.delete(0, log.length - MAX_LOG)
        }

        fun logText(): String = synchronized(log) { log.toString() }

        private companion object {
            const val MAX_LOG = 200_000
        }
    }

    /** Tarefas por id do agente (a mais recente de cada um). */
    val tasks = ConcurrentHashMap<String, AgentTask>()

    /** Avisa a tela inicial quando alguma tarefa muda. */
    var onTasksChanged: (() -> Unit)? = null

    private var lastTaskPost = 0L

    /** Conversa do chat aberta (uma por vez) e de qual agente ela é. */
    var chat: io.github.jonjonesbr.jangada.chat.AgentChat? = null
        private set
    var chatAgentId: String? = null
        private set
    /** Pasta de trabalho do chat aberto (nome dentro de ~/projetos; "" = a raiz). */
    var chatFolder: String = ""
        private set

    // Os mapas por sessão são usados só na thread principal (sem sincronização).
    /** Agente de cada sessão de terminal (id da receita), para a troca Chat ⇄ Terminal. */
    val sessionAgent = HashMap<String, String>()
    /** Quando cada sessão de terminal começou (para achar a conversa criada nela). */
    val sessionStartedAt = HashMap<String, Long>()
    /** Conversa que a sessão de terminal retomou (claude --resume), se houver. */
    val sessionConversation = HashMap<String, String>()
    /** Pasta de trabalho de cada sessão de terminal (nome dentro de ~/projetos; "" = a raiz). */
    val sessionFolder = HashMap<String, String>()

    /**
     * Abre (ou devolve) o chat do agente [agentId], na pasta [folder] ("" = a raiz).
     * [continueLast] retoma a conversa mais recente; [resume] uma conversa específica;
     * [restart] força reabrir mesmo com o chat rodando. Outra pasta reabre o chat.
     */
    fun openChat(
        agentId: String = CLAUDE,
        folder: String = "",
        continueLast: Boolean = false,
        resume: String? = null,
        restart: Boolean = false,
    ): io.github.jonjonesbr.jangada.chat.AgentChat {
        val current = chat
        if (!restart && current != null && chatAgentId == agentId && chatFolder == folder &&
            (current.running || resume == null && !continueLast)
        ) return current
        // O chat anterior é substituído: ele não pode mais mandar avisos.
        current?.onAttention = null
        val linux = LinuxEnv(this)
        // A integração com o Linux é refeita pelo próprio chat, fora da thread principal.
        startLinkWatcher(linux)
        if (chatAgentId != agentId) current?.stop()

        val engine = if (agentId == CLAUDE) {
            val previous = if (chatAgentId == agentId) current else null
            val settings = AppSettings(this)
            io.github.jonjonesbr.jangada.chat.ChatEngine(
                linux, settings.askPermissions, settings.chatModel, settings.chatEffort, Workspace.path(folder),
            ).also {
                previous?.stop()
                chat = it
                chatAgentId = CLAUDE
                chatFolder = folder
                it.onAttention = { attention -> onChatAttention(agentId, attention) }
                goForeground()
                it.start(resume = resume, continueLast = continueLast, history = true, previous = previous)
            }
        } else {
            val recipe = io.github.jonjonesbr.jangada.agents.AgentCatalog.load(this).first { it.id == agentId }
            if (chatAgentId == agentId) current?.stop()
            val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
            io.github.jonjonesbr.jangada.chat.AcpChat(linux, recipe.acp!!.command, version, Workspace.path(folder)).also {
                chat = it
                chatAgentId = agentId
                chatFolder = folder
                it.onAttention = { attention -> onChatAttention(agentId, attention) }
                goForeground()
                it.start(resume = resume)
            }
        }
        sessionsChanged()
        return engine
    }

    /**
     * Conversa do Claude Code usada numa sessão de terminal: a criada ou
     * alterada desde que a sessão começou; senão, a que ela retomou; senão,
     * nenhuma (era uma conversa nova, ainda sem mensagens).
     */
    fun claudeConversationOf(handle: String): String? {
        val started = sessionStartedAt[handle] ?: 0L
        val dir = ChatHistory.dir(LinuxEnv(this), Workspace.path(sessionFolder[handle].orEmpty()))
        val touched = dir.listFiles()
            ?.filter { it.name.endsWith(".jsonl") && it.lastModified() >= started - 2_000 }
            ?.maxByOrNull { it.lastModified() }
        return touched?.name?.removeSuffix(".jsonl") ?: sessionConversation[handle]
    }

    /** Fecha o chat; [then] roda depois que o agente terminou de sair. */
    fun closeChat(then: (() -> Unit)? = null) {
        val current = chat
        chat = null
        chatAgentId = null
        chatFolder = ""
        sessionsChanged()
        if (current == null) {
            then?.invoke()
            leaveForegroundIfIdle()
            return
        }
        current.stop {
            then?.invoke()
            leaveForegroundIfIdle()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Limpeza de restos de versões antigas: em segundo plano, sem atrasar o serviço.
        legacyCleanup = thread(name = "jangada-legacy") {
            runCatching {
                val linux = LinuxEnv(this)
                if (linux.isReady) linux.cleanupLegacy()
            }
        }
        watchNetworkDns()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            exit()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SETUP) {
            // Instalação do Linux em primeiro plano: a notificação mostra o progresso.
            started = true
            // Primeiro o primeiro plano: addListener já entrega o estado atual, e o fim pode sair dele.
            goForeground()
            Setup.addListener(setupListener)
            return START_NOT_STICKY
        }
        // Iniciado pelas telas: só vai ao primeiro plano se houver trabalho.
        started = true
        if (hasWork()) goForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tearDownSessions()?.stop()
        linkWatcher?.stopWatching()
        wakeLock?.let { if (it.isHeld) it.release() }
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        Setup.removeListener(setupListener)
        super.onDestroy()
    }

    /**
     * Quando a rede muda (Wi-Fi, dados, VPN), regrava o DNS do Debian com os
     * servidores da nova rede. Sem isso, apt e agentes não resolvem nomes em
     * redes que bloqueiam o DNS público.
     */
    private fun watchNetworkDns() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val servers = linkProperties.dnsServers
                thread(name = "jangada-dns") {
                    runCatching { LinuxEnv(this@TerminalService).takeIf { it.isReady }?.updateDns(servers) }
                }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    /**
     * Cria uma sessão: Debian quando instalado, shell do Android como reserva.
     * Com [script], roda o script primeiro (ex.: abrir um agente) e depois
     * deixa um shell aberto na mesma sessão.
     */
    fun createSession(title: String = DEFAULT_TITLE, script: String? = null, folder: String = ""): TerminalSession {
        val linux = LinuxEnv(this)
        val session = if (linux.isReady) {
            runCatching { linux.refreshIntegration(trustClaude = false) }.onFailure { Log.w(TAG, "integração", it) }
            // O ~/.claude.json pode ser grande: a confiança na pasta é feita fora da thread principal.
            // Enquanto o arquivo-sinal existir, o script da sessão espera (até 10 s) antes de abrir
            // o agente, para o Claude não ler o JSON antes da confiança e perguntar de novo.
            val pending = if (script == null) null else runCatching {
                File(linux.rootfs, "tmp/.jangada-trust-${System.nanoTime()}").takeIf { it.createNewFile() }
            }.getOrNull()
            thread(name = "jangada-trust") {
                try {
                    runCatching { linux.trustWorkspaceInClaude(Workspace.path(folder)) }.onFailure { Log.w(TAG, "confiança no Claude", it) }
                } finally {
                    pending?.delete()
                }
            }
            startLinkWatcher(linux)
            val waitTrust = pending?.let {
                "i=0; while [ -e /tmp/${it.name} ] && [ \$i -lt 100 ]; do sleep 0.1; i=\$((i+1)); done; unset i\n"
            }.orEmpty()
            val inner = if (script == null) linux.loginCommand() else linux.sessionScriptCommand(waitTrust + script)
            val argv = linux.prootArgv(inner)
            TerminalSession(argv[0], filesDir.absolutePath, argv, linux.hostEnv(), TRANSCRIPT_ROWS, proxyClient)
        } else {
            androidSession()
        }
        session.mSessionName = title
        sessionStartedAt[session.mHandle] = System.currentTimeMillis()
        sessionFolder[session.mHandle] = folder
        sessions += session
        goForeground()
        sessionsChanged()
        return session
    }

    fun session(handle: String): TerminalSession? = sessions.firstOrNull { it.mHandle == handle }

    /** Há uma sessão de terminal do Claude Code rodando agora? */
    fun hasClaudeTerminal(): Boolean = sessions.any { sessionAgent[it.mHandle] == CLAUDE && it.isRunning }

    /** Troca uma sessão encerrada por uma nova (Enter depois de "exit"). */
    fun replaceSession(old: TerminalSession): TerminalSession {
        old.finishIfRunning()
        sessions.remove(old)
        // A nova é um shell limpo: não herda agente nem conversa da antiga (só o título).
        forgetSession(old.mHandle)
        return createSession(old.mSessionName ?: DEFAULT_TITLE)
    }

    /** Fecha uma sessão; sem nenhuma aberta, o serviço sai do primeiro plano. */
    fun closeSession(handle: String) {
        val session = session(handle) ?: return
        session.finishIfRunning()
        sessions.remove(session)
        forgetSession(handle)
        sessionsChanged()
        leaveForegroundIfIdle()
    }

    /**
     * Instala ou atualiza um agente sem abrir o terminal. A saída fica
     * guardada na tarefa (para "Ver detalhes") e a tela acompanha pelo
     * [onTasksChanged]. Quem chama deve ter dado startService antes.
     */
    fun runAgentTask(recipe: AgentRecipe, updating: Boolean, uninstalling: Boolean = false) {
        if (tasks[recipe.id]?.state == AgentTask.State.RUNNING) return
        if (uninstalling) closeAgent(recipe.id)
        val task = AgentTask(recipe, updating, uninstalling)
        tasks[recipe.id] = task
        goForeground()
        updateNotification()
        onTasksChanged?.invoke()
        val worker = thread(start = false, name = "jangada-agent-${recipe.id}") {
            val exit = runCatching {
                val script = if (uninstalling) recipe.uninstallScript() else recipe.backgroundScript(updating)
                LinuxEnv(this).runBash(script, onStart = { process ->
                    task.process = process
                    // Cancelado antes de o processo nascer: encerra logo.
                    if (task.cancelled) process.destroy()
                }) { raw ->
                    val line = cleanLine(raw)
                    if (line.isEmpty()) return@runBash
                    task.append(line)
                    if (!line.startsWith("JANGADA_")) task.lastLine = line
                    postTasksChanged(throttle = true)
                }
            }.getOrElse { e ->
                // Ao cancelar, a leitura da saída falha de propósito: não é erro.
                if (!task.cancelled) task.append(getString(R.string.task_error, e.message.orEmpty()))
                -1
            }
            task.process = null
            if (task.cancelled) task.append(getString(R.string.task_cancelled))
            task.state = if (exit == 0 && !task.cancelled) AgentTask.State.OK else AgentTask.State.FAILED
            main.post {
                onTasksChanged?.invoke()
                updateNotification()
                // Com a tela inicial fechada, avisa que a tarefa terminou (cancelada não avisa).
                if (!homeScreenVisible && !task.cancelled) notifyTaskDone(task)
                leaveForegroundIfIdle()
            }
        }
        task.worker = worker
        worker.start()
    }

    /**
     * Cancela a instalação, atualização ou desinstalação em andamento do agente
     * [agentId]: pede para o processo sair e, se ele não sair em 2 s, força.
     * Sem tarefa rodando, não faz nada.
     */
    fun cancelTask(agentId: String) {
        val task = tasks[agentId]?.takeIf { it.state == AgentTask.State.RUNNING } ?: return
        task.cancelled = true
        task.process?.destroy()
        thread(name = "jangada-cancel-$agentId") {
            runCatching { Thread.sleep(2_000) }
            task.process?.takeIf { it.isAlive }?.destroyForcibly()
        }
        main.post {
            onTasksChanged?.invoke()
            updateNotification()
        }
    }

    /** Cancela todas as tarefas em andamento (ao sair pela notificação ou fechar tudo). */
    private fun cancelRunningTasks() {
        tasks.keys.toList().forEach { cancelTask(it) }
    }

    /** Fecha o chat e as sessões de terminal de um agente (antes de desinstalar). */
    private fun closeAgent(agentId: String) {
        if (chatAgentId == agentId) closeChat()
        sessionAgent.filterValues { it == agentId }.keys.toList().forEach { closeSession(it) }
    }

    private fun postTasksChanged(throttle: Boolean) {
        val now = System.currentTimeMillis()
        if (throttle && now - lastTaskPost < 250) return
        lastTaskPost = now
        main.post { onTasksChanged?.invoke() }
    }

    /** Tira cores e barras de progresso (\r) da saída dos instaladores. */
    private fun cleanLine(raw: String): String =
        raw.substringAfterLast('\r').replace(ANSI, "").trim()

    private fun hasRunningTasks() = tasks.values.any { it.state == AgentTask.State.RUNNING }

    /** Há algo que mantenha o serviço em primeiro plano (sessão, chat, instalação ou Linux)? */
    private fun hasWork() =
        sessions.isNotEmpty() || chat != null || hasRunningTasks() || Setup.state is Setup.State.Running

    private fun leaveForegroundIfIdle() {
        if (!hasWork()) leaveForeground()
    }

    private fun sessionsChanged() {
        updateNotification()
        sessionListeners.forEach { it() }
    }

    private fun leaveForeground() {
        foreground = false
        started = false
        stopForeground(true)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopSelf()
    }

    private fun androidSession(): TerminalSession {
        val home = File(filesDir, "home").apply { mkdirs() }
        val env = arrayOf(
            "TERM=xterm-256color", "COLORTERM=truecolor",
            "HOME=${home.absolutePath}", "TMPDIR=${cacheDir.absolutePath}",
            "PATH=/system/bin:/system/xbin", "LANG=en_US.UTF-8",
            "ANDROID_DATA=/data", "ANDROID_ROOT=/system",
        )
        return TerminalSession("/system/bin/sh", home.absolutePath, arrayOf("-sh"), env, TRANSCRIPT_ROWS, proxyClient)
    }

    private fun startLinkWatcher(linux: LinuxEnv) {
        if (linkWatcher != null) return
        val dir = linux.linkInbox.apply { mkdirs() }
        linkWatcher = LinkWatcher(dir) { url -> main.post { openLink(url) } }.also { it.startWatching() }
    }

    /**
     * Com o app na tela, abre direto (no máximo [MAX_DIRECT_LINKS] a cada
     * [DIRECT_LINK_WINDOW_MS], para um agente em loop não abrir navegador sem parar);
     * o resto, e tudo com o app fora da tela, avisa por notificação. Thread principal.
     */
    private fun openLink(url: String) {
        val now = SystemClock.elapsedRealtime()
        while (directLinkOpens.isNotEmpty() && now - directLinkOpens.first() >= DIRECT_LINK_WINDOW_MS) {
            directLinkOpens.removeFirst()
        }
        if (directLinkOpens.size < MAX_DIRECT_LINKS && screenLinkOpener?.invoke(url) == true) {
            directLinkOpens.addLast(now)
            return
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this, url.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_LINKS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.link_notification_title))
            .setContentText(url)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        notifications().notify(NOTIFICATION_LINK, notification)
    }

    /** Tira os mapas por sessão de uma sessão que saiu. */
    private fun forgetSession(handle: String) {
        sessionAgent.remove(handle)
        sessionStartedAt.remove(handle)
        sessionConversation.remove(handle)
        sessionFolder.remove(handle)
    }

    /**
     * Encerra todas as sessões de terminal, limpa os mapas por sessão e solta o
     * chat (zerando chat e chatAgentId). Devolve o chat solto para quem chama
     * parar: cada saída espera de um jeito diferente. Não cancela tarefas nem
     * avisa as telas; isso fica com quem chama, na ordem que cada um precisa.
     */
    private fun tearDownSessions(): io.github.jonjonesbr.jangada.chat.AgentChat? {
        sessions.forEach { it.finishIfRunning() }
        sessions.clear()
        sessionAgent.clear()
        sessionStartedAt.clear()
        sessionConversation.clear()
        sessionFolder.clear()
        val current = chat
        chat = null
        chatAgentId = null
        chatFolder = ""
        return current
    }

    private fun exit() {
        tearDownSessions()?.stop()
        cancelRunningTasks()
        sessionListeners.forEach { it() }
        onExit?.invoke()
        leaveForeground()
    }

    /**
     * Encerra tudo (sessões, chat, instalações em andamento) antes de reinstalar
     * ou restaurar o Linux. [then] roda na thread principal, depois que o chat
     * saiu e as instalações terminaram (no máximo 5 s para cada uma).
     */
    fun closeEverything(then: () -> Unit) {
        val current = tearDownSessions()
        cancelRunningTasks()
        sessionListeners.forEach { it() }
        val chatGone = java.util.concurrent.CountDownLatch(1)
        if (current == null) chatGone.countDown() else current.stop { chatGone.countDown() }
        val workers = tasks.values.mapNotNull { it.worker }
        thread(name = "jangada-close") {
            runCatching { chatGone.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            workers.forEach { runCatching { it.join(5_000) } }
            // Apagar o Linux enquanto a limpeza apaga parte dele faria as duas falharem.
            legacyCleanup?.let { runCatching { it.join(60_000) } }
            main.post {
                onExit?.invoke()
                leaveForeground()
                then()
            }
        }
    }

    private fun goForeground() {
        createChannels()
        startForeground(NOTIFICATION_SESSIONS, buildNotification())
        foreground = true
        // Sem startService, o serviço morre quando a última tela se desliga dele (só bind).
        if (!started) {
            started = true
            runCatching { startService(Intent(this, TerminalService::class.java)) }
        }
        // Manter o processador acordado é opcional (configurações, Modo Pro).
        if (wakeLock == null && AppSettings(this).keepCpuAwake) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jangada:sessions").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun updateNotification() {
        if (foreground) notifications().notify(NOTIFICATION_SESSIONS, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val exit = PendingIntent.getService(
            this, 1,
            Intent(this, TerminalService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val count = sessions.size + if (chat != null) 1 else 0
        val running = tasks.values.firstOrNull { it.state == AgentTask.State.RUNNING }
        val setup = Setup.state as? Setup.State.Running
        val text = when {
            setup != null -> {
                val progress = setup.progress
                if (progress != null) getString(R.string.service_setup_progress, (progress * 100).toInt())
                else getString(R.string.service_setup)
            }
            running != null -> getString(
                when {
                    running.uninstalling -> R.string.task_uninstalling
                    running.updating -> R.string.task_updating
                    else -> R.string.task_installing
                },
                running.recipe.name,
            )
            else -> resources.getQuantityString(R.plurals.service_sessions, count, count)
        }
        return Notification.Builder(this, CHANNEL_SESSIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_notification),
                    getString(R.string.service_exit),
                    exit,
                ).build(),
            )
            .build()
    }

    private fun createChannels() {
        val manager = notifications()
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SESSIONS, getString(R.string.channel_sessions), NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LINKS, getString(R.string.channel_links), NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ATTENTION, getString(R.string.channel_attention), NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    /** Aviso do chat: o agente precisa de você ou terminou. Com a tela do chat aberta, não avisa. */
    private fun onChatAttention(agentId: String, attention: ChatAttention) {
        if (chatScreenVisible) return
        val name = AgentCatalog.load(this).firstOrNull { it.id == agentId }?.name ?: "Claude Code"
        val (title, text) = when (attention) {
            ChatAttention.PERMISSION -> R.string.notify_permission_title to R.string.notify_permission_text
            ChatAttention.LOGIN -> R.string.notify_login_title to R.string.notify_login_text
            ChatAttention.FINISHED -> R.string.notify_finished_title to R.string.notify_finished_text
            ChatAttention.FAILED -> R.string.notify_failed_title to R.string.notify_failed_text
        }
        val open = Intent(this, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_AGENT_ID, agentId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        attentionNotification(agentId.hashCode(), NOTIFICATION_ATTENTION, getString(title, name), getString(text), open)
    }

    /** Aviso do fim de uma instalação, atualização ou desinstalação de agente. Ao toque, abre a tela inicial. */
    private fun notifyTaskDone(task: AgentTask) {
        val name = task.recipe.name
        val title = when {
            task.state == AgentTask.State.FAILED -> getString(R.string.task_notify_failed, name)
            task.uninstalling -> getString(R.string.task_notify_uninstalled, name)
            task.updating -> getString(R.string.task_notify_updated, name)
            else -> getString(R.string.task_notify_installed, name)
        }
        val open = Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        attentionNotification(REQUEST_HOME, NOTIFICATION_TASK, title, getString(R.string.task_notify_text), open)
    }

    /** Fim da instalação do Linux: avisa (se a tela não estiver aberta), para de acompanhar e recalcula o primeiro plano. */
    private fun finishSetup(state: Setup.State) {
        Setup.removeListener(setupListener)
        if (!SetupActivity.visible) {
            val title = getString(
                if (state is Setup.State.Failed) R.string.setup_notify_failed else R.string.setup_notify_done,
            )
            val open = Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            attentionNotification(REQUEST_SETUP, NOTIFICATION_TASK, title, getString(R.string.task_notify_text), open)
        }
        updateNotification()
        leaveForegroundIfIdle()
    }

    /** Mostra um aviso no canal de atenção; ao toque, abre [target] e o aviso some. */
    private fun attentionNotification(requestCode: Int, id: Int, title: String, text: String, target: Intent) {
        val pending = PendingIntent.getActivity(
            this, requestCode, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        notifications().notify(id, notification)
    }

    private fun notifications() = getSystemService(NotificationManager::class.java)

    /**
     * Repassa os eventos das sessões para a tela, quando ela está aberta.
     * Sem tela, só o essencial acontece (as sessões seguem rodando).
     */
    private val proxyClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            screenClient?.onTextChanged(changedSession)
        }
        override fun onTitleChanged(changedSession: TerminalSession) {
            screenClient?.onTitleChanged(changedSession)
        }
        override fun onSessionFinished(finishedSession: TerminalSession) {
            screenClient?.onSessionFinished(finishedSession)
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            screenClient?.onCopyTextToClipboard(session, text)
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            screenClient?.onPasteTextFromClipboard(session)
        }
        override fun onBell(session: TerminalSession) {
            screenClient?.onBell(session)
        }
        override fun onColorsChanged(session: TerminalSession) {
            screenClient?.onColorsChanged(session)
        }
        override fun onTerminalCursorStateChange(state: Boolean) {
            screenClient?.onTerminalCursorStateChange(state)
        }
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
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

    companion object {
        private const val TAG = "Jangada"
        const val ACTION_EXIT = "io.github.jonjonesbr.jangada.EXIT"
        /** Instalação do Linux em primeiro plano (startService antes do Setup.start). */
        const val ACTION_SETUP = "io.github.jonjonesbr.jangada.SETUP"
        private const val CHANNEL_SESSIONS = "sessions"
        private const val CHANNEL_LINKS = "links"
        private const val CHANNEL_ATTENTION = "attention"
        private const val NOTIFICATION_SESSIONS = 1
        private const val NOTIFICATION_LINK = 2
        /** Aviso do chat (permissão, login, resposta pronta ou erro). */
        private const val NOTIFICATION_ATTENTION = 3
        /** Aviso de tarefa de agente ou da instalação do Linux terminada. */
        private const val NOTIFICATION_TASK = 4
        private const val REQUEST_HOME = 10
        private const val REQUEST_SETUP = 11
        private const val TRANSCRIPT_ROWS = 5000
        /** Limite de links abertos direto na tela: [MAX_DIRECT_LINKS] a cada [DIRECT_LINK_WINDOW_MS]. */
        private const val MAX_DIRECT_LINKS = 3
        private const val DIRECT_LINK_WINDOW_MS = 30_000L
        const val DEFAULT_TITLE = "Terminal"
        const val CLAUDE = "claude-code"
        private val ANSI = Regex("""\u001B(\[[0-9;?]*[ -/]*[@-~]|\][^\u0007]*\u0007|[()][A-Z0-9])""")
    }
}

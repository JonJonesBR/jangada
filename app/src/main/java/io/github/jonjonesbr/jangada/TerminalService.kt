package io.github.jonjonesbr.jangada

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.linux.LinkWatcher
import io.github.jonjonesbr.jangada.linux.LinuxEnv
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
    private var linkWatcher: LinkWatcher? = null

    val sessions = mutableListOf<TerminalSession>()

    /** Cliente da tela que está aberta agora (null com o app fora da tela). */
    var screenClient: TerminalSessionClient? = null

    /** Abre um link pela tela aberta; devolve false se não há tela visível. */
    var screenLinkOpener: ((String) -> Boolean)? = null

    /** Chamado quando o usuário encerra tudo pela notificação. */
    var onExit: (() -> Unit)? = null

    /** Avisa a tela inicial quando a lista de sessões muda. */
    var onSessionsChanged: (() -> Unit)? = null

    /** Instalação ou atualização de um agente rodando em segundo plano. */
    class AgentTask(val recipe: AgentRecipe, val updating: Boolean) {
        enum class State { RUNNING, OK, FAILED }

        @Volatile var state = State.RUNNING
        @Volatile var lastLine = ""
        /** A tela inicial já registrou o resultado desta tarefa. */
        @Volatile var acknowledged = false
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

    /** Conversa do chat (Claude Code sem terminal), quando aberta. */
    var chat: io.github.jonjonesbr.jangada.chat.ChatEngine? = null
        private set

    /** Agente de cada sessão de terminal (id da receita), para a troca Chat ⇄ Terminal. */
    val sessionAgent = HashMap<String, String>()

    /**
     * Abre (ou devolve) a conversa do chat. [continueLast] retoma a conversa
     * mais recente da pasta de projetos — a mesma que estava no terminal.
     */
    fun openChat(continueLast: Boolean, resume: String? = null): io.github.jonjonesbr.jangada.chat.ChatEngine {
        chat?.let { if (it.running || resume == null && !continueLast) return it }
        val linux = LinuxEnv(this)
        runCatching { linux.refreshIntegration() }
        startLinkWatcher(linux)
        val engine = io.github.jonjonesbr.jangada.chat.ChatEngine(linux, AppSettings(this).askPermissions)
        val previous = chat
        previous?.stop()
        chat = engine
        // Ao continuar, mostra o que já foi conversado (no chat anterior ou no terminal).
        if (previous != null && previous.items.isNotEmpty() && (resume != null || continueLast)) {
            engine.items.addAll(previous.items)
        } else if (resume != null || continueLast) {
            sessionFile(linux, resume)?.let { engine.loadHistory(it) }
        }
        goForeground()
        engine.start(resume = resume, continueLast = continueLast)
        sessionsChanged()
        return engine
    }

    /** Arquivo da conversa do Claude Code: a [id] pedida ou a mais recente. */
    private fun sessionFile(linux: LinuxEnv, id: String?): File? {
        val dir = File(linux.rootfs, "root/.claude/projects/-root-${LinuxEnv.WORKSPACE}")
        if (id != null) return File(dir, "$id.jsonl").takeIf { it.exists() }
        return dir.listFiles()?.filter { it.name.endsWith(".jsonl") }?.maxByOrNull { it.lastModified() }
    }

    fun closeChat() {
        chat?.stop()
        chat = null
        sessionsChanged()
        leaveForegroundIfIdle()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            exit()
            return START_NOT_STICKY
        }
        goForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        chat?.stop()
        chat = null
        linkWatcher?.stopWatching()
        sessions.forEach { it.finishIfRunning() }
        sessions.clear()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /**
     * Cria uma sessão: Debian quando instalado, shell do Android como reserva.
     * Com [script], roda o script primeiro (ex.: abrir um agente) e depois
     * deixa um shell aberto na mesma sessão.
     */
    fun createSession(title: String = DEFAULT_TITLE, script: String? = null): TerminalSession {
        val linux = LinuxEnv(this)
        val session = if (linux.isReady) {
            runCatching { linux.refreshIntegration() }.onFailure { Log.w(TAG, "integração", it) }
            startLinkWatcher(linux)
            val inner = if (script == null) linux.loginCommand() else linux.sessionScriptCommand(script)
            val argv = linux.prootArgv(inner)
            TerminalSession(argv[0], filesDir.absolutePath, argv, linux.hostEnv(), TRANSCRIPT_ROWS, proxyClient)
        } else {
            androidSession()
        }
        session.mSessionName = title
        sessions += session
        sessionsChanged()
        return session
    }

    fun session(handle: String): TerminalSession? = sessions.firstOrNull { it.mHandle == handle }

    /** Troca uma sessão encerrada por uma nova (Enter depois de "exit"). */
    fun replaceSession(old: TerminalSession): TerminalSession {
        old.finishIfRunning()
        sessions.remove(old)
        return createSession(old.mSessionName ?: DEFAULT_TITLE)
    }

    /** Fecha uma sessão; sem nenhuma aberta, o serviço sai do primeiro plano. */
    fun closeSession(handle: String) {
        val session = session(handle) ?: return
        session.finishIfRunning()
        sessions.remove(session)
        sessionAgent.remove(handle)
        sessionsChanged()
        leaveForegroundIfIdle()
    }

    /**
     * Instala ou atualiza um agente sem abrir o terminal. A saída fica
     * guardada na tarefa (para "Ver detalhes") e a tela acompanha pelo
     * [onTasksChanged]. Quem chama deve ter dado startService antes.
     */
    fun runAgentTask(recipe: AgentRecipe, updating: Boolean) {
        if (tasks[recipe.id]?.state == AgentTask.State.RUNNING) return
        val task = AgentTask(recipe, updating)
        tasks[recipe.id] = task
        goForeground()
        updateNotification()
        onTasksChanged?.invoke()
        thread(name = "jangada-agent-${recipe.id}") {
            val exit = runCatching {
                LinuxEnv(this).runBash(recipe.backgroundScript(updating)) { raw ->
                    val line = cleanLine(raw)
                    if (line.isEmpty()) return@runBash
                    task.append(line)
                    if (!line.startsWith("JANGADA_")) task.lastLine = line
                    postTasksChanged(throttle = true)
                }
            }.getOrElse { e ->
                task.append("ERRO: ${e.message}")
                -1
            }
            task.state = if (exit == 0) AgentTask.State.OK else AgentTask.State.FAILED
            main.post {
                onTasksChanged?.invoke()
                updateNotification()
                leaveForegroundIfIdle()
            }
        }
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

    private fun leaveForegroundIfIdle() {
        if (sessions.isEmpty() && !hasRunningTasks() && chat == null) leaveForeground()
    }

    private fun sessionsChanged() {
        updateNotification()
        onSessionsChanged?.invoke()
    }

    private fun leaveForeground() {
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

    /** Com o app na tela, abre direto; fora dela, avisa por notificação. */
    private fun openLink(url: String) {
        if (screenLinkOpener?.invoke(url) == true) return
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

    private fun exit() {
        sessions.forEach { it.finishIfRunning() }
        sessions.clear()
        chat?.stop()
        chat = null
        onSessionsChanged?.invoke()
        onExit?.invoke()
        leaveForeground()
    }

    private fun goForeground() {
        createChannels()
        startForeground(NOTIFICATION_SESSIONS, buildNotification())
        if (wakeLock == null) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jangada:sessions").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun updateNotification() {
        if (wakeLock != null) notifications().notify(NOTIFICATION_SESSIONS, buildNotification())
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
        val count = (sessions.size + if (chat != null) 1 else 0).coerceAtLeast(1)
        val running = tasks.values.firstOrNull { it.state == AgentTask.State.RUNNING }
        val text = when {
            running != null -> getString(
                if (running.updating) R.string.task_updating else R.string.task_installing,
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
        private const val CHANNEL_SESSIONS = "sessions"
        private const val CHANNEL_LINKS = "links"
        private const val NOTIFICATION_SESSIONS = 1
        private const val NOTIFICATION_LINK = 2
        private const val TRANSCRIPT_ROWS = 5000
        const val DEFAULT_TITLE = "Terminal"
        private val ANSI = Regex("""\u001B(\[[0-9;?]*[ -/]*[@-~]|\][^\u0007]*\u0007|[()][A-Z0-9])""")
    }
}

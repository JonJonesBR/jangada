package io.github.jonjonesbr.jangada

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.github.jonjonesbr.jangada.agents.AgentCatalog
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.chat.AcpChat
import io.github.jonjonesbr.jangada.chat.AgentChat
import io.github.jonjonesbr.jangada.chat.ChatAttention
import io.github.jonjonesbr.jangada.chat.ChatEngine
import io.github.jonjonesbr.jangada.chat.ChatHistory
import io.github.jonjonesbr.jangada.chat.ChatItem
import io.github.jonjonesbr.jangada.chat.ChildAgents
import io.github.jonjonesbr.jangada.chat.LimitWindow
import io.github.jonjonesbr.jangada.chat.formatLimitReset
import io.github.jonjonesbr.jangada.chat.limitKindName
import io.github.jonjonesbr.jangada.chat.PermissionOption
import io.github.jonjonesbr.jangada.chat.PermissionRisk
import io.github.jonjonesbr.jangada.linux.CACHE_CLEAN_SCRIPT
import io.github.jonjonesbr.jangada.linux.CACHE_FREED_PREFIX
import io.github.jonjonesbr.jangada.linux.LinkWatcher
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Projects
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
    /** Ouvinte do ajuste de manter o processador acordado (guardado aqui: o Android guarda só referência fraca). */
    private var cpuAwakeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    /** Horários (elapsedRealtime) das aberturas diretas de link na janela; só a thread principal mexe. */
    private val directLinkOpens = ArrayDeque<Long>()
    /** Limpeza de restos antigos (onCreate); closeEverything espera por ela antes de apagar o Linux. */
    @Volatile private var legacyCleanup: Thread? = null
    /** Vigia a rede do celular, para regravar o DNS do Debian quando ela muda. */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** Último aviso de progresso da instalação do Linux (no máximo 1 por segundo). */
    private var lastSetupNotify = 0L
    /** Trabalhos (chaves de [chatKey]) cujo aviso atual é de pedido de permissão com botões. Só a thread principal mexe. */
    private val permissionNotices = HashSet<String>()
    /** Memória total do aparelho em bytes; -1 = ainda não lida (ver totalMem). */
    private var totalMemBytes = -1L

    /** Último trabalho de chat que esteve na tela (chave de [chatKey]): o toque na notificação volta para ele. */
    private var lastChat: String? = null

    /**
     * Trabalho de chat aberto na tela de chat agora (chave de [chatKey]; null se nenhum).
     * Ao virar uma chave, o aviso desse trabalho some (o usuário já viu) e ele vira o [lastChat]
     * (a notificação é refeita). Só a thread principal mexe.
     */
    var visibleChat: String? = null
        set(value) {
            field = value
            if (value != null) {
                notifications().cancel(value, NOTIFICATION_CHAT)
                if (value != lastChat) {
                    lastChat = value
                    updateNotification()
                }
            }
        }

    /** Tela inicial visível agora: ao virar true, o aviso de tarefa some. */
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

    /** Pedido de instalar, atualizar ou desinstalar que espera a vez (outra tarefa de agente rodando). */
    class QueuedTask(val recipe: AgentRecipe, val updating: Boolean, val uninstalling: Boolean)

    /**
     * Clone de um repositório para uma pasta nova de ~/projetos, em segundo plano. Com [source], é
     * a cópia de um projeto (Duplicar, ver [runCopy]): mesmo andamento, cancelar e aviso.
     */
    class CloneTask(val agentId: String, val url: String, val folder: String, val source: String? = null) {
        enum class State { RUNNING, OK, EXISTS, FAILED }

        @Volatile var state = State.RUNNING
        @Volatile var lastLine = ""
        /** Processo do git em andamento (para cancelar). */
        @Volatile var process: Process? = null
        /** true quando o usuário cancelou; o estado final fica FAILED (OK se o clone já tinha terminado). */
        @Volatile var cancelled = false
        /** Thread que roda o clone (o fechamento espera por ela). */
        @Volatile var worker: Thread? = null
        /** Uma tela mostra o progresso: ao terminar, ela abre o chat (sem aviso). */
        @Volatile var watched = true
        /** O usuário mandou o clone para segundo plano (botão): a tela não volta a mostrar o progresso. */
        @Volatile var sentToBackground = false
        /** O aviso de fim já saiu ou a tela já tratou o resultado. */
        @Volatile var reported = false
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

    /** Tarefas de agente esperando a vez, na ordem em que foram pedidas. Só mexida na thread principal. */
    private val taskQueue = ArrayDeque<QueuedTask>()

    /** Primeira falha da rodada (até a fila esvaziar): o aviso do fim mostra ela, não o último sucesso. */
    private var batchFailure: AgentTask? = null

    /** Clones por pasta de destino (o último de cada pasta). */
    val clones = ConcurrentHashMap<String, CloneTask>()

    /** Avisa a tela inicial quando alguma tarefa muda. */
    var onTasksChanged: (() -> Unit)? = null

    private var lastTaskPost = 0L

    /** Um trabalho de chat aberto: o agente, a pasta (nome dentro de ~/projetos; "" = a raiz) e o chat. */
    class ChatSlot(val agentId: String, val folder: String, val chat: AgentChat)

    /** Trabalhos de chat abertos, por [chatKey] (um por par agente + pasta). Só a thread principal mexe. */
    val chats = LinkedHashMap<String, ChatSlot>()

    /** Põe para dormir os agentes parados quando a memória aperta, ou quando ficam ociosos ou falta vaga. */
    val memoryWatch = MemoryWatch(this)

    // Os mapas por sessão são usados só na thread principal (sem sincronização).
    /** Agente de cada sessão de terminal (id da receita), para a troca Chat ⇄ Terminal. */
    val sessionAgent = HashMap<String, String>()
    /** Quando cada sessão de terminal começou (para achar a conversa criada nela). */
    val sessionStartedAt = HashMap<String, Long>()
    /** Conversa que a sessão de terminal retomou (claude --resume), se houver. */
    val sessionConversation = HashMap<String, String>()
    /** Pasta de trabalho de cada sessão de terminal (nome dentro de ~/projetos; "" = a raiz). */
    val sessionFolder = HashMap<String, String>()

    /** Chat aberto do par ([agentId], [folder]), se houver. */
    fun chat(agentId: String, folder: String): AgentChat? = chats[chatKey(agentId, folder)]?.chat

    /** Chats com o agente rodando: só eles contam no limite (um trabalho parado não usa memória). */
    fun runningChats(): List<ChatSlot> = chats.values.filter { it.chat.running }

    /** Agentes de chat rodando ao mesmo tempo: a escolha dos ajustes, ou o automático pela RAM (ver AgentLimit). */
    fun maxChats(): Int = AgentLimit.effective(AppSettings(this).maxAgents, totalMem())

    /** Memória total do aparelho em bytes (0 se o Android não informar). Lida uma vez e guardada. */
    private fun totalMem(): Long {
        if (totalMemBytes < 0L) {
            totalMemBytes = runCatching {
                val info = ActivityManager.MemoryInfo()
                getSystemService(ActivityManager::class.java).getMemoryInfo(info)
                info.totalMem
            }.getOrDefault(0L)
        }
        return totalMemBytes
    }

    /**
     * Pode abrir (ou reabrir) o trabalho do par? Sim se o agente dele já roda (é substituído,
     * sem somar) ou se há vaga. Um trabalho parado que volta a rodar também precisa de vaga.
     */
    fun canOpenChat(agentId: String, folder: String): Boolean =
        chat(agentId, folder)?.running == true || runningChats().size < maxChats()

    /**
     * Há vaga para o trabalho do par? Se não, põe para dormir um agente parado que libera uma
     * (ver [MemoryWatch.freeSlotFor]). Quem chama já mostrou a lista de trabalhos quando não há como.
     */
    fun makeRoomFor(agentId: String, folder: String): Boolean =
        canOpenChat(agentId, folder) || memoryWatch.freeSlotFor(chatKey(agentId, folder))

    /**
     * Abre (ou devolve) o chat do agente [agentId], na pasta [folder] ("" = a raiz).
     * [continueLast] retoma a conversa mais recente; [resume] uma conversa específica;
     * [restart] força reabrir mesmo com o chat rodando. Só mexe no trabalho desse par:
     * os de outros agentes e pastas seguem rodando. Não recusa nada: quem chama confere [canOpenChat].
     */
    fun openChat(
        agentId: String = CLAUDE,
        folder: String = "",
        continueLast: Boolean = false,
        resume: String? = null,
        restart: Boolean = false,
    ): AgentChat {
        val key = chatKey(agentId, folder)
        val current = chats[key]?.chat
        if (!restart && current != null && (current.running || resume == null && !continueLast)) return current
        // O chat anterior deste par é substituído: ele não pode mais mandar avisos.
        current?.onAttention = null
        val linux = LinuxEnv(this)
        // A integração com o Linux é refeita pelo próprio chat, fora da thread principal.
        startLinkWatcher(linux)
        val settings = AppSettings(this)
        val chat: AgentChat = if (agentId == CLAUDE) {
            // O Claude lê a compactação ao iniciar: trocar o ajuste reabre a conversa (ver ChatActivity).
            ChatEngine(
                linux, settings.askPermissions, settings.chatModel, settings.chatEffort, Workspace.path(folder),
                autoCompact = settings.autoCompactFor(key),
            )
        } else {
            val recipe = AgentCatalog.load(this).first { it.id == agentId }
            val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
            // Nos agentes ACP, o valor é lido a cada fim de turno (vale na hora).
            AcpChat(
                linux, recipe.acp!!.command, version, Workspace.path(folder), agentId = recipe.id,
                autoCompact = { settings.autoCompactFor(key) },
            )
        }
        // Para o chat anterior deste par (se havia) antes de o novo começar.
        current?.stop()
        chats[key] = ChatSlot(agentId, folder, chat)
        // Só avisa enquanto for o chat deste par (um substituído ou fechado fica calado).
        chat.onAttention = { attention -> if (chats[key]?.chat === chat) onChatAttention(agentId, folder, attention) }
        goForeground()
        when (chat) {
            // O Claude lê o histórico do chat anterior do mesmo par (se houver).
            is ChatEngine -> chat.start(resume = resume, continueLast = continueLast, history = true, previous = current)
            // Os agentes ACP também reaproveitam os itens do chat anterior (o replay do session/load é ignorado).
            is AcpChat -> chat.start(resume = resume, previous = current)
        }
        sessionsChanged()
        return chat
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

    /** Fecha o trabalho do par ([agentId], [folder]); [then] roda depois que o agente terminou de sair. */
    fun closeChat(agentId: String, folder: String, then: (() -> Unit)? = null) {
        val key = chatKey(agentId, folder)
        val current = chats.remove(key)?.chat
        current?.onAttention = null
        notifications().cancel(key, NOTIFICATION_CHAT)
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

    /**
     * Pastas com uma operação de projeto em andamento (excluir, renomear): nenhum chat nem clone
     * começa nelas até [releaseFolder]. Só a thread principal mexe.
     */
    private val busyFolders = HashSet<String>()

    /** A pasta [folder] está sendo excluída ou renomeada: a tela não abre o chat dela. Thread principal. */
    fun folderBusy(folder: String): Boolean = folder in busyFolders

    /**
     * Prepara [folder] para sair ou mudar de nome: marca a pasta como ocupada, fecha as sessões
     * de terminal e os chats dela (de todos os agentes) e cancela um clone para ela. [then] roda
     * na thread principal depois que os chats saíram e o clone parou (no máximo 5 s para cada
     * espera). Devolve false (e não faz nada) se a pasta já está ocupada. Quem chama libera a
     * pasta com [releaseFolder] ao terminar, com sucesso ou não. Thread principal.
     */
    fun closeFolder(folder: String, then: () -> Unit): Boolean {
        if (folder.isEmpty() || folder in busyFolders) return false
        // A pasta está sendo copiada (Duplicar): espera a cópia terminar ou ser cancelada.
        if (clones.values.any { it.source == folder && it.state == CloneTask.State.RUNNING }) return false
        busyFolders += folder
        sessionFolder.filterValues { it == folder }.keys.toList().forEach { closeSession(it) }
        cancelClone(folder)
        val cloneWorker = clones[folder]?.worker
        val slots = chats.values.filter { it.folder == folder }
        val chatsGone = java.util.concurrent.CountDownLatch(slots.size)
        // Os avisos de saída chegam na thread principal; a espera fica numa thread própria.
        slots.forEach { closeChat(it.agentId, it.folder) { chatsGone.countDown() } }
        thread(name = "jangada-fecha-pasta") {
            runCatching { chatsGone.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            cloneWorker?.let { runCatching { it.join(5_000) } }
            main.post { then() }
        }
        return true
    }

    /** Fim da operação na pasta [folder]: chats e clones podem voltar a abrir nela. Thread principal. */
    fun releaseFolder(folder: String) {
        busyFolders -= folder
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
        // Serviço recriado com o processo vivo: a limpeza que a instância anterior começou continua.
        // Esta instância entra em primeiro plano e só tira a fila quando ela acabar.
        val leftover = cacheCleaner
        if (cleaningCaches() && leftover != null) {
            goForeground()
            thread(name = "jangada-limpeza-espera") {
                runCatching { leftover.join() }
                main.post {
                    runNextQueued()
                    // O aviso deixa de dizer que limpa (se o serviço continua em primeiro plano).
                    updateNotification()
                    leaveForegroundIfIdle()
                }
            }
        }
        watchNetworkDns()
        scheduleAutoClean()
        // Trocar o ajuste nas configurações acorda ou solta o processador na hora.
        cpuAwakeListener = AppSettings(this).watchCpuAwake { main.post { applyCpuAwake() } }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            exit()
            return START_NOT_STICKY
        }
        if (intent != null && intent.action == ACTION_ANSWER_PERMISSION) {
            // Nunca vai ao primeiro plano por aqui (o toque no botão não é trabalho novo). Se o Android
            // recriou o serviço (processo morto), não há chat: só some com o aviso e o serviço se encerra.
            // Com chats, sessões ou tarefas, o serviço segue como estava.
            answerPermission(intent)
            leaveForegroundIfIdle()
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
        scheduleAutoClean()
        if (hasWork()) goForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        memoryWatch.stop()
        main.removeCallbacks(autoCleanCheck)
        tearDownSessions().forEach { it.stop() }
        linkWatcher?.stopWatching()
        cpuAwakeListener?.let { AppSettings(this).unwatchCpuAwake(it) }
        cpuAwakeListener = null
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
            // A integração (PATH, jangada-open, /tmp) é refeita fora da thread principal. Enquanto o
            // arquivo-sinal dela existir, o shell espera (até 10 s), para não abrir sem o PATH pronto.
            // Sem como criar o sinal, refaz aqui mesmo, como antes.
            val integration = runCatching {
                File(linux.rootfs, "tmp/.jangada-integration-${System.nanoTime()}").takeIf { it.createNewFile() }
            }.getOrNull()
            if (integration == null) {
                runCatching { linux.refreshIntegration(trustClaude = false) }.onFailure { Log.w(TAG, "integração", it) }
            } else {
                thread(name = "jangada-integration") {
                    try {
                        runCatching { linux.refreshIntegration(trustClaude = false) }.onFailure { Log.w(TAG, "integração", it) }
                    } finally {
                        integration.delete()
                    }
                }
            }
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
            val waits = integration?.let { waitForFile(it.name) }.orEmpty() +
                pending?.let { waitForFile(it.name) }.orEmpty()
            val inner = when {
                // Depois da espera, relê o PATH do Debian (o login já o leu antes do sinal).
                script != null -> linux.sessionScriptCommand(waits + LinuxEnv.PROFILE_RELOAD + script)
                waits.isEmpty() -> linux.loginCommand()
                // Login sem script, mas com a integração ainda pendente: espera e depois abre o shell de login.
                else -> linux.sessionScriptCommand(waits)
            }
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

    /** Linha de shell que espera (até 10 s) o arquivo-sinal [name] em /tmp do Debian sumir. */
    private fun waitForFile(name: String): String =
        "i=0; while [ -e /tmp/$name ] && [ \$i -lt 100 ]; do sleep 0.1; i=\$((i+1)); done; unset i\n"

    fun session(handle: String): TerminalSession? = sessions.firstOrNull { it.mHandle == handle }

    /** Há uma sessão de terminal do Claude Code rodando agora (na pasta [folder], se dada)? */
    fun hasClaudeTerminal(folder: String? = null): Boolean = sessions.any {
        sessionAgent[it.mHandle] == CLAUDE && it.isRunning && (folder == null || sessionFolder[it.mHandle].orEmpty() == folder)
    }

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
     * Instala, atualiza ou desinstala um agente sem abrir o terminal. A saída fica
     * guardada na tarefa (para "Ver detalhes") e a tela acompanha pelo
     * [onTasksChanged]. Quem chama deve ter dado startService antes.
     *
     * Fila: com outra tarefa de agente rodando, o pedido espera na [taskQueue] e
     * começa na sua vez (instalações em paralelo disputam o lock do apt). Um pedido
     * novo para o mesmo agente substitui o que esperava. Thread principal.
     */
    fun runAgentTask(recipe: AgentRecipe, updating: Boolean, uninstalling: Boolean = false) {
        if (tasks[recipe.id]?.state == AgentTask.State.RUNNING) return
        // Pedido novo substitui o que esperava na fila para este agente.
        taskQueue.removeAll { it.recipe.id == recipe.id }
        // Com fila, entra no fim mesmo sem nada rodando (a anterior pode estar acabando): não passa na frente.
        if (cleaningCaches() || taskQueue.isNotEmpty() || tasks.values.any { it.state == AgentTask.State.RUNNING }) {
            taskQueue.addLast(QueuedTask(recipe, updating, uninstalling))
            goForeground()
            updateNotification()
            onTasksChanged?.invoke()
            runNextQueued()
            return
        }
        startAgentTask(recipe, updating, uninstalling)
    }

    /** Começa a tarefa do agente agora, sem olhar a fila. Desinstalar fecha antes os chats do agente. */
    private fun startAgentTask(recipe: AgentRecipe, updating: Boolean, uninstalling: Boolean) {
        val task = AgentTask(recipe, updating, uninstalling)
        tasks[recipe.id] = task
        goForeground()
        // Depois de registrar a tarefa: fechar a última sessão do agente não pode tirar o serviço do primeiro plano.
        val chatsGone = if (uninstalling) closeAgent(recipe.id) else null
        updateNotification()
        onTasksChanged?.invoke()
        val worker = thread(start = false, name = "jangada-agent-${recipe.id}") {
            // Desinstalar: espera os chats do agente saírem (no máximo 5 s; a contagem desce na thread principal).
            chatsGone?.let { runCatching { it.await(5, java.util.concurrent.TimeUnit.SECONDS) } }
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
                // Com a fila andando, um aviso só no fim, mostrando a primeira falha se houve.
                if (task.state == AgentTask.State.FAILED && !task.cancelled && batchFailure == null) batchFailure = task
                if (taskQueue.isEmpty()) {
                    // A falha só vale se ainda for a última tarefa do agente (Tentar de novo pode ter resolvido).
                    val report = batchFailure?.takeIf { tasks[it.recipe.id] === it } ?: task.takeIf { !it.cancelled }
                    batchFailure = null
                    if (!homeScreenVisible && report != null) notifyTaskDone(report)
                }
                // Antes de sair do primeiro plano: a próxima da fila já o mantém.
                runNextQueued()
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
        cancelAgentTasks()
        clones.keys.toList().forEach { cancelClone(it) }
    }

    /**
     * Cancela tudo que é de agente, a fila inteira e as tarefas rodando (Cancelar todos, na
     * tela inicial). Não cancela os clones. Thread principal.
     */
    fun cancelAllTasks() {
        cancelAgentTasks()
        onTasksChanged?.invoke()
        updateNotification()
        leaveForegroundIfIdle()
    }

    /** Esvazia a fila e cancela as tarefas de agente rodando. */
    private fun cancelAgentTasks() {
        // A fila sai antes: o fim de uma tarefa cancelada não pode puxar a próxima.
        taskQueue.clear()
        batchFailure = null
        tasks.keys.toList().forEach { cancelTask(it) }
    }

    /**
     * Põe na fila a atualização dos agentes [recipes] (Atualizar todos): rodam um de cada
     * vez, na ordem da lista. Quem já está atualizando ou na fila fica de fora. Thread
     * principal; quem chama deve ter dado startService antes.
     */
    fun updateAll(recipes: List<AgentRecipe>) {
        var added = false
        for (recipe in recipes) {
            if (tasks[recipe.id]?.state == AgentTask.State.RUNNING || queued(recipe.id) != null) continue
            taskQueue.addLast(QueuedTask(recipe, updating = true, uninstalling = false))
            added = true
        }
        if (!added) return
        goForeground()
        updateNotification()
        onTasksChanged?.invoke()
        runNextQueued()
    }

    /** O pedido que espera a vez para o agente [agentId] (instalar, atualizar ou desinstalar), ou null. */
    fun queued(agentId: String): QueuedTask? = taskQueue.firstOrNull { it.recipe.id == agentId }

    /** true se há tarefas esperando a vez. */
    fun hasQueue(): Boolean = taskQueue.isNotEmpty()

    /** true se uma tarefa de agente (instalar, atualizar, desinstalar) roda ou espera na fila. Só na thread principal. */
    fun agentTaskBusy(): Boolean = hasQueue() || tasks.values.any { it.state == AgentTask.State.RUNNING }

    /** true enquanto a limpeza dos caches roda (ou até a marca cair, logo antes do fim chegar à thread principal). */
    fun cleaningCaches(): Boolean = cacheCleanerRunning

    /**
     * Limpeza segura do Linux ([CACHE_CLEAN_SCRIPT]) numa thread: caches de download, temporários de
     * navegador e versões antigas do Claude Code. Não começa (devolve false) com outra limpeza ou uma
     * tarefa de agente rodando ou na fila. [done] roda na thread principal e recebe quantos kB o script
     * disse ter liberado (linha JANGADA_FREED_KB; 0 se não veio).
     * Entra em primeiro plano como as tarefas de agente, para o Android não matar a limpeza ao sair da tela.
     * Só na thread principal.
     */
    fun cleanCaches(done: (freedKb: Long) -> Unit): Boolean {
        if (cleaningCaches() || agentTaskBusy()) return false
        cacheCleanerRunning = true
        // Pedido em segundo plano (limpeza automática), o Android pode recusar o primeiro plano: sem a marca presa.
        try {
            goForeground()
        } catch (e: RuntimeException) {
            cacheCleanerRunning = false
            return false
        }
        val worker = thread(start = false, name = "jangada-limpeza") {
            var freedKb = 0L
            runCatching {
                // As peças do app (PATH dos instaladores) antes do script. Sem mexer no ~/.claude.json:
                // a limpeza não abre o Claude, e ele pode estar gravando o arquivo num chat aberto.
                val linux = LinuxEnv(this)
                linux.refreshIntegration(trustClaude = false)
                linux.runBash(CACHE_CLEAN_SCRIPT) { line ->
                    if (line.startsWith(CACHE_FREED_PREFIX)) {
                        freedKb = line.removePrefix(CACHE_FREED_PREFIX).trim().toLongOrNull() ?: 0L
                    }
                }
            }
            // A marca cai antes do post: a fila da thread principal já pode andar.
            cacheCleanerRunning = false
            val freed = freedKb
            main.post {
                // Tarefas de agente pedidas durante a limpeza esperaram na fila (ver runAgentTask).
                runNextQueued()
                done(freed)
                updateNotification()
                leaveForegroundIfIdle()
            }
        }
        cacheCleaner = worker
        worker.start()
        return true
    }

    /** Checagem da limpeza automática (ver [maybeAutoClean]). Só a thread principal mexe. */
    private val autoCleanCheck = Runnable { maybeAutoClean() }

    /** Agenda a checagem da limpeza automática daqui a pouco (sem competir com a abertura do app). */
    private fun scheduleAutoClean() {
        main.removeCallbacks(autoCleanCheck)
        main.postDelayed(autoCleanCheck, AUTO_CLEAN_DELAY_MS)
    }

    /**
     * Limpeza automática: no máximo uma vez a cada [AUTO_CLEAN_INTERVAL_MS], com o Linux instalado, sem
     * instalação do Linux, limpeza, nem tarefa de agente rodando ou na fila (as mesmas condições do botão
     * Liberar espaço, ver [agentTaskBusy]), e também sem terminal aberto nem agente rodando. Sem aviso: cleanCaches só põe o serviço em primeiro plano
     * enquanto limpa. Só na thread principal.
     */
    private fun maybeAutoClean() {
        val settings = AppSettings(this)
        if (!settings.autoClean) return
        if (!runCatching { LinuxEnv(this).isReady }.getOrDefault(false)) return
        if (Setup.state is Setup.State.Running || cleaningCaches() || agentTaskBusy()) return
        // Com terminal aberto ou agente rodando, algo pode estar usando os caches ou o /tmp agora
        // (npm install, um navegador de teste): a automática espera; o botão Liberar espaço continua valendo.
        if (liveSessions() > 0 || chats.values.any { it.chat.running } || foregroundHolds > 0) return
        val now = System.currentTimeMillis()
        val last = settings.lastAutoCleanAt
        // Relógio que voltou (last no futuro) também conta como vencido, em vez de travar a limpeza.
        if (last in 1..now && now - last < AUTO_CLEAN_INTERVAL_MS) return
        // Grava antes de começar: se a limpeza falhar, não tenta de novo em loop.
        settings.lastAutoCleanAt = now
        cleanCaches { }
    }

    /** Ids dos agentes na fila; a tela compara com o que desenhou para saber se precisa redesenhar. */
    fun queuedIds(): Set<String> = taskQueue.mapTo(HashSet()) { it.recipe.id }

    /** Tira o pedido de [agentId] da fila. Ainda não começou, então só some. */
    fun unqueue(agentId: String) {
        val index = taskQueue.indexOfFirst { it.recipe.id == agentId }
        if (index < 0) return
        taskQueue.removeAt(index)
        onTasksChanged?.invoke()
        updateNotification()
        leaveForegroundIfIdle()
    }

    /** Começa o próximo da fila, se nenhuma tarefa de agente está rodando. */
    private fun runNextQueued() {
        if (cleaningCaches() || tasks.values.any { it.state == AgentTask.State.RUNNING }) return
        val next = taskQueue.removeFirstOrNull() ?: return
        startAgentTask(next.recipe, next.updating, next.uninstalling)
    }

    /**
     * Clona [url] para a pasta nova [folder] de ~/projetos, em segundo plano, para o agente [agentId].
     * Devolve null (e não clona) se a pasta já está sendo clonada, ou se o endereço ou o nome são
     * inválidos. Quem chama deve ter dado startService antes (goForeground já faz isso).
     */
    fun runClone(agentId: String, url: String, folder: String): CloneTask? {
        if (clones[folder]?.state == CloneTask.State.RUNNING || folder in busyFolders) return null
        if (!Projects.isCloneUrl(url) || !Workspace.isValidName(folder)) return null
        return launchFolderTask(CloneTask(agentId, url, folder), Projects.cloneScript(url, folder))
    }

    /**
     * Duplica o projeto [source] para a pasta nova [folder] de ~/projetos, em segundo plano (cópia
     * inteira, sem seguir links, ver [Projects.copyScript]). Devolve null (e não copia) se uma das
     * pastas está ocupada (sendo clonada, copiada, excluída ou renomeada) ou os nomes não servem.
     * Ao terminar sem tela acompanhando, o aviso abre a tela de Projetos. Thread principal.
     */
    fun runCopy(source: String, folder: String): CloneTask? {
        if (clones[folder]?.state == CloneTask.State.RUNNING || folder in busyFolders || source in busyFolders) return null
        if (!Workspace.isListable(source) || !Workspace.isValidName(folder) || source == folder) return null
        return launchFolderTask(CloneTask("", "", folder, source), Projects.copyScript(source, folder))
    }

    /** Roda o [script] de clone ou cópia de [task] numa thread (ver [runClone]). */
    private fun launchFolderTask(task: CloneTask, script: String): CloneTask {
        val folder = task.folder
        clones[folder] = task
        goForeground()
        updateNotification()
        val worker = thread(start = false, name = "jangada-clone-$folder") {
            val exit = runCatching {
                LinuxEnv(this).runBash(script, onStart = { process ->
                    task.process = process
                    // Cancelado antes de o processo nascer: encerra logo.
                    if (task.cancelled) process.destroy()
                }) { raw ->
                    val line = cleanLine(raw)
                    if (line.isEmpty()) return@runBash
                    task.append(line)
                    task.lastLine = line
                }
            }.getOrElse { e ->
                // Ao cancelar, a leitura da saída falha de propósito: não é erro.
                if (!task.cancelled) task.append(getString(R.string.task_error, e.message.orEmpty()))
                -1
            }
            task.process = null
            if (task.cancelled && exit != 0) task.append(getString(R.string.task_cancelled))
            if (!task.cancelled && exit == Projects.COPY_NO_SPACE) {
                task.append(getString(R.string.projects_copy_no_space))
                task.lastLine = getString(R.string.projects_copy_no_space)
            }
            // Sem o clone, a pasta oculta pode ter ficado (o processo foi morto no meio): apaga.
            if (exit != 0) runCatching { LinuxEnv(this).run(Projects.cleanupScript(folder)) { } }
            task.state = when {
                // Cancelar tarde demais (o script já tinha renomeado a pasta): o clone vale.
                exit == 0 -> CloneTask.State.OK
                task.cancelled -> CloneTask.State.FAILED
                exit == Projects.CLONE_EXISTS -> CloneTask.State.EXISTS
                else -> CloneTask.State.FAILED
            }
            main.post {
                updateNotification()
                if (!task.watched) reportClone(task)
                leaveForegroundIfIdle()
            }
        }
        task.worker = worker
        worker.start()
        return task
    }

    /**
     * Cancela o clone da pasta [folder]: pede para o git sair e, se ele não sair em 2 s, força.
     * Sem clone rodando nessa pasta, não faz nada.
     */
    fun cancelClone(folder: String) {
        val task = clones[folder]?.takeIf { it.state == CloneTask.State.RUNNING } ?: return
        task.cancelled = true
        task.process?.destroy()
        thread(name = "jangada-cancel-clone-$folder") {
            runCatching { Thread.sleep(2_000) }
            task.process?.takeIf { it.isAlive }?.destroyForcibly()
        }
        main.post { updateNotification() }
    }

    /**
     * A tela deixou de acompanhar o clone (Em segundo plano, ou a tela fechou): o aviso sai quando
     * ele terminar. Se já terminou, o aviso sai agora. Thread principal.
     */
    fun unwatchClone(task: CloneTask) {
        task.watched = false
        if (task.state != CloneTask.State.RUNNING) reportClone(task)
    }

    /**
     * A tela voltou e quer acompanhar de novo um clone que ela deixou de ver só porque saiu da frente
     * (não pelo botão Em segundo plano). Verdadeiro se o clone ainda roda e passou a ser acompanhado:
     * o aviso de fim não sai, a tela trata o resultado. Thread principal (a mesma do fim do clone).
     */
    fun rewatchClone(task: CloneTask): Boolean {
        if (task.state != CloneTask.State.RUNNING || task.watched || task.sentToBackground || task.reported) return false
        task.watched = true
        return true
    }

    /**
     * Aviso do fim de um clone que nenhuma tela está acompanhando. Clone OK abre o chat na pasta
     * nova; pasta que já existia ou falha abrem a tela inicial. Cancelado (sem clone) não avisa.
     */
    private fun reportClone(task: CloneTask) {
        if (task.reported) return
        task.reported = true
        if (task.cancelled && task.state != CloneTask.State.OK) return
        // Um código por pasta: com o mesmo, o PendingIntent de um aviso trocaria o extra do outro.
        val requestCode = REQUEST_CLONE + task.folder.hashCode() * 31
        if (task.source != null) {
            // Cópia: o aviso abre a tela de Projetos (ela não é de nenhum agente).
            val open = Intent(this, ProjectsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val ok = task.state == CloneTask.State.OK
            // Falha: a tela abre o log da cópia (o PendingIntent troca o extra pelo do último aviso).
            if (!ok) open.putExtra(EXTRA_CLONE_FAILED, task.folder)
            val text = when (task.state) {
                CloneTask.State.OK -> getString(R.string.projects_copy_notify_done_text, task.source)
                CloneTask.State.EXISTS -> getString(R.string.project_exists)
                else -> task.lastLine
            }
            attentionNotification(
                requestCode, NOTIFICATION_CLONE,
                getString(if (ok) R.string.projects_copy_notify_done else R.string.projects_copy_notify_failed, task.folder),
                text, open, tag = task.folder,
            )
            return
        }
        if (task.state == CloneTask.State.OK) {
            val open = Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_AGENT_ID, task.agentId)
                .putExtra(ChatActivity.EXTRA_FOLDER, task.folder)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            attentionNotification(
                requestCode, NOTIFICATION_CLONE,
                getString(R.string.clone_notify_done, task.folder),
                getString(R.string.clone_notify_done_text),
                open, tag = task.folder,
            )
        } else {
            val open = Intent(this, HomeActivity::class.java)
                .putExtra(EXTRA_CLONE_FAILED, task.folder)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val text = if (task.state == CloneTask.State.EXISTS) getString(R.string.project_exists) else task.lastLine
            attentionNotification(
                requestCode, NOTIFICATION_CLONE,
                getString(R.string.clone_notify_failed, task.folder), text,
                open, tag = task.folder,
            )
        }
    }

    /**
     * Fecha os chats (em todas as pastas) e as sessões de terminal de um agente (antes de
     * desinstalar). O latch devolvido libera quando todos esses chats terminaram de sair.
     */
    private fun closeAgent(agentId: String): java.util.concurrent.CountDownLatch {
        val slots = chats.values.filter { it.agentId == agentId }
        val gone = java.util.concurrent.CountDownLatch(slots.size)
        slots.forEach { closeChat(agentId, it.folder) { gone.countDown() } }
        sessionAgent.filterValues { it == agentId }.keys.toList().forEach { closeSession(it) }
        return gone
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

    private fun hasRunningTasks() =
        tasks.values.any { it.state == AgentTask.State.RUNNING } || clones.values.any { it.state == CloneTask.State.RUNNING }

    /** Há algo que mantenha o serviço em primeiro plano (sessão, chat, instalação ou Linux)? */
    /**
     * Abas de terminal com o processo vivo. Uma aba encerrada (exit) continua na tela até ser fechada,
     * mas não segura o primeiro plano, o WakeLock nem a limpeza automática.
     */
    private fun liveSessions() = sessions.count { it.isRunning }

    private fun hasWork() =
        liveSessions() > 0 || chats.isNotEmpty() || hasRunningTasks() || taskQueue.isNotEmpty() ||
            cleaningCaches() || Setup.state is Setup.State.Running || foregroundHolds > 0

    /** Tarefas das telas que pediram o primeiro plano (backup, restauração). Só a thread principal mexe. */
    private var foregroundHolds = 0

    /**
     * Segura o serviço em primeiro plano enquanto uma tela faz algo longo fora dele (exportar ou restaurar
     * backup): sem isso, ao sair do app, o Android pode matar o processo no meio do tar. Cada chamada que
     * devolve true pede um [releaseForeground]. Só na thread principal.
     */
    fun holdForeground(): Boolean {
        try {
            goForeground()
        } catch (e: RuntimeException) {
            return false
        }
        foregroundHolds++
        updateNotification()
        return true
    }

    /** Fim de uma tarefa de [holdForeground]: o serviço sai do primeiro plano se não sobrou nada. */
    fun releaseForeground() {
        if (foregroundHolds > 0) foregroundHolds--
        updateNotification()
        leaveForegroundIfIdle()
    }

    private fun leaveForegroundIfIdle() {
        if (!hasWork()) leaveForeground()
    }

    /** Estado dos trabalhos mudou: atualiza o aviso e as telas. Também acorda o vigia de memória, se há chat rodando. */
    internal fun sessionsChanged() {
        dropStalePermissionNotices()
        updateNotification()
        memoryWatch.ensureRunning()
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
     * Encerra todas as sessões de terminal, limpa os mapas por sessão e solta os
     * chats (e seus avisos). Devolve os chats soltos para quem chama parar: cada
     * saída espera de um jeito diferente. Não cancela tarefas nem avisa as telas;
     * isso fica com quem chama, na ordem que cada um precisa.
     */
    private fun tearDownSessions(): List<AgentChat> {
        sessions.forEach { it.finishIfRunning() }
        sessions.clear()
        sessionAgent.clear()
        sessionStartedAt.clear()
        sessionConversation.clear()
        sessionFolder.clear()
        val gone = chats.values.map { it.chat }
        gone.forEach { it.onAttention = null }
        chats.keys.forEach { notifications().cancel(it, NOTIFICATION_CHAT) }
        chats.clear()
        return gone
    }

    private fun exit() {
        tearDownSessions().forEach { it.stop() }
        cancelRunningTasks()
        sessionListeners.forEach { it() }
        onExit?.invoke()
        leaveForeground()
    }

    /**
     * Encerra tudo (sessões, chats, instalações em andamento) antes de reinstalar
     * ou restaurar o Linux. [then] roda na thread principal, depois que todos os
     * chats saíram e as instalações terminaram (no máximo 5 s para cada espera).
     * A espera fica numa thread própria: a saída dos chats é contada na principal.
     */
    fun closeEverything(then: () -> Unit) {
        val gone = tearDownSessions()
        // O vigia de links fica numa pasta do Linux que vai sumir: para e solta. startLinkWatcher o
        // recria na próxima sessão ou chat, que só existem depois que o Linux volta.
        linkWatcher?.stopWatching()
        linkWatcher = null
        cancelRunningTasks()
        sessionListeners.forEach { it() }
        // Espera a saída de todos os chats soltos (sem nenhum, o latch já está liberado).
        val chatsGone = java.util.concurrent.CountDownLatch(gone.size)
        gone.forEach { it.stop { chatsGone.countDown() } }
        val workers = tasks.values.mapNotNull { it.worker } + clones.values.mapNotNull { it.worker }
        thread(name = "jangada-close") {
            runCatching { chatsGone.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            workers.forEach { runCatching { it.join(5_000) } }
            // Apagar o Linux enquanto a limpeza apaga parte dele faria as duas falharem.
            legacyCleanup?.let { runCatching { it.join(60_000) } }
            cacheCleaner?.let { runCatching { it.join(60_000) } }
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
        applyCpuAwake()
    }

    /**
     * Segura o processador acordado só enquanto o serviço está em primeiro plano e o ajuste
     * está ligado. Chamado ao entrar no primeiro plano e quando o ajuste muda. Thread principal.
     */
    private fun applyCpuAwake() {
        val wanted = foreground && AppSettings(this).keepCpuAwake
        val held = wakeLock
        if (wanted && held == null) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jangada:sessions").apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!wanted && held != null) {
            if (held.isHeld) held.release()
            wakeLock = null
        }
    }

    private fun updateNotification() {
        if (foreground) notifications().notify(NOTIFICATION_SESSIONS, buildNotification())
    }

    private fun buildNotification(): Notification {
        // O toque volta para a tela em que o usuário estava (ver ReturnActivity): o último chat, senão o mais recente.
        val slot = lastChat?.let { chats[it] } ?: chats.values.lastOrNull()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, ReturnActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (slot != null) {
                    putExtra(ChatActivity.EXTRA_AGENT_ID, slot.agentId)
                    putExtra(ChatActivity.EXTRA_FOLDER, slot.folder)
                }
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val exit = PendingIntent.getService(
            this, 1,
            Intent(this, TerminalService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val count = liveSessions() + chats.size
        val running = tasks.values.firstOrNull { it.state == AgentTask.State.RUNNING }
        val cloning = clones.values.firstOrNull { it.state == CloneTask.State.RUNNING }
        val setup = Setup.state as? Setup.State.Running
        val text = when {
            setup != null -> {
                val progress = setup.progress
                if (progress != null) getString(R.string.service_setup_progress, (progress * 100).toInt())
                else getString(R.string.service_setup)
            }
            cloning != null -> getString(if (cloning.source != null) R.string.projects_copy_running else R.string.clone_running, cloning.folder)
            running != null -> getString(
                when {
                    running.uninstalling -> R.string.task_uninstalling
                    running.updating -> R.string.task_updating
                    else -> R.string.task_installing
                },
                running.recipe.name,
            )
            cleaningCaches() -> getString(R.string.storage_cleaning)
            foregroundHolds > 0 && count == 0 -> getString(R.string.service_backup)
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

    /**
     * Aviso de um trabalho de chat (agente + pasta): o agente precisa de você ou terminou.
     * Com a tela desse trabalho aberta, não avisa. Cada trabalho tem a sua notificação.
     */
    private fun onChatAttention(agentId: String, folder: String, attention: ChatAttention) {
        val key = chatKey(agentId, folder)
        if (visibleChat == key) return
        val name = chatName(agentId, folder)
        val (title, text) = when (attention) {
            ChatAttention.PERMISSION -> R.string.notify_permission_title to R.string.notify_permission_text
            ChatAttention.LOGIN -> R.string.notify_login_title to R.string.notify_login_text
            ChatAttention.FINISHED -> R.string.notify_finished_title to R.string.notify_finished_text
            ChatAttention.FAILED -> R.string.notify_failed_title to R.string.notify_failed_text
            ChatAttention.LIMIT -> R.string.notify_limit_title to R.string.notify_limit_text
        }
        val open = Intent(this, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_AGENT_ID, agentId)
            .putExtra(ChatActivity.EXTRA_FOLDER, folder)
            // Com uma tela de chat no topo, ela recebe o pedido (onNewIntent) em vez de empilhar outra.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Request code próprio do trabalho: com o mesmo, o PendingIntent de um aviso trocaria os extras do outro.
        val requestCode = key.hashCode()
        if (attention == ChatAttention.LIMIT) {
            // Aviso próprio, um por agente (o limite é da conta): não apaga o pedido de permissão do trabalho
            // nem some quando o aviso de fim de resposta chega logo depois.
            val limit = chats[key]?.chat?.limitCrossed ?: return
            attentionNotification(
                requestCode, NOTIFICATION_LIMIT, getString(title), limitText(chatName(agentId, ""), limit), open,
                tag = agentId,
            )
            return
        }
        val slot = chats[key]
        val pending = if (attention == ChatAttention.PERMISSION) slot?.let { pendingPermission(it.chat) } else null
        if (pending != null) {
            // Pedido de permissão: mostra o que o agente quer e deixa responder daqui.
            val asked = if (pending.summary.isEmpty()) {
                pending.title
            } else {
                getString(R.string.notify_permission_wants, pending.title, pending.summary)
            }
            // Pedido de um subagente: o nome dele entra no texto (o resto do formato fica igual).
            val wants = pending.childSessionId?.let { childId ->
                val label = ChildAgents.label(childId, slot?.chat?.items?.toList().orEmpty(), pending.childTitle)
                getString(R.string.notify_permission_child, label.ifEmpty { getString(R.string.chat_child_title) }, asked)
            } ?: asked
            val full = if (pending.detail.isEmpty()) wants else wants + "\n" + pending.detail
            attentionNotification(
                requestCode, NOTIFICATION_CHAT, getString(title, name), clip(wants, NOTIFY_TEXT_MAX), open,
                tag = key, big = clip(full, NOTIFY_BIG_MAX),
                actions = permissionActions(key, pending, slot?.chat?.cwd),
            )
            permissionNotices += key
        } else {
            attentionNotification(requestCode, NOTIFICATION_CHAT, getString(title, name), getString(text), open, tag = key)
            permissionNotices -= key
        }
        // A lista da tela inicial mostra o estado de cada trabalho: redesenha.
        sessionsChanged()
    }

    /** Texto do aviso de limite: o agente, a janela, o percentual e, se houver, quando renova. */
    private fun limitText(name: String, limit: LimitWindow): String {
        val what = limitKindName(this, limit)
        val reset = limit.resetsAtMs ?: return getString(R.string.notify_limit_text_noreset, name, what, limit.usedPercent)
        return getString(R.string.notify_limit_text, name, what, limit.usedPercent, formatLimitReset(this, reset))
    }

    /** Nome de um trabalho nos avisos: o agente e, se não for a raiz, a pasta. */
    private fun chatName(agentId: String, folder: String): String {
        val agent = AgentCatalog.load(this).firstOrNull { it.id == agentId }?.name ?: "Claude Code"
        return if (folder.isEmpty()) agent else "$agent · $folder"
    }

    /**
     * Aviso de pouca memória com agente rodando (ver [MemoryWatch]). [heaviest] é o trabalho que mais usa
     * (chave e bytes), se há relatório. Ao toque, abre esse chat; sem ele, a tela inicial.
     */
    internal fun notifyLowMemory(heaviest: Pair<String, Long>?) {
        val slot = heaviest?.let { chats[it.first] }
        val text = if (heaviest != null && slot != null) {
            getString(
                R.string.notify_memory_text,
                chatName(slot.agentId, slot.folder),
                Formatter.formatShortFileSize(this, heaviest.second),
            )
        } else {
            getString(R.string.notify_memory_text_generic)
        }
        val open = if (slot != null) {
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_AGENT_ID, slot.agentId)
                .putExtra(ChatActivity.EXTRA_FOLDER, slot.folder)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        attentionNotification(REQUEST_MEMORY, NOTIFICATION_MEMORY, getString(R.string.notify_memory_title), text, open)
    }

    /** Some com o aviso de pouca memória (a memória voltou ao normal). */
    internal fun cancelLowMemory() = notifications().cancel(NOTIFICATION_MEMORY)

    /**
     * Some com o aviso de permissão cujo trabalho não tem mais pedido pendente (o CLI cancelou, o agente
     * parou ou foi respondido sem a tela do chat aberta). Não há evento para isso: roda a cada
     * [sessionsChanged], que já acontece a cada aviso, abertura ou fechamento de trabalho. Sem esse gancho,
     * o aviso velho sai no próximo aviso do trabalho (a mesma tag o substitui) ou ao abrir o chat.
     */
    private fun dropStalePermissionNotices() {
        if (permissionNotices.isEmpty()) return
        for (key in permissionNotices.toList()) {
            val chat = chats[key]?.chat
            if (chat == null || pendingPermission(chat) == null) {
                notifications().cancel(key, NOTIFICATION_CHAT)
                permissionNotices -= key
            }
        }
    }

    /**
     * Pedido de permissão mais antigo ainda sem resposta do trabalho (cópia da lista: a conversa muda).
     * O primeiro é o que o cartão deixa responder; a faixa e o aviso falam dele.
     */
    private fun pendingPermission(chat: AgentChat): ChatItem.Permission? =
        chat.items.toList().filterIsInstance<ChatItem.Permission>().firstOrNull { it.chosen == null }

    /**
     * Botões Permitir e Recusar do aviso: só "uma vez" (nunca "sempre"). Cada botão aparece
     * só se o pedido tem a opção correspondente. Em alto risco o Permitir não aparece aqui:
     * o aviso não consegue pedir o segundo toque que o cartão exige.
     */
    private fun permissionActions(key: String, pending: ChatItem.Permission, project: String?): List<Notification.Action> {
        // Pergunta e plano: o aviso só abre o chat (a resposta é nos campos do cartão; o plano se lê antes de aprovar).
        if (pending.questions.isNotEmpty() || pending.toolName == ChatEngine.PLAN_TOOL) return emptyList()
        val command = pending.summary.ifBlank { pending.title }
        // Edição/escrita não traz cwd: o próprio comando é o caminho do arquivo (vale para "fora do projeto").
        val risk = PermissionRisk.analyze(
            command,
            PermissionRisk.workPath(pending.workdir, pending.toolName, command),
            project,
        )
        val actions = mutableListOf<Notification.Action>()
        val requestId = pending.requestId.toString()
        if (!risk.high && pending.options.any { it.kind == PermissionOption.Kind.ALLOW_ONCE }) {
            actions += answerAction(key, requestId, allow = true, requestCode = key.hashCode() * 31 + 1)
        }
        if (pending.options.any { it.kind == PermissionOption.Kind.REJECT_ONCE }) {
            actions += answerAction(key, requestId, allow = false, requestCode = key.hashCode() * 31 + 2)
        }
        return actions
    }

    /** Botão do aviso que responde ao pedido [requestId] do trabalho [key] (ver [answerPermission]). */
    private fun answerAction(key: String, requestId: String, allow: Boolean, requestCode: Int): Notification.Action {
        val intent = Intent(this, TerminalService::class.java)
            .setAction(ACTION_ANSWER_PERMISSION)
            .putExtra(EXTRA_CHAT_KEY, key)
            .putExtra(EXTRA_REQUEST, requestId)
            .putExtra(EXTRA_ALLOW, allow)
        val pending = PendingIntent.getService(
            this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notification),
            getString(if (allow) R.string.notify_action_allow else R.string.notify_action_deny),
            pending,
        )
        // Permitir pela tela bloqueada exige desbloquear o aparelho antes.
        if (allow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAuthenticationRequired(true)
        return builder.build()
    }

    /**
     * Responde ao pedido de permissão tocado no aviso. Sem o pedido (já respondido na tela,
     * ou o chat fechou), só some com o aviso. Se ainda sobrar pedido no trabalho, avisa de novo.
     */
    private fun answerPermission(intent: Intent) {
        val key = intent.getStringExtra(EXTRA_CHAT_KEY) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST)
        val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
        val slot = chats[key]
        val pending = slot?.chat?.let { chat ->
            chat.items.toList().filterIsInstance<ChatItem.Permission>()
                .firstOrNull { it.chosen == null && it.requestId.toString() == requestId }
        }
        if (slot != null && pending != null && slot.chat.running) {
            val kind = if (allow) PermissionOption.Kind.ALLOW_ONCE else PermissionOption.Kind.REJECT_ONCE
            pending.options.firstOrNull { it.kind == kind }?.let { slot.chat.answer(pending, it) }
        }
        if (slot != null && pendingPermission(slot.chat) != null) {
            // Sobrou outro pedido: o novo aviso substitui este (mesma tag).
            onChatAttention(slot.agentId, slot.folder, ChatAttention.PERMISSION)
        } else {
            notifications().cancel(key, NOTIFICATION_CHAT)
            permissionNotices -= key
            sessionsChanged()
        }
    }

    /** Texto cortado em [max] caracteres (com reticências), para caber no aviso. */
    private fun clip(text: String, max: Int) = if (text.length > max) text.take(max).trimEnd() + "…" else text

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

    /**
     * Mostra um aviso no canal de atenção; ao toque, abre [target] e o aviso some. [tag] separa avisos de mesmo [id].
     * [big] é o texto expandido ([Notification.BigTextStyle]); [actions] são os botões do aviso.
     */
    private fun attentionNotification(
        requestCode: Int,
        id: Int,
        title: String,
        text: String,
        target: Intent,
        tag: String? = null,
        big: String? = null,
        actions: List<Notification.Action> = emptyList(),
    ) {
        val pending = PendingIntent.getActivity(
            this, requestCode, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
        big?.let { builder.setStyle(Notification.BigTextStyle().bigText(it)) }
        actions.forEach { builder.addAction(it) }
        notifications().notify(tag, id, builder.build())
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
            // A aba fica, mas o processo acabou: o aviso conta de novo e o serviço pode sair do primeiro plano.
            main.post {
                sessionsChanged()
                leaveForegroundIfIdle()
            }
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
        /** Extra do aviso de clone ou cópia que falhou: a pasta, para a tela abrir o log dela. */
        const val EXTRA_CLONE_FAILED = "clone_failed"
        /** Permitir ou recusar um pedido pelo aviso (extras: chave do trabalho, id do pedido e se permite). */
        private const val ACTION_ANSWER_PERMISSION = "io.github.jonjonesbr.jangada.ANSWER_PERMISSION"
        private const val EXTRA_CHAT_KEY = "io.github.jonjonesbr.jangada.CHAT_KEY"
        private const val EXTRA_REQUEST = "io.github.jonjonesbr.jangada.REQUEST"
        private const val EXTRA_ALLOW = "io.github.jonjonesbr.jangada.ALLOW"
        private const val CHANNEL_SESSIONS = "sessions"
        private const val CHANNEL_LINKS = "links"
        private const val CHANNEL_ATTENTION = "attention"
        private const val NOTIFICATION_SESSIONS = 1
        private const val NOTIFICATION_LINK = 2
        /** Avisos de chat (permissão, login, resposta pronta ou erro): um por trabalho, com a chave como tag. */
        private const val NOTIFICATION_CHAT = 3
        /** Aviso de tarefa de agente ou da instalação do Linux terminada. */
        private const val NOTIFICATION_TASK = 4
        /** Aviso de clone terminado (ou falhou): um por pasta, com o nome da pasta como tag. */
        private const val NOTIFICATION_CLONE = 5
        /** Aviso de pouca memória com agente rodando (ver MemoryWatch): um só, sem tag. */
        private const val NOTIFICATION_MEMORY = 6
        /** Aviso de limite do plano acima de 90%: um por agente, com o id do agente como tag. */
        private const val NOTIFICATION_LIMIT = 7
        private const val REQUEST_HOME = 10
        private const val REQUEST_SETUP = 11
        /** Toque no aviso de pouca memória (o trabalho que mais usa muda: o PendingIntent é atualizado). */
        private const val REQUEST_MEMORY = 13
        /** Base do código do PendingIntent de cada aviso de clone (soma o hash da pasta). */
        private const val REQUEST_CLONE = 12
        /** Limites do texto do aviso de permissão (linha de baixo e texto expandido). */
        private const val NOTIFY_TEXT_MAX = 200
        private const val NOTIFY_BIG_MAX = 1000
        private const val TRANSCRIPT_ROWS = 5000
        /** Limite de links abertos direto na tela: [MAX_DIRECT_LINKS] a cada [DIRECT_LINK_WINDOW_MS]. */
        private const val MAX_DIRECT_LINKS = 3
        private const val DIRECT_LINK_WINDOW_MS = 30_000L
        /** Limpeza automática: no máximo uma por dia, checada 2 min depois de o serviço ser pedido. */
        private const val AUTO_CLEAN_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val AUTO_CLEAN_DELAY_MS = 2L * 60 * 1000
        const val DEFAULT_TITLE = "Terminal"
        const val CLAUDE = "claude-code"

        /**
         * Limpeza dos caches (cleanCaches). Fica no companion, e não na instância, para a instância nova
         * (serviço recriado com o processo vivo) saber que a antiga ainda limpa. closeEverything espera a thread.
         */
        @Volatile private var cacheCleaner: Thread? = null
        /** true da largada da limpeza até a thread cair; a marca cai antes do post para a thread principal. */
        @Volatile private var cacheCleanerRunning = false

        /** Chave do trabalho do par (agente, pasta): um chat por par. */
        fun chatKey(agentId: String, folder: String) = "$agentId|$folder"

        private val ANSI = Regex("""\u001B(\[[0-9;?]*[ -/]*[@-~]|\][^\u0007]*\u0007|[()][A-Z0-9])""")
    }
}

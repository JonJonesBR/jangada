package io.github.jonjonesbr.jangada

import android.content.Context
import android.content.SharedPreferences
import io.github.jonjonesbr.jangada.chat.AutoCompact
import io.github.jonjonesbr.jangada.linux.ProjectOps

/** Preferências do usuário (tela de configurações). */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** Compactação automática por trabalho: chave = TerminalService.chatKey; ausente = segue o geral. */
    private val jobPrefs = context.applicationContext.getSharedPreferences("auto_compact", Context.MODE_PRIVATE)

    /** Modo Simples: chat e caixa de texto por padrão. Modo Pro: terminal primeiro. */
    var simpleMode: Boolean
        get() = prefs.getString(KEY_MODE, MODE_SIMPLE) == MODE_SIMPLE
        set(value) = prefs.edit().putString(KEY_MODE, if (value) MODE_SIMPLE else MODE_PRO).apply()

    /** Enter do teclado envia a mensagem (em vez de pular linha). */
    var enterSends: Boolean
        get() = prefs.getBoolean(KEY_ENTER_SENDS, false)
        set(value) = prefs.edit().putBoolean(KEY_ENTER_SENDS, value).apply()

    /** No chat, perguntar antes de cada ação do agente (true) ou deixar o agente decidir (false). */
    var askPermissions: Boolean
        get() = prefs.getBoolean(KEY_ASK_PERMISSIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_ASK_PERMISSIONS, value).apply()

    /** Modelo do chat: "" = padrão da conta, ou "opus", "sonnet", "haiku". */
    var chatModel: String
        get() = prefs.getString(KEY_CHAT_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CHAT_MODEL, value).apply()

    /** Esforço do Claude no chat: "" = padrão, ou low, medium, high, xhigh, max. */
    var chatEffort: String
        get() = prefs.getString(KEY_CHAT_EFFORT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CHAT_EFFORT, value).apply()

    /** Última pasta de trabalho usada no chat, subpasta de ~/projetos ("" = a raiz). Abre o trabalho dessa pasta quando a tela não diz outra. */
    var chatFolder: String
        get() = prefs.getString(KEY_CHAT_FOLDER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CHAT_FOLDER, value).apply()

    /** Visualização do chat (ver ChatView): VIEW_DETAILED, VIEW_NORMAL ou VIEW_SUMMARY. */
    var chatView: String
        get() = prefs.getString(KEY_CHAT_VIEW, VIEW_NORMAL) ?: VIEW_NORMAL
        set(value) = prefs.edit().putString(KEY_CHAT_VIEW, value).apply()

    /** Segura o processador acordado enquanto há sessões (agentes não param com a tela apagada). */
    var keepCpuAwake: Boolean
        get() = prefs.getBoolean(KEY_CPU_AWAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_CPU_AWAKE, value).apply()

    /**
     * Avisa quando o ajuste de manter o processador acordado muda. Devolve o ouvinte, que deve
     * ser desligado com [unwatchCpuAwake] (o serviço faz isso no onDestroy).
     */
    fun watchCpuAwake(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_CPU_AWAKE) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unwatchCpuAwake(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** Tela do terminal não apaga sozinha. */
    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_ON, value).apply()

    /** Barra de teclas extras do terminal (dá para esconder com teclado físico). */
    var showExtraKeys: Boolean
        get() = prefs.getBoolean(KEY_EXTRA_KEYS, true)
        set(value) = prefs.edit().putBoolean(KEY_EXTRA_KEYS, value).apply()

    /** Tema das telas do app: "system", "light" ou "dark". */
    var appTheme: String
        get() = prefs.getString(KEY_APP_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit().putString(KEY_APP_THEME, value).apply()

    /** Agentes de chat rodando ao mesmo tempo: 1..AgentLimit.MAX, ou 0 = automático (ver AgentLimit). */
    var maxAgents: Int
        get() = prefs.getInt(KEY_MAX_AGENTS, 0)
        set(value) = prefs.edit().putInt(KEY_MAX_AGENTS, value).apply()

    /** Esquema de cores do terminal (ver TerminalThemes). */
    var terminalTheme: String
        get() = prefs.getString(KEY_TERMINAL_THEME, "default") ?: "default"
        set(value) = prefs.edit().putString(KEY_TERMINAL_THEME, value).apply()

    /** Cartão de boas-vindas da tela inicial já foi dispensado ("Entendi"). */
    var welcomeDismissed: Boolean
        get() = prefs.getBoolean(KEY_WELCOME_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_WELCOME_DISMISSED, value).apply()

    /** Tamanho da fonte do terminal, em pixels; 0 = nunca escolhido (usa o padrão da tela). */
    var fontSizePx: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 0)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value).apply()

    /** Caixa de texto do terminal visível. Sem escolha salva, segue o modo (Simples mostra). */
    var composeVisible: Boolean
        get() = prefs.getBoolean(KEY_COMPOSE_VISIBLE, simpleMode)
        set(value) = prefs.edit().putBoolean(KEY_COMPOSE_VISIBLE, value).apply()

    /** Vibra quando a resposta do chat fica pronta (só com o chat aberto; ver ChatActivity). */
    var hapticOnFinish: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_FINISH, false)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_FINISH, value).apply()

    /** Orienta os agentes sobre a memória do celular: um bloco nas instruções globais de cada um (ver AgentInstructions). */
    var agentMemoryTips: Boolean
        get() = prefs.getBoolean(KEY_AGENT_MEMORY_TIPS, true)
        set(value) = prefs.edit().putBoolean(KEY_AGENT_MEMORY_TIPS, value).apply()

    /**
     * Minutos parados (sem turno) até o agente dormir para liberar memória; 0 = nunca.
     * Um de [SLEEP_AFTER_MINUTES]; outro valor guardado volta ao padrão (30). Ver MemoryWatch.
     */
    var sleepAfterMinutes: Int
        get() = prefs.getInt(KEY_SLEEP_AFTER, 30).takeIf { it in SLEEP_AFTER_MINUTES } ?: 30
        set(value) = prefs.edit().putInt(KEY_SLEEP_AFTER, value).apply()

    /** Compactação automática da conversa, ajuste geral (ver AutoCompact). */
    var autoCompact: String
        get() = AutoCompact.normalizeGeneral(prefs.getString(KEY_AUTO_COMPACT, AutoCompact.DEFAULT))
        set(value) = prefs.edit().putString(KEY_AUTO_COMPACT, value).apply()

    /** Compactação automática de um trabalho ([key] = TerminalService.chatKey): um de AutoCompact.JOB; "global" segue o geral. */
    fun autoCompactOfJob(key: String): String = AutoCompact.normalizeJob(jobPrefs.getString(key, null))

    /** Guarda a compactação de um trabalho; "global" tira a chave (o trabalho volta a seguir o geral). */
    fun setAutoCompactOfJob(key: String, value: String) {
        if (value == AutoCompact.GLOBAL) jobPrefs.edit().remove(key).apply()
        else jobPrefs.edit().putString(key, AutoCompact.normalizeJob(value)).apply()
    }

    /** O valor que vale para o trabalho [key]: o dele, ou o geral (ver AutoCompact.effectiveAutoCompact). */
    fun autoCompactFor(key: String): String =
        AutoCompact.effectiveAutoCompact(autoCompact, jobPrefs.getString(key, null))

    /**
     * Projeto excluído: tira a compactação dos trabalhos da pasta [folder] e, se ela era a última
     * pasta usada no chat, volta à raiz.
     */
    fun forgetFolder(folder: String) {
        val editor = jobPrefs.edit()
        jobPrefs.all.keys.filter { ProjectOps.isKeyOf(it, folder) }.forEach { editor.remove(it) }
        editor.apply()
        if (chatFolder == folder) chatFolder = ""
    }

    /** Projeto renomeado: os ajustes dos trabalhos e a última pasta usada passam de [old] para [new]. */
    fun moveFolder(old: String, new: String) {
        val editor = jobPrefs.edit()
        for ((key, value) in jobPrefs.all) {
            val moved = ProjectOps.movedKey(key, old, new) ?: continue
            if (value is String) editor.putString(moved, value)
            editor.remove(key)
        }
        editor.apply()
        if (chatFolder == old) chatFolder = new
    }

    /** Limpeza automática dos caches (uma vez por dia, ver TerminalService.maybeAutoClean). */
    var autoClean: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEAN, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLEAN, value).apply()

    /** Quando a última limpeza automática começou (millis; 0 = nunca). */
    var lastAutoCleanAt: Long
        get() = prefs.getLong(KEY_LAST_AUTO_CLEAN, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AUTO_CLEAN, value).apply()

    /** Tamanho do texto do chat: um de [CHAT_TEXT_SCALES]; outro valor guardado volta a 1f. */
    var chatTextScale: Float
        get() = prefs.getFloat(KEY_CHAT_TEXT_SCALE, 1f).takeIf { it in CHAT_TEXT_SCALES } ?: 1f
        set(value) = prefs.edit().putFloat(KEY_CHAT_TEXT_SCALE, value).apply()

    /**
     * Segundos sem nenhuma mensagem do agente a partir dos quais a faixa do chat avisa possível
     * travamento (ver AgentStatus). O ajuste usa [CHAT_STALL_OPTIONS]; fora de 30 s a 15 min, o valor
     * guardado é limitado a essa faixa.
     */
    var chatStallSeconds: Int
        get() = prefs.getInt(KEY_CHAT_STALL, 120).coerceIn(30, 900)
        set(value) = prefs.edit().putInt(KEY_CHAT_STALL, value).apply()

    companion object {
        /** Escolhas do tamanho do texto do chat (multiplicam o tamanho padrão). */
        val CHAT_TEXT_SCALES = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f)
        /** Escolhas de [chatStallSeconds], em segundos (1, 2, 5 e 10 minutos). */
        val CHAT_STALL_OPTIONS = listOf(60, 120, 300, 600)
        /** Escolhas de [sleepAfterMinutes] (0 = nunca). */
        val SLEEP_AFTER_MINUTES = listOf(0, 15, 30, 60, 120)
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val VIEW_DETAILED = "detailed"
        const val VIEW_NORMAL = "normal"
        const val VIEW_SUMMARY = "summary"
        private const val KEY_CHAT_VIEW = "chat_view"
        private const val KEY_CHAT_FOLDER = "chat_folder"
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_TERMINAL_THEME = "terminal_theme"
        private const val KEY_CPU_AWAKE = "keep_cpu_awake"
        private const val KEY_SCREEN_ON = "keep_screen_on"
        private const val KEY_EXTRA_KEYS = "show_extra_keys"
        private const val KEY_CHAT_EFFORT = "chat_effort"
        private const val KEY_CHAT_MODEL = "chat_model"
        private const val KEY_MODE = "mode"
        private const val MODE_SIMPLE = "simple"
        private const val MODE_PRO = "pro"
        private const val KEY_ENTER_SENDS = "enter_sends"
        private const val KEY_ASK_PERMISSIONS = "ask_permissions"
        private const val KEY_WELCOME_DISMISSED = "welcome_dismissed"
        private const val KEY_FONT_SIZE = "font_size_px"
        private const val KEY_COMPOSE_VISIBLE = "compose_visible"
        private const val KEY_HAPTIC_FINISH = "haptic_on_finish"
        private const val KEY_CHAT_TEXT_SCALE = "chat_text_scale"
        const val KEY_CHAT_STALL = "chat_stall_seconds"
        private const val KEY_AGENT_MEMORY_TIPS = "agent_memory_tips"
        private const val KEY_MAX_AGENTS = "max_agents"
        private const val KEY_SLEEP_AFTER = "sleep_after_minutes"
        private const val KEY_AUTO_COMPACT = "auto_compact"
        private const val KEY_AUTO_CLEAN = "auto_clean"
        private const val KEY_LAST_AUTO_CLEAN = "last_auto_clean_at"
    }
}

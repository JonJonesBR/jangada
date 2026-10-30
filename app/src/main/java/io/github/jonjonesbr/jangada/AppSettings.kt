package io.github.jonjonesbr.jangada

import android.content.Context

/** Preferências do usuário (tela de configurações). */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

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

    /** Segura o processador acordado enquanto há sessões (agentes não param com a tela apagada). */
    var keepCpuAwake: Boolean
        get() = prefs.getBoolean(KEY_CPU_AWAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_CPU_AWAKE, value).apply()

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

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
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
    }
}

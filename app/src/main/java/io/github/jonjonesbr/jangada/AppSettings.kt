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

    private companion object {
        const val KEY_CHAT_EFFORT = "chat_effort"
        const val KEY_CHAT_MODEL = "chat_model"
        const val KEY_MODE = "mode"
        const val MODE_SIMPLE = "simple"
        const val MODE_PRO = "pro"
        const val KEY_ENTER_SENDS = "enter_sends"
        const val KEY_ASK_PERMISSIONS = "ask_permissions"
    }
}

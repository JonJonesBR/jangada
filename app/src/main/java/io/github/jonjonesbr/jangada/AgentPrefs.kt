package io.github.jonjonesbr.jangada

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferências dos agentes (arquivo "agents"). A tela inicial grava os comandos
 * instalados e a ShareActivity lê; o nome do arquivo e das chaves não podem mudar.
 */
object AgentPrefs {
    /** Nome do arquivo de preferências. */
    private const val FILE = "agents"
    /** Chave dos comandos instalados. */
    private const val KEY_INSTALLED = "installed_commands"

    /** Arquivo com as demais preferências dos agentes (ex.: quando cada um foi atualizado). */
    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Comandos instalados, ou null se a lista nunca foi gravada. */
    fun installedCommands(context: Context): Set<String>? =
        prefs(context).getStringSet(KEY_INSTALLED, null)?.toSet()

    /** Guarda os comandos instalados, para o compartilhar filtrar sem precisar do Debian. */
    fun saveInstalledCommands(context: Context, commands: Set<String>) {
        prefs(context).edit().putStringSet(KEY_INSTALLED, HashSet(commands)).apply()
    }
}

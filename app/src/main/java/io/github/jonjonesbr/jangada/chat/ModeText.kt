package io.github.jonjonesbr.jangada.chat

import android.content.Context
import io.github.jonjonesbr.jangada.R

/**
 * Textos simples para os modos de permissão dos agentes (ids do ACP e do Claude Code).
 * Ids desconhecidos ficam com o nome que o agente mandou.
 */
object ModeText {
    private fun key(id: String) = id.lowercase().replace("-", "").replace("_", "")

    fun labelRes(id: String): Int? = when (key(id)) {
        "default", "ask" -> R.string.mode_default
        "acceptedits", "autoedit", "edit" -> R.string.mode_accept_edits
        "auto" -> R.string.mode_auto
        "plan" -> R.string.mode_plan
        "readonly" -> R.string.mode_read_only
        "build" -> R.string.mode_build
        "yolo", "bypasspermissions", "fullaccess", "dangerfullaccess" -> R.string.mode_full
        else -> null
    }

    fun descriptionRes(id: String): Int? = when (key(id)) {
        "default", "ask" -> R.string.mode_default_desc
        "acceptedits", "autoedit", "edit" -> R.string.mode_accept_edits_desc
        "auto" -> R.string.mode_auto_desc
        "plan" -> R.string.mode_plan_desc
        "readonly" -> R.string.mode_read_only_desc
        "build" -> R.string.mode_build_desc
        "yolo", "bypasspermissions", "fullaccess", "dangerfullaccess" -> R.string.mode_full_desc
        else -> null
    }

    /** Modo que aprova tudo sem perguntar: pede confirmação antes de ligar. */
    fun isRisky(id: String): Boolean = key(id) in setOf("yolo", "bypasspermissions", "fullaccess", "dangerfullaccess")

    fun label(context: Context, id: String, fallback: String): String =
        labelRes(id)?.let { context.getString(it) } ?: fallback.ifEmpty { id }

    fun description(context: Context, id: String): String? = descriptionRes(id)?.let { context.getString(it) }
}

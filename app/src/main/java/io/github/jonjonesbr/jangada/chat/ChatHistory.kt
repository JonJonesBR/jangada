package io.github.jonjonesbr.jangada.chat

import io.github.jonjonesbr.jangada.linux.LinuxEnv
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Conversas anteriores do Claude Code na pasta de projetos. O próprio
 * Claude guarda cada conversa num arquivo .jsonl; aqui só listamos e
 * damos um título (o resumo que ele grava, ou a primeira mensagem).
 */
object ChatHistory {

    class Conversation(val id: String, val title: String, val updatedAt: Long)

    fun dir(linux: LinuxEnv) = File(linux.rootfs, "root/.claude/projects/-root-${LinuxEnv.WORKSPACE}")

    fun list(linux: LinuxEnv, limit: Int = 50): List<Conversation> =
        (dir(linux).listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".jsonl") && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
            .take(limit)
            .mapNotNull { file ->
                val title = title(file) ?: return@mapNotNull null
                Conversation(file.name.removeSuffix(".jsonl"), title, file.lastModified())
            }

    /** Resumo gravado pelo Claude ou a primeira mensagem do usuário. */
    private fun title(file: File): String? {
        var firstMessage: String? = null
        runCatching {
            file.bufferedReader().useLines { lines ->
                for (line in lines.take(MAX_LINES)) {
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (json.optString("type") == "summary") {
                        json.optString("summary").takeIf { it.isNotBlank() }?.let { return it.take(80) }
                    }
                    if (firstMessage == null && json.optString("type") == "user" && !json.optBoolean("isMeta")) {
                        firstMessage = userText(json.optJSONObject("message"))
                    }
                }
            }
        }
        return firstMessage?.replace('\n', ' ')?.take(80)
    }

    private fun userText(message: JSONObject?): String? {
        when (val content = message?.opt("content")) {
            is String -> return content.takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") }
            is JSONArray -> for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                val text = block.optString("text")
                if (block.optString("type") == "text" && text.isNotBlank() && !text.trimStart().startsWith("<")) return text
            }
        }
        return null
    }

    private const val MAX_LINES = 60
}

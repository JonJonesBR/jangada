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

    /** Arquivo da conversa [id], se existir. */
    fun file(linux: LinuxEnv, id: String): File? = File(dir(linux), "$id.jsonl").takeIf { it.isFile }

    /**
     * O que já foi conversado, lido do arquivo que o próprio Claude Code
     * grava (.jsonl): mensagens suas, respostas e ações. Fica com as [max]
     * últimas. Lê arquivo: rodar fora da thread principal.
     */
    fun read(file: File, max: Int): List<ChatItem> = runCatching {
        file.bufferedReader().useLines { parse(it, max) }
    }.getOrDefault(emptyList())

    /** Mesmo que [read], a partir das linhas (separado para os testes). */
    fun parse(lines: Sequence<String>, max: Int): List<ChatItem> {
        val loaded = ArrayDeque<ChatItem>()
        fun add(item: ChatItem) {
            loaded.addLast(item)
            if (loaded.size > max) loaded.removeFirst()
        }
        for (line in lines) {
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (json.optBoolean("isMeta") || json.optBoolean("isSidechain")) continue
            val message = json.optJSONObject("message") ?: continue
            when (json.optString("type")) {
                "user" -> {
                    val content = message.opt("content")
                    if (content is String && content.isNotBlank() && !isInternal(content)) {
                        add(ChatItem.User(content))
                    } else if (content is JSONArray) {
                        for (i in 0 until content.length()) {
                            val block = content.optJSONObject(i) ?: continue
                            val text = block.optString("text")
                            if (block.optString("type") == "text" && text.isNotBlank() && !isInternal(text)) {
                                add(ChatItem.User(text))
                            }
                        }
                    }
                }
                "assistant" -> {
                    val content = message.optJSONArray("content") ?: continue
                    for (i in 0 until content.length()) {
                        val block = content.optJSONObject(i) ?: continue
                        when (block.optString("type")) {
                            "text" -> block.optString("text").takeIf { it.isNotBlank() }
                                ?.let { add(ChatItem.Assistant(it, streaming = false)) }
                            "tool_use" -> add(
                                ChatItem.Tool(
                                    block.optString("id"), block.optString("name"),
                                    ToolText.summary(block.optString("name"), block.optJSONObject("input")),
                                ).apply { done = true },
                            )
                        }
                    }
                }
            }
        }
        return loaded.toList()
    }

    /** Apaga uma conversa (o arquivo e a pasta de anexos que o Claude cria com o mesmo id). */
    fun delete(linux: LinuxEnv, id: String) {
        val dir = dir(linux)
        File(dir, "$id.jsonl").delete()
        val extra = File(dir, id)
        if (extra.exists()) io.github.jonjonesbr.jangada.linux.Fs.deleteTree(extra.toPath())
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
            is String -> return content.takeIf { it.isNotBlank() && !isInternal(it) }
            is JSONArray -> for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                val text = block.optString("text")
                if (block.optString("type") == "text" && text.isNotBlank() && !isInternal(text)) return text
            }
        }
        return null
    }

    /** Marcadores que o próprio Claude Code grava (comandos, "!", avisos): não são mensagens digitadas. */
    private val INTERNAL_MARKERS = listOf(
        "<command-name>", "<command-message>", "<command-args>", "<local-command-",
        "<bash-input>", "<bash-stdout>", "<bash-stderr>", "<system-reminder>", "<user-prompt-submit-hook>",
    )

    /** Texto que começa com um desses marcadores. Mensagens comuns com "<" (ex.: "<div>") ficam. */
    private fun isInternal(text: String): Boolean {
        val trimmed = text.trimStart()
        return INTERNAL_MARKERS.any { trimmed.startsWith(it) }
    }

    private const val MAX_LINES = 60
}

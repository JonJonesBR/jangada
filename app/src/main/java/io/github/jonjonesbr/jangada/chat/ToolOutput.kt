package io.github.jonjonesbr.jangada.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * Saída de uma ação (resultado de um comando, arquivo lido etc.), limpa e
 * cortada para caber no chat: começo e fim, sem cores do terminal.
 */
object ToolOutput {
    const val HEAD_LINES = 20
    const val TAIL_LINES = 40
    const val MAX_LINE = 300
    const val MAX_CHARS = 6000

    private val ANSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)")
    private val REMINDER = Regex("<system-reminder>[\\s\\S]*?</system-reminder>")

    /** Conteúdo de um tool_result do Claude: texto, ou lista de blocos (só os de texto contam). */
    fun fromClaude(content: Any?): String? = when (content) {
        is String -> clip(content)
        is JSONArray -> clip(
            (0 until content.length()).mapNotNull { i ->
                content.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("text")
            }.joinToString("\n"),
        )
        else -> null
    }

    /**
     * Saída de um tool_call do ACP: os itens de texto de "content" (diffs e
     * terminais ficam de fora) ou, sem eles, o texto de "rawOutput".
     */
    fun fromAcp(call: JSONObject): String? {
        call.optJSONArray("content")?.let { list ->
            val text = (0 until list.length()).mapNotNull { i ->
                list.optJSONObject(i)?.takeIf { it.optString("type") == "content" }
                    ?.optJSONObject("content")?.takeIf { it.optString("type") == "text" }?.optString("text")
            }.joinToString("\n")
            clip(text)?.let { return it }
        }
        return when (val raw = call.opt("rawOutput")) {
            is String -> clip(raw)
            is JSONObject -> clip(
                listOf("output", "stdout", "stderr").mapNotNull { key -> (raw.opt(key) as? String) }.joinToString("\n"),
            )
            else -> null
        }
    }

    /** Limpa e corta [text]; null quando não sobra nada para mostrar. */
    fun clip(text: String): String? {
        val lines = REMINDER.replace(ANSI.replace(text, ""), "")
            .split('\n')
            // Barra de progresso: o "\r" volta ao começo da linha, então vale o último pedaço.
            .map { it.trimEnd('\r').substringAfterLast('\r').trimEnd() }
            .map { if (it.length > MAX_LINE) it.take(MAX_LINE) + "…" else it }
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
        if (lines.isEmpty()) return null
        val kept = if (lines.size > HEAD_LINES + TAIL_LINES) {
            lines.take(HEAD_LINES) + "⋯" + lines.takeLast(TAIL_LINES)
        } else {
            lines
        }
        val joined = kept.joinToString("\n")
        return if (joined.length > MAX_CHARS) joined.take(MAX_CHARS) + "\n⋯" else joined
    }
}

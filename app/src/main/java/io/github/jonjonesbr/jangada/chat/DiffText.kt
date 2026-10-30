package io.github.jonjonesbr.jangada.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * Diff simples, linha a linha, das edições de arquivo que o agente pede para
 * fazer: linhas antigas com "- " e novas com "+ ". Sem algoritmo sofisticado:
 * tira o começo e o fim iguais e lista o meio. Função pura (roda em testes).
 */
object DiffText {
    /** Máximo de linhas no resultado (a última é "… (+N)" quando corta). */
    const val MAX_LINES = 40

    /** Linhas muito longas são cortadas (um arquivo minificado não deve travar a tela). */
    private const val MAX_CHARS = 200

    /**
     * Diff de uma ferramenta do Claude Code: Edit (old_string/new_string),
     * MultiEdit (edits[]) e Write (o conteúdo inteiro como linhas novas).
     * Null quando a ferramenta não edita arquivo ou não há linha alterada.
     */
    fun fromClaude(toolName: String, input: JSONObject): String? {
        val lines = when (toolName) {
            "Edit" -> diffLines(input.optString("old_string"), input.optString("new_string"))
            "MultiEdit" -> {
                val edits = input.optJSONArray("edits") ?: return null
                (0 until edits.length()).flatMap { i ->
                    val edit = edits.optJSONObject(i) ?: return@flatMap emptyList<String>()
                    diffLines(edit.optString("old_string"), edit.optString("new_string"))
                }
            }
            "Write" -> splitLines(input.optString("content")).map { "+ ${cut(it)}" }
            else -> return null
        }
        return limit(lines)
    }

    /**
     * Diff dos blocos "diff" do ACP (campo "content" de um tool call). oldText
     * ausente ou null significa arquivo novo: tudo vira linha nova.
     */
    fun fromAcpContent(content: JSONArray?): String? {
        content ?: return null
        val lines = mutableListOf<String>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") != "diff") continue
            val old = if (block.isNull("oldText")) null else block.optString("oldText")
            lines += diffLines(old, block.optString("newText"))
        }
        return limit(lines)
    }

    /** Linhas "- " do começo ao fim diferente e "+ " do mesmo trecho na versão nova. */
    private fun diffLines(old: String?, new: String?): List<String> {
        val a = splitLines(old)
        val b = splitLines(new)
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }
        return a.subList(start, endA).map { "- ${cut(it)}" } + b.subList(start, endB).map { "+ ${cut(it)}" }
    }

    /** Texto em linhas, sem a quebra final; vazio não tem linha nenhuma. */
    private fun splitLines(text: String?): List<String> =
        if (text.isNullOrEmpty()) emptyList() else text.removeSuffix("\n").split('\n').map { it.removeSuffix("\r") }

    private fun cut(line: String): String = if (line.length > MAX_CHARS) line.take(MAX_CHARS) + "…" else line

    /** Junta as linhas, com no máximo [MAX_LINES] (contando a linha "… (+N)"). */
    private fun limit(lines: List<String>): String? {
        if (lines.isEmpty()) return null
        if (lines.size <= MAX_LINES) return lines.joinToString("\n")
        val shown = lines.take(MAX_LINES - 1)
        return (shown + "… (+${lines.size - shown.size})").joinToString("\n")
    }
}

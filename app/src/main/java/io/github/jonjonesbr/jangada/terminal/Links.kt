package io.github.jonjonesbr.jangada.terminal

import com.termux.terminal.TerminalEmulator

/**
 * Encontra links (http/https) no texto do terminal.
 *
 * Alguns programas (o login do Claude Code, por exemplo) quebram links
 * longos com quebras de linha "de verdade" quando a tela é estreita. Para
 * o terminal, cada pedaço vira uma linha separada. [joinHardWrapped] cola
 * esses pedaços de volta antes de procurar o link.
 */
object Links {
    private val URL = Regex("""https?://[^\s<>"'`│]+""")
    private val URL_CHAR = Regex("""[^\s<>"'`│]""")
    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')

    /** Quantas colunas antes da borda ainda contam como "linha cheia". */
    private const val EDGE = 3

    fun first(text: String?): String? =
        text?.let { URL.find(it)?.value?.let(::clean) }

    fun last(text: String?): String? =
        text?.let { URL.findAll(it).lastOrNull()?.value?.let(::clean) }

    /** Todos os links do texto, já limpos. */
    fun all(text: String): List<String> = URL.findAll(text).map { clean(it.value) }.toList()

    private fun clean(url: String) = url.trimEnd(*TRAILING)

    /** Último link da tela inteira (botão 🔗). */
    fun lastOnScreen(emulator: TerminalEmulator): String? {
        val screen = emulator.screen
        val text = screen.getSelectedText(
            0, -screen.activeTranscriptRows, emulator.mColumns, emulator.mRows, true, false,
        )
        return last(joinHardWrapped(text.split('\n'), emulator.mColumns))
    }

    /** O link sob o dedo, mesmo que o programa o tenha quebrado em várias linhas. */
    fun atLocation(emulator: TerminalEmulator, column: Int, row: Int): String? {
        val screen = emulator.screen
        val word = screen.getWordAtLocation(column, row).trim()
        return linkAround(word) {
            val top = maxOf(-screen.activeTranscriptRows, row - WINDOW)
            val bottom = minOf(emulator.mRows, row + WINDOW + 1)
            val text = screen.getSelectedText(0, top, emulator.mColumns, bottom, true, false)
            joinHardWrapped(text.split('\n'), emulator.mColumns)
        }
    }

    /**
     * Escolhe o link para a palavra tocada. [joined] (o texto da tela em volta)
     * só é lido se a palavra for longa o bastante para ser parte de um link.
     */
    internal fun linkAround(word: String, joined: () -> String): String? {
        if (word.length < 4) return first(word)
        val core = word.trimEnd(*TRAILING)
        // Palavra só de pontuação: sem núcleo, contains("") casaria com qualquer link.
        if (core.isEmpty()) return first(word)
        return all(joined()).firstOrNull { it.contains(core) } ?: first(word)
    }

    /**
     * Junta uma linha com a seguinte quando a primeira termina num link que
     * encosta na borda da tela e a seguinte continua com caracteres de link.
     */
    fun joinHardWrapped(lines: List<String>, columns: Int): String {
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            var logical = lines[i].trimEnd()
            var physical = logical
            while (i + 1 < lines.size && continuesLink(logical, physical, lines[i + 1], columns)) {
                i++
                physical = lines[i].trimEnd()
                logical += physical.trimStart()
            }
            out.append(logical).append('\n')
            i++
        }
        return out.toString()
    }

    private fun continuesLink(logical: String, physical: String, next: String, columns: Int): Boolean {
        if (columns <= EDGE) return false
        // A última linha física precisa ter chegado (quase) na borda.
        val lastRowLength = physical.length % columns
        if (lastRowLength != 0 && lastRowLength < columns - EDGE) return false
        // E o texto acumulado precisa terminar dentro de um link.
        val lastUrl = URL.findAll(logical).lastOrNull() ?: return false
        if (lastUrl.range.last != logical.length - 1) return false
        val nextStart = next.trimStart()
        return nextStart.isNotEmpty() && URL_CHAR.matches(nextStart.substring(0, 1))
    }

    private const val WINDOW = 20
}

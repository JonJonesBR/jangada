package io.github.jonjonesbr.jangada.terminal

/** Encontra links (http/https) no texto do terminal. */
object Links {
    private val URL = Regex("""https?://[^\s<>"'`│]+""")
    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')

    fun first(text: String?): String? =
        text?.let { URL.find(it)?.value?.trimEnd(*TRAILING) }

    fun last(text: String?): String? =
        text?.let { URL.findAll(it).lastOrNull()?.value?.trimEnd(*TRAILING) }
}

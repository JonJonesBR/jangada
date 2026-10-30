package io.github.jonjonesbr.jangada.chat

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Markdown simples para as respostas do agente: títulos, negrito, itálico,
 * `código`, blocos ``` e listas. O suficiente para ler bem no celular.
 */
object Markdown {

    fun render(source: String, codeBackground: Int): CharSequence {
        val out = SpannableStringBuilder()
        var inCode = false
        val lines = source.split('\n')
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                inCode = !inCode
                continue
            }
            val start = out.length
            when {
                inCode -> {
                    out.append(raw)
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(codeBackground), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    appendInline(out, line.drop(level).trim(), codeBackground)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val size = when (level) { 1 -> 1.35f; 2 -> 1.2f; else -> 1.1f }
                    out.setSpan(RelativeSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                BULLET.matches(line) -> {
                    appendInline(out, line.replaceFirst(BULLET_PREFIX, ""), codeBackground)
                    out.setSpan(BulletSpan(16), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> appendInline(out, line, codeBackground)
            }
            if (index < lines.size - 1) out.append('\n')
        }
        return out
    }

    /** Negrito (**x**), itálico (*x* ou _x_) e código (`x`) dentro da linha. */
    private fun appendInline(out: SpannableStringBuilder, text: String, codeBackground: Int) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        val start = out.length
                        out.append(text, i + 1, end)
                        out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(BackgroundColorSpan(codeBackground), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                        continue
                    }
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        val start = out.length
                        out.append(text, i + 2, end)
                        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                        continue
                    }
                }
                c == '*' || c == '_' -> {
                    val end = findEmphasisEnd(text, i)
                    if (end != -1) {
                        val start = out.length
                        out.append(text, i + 1, end)
                        out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
    }

    /**
     * Índice do marcador de fechamento da ênfase que começa em [start] (`*` ou `_`),
     * ou -1 se o trecho não é ênfase. `_` dentro de palavra (MAX_IMAGE_SIDE) não conta,
     * e `*` logo depois de dígito (2*3*4) também não.
     */
    internal fun findEmphasisEnd(text: String, start: Int): Int {
        val c = text[start]
        if (start + 1 >= text.length || text[start + 1].isWhitespace()) return -1
        if (c == '_' && start > 0 && text[start - 1].isLetterOrDigit()) return -1
        if (c == '*' && start > 0 && text[start - 1].isDigit()) return -1
        var end = text.indexOf(c, start + 1)
        while (end > start + 1) {
            val closes = !text[end - 1].isWhitespace() &&
                (c == '*' || end + 1 >= text.length || !text[end + 1].isLetterOrDigit())
            if (closes) return end
            end = text.indexOf(c, end + 1)
        }
        return -1
    }

    private val BULLET = Regex("""^\s*[-*+]\s+.*""")
    private val BULLET_PREFIX = Regex("""^\s*[-*+]\s+""")
}

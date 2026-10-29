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
                (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() -> {
                    val end = text.indexOf(c, i + 1)
                    if (end > i + 1 && !text[end - 1].isWhitespace()) {
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

    private val BULLET = Regex("""^\s*[-*+]\s+.*""")
    private val BULLET_PREFIX = Regex("""^\s*[-*+]\s+""")
}

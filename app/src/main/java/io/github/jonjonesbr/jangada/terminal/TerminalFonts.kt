package io.github.jonjonesbr.jangada.terminal

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import io.github.jonjonesbr.jangada.R

/**
 * Fonte do terminal: JetBrains Mono, com as Noto Sans Symbols como reserva
 * para os símbolos que os agentes desenham (⏵ ⏺ ⎿ ✻ ...). Sem elas o
 * Android mostra quadradinhos no lugar.
 */
object TerminalFonts {

    fun load(context: Context): Typeface = try {
        val res = context.resources
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            fun family(id: Int) = FontFamily.Builder(Font.Builder(res, id).build()).build()
            Typeface.CustomFallbackBuilder(family(R.font.jetbrains_mono))
                .addCustomFallback(family(R.font.noto_sans_symbols2))
                .addCustomFallback(family(R.font.noto_sans_symbols))
                .setSystemFallback("monospace")
                .build()
        } else {
            res.getFont(R.font.jetbrains_mono)
        }
    } catch (e: Exception) {
        Typeface.MONOSPACE
    }
}

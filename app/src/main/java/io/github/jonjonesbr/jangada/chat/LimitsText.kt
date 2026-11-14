package io.github.jonjonesbr.jangada.chat

import android.content.Context
import android.text.format.DateUtils
import io.github.jonjonesbr.jangada.R

/** A partir deste percentual, o chip e o cartão ficam em destaque. */
internal const val LIMIT_HIGH_PERCENT = 80

/** Resumo dos limites para o chip do chat e o cartão da tela inicial. [names] vale para a descrição. */
class LimitsSummary(val text: String, val high: Boolean, val names: String)

/**
 * Janelas de 5 horas e semanal (as "outras" só aparecem no painel). Null se não há nenhuma.
 * O texto é o do chip: "⏱ 5h 40% · semana 12%", com ⚠ a partir de 80%.
 */
internal fun limitsSummary(context: Context, limits: PlanLimits?): LimitsSummary? {
    val shown = shownLimitWindows(limits)
    if (shown.isEmpty()) return null
    val high = isHighLimit(shown)
    val parts = shown.joinToString(" · ") { w ->
        if (w.kind == LimitWindow.Kind.FIVE_HOUR) {
            context.getString(R.string.chat_limit_short_five_hour, w.usedPercent)
        } else {
            context.getString(R.string.chat_limit_short_weekly, w.usedPercent)
        }
    }
    return LimitsSummary(
        text = context.getString(if (high) R.string.chat_chip_limits_high else R.string.chat_chip_limits, parts),
        high = high,
        names = shown.joinToString(" · ") { limitName(context, it) },
    )
}

/** Janelas do chip e do cartão: as "outras" só aparecem no painel. */
internal fun shownLimitWindows(limits: PlanLimits?): List<LimitWindow> =
    limits?.windows.orEmpty().filter { it.kind != LimitWindow.Kind.OTHER }

/** Destaque (⚠) quando alguma janela mostrada chegou a [LIMIT_HIGH_PERCENT]. */
internal fun isHighLimit(windows: List<LimitWindow>): Boolean =
    windows.any { it.usedPercent >= LIMIT_HIGH_PERCENT }

/** Uma janela por extenso, com o percentual usado (sem o horário de renovação). */
internal fun limitName(context: Context, w: LimitWindow): String = when (w.kind) {
    LimitWindow.Kind.FIVE_HOUR -> context.getString(R.string.chat_limit_five_hour, w.usedPercent)
    LimitWindow.Kind.WEEKLY -> context.getString(R.string.chat_limit_weekly, w.usedPercent)
    LimitWindow.Kind.OTHER -> context.getString(R.string.chat_limit_other, w.label.orEmpty(), w.usedPercent)
}

/** Nome curto de uma janela para avisos: "limite de 5 horas", "limite semanal" ou o nome que o agente deu. */
internal fun limitKindName(context: Context, w: LimitWindow): String = when (w.kind) {
    LimitWindow.Kind.FIVE_HOUR -> context.getString(R.string.limit_name_five_hour)
    LimitWindow.Kind.WEEKLY -> context.getString(R.string.limit_name_weekly)
    LimitWindow.Kind.OTHER -> w.label.orEmpty()
}

/** Horário de renovação: só a hora se for hoje; senão, dia da semana e hora. */
internal fun formatLimitReset(context: Context, ms: Long): String {
    val flags = if (DateUtils.isToday(ms)) {
        DateUtils.FORMAT_SHOW_TIME
    } else {
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_TIME
    }
    return DateUtils.formatDateTime(context, ms, flags)
}

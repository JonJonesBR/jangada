package io.github.jonjonesbr.jangada.terminal

import com.termux.terminal.TerminalColors
import java.util.Properties

/** Esquemas de cores do terminal (configurações → Aparência). */
object TerminalThemes {

    const val DEFAULT = "default"

    /** id → nome mostrado. */
    val names = linkedMapOf(
        DEFAULT to "Jangada",
        "sea" to "Mar",
        "dracula" to "Dracula",
        "solarized_dark" to "Solarized escuro",
        "solarized_light" to "Solarized claro",
    )

    private val solarizedAnsi = mapOf(
        "color0" to "#073642", "color1" to "#dc322f", "color2" to "#859900", "color3" to "#b58900",
        "color4" to "#268bd2", "color5" to "#d33682", "color6" to "#2aa198", "color7" to "#eee8d5",
        "color8" to "#002b36", "color9" to "#cb4b16", "color10" to "#586e75", "color11" to "#657b83",
        "color12" to "#839496", "color13" to "#6c71c4", "color14" to "#93a1a1", "color15" to "#fdf6e3",
    )

    private val schemes: Map<String, Map<String, String>> = mapOf(
        DEFAULT to emptyMap(),
        "sea" to mapOf("background" to "#0e2a33", "foreground" to "#f2ede4", "cursor" to "#e9a23b"),
        "dracula" to mapOf(
            "background" to "#282a36", "foreground" to "#f8f8f2", "cursor" to "#f8f8f2",
            "color0" to "#21222c", "color1" to "#ff5555", "color2" to "#50fa7b", "color3" to "#f1fa8c",
            "color4" to "#bd93f9", "color5" to "#ff79c6", "color6" to "#8be9fd", "color7" to "#f8f8f2",
            "color8" to "#6272a4", "color9" to "#ff6e6e", "color10" to "#69ff94", "color11" to "#ffffa5",
            "color12" to "#d6acff", "color13" to "#ff92df", "color14" to "#a4ffff", "color15" to "#ffffff",
        ),
        "solarized_dark" to solarizedAnsi + mapOf("background" to "#002b36", "foreground" to "#839496", "cursor" to "#93a1a1"),
        "solarized_light" to solarizedAnsi + mapOf("background" to "#fdf6e3", "foreground" to "#657b83", "cursor" to "#586e75"),
    )

    /** Aplica o esquema a todos os terminais (o esquema do Termux é global). */
    fun apply(id: String) {
        val props = Properties()
        (schemes[id] ?: emptyMap()).forEach { (k, v) -> props.setProperty(k, v) }
        runCatching { TerminalColors.COLOR_SCHEME.updateWith(props) }
    }
}

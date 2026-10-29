package io.github.jonjonesbr.jangada.agents

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Uma receita do catálogo (app/src/main/assets/agents/<id>.json).
 * Adicionar um agente ao Jangada é só criar mais um desses arquivos.
 */
data class AgentRecipe(
    val id: String,
    val order: Int,
    val name: String,
    val vendor: String,
    /** Comando que abre o agente no terminal. */
    val command: String,
    /** Script de instalação (roda dentro do Debian). */
    val install: String,
    /** Script de atualização; quando ausente, a instalação é repetida. */
    val update: String?,
    /** Pacotes do Debian necessários antes da instalação. */
    val requires: List<String>,
    val homepage: String?,
    private val description: Map<String, String>,
    private val note: Map<String, String>,
    /** Pacote no npm usado só para saber qual é a versão mais nova. */
    val npm: String? = null,
) {
    fun description(): String = localized(description)
    fun note(): String? = localized(note).ifEmpty { null }

    /** Instala (ou atualiza) e, dando certo, já abre o agente. */
    fun installScript(updating: Boolean): String {
        val steps = if (updating) update ?: install else install
        val apt = if (requires.isEmpty()) "" else
            "apt-get update && apt-get install -y --no-install-recommends ${requires.joinToString(" ")} && "
        val title = if (updating) "Atualizando" else "Instalando"
        return """
            printf '\n== %s %s ==\n\n' ${q(title)} ${q(name)}
            ( set -e; $apt$steps )
            status=${'$'}?
            for f in /etc/profile ~/.profile ~/.bashrc; do [ -f "${'$'}f" ] && . "${'$'}f"; done 2>/dev/null; hash -r
            if [ "${'$'}status" -eq 0 ] && command -v ${q(command)} >/dev/null 2>&1; then
              printf '\n✓ %s pronto. Abrindo...\n\n' ${q(name)}; sleep 1; $command
            else
              printf '\n✕ Não terminou (código %s). Veja as mensagens acima.\n' "${'$'}status"
            fi
        """.trimIndent()
    }

    /**
     * Versão para rodar em segundo plano, sem terminal: instala ou atualiza
     * e termina com código 0 só se o comando do agente passou a existir.
     */
    fun backgroundScript(updating: Boolean): String {
        val steps = if (updating) update ?: install else install
        val apt = if (requires.isEmpty()) "" else
            "apt-get update && apt-get install -y --no-install-recommends ${requires.joinToString(" ")} && "
        return """
            export DEBIAN_FRONTEND=noninteractive
            ( set -e; $apt$steps ) </dev/null
            status=${'$'}?
            for f in /etc/profile ~/.profile ~/.bashrc; do [ -f "${'$'}f" ] && . "${'$'}f"; done 2>/dev/null; hash -r
            if [ "${'$'}status" -eq 0 ] && command -v ${q(command)} >/dev/null 2>&1; then
              echo "JANGADA_OK"; exit 0
            fi
            echo "JANGADA_FAIL ${'$'}status"; exit 1
        """.trimIndent()
    }

    fun launchScript(): String = command

    private fun localized(map: Map<String, String>): String {
        val lang = Locale.getDefault().language
        return map[lang] ?: map["en"] ?: map.values.firstOrNull() ?: ""
    }

    companion object {
        /** Aspas simples seguras para o shell. */
        fun q(text: String) = "'" + text.replace("'", "'\\''") + "'"

        fun parse(json: JSONObject): AgentRecipe {
            fun strings(key: String): Map<String, String> {
                val obj = json.optJSONObject(key) ?: return emptyMap()
                return obj.keys().asSequence().associateWith { obj.getString(it) }
            }
            val requires = json.optJSONArray("requires")
            return AgentRecipe(
                id = json.getString("id"),
                order = json.optInt("order", 1000),
                name = json.getString("name"),
                vendor = json.optString("vendor"),
                command = json.getString("command"),
                install = json.getString("install"),
                update = json.optString("update").ifEmpty { null },
                requires = requires?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                homepage = json.optString("homepage").ifEmpty { null },
                description = strings("description"),
                note = strings("note"),
                npm = json.optString("npm").ifEmpty { null },
            )
        }
    }
}

object AgentCatalog {
    private const val DIR = "agents"

    fun load(context: Context): List<AgentRecipe> {
        val assets = context.assets
        return (assets.list(DIR) ?: emptyArray())
            .filter { it.endsWith(".json") }
            .mapNotNull { file ->
                runCatching {
                    AgentRecipe.parse(JSONObject(assets.open("$DIR/$file").bufferedReader().readText()))
                }.getOrNull()
            }
            .sortedWith(compareBy({ it.order }, { it.name }))
    }

    /**
     * Script que lista quais agentes existem no Debian e, em seguida, a
     * versão de cada um. Saída: "JANGADA_FOUND cmd" e "JANGADA_VERSION cmd texto".
     */
    fun detectScript(recipes: List<AgentRecipe>): String {
        val names = recipes.joinToString(" ") { AgentRecipe.q(it.command) }
        return "exec 2>/dev/null; for f in ~/.profile ~/.bashrc; do [ -f \"\$f\" ] && . \"\$f\"; done; " +
            "for b in $names; do command -v \"\$b\" >/dev/null && echo \"JANGADA_FOUND \$b\"; done; " +
            "for b in $names; do command -v \"\$b\" >/dev/null || continue; " +
            "v=\$(timeout 20 \"\$b\" --version </dev/null 2>/dev/null | head -n 1); " +
            "echo \"JANGADA_VERSION \$b \$v\"; done; true"
    }
}

/** Versões: ler, comparar e descobrir a mais nova no npm. */
object Versions {
    private val NUMBER = Regex("""(\d+)\.(\d+)\.(\d+)""")

    /** Primeiro "x.y.z" do texto (ex.: "2.1.312 (Claude Code)" -> "2.1.312"). */
    fun parse(text: String?): String? = text?.let { NUMBER.find(it)?.value }

    /** true se [latest] for mais nova que [installed]. */
    fun isNewer(latest: String, installed: String): Boolean {
        val a = NUMBER.find(latest)?.destructured?.toList()?.map { it.toLong() } ?: return false
        val b = NUMBER.find(installed)?.destructured?.toList()?.map { it.toLong() } ?: return false
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    /** Versão mais nova publicada no npm, ou null se não der para saber. */
    fun latestOnNpm(pkg: String): String? = runCatching {
        val connection = java.net.URL("https://registry.npmjs.org/$pkg/latest").openConnection()
            as java.net.HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        try {
            if (connection.responseCode != 200) return null
            parse(org.json.JSONObject(connection.inputStream.bufferedReader().readText()).optString("version"))
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

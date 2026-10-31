package io.github.jonjonesbr.jangada.agents

import android.content.Context
import io.github.jonjonesbr.jangada.R
import io.github.jonjonesbr.jangada.linux.Workspace
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
    /** O agente tem chat próprio no app (o Claude Code, pelo protocolo dele). */
    val chat: Boolean = false,
    /** Como abrir o agente no modo ACP, para o chat dos demais agentes. */
    val acp: AcpSpec? = null,
    /** Passos extras para desinstalar (além de apagar o próprio comando). */
    val uninstall: String? = null,
) {
    /** Tem chat no app (próprio ou via ACP). */
    val hasChat: Boolean get() = chat || acp != null

    fun description(): String = localized(description)
    fun note(): String? = localized(note).ifEmpty { null }

    /** Instala (ou atualiza) e, dando certo, já abre o agente. Os avisos saem no idioma do app. */
    fun installScript(context: Context, updating: Boolean): String =
        installScript(
            updating,
            heading = context.getString(if (updating) R.string.install_heading_update else R.string.install_heading_install, name),
            ready = context.getString(R.string.install_ready, name),
            failed = context.getString(R.string.install_failed, "%s"),
        )

    /** [failed] leva um "%s" no lugar do código de saída. */
    fun installScript(updating: Boolean, heading: String, ready: String, failed: String): String {
        val steps = if (updating) update ?: install else install
        val apt = if (requires.isEmpty()) "" else
            "apt-get update && apt-get install -y --no-install-recommends ${requires.joinToString(" ")} && "
        // printf com o texto como argumento, nunca como formato (exceto o "%s" do código).
        val failedFormat = failed.replace("%", "%%").replace("%%s", "%s")
        // pipefail: se o download do "curl | bash" falhar, o passo falha também (sem ele, vale só o "bash").
        return """
            printf '\n== %s ==\n\n' ${q(heading)}
            ( set -eo pipefail; $apt$steps )
            status=${'$'}?
            for f in /etc/profile ~/.profile ~/.bashrc; do [ -f "${'$'}f" ] && . "${'$'}f"; done 2>/dev/null; hash -r
            if [ "${'$'}status" -eq 0 ] && command -v ${q(command)} >/dev/null 2>&1; then
              printf '\n%s\n\n' ${q(ready)}; sleep 1; ${launchScript()}
            else
              printf '\n'; printf ${q(failedFormat)} "${'$'}status"; printf '\n'
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
            ( set -eo pipefail; $apt$steps ) </dev/null
            status=${'$'}?
            for f in /etc/profile ~/.profile ~/.bashrc; do [ -f "${'$'}f" ] && . "${'$'}f"; done 2>/dev/null; hash -r
            if [ "${'$'}status" -eq 0 ] && command -v ${q(command)} >/dev/null 2>&1; then
              echo "JANGADA_OK"; exit 0
            fi
            echo "JANGADA_FAIL ${'$'}status"; exit 1
        """.trimIndent()
    }

    /**
     * Desinstala em segundo plano: roda os passos da receita e apaga o
     * comando do agente (só dentro de /root ou /usr/local, nunca do sistema).
     * Configurações e logins ficam guardados, para uma reinstalação futura.
     */
    fun uninstallScript(): String {
        val c = q(command)
        return """
            export DEBIAN_FRONTEND=noninteractive
            for f in /etc/profile ~/.profile ~/.bashrc; do [ -f "${'$'}f" ] && . "${'$'}f"; done 2>/dev/null
            ( ${uninstall ?: "true"} ) </dev/null || true
            for i in 1 2 3; do
              p=${'$'}(command -v $c 2>/dev/null) || break
              t=${'$'}(readlink -f "${'$'}p")
              case "${'$'}p" in /root/*|/usr/local/*) rm -f "${'$'}p" ;; *) break ;; esac
              case "${'$'}t" in /root/*|/usr/local/*) rm -f "${'$'}t" ;; esac
              hash -r
            done
            if command -v $c >/dev/null 2>&1; then echo "JANGADA_FAIL ainda instalado em ${'$'}(command -v $c)"; exit 1; fi
            echo "JANGADA_OK"
        """.trimIndent()
    }

    /** Abre o agente na pasta de trabalho [cwd] (a raiz dos projetos por padrão), não na home. */
    fun launchScript(cwd: String = Workspace.ROOT): String = "${enterDir(cwd)} && $command"

    private fun localized(map: Map<String, String>): String {
        val lang = Locale.getDefault().language
        return map[lang] ?: map["en"] ?: map.values.firstOrNull() ?: ""
    }

    companion object {
        /** Aspas simples seguras para o shell. */
        fun q(text: String) = "'" + text.replace("'", "'\\''") + "'"

        /** Entra na pasta [cwd]; a raiz dos projetos é criada se faltar (subpasta tem que existir). */
        fun enterDir(cwd: String): String =
            if (cwd == Workspace.ROOT) "mkdir -p ${q(cwd)} && cd ${q(cwd)}" else "cd ${q(cwd)}"

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
                uninstall = json.optString("uninstall").ifEmpty { null },
                chat = json.optBoolean("chat", false),
                acp = json.optJSONObject("acp")?.let { spec ->
                    val requires = spec.optJSONArray("requires")
                    AcpSpec(
                        command = spec.getString("command"),
                        bin = spec.optString("bin").ifEmpty { null },
                        install = spec.optString("install").ifEmpty { null },
                        requires = requires?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                    )
                },
            )
        }
    }
}

/**
 * Modo ACP de um agente: [command] o inicia; quando ele depende de um
 * adaptador separado ([bin], instalado por [install]), o app instala na
 * primeira vez que o chat abre.
 */
data class AcpSpec(
    val command: String,
    val bin: String? = null,
    val install: String? = null,
    val requires: List<String> = emptyList(),
) {
    /** Script para instalar o adaptador (em segundo plano, sem terminal). */
    fun installScript(): String? {
        val steps = install ?: return null
        val apt = if (requires.isEmpty()) "" else
            "apt-get update && apt-get install -y --no-install-recommends ${requires.joinToString(" ")} && "
        return "export DEBIAN_FRONTEND=noninteractive; set -eo pipefail; $apt$steps"
    }
}

object AgentCatalog {
    private const val DIR = "agents"

    /** Receitas já lidas: os assets não mudam enquanto o app roda, então lê uma vez por processo. */
    private var cache: List<AgentRecipe>? = null

    @Synchronized
    fun load(context: Context): List<AgentRecipe> {
        cache?.let { return it }
        val assets = context.assets
        val recipes = (assets.list(DIR) ?: emptyArray())
            .filter { it.endsWith(".json") }
            .mapNotNull { file ->
                runCatching {
                    val text = assets.open("$DIR/$file").bufferedReader().use { it.readText() }
                    AgentRecipe.parse(JSONObject(text))
                }.getOrNull()
            }
            .sortedWith(compareBy({ it.order }, { it.name }))
        cache = recipes
        return recipes
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
            "echo \"JANGADA_VERSION \$b \$v\"; done; " +
            SIZE_FUNCTION +
            "for b in $names; do command -v \"\$b\" >/dev/null || continue; " +
            "echo \"JANGADA_SIZE \$b \$(size_of \"\$b\")\"; done; true"
    }

    /**
     * Tamanho de um agente: a pasta onde ele foi instalado (ex.: ~/.opencode,
     * ~/.local/share/claude, o pacote em node_modules) ou o próprio arquivo.
     */
    private const val SIZE_FUNCTION =
        "size_of() { p=\$(command -v \"\$1\" 2>/dev/null) || return; t=\$(readlink -f \"\$p\"); d=\"\$t\"; " +
            "case \"\$t\" in " +
            "/root/.local/share/*) d=\$(printf '%s' \"\$t\" | cut -d/ -f1-5) ;; " +
            "/root/.local/*) d=\"\$t\" ;; " +
            "/root/.*/*) d=\$(printf '%s' \"\$t\" | cut -d/ -f1-3) ;; " +
            "*/node_modules/*) d=\$(printf '%s' \"\$t\" | sed -E 's#(.*/node_modules/(@[^/]+/)?[^/]+).*#\\1#') ;; " +
            "esac; du -sk \"\$d\" 2>/dev/null | cut -f1; }; "
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

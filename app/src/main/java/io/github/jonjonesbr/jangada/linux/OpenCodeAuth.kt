package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.agents.AgentRecipe
import org.json.JSONArray
import java.io.IOException

/**
 * Contas e chaves do OpenCode (ver [LinuxEnv]): lista as contas conectadas, conecta uma chave de API
 * (BYOK), remove uma conta e volta ao plano free (remove todas). Os scripts rodam no Debian. As
 * funções que rodam processos ([list], [login], [remove], [removeAll]) só podem ser chamadas fora da
 * thread principal; erros de processo viram null/false, nunca exceção. As funções de texto
 * ([parseAccounts], [parseListText], [isValidIntegration], [isValidKey], os scripts) são puras.
 *
 * A chave de API nunca entra na linha de comando nem no script: vai pela entrada padrão do processo
 * (ver [loginScript]) e a saída do login é descartada. Não há cópia dela em lugar nenhum do app.
 */
object OpenCodeAuth {

    /** Id da receita do agente no catálogo (assets/agents/opencode.json). */
    const val AGENT_ID = "opencode"

    /** Conta conectada. [integration] é o id (ex.: opencode-go); só no caminho da lista de texto, o nome mostrado. */
    data class Account(val integration: String, val label: String, val active: Boolean)

    /** Integrações sugeridas no painel (id → nome mostrado). Outro id é digitado pelo usuário. */
    val SUGGESTED: List<Pair<String, String>> = listOf(
        "opencode-go" to "OpenCode Go",
        "openrouter" to "OpenRouter",
        "anthropic" to "Anthropic",
        "openai" to "OpenAI",
        "google" to "Google",
    )

    /** Tamanho máximo de uma chave de API. */
    const val MAX_KEY = 512

    /** Marcas da primeira linha de [listScript]: de onde veio a lista. */
    private const val MARK_SQL = "JANGADA_ACCOUNTS_SQL"
    private const val MARK_TEXT = "JANGADA_ACCOUNTS_TEXT"
    private const val MARK_JSON = "JANGADA_ACCOUNTS_JSON"

    /** Caminho do banco do OpenCode, dentro do Debian (expandido pelo shell). */
    private const val DB = "\"\$HOME/.local/share/opencode/opencode.db\""

    /** Espera antes de digitar a chave: o `opencode auth login` precisa de tempo para abrir o campo. */
    private const val LOGIN_WAIT = 8

    /** Tempo máximo (segundos) do login inteiro; passou disso, o script é encerrado. */
    private const val LOGIN_TIMEOUT = 60

    private val INTEGRATION = Regex("^[a-z0-9][a-z0-9._-]*$")

    /** Id de integração válido (também vai como argumento de comando, então é restrito). */
    fun isValidIntegration(id: String): Boolean = INTEGRATION.matches(id)

    /** Texto que vai como argumento de comando: sem quebra de linha nem caractere de controle (o [AgentRecipe.q] cuida das aspas). */
    private fun isSafeText(text: String): Boolean = text.isNotBlank() && text.none { it.isISOControl() }

    /** Chave não vazia, de até [MAX_KEY] caracteres e sem caracteres de controle (nem quebra de linha). */
    fun isValidKey(key: String): Boolean =
        key.isNotEmpty() && key.length <= MAX_KEY && key.none { it.isISOControl() }

    /**
     * Lê a saída de [listScript]. A primeira linha diz de onde veio: com a marca do SQLite, cada linha
     * é `id|rótulo|ativa`; com a do `auth list`, a lista de texto. Sem marca, a saída não se entende
     * e a lista fica nula (ver [list]).
     */
    fun parseListed(lines: List<String>): List<Account>? {
        // A marca pode não ser a primeira linha: o bash -i do runBash avisa antes ("no job control").
        val first = lines.indexOfFirst { it.trim() == MARK_SQL || it.trim() == MARK_TEXT || it.trim() == MARK_JSON }
        if (first < 0) return null
        val rest = lines.drop(first + 1)
        return when (lines[first].trim()) {
            MARK_SQL -> parseAccounts(rest)
            MARK_TEXT -> parseListText(rest)
            MARK_JSON -> parseListJson(rest.joinToString("\n"))
            else -> null
        }
    }

    /**
     * Linhas `id|rótulo|ativa` do SQLite. O id não tem '|' (ver [isValidIntegration]), então a última
     * coluna é o estado e o rótulo é o que sobra no meio. Ativa é tudo menos "0". Linha sem id válido
     * ou com menos de três colunas é ignorada.
     */
    fun parseAccounts(lines: List<String>): List<Account> = lines.mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size < 3) return@mapNotNull null
        val id = parts.first()
        if (!isValidIntegration(id)) return@mapNotNull null
        val label = parts.subList(1, parts.size - 1).joinToString("|").trim()
        Account(integration = id, label = label, active = parts.last() != "0")
    }

    /**
     * Linhas do `opencode auth list` (colunas separadas por dois espaços ou mais): nome da integração,
     * rótulo e estado. Sem id, então o nome vira a integração (e o alvo de [logoutScript]). Linhas que
     * não têm as três colunas (a mensagem "No authenticated integrations", caixas do terminal) são ignoradas.
     */
    fun parseListText(lines: List<String>): List<Account> = lines.mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s{2,}"))
        if (parts.size < 3 || parts[0].isEmpty() || parts[1].isEmpty()) return@mapNotNull null
        // O texto não diz qual conta está em uso: cada uma listada conta como ativa.
        Account(integration = parts[0], label = parts[1], active = true)
    }

    /**
     * Saída de `opencode auth list --format json`: uma lista de integrações (`id`, `name`) com as
     * conexões de cada uma (`label`, `method`). A chave não vem nesse JSON; mesmo assim, só id e
     * rótulo são lidos. O JSON não diz qual conta está em uso: cada uma conta como ativa. Integração
     * com id inválido ou conexão sem rótulo é ignorada. JSON que não se entende vira null.
     */
    fun parseListJson(text: String): List<Account>? = try {
        val start = text.indexOf('[')
        if (start < 0) null
        else {
            val integrations = JSONArray(text.substring(start))
            val accounts = ArrayList<Account>()
            for (i in 0 until integrations.length()) {
                val integration = integrations.optJSONObject(i) ?: continue
                val id = integration.optString("id")
                if (!isValidIntegration(id)) continue
                val connections = integration.optJSONArray("connections") ?: continue
                for (j in 0 until connections.length()) {
                    val label = connections.optJSONObject(j)?.optString("label")?.trim().orEmpty()
                    if (isSafeText(label)) accounts += Account(integration = id, label = label, active = true)
                }
            }
            accounts
        }
    } catch (e: org.json.JSONException) {
        null
    }

    /** Nome mostrado de um id: o da lista sugerida ou o próprio id. */
    fun displayName(id: String): String = SUGGESTED.firstOrNull { it.first == id }?.second ?: id

    /**
     * Script que lista as contas. Usa o SQLite do OpenCode quando existe (id e rótulo confiáveis); sem
     * ele (o Debian do app não traz sqlite3), cai para `opencode auth list`. A coluna `value` do banco
     * tem a chave: a consulta nunca a lê.
     */
    fun listScript(): String = listOf(
        // Consulta que falha (banco travado pelo opencode aberto, tabela diferente) cai no auth list, em vez
        // de virar "nenhuma conta" (e o "voltar ao plano free" dizer que removeu tudo).
        "if command -v sqlite3 >/dev/null 2>&1 && [ -f $DB ] &&",
        "  rows=${'$'}(sqlite3 -cmd '.timeout 3000' $DB \"select integration_id||'|'||coalesce(label,'')||'|'||coalesce(active,1) from credential\" 2>/dev/null); then",
        "  echo $MARK_SQL",
        "  [ -n \"${'$'}rows\" ] && printf '%s\\n' \"${'$'}rows\"",
        // Sem sqlite3 (o caso do celular): o JSON do auth list traz o id e o rótulo, sem a chave.
        // Versão antiga do opencode, sem --format json, cai na lista de texto.
        "elif json=${'$'}(timeout 20 opencode auth list --format json --standalone </dev/null 2>/dev/null) &&",
        "  printf '%s' \"${'$'}json\" | grep -q '^ *\\['; then",
        "  echo $MARK_JSON",
        "  printf '%s\\n' \"${'$'}json\"",
        "else",
        "  echo $MARK_TEXT",
        "  opencode auth list --standalone </dev/null 2>/dev/null",
        "fi",
    ).joinToString("\n")

    /**
     * Script que conecta a integração [id] com uma chave de API. Lê a chave da entrada padrão (uma
     * linha) e a digita num pseudo-terminal, porque o `opencode auth login` não aceita a chave por
     * argumento nem sem terminal. O id passa por [AgentRecipe.q]. A saída (o que o terminal mostra)
     * vai para o mesmo fluxo e é descartada por [login].
     */
    fun loginScript(id: String): String {
        val inner = "opencode auth login ${AgentRecipe.q(id)} --standalone --method key"
        return listOf(
            // O startProcess não junta o erro à saída: junta aqui, para o login não travar numa pipe cheia.
            "exec 2>&1",
            "IFS= read -r JANGADA_KEY",
            "(sleep $LOGIN_WAIT; printf '%s\\r' \"\$JANGADA_KEY\"; sleep $LOGIN_WAIT) | " +
                "timeout $LOGIN_TIMEOUT script -qec ${AgentRecipe.q(inner)} /dev/null 2>&1",
        ).joinToString("\n")
    }

    /**
     * Script que remove a conta [account]: `opencode auth logout <integração> <rótulo>`. Os dois
     * valores passam por [AgentRecipe.q]; sem o rótulo o OpenCode pede um terminal e falha.
     */
    fun logoutScript(account: Account): String =
        "opencode auth logout ${AgentRecipe.q(account.integration)} ${AgentRecipe.q(account.label)} --standalone </dev/null"

    /** Contas conectadas, ou null se o Debian não respondeu como esperado. Fora da thread principal. */
    fun list(linux: LinuxEnv): List<Account>? = try {
        val lines = ArrayList<String>()
        linux.runBash(listScript()) { lines += it }
        parseListed(lines)
    } catch (e: IOException) {
        null
    }

    /**
     * Conecta a integração [id] com a chave [key]. Verdadeiro se o login saiu com código 0 e, com SQLite, a conta apareceu. A chave vai
     * pela entrada padrão do processo; nada dela é guardado, mostrado ou escrito em log. Fora da
     * thread principal.
     */
    fun login(linux: LinuxEnv, id: String, key: String): Boolean {
        if (!isValidIntegration(id) || !isValidKey(key)) return false
        var process: Process? = null
        val exited = try {
            process = linux.startProcess(loginScript(id), secrets = false)
            process.outputStream.use { it.write("$key\n".toByteArray(Charsets.UTF_8)) }
            // Saída descartada: o terminal do login mostra a chave mascarada e o resultado; não é guardada.
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor() == 0
        } catch (e: IOException) {
            // O script morreu cedo (a escrita falhou): não fica processo para trás.
            process?.destroy()
            false
        }
        if (!exited) return false
        // Saiu com 0, mas a chave pode não ter chegado (o campo abriu depois da digitação): confere no SQLite,
        // que traz o id. A lista de texto traz só o nome mostrado, e sem lista vale o código de saída.
        return loggedIn(linux, id) ?: true
    }

    /** A integração [id] aparece no SQLite ou no JSON do OpenCode? null se não deu para saber (lista de texto, erro). */
    private fun loggedIn(linux: LinuxEnv, id: String): Boolean? = try {
        val lines = ArrayList<String>()
        linux.runBash(listScript()) { lines += it }
        if (lines.none { it.trim() == MARK_SQL || it.trim() == MARK_JSON }) null
        else parseListed(lines)?.any { it.integration == id }
    } catch (e: IOException) {
        null
    }

    /** Resultado de perguntar ao provedor se a chave vale. */
    enum class KeyCheck { VALID, INVALID, UNKNOWN }

    /**
     * Como perguntar a cada provedor se a chave vale, sem gastar nada (lista de modelos ou dados da
     * chave). [header] tem um %s no lugar da chave. Conferido em 12/11/2026 com chaves falsas: 401
     * em todos, menos o Google (400 com "API key not valid"). As rotas do OpenCode respondem 200 sem
     * chave e 401 com chave errada.
     */
    private class KeyProbe(val url: String, val header: String, val extra: String? = null)

    private val PROBES = mapOf(
        "opencode-go" to KeyProbe("https://opencode.ai/zen/go/v1/models", "Authorization: Bearer %s"),
        "opencode" to KeyProbe("https://opencode.ai/zen/v1/models", "Authorization: Bearer %s"),
        "openrouter" to KeyProbe("https://openrouter.ai/api/v1/key", "Authorization: Bearer %s"),
        "anthropic" to KeyProbe("https://api.anthropic.com/v1/models", "x-api-key: %s", "anthropic-version: 2023-06-01"),
        "openai" to KeyProbe("https://api.openai.com/v1/models", "Authorization: Bearer %s"),
        "google" to KeyProbe("https://generativelanguage.googleapis.com/v1beta/models", "x-goog-api-key: %s"),
    )

    private const val MARK_KEYCHECK = "JANGADA_KEYCHECK"

    /** Dá para conferir a chave da integração [id] antes de conectar. */
    fun canCheckKey(id: String): Boolean = id in PROBES

    /**
     * Script que pergunta ao provedor de [id] se a chave vale. A chave vem pela entrada padrão (uma
     * linha) e vai ao curl pela entrada padrão também (`-H @-`): nunca entra na linha de comando de um
     * processo. Imprime `JANGADA_KEYCHECK <código http> <1 se a resposta diz chave inválida>`. Null
     * se o provedor não é conhecido.
     */
    fun keyCheckScript(id: String): String? {
        val probe = PROBES[id] ?: return null
        val headers = listOfNotNull(probe.header, probe.extra).joinToString("\\n")
        return listOf(
            "exec 2>/dev/null",
            "IFS= read -r JANGADA_KEY",
            // printf é do próprio bash: a chave não aparece na lista de processos.
            "out=${'$'}(printf '$headers\\n' \"${'$'}JANGADA_KEY\" | curl -s -m 15 -w '\\n$MARK_KEYCHECK_HTTP %{http_code}' -H @- ${AgentRecipe.q(probe.url)})",
            "unset JANGADA_KEY",
            "code=${'$'}{out##*$MARK_KEYCHECK_HTTP }",
            "bad=0",
            "case \"${'$'}out\" in *'API key not valid'*|*API_KEY_INVALID*) bad=1 ;; esac",
            "echo \"$MARK_KEYCHECK ${'$'}code ${'$'}bad\"",
        ).joinToString("\n")
    }

    private const val MARK_KEYCHECK_HTTP = "JANGADA_HTTP"

    /**
     * Lê a saída de [keyCheckScript]: 2xx vale; 401, 403 ou a resposta dizendo que a chave é inválida
     * (o Google responde 400) não vale; o resto (sem internet, tempo esgotado, 429, 5xx) não se sabe.
     */
    fun parseKeyCheck(lines: List<String>): KeyCheck {
        val line = lines.lastOrNull { it.trim().startsWith("$MARK_KEYCHECK ") } ?: return KeyCheck.UNKNOWN
        val parts = line.trim().split(Regex("\\s+"))
        val code = parts.getOrNull(1)?.toIntOrNull() ?: return KeyCheck.UNKNOWN
        val bad = parts.getOrNull(2) == "1"
        return when {
            code in 200..299 -> KeyCheck.VALID
            code == 401 || code == 403 || bad -> KeyCheck.INVALID
            else -> KeyCheck.UNKNOWN
        }
    }

    /**
     * Pergunta ao provedor de [id] se [key] vale, sem conectar nada. UNKNOWN se o provedor não é
     * conhecido ou a pergunta falhou. A chave vai pela entrada padrão e a resposta não é guardada.
     * Fora da thread principal.
     */
    fun checkKey(linux: LinuxEnv, id: String, key: String): KeyCheck {
        val script = keyCheckScript(id) ?: return KeyCheck.UNKNOWN
        if (!isValidKey(key)) return KeyCheck.UNKNOWN
        var process: Process? = null
        return try {
            val started = linux.startProcess(script, secrets = false)
            process = started
            started.outputStream.use { it.write("$key\n".toByteArray(Charsets.UTF_8)) }
            val lines = started.inputStream.bufferedReader().use { it.readLines() }
            started.waitFor()
            parseKeyCheck(lines)
        } catch (e: IOException) {
            KeyCheck.UNKNOWN
        } catch (e: InterruptedException) {
            // Quem chamou foi interrompido: devolve a interrupção à thread e não dá resposta.
            Thread.currentThread().interrupt()
            KeyCheck.UNKNOWN
        } finally {
            // Sempre encerra o processo (ex.: se a leitura falhou ou a espera foi interrompida).
            process?.destroy()
        }
    }

    /** Remove a conta [account]. Verdadeiro se o logout saiu com código 0. Fora da thread principal. */
    fun remove(linux: LinuxEnv, account: Account): Boolean {
        if (!isSafeText(account.integration) || !isSafeText(account.label)) return false
        return exitCode(linux, logoutScript(account)) == 0
    }

    /**
     * Remove todas as contas (plano free). Devolve quantas não saíram, ou null se a lista não pôde ser
     * lida. Fora da thread principal.
     */
    fun removeAll(linux: LinuxEnv): Int? {
        val accounts = list(linux) ?: return null
        return accounts.count { !remove(linux, it) }
    }

    /** Código de saída de [script] no Debian, ou -1 se o processo não pôde rodar. */
    private fun exitCode(linux: LinuxEnv, script: String): Int = try {
        linux.runBash(script) {}
    } catch (e: IOException) {
        -1
    }
}

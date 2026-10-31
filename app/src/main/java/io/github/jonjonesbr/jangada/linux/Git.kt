package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.agents.AgentRecipe

/**
 * Git dentro do Debian. [run] espera o processo terminar: chame só fora da
 * thread principal. As funções de texto ([parseStatus], [parseNul], [branchName],
 * [identityMissing]) são puras.
 */
object Git {

    /**
     * Resultado de um comando: código de saída (-1 se nem chegou a rodar), a saída (stdout e
     * stderr juntos) e se ela foi cortada em [MAX_OUTPUT] (o resto foi lido e descartado).
     */
    class Result(val code: Int, val output: String, val cut: Boolean = false) {
        /** A saída para mostrar: com "…" no fim, se foi cortada. */
        val shown: String get() = if (cut) "$output\n…" else output
    }

    /**
     * Uma entrada de `git status --porcelain=v1 -z`: o caminho (o novo, em renomeação ou cópia),
     * o código de 2 letras e, em renomeação ou cópia, o caminho antigo. Pasta não rastreada
     * termina em "/" (o modo padrão mostra a pasta, não os arquivos dela).
     */
    class Change(val path: String, val code: String, val from: String? = null) {
        val isUntrackedDir: Boolean get() = code == "??" && path.endsWith("/")
    }

    /** Tamanho máximo da saída guardada; o resto é descartado. */
    private const val MAX_OUTPUT = 200 * 1024

    /** Maior nome de branch aceito vindo do git. */
    private const val MAX_BRANCH = 200

    /** Trechos da mensagem do git quando falta nome ou e-mail para o commit. */
    private val IDENTITY = listOf("Please tell me who you are", "user.email", "auto-detect email")

    /**
     * Roda `git args...` na pasta [cwd] do Debian, sem pager, sem cores e sem pedir senha, com a
     * mensagem em inglês (é o que [identityMissing] reconhece). Cada argumento vai entre aspas
     * simples ([AgentRecipe.q]): aspas, `$`, crase e quebras de linha chegam ao git como texto.
     * Exceções viram Result(-1, mensagem).
     *
     * A saída chega por linhas; a quebra é recolocada como "\n". Com `-z` (separado por NUL) isso
     * só muda um nome de arquivo com "\r" solto, que vira "\n" (raríssimo).
     */
    fun run(linux: LinuxEnv, cwd: String, vararg args: String): Result {
        val out = StringBuilder()
        var cut = false
        return try {
            val command = "cd ${AgentRecipe.q(cwd)} && GIT_PAGER=cat GIT_TERMINAL_PROMPT=0 LC_ALL=C.UTF-8 " +
                "git -c color.ui=false -c core.quotepath=false " +
                args.joinToString(" ") { AgentRecipe.q(it) }
            val code = linux.run(command) { line ->
                if (!cut) {
                    val room = MAX_OUTPUT - out.length
                    if (line.length + 1 <= room) {
                        out.append(line).append('\n')
                    } else {
                        // Guarda o pedaço que cabe (com -z tudo vem numa "linha" só) e descarta o resto.
                        out.append(line, 0, room.coerceIn(0, line.length))
                        cut = true
                    }
                }
            }
            Result(code, out.toString(), cut)
        } catch (e: Exception) {
            Result(-1, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * `git status --porcelain=v1 -z` na pasta [cwd], com o modo padrão de não rastreados (uma
     * pasta nova aparece como "pasta/", sem listar o que tem dentro, como node_modules). Não
     * pega a trava do índice (`--no-optional-locks`): pode rodar junto com um commit ou um agente.
     */
    fun status(linux: LinuxEnv, cwd: String): Result =
        run(linux, cwd, "--no-optional-locks", "status", "--porcelain=v1", "-z")

    /**
     * Lê `git status --porcelain=v1 -z`: entradas separadas por NUL, "XY caminho". Em renomeação
     * ou cópia, o caminho antigo vem como a entrada seguinte. Entradas vazias ou incompletas
     * (o "\n" final de [run]) são ignoradas. Com a saída cortada, a última entrada pode estar
     * pela metade: quem chama descarta ela (ver [Result.cut]).
     */
    fun parseStatus(output: String): List<Change> {
        val fields = output.split('\u0000')
        val changes = ArrayList<Change>()
        var i = 0
        while (i < fields.size) {
            val entry = fields[i++]
            if (entry.length < 4 || entry[2] != ' ') continue
            val code = entry.substring(0, 2)
            val path = entry.substring(3)
            var from: String? = null
            if ('R' in code || 'C' in code) {
                from = fields.getOrNull(i)?.takeIf { it.isNotEmpty() && it != "\n" }
                i++
            }
            changes += Change(path, code, from)
        }
        return changes
    }

    /** Caminhos de uma saída com `-z` (ex.: `git ls-files -z`): separados por NUL, sem os vazios. */
    fun parseNul(output: String): List<String> =
        output.split('\u0000').filter { it.isNotEmpty() && it != "\n" }

    /**
     * Branch da saída de `git symbolic-ref -q --short HEAD`; null se o HEAD está solto (código
     * diferente de 0) ou se o nome não é confiável para virar argumento: vazio, com espaço ou
     * controle, comprido demais ou começando com "-" (seria lido como opção).
     */
    fun branchName(result: Result): String? {
        if (result.code != 0 || result.cut) return null
        return branchName(result.output)
    }

    internal fun branchName(output: String): String? {
        val name = output.trim()
        if (name.isEmpty() || name.length > MAX_BRANCH || name.startsWith("-")) return null
        if (name.any { it.isWhitespace() || it.isISOControl() }) return null
        return name
    }

    /** A saída do git diz que falta nome ou e-mail para o commit. */
    fun identityMissing(output: String): Boolean = IDENTITY.any { output.contains(it) }
}

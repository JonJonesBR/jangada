package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.agents.AgentRecipe

/**
 * Git dentro do Debian. [run] espera o processo terminar: chame só fora da
 * thread principal. As funções de texto ([parseStatus], [identityMissing]) são puras.
 */
object Git {

    /** Resultado de um comando: código de saída (-1 se nem chegou a rodar) e a saída (stdout e stderr juntos). */
    class Result(val code: Int, val output: String)

    /** Uma linha de `git status --porcelain=v1`: o caminho (o novo, em renomeação) e o código de 2 letras. */
    class Change(val path: String, val code: String)

    /** Tamanho máximo da saída guardada; o resto é descartado e vira "…". */
    private const val MAX_OUTPUT = 200 * 1024

    /** Trechos da mensagem do git quando falta nome ou e-mail para o commit. */
    private val IDENTITY = listOf("Please tell me who you are", "user.email", "auto-detect email")

    /**
     * Roda `git args...` na pasta [cwd] do Debian, sem pager e sem cores, com a
     * mensagem em inglês (é o que [identityMissing] reconhece). Exceções viram Result(-1, mensagem).
     */
    fun run(linux: LinuxEnv, cwd: String, vararg args: String): Result {
        val out = StringBuilder()
        var cut = false
        return try {
            val command = "cd ${AgentRecipe.q(cwd)} && GIT_PAGER=cat LC_ALL=C.UTF-8 " +
                "git -c color.ui=false -c core.quotepath=false " +
                args.joinToString(" ") { AgentRecipe.q(it) }
            val code = linux.run(command) { line ->
                if (!cut) {
                    if (out.length + line.length + 1 > MAX_OUTPUT) cut = true
                    else out.append(line).append('\n')
                }
            }
            Result(code, if (cut) "$out…\n" else out.toString())
        } catch (e: Exception) {
            Result(-1, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Lê `git status --porcelain=v1`: uma entrada por linha. Em renomeação ("antigo -> novo"),
     * o caminho é o novo. Linhas vazias são ignoradas.
     */
    fun parseStatus(output: String): List<Change> = output.lineSequence()
        .filter { it.length >= 4 }
        .map { line ->
            val code = line.substring(0, 2)
            val rest = line.substring(3)
            val path = if ('R' in code || 'C' in code) rest.substringAfter(" -> ", rest) else rest
            Change(path, code)
        }
        .toList()

    /** A saída do git diz que falta nome ou e-mail para o commit. */
    fun identityMissing(output: String): Boolean = IDENTITY.any { output.contains(it) }
}

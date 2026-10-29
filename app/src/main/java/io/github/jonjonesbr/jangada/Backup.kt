package io.github.jonjonesbr.jangada

import android.content.Context
import android.net.Uri
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Backup e restauração do que é seu dentro do Linux: os projetos e, se
 * quiser, as configurações e logins dos agentes. Os programas dos agentes
 * ficam de fora (são reinstalados pelo catálogo), o que deixa o arquivo
 * pequeno. O arquivo é um .tar.gz comum, que abre em qualquer computador.
 */
object Backup {

    private const val PROJECTS = "cd /root && tar -czf - ${LinuxEnv.WORKSPACE}"

    /** Tudo da pasta pessoal, menos programas e caches (que voltam pelo catálogo). */
    private val EVERYTHING = "cd /root && tar -czf - " + listOf(
        "./.cache", "./.npm", "./.bun", "./.local/share/claude", "./.local/bin", "./.opencode/bin",
        "./.local/share/uv", "./celular", "*/node_modules",
    ).joinToString(" ") { "--exclude='$it'" } + " ."

    /** Grava o backup em [uri] (escolhido pelo usuário). Devolve o tamanho em bytes. */
    fun export(context: Context, uri: Uri, everything: Boolean): Long {
        val linux = LinuxEnv(context)
        val process = linux.startProcess(if (everything) EVERYTHING else PROJECTS, login = false)
        val errors = StringBuilder()
        val stderr = thread { runCatching { errors.append(process.errorStream.bufferedReader().readText()) } }
        var total = 0L
        context.contentResolver.openOutputStream(uri)?.use { out ->
            process.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                }
            }
        } ?: throw IOException("não consegui abrir o arquivo de destino")
        val code = process.waitFor()
        stderr.join(2000)
        // tar sai com 1 quando um arquivo mudou durante a leitura: o backup ainda vale.
        if (code > 1) throw IOException(errors.lines().lastOrNull { it.isNotBlank() } ?: "tar terminou com código $code")
        return total
    }

    /** Extrai o backup de [uri] dentro da pasta pessoal do Linux. */
    fun restore(context: Context, uri: Uri) {
        val linux = LinuxEnv(context)
        val process = linux.startProcess("cd /root && tar -xzf -", login = false)
        val errors = StringBuilder()
        val stderr = thread { runCatching { errors.append(process.errorStream.bufferedReader().readText()) } }
        context.contentResolver.openInputStream(uri)?.use { input ->
            process.outputStream.use { input.copyTo(it, 64 * 1024) }
        } ?: throw IOException("não consegui abrir o arquivo do backup")
        val code = process.waitFor()
        stderr.join(2000)
        if (code != 0) throw IOException(errors.lines().lastOrNull { it.isNotBlank() } ?: "tar terminou com código $code")
        runCatching { linux.refreshIntegration() }
    }
}

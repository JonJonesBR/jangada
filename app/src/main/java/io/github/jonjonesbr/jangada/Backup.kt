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
        // O destino é aberto antes do processo: se não abrir, nada foi iniciado.
        val target = context.contentResolver.openOutputStream(uri)
            ?: throw IOException(context.getString(R.string.backup_error_target))
        return target.use { out ->
            val process = linux.startProcess(if (everything) EVERYTHING else PROJECTS, login = false, secrets = false)
            try {
                val errors = StringBuilder()
                val stderr = thread { runCatching { errors.append(process.errorStream.bufferedReader().readText()) } }
                var total = 0L
                process.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        total += n
                    }
                }
                val code = process.waitFor()
                stderr.join(2000)
                // tar sai com 1 quando um arquivo mudou durante a leitura: o backup ainda vale.
                if (code > 1) {
                    throw IOException(
                        errors.lines().lastOrNull { it.isNotBlank() }
                            ?: context.getString(R.string.backup_error_tar_exit, code),
                    )
                }
                total
            } finally {
                // Se algo falhou no meio, não deixa o tar rodando; se já terminou, não faz nada.
                process.destroy()
            }
        }
    }

    /** Extrai o backup de [uri] dentro da pasta pessoal do Linux. */
    fun restore(context: Context, uri: Uri) {
        val linux = LinuxEnv(context)
        // Como no export: o arquivo é aberto antes do processo, e o tar nunca fica rodando à toa.
        val source = context.contentResolver.openInputStream(uri)
            ?: throw IOException(context.getString(R.string.backup_error_open_backup))
        source.use { input ->
            val process = linux.startProcess("cd /root && tar -xzf -", login = false, secrets = false)
            try {
                val errors = StringBuilder()
                val stderr = thread { runCatching { errors.append(process.errorStream.bufferedReader().readText()) } }
                try {
                    process.outputStream.use { input.copyTo(it, 64 * 1024) }
                } catch (e: IOException) {
                    // Arquivo que não é backup faz o tar sair cedo: o "Broken pipe" não é o erro real.
                    // O use acima já fechou o stdin; espera o tar e mostra a mensagem dele, se houver.
                    val code = process.waitFor()
                    stderr.join(2000)
                    // A primeira linha é a causa (ex.: "gzip: stdin: not in gzip format"); as seguintes são consequência.
                    val message = errors.lines().firstOrNull { it.isNotBlank() }
                    if (code != 0 && message != null) throw IOException(message)
                    throw e
                }
                val code = process.waitFor()
                stderr.join(2000)
                if (code != 0) {
                    throw IOException(
                        errors.lines().lastOrNull { it.isNotBlank() }
                            ?: context.getString(R.string.backup_error_tar_exit, code),
                    )
                }
            } finally {
                process.destroy()
            }
        }
        runCatching { linux.refreshIntegration() }
    }
}

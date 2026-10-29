package io.github.jonjonesbr.jangada.linux

import java.io.File

/**
 * O Android esconde alguns arquivos de /proc dos apps. Programas Linux
 * (Node.js, por exemplo) leem esses arquivos e podem travar sem eles.
 * Aqui criamos substitutos falsos, como o proot-distro faz, e só os
 * usamos quando o arquivo real não pode ser lido.
 */
object FakeSysData {

    const val KERNEL_RELEASE = "6.17.0-jangada"
    const val KERNEL_VERSION = "#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000"

    private fun entries(cpus: Int): List<Triple<String, String, String>> = listOf(
        Triple("loadavg", "/proc/loadavg", "0.12 0.07 0.02 2/165 765\n"),
        Triple("stat", "/proc/stat", fakeStat(cpus)),
        Triple("uptime", "/proc/uptime", "124.08 ${"%.2f".format(java.util.Locale.US, 120.27 * cpus)}\n"),
        Triple(
            "version", "/proc/version",
            "Linux version $KERNEL_RELEASE (jangada@localhost) (gcc) $KERNEL_VERSION\n",
        ),
        Triple("vmstat", "/proc/vmstat", VMSTAT),
        Triple("cap_last_cap", "/proc/sys/kernel/cap_last_cap", "40\n"),
        Triple("max_user_watches", "/proc/sys/fs/inotify/max_user_watches", "4096\n"),
        Triple("overflowuid", "/proc/sys/kernel/overflowuid", "65534\n"),
        Triple("overflowgid", "/proc/sys/kernel/overflowgid", "65534\n"),
    )

    /** Escreve os arquivos em [dir] e devolve os --bind para o proot. */
    fun bindings(dir: File): List<String> {
        dir.mkdirs()
        val emptyDir = File(dir, "sys_empty").apply { mkdirs() }
        val result = mutableListOf("--bind=${emptyDir.absolutePath}:/sys/fs/selinux")
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        for ((name, real, content) in entries(cpus)) {
            if (isReadable(real)) continue
            val file = File(dir, name)
            file.writeText(content)
            result += "--bind=${file.absolutePath}:$real"
        }
        return result
    }

    private fun isReadable(path: String): Boolean = try {
        File(path).inputStream().use { it.read() }
        true
    } catch (e: Exception) {
        false
    }

    private fun fakeStat(cpus: Int): String {
        val row = longArrayOf(31, 0, 226, 12027, 82, 10, 4, 9, 0, 0)
        val total = row.map { it * cpus }
        return buildString {
            append("cpu  ").append(total.joinToString(" ")).append('\n')
            for (i in 0 until cpus) append("cpu$i ").append(row.joinToString(" ")).append('\n')
            append("intr 0\nctxt 140223\nbtime 1680020856\nprocesses 772\n")
            append("procs_running 2\nprocs_blocked 0\nsoftirq 0 0 0 0 0 0 0 0 0 0 0\n")
        }
    }

    private const val VMSTAT = """nr_free_pages 1743136
nr_inactive_anon 179281
nr_active_anon 7183
nr_inactive_file 22858
nr_active_file 51328
nr_unevictable 642
nr_mlock 0
nr_anon_pages 7723
nr_mapped 8905
nr_file_pages 253569
nr_dirty 0
nr_writeback 0
nr_shmem 178741
pgpgin 198734
pgpgout 3436
pswpin 0
pswpout 0
pgfault 1209438
pgmajfault 1218
"""
}

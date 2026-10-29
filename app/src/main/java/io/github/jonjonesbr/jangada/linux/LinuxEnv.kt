package io.github.jonjonesbr.jangada.linux

import android.content.Context
import android.os.Build
import java.io.File

/**
 * O Linux (Debian) guardado dentro do app: onde ficam as peças e como
 * montar o comando do proot para entrar nele.
 */
class LinuxEnv(context: Context) {

    private val app = context.applicationContext
    private val files: File = app.filesDir

    private val bootstrapDir = File(files, "bootstrap")
    private val libDir = File(bootstrapDir, "lib")
    val linuxDir = File(files, "linux")
    val rootfs = File(linuxDir, "rootfs")
    private val sysdataDir = File(linuxDir, "sysdata")
    private val shmDir = File(linuxDir, "shm")
    private val prootTmp = File(linuxDir, "tmp")
    private val rootfsMarker = File(linuxDir, ".rootfs-ok")
    private val baseMarker = File(linuxDir, ".base-ok")

    val proot = File(bootstrapDir, "proot")

    /** Por enquanto o ambiente Linux existe só para celulares ARM de 64 bits. */
    val isDeviceSupported: Boolean
        get() = Build.SUPPORTED_ABIS.contains("arm64-v8a") &&
            app.assets.list(ASSET_DIR)?.contains("proot") == true

    val isRootfsReady: Boolean get() = rootfsMarker.exists()
    val isReady: Boolean get() = rootfsMarker.exists() && baseMarker.exists()

    fun markRootfsReady() = rootfsMarker.writeText(IMAGE + "\n")
    fun markBaseReady() = baseMarker.writeText(BASE_PACKAGES + "\n")
    fun clearMarkers() {
        rootfsMarker.delete()
        baseMarker.delete()
    }

    /** Copia proot e bibliotecas dos assets para uma pasta de onde podem ser executados. */
    fun installBootstrap() {
        val names = app.assets.list(ASSET_DIR) ?: return
        libDir.mkdirs()
        for (name in names) {
            val target = if (name.startsWith("lib")) File(libDir, name) else File(bootstrapDir, name)
            val temp = File(target.parentFile, ".$name.tmp")
            app.assets.open("$ASSET_DIR/$name").use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            temp.setReadable(true, true)
            temp.setExecutable(true, true)
            // rename por cima funciona mesmo com o proot antigo em execução.
            if (!temp.renameTo(target)) throw java.io.IOException("não consegui instalar $name")
        }
    }

    /** Variáveis de ambiente do lado Android, para o próprio proot. */
    fun hostEnv(): Array<String> = buildList {
        add("PATH=/system/bin:/system/xbin")
        add("ANDROID_DATA=/data")
        add("ANDROID_ROOT=/system")
        add("LD_LIBRARY_PATH=${libDir.absolutePath}")
        add("PROOT_LOADER=${File(bootstrapDir, "loader").absolutePath}")
        File(bootstrapDir, "loader32").takeIf { it.exists() }?.let { add("PROOT_LOADER_32=${it.absolutePath}") }
        add("PROOT_TMP_DIR=${prootTmp.absolutePath}")
        add("PROOT_L2S_DIR=${File(rootfs, ".l2s").absolutePath}")
    }.toTypedArray()

    /** Comando completo (argv) para rodar [inner] dentro do Debian. */
    fun prootArgv(inner: List<String>): Array<String> {
        listOf(sysdataDir, shmDir, prootTmp, File(rootfs, ".l2s"), File(rootfs, "tmp"), File(rootfs, "root"))
            .forEach { it.mkdirs() }

        val args = mutableListOf(proot.absolutePath)
        args += "--kill-on-exit"
        args += "--link2symlink"
        args += "--sysvipc"
        args += "--kernel-release=\\Linux\\localhost\\${FakeSysData.KERNEL_RELEASE}" +
            "\\${FakeSysData.KERNEL_VERSION}\\aarch64\\localdomain\\-1\\"
        args += "-L"
        args += "--change-id=0:0"
        args += "--rootfs=${rootfs.absolutePath}"
        args += "--cwd=/root"
        args += listOf("--bind=/dev", "--bind=/proc", "--bind=/sys")
        args += "--bind=/dev/urandom:/dev/random"
        if (!File("/dev/fd").exists()) args += "--bind=/proc/self/fd:/dev/fd"
        listOf("stdin", "stdout", "stderr").forEachIndexed { fd, name ->
            if (!File("/dev/$name").exists()) args += "--bind=/proc/self/fd/$fd:/dev/$name"
        }
        args += FakeSysData.bindings(sysdataDir)
        args += "--bind=${shmDir.absolutePath}:/dev/shm"
        args += inner
        return args.toTypedArray()
    }

    /** Shell interativo do Debian, com ambiente limpo. */
    fun loginCommand(): List<String> = guestEnv() + listOf("/bin/bash", "--login")

    /** Script não interativo (usado na instalação). */
    fun scriptCommand(script: String): List<String> =
        guestEnv() + listOf("DEBIAN_FRONTEND=noninteractive", "/bin/sh", "-c", script)

    private fun guestEnv() = listOf(
        "/usr/bin/env", "-i",
        "HOME=/root", "USER=root", "LOGNAME=root",
        "LANG=C.UTF-8", "TERM=xterm-256color", "COLORTERM=truecolor", "TMPDIR=/tmp",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    )

    /** Roda um script dentro do Debian e entrega a saída linha a linha. */
    fun run(script: String, onLine: (String) -> Unit): Int {
        val builder = ProcessBuilder(*prootArgv(scriptCommand(script)))
            .directory(files)
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            for (entry in hostEnv()) {
                val (key, value) = entry.split("=", limit = 2)
                put(key, value)
            }
        }
        val process = builder.start()
        process.outputStream.close()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        return process.waitFor()
    }

    /** Ajustes no Debian recém-extraído para ele funcionar dentro do Android. */
    fun applyFixups() {
        val etc = File(rootfs, "etc").apply { mkdirs() }
        writeReplacing(File(etc, "resolv.conf"), "nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        writeReplacing(
            File(etc, "hosts"),
            "127.0.0.1   localhost.localdomain localhost\n" +
                "::1         localhost.localdomain localhost ip6-localhost ip6-loopback\n",
        )
        writeReplacing(File(etc, "hostname"), "localhost\n")
        // O apt tenta trocar para o usuário _apt, o que o proot não emula bem.
        val aptConf = File(etc, "apt/apt.conf.d").apply { mkdirs() }
        writeReplacing(File(aptConf, "99jangada"), "APT::Sandbox::User \"root\";\n")
        // Instaladores como o do Claude Code colocam o programa em ~/.local/bin.
        val profileD = File(etc, "profile.d").apply { mkdirs() }
        writeReplacing(
            File(profileD, "jangada.sh"),
            "case \":${'$'}PATH:\" in *\":${'$'}HOME/.local/bin:\"*) ;; " +
                "*) PATH=\"${'$'}HOME/.local/bin:${'$'}PATH\" ;; esac\nexport PATH\n",
        )
    }

    /** Apaga links antes de escrever, para nunca escrever "através" deles. */
    private fun writeReplacing(file: File, content: String) {
        Fs.deleteTree(file.toPath())
        file.writeText(content)
    }

    companion object {
        private const val ASSET_DIR = "bootstrap/aarch64"
        const val IMAGE_REPOSITORY = "library/debian"
        const val IMAGE_TAG = "trixie-slim"
        const val IMAGE = "debian:$IMAGE_TAG"
        const val ARCHITECTURE = "arm64"
        const val BASE_PACKAGES = "ca-certificates curl git less nano procps unzip xz-utils"
    }
}

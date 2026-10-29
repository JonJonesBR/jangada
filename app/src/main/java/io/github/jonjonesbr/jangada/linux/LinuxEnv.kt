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

    /** Pasta (dentro do Debian: /var/lib/jangada/open) onde chegam os links a abrir. */
    val linkInbox = File(rootfs, "var/lib/jangada/open")

    /** O app pode ler e gravar na memória do celular (Downloads, Documentos...)? */
    val hasStorageAccess: Boolean
        get() = runCatching { File(SHARED_STORAGE).let { it.canRead() && it.canWrite() } }.getOrDefault(false)

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
        // Memória do celular: /sdcard dentro do Debian (e ~/celular aponta para lá).
        if (hasStorageAccess) {
            args += "--bind=/storage"
            args += "--bind=$SHARED_STORAGE:/sdcard"
        }
        args += inner
        return args.toTypedArray()
    }

    /** Shell interativo do Debian, com ambiente limpo. */
    fun loginCommand(): List<String> = guestEnv() + listOf("/bin/bash", "--login")

    /**
     * Roda [script] num bash interativo (lê ~/.bashrc, onde instaladores
     * costumam pôr o PATH) e, ao terminar, continua com um shell normal.
     */
    fun sessionScriptCommand(script: String): List<String> =
        guestEnv() + listOf("/bin/bash", "--login", "-i", "-c", script + "\nexec /bin/bash --login")

    /** Igual ao anterior, mas sem terminal: para consultas rápidas do app. */
    fun bashCommand(script: String): List<String> =
        guestEnv() + listOf("/bin/bash", "--login", "-i", "-c", script)

    /** Script não interativo (usado na instalação). */
    fun scriptCommand(script: String): List<String> =
        guestEnv() + listOf("DEBIAN_FRONTEND=noninteractive", "/bin/sh", "-c", script)

    private fun guestEnv() = listOf(
        "/usr/bin/env", "-i",
        "HOME=/root", "USER=root", "LOGNAME=root",
        "LANG=C.UTF-8", "TERM=xterm-256color", "COLORTERM=truecolor", "TMPDIR=/tmp",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "BROWSER=/usr/local/bin/jangada-open",
    )

    /** Roda um script dentro do Debian e entrega a saída linha a linha. */
    fun run(script: String, onLine: (String) -> Unit): Int = runArgv(scriptCommand(script), onLine)

    /** Como [run], mas num bash interativo (enxerga o PATH dos instaladores). */
    fun runBash(script: String, onLine: (String) -> Unit): Int = runArgv(bashCommand(script), onLine)

    /**
     * Inicia [script] num bash de login dentro do Debian e devolve o processo
     * (para conversar com ele por stdin/stdout, como faz o chat).
     */
    fun startProcess(script: String): Process {
        val builder = ProcessBuilder(*prootArgv(guestEnv() + listOf("/bin/bash", "--login", "-c", script)))
            .directory(files)
        builder.environment().apply {
            clear()
            for (entry in hostEnv()) {
                val (key, value) = entry.split("=", limit = 2)
                put(key, value)
            }
        }
        return builder.start()
    }

    private fun runArgv(inner: List<String>, onLine: (String) -> Unit): Int {
        val builder = ProcessBuilder(*prootArgv(inner))
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
        refreshIntegration()
    }

    /**
     * Peças que ligam o Debian ao app. Reescritas a cada sessão, então
     * versões novas do app atualizam instalações antigas sem reinstalar.
     */
    fun refreshIntegration() {
        val etc = File(rootfs, "etc")
        // Instaladores como o do Claude Code colocam o programa em ~/.local/bin;
        // BROWSER faz os agentes abrirem links pelo app.
        val profileD = File(etc, "profile.d").apply { mkdirs() }
        writeReplacing(File(profileD, "jangada.sh"), PROFILE_SCRIPT)

        // jangada-open: entrega um link ao app. Também atende como xdg-open etc.
        val bin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        val opener = File(bin, "jangada-open")
        writeReplacing(opener, OPEN_SCRIPT)
        Fs.setMode(opener.toPath(), "755".toInt(8))
        for (alias in listOf("xdg-open", "sensible-browser", "x-www-browser", "www-browser")) {
            val link = File(bin, alias).toPath()
            Fs.deleteTree(link)
            java.nio.file.Files.createSymbolicLink(link, java.nio.file.Paths.get("jangada-open"))
        }
        linkInbox.mkdirs()

        // Pasta de trabalho dos agentes. Fora da home de propósito: o Claude Code
        // não guarda a confiança na pasta home e perguntaria toda vez.
        File(rootfs, "root/$WORKSPACE").mkdirs()
        val phone = File(rootfs, "root/celular").toPath()
        if (hasStorageAccess && !java.nio.file.Files.isSymbolicLink(phone)) {
            Fs.deleteTree(phone)
            java.nio.file.Files.createSymbolicLink(phone, java.nio.file.Paths.get("/sdcard"))
        }

        // /tmp precisa do "sticky bit" (1777); sem ele o Claude Code desliga recursos.
        for (tmp in listOf(File(rootfs, "tmp"), File(rootfs, "var/tmp"))) {
            tmp.mkdirs()
            runCatching { android.system.Os.chmod(tmp.absolutePath, "1777".toInt(8)) }
        }
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
        /** Pasta (dentro da home do Debian) onde os agentes trabalham. */
        const val WORKSPACE = "projetos"
        private const val SHARED_STORAGE = "/storage/emulated/0"

        private const val PROFILE_SCRIPT =
            "# Gerado pelo Jangada a cada sessão.\n" +
                "# Pastas onde os instaladores dos agentes costumam colocar os programas.\n" +
                "for d in \"\$HOME/.npm-global/bin\" \"\$HOME/.cargo/bin\" \"\$HOME/.bun/bin\" " +
                "\"\$HOME/.opencode/bin\" \"\$HOME/.local/bin\"; do\n" +
                "  case \":\$PATH:\" in *\":\$d:\"*) ;; *) PATH=\"\$d:\$PATH\" ;; esac\n" +
                "done\n" +
                "unset d\n" +
                "export PATH\n" +
                "export BROWSER=/usr/local/bin/jangada-open\n"

        private const val OPEN_SCRIPT =
            "#!/bin/sh\n" +
                "# Abre um link no Android, pelo app Jangada. Gerado pelo Jangada.\n" +
                "url=\"\$1\"\n" +
                "if [ -z \"\$url\" ]; then echo \"uso: jangada-open URL\" >&2; exit 1; fi\n" +
                "dir=/var/lib/jangada/open\n" +
                "mkdir -p \"\$dir\"\n" +
                "tmp=\$(mktemp \"\$dir/.link.XXXXXX\") || exit 1\n" +
                "printf '%s' \"\$url\" > \"\$tmp\"\n" +
                "mv \"\$tmp\" \"\$dir/link-\$\$-\$(date +%s%N)\"\n"
    }
}

package io.github.jonjonesbr.jangada

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.jonjonesbr.jangada.chat.AutoCompact
import io.github.jonjonesbr.jangada.chat.autoCompactText
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Setup
import io.github.jonjonesbr.jangada.terminal.TerminalThemes
import io.github.jonjonesbr.jangada.ui.Sheet
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.concurrent.thread

/** Configurações: modo Simples/Pro, Enter envia e permissões do chat. */
class SettingsActivity : JangadaActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A escolha do backup sobrevive se o Android recriar a tela enquanto o seletor de arquivos está aberto.
        backupEverything = savedInstanceState?.getBoolean(STATE_BACKUP_EVERYTHING) ?: false
        setContentView(R.layout.activity_settings)
        val settings = AppSettings(this)

        findViewById<View>(R.id.settings_back).setOnClickListener { finish() }

        val mode = findViewById<RadioGroup>(R.id.settings_mode)
        mode.check(if (settings.simpleMode) R.id.mode_simple else R.id.mode_pro)
        mode.setOnCheckedChangeListener { _, id -> settings.simpleMode = id == R.id.mode_simple }

        val enter = findViewById<Switch>(R.id.settings_enter_sends)
        enter.isChecked = settings.enterSends
        enter.setOnCheckedChangeListener { _, checked -> settings.enterSends = checked }

        val permissions = findViewById<RadioGroup>(R.id.settings_permissions)
        permissions.check(if (settings.askPermissions) R.id.permissions_ask else R.id.permissions_auto)
        permissions.setOnCheckedChangeListener { _, id -> settings.askPermissions = id == R.id.permissions_ask }

        val models = mapOf(
            R.id.model_default to "", R.id.model_opus to "opus",
            R.id.model_sonnet to "sonnet", R.id.model_haiku to "haiku",
        )
        val model = findViewById<RadioGroup>(R.id.settings_model_group)
        model.check(models.entries.firstOrNull { it.value == settings.chatModel }?.key ?: R.id.model_default)
        model.setOnCheckedChangeListener { _, id -> settings.chatModel = models[id] ?: "" }

        val efforts = mapOf(
            R.id.effort_default to "", R.id.effort_low to "low", R.id.effort_medium to "medium",
            R.id.effort_high to "high", R.id.effort_xhigh to "xhigh", R.id.effort_max to "max",
        )
        val effort = findViewById<RadioGroup>(R.id.settings_effort_group)
        effort.check(efforts.entries.firstOrNull { it.value == settings.chatEffort }?.key ?: R.id.effort_default)
        effort.setOnCheckedChangeListener { _, id -> settings.chatEffort = efforts[id] ?: "" }

        // Agentes de chat ao mesmo tempo: Automático (pela RAM do aparelho) ou de 1 a AgentLimit.MAX.
        val maxAgents = findViewById<Button>(R.id.settings_max_agents)
        val autoAgents = AgentLimit.auto(totalMemBytes())
        fun showMaxAgents() {
            val value = if (settings.maxAgents in 1..AgentLimit.MAX) {
                settings.maxAgents.toString()
            } else {
                getString(R.string.settings_max_agents_auto, autoAgents)
            }
            maxAgents.text = getString(R.string.settings_max_agents, value)
        }
        showMaxAgents()
        maxAgents.setOnClickListener {
            val chosen = settings.maxAgents
            val rows = listOf(
                Sheet.Row(
                    title = getString(R.string.settings_max_agents_auto_device, autoAgents),
                    checked = chosen !in 1..AgentLimit.MAX,
                ) {
                    settings.maxAgents = 0
                    showMaxAgents()
                },
            ) + (1..AgentLimit.MAX).map { n ->
                Sheet.Row(title = n.toString(), checked = chosen == n) {
                    settings.maxAgents = n
                    showMaxAgents()
                }
            }
            Sheet(this).title(getString(R.string.settings_max_agents_title)).rows(rows).show()
        }

        // Dormir agentes parados: a escolha abre num painel, como a visualização do chat (ver MemoryWatch).
        val sleepButton = findViewById<Button>(R.id.settings_sleep_after)
        fun sleepLabel(minutes: Int): String = when {
            minutes == 0 -> getString(R.string.settings_sleep_after_never)
            minutes % 60 == 0 -> getString(R.string.settings_sleep_after_hours, minutes / 60)
            else -> getString(R.string.settings_sleep_after_minutes, minutes)
        }
        fun showSleepAfter() {
            sleepButton.text = sleepLabel(settings.sleepAfterMinutes)
        }
        showSleepAfter()
        sleepButton.setOnClickListener {
            Sheet(this)
                .title(getString(R.string.settings_sleep_after))
                .rows(
                    AppSettings.SLEEP_AFTER_MINUTES.map { minutes ->
                        Sheet.Row(title = sleepLabel(minutes), checked = minutes == settings.sleepAfterMinutes) {
                            settings.sleepAfterMinutes = minutes
                            showSleepAfter()
                        }
                    },
                )
                .show()
        }

        // Compactação automática da conversa (ajuste geral; cada trabalho pode ter o seu, ver AutoCompact).
        // Só vale quando o agente inicia de novo: não reinicia nada aqui.
        val autoButton = findViewById<Button>(R.id.settings_auto_compact)
        fun showAutoCompact() {
            val value = autoCompactText(this, settings.autoCompact)
            autoButton.text = value
            // O rótulo "Compactação automática" é o título da seção, acima do botão: o leitor precisa dele junto.
            autoButton.contentDescription = getString(R.string.settings_auto_compact_label, value)
        }
        showAutoCompact()
        autoButton.setOnClickListener {
            Sheet(this)
                .title(getString(R.string.auto_compact_title))
                .rows(
                    AutoCompact.GENERAL.map { value ->
                        Sheet.Row(title = autoCompactText(this, value), checked = value == settings.autoCompact) {
                            settings.autoCompact = value
                            showAutoCompact()
                        }
                    },
                )
                .show()
        }

        // Vibração ao fim da resposta (só com o chat aberto; ver ChatActivity).
        bindSwitch(R.id.settings_haptic, settings.hapticOnFinish) { settings.hapticOnFinish = it }

        // Orientação sobre a memória nas instruções globais dos agentes (ver AgentInstructions).
        // A integração é refeita já, em segundo plano, para o trecho entrar ou sair logo.
        val linuxForTips = LinuxEnv(this)
        bindSwitch(R.id.settings_agent_tips, settings.agentMemoryTips) {
            settings.agentMemoryTips = it
            thread(name = "agent-tips") { runCatching { linuxForTips.refreshIntegration() } }
        }

        // ---- Aparência ----
        val themes = mapOf(
            R.id.theme_system to AppSettings.THEME_SYSTEM, R.id.theme_light to AppSettings.THEME_LIGHT,
            R.id.theme_dark to AppSettings.THEME_DARK,
        )
        val appTheme = findViewById<RadioGroup>(R.id.settings_app_theme)
        appTheme.check(themes.entries.firstOrNull { it.value == settings.appTheme }?.key ?: R.id.theme_system)
        appTheme.setOnCheckedChangeListener { _, id ->
            val chosen = themes[id] ?: AppSettings.THEME_SYSTEM
            if (chosen == settings.appTheme) return@setOnCheckedChangeListener
            settings.appTheme = chosen
            JangadaActivity.themeGeneration++
            recreate()
        }
        val terminalTheme = findViewById<Button>(R.id.settings_terminal_theme)
        fun showTerminalTheme() {
            val nameRes = TerminalThemes.names[settings.terminalTheme] ?: R.string.theme_default
            terminalTheme.text = getString(R.string.terminal_theme_button, getString(nameRes))
        }
        showTerminalTheme()
        terminalTheme.setOnClickListener {
            val ids = TerminalThemes.names.keys.toList()
            val labels = TerminalThemes.names.values.map { getString(it) }.toTypedArray()
            Sheet(this)
                .title(getString(R.string.terminal_theme_title))
                .rows(
                    ids.mapIndexed { index, id ->
                        Sheet.Row(title = labels[index], checked = id == settings.terminalTheme) {
                            settings.terminalTheme = id
                            showTerminalTheme()
                            if (id == "solarized_light") Toast.makeText(this, R.string.terminal_theme_note, Toast.LENGTH_LONG).show()
                        }
                    },
                )
                .show()
        }

        // Tamanho do texto do chat: a mesma escolha em diálogo que as cores do terminal.
        val chatText = findViewById<Button>(R.id.settings_chat_text)
        val scaleOptions = AppSettings.CHAT_TEXT_SCALES.zip(CHAT_TEXT_NAMES)
        fun showChatText() {
            val nameRes = scaleOptions.first { it.first == settings.chatTextScale }.second
            chatText.text = getString(R.string.settings_chat_text, getString(nameRes))
        }
        showChatText()
        chatText.setOnClickListener {
            Sheet(this)
                .title(getString(R.string.settings_chat_text_title))
                .rows(
                    scaleOptions.map { (scale, nameRes) ->
                        Sheet.Row(title = getString(nameRes), checked = scale == settings.chatTextScale) {
                            settings.chatTextScale = scale
                            showChatText()
                        }
                    },
                )
                .show()
        }

        // Possível travamento: quanto tempo sem mensagem do agente para a faixa do chat avisar (ver AgentStatus).
        val stallButton = findViewById<Button>(R.id.settings_chat_stall)
        fun stallLabel(seconds: Int): String = getString(R.string.settings_sleep_after_minutes, seconds / 60)
        fun showStall() {
            stallButton.text = getString(R.string.settings_chat_stall, stallLabel(settings.chatStallSeconds))
        }
        showStall()
        stallButton.setOnClickListener {
            val options = AppSettings.CHAT_STALL_OPTIONS
            Sheet(this)
                .title(getString(R.string.settings_chat_stall_title))
                .rows(
                    options.map { seconds ->
                        Sheet.Row(title = stallLabel(seconds), checked = seconds == settings.chatStallSeconds) {
                            settings.chatStallSeconds = seconds
                            showStall()
                        }
                    },
                )
                .show()
        }

        // ---- Backup e Sobre ----
        findViewById<View>(R.id.settings_backup_export).setOnClickListener { chooseBackup() }
        findViewById<View>(R.id.settings_backup_import).setOnClickListener { pickBackup() }
        findViewById<View>(R.id.settings_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        // ---- Avançado (Pro) ----
        bindSwitch(R.id.settings_cpu_awake, settings.keepCpuAwake) { settings.keepCpuAwake = it }
        bindSwitch(R.id.settings_screen_on, settings.keepScreenOn) { settings.keepScreenOn = it }
        bindSwitch(R.id.settings_extra_keys, settings.showExtraKeys) { settings.showExtraKeys = it }
        findViewById<View>(R.id.settings_api_keys).setOnClickListener {
            startActivity(Intent(this, ApiKeysActivity::class.java))
        }
        bindSwitch(R.id.settings_auto_clean, settings.autoClean) { settings.autoClean = it }
        findViewById<View>(R.id.settings_cleanup).setOnClickListener { freeSpace() }
        findViewById<View>(R.id.settings_repair).setOnClickListener { repairLinux() }
        findViewById<View>(R.id.settings_reinstall).setOnClickListener { confirmReinstall() }
        showLinuxSize()
    }

    private fun bindSwitch(id: Int, value: Boolean, save: (Boolean) -> Unit) {
        val switch = findViewById<Switch>(id)
        switch.isChecked = value
        switch.setOnCheckedChangeListener { _, checked -> save(checked) }
    }

    /** Memória total do aparelho em bytes para o automático (0 se o Android não informar). */
    private fun totalMemBytes(): Long = runCatching {
        val info = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(0L)

    /** Último tamanho do Linux conhecido (null até a varredura terminar). Liberar espaço subtrai dele, sem nova varredura. */
    private var lastLinuxBytes: Long? = null

    /** Tamanho do Linux (calculado em segundo plano, sem seguir links). */
    private fun showLinuxSize() {
        val label = findViewById<TextView>(R.id.settings_linux_size)
        thread(name = "linux-size") {
            val bytes = linuxBytes()
            runOnUiThread {
                if (!isDestroyed) {
                    lastLinuxBytes = bytes
                    label.text = getString(R.string.settings_linux_size, (bytes / 1_048_576).toString())
                }
            }
        }
    }

    private fun linuxBytes(): Long = runCatching {
        var total = 0L
        Files.walkFileTree(LinuxEnv(this).linuxDir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                total += attrs.size()
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
        })
        total
    }.getOrDefault(0L)

    /** Libera espaço pela mesma limpeza da tela inicial (no serviço) e mostra quanto liberou. Sem varredura. */
    private fun freeSpace() {
        val progress = AlertDialog.Builder(this).setMessage(R.string.settings_cleaning).setCancelable(false).show()
        CacheCleaner(progress).start()
    }

    /**
     * Liga-se ao serviço e pede a limpeza. O bind dura até a limpeza acabar (senão o serviço
     * pode parar no meio). Se o serviço recusar (tarefa de agente ou outra limpeza), mostra
     * [R.string.storage_clean_busy]. Ao acabar, o que liberou vem do próprio script.
     */
    private inner class CacheCleaner(private val progress: AlertDialog) : ServiceConnection {
        private var bound = false
        private var finished = false

        fun start() {
            bound = runCatching {
                applicationContext.bindService(Intent(applicationContext, TerminalService::class.java), this, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) finish(busy = true)
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? TerminalService.LocalBinder)?.service
            val started = service?.cleanCaches { freedKb -> finish(busy = false, freedKb = freedKb) } == true
            if (!started) finish(busy = true)
        }

        override fun onServiceDisconnected(name: ComponentName?) = finish(busy = true)

        private fun finish(busy: Boolean, freedKb: Long = 0L) {
            if (finished) return
            finished = true
            if (bound) runCatching { applicationContext.unbindService(this) }
            bound = false
            runCatching { progress.dismiss() }
            if (isDestroyed) return
            if (busy) {
                Toast.makeText(this@SettingsActivity, R.string.storage_clean_busy, Toast.LENGTH_SHORT).show()
                return
            }
            val freed = freedKb.coerceAtLeast(0L) * 1024
            val message = if (freed < 1_048_576) getString(R.string.settings_nothing_to_clean)
            else getString(R.string.settings_cleaned, (freed / 1_048_576).toString())
            AlertDialog.Builder(this@SettingsActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            // O tamanho mostrado sai do último valor medido, menos o que o script liberou (sem nova varredura).
            lastLinuxBytes?.let { before ->
                val left = (before - freed).coerceAtLeast(0L)
                lastLinuxBytes = left
                findViewById<TextView>(R.id.settings_linux_size).text =
                    getString(R.string.settings_linux_size, (left / 1_048_576).toString())
            }
        }
    }

    // ---- Backup ----

    private var backupEverything = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_BACKUP_EVERYTHING, backupEverything)
    }

    private fun chooseBackup() {
        // Escolha entre só os projetos ou tudo: abre num painel, como as outras escolhas da tela.
        fun startExport(everything: Boolean) {
            backupEverything = everything
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/gzip")
                .putExtra(Intent.EXTRA_TITLE, "jangada-backup-$stamp.tar.gz")
            runCatching { startActivityForResult(intent, REQUEST_EXPORT) }
        }
        Sheet(this)
            .title(getString(R.string.backup_choose_title))
            .rows(
                listOf(
                    Sheet.Row(title = getString(R.string.backup_projects)) { startExport(false) },
                    Sheet.Row(title = getString(R.string.backup_everything)) { startExport(true) },
                ),
            )
            .show()
    }

    private fun pickBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        runCatching { startActivityForResult(intent, REQUEST_IMPORT) }
    }

    @Deprecated("Resposta do seletor de arquivos do Android.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_EXPORT -> runBackupTask(R.string.backup_working, {
                val bytes = Backup.export(this, uri, backupEverything)
                getString(R.string.backup_done, String.format(java.util.Locale.getDefault(), "%.1f", bytes / 1_048_576.0)) +
                    if (backupEverything) "\n\n" + getString(R.string.backup_logins_warning) else ""
            })
            REQUEST_IMPORT -> AlertDialog.Builder(this)
                .setTitle(R.string.backup_restore_title)
                .setMessage(R.string.backup_restore_message)
                .setPositiveButton(R.string.backup_import) { _, _ ->
                    val progress = AlertDialog.Builder(this).setMessage(R.string.backup_restoring).setCancelable(false).show()
                    ServiceCloser {
                        runCatching { progress.dismiss() }
                        runBackupTask(R.string.backup_restoring, {
                            Backup.restore(this, uri)
                            getString(R.string.backup_restored)
                        })
                    }.start()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * Roda backup/restauração em segundo plano com um aviso de progresso (se a tela ainda existir).
     * Enquanto roda, o serviço fica em primeiro plano ([ForegroundHold]): sair do app não mata o tar no meio.
     */
    private fun runBackupTask(workingText: Int, work: () -> String) {
        val progress = if (isDestroyed) null else AlertDialog.Builder(this).setMessage(workingText).setCancelable(false).show()
        ForegroundHold { release ->
            thread(name = "backup") {
                val message = runCatching(work).getOrElse { getString(R.string.backup_failed, it.message ?: it.javaClass.simpleName) }
                runOnUiThread {
                    // Solta o serviço mesmo com a tela destruída (runOnUiThread roda assim mesmo).
                    release()
                    progress?.let { runCatching { it.dismiss() } }
                    if (isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
                    showLinuxSize()
                }
            }
        }.start()
    }

    /**
     * Liga-se ao serviço (pelo contexto do app, para o bind sobreviver à tela) e o segura em primeiro plano
     * ([TerminalService.holdForeground]) antes de chamar [then], na thread principal. [then] recebe a função
     * que solta o serviço e desfaz o bind (uma vez só). Sem serviço, [then] roda assim mesmo, sem primeiro plano.
     */
    private inner class ForegroundHold(private val then: (release: () -> Unit) -> Unit) : ServiceConnection {
        private var bound = false
        private var started = false
        private var service: TerminalService? = null
        private var held = false
        private var released = false

        fun start() {
            bound = runCatching {
                applicationContext.bindService(Intent(applicationContext, TerminalService::class.java), this, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) begin()
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TerminalService.LocalBinder)?.service
            held = service?.holdForeground() == true
            begin()
        }

        // O processo do serviço caiu: a tarefa segue (ou já caiu junto); só não há o que soltar.
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            held = false
            begin()
        }

        private fun begin() {
            if (started) return
            started = true
            then(::release)
        }

        private fun release() {
            if (released) return
            released = true
            if (held) service?.releaseForeground()
            held = false
            if (bound) runCatching { applicationContext.unbindService(this) }
            bound = false
        }
    }

    /** Reinstala só as ferramentas básicas, pela tela de preparação. */
    private fun repairLinux() {
        LinuxEnv(this).clearBaseMarker()
        Setup.reset()
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    private fun confirmReinstall() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_reinstall_title)
            .setMessage(R.string.settings_reinstall_message)
            .setPositiveButton(R.string.settings_reinstall_confirm) { _, _ -> reinstallLinux() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Fecha tudo, apaga o Linux e volta para a tela de preparação. */
    private fun reinstallLinux() {
        val progress = AlertDialog.Builder(this).setMessage(R.string.settings_erasing).setCancelable(false).show()
        ServiceCloser {
            val linux = LinuxEnv(this)
            thread(name = "linux-erase") {
                linux.clearMarkers()
                runCatching { Fs.deleteTree(linux.linuxDir.toPath()) }
                runOnUiThread {
                    runCatching { progress.dismiss() }
                    Setup.reset()
                    if (!isDestroyed) {
                        startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }
                }
            }
        }.start()
    }

    /**
     * Liga-se ao serviço, manda encerrar tudo (sessões, chat, instalações) e só
     * então chama [then], na thread principal. O bind é desfeito uma vez só.
     * [then] roda mesmo se a tela já tiver sido destruída: quem usa a tela checa isDestroyed.
     */
    private inner class ServiceCloser(private val then: () -> Unit) : ServiceConnection {
        private var bound = false
        private var finished = false

        fun start() {
            // Pelo contexto do app: o bind sobrevive à tela e é desfeito pelo mesmo contexto em done().
            bound = runCatching {
                applicationContext.bindService(Intent(applicationContext, TerminalService::class.java), this, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) done()
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? TerminalService.LocalBinder)?.service
            if (service == null) done() else service.closeEverything { done() }
        }

        override fun onServiceDisconnected(name: ComponentName?) = done()

        private fun done() {
            if (finished) return
            finished = true
            if (bound) runCatching { applicationContext.unbindService(this) }
            bound = false
            then()
        }
    }

    private companion object {
        const val REQUEST_EXPORT = 21
        const val REQUEST_IMPORT = 22
        const val STATE_BACKUP_EVERYTHING = "backup_everything"
        /** Nome de cada escolha do tamanho do chat, na mesma ordem de [AppSettings.CHAT_TEXT_SCALES]. */
        val CHAT_TEXT_NAMES = listOf(
            R.string.settings_chat_text_small, R.string.settings_chat_text_normal, R.string.settings_chat_text_large,
            R.string.settings_chat_text_larger, R.string.settings_chat_text_huge,
        )
    }
}

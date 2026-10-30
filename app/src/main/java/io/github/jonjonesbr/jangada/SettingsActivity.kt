package io.github.jonjonesbr.jangada

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
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Setup
import io.github.jonjonesbr.jangada.terminal.TerminalThemes
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
            AlertDialog.Builder(this)
                .setTitle(R.string.terminal_theme_title)
                .setSingleChoiceItems(labels, ids.indexOf(settings.terminalTheme).coerceAtLeast(0)) { dialog, which ->
                    settings.terminalTheme = ids[which]
                    dialog.dismiss()
                    showTerminalTheme()
                    if (ids[which] == "solarized_light") Toast.makeText(this, R.string.terminal_theme_note, Toast.LENGTH_LONG).show()
                }
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

    /** Tamanho do Linux (calculado em segundo plano, sem seguir links). */
    private fun showLinuxSize() {
        val label = findViewById<TextView>(R.id.settings_linux_size)
        thread(name = "linux-size") {
            val bytes = linuxBytes()
            runOnUiThread {
                if (!isDestroyed) label.text = getString(R.string.settings_linux_size, (bytes / 1_048_576).toString())
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

    /** Apaga caches e versões antigas dentro do Linux e mostra quanto liberou. */
    private fun freeSpace() {
        val progress = AlertDialog.Builder(this).setMessage(R.string.settings_cleaning).setCancelable(false).show()
        val linux = LinuxEnv(this)
        thread(name = "linux-cleanup") {
            val before = linuxBytes()
            runCatching { linux.refreshIntegration() }
            runCatching { linux.runBash(LinuxEnv.CLEANUP_SCRIPT) { } }
            val freed = (before - linuxBytes()).coerceAtLeast(0)
            runOnUiThread {
                progress.dismiss()
                if (isDestroyed) return@runOnUiThread
                val message = if (freed < 1_048_576) getString(R.string.settings_nothing_to_clean)
                else getString(R.string.settings_cleaned, (freed / 1_048_576).toString())
                AlertDialog.Builder(this)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                showLinuxSize()
            }
        }
    }

    // ---- Backup ----

    private var backupEverything = false

    private fun chooseBackup() {
        val options = arrayOf(getString(R.string.backup_projects), getString(R.string.backup_everything))
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_choose_title)
            .setItems(options) { _, which ->
                backupEverything = which == 1
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/gzip")
                    .putExtra(Intent.EXTRA_TITLE, "jangada-backup-$stamp.tar.gz")
                runCatching { startActivityForResult(intent, REQUEST_EXPORT) }
            }
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

    /** Roda backup/restauração em segundo plano com um aviso de progresso (se a tela ainda existir). */
    private fun runBackupTask(workingText: Int, work: () -> String) {
        val progress = if (isDestroyed) null else AlertDialog.Builder(this).setMessage(workingText).setCancelable(false).show()
        thread(name = "backup") {
            val message = runCatching(work).getOrElse { getString(R.string.backup_failed, it.message ?: it.javaClass.simpleName) }
            runOnUiThread {
                progress?.let { runCatching { it.dismiss() } }
                if (isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
                showLinuxSize()
            }
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
    }
}

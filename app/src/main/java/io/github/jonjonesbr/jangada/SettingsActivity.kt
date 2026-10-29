package io.github.jonjonesbr.jangada

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import io.github.jonjonesbr.jangada.linux.Fs
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Setup
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.concurrent.thread
import android.widget.RadioGroup
import android.widget.Switch
import androidx.activity.ComponentActivity

/** Configurações: modo Simples/Pro, Enter envia e permissões do chat. */
class SettingsActivity : ComponentActivity() {

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
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.settings_cleaned, (freed / 1_048_576).toString()))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
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
        startService(Intent(this, TerminalService::class.java).setAction(TerminalService.ACTION_EXIT))
        val linux = LinuxEnv(this)
        thread(name = "linux-erase") {
            Thread.sleep(1500) // dá tempo de os processos do Linux terminarem
            linux.clearMarkers()
            runCatching { Fs.deleteTree(linux.linuxDir.toPath()) }
            runOnUiThread {
                progress.dismiss()
                Setup.reset()
                startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }
    }
}

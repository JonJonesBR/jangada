package io.github.jonjonesbr.jangada

import android.os.Bundle
import android.view.View
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
    }
}

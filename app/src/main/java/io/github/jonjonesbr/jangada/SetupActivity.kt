package io.github.jonjonesbr.jangada

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Setup

/**
 * Primeira tela de quem acabou de instalar o app: prepara o Linux com
 * um toque, mostra o progresso em linguagem simples e guarda os detalhes
 * técnicos atrás de um botão.
 */
class SetupActivity : JangadaActivity() {

    private lateinit var steps: Map<Setup.Step, TextView>
    private lateinit var progress: ProgressBar
    private lateinit var detail: TextView
    private lateinit var error: TextView
    private lateinit var action: Button
    private lateinit var toggleLog: TextView
    private lateinit var logPanel: LinearLayout
    private lateinit var logView: TextView
    private lateinit var env: LinuxEnv

    private val listener: (Setup.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        env = LinuxEnv(this)

        steps = mapOf(
            Setup.Step.DOWNLOAD to findViewById(R.id.step_download),
            Setup.Step.EXTRACT to findViewById(R.id.step_extract),
            Setup.Step.PACKAGES to findViewById(R.id.step_packages),
        )
        progress = findViewById(R.id.progress)
        detail = findViewById(R.id.detail)
        error = findViewById(R.id.error)
        action = findViewById(R.id.action)
        toggleLog = findViewById(R.id.toggle_log)
        logPanel = findViewById(R.id.log_panel)
        logView = findViewById(R.id.log)

        action.setOnClickListener { Setup.start(this) }
        toggleLog.setOnClickListener {
            val show = logPanel.visibility != View.VISIBLE
            logPanel.visibility = if (show) View.VISIBLE else View.GONE
            toggleLog.setText(if (show) R.string.setup_hide_log else R.string.setup_show_log)
            logView.text = Setup.logText()
        }
        findViewById<Button>(R.id.copy_log).setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Jangada setup", Setup.logText()))
            Toast.makeText(this, R.string.setup_copied, Toast.LENGTH_SHORT).show()
        }

        if (!env.isDeviceSupported) {
            error.setText(R.string.setup_unsupported)
            error.visibility = View.VISIBLE
            action.isEnabled = false
            action.alpha = 0.4f
        }
    }

    override fun onStart() {
        super.onStart()
        Setup.addListener(listener)
    }

    override fun onStop() {
        Setup.removeListener(listener)
        super.onStop()
    }

    private fun render(state: Setup.State) {
        if (state is Setup.State.Done) {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }

        val current: Setup.Step? = when (state) {
            is Setup.State.Running -> state.step
            is Setup.State.Failed -> state.step
            else -> if (env.isRootfsReady) Setup.Step.PACKAGES else null
        }
        for ((step, view) in steps) {
            val status = when {
                current == null -> Status.PENDING
                step.ordinal < current.ordinal -> Status.DONE
                step.ordinal > current.ordinal -> Status.PENDING
                state is Setup.State.Failed -> Status.FAILED
                state is Setup.State.Running -> Status.RUNNING
                else -> Status.PENDING
            }
            showStep(view, status)
        }

        when (state) {
            is Setup.State.Running -> {
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = state.progress == null
                state.progress?.let { progress.progress = (it * 1000).toInt() }
                detail.text = state.detail
                error.visibility = View.GONE
                action.visibility = View.GONE
            }
            is Setup.State.Failed -> {
                progress.visibility = View.INVISIBLE
                detail.text = ""
                error.setText(state.messageRes)
                error.visibility = View.VISIBLE
                action.setText(R.string.setup_retry)
                action.visibility = View.VISIBLE
            }
            else -> {
                progress.visibility = View.INVISIBLE
                action.setText(if (env.isRootfsReady) R.string.setup_retry else R.string.setup_start)
                action.visibility = View.VISIBLE
            }
        }
        if (logPanel.visibility == View.VISIBLE) logView.text = Setup.logText()
    }

    private enum class Status { PENDING, RUNNING, DONE, FAILED }

    private fun showStep(view: TextView, status: Status) {
        // O texto original do passo fica guardado na tag, sem o marcador.
        val label = (view.tag as? String) ?: view.text.toString().also { view.tag = it }
        val (mark, color) = when (status) {
            Status.PENDING -> "○" to R.color.setup_muted
            Status.RUNNING -> "●" to R.color.key_active
            Status.DONE -> "✓" to R.color.setup_done
            Status.FAILED -> "✕" to R.color.setup_error
        }
        view.text = "$mark $label"
        view.setTextColor(getColor(color))
    }
}

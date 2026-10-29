package io.github.jonjonesbr.jangada

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.github.jonjonesbr.jangada.agents.AgentCatalog
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import kotlin.concurrent.thread

/**
 * Tela inicial: o catálogo de agentes e as sessões em andamento.
 * Instalar, abrir e atualizar um agente é um toque; tudo roda numa
 * sessão do terminal, à vista, para quem quiser acompanhar.
 */
class HomeActivity : ComponentActivity() {

    private lateinit var linux: LinuxEnv
    private lateinit var recipes: List<AgentRecipe>
    private lateinit var agentsList: LinearLayout
    private lateinit var sessionsList: LinearLayout
    private lateinit var runningTitle: TextView

    /** null = ainda verificando; senão, os comandos encontrados no Debian. */
    private var installed: Set<String>? = null
    private var service: TerminalService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        linux = LinuxEnv(this)
        if (linux.isDeviceSupported && !linux.isReady) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_home)
        agentsList = findViewById(R.id.agents)
        sessionsList = findViewById(R.id.sessions)
        runningTitle = findViewById(R.id.running_title)
        recipes = AgentCatalog.load(this)

        findViewById<Button>(R.id.open_terminal).setOnClickListener { openTerminal() }
        if (!linux.isDeviceSupported) findViewById<View>(R.id.unsupported).visibility = View.VISIBLE
        renderAgents()
    }

    override fun onStart() {
        super.onStart()
        if (!::agentsList.isInitialized) return
        bindService(Intent(this, TerminalService::class.java), connection, Context.BIND_AUTO_CREATE)
        refreshInstalled()
    }

    override fun onStop() {
        service?.let {
            it.onSessionsChanged = null
            unbindService(connection)
        }
        service = null
        super.onStop()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as TerminalService.LocalBinder).service
            service = svc
            svc.onSessionsChanged = { renderSessions() }
            renderSessions()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** Pergunta ao Debian quais agentes já estão instalados (em segundo plano). */
    private fun refreshInstalled() {
        if (!linux.isReady) return
        installed = null
        renderAgents()
        thread(name = "jangada-detect") {
            val found = HashSet<String>()
            runCatching {
                linux.runBash(AgentCatalog.detectScript(recipes)) { line ->
                    if (line.startsWith(FOUND)) found += line.removePrefix(FOUND).trim()
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                installed = found
                renderAgents()
            }
        }
    }

    private fun renderAgents() {
        agentsList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (recipe in recipes) {
            val card = inflater.inflate(R.layout.item_agent, agentsList, false)
            card.findViewById<TextView>(R.id.name).text = recipe.name
            card.findViewById<TextView>(R.id.vendor).text = recipe.vendor
            card.findViewById<TextView>(R.id.description).text = recipe.description()
            recipe.note()?.let {
                card.findViewById<TextView>(R.id.note).apply { text = it; visibility = View.VISIBLE }
            }

            val status = card.findViewById<TextView>(R.id.status)
            val primary = card.findViewById<Button>(R.id.primary)
            val update = card.findViewById<Button>(R.id.update)
            val known = installed
            val isInstalled = known?.contains(recipe.command) == true
            status.setText(
                when {
                    known == null -> R.string.agent_checking
                    isInstalled -> R.string.agent_installed
                    else -> R.string.agent_not_installed
                },
            )
            status.setTextColor(getColor(if (isInstalled) R.color.setup_done else R.color.setup_muted))

            primary.setText(if (isInstalled) R.string.agent_open else R.string.agent_install)
            primary.isEnabled = known != null && linux.isDeviceSupported
            primary.alpha = if (primary.isEnabled) 1f else 0.5f
            primary.setOnClickListener {
                if (isInstalled) openTerminal(recipe.name, recipe.launchScript())
                else openTerminal(recipe.name, recipe.installScript(updating = false))
            }
            if (isInstalled) {
                update.visibility = View.VISIBLE
                update.setOnClickListener { openTerminal(recipe.name, recipe.installScript(updating = true)) }
            }

            val site = card.findViewById<TextView>(R.id.site)
            val homepage = recipe.homepage
            if (homepage == null) site.visibility = View.GONE
            else site.setOnClickListener { openUrl(homepage) }

            agentsList.addView(card)
        }
    }

    private fun renderSessions() {
        sessionsList.removeAllViews()
        val sessions = service?.sessions.orEmpty()
        runningTitle.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        for (session in sessions) {
            val row = inflater.inflate(R.layout.item_session, sessionsList, false)
            row.findViewById<TextView>(R.id.session_title).text = session.mSessionName ?: TerminalService.DEFAULT_TITLE
            row.setOnClickListener {
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, session.mHandle))
            }
            row.findViewById<Button>(R.id.session_close).setOnClickListener {
                service?.closeSession(session.mHandle)
            }
            sessionsList.addView(row)
        }
    }

    private fun openTerminal(title: String? = null, script: String? = null) {
        val intent = Intent(this, MainActivity::class.java)
        if (script != null) {
            intent.putExtra(MainActivity.EXTRA_TITLE, title)
            intent.putExtra(MainActivity.EXTRA_SCRIPT, script)
        } else {
            intent.putExtra(MainActivity.EXTRA_NEW_SHELL, true)
        }
        startActivity(intent)
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private companion object {
        const val FOUND = "JANGADA_FOUND "
    }
}

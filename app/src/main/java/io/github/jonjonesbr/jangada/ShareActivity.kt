package io.github.jonjonesbr.jangada

import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import io.github.jonjonesbr.jangada.agents.AgentCatalog
import io.github.jonjonesbr.jangada.agents.AgentRecipe
import io.github.jonjonesbr.jangada.linux.LinuxEnv

/**
 * Recebe o "Compartilhar" de outros apps e manda o texto e os arquivos para
 * o chat de um agente. Não tem tela própria (o tema é translúcido): escolhe
 * o agente quando há mais de um e abre o chat.
 */
class ShareActivity : ComponentActivity() {

    private companion object {
        /** Máximo de arquivos por compartilhamento; o excesso é descartado com aviso. */
        const val MAX_SHARED_ITEMS = 20
    }

    private var text: String? = null
    private var uris: List<Uri> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readShared()
        if (text == null && uris.isEmpty()) {
            Toast.makeText(this, R.string.share_nothing_usable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!LinuxEnv(this).isReady) {
            Toast.makeText(this, R.string.share_linux_missing, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
            return
        }
        val agents = chatAgents()
        when {
            agents.isEmpty() -> {
                Toast.makeText(this, R.string.share_no_agents, Toast.LENGTH_LONG).show()
                finish()
            }
            agents.size == 1 -> openChat(agents[0])
            else -> chooseAgent(agents)
        }
    }

    /**
     * Lê o texto e os arquivos do Intent (ACTION_SEND ou ACTION_SEND_MULTIPLE).
     * Só aceita content:// (file:// apontaria para arquivos privados do Jangada).
     */
    @Suppress("DEPRECATION")
    private fun readShared() {
        text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        val received: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.filterNotNull().orEmpty()
            else -> emptyList()
        }
        val accepted = received.filter { it.scheme == ContentResolver.SCHEME_CONTENT }
        uris = accepted.take(MAX_SHARED_ITEMS)
        if (accepted.size > MAX_SHARED_ITEMS) {
            Toast.makeText(this, getString(R.string.share_too_many, MAX_SHARED_ITEMS), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Agentes com chat. Se a lista de instalados já foi gravada (pela tela
     * inicial), só entram os instalados.
     */
    private fun chatAgents(): List<AgentRecipe> {
        val agents = AgentCatalog.load(this).filter { it.hasChat }
        // Mesma lista que a HomeActivity grava (AgentPrefs).
        val installed = AgentPrefs.installedCommands(this)
        return if (installed.isNullOrEmpty()) agents else agents.filter { it.command in installed }
    }

    /** Pergunta para qual agente mandar; cancelar fecha sem enviar. */
    private fun chooseAgent(agents: List<AgentRecipe>) {
        AlertDialog.Builder(this, dialogTheme())
            .setTitle(R.string.share_choose_agent)
            .setItems(agents.map { it.name }.toTypedArray()) { _, which -> openChat(agents[which]) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** Abre o chat do agente já com o conteúdo recebido, e sai desta tela. */
    private fun openChat(agent: AgentRecipe) {
        val chat = Intent(this, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_AGENT_ID, agent.id)
            .putParcelableArrayListExtra(ChatActivity.EXTRA_SHARED_URIS, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        text?.let { chat.putExtra(ChatActivity.EXTRA_SHARED_TEXT, it) }
        // A permissão de leitura só passa para o chat por meio do ClipData.
        uris.firstOrNull()?.let { first ->
            chat.clipData = ClipData.newRawUri("", first).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }
        startActivity(chat)
        finish()
    }

    /** Tema do diálogo: o mesmo critério das telas do app (claro, escuro ou o do sistema). */
    private fun dialogTheme(): Int {
        val dark = when (AppSettings(this).appTheme) {
            AppSettings.THEME_LIGHT -> false
            AppSettings.THEME_DARK -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        return if (dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
    }
}

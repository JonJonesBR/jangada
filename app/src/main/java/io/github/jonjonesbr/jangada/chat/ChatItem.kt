package io.github.jonjonesbr.jangada.chat

import org.json.JSONObject

/** Um item da conversa mostrada no chat (igual para todos os agentes). */
sealed class ChatItem {
    class User(val text: String, val attachments: List<String> = emptyList()) : ChatItem()
    class Assistant(var text: String, var streaming: Boolean) : ChatItem()
    class Tool(val id: String, var name: String, var summary: String) : ChatItem() {
        var done = false
        var failed = false
    }

    /** Pedido de permissão; [chosen] guarda a opção escolhida (null = esperando). */
    class Permission(
        val requestId: Any,
        val title: String,
        val summary: String,
        val options: List<PermissionOption>,
        val input: JSONObject = JSONObject(),
        val toolName: String = "",
    ) : ChatItem() {
        var chosen: PermissionOption? = null
    }

    /** Plano de trabalho do agente (lista de tarefas que ele vai atualizando). */
    class Plan(var entries: List<PlanEntry>) : ChatItem()
    class Info(val text: String, val isError: Boolean = false) : ChatItem()
}

class PermissionOption(val id: String, val label: String, val kind: Kind) {
    enum class Kind { ALLOW_ONCE, ALLOW_ALWAYS, REJECT_ONCE, REJECT_ALWAYS }
    val allows get() = kind == Kind.ALLOW_ONCE || kind == Kind.ALLOW_ALWAYS
}

class PlanEntry(val text: String, val status: String)

/** Arquivo anexado a uma mensagem. Imagens vão como imagem; o resto, como caminho no Linux. */
class Attachment(
    val name: String,
    /** Caminho dentro do Debian (ex.: /root/projetos/anexos/foto.jpg). */
    val guestPath: String,
    val mimeType: String,
    /** Imagem já reduzida, em base64, quando é foto/print. */
    val imageBase64: String? = null,
)

/**
 * O que a tela de chat precisa de qualquer agente: hoje há duas
 * implementações, [ChatEngine] (Claude Code) e [AcpChat] (os agentes
 * que falam o Agent Client Protocol).
 */
interface AgentChat {
    val items: MutableList<ChatItem>
    val sessionId: String?
    /** Nome curto do modelo, quando o agente informa. */
    val model: String?
    val busy: Boolean
    val running: Boolean
    val interrupted: Boolean
    /** O agente lista as conversas anteriores. */
    val supportsHistory: Boolean
    var onChanged: (() -> Unit)?

    fun sendMessage(text: String, attachments: List<Attachment> = emptyList())
    fun interrupt()
    fun answer(item: ChatItem.Permission, option: PermissionOption)
    fun stop(then: (() -> Unit)? = null)
}

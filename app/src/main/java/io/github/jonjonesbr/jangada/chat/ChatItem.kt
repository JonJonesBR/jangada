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

    /**
     * Pedido de permissão; [chosen] guarda a opção escolhida (null = esperando).
     * [summary] é o comando ou caminho; [detail] é a descrição extra que o agente mandou (opcional).
     * [diff] é o trecho que a ação altera num arquivo (ver [DiffText]), quando há.
     */
    class Permission(
        val requestId: Any,
        val title: String,
        val summary: String,
        val options: List<PermissionOption>,
        val input: JSONObject = JSONObject(),
        val toolName: String = "",
        val detail: String = "",
        val diff: String? = null,
    ) : ChatItem() {
        var chosen: PermissionOption? = null
    }

    /**
     * Cartão de login: o agente precisa que o usuário entre na conta.
     * Cada opção é um jeito de entrar (pelo navegador, ou pela tela do agente).
     */
    class Login(val options: List<AuthOption>) : ChatItem() {
        enum class State { WAITING, WORKING, DONE, FAILED }
        var state = State.WAITING
        var message: String? = null
    }

    /** Plano de trabalho do agente (lista de tarefas que ele vai atualizando). */
    class Plan(var entries: List<PlanEntry>) : ChatItem()
    /** [details]: texto técnico (ex.: saída de erro do agente), mostrado sob o link "Detalhes". */
    class Info(val text: String, val isError: Boolean = false, val details: String? = null) : ChatItem()
}

/** Quando o chat pede a atenção do usuário (o serviço decide se mostra um aviso). */
enum class ChatAttention { PERMISSION, LOGIN, FINISHED, FAILED }

class PermissionOption(val id: String, val label: String, val kind: Kind) {
    enum class Kind { ALLOW_ONCE, ALLOW_ALWAYS, REJECT_ONCE, REJECT_ALWAYS }
    val allows get() = kind == Kind.ALLOW_ONCE || kind == Kind.ALLOW_ALWAYS
}

class PlanEntry(val text: String, val status: String)

/** Um jeito de entrar na conta do agente. */
class AuthOption(
    val id: String,
    val name: String,
    val description: String,
    val kind: Kind,
    /** Para TERMINAL: argumentos extras e variáveis para rodar o agente no modo de login. */
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
) {
    enum class Kind {
        /** O próprio agente faz o login (abre o navegador) a pedido do app. */
        AGENT,
        /** O app abre o agente num terminal, no modo de login dele. */
        TERMINAL,
        /** Sem instruções do agente: abre a tela normal dele, onde o login acontece. */
        AGENT_SCREEN,
    }
}

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
    /**
     * Avisos para o usuário (chamado na thread principal): pedido de permissão,
     * login, fim de um turno ou falha.
     */
    var onAttention: ((ChatAttention) -> Unit)?

    fun sendMessage(text: String, attachments: List<Attachment> = emptyList())
    fun interrupt()
    fun answer(item: ChatItem.Permission, option: PermissionOption)
    fun stop(then: (() -> Unit)? = null)
}

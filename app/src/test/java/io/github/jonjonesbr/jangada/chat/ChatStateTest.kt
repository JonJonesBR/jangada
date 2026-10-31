package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStateTest {

    /** Chat falso: só os campos que [chatState] lê. */
    private class FakeChat(
        override val items: MutableList<ChatItem> = mutableListOf(),
        override val busy: Boolean = false,
        override val running: Boolean = false,
    ) : AgentChat {
        override val cwd = "/root/projetos"
        override val sessionId: String? = null
        override val model: String? = null
        override val interrupted = false
        override val contextUsage: ContextUsage? = null
        override val supportsHistory = false
        override var onChanged: (() -> Unit)? = null
        override var onAttention: ((ChatAttention) -> Unit)? = null

        override fun sendMessage(text: String, attachments: List<Attachment>) {}
        override fun interrupt() {}
        override fun answer(item: ChatItem.Permission, option: PermissionOption) {}
        override fun stop(then: (() -> Unit)?) {}
    }

    private fun permissaoPendente() = ChatItem.Permission(1, "Executar", "ls", emptyList())

    private fun permissaoRespondida() = permissaoPendente().apply {
        chosen = PermissionOption("allow", "", PermissionOption.Kind.ALLOW_ONCE)
    }

    @Test
    fun permissaoSemRespostaEsperaVoce() {
        val chat = FakeChat(mutableListOf(permissaoPendente()), busy = true, running = true)

        assertEquals(ChatState.WAITING, chatState(chat))
    }

    @Test
    fun loginEmEsperaEsperaVoce() {
        val chat = FakeChat(mutableListOf(ChatItem.Login(emptyList())), running = true)

        assertEquals(ChatState.WAITING, chatState(chat))
    }

    @Test
    fun permissaoRespondidaNaoEspera() {
        val chat = FakeChat(mutableListOf(permissaoRespondida()), busy = true, running = true)

        assertEquals(ChatState.WORKING, chatState(chat))
    }

    @Test
    fun loginConcluidoNaoEspera() {
        val login = ChatItem.Login(emptyList()).apply { state = ChatItem.Login.State.DONE }
        val chat = FakeChat(mutableListOf(login), running = true)

        assertEquals(ChatState.READY, chatState(chat))
    }

    @Test
    fun turnoEmAndamentoTrabalha() {
        val chat = FakeChat(running = true, busy = true)

        assertEquals(ChatState.WORKING, chatState(chat))
    }

    @Test
    fun agenteRodandoSemTurnoEstaPronto() {
        val chat = FakeChat(running = true)

        assertEquals(ChatState.READY, chatState(chat))
    }

    @Test
    fun agenteParadoEstaParado() {
        val chat = FakeChat()

        assertEquals(ChatState.STOPPED, chatState(chat))
    }

    @Test
    fun agenteParadoComLoginAntigoEstaParado() {
        // O agente saiu (login pendente): a vaga está livre, a linha mostra Parado.
        val chat = FakeChat(mutableListOf(ChatItem.Login(emptyList())))

        assertEquals(ChatState.STOPPED, chatState(chat))
    }
}

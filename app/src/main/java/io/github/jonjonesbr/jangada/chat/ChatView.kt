package io.github.jonjonesbr.jangada.chat

import io.github.jonjonesbr.jangada.AppSettings

/**
 * Modos de visualização do chat (ajuste "Visualização"). Só escolhe o que a
 * tela mostra: a conversa guardada nunca muda.
 */
object ChatView {

    /**
     * O que a tela mostra no [mode]. No Resumo, as ações seguidas viram um
     * único [ChatItem.ToolGroup] (inclusive quando é uma só).
     */
    fun arrange(items: List<ChatItem>, mode: String): List<ChatItem> = when (mode) {
        AppSettings.VIEW_DETAILED -> items
        AppSettings.VIEW_SUMMARY -> summarize(items)
        // Normal, e qualquer valor desconhecido guardado nas preferências.
        else -> items.filter { it !is ChatItem.Thinking }
    }

    private fun summarize(items: List<ChatItem>): List<ChatItem> {
        val result = mutableListOf<ChatItem>()
        val group = mutableListOf<ChatItem.Tool>()
        // Mensagem, erro, plano, login ou pedido pendente quebram o grupo de ações.
        fun closeGroup() {
            if (group.isNotEmpty()) {
                result += ChatItem.ToolGroup(group.toList())
                group.clear()
            }
        }
        for (item in items) {
            when (item) {
                // Raciocínio some sem quebrar o grupo: ações separadas só por ele continuam juntas.
                is ChatItem.Thinking -> Unit
                is ChatItem.Permission -> {
                    // Pedido já respondido some; o pendente continua visível (quebra o grupo).
                    if (item.chosen == null) {
                        closeGroup()
                        result += item
                    }
                }
                is ChatItem.Tool -> {
                    group += item
                }
                else -> {
                    closeGroup()
                    result += item
                }
            }
        }
        closeGroup()
        return result
    }
}

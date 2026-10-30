package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryTest {

    private val transcript = listOf(
        """{"type":"summary","summary":"Resumo"}""",
        """{"type":"user","message":{"role":"user","content":"Oi"}}""",
        """{"type":"user","isMeta":true,"message":{"role":"user","content":"interno"}}""",
        """{"type":"user","message":{"role":"user","content":"<command-name>/clear</command-name>"}}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"Olá"},""" +
            """{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls -la"}}]}}""",
        """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}""",
        """{"type":"assistant","isSidechain":true,"message":{"content":[{"type":"text","text":"subagente"}]}}""",
        "isto não é JSON",
        """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Valeu"}]}}""",
    )

    @Test
    fun leMensagensRespostasEAcoes() {
        val items = ChatHistory.parse(transcript.asSequence(), max = 100)

        assertEquals(4, items.size)
        assertEquals("Oi", (items[0] as ChatItem.User).text)
        assertEquals("Olá", (items[1] as ChatItem.Assistant).text)
        val tool = items[2] as ChatItem.Tool
        assertEquals("Bash", tool.name)
        assertEquals("ls -la", tool.summary)
        assertTrue(tool.done)
        assertEquals("Valeu", (items[3] as ChatItem.User).text)
    }

    @Test
    fun guardaSoAsUltimas() {
        val items = ChatHistory.parse(transcript.asSequence(), max = 2)

        assertEquals(2, items.size)
        assertTrue(items[0] is ChatItem.Tool)
        assertEquals("Valeu", (items[1] as ChatItem.User).text)
    }

    @Test
    fun mantemMensagemComTagMasDescarteMarcadorInterno() {
        val lines = sequenceOf(
            """{"type":"user","message":{"role":"user","content":"<div>oi</div>"}}""",
            """{"type":"user","message":{"role":"user","content":"<local-command-stdout>x</local-command-stdout>"}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"<bash-stdout>ok</bash-stdout>"}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"<p>sim</p>"}]}}""",
        )

        val items = ChatHistory.parse(lines, max = 10)

        assertEquals(2, items.size)
        assertEquals("<div>oi</div>", (items[0] as ChatItem.User).text)
        assertEquals("<p>sim</p>", (items[1] as ChatItem.User).text)
    }

    @Test
    fun acaoComErroAparecePorFalha() {
        val lines = sequenceOf(
            """{"type":"user","message":{"role":"user","content":"Roda dois comandos"}}""",
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"e1","name":"Bash","input":{"command":"falha"}},""" +
                """{"type":"tool_use","id":"e2","name":"Bash","input":{"command":"ok"}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"e1","is_error":true,""" +
                """"content":"Exit code 1"},{"type":"tool_result","tool_use_id":"e2","content":"ok"}]}}""",
        )

        val items = ChatHistory.parse(lines, max = 100)

        assertEquals(3, items.size)
        val failed = items[1] as ChatItem.Tool
        val done = items[2] as ChatItem.Tool
        assertTrue(failed.done)
        assertTrue(failed.failed)
        assertTrue(done.done)
        assertFalse(done.failed)
    }

    @Test
    fun listaDeTarefasViraUmSoPlano() {
        val lines = sequenceOf(
            """{"type":"user","message":{"role":"user","content":"Faz duas coisas"}}""",
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"w1","name":"TodoWrite","input":""" +
                """{"todos":[{"content":"Ler","status":"pending","activeForm":"Lendo"},""" +
                """{"content":"Escrever","status":"pending","activeForm":"Escrevendo"}]}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"w1","content":"ok"}]}}""",
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"w2","name":"TodoWrite","input":""" +
                """{"todos":[{"content":"Ler","status":"completed","activeForm":"Lendo"},""" +
                """{"content":"Escrever","status":"in_progress","activeForm":"Escrevendo"}]}}]}}""",
        )

        val items = ChatHistory.parse(lines, max = 100)

        assertEquals(2, items.size)
        assertTrue(items[1] is ChatItem.Plan)
        val entries = (items[1] as ChatItem.Plan).entries
        assertEquals(listOf("Ler", "Escrever"), entries.map { it.text })
        assertEquals(listOf("completed", "in_progress"), entries.map { it.status })
    }

    @Test
    fun nomeAmigavelDoModelo() {
        assertEquals("Haiku 4.5", friendlyModel("claude-haiku-4-5-20251001"))
        assertEquals("Sonnet 4", friendlyModel("claude-sonnet-4-20250514"))
        assertEquals("Opus 4.1", friendlyModel("claude-opus-4-1[1m]"))
        assertNull(friendlyModel(""))
        assertNull(friendlyModel(null))
    }

    @Test
    fun resumoDaFerramenta() {
        val input = org.json.JSONObject().put("file_path", "/root/a.txt").put("content", "x")
        assertEquals("/root/a.txt", ToolText.summary("Write", input))
        assertEquals("", ToolText.summary("Write", null))
        assertEquals(null, ToolText.labelRes("FerramentaNova"))
    }
}

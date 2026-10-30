package io.github.jonjonesbr.jangada.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputTest {

    @Test
    fun tiraCoresEBarraDeProgresso() {
        assertEquals("ok\n100%", ToolOutput.clip("\u001B[32mok\u001B[0m\n10%\r50%\r100%\r\n"))
    }

    @Test
    fun tiraLembretesInternosDoClaude() {
        assertEquals("conteúdo", ToolOutput.clip("conteúdo\n\n<system-reminder>\ninterno\n</system-reminder>\n"))
    }

    @Test
    fun vazioViraNull() {
        assertNull(ToolOutput.clip("  \n\n"))
        assertNull(ToolOutput.fromClaude(null))
    }

    @Test
    fun saidaLongaGuardaComecoEFim() {
        val text = (1..200).joinToString("\n") { "linha $it" }
        val lines = ToolOutput.clip(text)!!.lines()
        assertEquals(ToolOutput.HEAD_LINES + 1 + ToolOutput.TAIL_LINES, lines.size)
        assertEquals("linha 1", lines.first())
        assertEquals("⋯", lines[ToolOutput.HEAD_LINES])
        assertEquals("linha 200", lines.last())
    }

    @Test
    fun linhaGiganteECortada() {
        val clipped = ToolOutput.clip("x".repeat(10_000))!!
        assertEquals(ToolOutput.MAX_LINE + 1, clipped.length)
        assertTrue(clipped.endsWith("…"))
    }

    @Test
    fun claudeJuntaOsBlocosDeTexto() {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "um"))
            .put(JSONObject().put("type", "image").put("source", JSONObject()))
            .put(JSONObject().put("type", "text").put("text", "dois"))
        assertEquals("um\ndois", ToolOutput.fromClaude(content))
        assertEquals("só texto", ToolOutput.fromClaude("só texto"))
    }

    @Test
    fun acpUsaOTextoDoContentEIgnoraDiff() {
        val call = JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "diff").put("path", "/a").put("newText", "x"))
            .put(JSONObject().put("type", "content").put("content", JSONObject().put("type", "text").put("text", "feito"))))
        assertEquals("feito", ToolOutput.fromAcp(call))
    }

    @Test
    fun acpSemContentUsaORawOutput() {
        assertEquals("saída", ToolOutput.fromAcp(JSONObject().put("rawOutput", JSONObject().put("stdout", "saída"))))
        assertEquals("texto", ToolOutput.fromAcp(JSONObject().put("rawOutput", "texto")))
        assertNull(ToolOutput.fromAcp(JSONObject().put("rawOutput", JSONObject().put("exit_code", 0))))
    }
}

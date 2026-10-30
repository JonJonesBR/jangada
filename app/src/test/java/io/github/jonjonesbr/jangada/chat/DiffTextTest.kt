package io.github.jonjonesbr.jangada.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiffTextTest {

    @Test
    fun editSimplesMostraSoALinhaTrocada() {
        val input = JSONObject().put("old_string", "a\nb\nc").put("new_string", "a\nB\nc")
        assertEquals("- b\n+ B", DiffText.fromClaude("Edit", input))
    }

    @Test
    fun multiEditJuntaAsEdicoes() {
        val edits = JSONArray()
            .put(JSONObject().put("old_string", "um").put("new_string", "dois"))
            .put(JSONObject().put("old_string", "").put("new_string", "novo"))
        val input = JSONObject().put("edits", edits)
        assertEquals("- um\n+ dois\n+ novo", DiffText.fromClaude("MultiEdit", input))
    }

    @Test
    fun writeMostraSoLinhasNovas() {
        val input = JSONObject().put("content", "x\ny\n")
        assertEquals("+ x\n+ y", DiffText.fromClaude("Write", input))
    }

    @Test
    fun corteEmQuarentaLinhasComResto() {
        val content = (1..100).joinToString("\n") { "l$it" }
        val diff = DiffText.fromClaude("Write", JSONObject().put("content", content))!!
        val lines = diff.lines()
        assertEquals(DiffText.MAX_LINES, lines.size)
        assertEquals("+ l1", lines.first())
        assertEquals("+ l39", lines[38])
        assertEquals("… (+61)", lines.last())
    }

    @Test
    fun acpComOldTextNullEArquivoNovo() {
        val content = JSONArray().put(
            JSONObject().put("type", "diff").put("path", "a.txt").put("newText", "oi\ntchau"),
        )
        assertEquals("+ oi\n+ tchau", DiffText.fromAcpContent(content))
    }

    @Test
    fun acpComOldTextTrocaSoOTrecho() {
        val content = JSONArray().put(
            JSONObject().put("type", "diff").put("path", "a.txt")
                .put("oldText", "1\n2\n3").put("newText", "1\n4\n3"),
        )
        assertEquals("- 2\n+ 4", DiffText.fromAcpContent(content))
    }

    @Test
    fun semDiffDevolveNull() {
        assertNull(DiffText.fromClaude("Bash", JSONObject().put("command", "ls")))
        assertNull(DiffText.fromClaude("Edit", JSONObject().put("old_string", "igual").put("new_string", "igual")))
        assertNull(DiffText.fromClaude("Write", JSONObject().put("content", "")))
        assertNull(DiffText.fromAcpContent(null))
        assertNull(DiffText.fromAcpContent(JSONArray().put(JSONObject().put("type", "content"))))
        assertNull(DiffText.fromAcpContent(JSONArray()))
    }
}

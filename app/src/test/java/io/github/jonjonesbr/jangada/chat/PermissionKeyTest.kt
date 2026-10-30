package io.github.jonjonesbr.jangada.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Sempre permitir": shell vale só para o mesmo comando; as demais, pelo nome. */
class PermissionKeyTest {

    private fun bash(command: String) = JSONObject().put("command", command)

    @Test
    fun shellUsaOComandoExato() {
        val ls = ChatEngine.permissionKey("Bash", bash("ls -la"))
        assertEquals(ls, ChatEngine.permissionKey("Bash", bash("ls -la")))
        assertNotEquals(ls, ChatEngine.permissionKey("Bash", bash("rm -rf ~")))
        assertNotEquals("Bash", ls)
        assertTrue(ChatEngine.isShellTool("BashOutput"))
        assertTrue(ChatEngine.isShellTool("KillShell"))
    }

    @Test
    fun demaisFerramentasUsamONome() {
        assertEquals("Edit", ChatEngine.permissionKey("Edit", JSONObject().put("file_path", "/a")))
        assertEquals("Edit", ChatEngine.permissionKey("Edit", JSONObject().put("file_path", "/b")))
        assertEquals("WebFetch", ChatEngine.permissionKey("WebFetch", JSONObject()))
    }
}

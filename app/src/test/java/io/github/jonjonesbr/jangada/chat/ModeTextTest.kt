package io.github.jonjonesbr.jangada.chat

import io.github.jonjonesbr.jangada.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeTextTest {

    @Test
    fun idsSaoNormalizados() {
        // Maiúsculas, hífen e sublinhado não mudam o modo: ids do ACP e do Claude Code.
        assertEquals(R.string.mode_accept_edits, ModeText.labelRes("acceptEdits"))
        assertEquals(R.string.mode_accept_edits, ModeText.labelRes("accept_edits"))
        assertEquals(R.string.mode_read_only, ModeText.labelRes("Read-Only"))
        assertEquals(R.string.mode_plan, ModeText.labelRes("plan"))
    }

    @Test
    fun descricaoVemDoMesmoModo() {
        assertEquals(R.string.mode_default_desc, ModeText.descriptionRes("ask"))
        assertEquals(R.string.mode_plan_desc, ModeText.descriptionRes("PLAN"))
    }

    @Test
    fun idDesconhecidoNaoTemTexto() {
        assertNull(ModeText.labelRes("modo-novo"))
        assertNull(ModeText.descriptionRes("modo-novo"))
        assertFalse(ModeText.isRisky("modo-novo"))
    }

    @Test
    fun modoQueAprovaTudoEhDeRisco() {
        assertEquals(R.string.mode_full, ModeText.labelRes("bypassPermissions"))
        assertTrue(ModeText.isRisky("bypassPermissions"))
        assertTrue(ModeText.isRisky("yolo"))
        assertTrue(ModeText.isRisky("Full-Access"))
        assertFalse(ModeText.isRisky("acceptEdits"))
        assertFalse(ModeText.isRisky("plan"))
    }
}

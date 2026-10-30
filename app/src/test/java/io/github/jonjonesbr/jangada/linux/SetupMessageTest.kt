package io.github.jonjonesbr.jangada.linux

import io.github.jonjonesbr.jangada.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class SetupMessageTest {

    @Test
    fun enospcNoDownloadViraFaltaDeEspaco() {
        val e = IOException("write failed: ENOSPC (No space left on device)")
        assertEquals(R.string.setup_error_space, friendlyMessage(e, Setup.Step.DOWNLOAD))
    }

    @Test
    fun noSpaceNaExtracaoViraFaltaDeEspaco() {
        val e = IOException("No space left on device")
        assertEquals(R.string.setup_error_space, friendlyMessage(e, Setup.Step.EXTRACT))
    }

    @Test
    fun causaComEspacoAcabadoViraFaltaDeEspaco() {
        val e = RuntimeException("falha ao extrair", IOException("No space left on device"))
        assertEquals(R.string.setup_error_space, friendlyMessage(e, Setup.Step.EXTRACT))
    }

    @Test
    fun erroDeDownloadGenericoContinuaSendoDownload() {
        val e = IOException("connection reset")
        assertEquals(R.string.setup_error_download, friendlyMessage(e, Setup.Step.DOWNLOAD))
    }

    @Test
    fun erroGenericoNaoEhFaltaDeEspaco() {
        val e = IllegalStateException("bug qualquer")
        assertEquals(R.string.setup_error_generic, friendlyMessage(e, Setup.Step.EXTRACT))
    }
}

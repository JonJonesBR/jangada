package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextUsageTest {

    @Test
    fun percentualArredondado() {
        assertEquals(50, ContextUsage(100_000, 200_000).percent)
        assertEquals(33, ContextUsage(1, 3).percent)
        assertEquals(67, ContextUsage(2, 3).percent)
    }

    @Test
    fun semTamanhoConhecidoDaZero() {
        assertEquals(0, ContextUsage(500, 0).percent)
    }

    @Test
    fun acimaDaJanelaFicaEm100() {
        assertEquals(100, ContextUsage(300, 200).percent)
    }
}

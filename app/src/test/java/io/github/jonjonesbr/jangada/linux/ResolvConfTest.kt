package io.github.jonjonesbr.jangada.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

/** Só literais IP: InetAddress.getByName não consulta a rede. */
class ResolvConfTest {

    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

    /** Só as linhas "nameserver X", na ordem em que aparecem. */
    private fun nameservers(content: String): List<String> =
        content.lines().filter { it.startsWith("nameserver ") }.map { it.removePrefix("nameserver ") }

    @Test
    fun servidoresDoCelularVemPrimeiro() {
        val content = LinuxEnv.resolvConf(listOf(ip("192.168.0.1"), ip("10.0.0.2")))
        assertEquals(listOf("192.168.0.1", "10.0.0.2", "8.8.8.8"), nameservers(content))
    }

    @Test
    fun linkLocalELoopbackSaoIgnorados() {
        val content = LinuxEnv.resolvConf(
            listOf(ip("169.254.1.1"), ip("127.0.0.1"), ip("fe80::1"), ip("192.168.1.9")),
        )
        assertEquals(listOf("192.168.1.9", "8.8.8.8", "1.1.1.1"), nameservers(content))
    }

    @Test
    fun nuncaMaisDeTresNameservers() {
        val content = LinuxEnv.resolvConf(listOf(ip("10.0.0.1"), ip("10.0.0.2"), ip("10.0.0.3"), ip("10.0.0.4")))
        val servers = nameservers(content)
        assertEquals(3, servers.size)
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), servers.take(2))
    }

    @Test
    fun listaVaziaUsaDnsPublicos() {
        assertEquals(listOf("8.8.8.8", "1.1.1.1"), nameservers(LinuxEnv.resolvConf(emptyList())))
    }

    @Test
    fun semRepeticao() {
        val servers = nameservers(LinuxEnv.resolvConf(listOf(ip("8.8.8.8"), ip("8.8.8.8"), ip("192.168.0.1"))))
        assertEquals(listOf("8.8.8.8", "192.168.0.1", "1.1.1.1"), servers)
    }

    @Test
    fun escopoIPv6NaoEntra() {
        // Endereço com escopo (%3): o resolv.conf não aceita o sufixo.
        val base = ip("2001:4860:4860::8888").address
        val scoped = Inet6Address.getByAddress(null, base, 3)
        val content = LinuxEnv.resolvConf(listOf(scoped))
        assertTrue(!content.contains('%'))
        val servers = nameservers(content)
        assertEquals(3, servers.size)
        assertTrue(servers[0].startsWith("2001:4860"))
        assertEquals("8.8.8.8", servers[1])
    }

    @Test
    fun terminaComOpcoesDeTimeout() {
        val content = LinuxEnv.resolvConf(listOf(ip("192.168.0.1")))
        assertTrue(content.contains("options timeout:2 attempts:2"))
    }
}

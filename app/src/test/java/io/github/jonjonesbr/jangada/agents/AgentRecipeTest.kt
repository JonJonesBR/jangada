package io.github.jonjonesbr.jangada.agents

import io.github.jonjonesbr.jangada.linux.LinuxEnv
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Confere as receitas do catálogo (assets/agents) e os scripts gerados a
 * partir delas. O app ignora em silêncio uma receita com erro; aqui ela
 * derruba o build.
 */
class AgentRecipeTest {

    private val files = File("src/main/assets/agents").listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
    private val recipes = files.map { AgentRecipe.parse(JSONObject(it.readText())) }

    @Test
    fun todasAsReceitasAbrem() {
        assertTrue("nenhuma receita encontrada", files.isNotEmpty())
        for ((file, recipe) in files.zip(recipes)) {
            assertEquals("id diferente do nome do arquivo", file.name.removeSuffix(".json"), recipe.id)
            assertTrue("${recipe.id}: sem nome", recipe.name.isNotBlank())
            assertTrue("${recipe.id}: sem comando", recipe.command.isNotBlank())
            val description = JSONObject(file.readText()).getJSONObject("description")
            for (lang in listOf("en", "pt")) {
                assertTrue("${recipe.id}: sem descrição em $lang", description.optString(lang).isNotBlank())
            }
        }
        assertEquals("ids repetidos", recipes.size, recipes.map { it.id }.toSet().size)
    }

    @Test
    fun scriptsGeradosSaoBashValido() {
        for (recipe in recipes) {
            for (updating in listOf(false, true)) {
                checkSyntax("${recipe.id} install", recipe.installScript(updating, "Instalando", "✓ pronto", "✕ código %s"))
                checkSyntax("${recipe.id} background", recipe.backgroundScript(updating))
            }
            checkSyntax("${recipe.id} uninstall", recipe.uninstallScript())
            checkSyntax("${recipe.id} launch", recipe.launchScript())
            recipe.acp?.installScript()?.let { checkSyntax("${recipe.id} acp", it) }
        }
        checkSyntax("detect", AgentCatalog.detectScript(recipes))
        checkSyntax("cleanup", LinuxEnv.CLEANUP_SCRIPT)
    }

    @Test
    fun aspasSeguras() {
        assertEquals("'simples'", AgentRecipe.q("simples"))
        assertEquals("'it'\\''s'", AgentRecipe.q("it's"))
        assertEquals(listOf("a b", "it's", "\$HOME", "`x`"), runBash("printf '%s\\n' ${listOf("a b", "it's", "\$HOME", "`x`").joinToString(" ") { AgentRecipe.q(it) }}"))
    }

    @Test
    fun avisoDeFalhaMostraOCodigo() {
        val recipe = recipes.first()
        val script = recipe.installScript(false, "H", "R", "falhou 100% (código %s)")
        // Só a linha do aviso de falha, com um status qualquer.
        val line = script.lines().first { it.contains("falhou") }.trim()
        assertEquals(listOf("", "falhou 100% (código 7)"), runBash("status=7; $line"))
    }

    @Test
    fun pipefailFazDownloadQuebradoFalhar() {
        // Os scripts de instalação e de segundo plano precisam de pipefail (o "curl | bash" depende disso).
        for (recipe in recipes) {
            for (updating in listOf(false, true)) {
                assertTrue("${recipe.id}: installScript sem pipefail", recipe.installScript(updating, "H", "R", "F %s").contains("pipefail"))
                assertTrue("${recipe.id}: backgroundScript sem pipefail", recipe.backgroundScript(updating).contains("pipefail"))
            }
            recipe.acp?.installScript()?.let { assertTrue("${recipe.id}: acp sem pipefail", it.contains("pipefail")) }
        }
        // Com pipefail, um pipeline com falha não passa; sem ele, passa (é o que o teste impede de voltar).
        assertFalse(runBash("set -eo pipefail; false | cat; echo JANGADA_OK").contains("JANGADA_OK"))
        assertTrue(runBash("set -e; false | cat; echo JANGADA_OK").contains("JANGADA_OK"))
        // Receita com instalação "false | bash" (download quebrado) e um comando que existe: não pode dar JANGADA_OK.
        val broken = AgentRecipe(
            id = "quebrada", order = 0, name = "Quebrada", vendor = "teste", command = "bash",
            install = "false | bash", update = null, requires = emptyList(), homepage = null,
            description = emptyMap(), note = emptyMap(),
        )
        val output = runBash(broken.backgroundScript(false))
        assertFalse("instalação com download quebrado não pode dar certo", output.contains("JANGADA_OK"))
        assertTrue(output.contains("JANGADA_FAIL 1"))
    }

    @Test
    fun versoes() {
        assertEquals("2.1.312", Versions.parse("2.1.312 (Claude Code)"))
        assertEquals(null, Versions.parse("sem versão"))
        assertTrue(Versions.isNewer("1.10.0", "1.9.9"))
        assertTrue(Versions.isNewer("v2.0.0", "1.99.99"))
        assertFalse(Versions.isNewer("1.0.0", "1.0.0"))
        assertFalse(Versions.isNewer("1.0.0", "1.0.1"))
        assertFalse(Versions.isNewer("x", "1.0.0"))
    }

    private fun checkSyntax(label: String, script: String) {
        val process = ProcessBuilder("bash", "-n", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("$label: bash demorou demais", process.waitFor(10, TimeUnit.SECONDS))
        assertEquals("$label: erro de sintaxe\n$output\n--- script ---\n$script", 0, process.exitValue())
    }

    private fun runBash(script: String): List<String> {
        val process = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(10, TimeUnit.SECONDS)
        return output.removeSuffix("\n").split("\n")
    }
}

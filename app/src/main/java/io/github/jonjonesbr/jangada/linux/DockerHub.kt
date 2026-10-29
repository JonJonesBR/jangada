package io.github.jonjonesbr.jangada.linux

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Cliente mínimo do Docker Hub: baixa as camadas de uma imagem oficial
 * (ex.: debian:trixie-slim) para a arquitetura do aparelho. É o mesmo
 * caminho que o proot-distro do Termux usa hoje para instalar distros.
 *
 * Cada camada é conferida pelo SHA-256 que o próprio registro publica.
 */
class DockerHub(private val repository: String) {

    data class Layer(val digest: String, val size: Long, val mediaType: String)

    class HttpError(val code: Int, host: String) : IOException("HTTP $code em $host")

    private var token: String? = null

    /** Lista as camadas da imagem [tag] para a arquitetura [architecture] (ex.: "arm64"). */
    fun layers(tag: String, architecture: String): List<Layer> {
        var manifest = manifest(tag)
        val list = manifest.optJSONArray("manifests")
        if (list != null) {
            var digest: String? = null
            for (i in 0 until list.length()) {
                val entry = list.getJSONObject(i)
                val platform = entry.optJSONObject("platform") ?: continue
                if (platform.optString("os") == "linux" && platform.optString("architecture") == architecture) {
                    digest = entry.getString("digest")
                    break
                }
            }
            manifest = manifest(digest ?: throw IOException("imagem sem versão para $architecture"))
        }
        val layers = manifest.getJSONArray("layers")
        return (0 until layers.length()).map {
            val layer = layers.getJSONObject(it)
            Layer(layer.getString("digest"), layer.getLong("size"), layer.getString("mediaType"))
        }
    }

    /** Baixa uma camada para [dest], conferindo o SHA-256 no final. */
    fun download(layer: Layer, dest: File, onProgress: (done: Long, total: Long) -> Unit) {
        val connection = request("$REGISTRY/v2/$repository/blobs/${layer.digest}", accept = null)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        onProgress(done, layer.size)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val actual = "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != layer.digest) {
            dest.delete()
            throw IOException("arquivo corrompido no download (esperado ${layer.digest}, veio $actual)")
        }
    }

    private fun manifest(reference: String): JSONObject {
        val connection = request("$REGISTRY/v2/$repository/manifests/$reference", MANIFEST_TYPES)
        try {
            return JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Faz um GET seguindo redirecionamentos à mão: o token só é enviado ao
     * próprio registro. A CDN para onde os arquivos redirecionam recusa
     * pedidos que carregam o token.
     */
    private fun request(url: String, accept: String?): HttpURLConnection {
        var current = URL(url)
        var retriedAuth = false
        repeat(MAX_REDIRECTS) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (accept != null) connection.setRequestProperty("Accept", accept)
            if (current.host == REGISTRY_HOST) connection.setRequestProperty("Authorization", "Bearer ${token()}")

            val code = connection.responseCode
            when {
                code in 200..299 -> return connection
                code in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    current = URL(current, location ?: throw HttpError(code, current.host))
                    if (current.protocol != "https") throw IOException("redirecionamento inseguro para ${current.host}")
                }
                code == 401 && !retriedAuth && current.host == REGISTRY_HOST -> {
                    connection.disconnect()
                    token = null
                    retriedAuth = true
                }
                else -> {
                    connection.disconnect()
                    throw HttpError(code, current.host)
                }
            }
        }
        throw IOException("redirecionamentos demais ao baixar $url")
    }

    private fun token(): String {
        token?.let { return it }
        val scope = URLEncoder.encode("repository:$repository:pull", "UTF-8")
        val connection = URL("$AUTH_URL?service=registry.docker.io&scope=$scope").openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (connection.responseCode !in 200..299) throw HttpError(connection.responseCode, "auth.docker.io")
            val json = JSONObject(connection.inputStream.bufferedReader().readText())
            val value = json.optString("token").ifEmpty { json.getString("access_token") }
            token = value
            return value
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val REGISTRY_HOST = "registry-1.docker.io"
        private const val REGISTRY = "https://$REGISTRY_HOST"
        private const val AUTH_URL = "https://auth.docker.io/token"
        private const val USER_AGENT = "Jangada (Android)"
        private const val MAX_REDIRECTS = 6
        private const val MANIFEST_TYPES =
            "application/vnd.oci.image.index.v1+json, " +
                "application/vnd.docker.distribution.manifest.list.v2+json, " +
                "application/vnd.oci.image.manifest.v1+json, " +
                "application/vnd.docker.distribution.manifest.v2+json"
    }
}

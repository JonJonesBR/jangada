package io.github.jonjonesbr.jangada.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Anexos do chat. Todo arquivo é copiado para ~/projetos/anexos (dentro do
 * Linux), onde o agente consegue abrir com as próprias ferramentas. Fotos
 * e prints também são reduzidos e enviados como imagem, para o agente ver.
 */
object Attachments {

    private const val MAX_IMAGE_SIDE = 1568
    private const val MAX_NAME = 80
    const val DIR = "anexos"

    /** Lê o arquivo escolhido no Android e prepara o anexo (rodar fora da thread principal). */
    fun prepare(context: Context, linux: LinuxEnv, uri: Uri): Attachment {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = safeName(displayName(context, uri) ?: "anexo")
        val dir = File(linux.rootfs, "root/${LinuxEnv.WORKSPACE}/$DIR").apply { mkdirs() }
        val target = unique(dir, name)
        try {
            resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: throw java.io.IOException("could not read the file")
        } catch (e: Exception) {
            // Cópia pela metade não fica na pasta de anexos.
            target.delete()
            throw e
        }
        val guestPath = "/root/${LinuxEnv.WORKSPACE}/$DIR/${target.name}"
        val image = if (mime.startsWith("image/")) encodeImage(target) else null
        return Attachment(target.name, guestPath, if (image != null) "image/jpeg" else mime, image)
    }

    /** Texto que acompanha a mensagem com o caminho dos arquivos anexados. */
    fun filesNote(attachments: List<Attachment>, includeImages: Boolean = false): String {
        val files = attachments.filter { includeImages || it.imageBase64 == null }
        if (files.isEmpty()) return ""
        return "\n\n(Anexos: " + files.joinToString(", ") { it.guestPath } + ")"
    }

    /** Reduz a foto (lado maior até 1568 px) e devolve em base64 JPEG. */
    private fun encodeImage(file: File): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /**
     * Nome seguro para o Linux, com no máximo [MAX_NAME] caracteres. Corta só
     * a base, para manter a extensão (".pdf"), e nunca no meio de um emoji.
     */
    internal fun safeName(name: String): String {
        val clean = name.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").trim('_').ifEmpty { "anexo" }
        if (clean.codePointCount(0, clean.length) <= MAX_NAME) return clean
        val dot = clean.lastIndexOf('.')
        val ext = if (dot > 0 && clean.length - dot <= 10) clean.substring(dot) else ""
        val base = clean.substring(0, clean.length - ext.length)
        val keep = (MAX_NAME - ext.codePointCount(0, ext.length)).coerceAtLeast(1)
        return base.substring(0, base.offsetByCodePoints(0, minOf(keep, base.codePointCount(0, base.length)))) + ext
    }

    private fun unique(dir: File, name: String): File {
        var file = File(dir, name)
        var counter = 2
        while (file.exists()) {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            file = File(dir, "$base-$counter$ext")
            counter++
        }
        return file
    }
}

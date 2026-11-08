package com.csm.jam

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Guests send their songs to the host instead of serving them from their own phone.
 * That way a song keeps playing for everyone even after the guest who added it leaves.
 */
object TrackUploader {

    /** Blocking – call from a background thread. Returns null on success, otherwise an error message. */
    fun upload(
        context: Context,
        hostIp: String,
        token: String,
        uri: Uri,
        fileName: String,
        autoplay: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): String? {
        val resolver = context.contentResolver
        var size = querySize(context, uri)
        var tempFile: File? = null
        // The host needs a Content-Length – copy streams of unknown length to a temp file first
        if (size <= 0L) {
            tempFile = File(context.cacheDir, "upload_${System.nanoTime()}")
            resolver.openInputStream(uri)?.use { input -> tempFile.outputStream().use { input.copyTo(it) } }
                ?: return "Datei konnte nicht gelesen werden"
            size = tempFile.length()
        }

        val query = buildString {
            append("token=").append(Uri.encode(token))
            append("&name=").append(Uri.encode(fileName))
            append("&source=app")
            if (autoplay) append("&autoplay=1")
        }
        val connection = URL("http://$hostIp:8081/api/upload?$query").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 30000
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(size)

            val input = tempFile?.inputStream() ?: resolver.openInputStream(uri) ?: return "Datei konnte nicht gelesen werden"
            input.use { inp ->
                connection.outputStream.use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val read = inp.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        sent += read
                        onProgress((sent.toFloat() / size).coerceIn(0f, 1f))
                    }
                }
            }
            if (connection.responseCode == 200) {
                null
            } else {
                val body = try { connection.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                body?.let { try { JSONObject(it).optString("error") } catch (_: Exception) { null } }
                    ?.ifBlank { null } ?: "Upload fehlgeschlagen (${connection.responseCode})"
            }
        } catch (e: Exception) {
            "Upload fehlgeschlagen – Verbindung zum Host?"
        } finally {
            connection.disconnect()
            tempFile?.delete()
        }
    }

    private fun querySize(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
            }
        } catch (_: Exception) {}
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length > 0) return afd.length
            }
        } catch (_: Exception) {}
        return -1L
    }
}

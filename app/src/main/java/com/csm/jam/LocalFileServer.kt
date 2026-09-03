package com.csm.jam

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LocalFileServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    // ConcurrentHashMap: NanoHTTPD serves each request on a worker thread; we write from the UI thread.
    private val activeFiles = ConcurrentHashMap<String, Uri>()
    private val activeArtworks = ConcurrentHashMap<String, ByteArray>()

    fun reset() {
        activeFiles.clear()
        activeArtworks.clear()
    }

    fun hostFile(uri: Uri): Pair<String, Boolean> {
        val id = UUID.randomUUID().toString()
        activeFiles[id] = uri

        var hasArt = false
        // Extract artwork if available
        try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val art = retriever.embeddedPicture
            if (art != null) {
                activeArtworks[id] = art
                hasArt = true
            }
            retriever.release()
        } catch (ignored: Exception) {}

        return Pair(id, hasArt)
    }

    fun getFileUri(fileId: String): Uri? = activeFiles[fileId]
    fun getArtwork(fileId: String): ByteArray? = activeArtworks[fileId]

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.removePrefix("/")

        // Deep Link Landing Page for WhatsApp/Browser: http://<ip>:8081/join
        if (path == "join" || path.startsWith("join/")) {
            val hostIp = session.headers["host"]?.substringBefore(":") ?: "127.0.0.1"
            val port = session.parameters["port"]?.firstOrNull()?.toIntOrNull() ?: 8887
            val html = """
                <!DOCTYPE html>
                <html lang="de">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>CSM Jam - Session beitreten</title>
                    <style>
                        * { box-sizing: border-box; margin: 0; padding: 0; }
                        body {
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                            background: linear-gradient(135deg, #0F2027 0%, #203A43 50%, #2C5364 100%);
                            color: #FFFFFF;
                            min-height: 100vh;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            padding: 20px;
                        }
                        .card {
                            background: rgba(30, 41, 59, 0.9);
                            backdrop-filter: blur(12px);
                            border: 1px solid rgba(255, 255, 255, 0.1);
                            border-radius: 24px;
                            padding: 32px 24px;
                            max-width: 420px;
                            width: 100%;
                            text-align: center;
                            box-shadow: 0 20px 40px rgba(0, 0, 0, 0.5);
                        }
                        .icon { font-size: 56px; margin-bottom: 16px; display: inline-block; }
                        h1 { font-size: 26px; font-weight: 800; margin-bottom: 10px; letter-spacing: -0.5px; }
                        p { color: #94A3B8; font-size: 14px; line-height: 1.5; margin-bottom: 24px; }
                        .info-pill {
                            background: rgba(0, 230, 118, 0.1);
                            border: 1px solid rgba(0, 230, 118, 0.3);
                            color: #00E676;
                            padding: 10px 16px;
                            border-radius: 12px;
                            font-size: 13px;
                            font-family: monospace;
                            display: inline-block;
                            margin-bottom: 24px;
                        }
                        .btn {
                            display: block; width: 100%; background: #00E676; color: #0F2027;
                            font-weight: 700; font-size: 16px; padding: 16px 20px;
                            border-radius: 30px; text-decoration: none;
                            box-shadow: 0 8px 20px rgba(0, 230, 118, 0.4);
                        }
                        .footer { margin-top: 24px; font-size: 12px; color: #64748B; }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div class="icon">𓆩🜲𓆪</div>
                        <h1>CSM Jam Session</h1>
                        <p>Du wurdest eingeladen, synchron Musik mitzuhören!</p>
                        <div class="info-pill">Host: $hostIp:$port</div>
                        <a class="btn" href="csmjam://join?host=$hostIp&port=$port">In CSM Jam öffnen</a>
                        <div class="footer">
                            Stelle sicher, dass du mit demselben WLAN oder Hotspot verbunden bist.
                        </div>
                    </div>
                    <script>
                        setTimeout(function() {
                            window.location.href = "csmjam://join?host=$hostIp&port=$port";
                        }, 300);
                    </script>
                </body>
                </html>
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html)
        }

        if (path.startsWith("art/")) {
            val artId = path.removePrefix("art/").substringBefore("/")
            val artwork = activeArtworks[artId]
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Artwork not found")
            return newFixedLengthResponse(
                Response.Status.OK, "image/jpeg",
                ByteArrayInputStream(artwork), artwork.size.toLong()
            )
        }

        val fileId = path.substringBefore("/")
        val contentUri = activeFiles[fileId]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")

        return try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(contentUri) ?: "audio/mpeg"

            // Determine file size
            var size: Long = -1L
            try {
                val cursor = contentResolver.query(contentUri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeIndex != -1) size = it.getLong(sizeIndex)
                    }
                }
            } catch (ignored: Exception) {}

            if (size <= 0L) {
                try {
                    contentResolver.openAssetFileDescriptor(contentUri, "r")?.use { afd ->
                        val len = afd.length
                        if (len != android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH) size = len
                    }
                } catch (ignored: Exception) {}
            }

            val rangeHeader = session.headers["range"] ?: session.headers["Range"]
            if (rangeHeader != null && rangeHeader.startsWith("bytes=") && size > 0L) {
                val range = rangeHeader.substring(6).split("-")
                val startFrom = (range[0].toLongOrNull() ?: 0L).coerceIn(0L, size - 1)
                var endAt = size - 1
                if (range.size > 1 && range[1].isNotEmpty()) {
                    endAt = (range[1].toLongOrNull() ?: endAt).coerceIn(startFrom, size - 1)
                }
                val length = (endAt - startFrom + 1).coerceAtLeast(0L)

                // --- Fast-Seek via FileChannel (O(1) seek, avoids reading & discarding bytes) ---
                val rangeStream: InputStream = try {
                    val pfd = contentResolver.openFileDescriptor(contentUri, "r")
                    if (pfd != null) {
                        val channel = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
                        channel.position(startFrom)
                        Channels.newInputStream(channel)
                    } else {
                        // Fallback: slow sequential skip
                        val stream = contentResolver.openInputStream(contentUri)!!
                        skipFully(stream, startFrom)
                        stream
                    }
                } catch (e: Exception) {
                    // Fallback for URIs that don't support FileDescriptor (e.g. virtual files)
                    val stream = contentResolver.openInputStream(contentUri)!!
                    skipFully(stream, startFrom)
                    stream
                }

                val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, rangeStream, length)
                res.addHeader("Accept-Ranges", "bytes")
                res.addHeader("Content-Range", "bytes $startFrom-$endAt/$size")
                res.addHeader("Content-Length", length.toString())
                res
            } else {
                // Full-file request
                val inputStream = contentResolver.openInputStream(contentUri)
                    ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream could not be opened")
                if (size <= 0L) {
                    newChunkedResponse(Response.Status.OK, mimeType, inputStream)
                } else {
                    val res = newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, size)
                    res.addHeader("Accept-Ranges", "bytes")
                    res
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    /** Fallback sequential skip for URIs that don't support FileDescriptor-based seeking. */
    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val buffer = ByteArray(65536)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                remaining -= read
            } else {
                remaining -= skipped
            }
        }
    }
}

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

    companion object {
        private const val MAX_UPLOAD_BYTES = 200L * 1024 * 1024
    }

    // ConcurrentHashMap: NanoHTTPD serves each request on a worker thread; we write from the UI thread.
    private val activeFiles = ConcurrentHashMap<String, Uri>()
    private val activeArtworks = ConcurrentHashMap<String, ByteArray>()
    // Explicit MIME types for files that have no ContentProvider behind them (browser uploads)
    private val activeMimeTypes = ConcurrentHashMap<String, String>()
    private val uploadDir get() = java.io.File(context.cacheDir, "web_uploads")

    fun reset() {
        activeFiles.clear()
        activeArtworks.clear()
        activeMimeTypes.clear()
        uploadDir.listFiles()?.forEach { it.delete() }
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
    fun getArtwork(fileId: String): ByteArray? = activeArtworks[fileId] ?: loadArtwork(fileId)

    /** Artwork is re-extracted on demand after a session restore instead of blocking the restart. */
    private fun loadArtwork(fileId: String): ByteArray? {
        val uri = activeFiles[fileId] ?: return null
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val art = retriever.embeddedPicture
            retriever.release()
            art?.also { activeArtworks[fileId] = it }
        } catch (_: Exception) { null }
    }

    /** id → uri/mime of every hosted file, for the session snapshot. */
    fun snapshot(): org.json.JSONObject = org.json.JSONObject().apply {
        activeFiles.forEach { (id, uri) ->
            put(id, org.json.JSONObject().put("uri", uri.toString()).put("mime", activeMimeTypes[id] ?: org.json.JSONObject.NULL))
        }
    }

    fun restore(files: org.json.JSONObject) {
        files.keys().forEach { id ->
            val o = files.getJSONObject(id)
            activeFiles[id] = Uri.parse(o.getString("uri"))
            if (!o.isNull("mime")) activeMimeTypes[id] = o.getString("mime")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.removePrefix("/")

        // Browser guests (iPhone & co.): the page itself checks /api/info whether this device hosts a session
        if (path == "" || path == "index.html" || path == "join" || path.startsWith("join/")) {
            return serveAsset("web/index.html", "text/html; charset=UTF-8")
        }
        if (path == "api/info") {
            val server = MainActivity.localServer
            val info = org.json.JSONObject().apply {
                put("hosting", server != null && MainActivity.isHostMode && MainActivity.isSessionActive)
                put("sessionName", server?.sessionName ?: "CSM Jam")
                put("participants", server?.getParticipantCount() ?: 0)
                put("track", server?.getCurrentTrackTitle() ?: org.json.JSONObject.NULL)
                put("wsPort", 8887)
            }
            return jsonResponse(Response.Status.OK, info).apply { addHeader("Cache-Control", "no-store") }
        }
        if (path == "api/upload") {
            return handleUpload(session)
        }
        if (path == "favicon.ico") {
            return newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
        }

        if (path.startsWith("art/")) {
            val artId = path.removePrefix("art/").substringBefore("/")
            val artwork = getArtwork(artId)
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
            val mimeType = activeMimeTypes[fileId] ?: contentResolver.getType(contentUri) ?: "audio/mpeg"

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

    private fun serveAsset(assetPath: String, mime: String): Response = try {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "no-cache")
        }
    } catch (e: Exception) {
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
    }

    private fun jsonResponse(status: Response.Status, body: org.json.JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=UTF-8", body.toString())

    /**
     * Song upload from the web player: raw file body, approved browser token in the query string.
     * The file is copied to the cache dir, hosted like any picked file and queued on the session server.
     */
    private fun handleUpload(session: IHTTPSession): Response {
        // The body may be left unread on errors – close the connection so it can't be parsed as the next request
        fun error(status: Response.Status, msg: String) =
            jsonResponse(status, org.json.JSONObject().put("error", msg)).apply { addHeader("Connection", "close") }

        if (session.method != Method.POST) return error(Response.Status.METHOD_NOT_ALLOWED, "POST erwartet")
        val server = MainActivity.localServer ?: return error(Response.Status.FORBIDDEN, "Keine aktive Session")
        val token = session.parameters["token"]?.firstOrNull().orEmpty()
        val autoplay = session.parameters["autoplay"]?.firstOrNull() == "1"
        val fromApp = session.parameters["source"]?.firstOrNull() == "app"
        val uploader = server.uploaderName(token, autoplay)
            ?: return error(Response.Status.FORBIDDEN, "Keine Berechtigung zum Hinzufügen")

        val length = session.headers["content-length"]?.toLongOrNull()
            ?: return error(Response.Status.BAD_REQUEST, "Content-Length fehlt")
        if (length <= 0L || length > MAX_UPLOAD_BYTES) return error(Response.Status.BAD_REQUEST, "Datei zu groß (max. 200 MB)")

        val originalName = session.parameters["name"]?.firstOrNull()
            ?.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")?.take(120)
            ?.ifBlank { null } ?: "Upload.mp3"
        val ext = originalName.substringAfterLast('.', "mp3").lowercase()
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> return error(Response.Status.UNSUPPORTED_MEDIA_TYPE, "Nur MP3, M4A, AAC, FLAC, OGG oder WAV")
        }

        val file = java.io.File(uploadDir.apply { mkdirs() }, "${UUID.randomUUID()}.$ext")
        try {
            file.outputStream().use { out ->
                val input = session.inputStream
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
                if (remaining > 0) throw java.io.IOException("Upload abgebrochen")
            }
        } catch (e: Exception) {
            file.delete()
            return error(Response.Status.BAD_REQUEST, "Upload fehlgeschlagen")
        }

        val (fileId, hasArt) = hostFile(Uri.fromFile(file))
        activeMimeTypes[fileId] = mime

        val title = try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val t = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            val a = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
            retriever.release()
            if (!t.isNullOrBlank()) (if (!a.isNullOrBlank()) "$t - $a" else t) else null
        } catch (_: Exception) { null } ?: originalName.substringBeforeLast('.')

        // The Host header is the address the browser used to reach us – reachable for everyone else in the LAN too
        val hostAddr = session.headers["host"]?.substringBefore(":")?.ifBlank { null } ?: "127.0.0.1"
        val trackUrl = "http://$hostAddr:8081/$fileId/${Uri.encode(originalName)}"
        val artUrl = if (hasArt) "http://$hostAddr:8081/art/$fileId" else null
        val addedBy = when {
            autoplay -> "$uploader (Autoplay)"
            fromApp -> uploader
            else -> "$uploader (Web)"
        }
        server.addTrack(trackUrl, title, artUrl, addedBy)

        return jsonResponse(Response.Status.OK, org.json.JSONObject().put("ok", true).put("title", title))
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

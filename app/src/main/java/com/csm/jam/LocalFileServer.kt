package com.csm.jam

import android.content.Context
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID

class LocalFileServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    private val activeFiles = mutableMapOf<String, Uri>()
    private val activeArtworks = mutableMapOf<String, ByteArray>()

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

    fun getFileUri(fileId: String): Uri? {
        return activeFiles[fileId]
    }

    fun getArtwork(fileId: String): ByteArray? {
        return activeArtworks[fileId]
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.removePrefix("/")
        
        if (path.startsWith("art/")) {
            val artId = path.removePrefix("art/").substringBefore("/")
            val artwork = activeArtworks[artId]
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Artwork not found")
            return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(artwork), artwork.size.toLong())
        }

        val fileId = path.substringBefore("/")
        
        val contentUri = activeFiles[fileId]
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")


        return try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(contentUri) ?: "audio/mpeg"
            val inputStream: InputStream? = contentResolver.openInputStream(contentUri)
            
            if (inputStream != null) {
                var size: Long = -1L
                try {
                    val cursor = contentResolver.query(contentUri, null, null, null, null)
                    if (cursor != null && cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeIndex != -1) {
                            size = cursor.getLong(sizeIndex)
                        }
                        cursor.close()
                    }
                } catch (ignored: Exception) {}

                if (size <= 0L) {
                    try {
                        val afd = contentResolver.openAssetFileDescriptor(contentUri, "r")
                        if (afd != null) {
                            val afdLength = afd.length
                            if (afdLength != android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH) {
                                size = afdLength
                            }
                            afd.close()
                        }
                    } catch (ignored: Exception) {}
                }

                if (size <= 0L) {
                    // Fallback to chunked transfer if size is still unknown
                    return newChunkedResponse(Response.Status.OK, mimeType, inputStream)
                }

                val rangeHeader = session.headers["range"]
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val range = rangeHeader.substring(6).split("-")
                    val startFrom = range[0].toLongOrNull() ?: 0L
                    var endAt = size - 1
                    if (range.size > 1 && range[1].isNotEmpty()) {
                        endAt = range[1].toLong()
                    }
                    val length = endAt - startFrom + 1

                    inputStream.skip(startFrom)
                    val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, inputStream, length)
                    res.addHeader("Accept-Ranges", "bytes")
                    res.addHeader("Content-Range", "bytes $startFrom-$endAt/$size")
                    return res
                } else {
                    val res = newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, size)
                    res.addHeader("Accept-Ranges", "bytes")
                    return res
                }
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream could not be opened")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }
}

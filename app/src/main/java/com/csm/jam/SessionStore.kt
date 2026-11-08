package com.csm.jam

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps a snapshot of the hosted session on disk. If the host app gets killed or crashes,
 * the session (queue, position, guests' approvals, stats) can be resumed for 10 minutes.
 */
object SessionStore {
    const val MAX_AGE_MS = 10 * 60 * 1000L
    private const val FILE_NAME = "session_snapshot.json"

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var pendingSave: ScheduledFuture<*>? = null

    data class Saved(
        val server: JSONObject,
        val files: JSONObject,
        val savedAtMs: Long,
        val sessionName: String,
        val queueSize: Int,
        val currentTitle: String?
    ) {
        val remainingMs: Long get() = (MAX_AGE_MS - (System.currentTimeMillis() - savedAtMs)).coerceAtLeast(0L)
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Debounced save (state changes come in bursts); the snapshot is taken on the background thread. */
    @Synchronized
    fun requestSave(context: Context, server: LocalMusicServer, fileServer: LocalFileServer?, hostIp: String) {
        if (pendingSave?.isDone == false) return
        val appContext = context.applicationContext
        pendingSave = executor.schedule({
            try {
                val json = JSONObject().apply {
                    put("server", server.snapshot())
                    put("files", fileServer?.snapshot() ?: JSONObject())
                    put("hostIp", hostIp)
                    put("savedAtMs", System.currentTimeMillis())
                }
                // Write to a temp file first so a crash mid-write never leaves a broken snapshot
                val target = file(appContext)
                val tmp = File(target.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(json.toString())
                tmp.renameTo(target)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, 1, TimeUnit.SECONDS)
    }

    /** Returns the saved session if it is younger than 10 minutes, with URLs rewritten to [currentIp]. */
    fun load(context: Context, currentIp: String): Saved? {
        val f = file(context)
        if (!f.exists()) return null
        return try {
            var text = f.readText()
            val raw = JSONObject(text)
            val savedAt = raw.optLong("savedAtMs", 0L)
            if (System.currentTimeMillis() - savedAt > MAX_AGE_MS) {
                f.delete()
                return null
            }
            // The phone may have got a new IP after the restart – all track/artwork URLs point at the old one
            val oldIp = raw.optString("hostIp", "")
            if (oldIp.isNotBlank() && currentIp != "Unbekannt" && oldIp != currentIp) {
                text = text.replace("http://$oldIp:8081/", "http://$currentIp:8081/")
            }
            val json = JSONObject(text)
            val server = json.getJSONObject("server")
            val current = if (server.isNull("currentTrackUrl")) null else server.optString("currentTrackUrl")
            Saved(
                server = server,
                files = json.optJSONObject("files") ?: JSONObject(),
                savedAtMs = savedAt,
                sessionName = server.optString("sessionName", "CSM Jam"),
                queueSize = server.optJSONArray("queue")?.length() ?: 0,
                currentTitle = current?.let { server.optJSONObject("titles")?.optString(it)?.ifBlank { null } }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @Synchronized
    fun clear(context: Context) {
        pendingSave?.cancel(false)
        pendingSave = null
        val appContext = context.applicationContext
        executor.execute { file(appContext).delete() }
    }
}

package com.csm.jam

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Drives the real WebSocket server with an "app host" and "browser guests" (Origin header). */
class LocalMusicServerTest {

    private lateinit var server: LocalMusicServer
    private var port = 0
    private val clients = mutableListOf<TestClient>()

    class TestClient(uri: URI, headers: Map<String, String>) : WebSocketClient(uri, headers) {
        val inbox = LinkedBlockingQueue<JSONObject>()
        @Volatile var closeReason: String? = null
        val closed = java.util.concurrent.CountDownLatch(1)

        override fun onOpen(handshakedata: ServerHandshake?) {}
        override fun onMessage(message: String) { inbox.add(JSONObject(message)) }
        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            closeReason = reason
            closed.countDown()
        }
        override fun onError(ex: Exception?) {}

        fun sendJson(vararg pairs: Pair<String, Any>) {
            send(JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }.toString())
        }

        /** Waits for the next message of [type], skipping everything else. */
        fun await(type: String, timeoutMs: Long = 3000): JSONObject? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                val msg = inbox.poll(left, TimeUnit.MILLISECONDS) ?: return null
                if (msg.optString("type") == type) return msg
            }
        }

        /** Waits for a JOIN_REQUESTS update that actually contains requests (the host also gets empty lists). */
        fun awaitRequests(timeoutMs: Long = 3000): org.json.JSONArray? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val msg = await("JOIN_REQUESTS", deadline - System.currentTimeMillis()) ?: return null
                val arr = msg.getJSONArray("requests")
                if (arr.length() > 0) return arr
            }
            return null
        }

        /** True if a message of [type] arrives within [timeoutMs]. */
        fun receives(type: String, timeoutMs: Long = 600) = await(type, timeoutMs) != null
    }

    @Before
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        server = LocalMusicServer(port)
        server.start()
        Thread.sleep(300)
    }

    @After
    fun tearDown() {
        clients.forEach { try { it.closeBlocking() } catch (_: Exception) {} }
        server.stop(500)
    }

    private fun connect(browser: Boolean): TestClient {
        val headers = if (browser) mapOf("Origin" to "http://127.0.0.1:8081", "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari") else emptyMap()
        val c = TestClient(URI("ws://127.0.0.1:$port"), headers)
        assertTrue(c.connectBlocking(3, TimeUnit.SECONDS))
        clients += c
        return c
    }

    private fun hostWithSession(): TestClient {
        val host = connect(browser = false)
        host.sendJson("type" to "ACTION_JOIN", "username" to "Steve")
        assertNotNull(host.await("LISTS_UPDATE"))
        return host
    }

    @Test
    fun browserGuestMustBeApprovedByHost() {
        val host = hostWithSession()
        val guest = connect(browser = true)

        // Before introducing itself the browser gets no session data at all
        guest.sendJson("type" to "ACTION_JOIN", "username" to "Lena")
        assertNotNull(guest.await("JOIN_PENDING"))
        assertFalse(guest.receives("LISTS_UPDATE"))

        val req = host.awaitRequests()!!
        assertEquals(1, req.length())
        assertEquals("Lena", req.getJSONObject(0).getString("name"))
        assertEquals("iPhone · Safari", req.getJSONObject(0).getString("device"))

        // Pending guests can't control anything
        guest.sendJson("type" to "ACTION_ADD_TRACK", "trackUrl" to "http://x/1.mp3")
        assertFalse(host.receives("PREPARE"))

        host.sendJson("type" to "ACTION_RESPOND_JOIN", "requestId" to req.getJSONObject(0).getString("requestId"), "accept" to true)
        val approved = guest.await("JOIN_APPROVED")
        assertNotNull(approved)
        assertTrue(approved!!.getString("token").isNotBlank())
        val lists = guest.await("LISTS_UPDATE")!!
        val names = (0 until lists.getJSONArray("participants").length()).map {
            lists.getJSONArray("participants").getJSONObject(it).getString("name")
        }
        assertTrue(names.contains("◎ Lena"))
        assertEquals(0, host.await("JOIN_REQUESTS")!!.getJSONArray("requests").length())
    }

    @Test
    fun deniedBrowserGuestIsDisconnected() {
        val host = hostWithSession()
        val guest = connect(browser = true)
        guest.sendJson("type" to "ACTION_JOIN", "username" to "Troll")
        val requestId = host.awaitRequests()!!.getJSONObject(0).getString("requestId")

        host.sendJson("type" to "ACTION_RESPOND_JOIN", "requestId" to requestId, "accept" to false)
        assertNotNull(guest.await("JOIN_DENIED"))
        assertTrue(guest.closed.await(3, TimeUnit.SECONDS))
        assertEquals(LocalMusicServer.CLOSE_DENIED, guest.closeReason)
    }

    @Test
    fun approvedBrowserRejoinsWithTokenWithoutAsking() {
        val host = hostWithSession()
        val guest = connect(browser = true)
        guest.sendJson("type" to "ACTION_JOIN", "username" to "Lena")
        val requestId = host.awaitRequests()!!.getJSONObject(0).getString("requestId")
        host.sendJson("type" to "ACTION_RESPOND_JOIN", "requestId" to requestId, "accept" to true)
        val token = guest.await("JOIN_APPROVED")!!.getString("token")
        guest.closeBlocking()
        host.inbox.clear()

        val again = connect(browser = true)
        again.sendJson("type" to "ACTION_JOIN", "username" to "Lena", "token" to token)
        assertNotNull(again.await("JOIN_APPROVED"))
        // The host must not be bothered with a new (non-empty) request
        val pending = host.await("JOIN_REQUESTS", 600)
        assertTrue(pending == null || pending.getJSONArray("requests").length() == 0)
    }

    @Test
    fun appGuestJoinsWithoutApprovalAndGetsPermissions() {
        hostWithSession()
        // App clients send no Origin header. (Loopback while a host is live → regular guest, not host.)
        val appGuest = connect(browser = false)
        val perms = appGuest.await("PARTICIPANT_PERMISSIONS_UPDATED")
        assertNotNull(perms)
        assertTrue(perms!!.getBoolean("canMegaphone"))
        assertFalse(perms.getBoolean("canControl"))
        assertNotNull(appGuest.await("LISTS_UPDATE"))
    }

    @Test
    fun hostReconnectStaysHost() {
        val host = hostWithSession()
        host.closeBlocking()
        Thread.sleep(200)
        val newHost = connect(browser = false)
        assertTrue(newHost.await("PARTICIPANT_PERMISSIONS_UPDATED")!!.getBoolean("canControl"))
    }

    @Test
    fun guestsAreTurnedAwayAfterSessionEnded() {
        hostWithSession()
        server.resetSession()
        Thread.sleep(200)
        val guest = connect(browser = true)
        assertTrue(guest.closed.await(3, TimeUnit.SECONDS))
        assertEquals(LocalMusicServer.CLOSE_NO_SESSION, guest.closeReason)
    }

    @Test
    fun kickedBrowserLosesItsToken() {
        val host = hostWithSession()
        val guest = connect(browser = true)
        guest.sendJson("type" to "ACTION_JOIN", "username" to "Lena")
        val requestId = host.awaitRequests()!!.getJSONObject(0).getString("requestId")
        host.sendJson("type" to "ACTION_RESPOND_JOIN", "requestId" to requestId, "accept" to true)
        val token = guest.await("JOIN_APPROVED")!!.getString("token")
        val lists = host.await("LISTS_UPDATE")!!.getJSONArray("participants")
        val lenaId = (0 until lists.length()).map { lists.getJSONObject(it) }.first { it.getBoolean("isWeb") }.getString("id")

        host.sendJson("type" to "ACTION_KICK_PARTICIPANT", "targetId" to lenaId)
        assertNotNull(guest.await("KICKED"))
        assertTrue(guest.closed.await(3, TimeUnit.SECONDS))

        val again = connect(browser = true)
        again.sendJson("type" to "ACTION_JOIN", "username" to "Lena", "token" to token)
        assertNotNull(again.await("JOIN_PENDING"))
        assertNull(again.await("JOIN_APPROVED", 500))
    }

    @Test
    fun addTrackStartsPlaybackAndPauseBroadcastsState() {
        val host = hostWithSession()
        host.sendJson("type" to "ACTION_ADD_TRACK", "trackUrl" to "http://h/1.mp3", "trackTitle" to "Song - Artist")
        assertEquals("http://h/1.mp3", host.await("PREPARE")!!.getString("trackUrl"))
        host.sendJson("type" to "ACTION_TRACK_READY", "trackUrl" to "http://h/1.mp3")
        val play = host.await("PLAY")!!
        assertTrue(play.getLong("startedAtMs") > System.currentTimeMillis())

        Thread.sleep(1300)
        host.inbox.clear()
        host.sendJson("type" to "ACTION_PAUSE")
        val pause = host.await("PAUSE")!!
        assertTrue(pause.getLong("pauseOffsetMs") in 100..1500)
        assertFalse(host.await("STATE_UPDATE")!!.getBoolean("isPlaying"))
    }

    @Test
    fun onlyOneMegaphoneSpeakerAtATime() {
        val host = hostWithSession()
        val guest = connect(browser = false)
        guest.sendJson("type" to "ACTION_JOIN", "username" to "Max")
        assertNotNull(guest.await("LISTS_UPDATE"))

        host.sendJson("type" to "VOICE_START", "speaker" to "Steve")
        assertEquals("Steve", guest.await("VOICE_START")!!.getString("speaker"))

        guest.sendJson("type" to "VOICE_START", "speaker" to "Max")
        assertFalse(host.receives("VOICE_START", 400))
        assertNotNull(guest.await("VOICE_BUSY"))

        // A non-speaker can't end someone else's announcement
        guest.sendJson("type" to "VOICE_END")
        assertFalse(guest.receives("VOICE_END"))
        host.sendJson("type" to "VOICE_END")
        assertNotNull(guest.await("VOICE_END"))
    }

    @Test
    fun nextTrackIsAnnouncedSeamlesslyAtTheExactEnd() {
        val host = hostWithSession()
        host.sendJson("type" to "ACTION_ADD_TRACK", "trackUrl" to "http://h/a.mp3", "trackTitle" to "A")
        host.sendJson("type" to "ACTION_ADD_TRACK", "trackUrl" to "http://h/b.mp3", "trackTitle" to "B")
        assertEquals("http://h/a.mp3", host.await("PREPARE")!!.getString("trackUrl"))
        // Clients get the follow-up track to buffer in advance
        var preload = host.await("PRELOAD")
        while (preload != null && preload.isNull("trackUrl")) preload = host.await("PRELOAD")
        assertEquals("http://h/b.mp3", preload!!.getString("trackUrl"))

        host.sendJson("type" to "ACTION_TRACK_READY", "trackUrl" to "http://h/a.mp3", "durationMs" to 2500L)
        val playA = host.await("PLAY")!!
        val startA = playA.getLong("startedAtMs")

        // PLAY for B arrives *before* A ends and starts exactly where A ends – no PREPARE round trip
        val playB = host.await("PLAY", 5000)!!
        assertEquals("http://h/b.mp3", playB.getString("trackUrl"))
        assertEquals(startA + 2500L, playB.getLong("startedAtMs"))
        assertEquals(0L, playB.getLong("pauseOffsetMs"))
        assertTrue(JamClock.nowMs() < playB.getLong("startedAtMs"))
    }

    @Test
    fun appGuestsNeedApprovalWhenHostWantsIt() {
        val host = hostWithSession()
        host.sendJson("type" to "ACTION_SET_APP_APPROVAL", "enabled" to true)
        // The host also gets the initial "false" on connect – wait for the switched value
        var approval = host.await("APP_APPROVAL_CHANGED")
        while (approval != null && !approval.getBoolean("enabled")) approval = host.await("APP_APPROVAL_CHANGED")
        assertNotNull(approval)

        val appGuest = connect(browser = false)
        appGuest.sendJson("type" to "ACTION_JOIN", "username" to "Max")
        assertNotNull(appGuest.await("JOIN_PENDING"))
        val req = host.awaitRequests()!!.getJSONObject(0)
        assertFalse(req.getBoolean("isWeb"))
        assertEquals("CSM Jam App", req.getString("device"))

        host.sendJson("type" to "ACTION_RESPOND_JOIN", "requestId" to req.getString("requestId"), "accept" to true)
        assertNotNull(appGuest.await("JOIN_APPROVED"))
        val lists = appGuest.await("LISTS_UPDATE")!!.getJSONArray("participants")
        val names = (0 until lists.length()).map { lists.getJSONObject(it).getString("name") }
        assertTrue(names.contains("◇ Max"))
    }

    @Test
    fun uploadsNeedControlRightsExceptRequestedAutoplay() {
        val host = hostWithSession()
        val hostToken = host.inbox.firstOrNull { it.optString("type") == "SESSION_TOKEN" }?.getString("token")
            ?: host.await("SESSION_TOKEN")!!.getString("token")
        val guest = connect(browser = false)
        val guestToken = guest.await("SESSION_TOKEN")!!.getString("token")

        assertEquals("Steve", server.uploaderName(hostToken))
        assertNull(server.uploaderName(guestToken))
        assertNull(server.uploaderName("made-up-token"))

        // The guest registers an autoplay folder and gets asked for a track → that one upload is allowed
        guest.sendJson("type" to "ACTION_REGISTER_FALLBACK", "eligible" to true)
        assertNotNull(guest.await("REQUEST_FALLBACK_TRACK"))
        assertNotNull(server.uploaderName(guestToken, autoplay = true))
        assertNull(server.uploaderName(guestToken, autoplay = false))

        host.sendJson("type" to "ACTION_SET_GUEST_PERMISSIONS", "allow" to true)
        assertNotNull(guest.await("GUEST_PERMISSIONS_CHANGED"))
        assertNotNull(server.uploaderName(guestToken))
    }

    @Test
    fun sessionSurvivesHostRestartViaSnapshot() {
        val host = hostWithSession()
        host.sendJson("type" to "ACTION_ADD_TRACK", "trackUrl" to "http://h/a.mp3", "trackTitle" to "Song A - Artist")
        host.sendJson("type" to "ACTION_ADD_TRACK", "trackUrl" to "http://h/b.mp3", "trackTitle" to "Song B")
        assertNotNull(host.await("PREPARE"))
        host.sendJson("type" to "ACTION_TRACK_READY", "trackUrl" to "http://h/a.mp3", "durationMs" to 200_000L)
        assertNotNull(host.await("PLAY"))
        Thread.sleep(1800)
        val snapshot = server.snapshot()
        assertTrue(snapshot.getLong("positionMs") in 500..2500)

        // "Crash": a brand-new server process restores the snapshot, the host app reconnects
        host.closeBlocking()
        server.stop(500)
        port = ServerSocket(0).use { it.localPort }
        server = LocalMusicServer(port)
        server.restore(org.json.JSONObject(snapshot.toString()))
        server.start()
        Thread.sleep(300)

        val newHost = connect(browser = false)
        val lists = newHost.await("LISTS_UPDATE")!!
        assertEquals(2, lists.getJSONArray("queue").length())
        assertEquals("Song A - Artist", lists.getJSONObject("trackTitles").getString("http://h/a.mp3"))
        assertEquals("http://h/a.mp3", newHost.await("PREPARE")!!.getString("trackUrl"))
        newHost.sendJson("type" to "ACTION_TRACK_READY", "trackUrl" to "http://h/a.mp3")
        val resumed = newHost.await("PLAY")!!
        // Continues where it stopped instead of from the beginning
        assertTrue(resumed.getLong("pauseOffsetMs") in 500..2500)
    }

    @Test
    fun crossfadeStartsNextTrackBeforeTheEndAndOnlyHostMaySetIt() {
        val host = hostWithSession()
        val guest = connect(browser = false)
        assertNotNull(guest.await("LISTS_UPDATE"))
        // A guest can't change the crossfade
        guest.sendJson("type" to "ACTION_SET_CROSSFADE", "ms" to 9000)
        assertFalse(guest.receives("CROSSFADE_CHANGED", 400))
        host.sendJson("type" to "ACTION_SET_CROSSFADE", "ms" to 600)
        assertEquals(600, guest.await("CROSSFADE_CHANGED")!!.getInt("ms"))

        host.sendJson("type" to "ACTION_ADD_TRACK", "trackUrl" to "http://h/a.mp3")
        host.sendJson("type" to "ACTION_ADD_TRACK", "trackUrl" to "http://h/b.mp3")
        assertNotNull(host.await("PREPARE"))
        host.sendJson("type" to "ACTION_TRACK_READY", "trackUrl" to "http://h/a.mp3", "durationMs" to 3000L)
        guest.sendJson("type" to "ACTION_TRACK_READY", "trackUrl" to "http://h/a.mp3")
        val startA = host.await("PLAY")!!.getLong("startedAtMs")

        val playB = host.await("PLAY", 6000)!!
        assertEquals("http://h/b.mp3", playB.getString("trackUrl"))
        // B starts 600 ms before A ends, and clients learn which track to fade out
        assertEquals(startA + 3000L - 600L, playB.getLong("startedAtMs"))
        assertEquals(600, playB.getInt("crossfadeMs"))
        assertEquals("http://h/a.mp3", playB.getString("fromTrackUrl"))
    }

    @Test
    fun calibrationPlanGivesEveryDeviceItsOwnChirpSlots() {
        val host = hostWithSession()
        val guest = connect(browser = false)
        assertNotNull(guest.await("LISTS_UPDATE"))
        guest.sendJson("type" to "ACTION_CALIBRATION_START")
        assertFalse(guest.receives("CALIBRATION_START", 400)) // guests can't start it

        host.sendJson("type" to "ACTION_CALIBRATION_START")
        val plan = host.await("CALIBRATION_PLAN")!!
        val slots = plan.getJSONArray("slots")
        assertEquals(4, slots.length()) // 2 devices × 2 rounds
        assertTrue(slots.getJSONObject(0).getBoolean("isHost")) // host's chirp is the reference, first
        // Music off everywhere first, then each device gets its own chirp times
        val untilMs = guest.await("CALIBRATION_START")!!.getLong("untilMs")
        val guestTimes = guest.await("CALIBRATION_CHIRP")!!.getJSONArray("times")
        assertEquals(2, guestTimes.length())
        val all = (0 until slots.length()).map { slots.getJSONObject(it).getLong("atMs") }.sorted()
        // No two chirps overlap: ≥ 1 s apart
        all.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 1000) }
        assertTrue(untilMs > all.last())

        val guestId = (0 until slots.length()).map { slots.getJSONObject(it) }.first { !it.getBoolean("isHost") }.getString("id")
        host.send(org.json.JSONObject().put("type", "ACTION_CALIBRATION_RESULT")
            .put("results", org.json.JSONArray().put(org.json.JSONObject().put("id", guestId).put("deltaMs", 214))).toString())
        assertEquals(214L, guest.await("CALIBRATION_APPLY")!!.getLong("deltaMs"))
    }

    @Test
    fun clickTestIsHostOnlyAndReachesLateJoiners() {
        val host = hostWithSession()
        val guest = connect(browser = false)
        assertNotNull(guest.await("LISTS_UPDATE"))
        guest.sendJson("type" to "ACTION_SYNC_TEST", "enabled" to true)
        assertFalse(host.receives("SYNC_TEST", 400)) // guests can't start it

        host.sendJson("type" to "ACTION_SYNC_TEST", "enabled" to true)
        assertTrue(guest.await("SYNC_TEST")!!.getBoolean("enabled"))
        // Someone joining in the middle of the test clicks along right away
        val late = connect(browser = false)
        assertTrue(late.await("SYNC_TEST")!!.getBoolean("enabled"))

        host.sendJson("type" to "ACTION_SYNC_TEST", "enabled" to false)
        assertFalse(guest.await("SYNC_TEST")!!.getBoolean("enabled"))
    }

    @Test
    fun announcementEndsWhenSpeakerDisconnects() {
        val host = hostWithSession()
        val guest = connect(browser = false)
        guest.sendJson("type" to "ACTION_JOIN", "username" to "Max")
        assertNotNull(host.await("LISTS_UPDATE"))

        guest.sendJson("type" to "VOICE_START", "speaker" to "Max")
        assertNotNull(host.await("VOICE_START"))
        guest.closeBlocking()
        assertNotNull(host.await("VOICE_END"))
    }
}

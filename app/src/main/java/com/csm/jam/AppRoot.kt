package com.csm.jam

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.palette.graphics.Palette
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Root of the UI: start screen or session, plus every overlay, sheet and system launcher. */
@Composable
fun AppRoot(activity: MainActivity) {
    val session = remember { JamSession.get(activity) }
    val engine = remember { PlaybackEngine.get(activity) }
    val device = remember { DeviceUiState() }
    var showFullPlayer by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var showWrapped by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<DiscoveredSession>>(emptyList()) }
    val connected = session.isConnected

    fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    // ── Discovery only while looking for a session ─────────────────────────
    DisposableEffect(connected) {
        if (!connected) SessionDiscoveryManager.startDiscovery(activity) { list -> activity.runOnUiThread { discovered = list } }
        else SessionDiscoveryManager.stopDiscovery()
        onDispose { SessionDiscoveryManager.stopDiscovery() }
    }

    // ── Kicked / declined / host ended the session ─────────────────────────
    val terminated = session.terminatedReasonState.value
    LaunchedEffect(terminated) {
        terminated?.let {
            showFullPlayer = false
            showWrapped = false
            session.handleTermination(it)
        }
    }

    // ── Deep link / NFC / QR join ──────────────────────────────────────────
    val pendingJoin = activity.pendingJoinIp
    LaunchedEffect(pendingJoin) {
        val ip = pendingJoin ?: return@LaunchedEffect
        activity.pendingJoinIp = null
        if (session.isConnected && session.connectedHostIp == ip) {
            toast("Bereits mit dieser Session verbunden")
            return@LaunchedEffect
        }
        if (session.isConnected) session.leave()
        session.join(ip)
    }

    // ── Android 13+: notification permission for the session notification ─
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(connected) {
        if (connected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ── Clock + WLAN info for the session tab ──────────────────────────────
    var wallClockMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var wifi by remember { mutableStateOf(getWifiNetworkInfo(activity)) }
    LaunchedEffect(connected) {
        while (isActive && connected) {
            wallClockMs = System.currentTimeMillis()
            wifi = getWifiNetworkInfo(activity)
            delay(1000)
        }
    }

    // ── Colors from the cover (full player, Wrapped image, status bar) ─────
    val defaultGradient = listOf(Color(0xFF2A1B3D), Jam.Bg)
    var gradient by remember { mutableStateOf(defaultGradient) }
    val artworkUrl = engine.currentTrack?.let { session.trackArtworks[it] }
    LaunchedEffect(artworkUrl) {
        if (artworkUrl.isNullOrEmpty()) {
            gradient = defaultGradient
            return@LaunchedEffect
        }
        gradient = withContext(Dispatchers.IO) {
            try {
                val request = coil.request.ImageRequest.Builder(activity)
                    .data(resolveArtwork(artworkUrl))
                    .allowHardware(false) // Palette needs a software bitmap
                    .size(128, 128)
                    .build()
                val bitmap = ((coil.ImageLoader(activity).execute(request) as? coil.request.SuccessResult)?.drawable
                    as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (bitmap == null) defaultGradient else {
                    val palette = Palette.from(bitmap).generate()
                    val main = palette.darkVibrantSwatch?.rgb ?: palette.vibrantSwatch?.rgb ?: palette.darkMutedSwatch?.rgb
                    if (main == null) defaultGradient else listOf(Color(main), Jam.Bg)
                }
            } catch (_: Exception) {
                defaultGradient
            }
        }
    }
    LaunchedEffect(gradient, device.batterySaver, showFullPlayer) {
        try {
            val top = if (device.batterySaver) Color.Black else if (showFullPlayer) gradient.first() else Jam.Bg
            activity.window.statusBarColor = android.graphics.Color.argb(255, (top.red * 255).toInt(), (top.green * 255).toInt(), (top.blue * 255).toInt())
            activity.window.navigationBarColor = if (device.batterySaver) android.graphics.Color.BLACK else android.graphics.Color.argb(255, 10, 10, 11)
        } catch (_: Exception) {}
    }

    // ── System launchers ───────────────────────────────────────────────────
    // OpenMultipleDocuments (not GetContent) so the read permission can be persisted for the file server
    val pickSongs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) session.addTracks(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) session.chooseFallbackFolder(uri)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The finger is long gone by now – only arm it, the next press/tap uses the mic
        toast(if (granted) "Mikro freigegeben – jetzt nochmal drücken" else "Ohne Mikrofon geht das leider nicht")
    }
    var pendingDownload by remember { mutableStateOf<String?>(null) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { dest ->
        val source = pendingDownload
        if (dest != null && source != null) downloadTrack(activity, source, dest)
    }
    val scanQr = rememberLauncherForActivityResult(ScanContract()) { result ->
        val content = result.contents ?: return@rememberLauncherForActivityResult
        val host = parseHostFromQr(content)
        if (host != null) session.join(host) else toast("Ungültiger CSM Jam QR-Code")
    }

    val actions = remember {
        SessionActions(
            addSongs = { pickSongs.launch(arrayOf("audio/*")) },
            pickFolder = { pickFolder.launch(null) },
            withMic = { block ->
                if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) block()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
            openPlayer = { showFullPlayer = true },
            openSync = { showSync = true },
            downloadTrack = { url, title ->
                pendingDownload = url
                saveFile.launch("$title.mp3")
            },
            share = { showShare = true },
            leave = { showWrapped = true }
        )
    }

    val ownNetworkPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (LocalHotspot.hasPermissions(activity)) {
            session.hostWithOwnNetwork { showShare = true }
        } else {
            toast("Ohne die Berechtigung kann Android kein eigenes WLAN öffnen")
        }
    }
    val hostOwnNetwork = {
        when {
            LocalHotspot.locationSwitchRequired(activity) ->
                toast("Bitte kurz den Standort einschalten – Android braucht das für ein eigenes WLAN")
            !LocalHotspot.hasPermissions(activity) -> ownNetworkPermissions.launch(LocalHotspot.requiredPermissions())
            else -> session.hostWithOwnNetwork { showShare = true }
        }
    }

    val resumable = remember(connected) {
        if (!connected) SessionStore.load(activity, getRawIpAddress(activity)) else null
    }

    Box(modifier = Modifier.fillMaxSize().background(Jam.Bg)) {
        if (!connected) {
            StartScreen(
                session = session,
                discovered = discovered,
                resumable = resumable,
                onDiscardResume = { SessionStore.clear(activity) },
                onHostOwnNetwork = hostOwnNetwork,
                onScanQr = {
                    scanQr.launch(ScanOptions().apply {
                        setPrompt("CSM Jam QR-Code scannen")
                        setBeepEnabled(false)
                        setOrientationLocked(true)
                        setCameraId(0)
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    })
                }
            )
        } else {
            SessionScreen(session, engine, device, actions, wallClockMs, wifi)
        }

        AnimatedVisibility(
            visible = connected && showFullPlayer,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            BackHandler { showFullPlayer = false }
            FullPlayerScreen(session, engine, gradient, device.batterySaver, onClose = { showFullPlayer = false })
        }

        if (connected && !session.isHost && session.awaitingApproval) {
            WaitingForApproval(onCancel = { session.leave() })
        }
    }

    // ── Sheets & dialogs ───────────────────────────────────────────────────
    if (connected && session.isHost && session.joinRequests.isNotEmpty()) JoinRequestSheet(session)
    if (connected) CalibrationSheet(session)
    if (showShare && connected) ShareSheet(session, onDismiss = { showShare = false })
    if (showWrapped && connected) {
        WrappedDialog(
            session, wallClockMs, gradient,
            onLeave = {
                showWrapped = false
                showFullPlayer = false
                device.batterySaver = false
                session.leave()
            },
            onDismiss = { showWrapped = false }
        )
    }
    device.selectedParticipant?.let { p -> ParticipantDialog(p, onDismiss = { device.selectedParticipant = null }) }
    if (device.showPartyLightPin) {
        PartyLightPinDialog(
            onDismiss = { device.showPartyLightPin = false },
            onUnlocked = {
                device.showPartyLightPin = false
                device.partyLightUnlocked = true
                toast("✧ Party-Licht freigeschaltet ✧")
            }
        )
    }
    if (showSync && connected) SyncSheet(engine, onDismiss = { showSync = false })
    if (device.showPartyLightModes) PartyLightModeSheet(session, onDismiss = { device.showPartyLightModes = false })
}

/** Guests can keep a copy of a queued song. */
private fun downloadTrack(activity: MainActivity, sourceUrl: String, dest: Uri) {
    Thread {
        val ok = try {
            java.net.URL(sourceUrl).openStream().use { input ->
                activity.contentResolver.openOutputStream(dest)?.use { input.copyTo(it) } != null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
        activity.runOnUiThread {
            Toast.makeText(activity, if (ok) "Download abgeschlossen!" else "Download fehlgeschlagen", Toast.LENGTH_SHORT).show()
        }
    }.start()
}

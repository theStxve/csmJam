@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.csm.jam

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyColumnState

/** Everything the session screens need from the Activity (launchers, permissions, navigation). */
class SessionActions(
    val addSongs: () -> Unit,
    val pickFolder: () -> Unit,
    /** Runs the block once the microphone permission is granted (asks for it otherwise). */
    val withMic: (() -> Unit) -> Unit,
    val openPlayer: () -> Unit,
    /** This device's offset sheet (buttons, ruler) – also used during the click test. */
    val openSync: () -> Unit,
    val downloadTrack: (url: String, title: String) -> Unit,
    val share: () -> Unit,
    val leave: () -> Unit
)

/** UI-only state of this device that isn't part of the session (battery saver, hidden party light). */
class DeviceUiState {
    var batterySaver by mutableStateOf(false)
    var partyLightUnlocked by mutableStateOf(false)
    var showPartyLightPin by mutableStateOf(false)
    var showPartyLightModes by mutableStateOf(false)
    var selectedParticipant by mutableStateOf<SessionParticipant?>(null)
}

data class SessionParticipant(
    val id: String,
    val name: String,
    val isHost: Boolean,
    val canMegaphone: Boolean,
    val canControl: Boolean,
    val isWeb: Boolean = false
)

fun parseParticipant(raw: String): SessionParticipant = try {
    val json = org.json.JSONObject(raw)
    SessionParticipant(
        id = json.getString("id"),
        name = json.getString("name"),
        isHost = json.optBoolean("isHost", false),
        canMegaphone = json.optBoolean("canMegaphone", true),
        canControl = json.optBoolean("canControl", false),
        isWeb = json.optBoolean("isWeb", false)
    )
} catch (_: Exception) {
    SessionParticipant(id = raw, name = raw, isHost = raw.contains("𓆩🜲𓆪"), canMegaphone = true, canControl = false)
}

/** "Title - Artist" (the app's convention) → title, artist. */
fun splitTitle(full: String): Pair<String, String?> {
    val parts = full.split(" - ", limit = 2)
    return parts[0] to parts.getOrNull(1)
}

/** The host shows its own covers straight from memory instead of fetching them over HTTP. */
fun resolveArtwork(url: String?): Any? {
    if (url == null) return null
    if (MainActivity.isHostMode && url.contains("/art/")) {
        MainActivity.localFileServer?.getArtwork(url.substringAfterLast("/"))?.let { return it }
    }
    return url
}

@Composable
fun rememberPlaybackPosition(engine: PlaybackEngine): Pair<Long, Long> {
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    val player = engine.activePlayerState
    LaunchedEffect(player) {
        while (true) {
            player?.let {
                if (it.playbackState == androidx.media3.common.Player.STATE_READY || it.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    position = it.currentPosition
                    duration = it.duration.coerceAtLeast(0L)
                }
            }
            delay(250)
        }
    }
    return position to duration
}

fun formatTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0L)) / 1000
    return String.format("%d:%02d", s / 60, s % 60)
}

// ── Screen ────────────────────────────────────────────────────────────────

@Composable
fun SessionScreen(session: JamSession, engine: PlaybackEngine, device: DeviceUiState, actions: SessionActions, wallClockMs: Long, wifi: WifiNetworkInfo) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val bottomMode = session.playerAtBottom
    val currentTrack = engine.currentTrack

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (device.batterySaver) Color.Black else Jam.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SessionHeader(session, engine, device)
            Segmented(
                options = listOf("Warteschlange", "Session"),
                selected = pagerState.currentPage,
                onSelect = { scope.launch { pagerState.animateScrollToPage(it) } }
            )
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            if (page == 0) QueueTab(session, engine, actions, bottomMode)
            else SessionTab(session, engine, device, actions, wallClockMs, wifi)
        }
        if (bottomMode && currentTrack != null) {
            MiniPlayerBar(session, engine, actions)
        }
    }
}

@Composable
private fun SessionHeader(session: JamSession, engine: PlaybackEngine, device: DeviceUiState) {
    var tapCount by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }
    val sessionName = MainActivity.localServer?.sessionName?.takeIf { session.isHost } ?: "Jam Session"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                sessionName,
                style = Jam.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Party light: 7 quick taps on the title (no ripple, no hint) → PIN
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures {
                        val now = System.currentTimeMillis()
                        tapCount = if (now - lastTap < 600) tapCount + 1 else 1
                        lastTap = now
                        if (tapCount >= 7) {
                            tapCount = 0
                            if (!device.partyLightUnlocked) device.showPartyLightPin = true
                        }
                    }
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Jam.Accent))
                Spacer(Modifier.width(6.dp))
                Text(
                    buildAnnotatedString {
                        append(if (session.isHost) "Host" else "Gast")
                        append(" · ${session.participants.size} Geräte")
                        if (engine.rttMs > 0) {
                            append(" · ")
                            withStyle(SpanStyle(fontFamily = Jam.Mono)) { append("${engine.rttMs} ms") }
                        }
                    },
                    fontSize = 12.sp, color = Jam.Muted
                )
            }
        }
        PlayerLayoutToggle(atBottom = session.playerAtBottom) { session.playerAtBottom = it }
    }
}

/** Per device: player card on top or Spotify-style bar at the bottom. */
@Composable
private fun PlayerLayoutToggle(atBottom: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Jam.Surface)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        listOf(false to Icons.Filled.ViewAgenda, true to Icons.Filled.CallToAction).forEach { (bottom, icon) ->
            val selected = atBottom == bottom
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (selected) Jam.Surface3 else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onChange(bottom) }
                    .semantics { contentDescription = if (bottom) "Player unten (wie Spotify)" else "Player oben" },
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = if (selected) Jam.Text else Jam.Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ── Queue tab ─────────────────────────────────────────────────────────────

@Composable
private fun QueueTab(session: JamSession, engine: PlaybackEngine, actions: SessionActions, bottomMode: Boolean) {
    val canControl = session.isHost || session.guestsCanControl
    val canMegaphone = session.isHost || session.myCanMegaphone
    val queue = session.queue
    val currentTrack = engine.currentTrack

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(2.dp))

        // Click test: music is paused for everybody, each device tunes its own offset by ear
        AnimatedVisibility(visible = engine.syncTestActive) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Jam.AccentSoft).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Jam.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Klick-Test läuft", color = Jam.Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Stell ein, bis alle Klicks gleichzeitig kommen", color = Jam.Muted, fontSize = 12.sp)
                }
                TextButton(onClick = actions.openSync) { Text("Einstellen", color = Jam.Accent, fontWeight = FontWeight.Bold) }
            }
        }

        AnimatedVisibility(visible = session.activeVoiceSpeaker != null) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xE6E53935)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Campaign, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("${session.activeVoiceSpeaker} macht eine Durchsage …", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        if (!bottomMode && currentTrack != null) {
            NowPlayingCard(session, engine, canControl, onOpen = actions.openPlayer)
            if (canMegaphone) PushToTalkButton(session, actions, label = "Gedrückt halten für Durchsage", modifier = Modifier.fillMaxWidth())
        }
        if (bottomMode && (canControl || canMegaphone)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (canControl) {
                    JamButton(
                        "Songs", onClick = actions.addSongs, modifier = Modifier.weight(1f), height = 48.dp,
                        icon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }
                if (canMegaphone) PushToTalkButton(session, actions, label = "Durchsage", modifier = Modifier.weight(1f), height = 48.dp)
            }
        }

        session.uploadStatus?.let { status ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Lade zum Host hoch: $status", color = Jam.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(
                    progress = { session.uploadProgress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Jam.Accent,
                    trackColor = Jam.Track
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(if (bottomMode) "Warteschlange · ${queue.size}" else "Als Nächstes", modifier = Modifier.weight(1f))
            if (engine.crossfadeMs > 0) {
                Text(
                    buildAnnotatedString {
                        append("Crossfade ")
                        withStyle(SpanStyle(fontFamily = Jam.Mono, color = Jam.Text)) { append("${engine.crossfadeMs / 1000} s") }
                    },
                    fontSize = 12.sp, color = Jam.Muted
                )
            }
        }

        val items = if (bottomMode) queue.toList() else queue.filter { it != currentTrack }
        if (items.isEmpty()) {
            Text(
                if (canControl) "Noch nichts in der Warteschlange – füg was hinzu!" else "Noch nichts in der Warteschlange.",
                color = Jam.Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        val listState = rememberLazyListState()
        val reorderState = rememberReorderableLazyColumnState(listState) { from, to ->
            // Indices in the full queue (top mode hides the playing track)
            val fromUrl = items.getOrNull(from.index) ?: return@rememberReorderableLazyColumnState
            val toUrl = items.getOrNull(to.index) ?: return@rememberReorderableLazyColumnState
            val fromIdx = queue.indexOf(fromUrl)
            val toIdx = queue.indexOf(toUrl)
            if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyColumnState
            queue.add(toIdx, queue.removeAt(fromIdx))
            MainActivity.syncClient?.sendReorderQueue(fromIdx, toIdx)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(items, key = { _, url -> url }) { _, url ->
                ReorderableItem(reorderState, key = url) { isDragging ->
                    QueueRow(session, url, isCurrent = url == currentTrack, canControl = canControl, isDragging = isDragging, actions = actions, handleModifier = Modifier.draggableHandle())
                }
            }
        }

        if (!bottomMode && canControl) {
            JamButton(
                "Songs hinzufügen", onClick = actions.addSongs, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                icon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
        }
    }
}

@Composable
private fun QueueRow(
    session: JamSession,
    url: String,
    isCurrent: Boolean,
    canControl: Boolean,
    isDragging: Boolean,
    actions: SessionActions,
    handleModifier: Modifier
) {
    val (title, artist) = splitTitle(session.trackTitles[url] ?: extractFileNameFromUrl(url))
    val by = session.trackAddedBy[url]
    val elevation by animateFloatAsState(if (isDragging) 12f else 0f, label = "drag")
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && canControl && !isCurrent) {
                MainActivity.syncClient?.sendRemoveTrack(url)
                true
            } else false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val active = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
            Box(
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(if (active) Color(0xFFB3261E) else Color.Transparent),
                contentAlignment = Alignment.CenterEnd
            ) { Icon(Icons.Filled.Delete, contentDescription = "Entfernen", tint = Color.White, modifier = Modifier.padding(end = 16.dp)) }
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { shadowElevation = elevation }
                .clip(RoundedCornerShape(14.dp))
                .background(if (isCurrent) Jam.AccentSoft else Jam.Bg)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Cover(resolveArtwork(session.trackArtworks[url]), size = 44, radius = 10)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (isCurrent) Jam.Accent else Jam.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(artist, by?.let { "von $it" }).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Jam.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (isCurrent) {
                Text("LÄUFT", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = Jam.Accent)
            } else {
                if (!session.isHost) {
                    IconButton(onClick = { actions.downloadTrack(url, title) }) {
                        Icon(Icons.Filled.Download, contentDescription = "Herunterladen", tint = Jam.Faint, modifier = Modifier.size(18.dp))
                    }
                }
                if (canControl) {
                    Icon(Icons.Filled.DragHandle, contentDescription = "Ziehen zum Sortieren", tint = Jam.Faint, modifier = handleModifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
fun Cover(model: Any?, size: Int, radius: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(radius.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A2D5C), Jam.Surface2))),
        contentAlignment = Alignment.Center
    ) {
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Jam.Muted, modifier = Modifier.size((size / 2.4f).dp))
        }
    }
}

@Composable
fun SyncBadge(engine: PlaybackEngine) {
    if (!engine.isPlaying) return
    val diff = engine.syncDiffMs
    val inSync = kotlin.math.abs(diff) <= 25
    Chip(
        if (inSync) "Synchron" else "${if (diff > 0) "+" else ""}$diff ms",
        color = if (inSync) Jam.Accent else Jam.Warn,
        background = if (inSync) Jam.AccentSoft else Color(0x26FFB86B),
        bold = true
    )
}

@Composable
private fun NowPlayingCard(session: JamSession, engine: PlaybackEngine, canControl: Boolean, onOpen: () -> Unit) {
    val url = engine.currentTrack ?: return
    val (title, artist) = splitTitle(session.trackTitles[url] ?: extractFileNameFromUrl(url))
    val (position, duration) = rememberPlaybackPosition(engine)
    JamCard(modifier = Modifier.clickable(onClick = onOpen)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(resolveArtwork(session.trackArtworks[url]), size = 84, radius = 14)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Jam.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    artist?.let { Text(it, fontSize = 14.sp, color = Jam.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    session.trackAddedBy[url]?.let { Text("◇ von $it", fontSize = 12.sp, color = Jam.Muted) }
                }
                SyncBadge(engine)
            }
            ProgressLine(position, duration)
            TransportControls(session, engine, canControl, compact = true)
        }
    }
}

@Composable
fun ProgressLine(position: Long, duration: Long, color: Color = Jam.Text) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = color,
            trackColor = Jam.Track
        )
        Row {
            Text(formatTime(position), style = Jam.Number, fontSize = 11.sp, color = Jam.Muted, modifier = Modifier.weight(1f))
            Text(formatTime(duration), style = Jam.Number, fontSize = 11.sp, color = Jam.Muted)
        }
    }
}

@Composable
fun TransportControls(session: JamSession, engine: PlaybackEngine, canControl: Boolean, compact: Boolean) {
    val client = MainActivity.syncClient
    val playSize = if (compact) 64.dp else 76.dp
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { client?.sendSetRepeatSingle(!engine.isRepeatSingle) }, enabled = canControl) {
            Icon(Icons.Filled.RepeatOne, contentDescription = "Song wiederholen", tint = if (engine.isRepeatSingle) Jam.Accent else Jam.Muted)
        }
        IconButton(onClick = { client?.sendSkipPrev() }, enabled = canControl, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Zurück", tint = if (canControl) Jam.Text else Jam.Faint, modifier = Modifier.size(30.dp))
        }
        Surface(
            onClick = { if (engine.isPlaying) client?.sendPauseAction() else client?.sendPlayAction(null) },
            enabled = canControl,
            shape = CircleShape,
            color = if (canControl) Jam.Text else Jam.Surface3,
            contentColor = Jam.Bg,
            modifier = Modifier.size(playSize)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (engine.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (engine.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(if (compact) 30.dp else 36.dp)
                )
            }
        }
        IconButton(onClick = { client?.sendSkipNext() }, enabled = canControl, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Weiter", tint = if (canControl) Jam.Text else Jam.Faint, modifier = Modifier.size(30.dp))
        }
        IconButton(onClick = { client?.sendShuffle() }, enabled = canControl) {
            Icon(Icons.Filled.Shuffle, contentDescription = "Mischen", tint = Jam.Muted)
        }
    }
}

/**
 * Megaphone: own press gesture instead of Button+isPressed – inside the HorizontalPager the slightest
 * sideways finger movement would otherwise cancel the press and cut the announcement off.
 */
@Composable
private fun PushToTalkButton(session: JamSession, actions: SessionActions, label: String, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 52.dp) {
    val transmitting = session.isTransmitting
    val onDown by rememberUpdatedState { actions.withMic { session.startTransmit() } }
    val onUp by rememberUpdatedState { session.stopTransmit() }
    DisposableEffect(Unit) { onDispose { onUp() } }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (transmitting) Color(0xFFE53935) else Jam.Surface,
        contentColor = Jam.Text,
        border = if (transmitting) null else BorderStroke(1.dp, Color(0x1AFFFFFF)),
        modifier = modifier
            .height(height)
            .semantics { contentDescription = "Megafon: gedrückt halten zum Sprechen" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    onDown()
                    try {
                        do {
                            val event = awaitPointerEvent()
                            // Consume everything so the pager never takes over the gesture
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        onUp()
                    }
                }
            }
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(if (transmitting) Icons.Filled.Mic else Icons.Filled.Campaign, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (transmitting) "Live – loslassen zum Beenden" else label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
        }
    }
}

/** Spotify-style bar above the navigation bar; tap opens the full player. */
@Composable
private fun MiniPlayerBar(session: JamSession, engine: PlaybackEngine, actions: SessionActions) {
    val url = engine.currentTrack ?: return
    val (title, artist) = splitTitle(session.trackTitles[url] ?: extractFileNameFromUrl(url))
    val (position, duration) = rememberPlaybackPosition(engine)
    val canControl = session.isHost || session.guestsCanControl
    val inSync = kotlin.math.abs(engine.syncDiffMs) <= 25
    Surface(
        onClick = actions.openPlayer,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF2A1B3D),
        contentColor = Jam.Text,
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
    ) {
        Column {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(resolveArtwork(session.trackArtworks[url]), size = 44, radius = 10)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        artist?.let { Text(it, fontSize = 12.sp, color = Color(0xFFD4D4D8), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                        if (engine.isPlaying) {
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.size(5.dp).clip(CircleShape).background(if (inSync) Jam.Accent else Jam.Warn))
                            Spacer(Modifier.width(6.dp))
                            Text(if (inSync) "synchron" else "${engine.syncDiffMs} ms", fontSize = 12.sp, color = if (inSync) Jam.Accent else Jam.Warn)
                        }
                    }
                }
                if (canControl) {
                    IconButton(onClick = { if (engine.isPlaying) MainActivity.syncClient?.sendPauseAction() else MainActivity.syncClient?.sendPlayAction(null) }) {
                        Icon(if (engine.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (engine.isPlaying) "Pause" else "Play", modifier = Modifier.size(28.dp))
                    }
                    IconButton(onClick = { MainActivity.syncClient?.sendSkipNext() }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Weiter", modifier = Modifier.size(26.dp))
                    }
                }
            }
            LinearProgressIndicator(
                progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Jam.Text,
                trackColor = Color(0x26FFFFFF)
            )
        }
    }
}

// ── Session tab ───────────────────────────────────────────────────────────

private val CROSSFADE_OPTIONS = listOf(0, 3000, 6000, 10000)

@Composable
private fun SessionTab(session: JamSession, engine: PlaybackEngine, device: DeviceUiState, actions: SessionActions, wallClockMs: Long, wifi: WifiNetworkInfo) {
    val client = MainActivity.syncClient
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Crossfade: host option, everybody sees it
        if (session.isHost) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Übergang zwischen Songs", style = Jam.Label.copy(letterSpacing = 0.sp))
                val selected = CROSSFADE_OPTIONS.indexOfFirst { it == engine.crossfadeMs }.let { if (it < 0) 0 else it }
                Segmented(
                    options = listOf("Aus", "3 s", "6 s", "10 s"),
                    selected = selected,
                    onSelect = { client?.sendSetCrossfade(CROSSFADE_OPTIONS[it]) },
                    mono = true
                )
                Text("Crossfade gilt für alle Geräte. Nur du kannst ihn ändern.", fontSize = 12.sp, color = Jam.Muted)
            }

            JamCard {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Jam.AccentSoft), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Jam.Accent, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Kalibrieren", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                "Automatisch: dein Mikro misst jede Box. Klick-Test: alle spielen den gleichen Klick, jeder stellt nach Gehör nach.",
                                fontSize = 12.sp, color = Jam.Muted, lineHeight = 16.sp
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        JamButton(
                            "Automatisch", onClick = { actions.withMic { session.startAutoCalibration() } },
                            modifier = Modifier.weight(1f), height = 42.dp, enabled = !engine.syncTestActive
                        )
                        JamButton(
                            if (engine.syncTestActive) "Klick-Test beenden" else "Klick-Test",
                            onClick = { client?.sendSyncTest(!engine.syncTestActive) },
                            primary = false, modifier = Modifier.weight(1f), height = 42.dp
                        )
                    }
                }
            }
        } else if (engine.crossfadeMs > 0) {
            Text("Übergang: ${engine.crossfadeMs / 1000} s Crossfade (vom Host)", fontSize = 12.sp, color = Jam.Muted)
        }

        JamCard {
            Column {
                if (session.isHost) {
                    SwitchRow("Gäste dürfen steuern", "Play, Skip & Songs hinzufügen", session.guestsCanControl) {
                        session.guestsCanControl = it
                        client?.sendSetGuestPermissions(it)
                    }
                    Divider()
                    SwitchRow("App-Gäste bestätigen", "Browser-Gäste fragen immer an", session.requireAppApproval) {
                        session.requireAppApproval = it
                        client?.sendSetAppApproval(it)
                    }
                    Divider()
                }
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Autoplay-Ordner", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            when {
                                session.isScanningFallback -> "Ordner wird durchsucht …"
                                session.fallbackFolder != null -> "${session.fallbackTracks.size} Songs · springt ein, wenn die Warteschlange leer wird"
                                else -> "Kein Ordner gewählt"
                            },
                            fontSize = 12.sp, color = Jam.Muted
                        )
                    }
                    if (session.fallbackFolder != null) {
                        TextButton(onClick = { session.forgetFallbackFolder() }) { Text("Entfernen", color = Jam.Faint, fontSize = 12.sp) }
                    }
                    SmallOutlineButton(if (session.fallbackFolder != null) "Ändern" else "Wählen", actions.pickFolder)
                }
            }
        }

        // This device
        JamCard {
            Column {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (wifi.level) { 4, 3 -> Icons.Filled.Wifi; 2 -> Icons.Filled.Wifi2Bar; 1 -> Icons.Filled.Wifi1Bar; else -> Icons.Filled.WifiOff },
                        contentDescription = "WLAN-Signal", tint = Jam.Accent, modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(wifi.ssid, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            buildAnnotatedString {
                                append("Ping ")
                                withStyle(SpanStyle(fontFamily = Jam.Mono)) { append(if (engine.rttMs > 0) "${engine.rttMs} ms" else "–") }
                                append(" · Offset ")
                                withStyle(SpanStyle(fontFamily = Jam.Mono)) { append("${engine.calibrationMs.toInt()} ms") }
                            },
                            fontSize = 12.sp, color = Jam.Muted
                        )
                    }
                    SmallOutlineButton("Neu takten") {
                        client?.pingServer(resetHistory = true)
                        client?.sendForceSync()
                        engine.resync()
                    }
                }
                Divider()
                SwitchRow("Akku-Sparmodus", "AMOLED-Schwarz, keine Farbverläufe", device.batterySaver) { device.batterySaver = it }
            }
        }

        // Stats
        val elapsed = ((wallClockMs - session.sessionStartTimeMsState.value).coerceAtLeast(0L)) / 1000
        val topDj = (session.playedLog.map { it.second }.filter { it.isNotBlank() }.ifEmpty { session.trackAddedBy.values.toList() })
            .map { it.removeSuffix(" (Autoplay)").removeSuffix(" (Web)") }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Laufzeit", if (elapsed >= 3600) String.format("%d:%02d h", elapsed / 3600, (elapsed % 3600) / 60) else String.format("%d min", elapsed / 60), Modifier.weight(1f))
            StatTile("Gespielt", "${session.totalSongsPlayedState.value}", Modifier.weight(1f))
            StatTile("Top DJ", topDj?.key ?: "–", Modifier.weight(1f), mono = false)
        }

        if (device.partyLightUnlocked) {
            JamCard {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { session.setPartyLight(!session.isPartyLightActive) }) {
                        Icon(
                            if (session.isPartyLightActive) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = "Party-Licht", tint = Color(0xFFFFD600)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Party-Licht", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Modus: ${session.partyLightMode.label}", fontSize = 12.sp, color = Color(0xFFFFD600))
                    }
                    TextButton(onClick = {
                        device.partyLightUnlocked = false
                        if (session.isPartyLightActive) {
                            session.isPartyLightActive = false
                            session.partyLight.stop()
                            if (session.isHost) client?.sendSetPartyLight(false)
                        }
                    }) { Text("Verstecken", color = Jam.Faint, fontSize = 12.sp) }
                    SmallOutlineButton("Modus") { device.showPartyLightModes = true }
                }
            }
        }

        SectionLabel("Dabei · ${session.participants.size}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            session.participants.map { parseParticipant(it) }.sortedByDescending { it.isHost }.forEach { p ->
                val label = p.name + if (p.canControl && !p.isHost) " · Co-DJ" else ""
                Chip(
                    label,
                    color = if (p.isHost) Jam.Accent else Jam.Text,
                    background = if (p.isHost) Jam.AccentSoft else Jam.Surface,
                    modifier = if (session.isHost && !p.isHost) Modifier.clip(RoundedCornerShape(50)).clickable { device.selectedParticipant = p } else Modifier
                )
            }
        }
        if (session.isHost) Text("Tippe auf einen Gast, um Rechte zu ändern oder ihn zu entfernen.", fontSize = 11.sp, color = Jam.Faint)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (session.isHost || session.guestsCanControl) {
                JamButton("Teilen & QR", onClick = actions.share, primary = false, modifier = Modifier.weight(1f), height = 50.dp)
            }
            JamButton(
                if (session.isHost) "Session beenden" else "Verlassen",
                onClick = actions.leave, danger = true, primary = false, modifier = Modifier.weight(1f), height = 50.dp
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, fontSize = 12.sp, color = Jam.Muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Jam.Bg, checkedTrackColor = Jam.Accent,
                uncheckedThumbColor = Jam.Muted, uncheckedTrackColor = Jam.Track, uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(Color(0x0FFFFFFF)))
}

@Composable
fun SmallOutlineButton(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = Color.Transparent, contentColor = Jam.Text, border = BorderStroke(1.dp, Color(0x1FFFFFFF))) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier, mono: Boolean = true) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(Jam.Surface).padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontFamily = if (mono) Jam.Mono else Jam.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Jam.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = 11.sp, color = Jam.Muted)
    }
}

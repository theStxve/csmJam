@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.csm.jam

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Full-screen player (tap on the bar / card). Colors follow the cover unless the battery saver is on. */
@Composable
fun FullPlayerScreen(
    session: JamSession,
    engine: PlaybackEngine,
    gradient: List<Color>,
    batterySaver: Boolean,
    onClose: () -> Unit
) {
    val url = engine.currentTrack
    val (title, artist) = splitTitle(url?.let { session.trackTitles[it] ?: extractFileNameFromUrl(it) } ?: "Warten auf den ersten Song")
    val canControl = session.isHost || session.guestsCanControl
    val (position, duration) = rememberPlaybackPosition(engine)
    var showSync by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val top = gradient.firstOrNull() ?: Jam.Surface

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (batterySaver) Brush.verticalGradient(listOf(Color.Black, Color.Black))
                else Brush.verticalGradient(0f to top, 0.45f to top.copy(alpha = 0.35f).compositeOver(Jam.Bg), 1f to Jam.Bg)
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Player schließen", tint = Jam.Text, modifier = Modifier.size(30.dp))
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val name = MainActivity.localServer?.sessionName?.takeIf { session.isHost } ?: "JAM SESSION"
                Text(name.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = Color(0xFFD4D4D8), maxLines = 1)
                val idx = session.queue.indexOf(url)
                if (idx >= 0 && session.queue.size > 1) Text("${idx + 1} / ${session.queue.size}", fontSize = 12.sp, color = Color(0xFFD4D4D8))
            }
            IconButton(onClick = { showSync = true }) {
                Icon(Icons.Filled.Tune, contentDescription = "Sync & Kalibrierung", tint = Color(0xFFD4D4D8))
            }
        }

        Cover(
            resolveArtwork(url?.let { session.trackArtworks[it] }),
            size = 320, radius = 24,
            modifier = Modifier.align(Alignment.CenterHorizontally).shadow(30.dp, RoundedCornerShape(24.dp))
        )

        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontFamily = Jam.Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.5).sp, color = Jam.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                artist?.let { Text(it, fontSize = 16.sp, color = Color(0xFFD4D4D8), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                url?.let { session.trackAddedBy[it] }?.let { Text("◇ von $it", fontSize = 12.sp, color = Jam.Muted, modifier = Modifier.padding(top = 4.dp)) }
            }
            Spacer(Modifier.width(12.dp))
            SyncBadge(engine)
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val shown = dragPosition ?: position.toFloat()
            Slider(
                value = if (duration > 0) shown.coerceIn(0f, duration.toFloat()) else 0f,
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                onValueChange = { dragPosition = it },
                onValueChangeFinished = {
                    dragPosition?.let { MainActivity.syncClient?.sendSeekAction(it.toLong()) }
                    dragPosition = null
                },
                enabled = canControl && duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = Jam.Text, activeTrackColor = Jam.Text, inactiveTrackColor = Color(0x2EFFFFFF),
                    disabledThumbColor = Color.Transparent, disabledActiveTrackColor = Jam.Text, disabledInactiveTrackColor = Color(0x2EFFFFFF)
                )
            )
            Row {
                Text(formatTime(shown.toLong()), style = Jam.Number, fontSize = 11.sp, color = Color(0xFFD4D4D8), modifier = Modifier.weight(1f))
                Text(formatTime(duration), style = Jam.Number, fontSize = 11.sp, color = Color(0xFFD4D4D8))
            }
        }

        TransportControls(session, engine, canControl, compact = false)
        if (!canControl) {
            Text("Steuern kann der Host (oder Co-DJs)", fontSize = 12.sp, color = Jam.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = { showSync = true },
                shape = RoundedCornerShape(12.dp),
                color = Color(0x0AFFFFFF),
                contentColor = Jam.Text,
                border = BorderStroke(1.dp, Color(0x24FFFFFF))
            ) {
                Text(
                    buildAnnotatedString {
                        append("Mein Offset ")
                        withStyle(SpanStyle(fontFamily = Jam.Mono, color = Jam.Accent)) {
                            val v = engine.calibrationMs.toInt()
                            append("${if (v > 0) "+" else ""}$v ms")
                        }
                    },
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Icon(Icons.Filled.QueueMusic, contentDescription = null, tint = Color(0xFFD4D4D8), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Warteschlange", color = Color(0xFFD4D4D8), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }

    if (showSync) SyncSheet(engine, onDismiss = { showSync = false })
}

private fun Color.compositeOver(background: Color): Color {
    val a = alpha
    return Color(red * a + background.red * (1 - a), green * a + background.green * (1 - a), blue * a + background.blue * (1 - a), 1f)
}

/** Plain-language description of an offset (+ = this phone plays earlier). */
fun describeOffset(ms: Int): String = when {
    ms > 0 -> "spielt $ms ms früher als normal"
    ms < 0 -> "spielt ${-ms} ms später als normal"
    else -> "kein Ausgleich"
}

/**
 * This device's audio offset in words instead of +/−: "Ich bin zu früh / zu spät" buttons
 * (hold to repeat), a 5 ms ruler, exact input, and it takes effect immediately.
 */
@Composable
fun SyncSheet(engine: PlaybackEngine, onDismiss: () -> Unit) {
    val value = engine.calibrationMs.toInt()
    JamSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Mein Sync", fontFamily = Jam.Display, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            if (engine.syncTestActive) {
                Text(
                    "Klick-Test läuft: stell ein, bis die Klicks aller Geräte genau gleichzeitig kommen – dann klingt es wie ein einziger Klick.",
                    fontSize = 13.sp, color = Jam.Accent, lineHeight = 18.sp,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Jam.AccentSoft).padding(12.dp)
                )
            }
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (value == 0) "0 ms" else "${if (value > 0) "+" else "−"}${kotlin.math.abs(value)} ms",
                    fontFamily = Jam.Mono, fontSize = 40.sp, color = if (value == 0) Jam.Text else Jam.Accent
                )
                Text(describeOffset(value), fontSize = 13.sp, color = Jam.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RepeatButton("Ich bin zu früh", "später abspielen", Modifier.weight(1f)) {
                    engine.setCalibration(engine.calibrationMs - 5f)
                }
                RepeatButton("Ich bin zu spät", "früher abspielen", Modifier.weight(1f)) {
                    engine.setCalibration(engine.calibrationMs + 5f)
                }
            }
            CalibrationRuler(
                valueMs = value,
                onValueChange = { engine.setCalibration(it.toFloat()) },
                modifier = Modifier.fillMaxWidth().height(88.dp)
            )
            Text(
                "Hörst du dein Handy hinter den anderen? → „zu spät“. Davor? → „zu früh“. Gedrückt halten für große Schritte. Wirkt sofort und gilt nur für dieses Handy.",
                fontSize = 12.sp, color = Jam.Muted, lineHeight = 17.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { engine.setCalibration(0f) }) { Text("Zurücksetzen", color = Jam.Muted) }
                Spacer(Modifier.weight(1f))
                Text("Genau:", fontSize = 13.sp, color = Jam.Muted)
                var input by remember(value) { mutableStateOf(value.toString()) }
                BasicTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        it.replace(Regex("""[^0-9\-]"""), "").toIntOrNull()?.let { v -> engine.setCalibration(v.toFloat()) }
                    },
                    textStyle = TextStyle(color = Jam.Text, fontFamily = Jam.Mono, fontSize = 16.sp, textAlign = TextAlign.End),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    cursorBrush = SolidColor(Jam.Accent),
                    modifier = Modifier.width(64.dp)
                )
                Text(" ms", fontSize = 13.sp, color = Jam.Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetStat("Ping", if (engine.rttMs > 0) "${engine.rttMs} ms" else "–", Modifier.weight(1f))
                SheetStat("Abweichung", if (engine.isPlaying) "${engine.syncDiffMs} ms" else "–", Modifier.weight(1f))
                SheetStat("Crossfade", if (engine.crossfadeMs > 0) "${engine.crossfadeMs / 1000} s" else "aus", Modifier.weight(1f))
            }
            JamButton(
                "Neu synchronisieren",
                onClick = {
                    MainActivity.syncClient?.pingServer(resetHistory = true)
                    MainActivity.syncClient?.sendForceSync()
                    engine.resync()
                },
                primary = false,
                modifier = Modifier.fillMaxWidth(),
                height = 48.dp
            )
        }
    }
}

/** Tap = one step, hold = keeps stepping faster (for big Bluetooth delays). */
@Composable
private fun RepeatButton(title: String, subtitle: String, modifier: Modifier, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Jam.Surface2)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    step()
                    // Track the finger ourselves: only a timeout while it is still down counts as "held".
                    // (waitForUpOrCancellation also returns null when the sheet consumes the release,
                    // which used to keep stepping for seconds after a simple tap.)
                    var delayMs = 400L
                    val heldSince = System.currentTimeMillis()
                    while (true) {
                        val event = withTimeoutOrNull(delayMs) { awaitPointerEvent() }
                        if (event == null) {
                            if (System.currentTimeMillis() - heldSince > 8000) break // safety stop
                            step()
                            delayMs = 80L
                            continue
                        }
                        event.changes.forEach { it.consume() } // keep the sheet from dragging along
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .semantics { role = Role.Button }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Jam.Text)
        Text(subtitle, fontSize = 11.sp, color = Jam.Muted)
    }
}

@Composable
private fun SheetStat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(Jam.Surface2).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontFamily = Jam.Mono, fontSize = 15.sp, color = Jam.Text)
        Text(label, fontSize = 11.sp, color = Jam.Muted)
    }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.csm.jam

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast

// ── Join request (host) ───────────────────────────────────────────────────

@Composable
fun JoinRequestSheet(session: JamSession) {
    val request = session.joinRequests.firstOrNull() ?: return
    val respond: (Boolean) -> Unit = { accept ->
        MainActivity.syncClient?.sendRespondJoin(request.requestId, accept)
        session.joinRequests.removeAll { it.requestId == request.requestId }
    }
    // The host has to decide – swiping the sheet away must not silently drop the guest
    JamSheet(onDismissRequest = {}, dismissible = false) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Jam.Surface2), contentAlignment = Alignment.Center) {
                    Text(request.name.take(1).uppercase(), fontFamily = Jam.Display, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    SectionLabel("Beitrittsanfrage")
                    Text(
                        (if (request.isWeb) LocalMusicServer.WEB_PREFIX else LocalMusicServer.GUEST_PREFIX) + request.name,
                        fontFamily = Jam.Display, fontWeight = FontWeight.Bold, fontSize = 26.sp
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (request.isWeb) "Browser" else "App")
                Chip(request.device)
            }
            Text(
                if (request.isWeb) "Möchte ohne App über den Browser mithören. Songs hinzufügen geht nur, wenn Gäste steuern dürfen."
                else "Möchte mit der CSM Jam App beitreten.",
                fontSize = 14.sp, color = Jam.Muted, lineHeight = 20.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                JamButton("Ablehnen", onClick = { respond(false) }, primary = false, modifier = Modifier.weight(1f))
                JamButton("Annehmen", onClick = { respond(true) }, modifier = Modifier.weight(1f))
            }
            if (session.joinRequests.size > 1) {
                Text("+ ${session.joinRequests.size - 1} weitere warten", fontSize = 12.sp, color = Jam.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

// ── Auto-calibration (host) ───────────────────────────────────────────────

@Composable
fun CalibrationSheet(session: JamSession) {
    val state = session.calibrationState.value
    if (state is JamSession.Calibration.Idle) return
    // While measuring the sheet stays open (closing it would hide the progress)
    JamSheet(
        onDismissRequest = { session.dismissCalibration() },
        dismissible = state !is JamSession.Calibration.Running
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Auto-Kalibrierung", fontFamily = Jam.Display, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            when (state) {
                is JamSession.Calibration.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Jam.Accent, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(state.step, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Alle Handys und Boxen nah an dein Handy (ca. 30 cm), Musik ist kurz aus. Jedes Gerät spielt zwei kurze Töne, dein Mikro hört zu.",
                        fontSize = 13.sp, color = Jam.Muted, lineHeight = 19.sp
                    )
                }
                is JamSession.Calibration.Failed -> {
                    Text(state.message, fontSize = 15.sp, color = Jam.Warn)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        JamButton("Schließen", onClick = { session.dismissCalibration() }, primary = false, modifier = Modifier.weight(1f))
                        JamButton("Nochmal", onClick = { session.startAutoCalibration() }, modifier = Modifier.weight(1f))
                    }
                }
                is JamSession.Calibration.Done -> {
                    val adjusted = state.results.count { !it.isHost && it.deltaMs != null }
                    val guests = state.results.count { !it.isHost }
                    Text("Fertig – $adjusted von $guests Geräten angeglichen", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Alle sind jetzt auf dein Handy abgestimmt.", fontSize = 13.sp, color = Jam.Muted)
                    JamCard(modifier = Modifier.background(Jam.Surface2, RoundedCornerShape(18.dp))) {
                        Column {
                            state.results.forEachIndexed { i, r ->
                                if (i > 0) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(Color(0x0FFFFFFF)))
                                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(r.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Text(
                                            when {
                                                r.isHost -> "Referenz · dieses Handy"
                                                r.deltaMs == null -> "Nicht gehört – näher ran?"
                                                else -> "angeglichen"
                                            },
                                            fontSize = 12.sp, color = if (!r.isHost && r.deltaMs == null) Jam.Warn else Jam.Muted
                                        )
                                    }
                                    Text(
                                        when {
                                            r.deltaMs == null -> "—"
                                            r.deltaMs > 0 -> "+${r.deltaMs} ms"
                                            r.deltaMs < 0 -> "−${-r.deltaMs} ms"
                                            else -> "0 ms"
                                        },
                                        fontFamily = Jam.Mono, fontSize = 14.sp,
                                        color = when {
                                            r.isHost -> Jam.Muted
                                            r.deltaMs == null -> Jam.Warn
                                            else -> Jam.Accent
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        JamButton("Nochmal", onClick = { session.startAutoCalibration() }, primary = false, modifier = Modifier.weight(1f))
                        JamButton("Fertig", onClick = { session.dismissCalibration() }, modifier = Modifier.weight(1f))
                    }
                }
                else -> {}
            }
        }
    }
}

// ── Guest waiting for approval ────────────────────────────────────────────

@Composable
fun WaitingForApproval(onCancel: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Jam.Bg).clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            CircularProgressIndicator(color = Jam.Accent)
            Spacer(Modifier.height(24.dp))
            Text("Warte auf den Host …", fontFamily = Jam.Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Jam.Text)
            Spacer(Modifier.height(8.dp))
            Text("Deine Anfrage ist raus. Sobald der Host dich annimmt, geht's los.", color = Jam.Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            JamButton("Abbrechen", onClick = onCancel, primary = false, height = 48.dp, modifier = Modifier.width(200.dp))
        }
    }
}

// ── Share / QR ────────────────────────────────────────────────────────────

fun webLinkFor(session: JamSession, context: Context): String {
    val host = if (session.isHost) getRawIpAddress(context) else session.connectedHostIp
    return "http://$host:8081/join"
}

@Composable
fun ShareSheet(session: JamSession, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val link = webLinkFor(session, context)
    val wifi = LocalHotspot.credentialsState.value
    // Own WLAN: guests first join the network (step 1), then the session (step 2)
    var step by remember(wifi) { mutableStateOf(if (wifi != null) 0 else 1) }
    val qrContent = if (wifi != null && step == 0) LocalHotspot.wifiQrPayload(wifi) else link
    val qr = remember(qrContent) { generateQrCodeBitmap(qrContent, 600) }
    JamSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Leute einladen", fontFamily = Jam.Display, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            if (wifi != null) {
                Segmented(options = listOf("1 · WLAN", "2 · Session"), selected = step, onSelect = { step = it })
            }
            if (qr != null) {
                Box(Modifier.size(240.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(10.dp)) {
                    Image(bitmap = qr.asImageBitmap(), contentDescription = "QR-Code zum Beitreten", modifier = Modifier.fillMaxSize())
                }
            }
            if (wifi != null && step == 0) {
                Text(wifi.ssid, fontFamily = Jam.Mono, fontSize = 15.sp, color = Jam.Accent)
                Text("Passwort: ${wifi.password}", fontFamily = Jam.Mono, fontSize = 13.sp, color = Jam.Text)
                Text(
                    "Mit der Kamera scannen → WLAN verbinden. Danach Schritt 2 scannen.",
                    fontSize = 12.sp, color = Jam.Muted, textAlign = TextAlign.Center
                )
                JamButton("Weiter zu Schritt 2", onClick = { step = 1 }, primary = false, modifier = Modifier.fillMaxWidth(), height = 48.dp)
            } else {
            Text(link.removePrefix("http://"), fontFamily = Jam.Mono, fontSize = 13.sp, color = Jam.Accent)
            Text(
                "App-Nutzer scannen mit dem Scanner-Icon, iPhones & Co. einfach mit der Kamera – Browser-Gäste musst du annehmen.",
                fontSize = 12.sp, color = Jam.Muted, textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                JamButton("Link kopieren", primary = false, modifier = Modifier.weight(1f), height = 48.dp, onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("CSM Jam Link", link))
                    Toast.makeText(context, "Link kopiert", Toast.LENGTH_SHORT).show()
                })
                JamButton("Teilen", modifier = Modifier.weight(1f), height = 48.dp, onClick = {
                    val host = link.removePrefix("http://").substringBefore(":")
                    val text = "𓆩🜲𓆪 Tritt meiner CSM Jam Session bei!\n\nIm selben WLAN / Hotspot öffnen (geht auch ohne App, z. B. am iPhone):\n$link\n\nDirektlink für die App:\ncsmjam://join?host=$host&port=8887"
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }, "CSM Jam Session teilen"))
                })
            }
            }
        }
    }
}

// ── Manage a guest (host) ─────────────────────────────────────────────────

@Composable
fun ParticipantDialog(participant: SessionParticipant, onDismiss: () -> Unit) {
    var canMegaphone by remember(participant.id) { mutableStateOf(participant.canMegaphone) }
    var canControl by remember(participant.id) { mutableStateOf(participant.canControl) }
    val context = LocalContext.current
    JamSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(participant.name, fontFamily = Jam.Display, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            if (!participant.isWeb) {
                PermissionRow("Megafon", "Darf Durchsagen über alle Lautsprecher machen", canMegaphone) {
                    canMegaphone = it
                    MainActivity.syncClient?.sendUpdateParticipantPermissions(participant.id, canMegaphone = it, canControl = canControl)
                }
            }
            PermissionRow("Co-DJ", "Darf steuern, Songs hinzufügen und sortieren", canControl) {
                canControl = it
                MainActivity.syncClient?.sendUpdateParticipantPermissions(participant.id, canMegaphone = canMegaphone, canControl = it)
            }
            JamButton("Aus Session entfernen", danger = true, primary = false, modifier = Modifier.fillMaxWidth(), height = 50.dp, onClick = {
                MainActivity.syncClient?.sendKickParticipant(participant.id)
                Toast.makeText(context, "${participant.name} entfernt", Toast.LENGTH_SHORT).show()
                onDismiss()
            })
        }
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, fontSize = 12.sp, color = Jam.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = Jam.Bg, checkedTrackColor = Jam.Accent))
    }
}

// ── Party light modes ─────────────────────────────────────────────────────

@Composable
fun PartyLightModeSheet(session: JamSession, onDismiss: () -> Unit) {
    JamSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Party-Licht Modus", fontFamily = Jam.Display, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            PartyLightMode.values().forEach { mode ->
                val selected = session.partyLightMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) Color(0x33FFD600) else Jam.Surface2)
                        .clickable { session.changePartyLightMode(mode); onDismiss() }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(mode.label, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (selected) Color(0xFFFFD600) else Jam.Text)
                        Text(
                            when (mode) {
                                PartyLightMode.BEAT -> "Blitzt passend zum Bass der Musik"
                                PartyLightMode.STROBE_FAST -> "Schnelles Club-Stroboskop"
                                PartyLightMode.STROBE_SLOW -> "Ruhiges, chilliges Blinken"
                            },
                            fontSize = 12.sp, color = Jam.Muted
                        )
                    }
                    RadioButton(selected = selected, onClick = { session.changePartyLightMode(mode); onDismiss() }, colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFD600)))
                }
            }
        }
    }
}

// ── Session Wrapped (leaving) ─────────────────────────────────────────────

@Composable
fun WrappedDialog(session: JamSession, wallClockMs: Long, gradient: List<Color>, onLeave: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val durationMin = ((wallClockMs - session.sessionStartTimeMsState.value).coerceAtLeast(0L) / 60000).toInt()
    // Prefer what was actually played; fall back to what was queued
    val topDj = session.playedLog.map { it.second }.filter { it.isNotBlank() }.ifEmpty { session.trackAddedBy.values.toList() }
        .map { it.removeSuffix(" (Autoplay)").removeSuffix(" (Web)") }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "—"
    val topArtist = session.playedLog.map { it.first }.ifEmpty { session.trackTitles.values.toList() }
        .mapNotNull { t -> t.substringAfterLast(" - ", "").takeIf { it.isNotBlank() } }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "—"
    val top = gradient.firstOrNull() ?: Jam.Surface
    val recap = WrappedShare.Recap(
        sessionName = MainActivity.localServer?.sessionName?.takeIf { session.isHost } ?: "CSM Jam Session",
        duration = "$durationMin Min",
        songsPlayed = session.totalSongsPlayedState.value,
        topDj = topDj,
        topArtist = topArtist,
        playedSongs = session.playedLog.toList(),
        colorTop = android.graphics.Color.argb(255, (top.red * 255).toInt(), (top.green * 255).toInt(), (top.blue * 255).toInt()),
        colorBottom = android.graphics.Color.BLACK
    )
    JamSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("✦ SESSION WRAPPED ✦", style = Jam.Label.copy(color = Jam.Accent, letterSpacing = 1.4.sp))
            Text(recap.sessionName, fontFamily = Jam.Display, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WrappedTile("Dauer", recap.duration, Modifier.weight(1f))
                WrappedTile("Songs", "${recap.songsPlayed}", Modifier.weight(1f))
            }
            WrappedTile("Top DJ", topDj, Modifier.fillMaxWidth())
            WrappedTile("Meist gespielt", topArtist, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                JamButton("Als Bild", onClick = { WrappedShare.shareImage(context, recap) }, primary = false, modifier = Modifier.weight(1f), height = 46.dp)
                JamButton("Songliste", onClick = { WrappedShare.shareSongList(context, recap) }, primary = false, enabled = session.playedLog.isNotEmpty(), modifier = Modifier.weight(1f), height = 46.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                JamButton("Weiterfeiern", onClick = onDismiss, primary = false, modifier = Modifier.weight(1f))
                JamButton(if (session.isHost) "Beenden" else "Verlassen", onClick = onLeave, danger = true, primary = false, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WrappedTile(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier.clip(RoundedCornerShape(16.dp)).background(Jam.Surface2).padding(14.dp)) {
        Text(label.uppercase(), style = Jam.Label)
        Text(value, fontFamily = Jam.Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1)
    }
}

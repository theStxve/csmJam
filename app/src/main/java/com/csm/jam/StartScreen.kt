package com.csm.jam

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Start: name, continue a killed session, host, or join one of the sessions found in the WLAN. */
@Composable
fun StartScreen(
    session: JamSession,
    discovered: List<DiscoveredSession>,
    resumable: SessionStore.Saved?,
    onDiscardResume: () -> Unit,
    onScanQr: () -> Unit,
    onHostOwnNetwork: () -> Unit
) {
    var name by remember { mutableStateOf(session.username.takeIf { it != "Gast" } ?: "") }
    var showIpDialog by remember { mutableStateOf(false) }
    var showResume by remember(resumable) { mutableStateOf(resumable != null) }
    // Countdown so the host sees how long the session can still be resumed
    var remainingMs by remember(resumable) { mutableStateOf(resumable?.remainingMs ?: 0L) }
    LaunchedEffect(resumable) {
        while (resumable != null && remainingMs > 0) {
            delay(1000)
            remainingMs = resumable.remainingMs
        }
        if (resumable != null) showResume = false
    }

    fun commitName() = session.updateUsername(name.ifBlank { "Gast ${System.currentTimeMillis() % 100}" })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Jam.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    append("csm jam")
                    withStyle(SpanStyle(color = Jam.Accent)) { append(".") }
                },
                fontFamily = Jam.Display, fontWeight = FontWeight.Bold, fontSize = 30.sp,
                letterSpacing = (-1).sp, color = Jam.Text, modifier = Modifier.weight(1f)
            )
            IconTile(onClick = { commitName(); onScanQr() }, contentDescription = "QR-Code scannen") {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Dein Name", style = Jam.Label.copy(letterSpacing = 0.sp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(32) },
                placeholder = { Text("z. B. Lena", color = Jam.Faint) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Jam.Accent,
                    unfocusedBorderColor = Color(0x1AFFFFFF),
                    focusedContainerColor = Jam.Surface,
                    unfocusedContainerColor = Jam.Surface,
                    focusedTextColor = Jam.Text,
                    unfocusedTextColor = Jam.Text,
                    cursorColor = Jam.Accent
                )
            )
        }

        if (showResume && resumable != null) {
            JamCard {
              Column {
                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Jam.Surface2),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.History, contentDescription = null, tint = Jam.Accent, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Session fortsetzen", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Jam.Text)
                        Text(
                            buildAnnotatedString {
                                append("${resumable.sessionName} · ${resumable.queueSize} Songs · noch ")
                                withStyle(SpanStyle(fontFamily = Jam.Mono)) {
                                    append(String.format("%d:%02d", remainingMs / 60000, (remainingMs % 60000) / 1000))
                                }
                            },
                            fontSize = 12.sp, color = Jam.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // Actions on their own row – next to the text they squeezed the title on small phones
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    JamButton("Verwerfen", onClick = { showResume = false; onDiscardResume() }, primary = false, modifier = Modifier.weight(1f), height = 40.dp)
                    JamButton("Fortsetzen", onClick = { commitName(); session.host(resumable) }, modifier = Modifier.weight(1f), height = 40.dp)
                }
              }
            }
        }

        JamButton(
            text = if (session.isStartingHost) "Server startet …" else "Session hosten",
            onClick = { commitName(); session.host() },
            enabled = !session.isStartingHost,
            modifier = Modifier.fillMaxWidth(),
            height = 58.dp,
            icon = { Icon(Icons.Filled.Router, contentDescription = null, modifier = Modifier.size(20.dp)) }
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val startingNetwork = LocalHotspot.isStartingState.value
            JamButton(
                text = if (startingNetwork) "WLAN wird geöffnet …" else "Eigenes WLAN aufmachen",
                onClick = { commitName(); onHostOwnNetwork() },
                enabled = !startingNetwork && !session.isStartingHost,
                primary = false,
                modifier = Modifier.fillMaxWidth(),
                height = 50.dp,
                icon = { Icon(Icons.Filled.WifiTethering, contentDescription = null, modifier = Modifier.size(20.dp)) }
            )
            Text(
                "Ohne WLAN oder im Gast-WLAN: dein Handy macht ein eigenes Netz auf, Gäste scannen einen QR-Code und sind drin.",
                fontSize = 12.sp, color = Jam.Muted, lineHeight = 17.sp
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Jam.Accent))
                Spacer(Modifier.width(8.dp))
                SectionLabel("In der Nähe")
            }
            if (discovered.isEmpty()) {
                JamCard {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Jam.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text("Suche nach Sessions im WLAN …", fontSize = 13.sp, color = Jam.Muted)
                    }
                }
            }
            discovered.forEach { found ->
                NearbySessionRow(found, connecting = session.isConnecting) { commitName(); session.join(found.hostIp) }
            }
        }

        TextButton(onClick = { showIpDialog = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Per IP-Adresse verbinden", color = Jam.Muted, fontSize = 13.sp)
        }
        // Helps checking whether both phones are in the same network (same first three number groups)
        val context = androidx.compose.ui.platform.LocalContext.current
        val ownIp = remember { getRawIpAddress(context) }
        Text(
            "Dein Gerät: $ownIp" + if (WifiBinder.wifiNetwork != null) " · WLAN" else "",
            fontFamily = Jam.Mono, fontSize = 11.sp, color = Jam.Faint,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }

    if (showIpDialog) {
        var ip by remember { mutableStateOf(session.hostIpState.value) }
        AlertDialog(
            onDismissRequest = { showIpDialog = false },
            containerColor = Jam.Surface,
            title = { Text("Per IP verbinden", fontFamily = Jam.Display, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("Host IP-Adresse") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Jam.Accent, cursorColor = Jam.Accent)
                )
            },
            confirmButton = {
                TextButton(onClick = { showIpDialog = false; commitName(); session.join(ip) }) { Text("Verbinden", color = Jam.Accent) }
            },
            dismissButton = { TextButton(onClick = { showIpDialog = false }) { Text("Abbrechen", color = Jam.Muted) } }
        )
    }
}

@Composable
private fun NearbySessionRow(found: DiscoveredSession, connecting: Boolean, onJoin: () -> Unit) {
    Surface(onClick = onJoin, shape = RoundedCornerShape(18.dp), color = Jam.Surface, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF3A2D5C), Jam.Surface2)))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(found.sessionName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Jam.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val info = buildString {
                    append(if (found.participantsCount > 0) "${found.participantsCount} dabei" else found.hostIp)
                    found.currentTrack?.takeIf { it.isNotBlank() }?.let { append(" · ◈ $it") }
                }
                Text(info, fontSize = 12.sp, color = Jam.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Surface(
                onClick = onJoin,
                enabled = !connecting,
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                contentColor = Jam.Text,
                border = BorderStroke(1.dp, Color(0x1FFFFFFF)),
                modifier = Modifier.height(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(if (connecting) "Verbindet …" else "Beitreten", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    }
}

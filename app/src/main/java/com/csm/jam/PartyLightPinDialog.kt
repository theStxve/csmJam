package com.csm.jam

import android.content.Context
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** Fixed party-light PIN, stored only as hash so it doesn't show up as plain text in the APK. */
private val partyLightPinHash = "d2cb0d59a31ba6ab2f0cdfab559239e6bb2593ffe5cf95fed14c8875e372d1cc"

private fun hashPin(pin: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest("csmjam-light:$pin".toByteArray())
        .joinToString("") { "%02x".format(it) }

/** Asks for the fixed PIN before every unlock. 3 wrong tries lock the dialog for 30 s. */
@Composable
fun PartyLightPinDialog(onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("CSM_Prefs", Context.MODE_PRIVATE) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var lockedUntil by remember { mutableStateOf(prefs.getLong("party_light_locked_until", 0L)) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockedUntil) {
        while (System.currentTimeMillis() < lockedUntil) {
            now = System.currentTimeMillis()
            delay(500)
        }
        now = System.currentTimeMillis()
    }
    val isLocked = now < lockedUntil

    fun submit() {
        if (isLocked) return
        if (hashPin(pin) == partyLightPinHash) {
            prefs.edit().putInt("party_light_fails", 0).apply()
            onUnlocked()
        } else {
            val fails = prefs.getInt("party_light_fails", 0) + 1
            pin = ""
            if (fails >= 3) {
                val until = System.currentTimeMillis() + 30_000
                prefs.edit().putInt("party_light_fails", 0).putLong("party_light_locked_until", until).apply()
                lockedUntil = until
                error = null
            } else {
                prefs.edit().putInt("party_light_fails", fails).apply()
                error = "Falsche PIN (${3 - fails} Versuch(e) übrig)"
            }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFFFFD600),
        cursorColor = Color(0xFFFFD600),
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFFFFD600)) },
        title = { Text("PIN eingeben", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8); error = null },
                    label = { Text("PIN") },
                    singleLine = true,
                    enabled = !isLocked,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = fieldColors
                )
                when {
                    isLocked -> Text("Zu viele Fehlversuche – warte ${((lockedUntil - now) / 1000) + 1} s", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { submit() },
                enabled = !isLocked && pin.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD600), contentColor = Color.Black)
            ) { Text("Freischalten") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen", color = Color.Gray) }
        }
    )
}

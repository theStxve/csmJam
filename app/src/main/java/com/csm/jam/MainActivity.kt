package com.csm.jam

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Thin host for the UI. The session itself lives in [JamSession] (state + logic), the
 * synced playback in [PlaybackEngine]; both outlive this Activity via the foreground service.
 */
class MainActivity : ComponentActivity() {

    companion object {
        var localServer: LocalMusicServer? = null
        var localFileServer: LocalFileServer? = null
        var syncClient: MusicSyncClient? = null
        var isSessionActive: Boolean = false
        var isHostMode: Boolean = false
        var instance: MainActivity? = null
    }

    /** Host from a csmjam:// deep link, NFC tag or QR code – joined by the UI. */
    var pendingJoinIp by mutableStateOf<String?>(null)

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "csmjam" && data.host == "join") {
            data.getQueryParameter("host")?.trim()?.takeIf { it.isNotBlank() }?.let { pendingJoinIp = it }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        // Party WLANs often have no internet – keep the app on the WLAN instead of mobile data
        WifiBinder.start(this)
        handleIntent(intent)

        // Dark system bars with light icons, matching the app background
        try {
            window.statusBarColor = android.graphics.Color.argb(255, 10, 10, 11)
            window.navigationBarColor = android.graphics.Color.argb(255, 10, 10, 11)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = 0
            }
        } catch (_: Exception) {}

        // NFC Quick-Join (deprecated Android Beam API, called via reflection where it still exists)
        try {
            val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
            @Suppress("DEPRECATION")
            nfcAdapter?.javaClass?.getMethod(
                "setNdefPushMessageCallback",
                NfcAdapter.CreateNdefMessageCallback::class.java,
                android.app.Activity::class.java
            )?.invoke(nfcAdapter, NfcAdapter.CreateNdefMessageCallback {
                if (localServer == null) return@CreateNdefMessageCallback null
                val hostIp = getRawIpAddress(this)
                if (hostIp == "Unbekannt") return@CreateNdefMessageCallback null
                NdefMessage(arrayOf(NdefRecord.createUri("csmjam://join?host=$hostIp")))
            }, this)
        } catch (_: Exception) {}

        // Players + media session up front, so the lock screen controls exist right away
        PlaybackEngine.get(this)

        // Local HTTP server (own songs, uploads, web player) as process singleton
        if (localFileServer == null) {
            localFileServer = LocalFileServer(applicationContext, 8081)
            try {
                localFileServer?.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContent {
            JamTheme { AppRoot(this) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        SessionDiscoveryManager.stopDiscovery()
        // Servers, engine and session keep running – the jam only stops via "Session verlassen".
    }
}

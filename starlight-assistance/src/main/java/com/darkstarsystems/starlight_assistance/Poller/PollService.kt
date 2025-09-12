package com.darkstarsystems.starlight_assistance

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class PollService : Service() {

    companion object {
        const val ACTION_START = "starlight.POLL_START"
        const val ACTION_STOP = "starlight.POLL_STOP"
        private const val CHANNEL_ID = "starlight_phone_poll"
        private const val NOTIF_ID = 2001
        private const val TAG = "StarlightPhone"

        private const val SERVER_URL = "https://darkstardestinations.com/StarlightReceiver"
        private const val ACTIVITY_KEY = "G6584-A9638-RENAE-DARK"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var autoPlay = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Starlight idle"))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHeartbeat()
        scope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                autoPlay = true
                startHeartbeat()
                updateNotification("Auto-play ON")
            }
            ACTION_STOP -> {
                autoPlay = false
                stopHeartbeat()
                updateNotification("Auto-play OFF")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* ---------------- Heartbeat loop ---------------- */

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startHeartbeat() {
        if (heartbeatJob != null) return
        heartbeatJob = scope.launch {
            while (isActive) {
                try {
                    val file = pollServer()
                    if (file != null && autoPlay) playFileNow(file)
                } catch (t: Throwable) {
                    Log.w(TAG, "heartbeat error", t)
                }
                delay(3000)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun pollServer(): File? {
        val url = URL(SERVER_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("X-Device-Type", "phone")
            setRequestProperty("X-Activity-Key", ACTIVITY_KEY)
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 5000
            readTimeout = 5000
        }
        conn.outputStream.use { it.write("{}".toByteArray()) }

        return when (val code = conn.responseCode) {
            200 -> {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                Log.i(TAG, "Server response: $body")
                parseReply(body)
            }
            else -> null
        }.also { conn.disconnect() }
    }

    private fun parseReply(json: String?): File? {
        if (json.isNullOrBlank()) return null
        val obj = JSONObject(json)
        if (!obj.has("itemAudio") || obj.isNull("itemAudio")) return null

        val b64 = obj.getString("itemAudio")
        val mime = obj.optString("itemMime", "audio/mpeg")
        val raw = gunzipBase64(b64)

        val ext = when {
            mime.contains("wav", true) -> ".wav"
            mime.contains("mp4", true) || mime.contains("aac", true) -> ".m4a"
            else -> ".mp3"
        }
        val file = File(cacheDir, "reply_${System.currentTimeMillis()}$ext")
        file.writeBytes(raw)
        return file
    }

    private fun gunzipBase64(b64: String): ByteArray {
        val comp = Base64.decode(b64, Base64.DEFAULT)
        GZIPInputStream(comp.inputStream()).use { gis -> return gis.readBytes() }
    }

    /* ---------------- Playback ---------------- */

    @RequiresApi(Build.VERSION_CODES.O)
    private fun playFileNow(file: File) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Build AudioAttributes once
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        // Request audio focus (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* optional: handle loss/duck here */ }
                .build()
            am.requestAudioFocus(focusReq)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }

        // Play
        MediaPlayer().apply {
            setAudioAttributes(attrs)
            setDataSource(file.absolutePath)
            setOnPreparedListener { it.start(); updateNotification("Playing…") }
            setOnCompletionListener { mp ->
                mp.release()
                file.delete()
                updateNotification("Auto-play ON")
                // Abandon focus when done
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    am.abandonAudioFocusRequest(
                        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(attrs)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(null)
                }
            }
            setOnErrorListener { mp, _, _ ->
                mp.release()
                file.delete()
                updateNotification("Auto-play ON")
                false
            }
            prepare()
        }
    }

    /* ---------------- Notifications ---------------- */

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Starlight Phone",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val openPI = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Starlight Phone")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPI)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}

package com.darkstarsystems.starlight.recorder

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.darkstarsystems.starlight.presentation.MainActivity
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.util.Base64

class RecordService : Service() {

    companion object {
        const val ACTION_START = "com.darkstarsystems.starlight.recorder.START"
        const val ACTION_STOP  = "com.darkstarsystems.starlight.recorder.STOP"
        private const val CHANNEL_ID = "starlight_recorder"
        private const val NOTIF_ID   = 1001
        private const val TAG = "StarlightUpload"

        private const val UPLOAD_URL = "https://darkstardestinations.com/StarlightReceiver"
        private const val HEARTBEAT_URL = "https://darkstardestinations.com/StarlightReceiver"
        private const val ACTIVITY_KEY = "G6584-A9638-RENAE-DARK"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Promote to foreground immediately; keep the service alive indefinitely.
        startForeground(NOTIF_ID, buildNotification("Starlight active"))
        startHeartbeat()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHeartbeat()
        // DO NOT call stopSelf() here; the system is destroying us.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP  -> stopRecordingThenUpload()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* ---------------- Heartbeat (3s) ---------------- */

    private fun startHeartbeat() {
        if (heartbeatJob != null) return
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                try {
                    sendHeartbeat()
                } catch (t: Throwable) {
                    Log.w(TAG, "heartbeat failed", t)
                }
                delay(700) // every 3s
            }
        }
        Log.i(TAG, "heartbeat loop started")
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        Log.i(TAG, "heartbeat loop stopped")
    }

    private fun sendHeartbeat() {
        val url = URL(HEARTBEAT_URL)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            setRequestProperty("X-Device-Type", "watch")
            setRequestProperty("X-Activity-Key", ACTIVITY_KEY)
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 5000
            readTimeout = 5000
        }

        // empty JSON to signal poll
        conn.outputStream.use { out ->
            out.write("{}".toByteArray())
            out.flush()
        }

        val code = conn.responseCode
        Log.d(TAG, "heartbeat: http=$code")

        if (code == 200) {
            val body = runCatching {
                conn.inputStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!body.isNullOrBlank()) {
                Log.v(TAG, "heartbeat: body.head=${body.take(200)}…")
                maybePlayReplyFromJson(body)
                updateNotification("Starlight active")
            } else {
                Log.i(TAG, "heartbeat: no audio in body")
            }
        } else if (code == 204) {
            // nothing queued right now
        } else {
            val err = runCatching {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!err.isNullOrBlank()) Log.w(TAG, "heartbeat error: ${err.take(200)}…")
        }

        conn.disconnect()
    }

    /* ---------------- Recording / Upload ---------------- */

    private fun startRecording() {
        if (recorder != null) return
        updateNotification("Recording…")

        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
        outputFile = File(dir, "rec_${System.currentTimeMillis()}.m4a")
        Log.i(TAG, "startRecording: ${outputFile?.absolutePath}")

        @Suppress("DEPRECATION") val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        recorder = r.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(16_000)
            setOutputFile(outputFile!!.absolutePath)
            prepare()
            start()
        }
        Log.d(TAG, "recorder started")
    }

    private fun stopRecordingThenUpload() {
        Log.i(TAG, "stopRecording requested")
        try { recorder?.apply { stop(); reset(); release() } } catch (e: Exception) {
            Log.w(TAG, "stopRecording ignored error", e)
        }
        recorder = null

        val file = outputFile
        outputFile = null
        if (file == null) {
            Log.w(TAG, "no file to upload")
            updateNotification("Starlight active")
            return
        }

        updateNotification("Uploading…")
        // Off the main thread
        serviceScope.launch {
            val (ok, body) = uploadMultipart(file, UPLOAD_URL)
            if (ok) {
                maybePlayReplyFromJson(body)
                runCatching { file.delete() }
            } else {
                Log.w(TAG, "upload failed; keeping ${file.absolutePath}")
            }
            updateNotification("Starlight active")
        }
    }

    private fun uploadMultipart(file: File, urlString: String): Pair<Boolean, String?> {
        return try {
            val boundary = "----StarlightBoundary-${UUID.randomUUID()}"
            val LINE = "\r\n"

            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                doOutput = true
                doInput = true
                useCaches = false
                requestMethod = "POST"
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Connection", "Keep-Alive")
                setRequestProperty("X-Activity-Key", ACTIVITY_KEY)
                connectTimeout = 20_000
                readTimeout = 60_000
            }

            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary$LINE")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"$LINE")
                out.writeBytes("Content-Type: audio/mp4$LINE$LINE")
                FileInputStream(file).use { it.copyTo(out) }
                out.writeBytes(LINE)
                out.writeBytes("--$boundary--$LINE")
                out.flush()
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }

            Log.d(TAG, "upload http=$code")
            conn.disconnect()
            Pair(code in 200..299, body)
        } catch (e: Exception) {
            Log.e(TAG, "upload exception", e)
            Pair(false, null)
        }
    }

    /* ---------------- Play reply ---------------- */

    private fun maybePlayReplyFromJson(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val obj = JSONObject(json)
            if (!obj.has("itemAudio") || obj.isNull("itemAudio")) return

            val mime = obj.optString("itemMime", "audio/mpeg")
            val b64  = obj.getString("itemAudio")
            val rawBytes = gunzipBase64(b64)

            val ext = when {
                mime.contains("wav", true) -> ".wav"
                mime.contains("mp4", true) || mime.contains("aac", true) -> ".m4a"
                else -> ".mp3"
            }
            val outFile = File(cacheDir, "reply_${System.currentTimeMillis()}$ext")
            outFile.writeBytes(rawBytes)
            Log.i(TAG, "reply saved ${outFile.name} (${rawBytes.size} B)")
            playFileNow(outFile)
        } catch (t: Throwable) {
            Log.w(TAG, "reply parse/play failed", t)
        }
    }

    private fun gunzipBase64(b64: String): ByteArray {
        val comp = Base64.decode(b64, Base64.DEFAULT)
        GZIPInputStream(comp.inputStream()).use { gis -> return gis.readBytes() }
    }

    private fun playFileNow(file: File) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                ).build()
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }

        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(file.absolutePath)
            setOnPreparedListener { it.start(); updateNotification("Playing reply…") }
            setOnCompletionListener { it.release(); file.delete(); updateNotification("Starlight active") }
            setOnErrorListener { p, _, _ -> p.release(); updateNotification("Starlight active"); false }
            prepare()
        }
    }

    /* ---------------- Notifications ---------------- */

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Starlight",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Ongoing Starlight service" }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val openPI = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, RecordService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Starlight")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openPI)
            .addAction(0, "Stop", stopPI)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}

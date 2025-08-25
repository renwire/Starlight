package com.darkstarsystems.starlight.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.darkstarsystems.starlight.presentation.MainActivity
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

class RecordService : Service() {

    companion object {
        const val ACTION_START = "com.darkstarsystems.starlight.recorder.START"
        const val ACTION_STOP  = "com.darkstarsystems.starlight.recorder.STOP"
        private const val CHANNEL_ID = "starlight_recorder"
        private const val NOTIF_ID   = 1001
        private const val TAG = "StarlightUpload"
        // TODO: set to your HTTPS endpoint, e.g., https://api.darkstar.dev/transcribe
        private const val UPLOAD_URL = "https://darkstardestinations.com/StarlightReceiver"
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP  -> stopRecordingThenUpload()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* ---------------- Recording ---------------- */

    private fun startRecording() {
        if (recorder != null) return
        createChannel()
        startForeground(NOTIF_ID, buildNotification(text = "Recording in progress"))

        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
        outputFile = File(dir, "rec_${System.currentTimeMillis()}.m4a")
        Log.i(TAG, "startRecording: file=${outputFile?.absolutePath}")

        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
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
        Log.d(TAG, "Recorder started (AAC/16kHz/128kbps)")
    }


    private fun stopRecordingThenUpload() {
        Log.i(TAG, "stopRecording: requested")
        try { recorder?.apply { stop(); reset(); release() } } catch (e: Exception) {
            Log.w(TAG, "stopRecording: ignored error (likely very short clip)", e)
        }
        recorder = null

        val file = outputFile
        outputFile = null

        if (file == null) {
            Log.w(TAG, "stopRecording: no file to upload")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }

        Log.i(TAG, "stopped: size=${file.length()} bytes path=${file.absolutePath}")
        updateNotification("Uploading…")

        thread(name = "StarlightUpload") {
            Log.i(TAG, "upload: begin url=$UPLOAD_URL name=${file.name} size=${file.length()}")
            val t0 = System.currentTimeMillis()
            val success = uploadMultipart(file, UPLOAD_URL)
            val dt = System.currentTimeMillis() - t0
            Log.i(TAG, "upload: completed success=$success elapsed=${dt}ms")

            if (success) {
                val deleted = runCatching { file.delete() }.getOrElse { false }
                if (deleted) Log.i(TAG, "cleanup: deleted=${file.name}")
                else Log.w(TAG, "cleanup: delete failed for ${file.name}")
            } else {
                Log.w(TAG, "upload: server failure; file retained at ${file.absolutePath}")
            }

            updateNotification(if (success) "Uploaded" else "Upload failed")
            try { Thread.sleep(500) } catch (_: InterruptedException) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }


    /* ---------------- Upload (no external deps) ---------------- */

    private fun uploadMultipart(file: File, urlString: String): Boolean {
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
                setRequestProperty("X-Activity-Key", "G6584-A9638-RENAE-DARK") // <— add this
                connectTimeout = 20_000
                readTimeout = 60_000
            }

            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary$LINE")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"$LINE")
                out.writeBytes("Content-Type: audio/mp4$LINE$LINE")
                FileInputStream(file).use { it.copyTo(out, bufferSize = 8 * 1024) }
                out.writeBytes(LINE)
                out.writeBytes("--$boundary--$LINE")
                out.flush()
            }

            val code = conn.responseCode
            val msg  = conn.responseMessage ?: ""
            val body = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use {
                    it.readText().take(1024) // cap to 1KB to keep logs tidy
                } ?: ""
            } catch (e: Exception) { "" }

            Log.d(TAG, "http: code=$code msg=$msg")
            if (body.isNotEmpty()) Log.v(TAG, "http: body=${body}")

            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "upload: exception", e)
            false
        }
    }


    /* ---------------- Notifications ---------------- */

    private fun createChannel() {
        //if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Starlight Recording",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Ongoing recording & upload" }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, RecordService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPI = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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

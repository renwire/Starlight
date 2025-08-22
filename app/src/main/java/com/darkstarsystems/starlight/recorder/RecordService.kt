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
import androidx.core.app.NotificationCompat
import com.darkstarsystems.starlight.presentation.MainActivity
import java.io.File

class RecordService : Service() {

    companion object {
        const val ACTION_START = "com.darkstarsystems.starlight.recorder.START"
        const val ACTION_STOP  = "com.darkstarsystems.starlight.recorder.STOP"
        private const val CHANNEL_ID = "starlight_recorder"
        private const val NOTIF_ID   = 1001
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP  -> stopRecordingAndSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecording() {
        if (recorder != null) return  // already recording

        createChannel()
        startForeground(NOTIF_ID, buildNotification())

        // Save under app's external music dir
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
        outputFile = File(dir, "rec_${System.currentTimeMillis()}.m4a")

        // MediaRecorder works well on Wear; AudioRecord is overkill here.
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        recorder = r.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(outputFile!!.absolutePath)
            prepare()
            start()
        }
    }

    private fun stopRecordingAndSelf() {
        try {
            recorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (_: Exception) {
            // ignore stop errors from very short recordings
        } finally {
            recorder = null
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Starlight Recording",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Ongoing microphone recording" }
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RecordService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openPI = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording in progress")
            .setContentText("Tap Stop to finish.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openPI)
            .addAction(0, "Stop", stopPI)
            .build()
    }

    override fun onDestroy() {
        // Ensure the recorder is released if the system kills the service.
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        super.onDestroy()
    }
}

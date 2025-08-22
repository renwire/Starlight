package com.example.starlight.presentation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.starlight.recorder.RecordService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                WearRecorderScreen(
                    requestPermissions = { requestRuntimePermissions(this@MainActivity) },
                    start = { startRecording(this@MainActivity) },
                    stop = { stopRecording(this@MainActivity) }
                )
            }
        }
    }

    private fun requestRuntimePermissions(activity: Activity) {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Launch only if something is missing
        if (permissions.any {
                ContextCompat.checkSelfPermission(activity, it) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            }) {
            activity.registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {}.launch(permissions.toTypedArray())
        }
    }
}

@Composable
private fun WearRecorderScreen(
    requestPermissions: () -> Unit,
    start: () -> Unit,
    stop: () -> Unit
) {
    // Local UI state; service keeps the actual recording alive.
    var isRecording by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { requestPermissions() }

    Scaffold(
        timeText = { TimeText() }
    ) {
        androidx.wear.compose.material.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Text(if (isRecording) "Recording…" else "Ready", modifier = Modifier.padding(6.dp))
            Button(onClick = {
                if (isRecording) {
                    stop()
                } else {
                    start()
                }
                isRecording = !isRecording
            }) {
                Text(if (isRecording) "Stop" else "Record")
            }
        }
    }
}

/* --- Service control helpers --- */

private fun startRecording(context: Context) {
    val intent = Intent(context, RecordService::class.java).apply {
        action = RecordService.ACTION_START
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun stopRecording(context: Context) {
    val intent = Intent(context, RecordService::class.java).apply {
        action = RecordService.ACTION_STOP
    }
    ContextCompat.startForegroundService(context, intent)
}

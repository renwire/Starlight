package com.darkstarsystems.starlight.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.darkstarsystems.starlight.presentation.theme.StarlightTheme
import com.darkstarsystems.starlight.recorder.RecordService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op */ }

        setContent {
            StarlightTheme {
                WearRecorderScreen(
                    requestPermissions = { requestRuntimePermissions() },
                    start = { startRecording(this@MainActivity) },
                    stop  = { stopRecording(this@MainActivity) }
                )
            }
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needsAny = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needsAny) permissionLauncher.launch(permissions.toTypedArray())
    }
}

@Composable
private fun KeepScreenOnFor(
    minDurationMillis: Long,
    rearmKey: Any
) {
    val view = LocalView.current

    LaunchedEffect(rearmKey) {
        view.keepScreenOn = true
        delay(minDurationMillis)
        view.keepScreenOn = false
    }

    DisposableEffect(Unit) {
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun WearRecorderScreen(
    requestPermissions: () -> Unit,
    start: () -> Unit,
    stop: () -> Unit
) {
    var isRecording by remember { mutableStateOf(false) }

    // Re-arm on activity resume
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Re-arm on button press
    var tapTick by remember { mutableIntStateOf(0) }

    // Keep the screen awake for at least 10s on resume or tap
    KeepScreenOnFor(minDurationMillis = 10_000L, rearmKey = resumeTick to tapTick)

    LaunchedEffect(Unit) { requestPermissions() }

    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(if (isRecording) "Recording…" else "Ready", modifier = Modifier.padding(6.dp))
            Button(onClick = {
                if (isRecording) stop() else start()
                isRecording = !isRecording
                tapTick++ // re-arm the 10s window on each tap
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
    context.startService(intent)
}

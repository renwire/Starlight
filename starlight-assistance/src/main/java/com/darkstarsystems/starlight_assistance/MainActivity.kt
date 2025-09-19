package com.darkstarsystems.starlight_assistance

import android.content.Context
import android.content.Intent
import android.media.*
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import android.util.Base64
import androidx.annotation.RequiresApi
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.darkstarsystems.starlight_assistance.ui.theme.StarlightTheme
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        // 1) Login check
        val apiKey = LoginStash.getApiKey(this)
        if (apiKey == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContent {
            StarlightTheme {
                PhoneDashboard()
            }
        }

    }


    object LoginStash {
        private const val PREF_NAME = "darkstar_prefs"
        private const val KEY_API = "api_key"

        fun getApiKey(context: Context): String? {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_API, null)
        }

        fun saveApiKey(context: Context, apiKey: String) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_API, apiKey).apply()

            // send to watch
            val putDataReq = PutDataMapRequest.create("/api_key").apply {
                dataMap.putString("apiKey", apiKey)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(putDataReq)
        }

        fun clear(context: Context) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_API).commit()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneDashboard() {
    val context = LocalContext.current
    var autoPlay by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("Starlight Companion") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Switch(
                checked = autoPlay,
                onCheckedChange = {
                    autoPlay = it
                    val intent = Intent(context, PollService::class.java).apply {
                        action = if (it) PollService.ACTION_START else PollService.ACTION_STOP
                    }
                    context.startForegroundService(intent)
                }
            )
            Text(if (autoPlay) "Auto-play ON" else "Auto-play OFF")
        }
    }
}

/* ----------------- Networking ------------------ */

//private const val SERVER_URL = "https://darkstardestinations.com/StarlightReceiver"
//private const val ACTIVITY_KEY = "G6584-A9638-RENAE-DARK"

//private fun pollServer(): File? {
//    val url = URL(SERVER_URL)
//    val conn = (url.openConnection() as HttpURLConnection).apply {
//        requestMethod = "POST"
//        doOutput = true
//        setRequestProperty("X-Activity-Key", ACTIVITY_KEY)
//        setRequestProperty("Content-Type", "application/json")
//        connectTimeout = 5000
//        readTimeout = 5000
//    }
//    conn.outputStream.use { it.write("{}".toByteArray()) }
//
//    return when (val code = conn.responseCode) {
//        200 -> {
//            val body = conn.inputStream.bufferedReader().use { it.readText() }
//            parseAndSaveReply(body)
//        }
//        204 -> null
//        else -> null
//    }.also { conn.disconnect() }
//}

//private fun parseAndSaveReply(json: String?): File? {
//    if (json.isNullOrBlank()) return null
//    val obj = JSONObject(json)
//    if (!obj.has("itemAudio") || obj.isNull("itemAudio")) return null
//
//    val b64 = obj.getString("itemAudio")
//    val mime = obj.optString("itemMime", "audio/mpeg")
//    val raw = gunzipBase64(b64)
//
//    val ext = when {
//        mime.contains("wav", true) -> ".wav"
//        mime.contains("mp4", true) || mime.contains("aac", true) -> ".m4a"
//        else -> ".mp3"
//    }
//    val file = File.createTempFile("reply_", ext)
//    file.writeBytes(raw)
//    return file
//}

//private fun gunzipBase64(b64: String): ByteArray {
//    val comp = Base64.decode(b64, Base64.DEFAULT)
//    GZIPInputStream(comp.inputStream()).use { gis -> return gis.readBytes() }
//}
//
///* ----------------- Playback ------------------ */
//
//private fun playFileNow(file: File, cacheDir: File) {
//    MediaPlayer().apply {
//        setAudioAttributes(
//            AudioAttributes.Builder()
//                .setUsage(AudioAttributes.USAGE_MEDIA)
//                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
//                .build()
//        )
//        setDataSource(file.absolutePath)
//        setOnPreparedListener { it.start() }
//        setOnCompletionListener { mp ->
//            mp.release()
//            file.delete()
//        }
//        setOnErrorListener { mp, _, _ -> mp.release(); file.delete(); false }
//        prepare()
//    }
//}

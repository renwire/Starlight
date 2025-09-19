package com.darkstarsystems.starlight.presentation

import android.content.Context
import com.google.android.gms.wearable.WearableListenerService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem

class KeySyncListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path == "/api_key") {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val apiKey = dataMap.getString("apiKey")
                if (apiKey != null) {
                    val prefs = getSharedPreferences("darkstar_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString("api_key", apiKey).apply()
                }
            }
        }
    }
}

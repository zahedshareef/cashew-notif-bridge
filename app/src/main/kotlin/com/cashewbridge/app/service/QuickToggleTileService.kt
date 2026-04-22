package com.cashewbridge.app.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.cashewbridge.app.prefs.AppPreferences

/**
 * Quick Settings tile that shows the bridge status and toggles it on/off (#8).
 *
 * The user can add the tile from the QS edit panel. Tapping it toggles
 * [AppPreferences.isEnabled] without opening the app.
 */
@RequiresApi(Build.VERSION_CODES.N)
class QuickToggleTileService : TileService() {

    private lateinit var prefs: AppPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(applicationContext)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        prefs.isEnabled = !prefs.isEnabled
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val enabled = prefs.isEnabled
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Cashew Bridge"
        tile.contentDescription = if (enabled) "Bridge active" else "Bridge paused"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (enabled) "Active" else "Paused"
        }
        tile.updateTile()
    }
}

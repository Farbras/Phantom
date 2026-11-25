package app.phantom.vpn

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.phantom.MainActivity

class PhantomTile: TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply { state=if(VpnRuntime.state.value.tunnel) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE;label="Phantom";updateTile() }
    }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // Legacy overload is only called on API < 34.
    override fun onClick() {
        super.onClick()
        if(VpnRuntime.state.value.phase!=Phase.OFF) PhantomVpnService.disconnect(this)
        else if(VpnService.prepare(this)==null) PhantomVpnService.connect(this)
        else {
            val intent=Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("connect",true)
            if(Build.VERSION.SDK_INT>=34) startActivityAndCollapse(PendingIntent.getActivity(this,5,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            else { @Suppress("DEPRECATION") startActivityAndCollapse(intent) }
        }
    }
    companion object { fun refresh(context: Context) { requestListeningState(context,ComponentName(context,PhantomTile::class.java)) } }
}

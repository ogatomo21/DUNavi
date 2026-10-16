package net.ogatomo.dunavi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_START, false)
        val allowed = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!enabled) return
        if (allowed) {
            DunState.log("BOOT auto-start")
            DunService.start(context)
        } else {
            DunState.log("BOOT auto-start skipped: Bluetooth permission")
        }
    }

    companion object {
        const val PREFS = "dunavi"
        const val KEY_AUTO_START = "auto_start"
    }
}

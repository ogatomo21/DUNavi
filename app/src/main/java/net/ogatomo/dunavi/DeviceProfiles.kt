package net.ogatomo.dunavi

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeviceProfile(
    val address: String,
    val name: String,
    val connections: Int,
    val successfulSessions: Int,
    val lastConnectedAtMs: Long,
    val observedAtCommands: List<String>,
)

object DeviceProfiles {
    private const val IDS = "device_profile_ids"
    private var preferences: SharedPreferences? = null
    private val mutable = MutableStateFlow<List<DeviceProfile>>(emptyList())
    val profiles = mutable.asStateFlow()

    @Synchronized fun init(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)
        refresh()
    }

    @Synchronized fun connected(address: String, name: String) {
        val prefs = prefs() ?: return
        val ids = prefs.getStringSet(IDS, emptySet()).orEmpty() + address
        prefs.edit {
            putStringSet(IDS, ids)
            putString("profile_name_$address", name)
            putInt("profile_connections_$address", prefs.getInt("profile_connections_$address", 0) + 1)
            putLong("profile_last_$address", System.currentTimeMillis())
        }
        refresh()
    }

    @Synchronized fun succeeded(address: String) {
        val prefs = prefs() ?: return
        prefs.edit { putInt("profile_success_$address", prefs.getInt("profile_success_$address", 0) + 1) }
        refresh()
    }

    @Synchronized fun observedAt(address: String, command: String) {
        val name = atCommandName(command) ?: return
        val prefs = prefs() ?: return
        val key = "profile_at_$address"
        val commands = prefs.getString(key, "").orEmpty().split(',').filter(String::isNotBlank).toMutableList()
        if (name !in commands) commands += name
        prefs.edit { putString(key, commands.takeLast(32).joinToString(",")) }
        refresh()
    }

    private fun prefs() = preferences

    private fun refresh() {
        val prefs = prefs() ?: return
        mutable.value = prefs.getStringSet(IDS, emptySet()).orEmpty().map { address ->
            DeviceProfile(
                address,
                prefs.getString("profile_name_$address", null) ?: "ペアリング済み機器",
                prefs.getInt("profile_connections_$address", 0),
                prefs.getInt("profile_success_$address", 0),
                prefs.getLong("profile_last_$address", 0),
                prefs.getString("profile_at_$address", "").orEmpty().split(',').filter(String::isNotBlank),
            )
        }.sortedByDescending { it.lastConnectedAtMs }
    }
}

internal fun atCommandName(command: String): String? {
    val value = command.trim().uppercase()
    if (!value.startsWith("AT")) return null
    if (value.startsWith("ATD")) return "ATD"
    val base = value.substringBefore('=').take(32)
    return when {
        '=' in value && !value.endsWith("=?") -> "$base="
        else -> value.take(32)
    }
}

package app.sona.notch

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager

/**
 * Listens for system events worth a pop-up: Wi-Fi and mobile data (connected/disconnected, each with its
 * own logo), Bluetooth, airplane mode, charging started, and ringer mode changes. Anything that happens
 * right at start-up is ignored so existing connections do not pop up. Each kind can be turned off in settings.
 */
class SystemEvents(private val context: Context, private val onEvent: (EventItem) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.elapsedRealtime()
    private val lastBluetooth = HashMap<String, Long>()
    private val announcedBluetooth = HashSet<String>()
    private var wifiAnnounced = false
    private var cellAnnounced = false
    private var lastLevel = -1
    private var lastPlugged = false

    private fun settling() = SystemClock.elapsedRealtime() - startedAt < SETTLE_MS

    private fun emit(pref: String, event: EventItem) {
        if (!Prefs.bool(pref)) return
        main.post { onEvent(event) }
    }

    // A brief drop (switching networks) must not produce a "disconnected" pop-up; wait first.
    private val wifiLost = Runnable {
        wifiAnnounced = false
        emit(Prefs.EVENT_WIFI, EventItem(0, Color.WHITE, "Disconnected", EventItem.KIND_WIFI, on = false))
    }
    private val cellLost = Runnable {
        cellAnnounced = false
        emit(Prefs.EVENT_CELL, EventItem(0, Color.WHITE, "Disconnected", EventItem.KIND_CELL, on = false))
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            main.removeCallbacks(wifiLost)
            val info = caps.transportInfo as? WifiInfo ?: return
            if (wifiAnnounced) return
            wifiAnnounced = true
            if (settling()) return
            val ssid = info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "Wi-Fi"
            val level = context.getSystemService(WifiManager::class.java).calculateSignalLevel(info.rssi)
            emit(Prefs.EVENT_WIFI, EventItem(0, Color.WHITE, ssid, EventItem.KIND_WIFI, level.coerceIn(1, 4)))
        }

        override fun onLost(network: Network) {
            if (wifiAnnounced) main.postDelayed(wifiLost, LOST_DELAY_MS)
        }
    }

    private val cellCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            main.removeCallbacks(cellLost)
            if (cellAnnounced) return
            cellAnnounced = true
            if (settling()) return
            val tm = context.getSystemService(TelephonyManager::class.java)
            val operator = tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "Mobile data"
            val level = try {
                tm.signalStrength?.level ?: 3
            } catch (e: SecurityException) {
                3
            }
            emit(Prefs.EVENT_CELL, EventItem(0, Color.WHITE, operator, EventItem.KIND_CELL, level.coerceIn(1, 4)))
        }

        override fun onLost(network: Network) {
            if (cellAnnounced) main.postDelayed(cellLost, LOST_DELAY_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> onBluetooth(intent, connected = true)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> onBluetooth(intent, connected = false)
                Intent.ACTION_POWER_CONNECTED -> {
                    // The system needs a moment to estimate the time to full; wait for it, then show both.
                    main.postDelayed({
                        val bm = context.getSystemService(BatteryManager::class.java)
                        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        val ms = bm.computeChargeTimeRemaining()
                        val left = if (ms > 0) " · " + formatRemaining(ms) else ""
                        emit(
                            Prefs.EVENT_CHARGING,
                            EventItem(R.drawable.ic_bolt, COLOR_GREEN, "$pct%$left", EventItem.KIND_CHARGE, pct),
                        )
                    }, 1300)
                }
                Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val on = context.getSystemService(PowerManager::class.java).isPowerSaveMode
                    emit(
                        Prefs.EVENT_BATTERY,
                        EventItem(R.drawable.ic_bolt, COLOR_YELLOW, if (on) "Power saving" else "Power saving off", on = on),
                    )
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val on = intent.getBooleanExtra("state", false)
                    emit(
                        Prefs.EVENT_AIRPLANE,
                        EventItem(R.drawable.ic_airplane, if (on) COLOR_ORANGE else Color.WHITE, if (on) "Airplane" else "Airplane off", on = on),
                    )
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    // The system re-sends this right after the service starts; only real changes should pop up.
                    if (settling()) return
                    val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
                    emit(
                        Prefs.EVENT_RINGER,
                        when (mode) {
                            AudioManager.RINGER_MODE_SILENT -> EventItem(R.drawable.ic_mute, Color.WHITE, "Silent")
                            AudioManager.RINGER_MODE_VIBRATE -> EventItem(R.drawable.ic_vibrate, Color.WHITE, "Vibrate")
                            else -> EventItem(R.drawable.ic_ring, Color.WHITE, "Ring")
                        },
                    )
                }
            }
        }
    }

    /** "52 min" or "1 h 05 min". */
    private fun formatRemaining(ms: Long): String {
        val min = (ms / 60_000L).toInt()
        return if (min < 60) "$min min" else "${min / 60} h %02d min".format(min % 60)
    }

    /** Low battery (20% and 10%) and fully charged, each reported once as the level crosses the line. */
    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0) return
        val pct = level * 100 / scale
        val before = lastLevel
        val wasPlugged = lastPlugged
        lastLevel = pct
        lastPlugged = plugged
        if (before < 0 || settling()) return
        if (!plugged && before > 20 && pct <= 20) {
            emit(Prefs.EVENT_BATTERY, EventItem(R.drawable.ic_bolt, COLOR_ORANGE, "Low battery", EventItem.KIND_CHARGE, pct))
        } else if (!plugged && before > 10 && pct <= 10) {
            emit(
                Prefs.EVENT_BATTERY,
                EventItem(R.drawable.ic_bolt, COLOR_RED, "Battery 10%", EventItem.KIND_CHARGE, pct, reaction = EyesView.Reaction.TEARS),
            )
        } else if (plugged && wasPlugged && before < 100 && pct >= 100) {
            emit(Prefs.EVENT_BATTERY, EventItem(R.drawable.ic_bolt, COLOR_GREEN, "Fully charged", EventItem.KIND_CHARGE, 100))
        }
    }

    /** Battery level of a Bluetooth accessory, if it reports one (hidden API, so read reflectively). */
    private fun bluetoothBattery(device: BluetoothDevice): Int = try {
        device.javaClass.getMethod("getBatteryLevel").invoke(device) as Int
    } catch (e: Exception) {
        -1
    }

    private fun onBluetooth(intent: Intent, connected: Boolean) {
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val address = device.address
        val now = SystemClock.elapsedRealtime()
        if (connected) {
            // BLE devices (watches, trackers) reconnect often; one pop-up per device per 30 seconds.
            if (now - (lastBluetooth[address] ?: -COOLDOWN_MS) < COOLDOWN_MS) return
            lastBluetooth[address] = now
            announcedBluetooth += address
        } else {
            // Only devices we announced as connected are announced as disconnected.
            if (!announcedBluetooth.remove(address)) return
        }
        if (settling()) return
        val name = try {
            device.name
        } catch (e: SecurityException) {
            null
        }?.takeIf { it.isNotBlank() } ?: "Bluetooth"
        val battery = if (connected) bluetoothBattery(device) else -1
        val label = if (battery in 0..100) "$name · $battery%" else name
        emit(
            Prefs.EVENT_BLUETOOTH,
            if (connected) EventItem(R.drawable.ic_bluetooth, COLOR_BLUE, label)
            else EventItem(R.drawable.ic_bluetooth, COLOR_BLUE, "Disconnected", on = false),
        )
    }

    fun register() {
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifiCallback,
            )
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cellCallback,
            )
        } catch (e: SecurityException) {
            // Without ACCESS_NETWORK_STATE network pop-ups are skipped; other events still work.
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    fun unregister() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        for (cb in listOf(wifiCallback, cellCallback)) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (e: IllegalArgumentException) {
                // Not registered.
            }
        }
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Not registered.
        }
        main.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val SETTLE_MS = 2500L
        const val COOLDOWN_MS = 30_000L
        const val LOST_DELAY_MS = 1500L
        val COLOR_BLUE = 0xFF4DA3FF.toInt()
        val COLOR_GREEN = 0xFF34C759.toInt()
        val COLOR_ORANGE = 0xFFFF9F0A.toInt()
        val COLOR_RED = 0xFFFF453A.toInt()
        val COLOR_YELLOW = 0xFFFFD60A.toInt()
    }
}

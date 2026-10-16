package com.shilapi.xcertplay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.Closeable

internal data class ConnectedPhone(val address: String, val name: String?)
internal data class ConnectedPhoneState(
    val permitted: Boolean = true,
    val enabled: Boolean = false,
    val phones: List<ConnectedPhone> = emptyList(),
)

/** Connection evidence comes from live profiles/ACL, never from pairing alone. */
internal class ConnectedBluetoothPhones(context: Context, private val changed: (ConnectedPhoneState) -> Unit) : Closeable {
    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val profiles = mutableMapOf<Int, BluetoothProfile>()
    private val requested = mutableSetOf<Int>()
    private val acl = mutableMapOf<String, BluetoothDevice>()
    private val disconnected = mutableSetOf<String>()
    private var closed = false
    private var registered = false
    private var last: ConnectedPhoneState? = null
    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (closed) { runCatching { adapter?.closeProfileProxy(profile, proxy) }; return }
            profiles[profile] = proxy
            refresh()
        }
        override fun onServiceDisconnected(profile: Int) { profiles.remove(profile); refresh() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (device != null) runCatching {
                val address = device.address
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> { acl.remove(address); disconnected.add(address) }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> { acl[address] = device; disconnected.remove(address) }
                    else -> if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) {
                        disconnected.remove(address)
                    }
                }
            }
            refresh()
        }
    }

    fun start() {
        if (!registered) {
            registered = runCatching {
                ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    PhoneBluetoothReceiver.ACTIONS.keys.forEach(::addAction)
                }, ContextCompat.RECEIVER_EXPORTED)
                true
            }.getOrDefault(false)
        }
        refresh()
    }

    fun refresh() {
        if (closed) return
        val permitted = Build.VERSION.SDK_INT < 31 ||
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val enabled = permitted && runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        if (!enabled) {
            acl.clear()
            disconnected.clear()
            publish(ConnectedPhoneState(permitted, false))
            return
        }
        // 11/16 are the head unit's A2DP sink/HFP client; ordinary Android profiles are also
        // queried when available. No discovery, pairing, or connection is initiated here.
        for (profile in listOf(11, 16, BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            if (requested.add(profile)) runCatching { adapter?.getProfileProxy(app, listener, profile) }
        }
        val live = profiles.values.flatMap { runCatching { it.connectedDevices }.getOrDefault(emptyList()) }
        // Some E02 firmware exposes receiver profiles late. Use isConnected only as positive
        // evidence for already bonded devices; unavailable hidden methods do not imply connected.
        val bondedConnected = runCatching { adapter?.bondedDevices.orEmpty().filter { device ->
            runCatching { device.javaClass.getMethod("isConnected").invoke(device) == true }.getOrDefault(false)
        } }.getOrDefault(emptyList())
        val phones = (live + bondedConnected + acl.values).mapNotNull { device -> runCatching {
            val major = device.bluetoothClass?.majorDeviceClass
            if (device.address in disconnected || major != null && major !in setOf(
                    BluetoothClass.Device.Major.PHONE, BluetoothClass.Device.Major.UNCATEGORIZED,
                    BluetoothClass.Device.Major.MISC)) null else ConnectedPhone(device.address, device.name)
        }.getOrNull() }.distinctBy { it.address.uppercase() }.sortedBy { it.name ?: it.address }
        publish(ConnectedPhoneState(true, true, phones))
    }

    private fun publish(state: ConnectedPhoneState) {
        if (last != state) { last = state; changed(state) }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (registered) runCatching { app.unregisterReceiver(receiver) }
        profiles.forEach { (profile, proxy) -> runCatching { adapter?.closeProfileProxy(profile, proxy) } }
        profiles.clear()
        acl.clear()
    }
}

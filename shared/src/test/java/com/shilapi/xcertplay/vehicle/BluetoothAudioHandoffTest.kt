package com.shilapi.xcertplay.vehicle

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class BluetoothAudioHandoffTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val adapter get() = BluetoothAdapter.getDefaultAdapter()
    private val phone get() = adapter.getRemoteDevice("02:11:22:33:44:55")
    private val other get() = adapter.getRemoteDevice("02:66:77:88:99:AA")
    private val owners = mutableListOf<BluetoothAudioHandoff>()
    private val log = mutableListOf<String>()
    private lateinit var profile: MusicProfile

    @Before fun prepare() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(adapter).setEnabled(true)
        shadowOf(adapter).setBondedDevices(setOf(phone, other))
        shadowOf(phone).setBondState(BluetoothDevice.BOND_BONDED)
        shadowOf(other).setBondState(BluetoothDevice.BOND_BONDED)
        profile = MusicProfile(linkedMapOf(phone to BluetoothProfile.STATE_CONNECTED, other to BluetoothProfile.STATE_DISCONNECTED))
        shadowOf(adapter).setProfileProxy(11, profile)
    }

    @After fun cleanup() {
        profile.states[phone] = BluetoothProfile.STATE_CONNECTED
        owners.forEach { it.close() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
    }

    private fun owner(suppressed: Boolean, playing: ((Boolean) -> Unit)? = null) =
        BluetoothAudioHandoff(context, phone.address, log::add, playing).also {
            owners.add(it); it.setSuppressed(suppressed); it.start()
            shadowOf(Looper.getMainLooper()).idle()
        }

    @Test fun nativeOutputKeepsBothPeersAndReportsOnlyActualSelectedPhonePlayback() {
        profile.states[other] = BluetoothProfile.STATE_CONNECTED
        val playing = mutableListOf<Boolean>()
        owner(false, playing::add)
        assertTrue(profile.disconnected.isEmpty())
        assertTrue(playing.isEmpty()) // connection is not playback
        profile.playing = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf(true), playing)
        context.sendBroadcast(Intent(ACTION_PLAYING).putExtra(BluetoothDevice.EXTRA_DEVICE, other)
            .putExtra(BluetoothProfile.EXTRA_STATE, 11))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true), playing)
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf(true, false), playing)
        assertTrue(profile.connected.isEmpty()) // no unsolicited reconnect
    }

    @Test fun closeWaitsForOurDisconnectThenRestoresOnlyThatPeer() {
        val handoff = owner(true)
        assertEquals(listOf(phone), profile.disconnected)
        handoff.close()
        assertTrue(profile.connected.isEmpty())
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(listOf(phone), profile.connected)
        assertTrue(shadowOf(adapter).hasActiveProfileProxy(11))
        profile.states[phone] = BluetoothProfile.STATE_CONNECTED
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertFalse(shadowOf(adapter).hasActiveProfileProxy(11))
    }

    @Test fun replacementSessionPreventsOldOwnerRestoringNewOwnersSource() {
        val first = owner(true)
        // Android gives each getProfileProxy caller its own proxy object. Robolectric's
        // single-profile registry would otherwise disconnect every listener on close.
        val replacementProfile = MusicProfile(profile.states)
        shadowOf(adapter).setProfileProxy(11, replacementProfile)
        val second = owner(true)
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        first.close()
        assertTrue(profile.connected.isEmpty())
        second.close()
        assertEquals(listOf(phone), replacementProfile.connected)
    }

    @Test fun peerWeDidNotDisconnectIsNeverConnectedOnClose() {
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        val handoff = owner(false)
        handoff.close()
        assertTrue(profile.disconnected.isEmpty())
        assertTrue(profile.connected.isEmpty())
    }

    @Test fun changingOwnershipBackToNativeRestoresAndLateUpdatesAfterCloseAreIgnored() {
        val handoff = owner(true)
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        handoff.setSuppressed(false)
        assertEquals(listOf(phone), profile.connected)
        handoff.close()
        handoff.setSuppressed(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(1, profile.disconnected.size)
    }

    @Test fun activeCallDefersMusicHandoffAndUnbondingPreventsRestore() {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_CALL
        val handoff = owner(true)
        assertTrue(profile.disconnected.isEmpty())
        audio.mode = AudioManager.MODE_NORMAL
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(listOf(phone), profile.disconnected)
        shadowOf(adapter).setBondedDevices(setOf(other))
        handoff.close()
        assertTrue(profile.connected.isEmpty())
    }

    @Test fun unsupportedPlaybackQueryDoesNotPretendConnectedPhoneIsPlaying() {
        profile.queryFails = true
        val playing = mutableListOf<Boolean>()
        val handoff = owner(false, playing::add)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(playing.isEmpty())
        assertEquals(1, log.count { it.contains("playback state unavailable") })
        handoff.close()
        assertTrue(profile.disconnected.isEmpty())
    }

    @Test fun missingRestoreMethodPreventsDisconnectAndRepeatedAttempts() {
        val sink = DisconnectOnlyProfile(profile)
        shadowOf(adapter).setProfileProxy(11, sink)
        val handoff = owner(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(profile.disconnected.isEmpty())
        assertEquals(BluetoothProfile.STATE_CONNECTED, profile.getConnectionState(phone))
        assertEquals(1, log.count { it.contains("handoff fallback: NoSuchMethodException") })
        handoff.setSuppressed(false)
        handoff.close()
        assertTrue(profile.connected.isEmpty())
        assertEquals(BluetoothProfile.STATE_DISCONNECTED, profile.getConnectionState(other))
    }

    private fun nativeOwner() = BluetoothAudioHandoff(context, phone.address, log::add, nativeOutput = true).also {
        owners.add(it); it.setSuppressed(false); it.start(); shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun explicitNativeModeConnectsOnlyTheSelectedBondedPhoneAndWaitsForConfirmation() {
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        nativeOwner()
        assertEquals(listOf(phone), profile.connected)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(log.any { it.contains("connection confirmed") })
        profile.states[phone] = BluetoothProfile.STATE_CONNECTED
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals(1, log.count { it.contains("connection confirmed") })
        assertTrue(profile.disconnected.isEmpty())
    }

    @Test fun nativeRecoveryDoesNotEvictAnotherPhonesMusicConnection() {
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        profile.states[other] = BluetoothProfile.STATE_CONNECTED
        nativeOwner()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue(profile.connected.isEmpty())
        assertTrue(profile.disconnected.isEmpty())
        assertTrue(log.any { it.contains("another phone owns music") })
    }

    @Test fun carPlayHandoffKeepsTheRouteIfRestoringWouldRiskAnotherPhone() {
        profile.states[other] = BluetoothProfile.STATE_CONNECTED
        val handoff = owner(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertTrue(profile.disconnected.isEmpty())
        handoff.close()
        assertTrue(profile.connected.isEmpty())
        assertTrue(log.any { it.contains("safe restoration is not assured") })
    }

    @Test fun nativeRecoveryDefersDuringCallsAndCancelsWhenClosed() {
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        val audio = context.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_CALL
        val owner = nativeOwner()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(profile.connected.isEmpty())
        owner.close()
        audio.mode = AudioManager.MODE_NORMAL
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue(profile.connected.isEmpty())
    }

    @Test fun missingNativeConnectAndMissingPlatformComponentNeverPretendMusicWasRestored() {
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        shadowOf(adapter).setProfileProxy(11, DisconnectOnlyProfile(profile))
        val owner = nativeOwner()
        repeat(3) { owner.setSuppressed(false) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(1, log.count { it.contains("platform Bluetooth music reconnect accepted=false") })
        assertTrue(log.any { it.contains("compatible authorization component 6 is required") })
        assertFalse(log.any { it.contains("connection confirmed") })
        assertTrue(profile.connected.isEmpty())
    }

    @Test fun acceptedConnectionThatNeverCompletesTimesOutWithoutFalseConfirmation() {
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        nativeOwner()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(listOf(phone), profile.connected)
        assertEquals(1, log.count { it.contains("reconnect timed out") })
        assertFalse(log.any { it.contains("connection confirmed") })
    }

    @Test fun manualMusicReconnectionCanBeConfirmedAfterConnectApiWasUnavailable() {
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        shadowOf(adapter).setProfileProxy(11, DisconnectOnlyProfile(profile))
        nativeOwner()
        assertTrue(log.any { it.contains("platform Bluetooth music reconnect accepted=false") })
        profile.states[phone] = BluetoothProfile.STATE_CONNECTED
        context.sendBroadcast(Intent(ACTION_CONNECTION).putExtra(BluetoothDevice.EXTRA_DEVICE, phone)
            .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(log.any { it.contains("connection confirmed") })
        assertTrue(profile.disconnected.isEmpty())
    }

    @Test fun connectedBroadcastAfterRestoreDeadlineStillConfirmsTheRealConnection() {
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        nativeOwner()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue(log.any { it.contains("reconnect timed out") })
        assertFalse(log.any { it.contains("connection confirmed") })
        profile.states[phone] = BluetoothProfile.STATE_CONNECTED
        context.sendBroadcast(Intent(ACTION_CONNECTION).putExtra(BluetoothDevice.EXTRA_DEVICE, phone)
            .putExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_CONNECTED))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, log.count { it.contains("connection confirmed") })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(listOf(phone), profile.connected)
    }

    @Test fun rejectedNativeConnectionRetriesOnlyWithinTheRestoreWindow() {
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        profile.acceptConnect = false
        nativeOwner()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        val requests = profile.connected.size
        assertTrue(requests in 2..6)
        assertTrue(profile.connected.all { it == phone })
        assertEquals(1, log.count { it.contains("reconnect timed out") })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(requests, profile.connected.size)
        assertFalse(log.any { it.contains("connection confirmed") })
    }

    @Test fun nativePreparationWaitsForSoleSelectedPhoneAndNoCallThenRunsOnlyOnce() {
        var preparations = 0
        var prepares = 0
        var closes = 0
        lateinit var connection: NativeBluetoothAudioPreparation.Connection
        val audio = context.getSystemService(AudioManager::class.java)
        profile.states[other] = BluetoothProfile.STATE_CONNECTED
        val handoff = BluetoothAudioHandoff(context, phone.address, log::add, nativeOutput = true,
            nativePreparationFactory = { app, eligible, report ->
                preparations++
                NativeBluetoothAudioPreparation(app, eligible, report, { true }, { callback ->
                    connection = callback
                    object : NativeBluetoothAudioPreparation.Browser {
                        override fun connect() { }
                        override fun prepare() { prepares++ }
                        override fun close() { closes++ }
                    }
                })
            }).also { owners.add(it); it.setSuppressed(false); it.start() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, preparations)
        profile.states[other] = BluetoothProfile.STATE_DISCONNECTED
        audio.mode = AudioManager.MODE_IN_CALL
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(0, preparations)
        audio.mode = AudioManager.MODE_NORMAL
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, preparations)
        connection.connected()
        assertEquals(1, prepares)
        assertEquals(1, closes)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(1, preparations)
        assertTrue(profile.disconnected.isEmpty())
        assertTrue(profile.connected.isEmpty())
        handoff.close()
    }

    @Test fun carPlayAudioNeverPreparesTheNativeBluetoothPlayer() {
        var preparations = 0
        val handoff = BluetoothAudioHandoff(context, phone.address, log::add,
            nativePreparationFactory = { _, _, _ -> preparations++; error("CarPlay audio must not prepare native audio") })
            .also { owners.add(it); it.setSuppressed(false); it.start() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(0, preparations)
        handoff.close()
    }

    // Reproduces build 85's IHU353G: disconnect is visible, connect cannot be resolved.
    @Test fun hiddenConnectUsesASessionLeaseAndRechecksActualDisconnectionBeforeMusicIsReady() {
        shadowOf(adapter).setProfileProxy(11, DisconnectOnlyProfile(profile))
        val states = mutableListOf<Boolean>()
        lateinit var changed: (Boolean, String) -> Unit
        var released = 0
        val handoff = BluetoothAudioHandoff(context, phone.address, log::add,
            onHandoffReady = states::add,
            platformLeaseFactory = { _, _, callback ->
                changed = callback
                object : BluetoothAudioLeaseClient {
                    override fun start() {}
                    override fun close() { released++ }
                }
            }).also { owners.add(it); it.setSuppressed(true); it.start() }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(false), states)
        changed(true, "system request accepted")
        assertEquals(listOf(false), states) // Still connected according to the actual profile.
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        changed(true, "disconnection confirmed")
        assertEquals(listOf(false, true), states)
        assertTrue(profile.disconnected.isEmpty())
        handoff.close()
        assertEquals(1, released)
        changed(false, "late callback")
        assertEquals(listOf(false, true), states)
    }

    @Test fun cancellationBeforeThePlatformReplyCannotMarkTheOldPhoneReady() {
        shadowOf(adapter).setProfileProxy(11, DisconnectOnlyProfile(profile))
        val states = mutableListOf<Boolean>()
        lateinit var changed: (Boolean, String) -> Unit
        val handoff = BluetoothAudioHandoff(context, phone.address, log::add,
            onHandoffReady = states::add,
            platformLeaseFactory = { _, _, callback ->
                changed = callback
                object : BluetoothAudioLeaseClient { override fun start() {}; override fun close() {} }
            }).also { owners.add(it); it.setSuppressed(true); it.start() }
        shadowOf(Looper.getMainLooper()).idle()
        handoff.close()
        profile.states[phone] = BluetoothProfile.STATE_DISCONNECTED
        changed(true, "late disconnection")
        assertEquals(listOf(false), states)
    }

    class DisconnectOnlyProfile(private val delegate: MusicProfile) : BluetoothProfile by delegate {
        fun disconnect(device: BluetoothDevice): Boolean = delegate.disconnect(device)
    }

    /** Public methods mirror the hidden A2DP sink surface without invoking a car service. */
    class MusicProfile(val states: MutableMap<BluetoothDevice, Int>) : BluetoothProfile {
        val disconnected = mutableListOf<BluetoothDevice>()
        val connected = mutableListOf<BluetoothDevice>()
        var playing = false
        var queryFails = false
        var acceptConnect = true
        override fun getConnectedDevices() = states.filterValues { it == BluetoothProfile.STATE_CONNECTED }.keys.toList()
        override fun getDevicesMatchingConnectionStates(requested: IntArray) = states.filterValues { it in requested }.keys.toList()
        override fun getConnectionState(device: BluetoothDevice) = states[device] ?: BluetoothProfile.STATE_DISCONNECTED
        fun disconnect(device: BluetoothDevice): Boolean { disconnected.add(device); states[device] = BluetoothProfile.STATE_DISCONNECTING; return true }
        fun connect(device: BluetoothDevice): Boolean {
            connected.add(device)
            if (acceptConnect) states[device] = BluetoothProfile.STATE_CONNECTING
            return acceptConnect
        }
        fun isA2dpPlaying(device: BluetoothDevice): Boolean { if (queryFails) error("unsupported"); return playing && getConnectionState(device) == BluetoothProfile.STATE_CONNECTED }
    }

    companion object {
        const val ACTION_PLAYING = "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED"
        const val ACTION_CONNECTION = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
    }
}

package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.time.Duration
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.hud.NavigationOutputs
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbException
import org.mockito.Mockito.mock
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import com.shilapi.xcertplay.network.CarPlayVpnService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControllerUsbServiceTest {
    private val controllers = mutableListOf<CarPlayController>()
    private val statuses = CopyOnWriteArrayList<CarPlayStatus>()

    @After fun tearDown() {
        controllers.forEach { it.close(); assertTrue(it.awaitClosed(2_000)) }
        NavigationOutputs.endNow()
    }

    @Test fun wirelessLocalCanBeCreatedAndReachMfiWithoutAUsbService() {
        val context = UsbContext(false)
        val controller = controller(context, CarPlayTransport.WIRELESS, MfiTarget.LOCAL)
        controller.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.contains(CarPlayStatus.DiscoveringMfi))
        assertFalse(statuses.filterIsInstance<CarPlayStatus.Failed>().any { it.message.contains("USB service") })
        assertEquals(0, context.receivers.size)
    }

    @Test fun wiredStartReportsMissingUsbServiceWithoutRegisteringReceivers() {
        missingServiceFailsNormally(CarPlayTransport.WIRED, MfiTarget.LOCAL)
    }

    @Test fun wirelessCh341ReportsMissingUsbServiceWithoutRegisteringReceivers() {
        missingServiceFailsNormally(CarPlayTransport.WIRELESS, MfiTarget.USB_CH341)
    }

    @Test fun reconnectMfiAlsoReportsMissingUsbServiceNormally() {
        val context = UsbContext(false)
        val controller = controller(context, CarPlayTransport.WIRED, MfiTarget.LOCAL)
        controller.reconnectMfi()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        assertTrue((statuses.single() as CarPlayStatus.Failed).message.contains("USB service"))
        assertEquals(0, context.receivers.size)
    }

    @Test fun availableWiredUsbStillRegistersAndClosesBothIphoneReceivers() {
        val context = UsbContext(true)
        val controller = controller(context, CarPlayTransport.WIRED, MfiTarget.LOCAL)
        controller.start()
        assertEquals(2, context.receivers.size)
        assertTrue(context.receivers.values.any { it.hasAction("${context.packageName}.IPHONE_USB_PERMISSION") })
        assertTrue(context.receivers.values.any { it.hasAction("android.hardware.usb.action.USB_DEVICE_ATTACHED") })
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
        assertEquals(0, context.receivers.size)
        assertEquals(2, context.unregistered)
    }

    @Test fun availableWirelessCh341StillWaitsForMfiAndClosesItsPermissionReceiver() {
        val context = UsbContext(true)
        val controller = controller(context, CarPlayTransport.WIRELESS, MfiTarget.USB_CH341)
        controller.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.contains(CarPlayStatus.WaitingForMfi))
        assertEquals(1, context.receivers.size)
        assertFalse(context.receivers.values.any { it.hasAction("android.hardware.usb.action.USB_DEVICE_ATTACHED") })
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
        assertEquals(0, context.receivers.size)
        assertEquals(1, context.unregistered)
    }

    @Test fun wiredBindingTargetsTheProtectedVpnServiceAndHandlesBindFailure() {
        val context = UsbContext(true)
        val controller = controller(context, CarPlayTransport.WIRED, MfiTarget.LOCAL)
        assertNull(ReflectionHelpers.callInstanceMethod<CarPlayVpnService?>(controller, "awaitVpnService"))
        assertEquals(CarPlayVpnService::class.java.name, context.boundIntent?.component?.className)
    }

    @Test fun cancellationWakesPendingVpnBindingAndIgnoresItsLateCallback() {
        val context = UsbContext(true, acceptBinding = true)
        val controller = controller(context, CarPlayTransport.WIRED, MfiTarget.LOCAL)
        val waiting = FutureTask { ReflectionHelpers.callInstanceMethod<CarPlayVpnService?>(controller, "awaitVpnService") }
        Thread(waiting, "test-vpn-bind").start()
        assertTrue(context.bindCalled.await(2, TimeUnit.SECONDS))
        controller.close()
        assertNull(waiting.get(2, TimeUnit.SECONDS))
        assertTrue(controller.awaitClosed(2_000))
        assertEquals(1, context.unbound)
        val service = Robolectric.buildService(CarPlayVpnService::class.java).create()
        try {
            context.boundConnection!!.onServiceConnected(context.boundIntent!!.component!!, service.get().onBind(Intent())!!)
            assertFalse(service.get().isAttached())
        } finally { service.destroy() }
    }

    // #335: after Save and reconnect an iPhone still in CarPlay mode ignored the mode request, and only a USB
    // attach ended the wait, so wired CarPlay sat on "waiting for the CarPlay USB configuration" for minutes.
    @Test fun anUnansweredModeRequestLooksAtTheIphoneAgainAfterTheWait() {
        val context = UsbContext(true)
        val usb = context.getSystemService(UsbManager::class.java)
        val iphone = usbDevice(0x05ac, 0x12a8, "/dev/bus/usb/001/004")
        shadowOf(usb).addOrUpdateUsbDevice(iphone, true)
        val logs = CopyOnWriteArrayList<String>()
        val controller = waitingForReenumeration(context, logs)

        ReflectionHelpers.callInstanceMethod<Unit>(controller, "watchReenumeration",
            ReflectionHelpers.ClassParameter.from(UsbDevice::class.java, iphone))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CarPlayController.REENUMERATION_WAIT_MILLIS - 100))
        assertTrue(logs.none { it.contains("USB transition unanswered") || it.contains("permission already granted") })

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue(logs.any { it.contains("USB transition unanswered after 10000ms count=1; checking the iPhone again") })
        assertTrue(logs.any { it == "wired iPhone USB permission already granted" })
    }

    @Test fun anAttachOrAnIphoneThatLeftTheBusEndsTheWaitWithoutALook() {
        val context = UsbContext(true)
        val usb = context.getSystemService(UsbManager::class.java)
        val iphone = usbDevice(0x05ac, 0x12a8, "/dev/bus/usb/001/004")
        shadowOf(usb).addOrUpdateUsbDevice(iphone, true)
        val logs = CopyOnWriteArrayList<String>()
        val controller = waitingForReenumeration(context, logs)

        // The iPhone came back on its own: its attach answered the request.
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "watchReenumeration",
            ReflectionHelpers.ClassParameter.from(UsbDevice::class.java, iphone))
        ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicInteger>(controller, "availabilityPollGeneration")
            .incrementAndGet()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertTrue(logs.none { it.contains("USB transition unanswered") })

        // The iPhone is off the bus while it re-enumerates: its attach will end the wait.
        shadowOf(usb).removeUsbDevice(iphone)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "watchReenumeration",
            ReflectionHelpers.ClassParameter.from(UsbDevice::class.java, iphone))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertTrue(logs.none { it.contains("USB transition unanswered") })
    }

    private fun waitingForReenumeration(context: Context, logs: MutableList<String>): CarPlayController {
        val controller = controller(context, CarPlayTransport.WIRED, MfiTarget.LOCAL, logs)
        ReflectionHelpers.setField(controller, "phase",
            Class.forName("com.shilapi.xcertplay.orchestration.CarPlayController\$Phase").enumConstants
                .single { (it as Enum<*>).name == "REENUMERATION" })
        ReflectionHelpers.setField(controller, "reenumerationAttempts", 1)
        return controller
    }

    private fun usbDevice(vendor: Int, product: Int, node: String): UsbDevice =
        ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this, "mName", node)
            ReflectionHelpers.setField(this, "mVendorId", vendor)
            ReflectionHelpers.setField(this, "mProductId", product)
            ReflectionHelpers.setField(this, "mConfigurations", emptyArray<android.hardware.usb.UsbConfiguration>())
        }

    @Test fun cancellationPreventsTheDelayedUsbRecheck() {
        val context = UsbContext(true)
        val iphone = usbDevice(0x05ac, 0x12a8, "/dev/bus/usb/001/004")
        shadowOf(context.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(iphone, true)
        val logs = CopyOnWriteArrayList<String>()
        val controller = waitingForReenumeration(context, logs)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "watchReenumeration",
            ReflectionHelpers.ClassParameter.from(UsbDevice::class.java, iphone))
        controller.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertTrue(logs.none { it.contains("USB transition unanswered") })
        assertNull(context.boundIntent)
    }

    @Test fun aNewModeAttemptInvalidatesThePreviousWait() {
        val context = UsbContext(true)
        val iphone = usbDevice(0x05ac, 0x12a8, "/dev/bus/usb/001/004")
        shadowOf(context.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(iphone, true)
        val logs = CopyOnWriteArrayList<String>()
        val controller = waitingForReenumeration(context, logs)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "watchReenumeration",
            ReflectionHelpers.ClassParameter.from(UsbDevice::class.java, iphone))
        ReflectionHelpers.setField(controller, "reenumerationAttempts", 2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertTrue(logs.none { it.contains("USB transition unanswered") })
    }

    @Test fun aDifferentIphoneCannotSatisfyTheOriginalWait() {
        val context = UsbContext(true)
        val iphone = usbDevice(0x05ac, 0x12a8, "/dev/bus/usb/001/004")
        shadowOf(context.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(
            usbDevice(0x05ac, 0x12a8, "/dev/bus/usb/001/005"), true)
        val logs = CopyOnWriteArrayList<String>()
        val controller = waitingForReenumeration(context, logs)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "watchReenumeration",
            ReflectionHelpers.ClassParameter.from(UsbDevice::class.java, iphone))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertTrue(logs.none { it.contains("USB transition unanswered") })
    }

    @Test fun usbRecheckStillRequiresSystemPermissionAndDoesNotBindVpn() {
        val context = UsbContext(true)
        val iphone = usbDevice(0x05ac, 0x12a8, "/dev/bus/usb/001/004")
        shadowOf(context.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(iphone, false)
        val logs = CopyOnWriteArrayList<String>()
        val controller = waitingForReenumeration(context, logs)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "watchReenumeration",
            ReflectionHelpers.ClassParameter.from(UsbDevice::class.java, iphone))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertTrue(logs.any { it.contains("USB transition unanswered") })
        assertTrue(statuses.contains(CarPlayStatus.RequestingIphonePermission))
        assertTrue(logs.none { it.contains("USB permission already granted") })
        assertNull(context.boundIntent)
    }

    @Test fun acceptedModeRequestActuallyArmsTheTenSecondRecheck() {
        val pending = beginPendingModeRequest()
        pending.callbacks.single()(IphoneUsbHost.TransitionResult.ReenumerationRequested)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(9_900))
        assertEquals(0, pending.permissionChecks)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(1, pending.permissionChecks)
        assertEquals("The existing configuration policy allows only the next bounded attempt", 2,
            ReflectionHelpers.getField<Int>(pending.controller, "reenumerationAttempts"))
    }

    @Test fun modeRequestSuccessArrivingAfterCloseCannotRestoreTheWait() {
        val pending = beginPendingModeRequest()
        pending.controller.close()
        pending.callbacks.single()(IphoneUsbHost.TransitionResult.ReenumerationRequested)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertFalse(statuses.contains(CarPlayStatus.WaitingForReenumeration))
        assertEquals(0, pending.permissionChecks)
    }

    @Test fun anOldModeRequestFailureCannotFailItsReplacement() {
        val pending = beginPendingModeRequest()
        ReflectionHelpers.callInstanceMethod<Unit>(pending.controller, "beginReenumeration",
            ReflectionHelpers.ClassParameter.from(UsbDevice::class.java, pending.phone),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        assertEquals(2, pending.callbacks.size)
        pending.callbacks.first()(IphoneUsbHost.TransitionResult.Failed(IphoneUsbException.TimedOut("old request")))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.none { it is CarPlayStatus.Failed })
        assertEquals(2, ReflectionHelpers.getField<Int>(pending.controller, "reenumerationAttempts"))
        assertEquals(0, pending.permissionChecks)
    }

    private class PendingMode(val controller: CarPlayController, val phone: UsbDevice) {
        val callbacks = mutableListOf<(IphoneUsbHost.TransitionResult) -> Unit>()
        var permissionChecks = 0
    }

    private fun beginPendingModeRequest(): PendingMode {
        val pending = PendingMode(controller(UsbContext(true), CarPlayTransport.WIRED, MfiTarget.LOCAL),
            usbDevice(0x05ac, 0x12a8, "/dev/bus/usb/001/004"))
        val host = mock(IphoneUsbHost::class.java) { call ->
            when (call.method.name) {
                "requestCarPlayReenumerationAsync" -> {
                    pending.callbacks.add(call.getArgument(2))
                    null
                }
                "discover" -> listOf(pending.phone)
                "requestPermission" -> {
                    pending.permissionChecks++
                    IphoneUsbHost.PermissionRequest.AlreadyGranted(pending.phone)
                }
                else -> null
            }
        }
        ReflectionHelpers.setField(pending.controller, "iphoneHost\$delegate", lazyOf(host))
        ReflectionHelpers.callInstanceMethod<Unit>(pending.controller, "beginReenumeration",
            ReflectionHelpers.ClassParameter.from(UsbDevice::class.java, pending.phone),
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        return pending
    }

    private fun missingServiceFailsNormally(transport: CarPlayTransport, target: MfiTarget) {
        val context = UsbContext(false)
        val controller = controller(context, transport, target)
        controller.start()
        shadowOf(Looper.getMainLooper()).idle()
        val failure = statuses.single() as CarPlayStatus.Failed
        assertTrue(failure.message.contains("USB service"))
        assertEquals(0, context.receivers.size)
    }

    private fun controller(context: Context, transport: CarPlayTransport, target: MfiTarget,
        logs: MutableList<String>? = null): CarPlayController =
        CarPlayController(context, CarPlayRuntimeConfig(
            mfiTarget = target, transport = transport,
            ch341Devices = if (target == MfiTarget.USB_CH341) listOf(UsbDeviceId(0x1a86, 0x7523)) else emptyList(),
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
        ), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {
                override fun onDebugLog(message: String) { logs?.add(message) }
            }, object : AirPlayMediaHandler {}, statuses::add).also(controllers::add)

    private class UsbContext(private val hasUsb: Boolean, private val acceptBinding: Boolean = false) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val receivers = mutableMapOf<BroadcastReceiver, IntentFilter>()
        var unregistered = 0
        val bindCalled = CountDownLatch(1)
        var boundIntent: Intent? = null
        var boundConnection: ServiceConnection? = null
        var unbound = 0
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            boundIntent = service
            boundConnection = conn
            bindCalled.countDown()
            return acceptBinding
        }
        override fun unbindService(conn: ServiceConnection) { unbound++ }
        override fun getSystemService(name: String): Any? =
            if (name == USB_SERVICE && !hasUsb) null else super.getSystemService(name)
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
            if (receiver != null) receivers[receiver] = filter
            return super.registerReceiver(receiver, filter)
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            if (receivers.remove(receiver) != null) unregistered++
            super.unregisterReceiver(receiver)
        }
    }
}

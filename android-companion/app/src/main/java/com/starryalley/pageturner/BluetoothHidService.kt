package com.starryalley.pageturner

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import java.util.concurrent.CopyOnWriteArrayList

class BluetoothHidService : Service() {

    companion object {
        private const val TAG = "PageTurnerService"
        private const val CHANNEL_ID = "page_turner_channel"
        private const val NOTIFICATION_ID = 101

        const val GARMIN_APP_UUID = "a94b3c7d-de34-4b5c-897a-624e5482312b"

        // HID Keyboard Report Descriptor (8-byte Input Report: Modifier, Reserved, 6 Keycodes)
        private val HID_KEYBOARD_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x06.toByte(), // Usage (Keyboard)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x01.toByte(), //   Report ID (1)
            0x05.toByte(), 0x07.toByte(), //   Usage Page (Keyboard)
            0x19.toByte(), 0xE0.toByte(), //   Usage Minimum (Keyboard LeftControl)
            0x29.toByte(), 0xE7.toByte(), //   Usage Maximum (Keyboard Right GUI)
            0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //   Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(), //   Report Size (1)
            0x95.toByte(), 0x08.toByte(), //   Report Count (8)
            0x81.toByte(), 0x02.toByte(), //   Input (Data,Var,Abs)
            0x95.toByte(), 0x01.toByte(), //   Report Count (1)
            0x75.toByte(), 0x08.toByte(), //   Report Size (8)
            0x81.toByte(), 0x01.toByte(), //   Input (Constant) (reserved byte)
            0x95.toByte(), 0x05.toByte(), //   Report Count (5)
            0x75.toByte(), 0x01.toByte(), //   Report Size (1)
            0x05.toByte(), 0x08.toByte(), //   Usage Page (LEDs)
            0x19.toByte(), 0x01.toByte(), //   Usage Minimum (Num Lock)
            0x29.toByte(), 0x05.toByte(), //   Usage Maximum (Kana)
            0x91.toByte(), 0x02.toByte(), //   Output (Data,Var,Abs)
            0x95.toByte(), 0x01.toByte(), //   Report Count (1)
            0x75.toByte(), 0x03.toByte(), //   Report Size (3)
            0x91.toByte(), 0x01.toByte(), //   Output (Constant)
            0x95.toByte(), 0x06.toByte(), //   Report Count (6)
            0x75.toByte(), 0x08.toByte(), //   Report Size (8)
            0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
            0x25.toByte(), 0x65.toByte(), //   Logical Maximum (101)
            0x05.toByte(), 0x07.toByte(), //   Usage Page (Keyboard)
            0x19.toByte(), 0x00.toByte(), //   Usage Minimum (Reserved)
            0x29.toByte(), 0x65.toByte(), //   Usage Maximum (Keyboard Application)
            0x81.toByte(), 0x00.toByte(), //   Input (Data,Ary,Abs)
            0xC0.toByte()                 // End Collection
        )

        // Keycodes (standard USB HID keyboard Usage IDs)
        const val HID_KEY_LEFT_ARROW: Byte = 0x50.toByte()
        const val HID_KEY_RIGHT_ARROW: Byte = 0x4F.toByte()
        const val HID_KEY_PAGE_UP: Byte = 0x4B.toByte()
        const val HID_KEY_PAGE_DOWN: Byte = 0x4E.toByte()
        const val HID_KEY_F4: Byte = 0x3D.toByte()
        const val HID_KEY_SPACE: Byte = 0x2C.toByte()
        const val HID_KEY_BACKSPACE: Byte = 0x2A.toByte()
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logList = CopyOnWriteArrayList<String>()
    
    // Key settings
    var mappingMode: Int = 1 // 1: Arrows, 2: PageUp/Down, 3: Backspace/Space
        set(value) {
            field = value
            addLog("Mapping updated: " + getMappingDescription())
        }

    // Connection mode and KOReader configuration
    var connectionMode: Int = 1 // 1: Bluetooth HID, 2: KOReader HTTP
    var koreaderIp: String = ""
    var koreaderPort: Int = 8080
    var isKoreaderConnected: Boolean = false

    // Callbacks for UI updates
    interface ServiceListener {
        fun onLogAdded()
        fun onConnectionStatesChanged()
    }
    
    private val listeners = CopyOnWriteArrayList<ServiceListener>()

    // Bluetooth HID State variables
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDeviceProxy: BluetoothHidDevice? = null
    private var connectedReaderDevice: BluetoothDevice? = null
    private var isHidRegistered = false
    private var hidConnectionState = BluetoothProfile.STATE_DISCONNECTED

    // Garmin ConnectIQ State variables
    private var connectIQ: ConnectIQ? = null
    private var garminDevice: IQDevice? = null
    private var isGarminConnected = false
    
    inner class LocalBinder : Binder() {
        fun getService(): BluetoothHidService = this@BluetoothHidService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startServiceForeground()
        
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            addLog("Bluetooth is not supported on this device.")
        }
        
        initializeBluetoothHid()
        initializeGarminSdk()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep running until explicitly stopped
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        addLog("Service stopping...")
        unregisterBluetoothHid()
        releaseGarminSdk()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // Foreground service notification setup
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Garmin Page Turner Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Garmin Watch to E-Reader Bluetooth bridge active."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startServiceForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Garmin Page Turner Active")
            .setContentText("Relaying page turns from watch to e-reader...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // Bluetooth HID implementation
    private fun initializeBluetoothHid() {
        val adapter = bluetoothAdapter ?: return
        addLog("Initializing Bluetooth HID Profile...")
        
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDeviceProxy = proxy as BluetoothHidDevice
                    addLog("HID Device Profile Proxy bound.")
                    registerHidApp()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    addLog("HID Device Profile Proxy unbound.")
                    hidDeviceProxy = null
                    isHidRegistered = false
                    notifyStateChange()
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun registerHidApp() {
        val hid = hidDeviceProxy ?: return
        if (isHidRegistered) {
            addLog("HID App already registered.")
            return
        }

        addLog("Registering HID App...")
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "Garmin Page Turner",
            "Relays Garmin watch gestures as keystrokes",
            "Antigravity",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            HID_KEYBOARD_REPORT_DESCRIPTOR
        )

        val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                isHidRegistered = registered
                addLog("HID registration status updated: $registered")
                if (registered) {
                    checkForAlreadyConnectedHidDevices()
                }
                notifyStateChange()
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                hidConnectionState = state
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    connectedReaderDevice = device
                    addLog("Connected to E-Reader: ${device.name ?: device.address}")
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    addLog("Disconnected from E-Reader: ${device.name ?: device.address}")
                    if (connectedReaderDevice == device) {
                        connectedReaderDevice = null
                    }
                }
                notifyStateChange()
            }

            override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
                // Return empty keyboard report
                hid.replyReport(device, type, id, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
            }

            override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
                // Host LEDs (NumLock, ScrollLock, CapsLock) - Ignore
            }

            override fun onVirtualCableUnplug(device: BluetoothDevice) {
                addLog("Virtual Cable Unplugged: ${device.address}")
                connectedReaderDevice = null
                hidConnectionState = BluetoothProfile.STATE_DISCONNECTED
                notifyStateChange()
            }
        }

        try {
            val executor = ContextCompat.getMainExecutor(this)
            val success = hid.registerApp(sdpSettings, null, null, executor, callback)
            if (!success) {
                addLog("HID registerApp() returned false. Bluetooth might be busy.")
            }
        } catch (e: Exception) {
            addLog("Error registering HID application: ${e.message}")
        }
    }

    fun checkForAlreadyConnectedHidDevices() {
        val hid = hidDeviceProxy ?: return
        if (!isHidRegistered) return
        
        try {
            val connectedDevices = hid.getDevicesMatchingConnectionStates(intArrayOf(BluetoothProfile.STATE_CONNECTED))
            if (connectedDevices.isNotEmpty()) {
                val device = connectedDevices[0]
                connectedReaderDevice = device
                hidConnectionState = BluetoothProfile.STATE_CONNECTED
                addLog("Detected existing HID connection to: ${device.name ?: device.address}")
                notifyStateChange()
            } else {
                addLog("No existing HID connections detected.")
            }
        } catch (e: Exception) {
            addLog("Error checking connected devices: ${e.message}")
        }
    }

    private fun unregisterBluetoothHid() {
        val hid = hidDeviceProxy ?: return
        if (isHidRegistered) {
            try {
                hid.unregisterApp()
                isHidRegistered = false
                addLog("HID Application unregistered.")
            } catch (e: Exception) {
                addLog("Error unregistering HID: ${e.message}")
            }
        }
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
    }

    fun connectToDevice(device: BluetoothDevice) {
        val hid = hidDeviceProxy ?: return
        if (!isHidRegistered) {
            addLog("HID App is not registered. Attempting to register now...")
            registerHidApp()
            return
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            addLog("Connecting to ${device.name ?: device.address}...")
            hid.connect(device)
        } else {
            addLog("Missing BLUETOOTH_CONNECT permission.")
        }
    }

    fun disconnectDevice() {
        val hid = hidDeviceProxy ?: return
        val dev = connectedReaderDevice ?: return
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            addLog("Disconnecting from ${dev.name ?: dev.address}...")
            hid.disconnect(dev)
        } else {
            addLog("Missing BLUETOOTH_CONNECT permission.")
        }
    }

    // Garmin SDK communications
    private fun initializeGarminSdk() {
        addLog("Initializing Garmin ConnectIQ SDK...")
        connectIQ = ConnectIQ.getInstance(this, ConnectIQ.IQConnectType.WIRELESS)
        connectIQ?.initialize(this, true, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                addLog("Garmin SDK is ready.")
                startListeningForGarminDevices()
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                addLog("Garmin SDK failed to initialize: ${status.name}")
            }

            override fun onSdkShutDown() {
                addLog("Garmin SDK was shut down.")
            }
        })
    }

    private fun startListeningForGarminDevices() {
        val sdk = connectIQ ?: return
        val devices = sdk.knownDevices ?: emptyList()
        addLog("Scanning for known paired Garmin watches (Found: ${devices.size})...")

        if (devices.isEmpty()) {
            addLog("No paired Garmin devices found.")
            return
        }

        for (device in devices) {
            try {
                // Check current status immediately
                val currentStatus = sdk.getDeviceStatus(device)
                addLog("Garmin Watch '${device.friendlyName}' current status: ${currentStatus.name}")

                if (currentStatus == IQDevice.IQDeviceStatus.CONNECTED) {
                    garminDevice = device
                    isGarminConnected = true
                    registerGarminAppEvents(device)
                }

                sdk.registerForDeviceEvents(device) { dev, status ->
                    addLog("Garmin Watch '${dev.friendlyName}' status event: ${status.name}")
                    
                    val isConnected = (status == IQDevice.IQDeviceStatus.CONNECTED)
                    
                    // Logic to avoid overwriting a connected watch with a disconnected status from another watch
                    if (isConnected) {
                        garminDevice = dev
                        isGarminConnected = true
                        registerGarminAppEvents(dev)
                        notifyStateChange()
                    } else if (dev == garminDevice) {
                        // Only set disconnected if it's the currently tracked device
                        isGarminConnected = false
                        notifyStateChange()
                        
                        // If our main device disconnected, check if another one is already connected
                        checkForConnectedGarminDevice()
                    }
                }
            } catch (e: Exception) {
                addLog("Error listening to Garmin device: ${e.message}")
            }
        }
        notifyStateChange()
    }

    private fun checkForConnectedGarminDevice() {
        val sdk = connectIQ ?: return
        val devices = sdk.knownDevices ?: return
        for (device in devices) {
            if (sdk.getDeviceStatus(device) == IQDevice.IQDeviceStatus.CONNECTED) {
                addLog("Switching to already connected Garmin watch: ${device.friendlyName}")
                garminDevice = device
                isGarminConnected = true
                registerGarminAppEvents(device)
                notifyStateChange()
                return
            }
        }
    }

    private fun registerGarminAppEvents(device: IQDevice) {
        val sdk = connectIQ ?: return
        val app = IQApp(GARMIN_APP_UUID)
        
        addLog("Registering watch mailbox listener for '${device.friendlyName}'...")
        try {
            sdk.registerForAppEvents(device, app) { _, _, messageData, status ->
                if (status == ConnectIQ.IQMessageStatus.SUCCESS) {
                    for (msg in messageData) {
                        if (msg is Map<*, *>) {
                            val cmd = msg["command"] as? String
                            if (cmd != null) {
                                triggerKeyForCommand(cmd)
                            }
                        }
                    }
                } else {
                    addLog("Mailbox message error: ${status.name}")
                }
            }
        } catch (e: Exception) {
            addLog("Garmin registration crash: ${e.message}")
        }
    }

    private fun releaseGarminSdk() {
        val sdk = connectIQ ?: return
        val devices = sdk.knownDevices ?: emptyList()
        val app = IQApp(GARMIN_APP_UUID)
        
        for (device in devices) {
            try {
                sdk.unregisterForApplicationEvents(device, app)
                sdk.unregisterForDeviceEvents(device)
            } catch (e: Exception) {
                // Ignore during shutdown
            }
        }
        connectIQ = null
        addLog("Garmin SDK released.")
    }

    // Map command and transmit command via Bluetooth HID keystroke or HTTP
    private fun triggerKeyForCommand(command: String) {
        addLog("Watch Command Received: '$command'")
        if (connectionMode == 2) {
            sendKoReaderCommand(command)
        } else {
            val keyCode: Byte = when (command.lowercase()) {
                "left" -> getLeftKeyCode()
                "right" -> getRightKeyCode()
                "refresh" -> HID_KEY_F4
                else -> {
                    addLog("Unknown command string ignored: $command")
                    return
                }
            }

            // Send keystroke IMMEDIATELY before logging to minimize latency
            sendKeystroke(keyCode)
        }
    }

    private fun getLeftKeyCode(): Byte {
        return when (mappingMode) {
            2 -> HID_KEY_PAGE_UP
            3 -> HID_KEY_BACKSPACE
            else -> HID_KEY_LEFT_ARROW
        }
    }

    private fun getRightKeyCode(): Byte {
        return when (mappingMode) {
            2 -> HID_KEY_PAGE_DOWN
            3 -> HID_KEY_SPACE
            else -> HID_KEY_RIGHT_ARROW
        }
    }

    fun getMappingDescription(): String {
        return when (mappingMode) {
            2 -> "Left: Page Up | Right: Page Down"
            3 -> "Left: Backspace | Right: Space"
            else -> "Left: Left Arrow | Right: Right Arrow"
        }
    }

    private fun sendKeystroke(keyCode: Byte) {
        val hid = hidDeviceProxy ?: return
        val dev = connectedReaderDevice ?: return

        if (hidConnectionState != BluetoothProfile.STATE_CONNECTED) {
            addLog("Bridge failed: E-Reader keyboard is not connected.")
            return
        }

        // 8-byte standard keyboard report payload
        val pressReport = ByteArray(8)
        pressReport[0] = 0 // Modifier keys
        pressReport[1] = 0 // Reserved
        pressReport[2] = keyCode // First keycode slot

        val releaseReport = ByteArray(8) // All zeroes for release

        try {
            // Send Press
            hid.sendReport(dev, 1, pressReport)
            
            // Release after 20ms (reduced from 40ms)
            mainHandler.postDelayed({
                try {
                    hid.sendReport(dev, 1, releaseReport)
                } catch (e: Exception) {
                    // Ignore release failures in logs to keep path hot
                }
            }, 20)
            
        } catch (e: Exception) {
            addLog("HID press failure: ${e.message}")
        }
    }

    private fun sendKoReaderCommand(command: String) {
        val event = when (command.lowercase()) {
            "left" -> "GotoViewRel/-1"
            "right" -> "GotoViewRel/1"
            "refresh" -> "FullRefresh"
            else -> return
        }

        val ip = koreaderIp
        val port = koreaderPort
        if (ip.isBlank()) {
            addLog("KOReader Mode: IP address is blank.")
            return
        }

        val urlString = "http://$ip:$port/koreader/event/$event"
        addLog("KOReader sending command: $event")
        
        Thread {
            try {
                val url = java.net.URL(urlString)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    addLog("KOReader command '$event' success ($responseCode)")
                } else {
                    addLog("KOReader command '$event' response: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                addLog("KOReader HTTP error: ${e.message}")
            }
        }.start()
    }

    fun connectToKoReader(ip: String, port: Int) {
        koreaderIp = ip
        koreaderPort = port
        addLog("Connecting to KOReader at $ip:$port...")
        
        Thread {
            try {
                val urlString = "http://$ip:$port/koreader/event/GotoViewRel/0"
                val url = java.net.URL(urlString)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                val responseCode = connection.responseCode
                if (responseCode in 200..399) {
                    isKoreaderConnected = true
                    addLog("Connected to KOReader successfully!")
                } else {
                    isKoreaderConnected = false
                    addLog("KOReader connection check failed (HTTP $responseCode)")
                }
                connection.disconnect()
            } catch (e: Exception) {
                isKoreaderConnected = false
                addLog("KOReader connection failed: ${e.message}")
            }
            notifyStateChange()
        }.start()
    }

    fun disconnectKoReader() {
        isKoreaderConnected = false
        addLog("Disconnected from KOReader.")
        notifyStateChange()
    }

    // Helper functions for UI communication
    private fun addLog(message: String) {
        Log.d(TAG, message)
        val formattedMsg = "[${System.currentTimeMillis() % 1000000 / 1000}s] $message"
        mainHandler.post {
            logList.add(0, formattedMsg)
            // Limit logs list to 100 entries to prevent memory leak
            if (logList.size > 100) {
                logList.removeAt(logList.size - 1)
            }
            listeners.forEach { it.onLogAdded() }
        }
    }

    private fun notifyStateChange() {
        mainHandler.post {
            listeners.forEach { it.onConnectionStatesChanged() }
        }
    }

    fun registerListener(listener: ServiceListener) {
        listeners.add(listener)
    }

    fun unregisterListener(listener: ServiceListener) {
        listeners.remove(listener)
    }

    fun getLogs(): List<String> = logList

    fun clearLogs() {
        logList.clear()
        notifyStateChange()
    }

    fun getBluetoothState(): Int = hidConnectionState
    
    fun getGarminState(): Boolean = isGarminConnected
    
    fun getGarminWatchName(): String? = garminDevice?.friendlyName?.takeIf { it.isNotBlank() }

    fun getConnectedReader(): BluetoothDevice? = connectedReaderDevice

    // Returns a list of already paired Bluetooth devices (excluding BLE-only where possible)
    fun getPairedDevices(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return adapter.bondedDevices.toList()
        }
        return emptyList()
    }
}

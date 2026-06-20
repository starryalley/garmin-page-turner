package com.starryalley.pageturner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var hidService: BluetoothHidService? = null
    private var isBound = false

    // State variables for Compose UI binding
    private val isServiceRunning = mutableStateOf(false)
    private val garminConnected = mutableStateOf(false)
    private val garminWatchName = mutableStateOf<String?>(null)
    private val hidConnectionState = mutableStateOf(BluetoothProfile.STATE_DISCONNECTED)
    private val connectedReaderDeviceName = mutableStateOf<String?>(null)
    private val pairedDevices = mutableStateListOf<BluetoothDevice>()
    private val logsList = mutableStateListOf<String>()
    private val mappingMode = mutableStateOf(1)
    private val permissionsGranted = mutableStateOf(false)

    // Connection mode and KOReader state
    private val connectionMode = mutableStateOf(1) // 1: Bluetooth HID, 2: KOReader HTTP
    private val koreaderIp = mutableStateOf("")
    private val koreaderPort = mutableStateOf("8080")
    private val isKoreaderConnected = mutableStateOf(false)

    // State for selected device persistence in UI
    private val selectedDevice = mutableStateOf<BluetoothDevice?>(null)
    private val PREFS_NAME = "PageTurnerPrefs"
    private val KEY_LAST_DEVICE = "last_device_address"
    private val KEY_CONNECTION_MODE = "connection_mode"
    private val KEY_KOREADER_IP = "koreader_ip"
    private val KEY_KOREADER_PORT = "koreader_port"

    // Service Connection lifecycle listener
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothHidService.LocalBinder
            val s = binder.getService()
            hidService = s
            isBound = true
            isServiceRunning.value = true
            
            // Register state listener for live service events
            s.registerListener(serviceListener)
            
            // Initial state load
            syncServiceState()
            addLocalLog("Bound to background service.")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hidService?.unregisterListener(serviceListener)
            hidService = null
            isBound = false
            isServiceRunning.value = false
            addLocalLog("Service disconnected.")
        }
    }

    private val serviceListener = object : BluetoothHidService.ServiceListener {
        override fun onLogAdded() {
            mainHandlerUpdateLogs()
        }

        override fun onConnectionStatesChanged() {
            syncServiceState()
        }
    }

    // Register permission request launcher
    private val requestMultiplePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        permissionsGranted.value = allGranted
        if (allGranted) {
            addLocalLog("All required Bluetooth permissions granted.")
            startAndBindService()
        } else {
            addLocalLog("Permissions denied. Bluetooth bridging unavailable.")
            Toast.makeText(this, "Permissions required for Bluetooth HID", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkPermissions()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF1E88E5), // Bright Blue
                    background = Color(0xFF121212), // Premium Dark
                    surface = Color(0xFF1E1E1E), // Dark Gray Cards
                    onPrimary = Color.White,
                    onBackground = Color(0xFFE0E0E0),
                    onSurface = Color(0xFFE0E0E0)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!permissionsGranted.value) {
                        PermissionRequiredScreen { checkPermissions() }
                    } else {
                        MainLayout()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            hidService?.unregisterListener(serviceListener)
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            permissionsGranted.value = true
            startAndBindService()
        } else {
            requestMultiplePermissions.launch(missing.toTypedArray())
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, BluetoothHidService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopServiceApp() {
        if (isBound) {
            hidService?.unregisterListener(serviceListener)
            unbindService(serviceConnection)
            isBound = false
        }
        val intent = Intent(this, BluetoothHidService::class.java)
        stopService(intent)
        isServiceRunning.value = false
        addLocalLog("Background Service stopped.")
    }

    private fun syncServiceState() {
        val s = hidService ?: return
        garminConnected.value = s.getGarminState()
        garminWatchName.value = s.getGarminWatchName()
        hidConnectionState.value = s.getBluetoothState()
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val reader = s.getConnectedReader()
            connectedReaderDeviceName.value = reader?.name?.takeIf { it.isNotBlank() } ?: reader?.address
            
            // Sync paired devices list
            val currentPaired = s.getPairedDevices()
            pairedDevices.clear()
            pairedDevices.addAll(currentPaired)

            // Persist connection selection logic
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (reader != null) {
                // If currently connected, update both UI selection and stored preference
                selectedDevice.value = reader
                prefs.edit().putString(KEY_LAST_DEVICE, reader.address).apply()
            } else if (selectedDevice.value == null && currentPaired.isNotEmpty()) {
                // Initial load: try to restore last used device
                val lastAddress = prefs.getString(KEY_LAST_DEVICE, null)
                selectedDevice.value = currentPaired.find { it.address == lastAddress } ?: currentPaired.first()
            }
        }

        // Sync KOReader settings from preferences to UI and service
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        connectionMode.value = prefs.getInt(KEY_CONNECTION_MODE, 1)
        koreaderIp.value = prefs.getString(KEY_KOREADER_IP, "") ?: ""
        koreaderPort.value = prefs.getString(KEY_KOREADER_PORT, "8080") ?: "8080"
        
        s.connectionMode = connectionMode.value
        s.koreaderIp = koreaderIp.value
        s.koreaderPort = koreaderPort.value.toIntOrNull() ?: 8080
        isKoreaderConnected.value = s.isKoreaderConnected
        
        mappingMode.value = s.mappingMode
        mainHandlerUpdateLogs()
    }

    private fun mainHandlerUpdateLogs() {
        val s = hidService ?: return
        logsList.clear()
        logsList.addAll(s.getLogs())
    }

    private fun addLocalLog(message: String) {
        val formattedMsg = "[${System.currentTimeMillis() % 1000000 / 1000}s] [App] $message"
        logsList.add(0, formattedMsg)
    }

    // Trigger phone discoverability dialog (standard Android API)
    private fun makePhoneDiscoverable() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            addLocalLog("Requesting Bluetooth discoverability...")
            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            }
            startActivity(discoverableIntent)
        } else {
            addLocalLog("Discoverability aborted: missing permissions.")
        }
    }

    @Composable
    fun PermissionRequiredScreen(onRequestClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Permissions Required",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Bluetooth Permissions Required",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "This app requires Bluetooth permissions to register as an emulated HID keyboard and listen to your Garmin watch.",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onRequestClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Grant Permissions", color = Color.White)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainLayout() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Garmin Page Turner",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    actions = {
                        Switch(
                            checked = isServiceRunning.value,
                            onCheckedChange = { start ->
                                if (start) {
                                    startAndBindService()
                                } else {
                                    stopServiceApp()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Green,
                                checkedTrackColor = Color.Green.copy(alpha = 0.5f)
                            )
                        )
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Connection statuses (Watch & E-Reader)
                StatusCard()

                // Paired devices / connect controller
                DeviceConnectionCard()

                // Custom settings dropdown
                if (connectionMode.value == 1) {
                    SettingsCard()
                }

                // Monospace console logger
                Box(modifier = Modifier.height(300.dp)) {
                    LogsConsole()
                }
            }
        }
    }

    @Composable
    fun StatusCard() {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Connection Status",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Color.White
                )
                
                Divider(color = Color.Gray.copy(alpha = 0.2f))

                // Watch status row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (garminConnected.value) Color.Green else Color.Gray,
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Garmin Watch: ",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.Gray
                    )
                    Text(
                        text = if (garminConnected.value) (garminWatchName.value ?: "Connected") else "Disconnected",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (garminConnected.value) Color.Green else Color.White
                    )
                }

                // E-Reader / KOReader status row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (connectionMode.value == 2) {
                        val isConnected = isKoreaderConnected.value
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    color = if (isConnected) Color.Green else Color.Gray,
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "KOReader Link: ",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.Gray
                        )
                        Text(
                            text = if (isConnected) "Connected (${koreaderIp.value}:${koreaderPort.value})" else "Disconnected",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) Color.Green else Color.White
                        )
                    } else {
                        val color = when (hidConnectionState.value) {
                            BluetoothProfile.STATE_CONNECTED -> Color.Green
                            BluetoothProfile.STATE_CONNECTING -> Color.Cyan
                            else -> Color.Gray
                        }
                        val text = when (hidConnectionState.value) {
                            BluetoothProfile.STATE_CONNECTED -> connectedReaderDeviceName.value ?: "Connected"
                            BluetoothProfile.STATE_CONNECTING -> "Connecting..."
                            else -> "Disconnected"
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    color = color,
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "E-Reader HID: ",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.Gray
                        )
                        Text(
                            text = text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (hidConnectionState.value == BluetoothProfile.STATE_CONNECTED) Color.Green else Color.White
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun DeviceConnectionCard() {
        var expanded by remember { mutableStateOf(false) }
        var modeExpanded by remember { mutableStateOf(false) }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "E-Reader Link Controls",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Color.White
                )

                // Mode selector (Bluetooth vs KOReader)
                ExposedDropdownMenuBox(
                    expanded = modeExpanded,
                    onExpandedChange = { modeExpanded = !modeExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextField(
                        readOnly = true,
                        value = if (connectionMode.value == 2) "KOReader HTTP Inspector" else "Bluetooth HID (Keyboard)",
                        onValueChange = {},
                        label = { Text("Connection Mode") },
                        trailingIcon = {
                            Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = "Dropdown")
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF2C2C2C),
                            unfocusedContainerColor = Color(0xFF2C2C2C),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = modeExpanded,
                        onDismissRequest = { modeExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Bluetooth HID (Keyboard)") },
                            onClick = {
                                connectionMode.value = 1
                                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit().putInt(KEY_CONNECTION_MODE, 1).apply()
                                hidService?.connectionMode = 1
                                modeExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("KOReader HTTP Inspector") },
                            onClick = {
                                connectionMode.value = 2
                                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit().putInt(KEY_CONNECTION_MODE, 2).apply()
                                hidService?.connectionMode = 2
                                modeExpanded = false
                            }
                        )
                    }
                }

                Divider(color = Color.Gray.copy(alpha = 0.2f))

                if (connectionMode.value == 2) {
                    // KOReader HTTP settings input
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextField(
                            value = koreaderIp.value,
                            onValueChange = { newVal ->
                                koreaderIp.value = newVal
                                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit().putString(KEY_KOREADER_IP, newVal).apply()
                                hidService?.koreaderIp = newVal
                            },
                            label = { Text("KOReader IP Address") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF2C2C2C),
                                unfocusedContainerColor = Color(0xFF2C2C2C),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.weight(2f)
                        )

                        TextField(
                            value = koreaderPort.value,
                            onValueChange = { newVal ->
                                koreaderPort.value = newVal
                                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit().putString(KEY_KOREADER_PORT, newVal).apply()
                                hidService?.koreaderPort = newVal.toIntOrNull() ?: 8080
                            },
                            label = { Text("Port") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF2C2C2C),
                                unfocusedContainerColor = Color(0xFF2C2C2C),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Connect/Disconnect for KOReader
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                val portVal = koreaderPort.value.toIntOrNull() ?: 8080
                                hidService?.connectToKoReader(koreaderIp.value, portVal)
                            },
                            enabled = isServiceRunning.value && !isKoreaderConnected.value && koreaderIp.value.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Connect", color = Color.White)
                        }

                        Button(
                            onClick = {
                                hidService?.disconnectKoReader()
                            },
                            enabled = isServiceRunning.value && isKoreaderConnected.value,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Disconnect", color = Color.White)
                        }
                    }
                } else {
                    // Select Device Dropdown
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextField(
                            readOnly = true,
                            value = if (ActivityCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                            ) {
                                selectedDevice.value?.let { "${it.name ?: "Unknown"} (${it.address})" }
                                    ?: "No paired devices found"
                            } else {
                                "Permission missing"
                            },
                            onValueChange = {},
                            label = { Text("Select Paired E-Reader") },
                            trailingIcon = {
                                Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = "Dropdown")
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF2C2C2C),
                                unfocusedContainerColor = Color(0xFF2C2C2C),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            pairedDevices.forEach { device ->
                                DropdownMenuItem(
                                    text = {
                                        val name = if (ActivityCompat.checkSelfPermission(
                                                this@MainActivity,
                                                Manifest.permission.BLUETOOTH_CONNECT
                                            ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                                        ) {
                                            device.name ?: "Unknown"
                                        } else {
                                            "Unknown"
                                        }
                                        Text("${name} (${device.address})")
                                    },
                                    onClick = {
                                        selectedDevice.value = device
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Connect/Disconnect & Pairing Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                selectedDevice.value?.let { hidService?.connectToDevice(it) }
                            },
                            enabled = isServiceRunning.value && hidConnectionState.value == BluetoothProfile.STATE_DISCONNECTED && selectedDevice.value != null,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Connect", color = Color.White)
                        }

                        Button(
                            onClick = {
                                hidService?.disconnectDevice()
                            },
                            enabled = isServiceRunning.value && hidConnectionState.value == BluetoothProfile.STATE_CONNECTED,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Disconnect", color = Color.White)
                        }
                    }

                    // Discoverability & Check Connection Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { makePhoneDiscoverable() },
                            enabled = isServiceRunning.value,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Pairing Mode", textAlign = TextAlign.Center)
                        }

                        OutlinedButton(
                            onClick = { hidService?.checkForAlreadyConnectedHidDevices() },
                            enabled = isServiceRunning.value && hidConnectionState.value == BluetoothProfile.STATE_DISCONNECTED,
                            border = BorderStroke(1.dp, Color.Gray),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Check Connection", textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsCard() {
        var expanded by remember { mutableStateOf(false) }
        val mappingModesList = listOf(
            1 to "Arrow Keys (Left / Right)",
            2 to "Page Keys (Page Up / Page Down)",
            3 to "Space / Backspace (Scroll)"
        )

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Page Turner Mappings",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Color.White
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextField(
                        readOnly = true,
                        value = mappingModesList.find { it.first == mappingMode.value }?.second ?: "Arrow Keys",
                        onValueChange = {},
                        label = { Text("Gestures Key mapping") },
                        trailingIcon = {
                            Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = "Dropdown")
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF2C2C2C),
                            unfocusedContainerColor = Color(0xFF2C2C2C),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        mappingModesList.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.second) },
                                onClick = {
                                    hidService?.mappingMode = mode.first
                                    mappingMode.value = mode.first
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun LogsConsole() {
        val listState = rememberLazyListState()

        // Auto-scroll list when new logs are added
        LaunchedEffect(logsList.size) {
            if (logsList.isNotEmpty()) {
                listState.animateScrollToItem(0)
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F0F)), // Terminal Black
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Bridge Activity Log",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Color.LightGray
                    )
                    
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear logs",
                        tint = Color.Gray,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable {
                                hidService?.clearLogs()
                                logsList.clear()
                                addLocalLog("Logs cleared.")
                            }
                    )
                }

                Divider(color = Color.Gray.copy(alpha = 0.15f))

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp)),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logsList) { log ->
                        val color = if (log.contains("TX Error") || log.contains("failure") || log.contains("denied")) {
                            Color.Red
                        } else if (log.contains("Transmitting") || log.contains("Command Received") || log.contains("Connected")) {
                            Color.Green
                        } else {
                            Color.LightGray
                        }
                        Text(
                            text = log,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = color,
                            lineHeight = 15.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

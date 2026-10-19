package io.github.ultrapg.btconnect.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import io.github.ultrapg.btconnect.model.BtDeviceModel
import io.github.ultrapg.btconnect.model.BypassStrategy
import io.github.ultrapg.btconnect.model.DeviceCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BluetoothBypassController(private val context: Context) {

    companion object {
        // Standard & Hidden Android BluetoothProfile IDs
        const val PROFILE_HEADSET = BluetoothProfile.HEADSET // 1
        const val PROFILE_A2DP = BluetoothProfile.A2DP       // 2
        const val PROFILE_HID_HOST = 4                       // BluetoothHidHost / BluetoothInputDevice
        const val PROFILE_PAN = 5                            // BluetoothPan

        // Transport constants for createBond(int transport)
        const val TRANSPORT_AUTO = 0
        const val TRANSPORT_BREDR = 1
        const val TRANSPORT_LE = 2

        val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val UUID_HID_CLASSIC: UUID = UUID.fromString("00001124-0000-1000-8000-00805F9B34FB")
        val UUID_HID_LE_HOGP: UUID = UUID.fromString("00001812-0000-1000-8000-00805F9B34FB")
        val UUID_PNP_INFO: UUID = UUID.fromString("00001200-0000-1000-8000-00805F9B34FB")
        val UUID_A2DP_SINK: UUID = UUID.fromString("0000110B-0000-1000-8000-00805F9B34FB")
        val UUID_HANDSFREE: UUID = UUID.fromString("0000111E-0000-1000-8000-00805F9B34FB")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val deviceCache = ConcurrentHashMap<String, BtDeviceModel>()
    private val activeSockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val activeGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val profileProxies = ConcurrentHashMap<Int, BluetoothProfile>()

    private val _devices = MutableStateFlow<List<BtDeviceModel>>(emptyList())
    val devices: StateFlow<List<BtDeviceModel>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(adapter?.isEnabled == true)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _connectingAddress = MutableStateFlow<String?>(null)
    val connectingAddress: StateFlow<String?> = _connectingAddress.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready — Tap any device to force-connect")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _autoPairEnabled = MutableStateFlow(true)
    val autoPairEnabled: StateFlow<Boolean> = _autoPairEnabled.asStateFlow()

    private val _defaultPin = MutableStateFlow("0000")
    val defaultPin: StateFlow<String> = _defaultPin.asStateFlow()

    private var scanJob: Job? = null
    private var isLeScanning = false
    private var receiverRegistered = false

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            profileProxies[profile] = proxy
            val name = profileName(profile)
            appendLog("Bound system profile proxy: $name (id=$profile)")
            refreshBondedAndConnectedDevices()
        }

        override fun onServiceDisconnected(profile: Int) {
            profileProxies.remove(profile)
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val scanRecordName = result.scanRecord?.deviceName
            val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
            upsertFromBluetoothDevice(
                device = device,
                rssi = result.rssi,
                fallbackName = scanRecordName,
                extraUuids = serviceUuids
            )
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            appendLog("BLE scan warning (code=$errorCode) — Classic BR/EDR discovery continues")
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    _isBluetoothEnabled.value = (state == BluetoothAdapter.STATE_ON)
                    if (state == BluetoothAdapter.STATE_ON) {
                        bindSystemProfileProxies()
                        refreshBondedAndConnectedDevices()
                    }
                }

                BluetoothDevice.ACTION_FOUND -> {
                    val device = extractDevice(intent) ?: return
                    val rssiShort = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    val rssi = if (rssiShort != Short.MIN_VALUE) rssiShort.toInt() else null
                    val extraName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    upsertFromBluetoothDevice(device, rssi = rssi, fallbackName = extraName)
                }

                BluetoothDevice.ACTION_NAME_CHANGED -> {
                    val device = extractDevice(intent) ?: return
                    val extraName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    upsertFromBluetoothDevice(device, fallbackName = extraName)
                }

                BluetoothDevice.ACTION_UUID -> {
                    val device = extractDevice(intent) ?: return
                    val parcelUuids = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                    }
                    val uuidStrings = parcelUuids?.mapNotNull { (it as? ParcelUuid)?.uuid?.toString() } ?: emptyList()
                    upsertFromBluetoothDevice(device, extraUuids = uuidStrings)
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = extractDevice(intent) ?: return
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val stateLabel = when (bondState) {
                        BluetoothDevice.BOND_BONDED -> "BONDED (Paired)"
                        BluetoothDevice.BOND_BONDING -> "BONDING (Pairing...)"
                        else -> "UNBONDED"
                    }
                    appendLog("Bond state for ${device.address}: $stateLabel")
                    upsertFromBluetoothDevice(device)
                }

                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = extractDevice(intent) ?: return
                    appendLog("ACL baseband link CONNECTED: ${device.address}")
                    upsertFromBluetoothDevice(device, forceConnected = true)
                    _statusMessage.value = "Connected to ${safeDeviceName(device)}"
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = extractDevice(intent) ?: return
                    appendLog("ACL baseband link DISCONNECTED: ${device.address}")
                    upsertFromBluetoothDevice(device, forceConnected = false)
                }

                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val device = extractDevice(intent) ?: return
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
                    val passkey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR)
                    appendLog("Intercepted PAIRING_REQUEST from ${device.address} (variant=$variant, key=$passkey)")

                    if (_autoPairEnabled.value) {
                        handlePairingBypass(device, variant)
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _isScanning.value = true
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (!isLeScanning) {
                        _isScanning.value = false
                    }
                }
            }
        }
    }

    fun initialize() {
        registerReceiversIfNeeded()
        _isBluetoothEnabled.value = adapter?.isEnabled == true
        bindSystemProfileProxies()
        refreshBondedAndConnectedDevices()
    }

    fun setAutoPairEnabled(enabled: Boolean) {
        _autoPairEnabled.value = enabled
        appendLog("Auto-PIN legacy pairing bypass ${if (enabled) "ENABLED (PIN=${_defaultPin.value})" else "DISABLED"}")
    }

    fun setDefaultPin(pin: String) {
        _defaultPin.value = pin
        appendLog("Default legacy PIN set to '$pin'")
    }

    private fun registerReceiversIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_UUID)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(bluetoothReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun bindSystemProfileProxies() {
        val btAdapter = adapter ?: return
        if (!btAdapter.isEnabled) return
        listOf(PROFILE_HID_HOST, PROFILE_A2DP, PROFILE_HEADSET, PROFILE_PAN).forEach { profileId ->
            if (!profileProxies.containsKey(profileId)) {
                runCatching {
                    btAdapter.getProfileProxy(context, profileListener, profileId)
                }
            }
        }
    }

    fun refreshBondedAndConnectedDevices() {
        val btAdapter = adapter ?: return
        _isBluetoothEnabled.value = btAdapter.isEnabled
        if (!btAdapter.isEnabled) return

        runCatching {
            btAdapter.bondedDevices?.forEach { device ->
                upsertFromBluetoothDevice(device)
            }
        }

        // Also inspect connected devices from bound system profile proxies
        profileProxies.forEach { (profileId, proxy) ->
            runCatching {
                proxy.connectedDevices?.forEach { device ->
                    upsertFromBluetoothDevice(device, forceConnected = true)
                }
            }
        }

        // Also check BLE GATT connected devices
        runCatching {
            bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)?.forEach { device ->
                upsertFromBluetoothDevice(device, forceConnected = true)
            }
        }

        publishDeviceList()
    }

    fun startFullScan() {
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            _statusMessage.value = "Bluetooth is turned off. Please enable Bluetooth."
            return
        }

        refreshBondedAndConnectedDevices()
        scanJob?.cancel()
        scanJob = scope.launch {
            _isScanning.value = true
            _statusMessage.value = "Scanning Classic BR/EDR + BLE (unfiltered)..."
            appendLog("Starting unfiltered dual-stack scan (Classic BR/EDR + Low-Latency BLE)")

            // 1. Classic Bluetooth Discovery (finds Apple Magic Mouse 1, legacy keyboards, audio, SPP)
            runCatching {
                if (btAdapter.isDiscovering) {
                    btAdapter.cancelDiscovery()
                }
                val started = btAdapter.startDiscovery()
                appendLog("Classic BR/EDR startDiscovery() -> $started")
            }.onFailure {
                appendLog("Classic discovery error: ${it.message}")
            }

            // 2. Unfiltered BLE Scanner (finds BLE mice, keyboards, Dual-mode devices, HOGP)
            runCatching {
                val scanner = btAdapter.bluetoothLeScanner
                if (scanner != null && !isLeScanning) {
                    val settings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                        .build()
                    scanner.startScan(null, settings, leScanCallback)
                    isLeScanning = true
                    appendLog("Unfiltered BLE scan started (SCAN_MODE_LOW_LATENCY)")
                }
            }.onFailure {
                appendLog("BLE scan error: ${it.message}")
            }

            // Keep scanning for 18 seconds and re-trigger classic discovery if needed
            delay(18_000)
            stopScan()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        val btAdapter = adapter
        runCatching {
            if (btAdapter?.isDiscovering == true) {
                btAdapter.cancelDiscovery()
            }
        }
        runCatching {
            if (isLeScanning) {
                btAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
                isLeScanning = false
            }
        }
        _isScanning.value = false
        if (_connectingAddress.value == null) {
            _statusMessage.value = "Scan complete (${deviceCache.size} devices found)"
        }
    }

    /**
     * Connects to a device by MAC address using the selected [BypassStrategy].
     * Works even for hidden/undiscovered devices if the user enters their MAC address directly.
     */
    fun connectByMac(
        rawAddress: String,
        strategy: BypassStrategy = BypassStrategy.SMART_AUTO,
        forceRebond: Boolean = false,
        customPin: String? = null
    ) {
        val normalized = rawAddress.trim().uppercase(Locale.US).replace("-", ":")
        if (!BluetoothAdapter.checkBluetoothAddress(normalized)) {
            _statusMessage.value = "Invalid MAC address: $rawAddress (expected XX:XX:XX:XX:XX:XX)"
            appendLog("Rejected invalid MAC address: $rawAddress")
            return
        }
        val btAdapter = adapter ?: return
        val remoteDevice = runCatching { btAdapter.getRemoteDevice(normalized) }.getOrNull()
        if (remoteDevice == null) {
            _statusMessage.value = "Could not resolve remote device $normalized"
            return
        }
        val model = upsertFromBluetoothDevice(remoteDevice)
        connectDevice(model, strategy = strategy, forceRebond = forceRebond, customPin = customPin)
    }

    fun connectDevice(
        deviceModel: BtDeviceModel,
        strategy: BypassStrategy = BypassStrategy.SMART_AUTO,
        forceRebond: Boolean = false,
        customPin: String? = null
    ) {
        val btAdapter = adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            _statusMessage.value = "Please turn on Bluetooth first"
            return
        }

        if (!customPin.isNullOrBlank()) {
            _defaultPin.value = customPin.trim()
        }

        scope.launch {
            val address = deviceModel.address
            _connectingAddress.value = address

            val effectiveStrategy = if (strategy == BypassStrategy.SMART_AUTO) {
                deviceModel.recommendedStrategy
            } else {
                strategy
            }

            try {
                // Step 1: Stop discovery so radio paging has full bandwidth
                stopScan()
                val device = btAdapter.getRemoteDevice(address)
                val name = safeDeviceName(device)
                appendLog("=== Starting Bypass Connect for $name [$address] ===")
                appendLog("Selected mode: ${effectiveStrategy.title}")

                // Step 2: Optional clean unpair if stuck in corrupted bond state
                if (forceRebond && device.bondState == BluetoothDevice.BOND_BONDED) {
                    _statusMessage.value = "Clearing corrupted bond for $name..."
                    appendLog("Invoking removeBond() reflection to clear stale pairing state")
                    invokeRemoveBond(device)
                    delay(1200)
                }

                // Step 3: Trigger SDP UUID fetch to wake up remote stack
                _statusMessage.value = "Waking up $name (SDP & Baseband)..."
                runCatching {
                    device.fetchUuidsWithSdp()
                    appendLog("Triggered fetchUuidsWithSdp() on $address")
                }

                // Step 4: Pair / Bond with explicit transport bypass if not yet bonded
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    val preferredTransport = when (effectiveStrategy) {
                        BypassStrategy.LEGACY_HID_MAGIC_MOUSE,
                        BypassStrategy.AUDIO_HEADSET_FORCE,
                        BypassStrategy.INSECURE_RFCOMM_DIRECT -> TRANSPORT_BREDR
                        BypassStrategy.BLE_GATT_DIRECT -> TRANSPORT_LE
                        BypassStrategy.SMART_AUTO -> if (deviceModel.deviceType == BluetoothDevice.DEVICE_TYPE_LE) {
                            TRANSPORT_LE
                        } else {
                            TRANSPORT_BREDR
                        }
                    }

                    _statusMessage.value = "Pairing $name (Transport=${transportName(preferredTransport)}, PIN=${_defaultPin.value})..."
                    val bondInitiated = invokeCreateBondWithTransport(device, preferredTransport)
                    appendLog("createBond(transport=$preferredTransport) initiated -> $bondInitiated")

                    // Wait up to 10 seconds for bonding or ACL connection
                    var waitedMs = 0L
                    while (waitedMs < 10_000L &&
                        device.bondState == BluetoothDevice.BOND_BONDING &&
                        !isDeviceAclConnected(device)
                    ) {
                        delay(400)
                        waitedMs += 400
                    }
                }

                // Step 5: Execute strategy-specific connection bypass
                var connectedSuccess = false

                when (effectiveStrategy) {
                    BypassStrategy.LEGACY_HID_MAGIC_MOUSE -> {
                        _statusMessage.value = "Connecting HID Input Profile (Magic Mouse / Keyboard bypass)..."
                        // Connect HID Host profile (profile 4) + PAN (profile 5) via reflection
                        val hidOk = connectViaProfileProxy(device, PROFILE_HID_HOST)
                        connectViaProfileProxy(device, PROFILE_PAN)
                        // Also open insecure baseband wakeup channel (helps Apple Magic Mouse 1st Gen & older BT 2.0/2.1 HID)
                        val basebandOk = establishInsecureBasebandWakeup(device, keepSocketOpen = false)
                        delay(700)
                        connectedSuccess = hidOk || basebandOk || isDeviceAclConnected(device)
                    }

                    BypassStrategy.AUDIO_HEADSET_FORCE -> {
                        _statusMessage.value = "Forcing A2DP & Hands-Free Headset profiles..."
                        val a2dpOk = connectViaProfileProxy(device, PROFILE_A2DP)
                        val hfpOk = connectViaProfileProxy(device, PROFILE_HEADSET)
                        delay(700)
                        connectedSuccess = a2dpOk || hfpOk || isDeviceAclConnected(device)
                        if (!connectedSuccess) {
                            connectedSuccess = establishInsecureBasebandWakeup(device, keepSocketOpen = true)
                        }
                    }

                    BypassStrategy.INSECURE_RFCOMM_DIRECT -> {
                        _statusMessage.value = "Opening direct insecure RFCOMM baseband socket..."
                        connectedSuccess = establishInsecureBasebandWakeup(device, keepSocketOpen = true)
                        // Also nudge HID/A2DP profiles once ACL is up
                        connectAllAvailableProfiles(device)
                    }

                    BypassStrategy.BLE_GATT_DIRECT -> {
                        _statusMessage.value = "Connecting direct BLE GATT bridge..."
                        connectedSuccess = connectViaBleGatt(device)
                        // Also nudge HID Host in case it's HOGP
                        connectViaProfileProxy(device, PROFILE_HID_HOST)
                    }

                    BypassStrategy.SMART_AUTO -> {
                        _statusMessage.value = "Connecting profiles & direct baseband link..."
                        val profileOk = connectAllAvailableProfiles(device)
                        val basebandOk = if (deviceModel.deviceType == BluetoothDevice.DEVICE_TYPE_LE) {
                            connectViaBleGatt(device)
                        } else {
                            establishInsecureBasebandWakeup(device, keepSocketOpen = !profileOk)
                        }
                        delay(600)
                        connectedSuccess = profileOk || basebandOk || isDeviceAclConnected(device)
                    }
                }

                upsertFromBluetoothDevice(device, forceConnected = connectedSuccess || isDeviceAclConnected(device))
                refreshBondedAndConnectedDevices()

                if (connectedSuccess || isDeviceAclConnected(device) || device.bondState == BluetoothDevice.BOND_BONDED) {
                    val stateNote = if (isDeviceAclConnected(device) || connectedSuccess) {
                        "Connected to $name"
                    } else {
                        "Paired with $name — move/click device to wake input link"
                    }
                    _statusMessage.value = stateNote
                    appendLog("SUCCESS: $stateNote")
                } else {
                    _statusMessage.value = "Could not reach $name — ensure it is in pairing/discovery mode and try Legacy HID or Baseband mode"
                    appendLog("WARNING: Connection attempt to $name finished without active ACL confirmation")
                }
            } catch (e: Exception) {
                appendLog("ERROR connecting to ${deviceModel.address}: ${e.message}")
                _statusMessage.value = "Connection error: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                _connectingAddress.value = null
                refreshBondedAndConnectedDevices()
            }
        }
    }

    fun disconnectDevice(deviceModel: BtDeviceModel) {
        scope.launch {
            val address = deviceModel.address
            _statusMessage.value = "Disconnecting ${deviceModel.displayName}..."
            appendLog("Disconnecting ${deviceModel.displayName} [$address]")

            // Close any active RFCOMM socket
            activeSockets.remove(address)?.let { socket ->
                runCatching { socket.close() }
                appendLog("Closed direct RFCOMM socket for $address")
            }

            // Close any active BLE GATT connection
            activeGatts.remove(address)?.let { gatt ->
                runCatching {
                    gatt.disconnect()
                    gatt.close()
                }
                appendLog("Closed direct BLE GATT connection for $address")
            }

            // Disconnect from all bound system profile proxies
            val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
            if (device != null) {
                profileProxies.forEach { (profileId, proxy) ->
                    runCatching {
                        val disconnectMethod = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        disconnectMethod.isAccessible = true
                        disconnectMethod.invoke(proxy, device)
                        appendLog("Invoked disconnect() on ${profileName(profileId)}")
                    }
                }
                delay(500)
                upsertFromBluetoothDevice(device, forceConnected = false)
            }

            refreshBondedAndConnectedDevices()
            _statusMessage.value = "Disconnected ${deviceModel.displayName}"
        }
    }

    fun unpairDevice(deviceModel: BtDeviceModel) {
        scope.launch {
            val address = deviceModel.address
            disconnectDevice(deviceModel)
            delay(300)
            val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return@launch
            val removed = invokeRemoveBond(device)
            appendLog("Unpair (removeBond) on $address -> $removed")
            delay(500)
            upsertFromBluetoothDevice(device, forceConnected = false)
            refreshBondedAndConnectedDevices()
            _statusMessage.value = if (removed) {
                "Unpaired ${deviceModel.displayName}"
            } else {
                "Requested unpair for ${deviceModel.displayName}"
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun handlePairingBypass(device: BluetoothDevice, variant: Int) {
        val pin = _defaultPin.value.ifBlank { "0000" }
        try {
            when (variant) {
                BluetoothDevice.PAIRING_VARIANT_PIN -> {
                    val pinBytes = pin.toByteArray(Charsets.UTF_8)
                    val pinSet = device.setPin(pinBytes)
                    runCatching { device.setPairingConfirmation(true) }
                    appendLog("Auto-injected legacy PIN '$pin' for ${device.address} (result=$pinSet)")
                }

                BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> {
                    runCatching {
                        device.setPairingConfirmation(true)
                        appendLog("Auto-confirmed SSP Passkey for ${device.address}")
                    }.onFailure {
                        appendLog("Passkey auto-confirm note: ${it.message}")
                    }
                }

                else -> {
                    // Variant 3 (CONSENT), Variant 4 (DISPLAY_PASSKEY), Variant 5 (DISPLAY_PIN), Variant 7 (PIN_16_DIGITS)
                    val pinBytes = pin.toByteArray(Charsets.UTF_8)
                    runCatching { device.setPin(pinBytes) }
                    runCatching { device.setPairingConfirmation(true) }
                    appendLog("Applied universal pairing confirmation & PIN '$pin' for variant=$variant")
                }
            }
        } catch (e: Exception) {
            appendLog("Auto-pairing helper note: ${e.message}")
        }
    }

    private fun invokeCreateBondWithTransport(device: BluetoothDevice, transport: Int): Boolean {
        return try {
            val createBondMethod = device.javaClass.getMethod("createBond", Int::class.javaPrimitiveType)
            createBondMethod.isAccessible = true
            val result = createBondMethod.invoke(device, transport) as? Boolean ?: false
            if (!result) {
                device.createBond()
            } else {
                true
            }
        } catch (_: Exception) {
            runCatching { device.createBond() }.getOrDefault(false)
        }
    }

    private fun invokeRemoveBond(device: BluetoothDevice): Boolean {
        return try {
            // Cancel in-progress bonding first if stuck
            if (device.bondState == BluetoothDevice.BOND_BONDING) {
                runCatching {
                    val cancelMethod = device.javaClass.getMethod("cancelBondProcess")
                    cancelMethod.isAccessible = true
                    cancelMethod.invoke(device)
                }
            }
            val removeBondMethod = device.javaClass.getMethod("removeBond")
            removeBondMethod.isAccessible = true
            removeBondMethod.invoke(device) as? Boolean ?: false
        } catch (e: Exception) {
            appendLog("removeBond reflection note: ${e.message}")
            false
        }
    }

    private fun connectAllAvailableProfiles(device: BluetoothDevice): Boolean {
        var anySuccess = false
        for (profileId in listOf(PROFILE_HID_HOST, PROFILE_A2DP, PROFILE_HEADSET, PROFILE_PAN)) {
            if (connectViaProfileProxy(device, profileId)) {
                anySuccess = true
            }
        }
        return anySuccess
    }

    private fun connectViaProfileProxy(device: BluetoothDevice, profileId: Int): Boolean {
        val proxy = profileProxies[profileId] ?: return false
        val name = profileName(profileId)
        return try {
            // Try enabling priority / connection policy first
            runCatching {
                val policyMethod = proxy.javaClass.getMethod(
                    "setConnectionPolicy",
                    BluetoothDevice::class.java,
                    Int::class.javaPrimitiveType
                )
                policyMethod.isAccessible = true
                policyMethod.invoke(proxy, device, 100) // CONNECTION_POLICY_ALLOWED = 100
            }
            runCatching {
                val priorityMethod = proxy.javaClass.getMethod(
                    "setPriority",
                    BluetoothDevice::class.java,
                    Int::class.javaPrimitiveType
                )
                priorityMethod.isAccessible = true
                priorityMethod.invoke(proxy, device, 100) // PRIORITY_ON = 100
            }

            val connectMethod = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            connectMethod.isAccessible = true
            val ok = connectMethod.invoke(proxy, device) as? Boolean ?: false
            appendLog("Profile proxy $name.connect(${device.address}) -> $ok")
            ok
        } catch (e: Exception) {
            appendLog("Profile proxy $name reflection note: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Opens a non-MITM insecure RFCOMM socket to force Android's Bluetooth stack
     * to page the remote device, negotiate baseband ACL + link key, and wake up
     * legacy devices (like Apple Magic Mouse, legacy keyboards, and SPP peripherals).
     */
    private suspend fun establishInsecureBasebandWakeup(
        device: BluetoothDevice,
        keepSocketOpen: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val candidateUuids = buildList {
            device.uuids?.mapNotNull { it?.uuid }?.forEach { add(it) }
            add(UUID_HID_CLASSIC)
            add(UUID_SPP)
            add(UUID_PNP_INFO)
            add(UUID_A2DP_SINK)
            add(UUID_HANDSFREE)
        }.distinct()

        // 1. Try insecure service record sockets
        for (uuid in candidateUuids.take(4)) {
            try {
                val socket = device.createInsecureRfcommSocketToServiceRecord(uuid)
                appendLog("Paging baseband via Insecure RFCOMM UUID ${uuid.toString().take(8)}...")
                socket.connect()
                appendLog("Baseband socket connected via UUID ${uuid.toString().take(8)}!")
                if (keepSocketOpen) {
                    activeSockets.put(device.address, socket)?.let { old -> runCatching { old.close() } }
                } else {
                    delay(250)
                    runCatching { socket.close() }
                }
                return@withContext true
            } catch (_: Exception) {
                // If ACL link came up during the handshake (very common for HID devices like Magic Mouse!), check it:
                if (isDeviceAclConnected(device)) {
                    appendLog("ACL baseband link is now ACTIVE after paging handshake!")
                    return@withContext true
                }
            }
        }

        // 2. Try direct insecure RFCOMM channel reflection (channels 1, 2, 11, 17, 19)
        for (channel in listOf(1, 2, 11, 17, 19)) {
            try {
                val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                method.isAccessible = true
                val socket = method.invoke(device, channel) as? BluetoothSocket ?: continue
                appendLog("Probing direct insecure RFCOMM channel $channel...")
                socket.connect()
                appendLog("Connected on direct insecure RFCOMM channel $channel!")
                if (keepSocketOpen) {
                    activeSockets.put(device.address, socket)?.let { old -> runCatching { old.close() } }
                } else {
                    delay(250)
                    runCatching { socket.close() }
                }
                return@withContext true
            } catch (_: Exception) {
                if (isDeviceAclConnected(device)) {
                    appendLog("ACL baseband link activated via channel $channel handshake!")
                    return@withContext true
                }
            }
        }

        isDeviceAclConnected(device)
    }

    private suspend fun connectViaBleGatt(device: BluetoothDevice): Boolean = withContext(Dispatchers.Main) {
        val resultSignal = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    appendLog("BLE GATT connected to ${device.address} (status=$status)")
                    runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                    runCatching { gatt.discoverServices() }
                    upsertFromBluetoothDevice(device, forceConnected = true)
                    if (!resultSignal.isCompleted) resultSignal.complete(true)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    appendLog("BLE GATT disconnected from ${device.address} (status=$status)")
                    activeGatts.remove(device.address)
                    upsertFromBluetoothDevice(device, forceConnected = false)
                    if (!resultSignal.isCompleted) resultSignal.complete(false)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val serviceCount = gatt.services?.size ?: 0
                val hasHogp = gatt.services?.any { it.uuid == UUID_HID_LE_HOGP } == true
                appendLog("Discovered $serviceCount BLE GATT services (HID-over-GATT=$hasHogp)")
                upsertFromBluetoothDevice(device, forceConnected = true)
            }
        }

        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }

        if (gatt != null) {
            activeGatts.put(device.address, gatt)?.let { old -> runCatching { old.close() } }
        } else {
            return@withContext false
        }

        withContext(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                resultSignal.await()
            } ?: isDeviceAclConnected(device)
        }
    }

    private fun isDeviceAclConnected(device: BluetoothDevice): Boolean {
        if (activeSockets[device.address]?.isConnected == true) return true
        if (activeGatts.containsKey(device.address)) return true

        // Check bound profile proxies
        for ((_, proxy) in profileProxies) {
            val connected = runCatching { proxy.connectedDevices?.any { it.address == device.address } == true }
                .getOrDefault(false)
            if (connected) return true
        }

        // Check BluetoothDevice.isConnected() via reflection
        return runCatching {
            val method = device.javaClass.getMethod("isConnected")
            method.isAccessible = true
            method.invoke(device) as? Boolean ?: false
        }.getOrDefault(false)
    }

    private fun getConnectedProfileNames(device: BluetoothDevice): Set<String> {
        val names = mutableSetOf<String>()
        profileProxies.forEach { (profileId, proxy) ->
            val isConn = runCatching {
                proxy.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
            }.getOrDefault(false)
            if (isConn) {
                names.add(profileName(profileId))
            }
        }
        if (activeSockets[device.address]?.isConnected == true) {
            names.add("RFCOMM")
        }
        if (activeGatts.containsKey(device.address)) {
            names.add("BLE GATT")
        }
        return names
    }

    private fun upsertFromBluetoothDevice(
        device: BluetoothDevice,
        rssi: Int? = null,
        fallbackName: String? = null,
        extraUuids: List<String> = emptyList(),
        forceConnected: Boolean? = null
    ): BtDeviceModel {
        val address = device.address ?: return BtDeviceModel(address = "00:00:00:00:00:00", name = "Unknown")
        val existing = deviceCache[address]

        val resolvedName = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: fallbackName?.takeIf { it.isNotBlank() }
            ?: existing?.name

        val bondState = runCatching { device.bondState }.getOrDefault(existing?.bondState ?: BluetoothDevice.BOND_NONE)
        val deviceType = runCatching { device.type }.getOrDefault(existing?.deviceType ?: BluetoothDevice.DEVICE_TYPE_UNKNOWN)
        val btClass: BluetoothClass? = runCatching { device.bluetoothClass }.getOrNull()
        val majorClass = btClass?.majorDeviceClass ?: existing?.majorClass ?: BluetoothClass.Device.Major.UNCATEGORIZED
        val fullClass = btClass?.deviceClass ?: existing?.deviceClass ?: 0

        val deviceUuids = runCatching {
            device.uuids?.mapNotNull { it?.uuid?.toString() } ?: emptyList()
        }.getOrDefault(emptyList())

        val mergedUuids = ((existing?.uuids ?: emptyList()) + deviceUuids + extraUuids).distinct()
        val connectedProfiles = getConnectedProfileNames(device)
        val isConnected = forceConnected ?: (connectedProfiles.isNotEmpty() || isDeviceAclConnected(device))

        val updated = BtDeviceModel(
            address = address,
            name = resolvedName,
            bondState = bondState,
            deviceType = deviceType,
            majorClass = majorClass,
            deviceClass = fullClass,
            rssi = rssi ?: existing?.rssi,
            isConnected = isConnected,
            connectedProfiles = connectedProfiles,
            uuids = mergedUuids,
            lastSeenMs = System.currentTimeMillis()
        )

        deviceCache[address] = updated
        publishDeviceList()
        return updated
    }

    private fun publishDeviceList() {
        val sorted = deviceCache.values.sortedWith(
            compareByDescending<BtDeviceModel> { it.isConnected }
                .thenByDescending { it.isBonded }
                .thenByDescending { it.isLikelyLegacyHidOrMagicMouse }
                .thenByDescending { !it.name.isNullOrBlank() }
                .thenByDescending { it.rssi ?: -120 }
                .thenBy { it.displayName.lowercase(Locale.US) }
        )
        _devices.value = sorted
    }

    private fun extractDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun safeDeviceName(device: BluetoothDevice): String {
        return runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: deviceCache[device.address]?.name
            ?: device.address
    }

    private fun profileName(profileId: Int): String = when (profileId) {
        PROFILE_HID_HOST -> "HID Input"
        PROFILE_A2DP -> "A2DP Audio"
        PROFILE_HEADSET -> "Headset HFP"
        PROFILE_PAN -> "PAN Network"
        else -> "Profile#$profileId"
    }

    private fun transportName(transport: Int): String = when (transport) {
        TRANSPORT_BREDR -> "Classic BR/EDR"
        TRANSPORT_LE -> "BLE"
        else -> "Auto"
    }

    fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _logs.update { current ->
            (current + "[$timestamp] $message").takeLast(120)
        }
    }

    fun release() {
        stopScan()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(bluetoothReceiver) }
            receiverRegistered = false
        }
        profileProxies.forEach { (profileId, proxy) ->
            runCatching { adapter?.closeProfileProxy(profileId, proxy) }
        }
        profileProxies.clear()
    }
}

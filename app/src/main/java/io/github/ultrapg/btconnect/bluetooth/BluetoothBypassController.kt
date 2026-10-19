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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

@SuppressLint("MissingPermission")
class BluetoothBypassController(private val context: Context) {

    companion object {
        const val PROFILE_HEADSET = BluetoothProfile.HEADSET // 1
        const val PROFILE_A2DP = BluetoothProfile.A2DP       // 2
        const val PROFILE_HID_HOST = 4                       // BluetoothHidHost / BluetoothInputDevice
        const val PROFILE_PAN = 5                            // BluetoothPan

        const val TRANSPORT_AUTO = 0
        const val TRANSPORT_BREDR = 1
        const val TRANSPORT_LE = 2

        private const val SOCKET_CONNECT_TIMEOUT_MS = 3_200L

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

    @Volatile
    private var pendingSocket: BluetoothSocket? = null

    private val _devices = MutableStateFlow<List<BtDeviceModel>>(emptyList())
    val devices: StateFlow<List<BtDeviceModel>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(adapter?.isEnabled == true)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _connectingAddress = MutableStateFlow<String?>(null)
    val connectingAddress: StateFlow<String?> = _connectingAddress.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _autoPairEnabled = MutableStateFlow(true)
    val autoPairEnabled: StateFlow<Boolean> = _autoPairEnabled.asStateFlow()

    private val _defaultPin = MutableStateFlow("0000")
    val defaultPin: StateFlow<String> = _defaultPin.asStateFlow()

    private var scanJob: Job? = null
    private var connectJob: Job? = null

    @Volatile
    private var isLeScanning = false
    private var receiverRegistered = false

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            profileProxies[profile] = proxy
            appendLog("Bound system profile proxy: ${profileName(profile)}")
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
            isLeScanning = false
            appendLog("BLE scan note (code=$errorCode) — Classic inquiry active")
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val enabled = (state == BluetoothAdapter.STATE_ON)
                    _isBluetoothEnabled.value = enabled
                    if (enabled) {
                        bindSystemProfileProxies()
                        refreshBondedAndConnectedDevices()
                    } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        cleanupOnBluetoothOff()
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
                        BluetoothDevice.BOND_BONDED -> "Paired"
                        BluetoothDevice.BOND_BONDING -> "Pairing..."
                        else -> "Unpaired"
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
                    activeSockets.remove(device.address)?.let { runCatching { it.close() } }
                    upsertFromBluetoothDevice(device, forceConnected = false)
                }

                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val device = extractDevice(intent) ?: return
                    val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
                    val passkey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR)
                    appendLog("Intercepted PAIRING_REQUEST from ${device.address} (variant=$variant, key=$passkey)")

                    if (_autoPairEnabled.value) {
                        val handled = handlePairingBypass(device, variant)
                        if (handled && variant == BluetoothDevice.PAIRING_VARIANT_PIN) {
                            runCatching { abortBroadcast() }
                        }
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _isScanning.value = true
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (!isLeScanning && scanJob?.isActive != true) {
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
        appendLog("Auto-PIN legacy pairing ${if (enabled) "enabled (PIN=${_defaultPin.value})" else "disabled"}")
    }

    fun setDefaultPin(pin: String) {
        val sanitized = pin.trim().ifBlank { "0000" }
        _defaultPin.value = sanitized
        appendLog("Default legacy PIN set to '$sanitized'")
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

    private fun cleanupOnBluetoothOff() {
        scanJob?.cancel()
        connectJob?.cancel()
        isLeScanning = false
        _isScanning.value = false
        _connectingAddress.value = null
        activeSockets.values.forEach { runCatching { it.close() } }
        activeSockets.clear()
        activeGatts.values.forEach {
            runCatching {
                it.disconnect()
                it.close()
            }
        }
        activeGatts.clear()
        _statusMessage.value = "Bluetooth is turned off"
        publishDeviceList()
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

        profileProxies.forEach { (_, proxy) ->
            runCatching {
                proxy.connectedDevices?.forEach { device ->
                    upsertFromBluetoothDevice(device, forceConnected = true)
                }
            }
        }

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
            _statusMessage.value = "Turn on Bluetooth to scan"
            return
        }

        refreshBondedAndConnectedDevices()
        scanJob?.cancel()
        scanJob = scope.launch {
            try {
                _isScanning.value = true
                _statusMessage.value = "Scanning nearby devices..."
                appendLog("Starting dual-stack scan (Classic BR/EDR + Low-Latency BLE)")

                runCatching {
                    if (btAdapter.isDiscovering) {
                        btAdapter.cancelDiscovery()
                    }
                    val started = btAdapter.startDiscovery()
                    appendLog("Classic BR/EDR startDiscovery() -> $started")
                }

                runCatching {
                    val scanner = btAdapter.bluetoothLeScanner
                    if (scanner != null) {
                        if (isLeScanning) {
                            runCatching { scanner.stopScan(leScanCallback) }
                            isLeScanning = false
                        }
                        val settings = ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                            .build()
                        scanner.startScan(null, settings, leScanCallback)
                        isLeScanning = true
                    }
                }

                delay(15_000)
            } finally {
                stopScanRadioInternal()
                if (_connectingAddress.value == null) {
                    _statusMessage.value = "Ready • ${deviceCache.size} devices"
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        stopScanRadioInternal()
        if (_connectingAddress.value == null) {
            _statusMessage.value = "Ready • ${deviceCache.size} devices"
        }
    }

    private fun stopScanRadioInternal() {
        val btAdapter = adapter
        runCatching {
            if (btAdapter?.isDiscovering == true) {
                btAdapter.cancelDiscovery()
            }
        }
        try {
            if (isLeScanning) {
                btAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
            }
        } catch (_: Exception) {
        } finally {
            isLeScanning = false
            _isScanning.value = false
        }
    }

    fun cancelCurrentConnection() {
        val addr = _connectingAddress.value
        runCatching { pendingSocket?.close() }
        pendingSocket = null
        connectJob?.cancel()
        connectJob = null
        _connectingAddress.value = null
        if (addr != null) {
            appendLog("Cancelled connection attempt to $addr")
            _statusMessage.value = "Connection cancelled"
        }
    }

    fun connectByMac(
        rawAddress: String,
        strategy: BypassStrategy = BypassStrategy.SMART_AUTO,
        forceRebond: Boolean = false,
        customPin: String? = null
    ) {
        val normalized = BtDeviceModel.normalizeMacAddress(rawAddress)
        if (normalized == null) {
            _statusMessage.value = "Invalid MAC address: $rawAddress"
            appendLog("Rejected invalid MAC address: $rawAddress")
            return
        }
        val btAdapter = adapter ?: return
        val remoteDevice = runCatching { btAdapter.getRemoteDevice(normalized) }.getOrNull()
        if (remoteDevice == null) {
            _statusMessage.value = "Could not resolve $normalized"
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
            _statusMessage.value = "Turn on Bluetooth first"
            return
        }

        if (!customPin.isNullOrBlank()) {
            _defaultPin.value = customPin.trim()
        }

        // Cancel any previous connection attempt cleanly
        runCatching { pendingSocket?.close() }
        val previousJob = connectJob

        connectJob = scope.launch {
            previousJob?.cancelAndJoin()
            val address = deviceModel.address
            _connectingAddress.value = address

            val effectiveStrategy = if (strategy == BypassStrategy.SMART_AUTO) {
                deviceModel.recommendedStrategy
            } else {
                strategy
            }

            try {
                stopScanRadioInternal()
                scanJob?.cancel()

                val device = btAdapter.getRemoteDevice(address)
                val name = safeDeviceName(device)
                appendLog("=== Bypass Connect: $name [$address] (${effectiveStrategy.badge}) ===")

                if (forceRebond && device.bondState == BluetoothDevice.BOND_BONDED) {
                    _statusMessage.value = "Resetting pairing for $name..."
                    invokeRemoveBond(device)
                    delay(1_000)
                }

                _statusMessage.value = "Waking up $name..."
                runCatching { device.fetchUuidsWithSdp() }

                // Pair / Bond with explicit transport if not bonded
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

                    _statusMessage.value = "Pairing $name (PIN ${_defaultPin.value})..."
                    val bondInitiated = invokeCreateBondWithTransport(device, preferredTransport)
                    appendLog("createBond(transport=${transportName(preferredTransport)}) -> $bondInitiated")

                    if (bondInitiated) {
                        // Wait up to 2.0s for bondState to leave BOND_NONE
                        var startWaitMs = 0L
                        while (coroutineContext.isActive &&
                            startWaitMs < 2_000L &&
                            device.bondState == BluetoothDevice.BOND_NONE &&
                            !isDeviceAclConnected(device)
                        ) {
                            delay(200)
                            startWaitMs += 200
                        }

                        // Wait up to 12.0s while BOND_BONDING is in progress
                        var bondingWaitMs = 0L
                        while (coroutineContext.isActive &&
                            bondingWaitMs < 12_000L &&
                            device.bondState == BluetoothDevice.BOND_BONDING
                        ) {
                            delay(300)
                            bondingWaitMs += 300
                        }
                    }
                }

                var connectedSuccess = false

                when (effectiveStrategy) {
                    BypassStrategy.LEGACY_HID_MAGIC_MOUSE -> {
                        _statusMessage.value = "Connecting HID Input ($name)..."
                        val hidOk = connectViaProfileProxy(device, PROFILE_HID_HOST)
                        connectViaProfileProxy(device, PROFILE_PAN)
                        val basebandOk = if (!isDeviceAclConnected(device)) {
                            establishInsecureBasebandWakeup(
                                device = device,
                                uuidsToTry = listOf(UUID_HID_CLASSIC, UUID_PNP_INFO, UUID_SPP),
                                channelsToTry = listOf(17, 19, 1),
                                keepSocketOpen = false
                            )
                        } else {
                            true
                        }
                        if (basebandOk && !hidOk) {
                            connectViaProfileProxy(device, PROFILE_HID_HOST)
                        }
                        delay(400)
                        connectedSuccess = hidOk || basebandOk || isDeviceAclConnected(device)
                    }

                    BypassStrategy.AUDIO_HEADSET_FORCE -> {
                        _statusMessage.value = "Connecting Audio ($name)..."
                        val a2dpOk = connectViaProfileProxy(device, PROFILE_A2DP)
                        val hfpOk = connectViaProfileProxy(device, PROFILE_HEADSET)
                        delay(500)
                        connectedSuccess = a2dpOk || hfpOk || isDeviceAclConnected(device)
                        if (!connectedSuccess) {
                            connectedSuccess = establishInsecureBasebandWakeup(
                                device = device,
                                uuidsToTry = listOf(UUID_A2DP_SINK, UUID_HANDSFREE, UUID_SPP),
                                channelsToTry = listOf(1, 2),
                                keepSocketOpen = true
                            )
                        }
                    }

                    BypassStrategy.INSECURE_RFCOMM_DIRECT -> {
                        _statusMessage.value = "Opening direct baseband link ($name)..."
                        connectedSuccess = establishInsecureBasebandWakeup(
                            device = device,
                            uuidsToTry = listOf(UUID_SPP, UUID_HID_CLASSIC, UUID_PNP_INFO),
                            channelsToTry = listOf(1, 2, 11, 17, 19),
                            keepSocketOpen = true
                        )
                        connectAllAvailableProfiles(device)
                    }

                    BypassStrategy.BLE_GATT_DIRECT -> {
                        _statusMessage.value = "Connecting BLE GATT ($name)..."
                        connectedSuccess = connectViaBleGatt(device)
                        connectViaProfileProxy(device, PROFILE_HID_HOST)
                    }

                    BypassStrategy.SMART_AUTO -> {
                        _statusMessage.value = "Connecting $name..."
                        val profileOk = connectAllAvailableProfiles(device)
                        val basebandOk = if (deviceModel.deviceType == BluetoothDevice.DEVICE_TYPE_LE) {
                            connectViaBleGatt(device)
                        } else if (!isDeviceAclConnected(device)) {
                            establishInsecureBasebandWakeup(
                                device = device,
                                uuidsToTry = listOf(UUID_HID_CLASSIC, UUID_SPP, UUID_A2DP_SINK),
                                channelsToTry = listOf(1, 17, 19),
                                keepSocketOpen = !profileOk
                            )
                        } else {
                            true
                        }
                        delay(400)
                        connectedSuccess = profileOk || basebandOk || isDeviceAclConnected(device)
                    }
                }

                val aclActive = isDeviceAclConnected(device)
                upsertFromBluetoothDevice(device, forceConnected = connectedSuccess || aclActive)
                refreshBondedAndConnectedDevices()

                if (aclActive || connectedSuccess) {
                    _statusMessage.value = "Connected to $name"
                    appendLog("Connected to $name")
                } else if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    _statusMessage.value = "Paired $name — click/move device to wake"
                    appendLog("Paired $name (waiting for input wake)")
                } else {
                    _statusMessage.value = "Could not reach $name — check pairing mode"
                    appendLog("Connection attempt finished without active link for $name")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                appendLog("Error connecting ${deviceModel.address}: ${e.message}")
                _statusMessage.value = "Failed: ${e.localizedMessage ?: "Connection error"}"
            } finally {
                pendingSocket = null
                if (_connectingAddress.value == address) {
                    _connectingAddress.value = null
                }
                refreshBondedAndConnectedDevices()
            }
        }
    }

    fun disconnectDevice(deviceModel: BtDeviceModel) {
        scope.launch {
            performDisconnectInternal(deviceModel)
            _statusMessage.value = "Disconnected ${deviceModel.displayName}"
        }
    }

    fun unpairDevice(deviceModel: BtDeviceModel) {
        scope.launch {
            val address = deviceModel.address
            performDisconnectInternal(deviceModel)
            val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return@launch
            val removed = invokeRemoveBond(device)
            appendLog("Unpair (removeBond) on $address -> $removed")
            delay(400)
            upsertFromBluetoothDevice(device, forceConnected = false)
            refreshBondedAndConnectedDevices()
            _statusMessage.value = "Unpaired ${deviceModel.displayName}"
        }
    }

    private suspend fun performDisconnectInternal(deviceModel: BtDeviceModel) {
        val address = deviceModel.address
        if (_connectingAddress.value == address) {
            cancelCurrentConnection()
        }
        appendLog("Disconnecting ${deviceModel.displayName} [$address]")

        activeSockets.remove(address)?.let { socket ->
            runCatching { socket.close() }
        }

        activeGatts.remove(address)?.let { gatt ->
            runCatching {
                gatt.disconnect()
                gatt.close()
            }
        }

        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device != null) {
            profileProxies.forEach { (profileId, proxy) ->
                invokeHiddenMethod(
                    target = proxy,
                    methodName = "disconnect",
                    paramTypes = arrayOf(BluetoothDevice::class.java),
                    args = arrayOf(device)
                )
                appendLog("Disconnected ${profileName(profileId)}")
            }
            delay(350)
            upsertFromBluetoothDevice(device, forceConnected = false)
        }
        refreshBondedAndConnectedDevices()
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun handlePairingBypass(device: BluetoothDevice, variant: Int): Boolean {
        val pin = _defaultPin.value.ifBlank { "0000" }
        return try {
            when (variant) {
                BluetoothDevice.PAIRING_VARIANT_PIN -> {
                    val pinBytes = pin.toByteArray(Charsets.UTF_8)
                    val pinSet = device.setPin(pinBytes)
                    runCatching { device.setPairingConfirmation(true) }
                    appendLog("Injected legacy PIN '$pin' for ${device.address} (ok=$pinSet)")
                    pinSet
                }

                BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> {
                    val confirmed = runCatching {
                        device.setPairingConfirmation(true)
                        true
                    }.getOrDefault(false)
                    appendLog("Auto-confirmed SSP Passkey for ${device.address} (ok=$confirmed)")
                    confirmed
                }

                else -> {
                    val pinBytes = pin.toByteArray(Charsets.UTF_8)
                    val pinSet = runCatching { device.setPin(pinBytes) }.getOrDefault(false)
                    runCatching { device.setPairingConfirmation(true) }
                    appendLog("Applied PIN '$pin' & confirmation for variant=$variant")
                    pinSet
                }
            }
        } catch (e: Exception) {
            appendLog("Auto-pair note: ${e.message}")
            false
        }
    }

    private fun invokeCreateBondWithTransport(device: BluetoothDevice, transport: Int): Boolean {
        val res = invokeHiddenMethod(
            target = device,
            methodName = "createBond",
            paramTypes = arrayOf(Int::class.javaPrimitiveType!!),
            args = arrayOf(transport)
        ) as? Boolean
        return if (res == true) {
            true
        } else {
            runCatching { device.createBond() }.getOrDefault(false)
        }
    }

    private fun invokeRemoveBond(device: BluetoothDevice): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDING) {
            invokeHiddenMethod(
                target = device,
                methodName = "cancelBondProcess",
                paramTypes = emptyArray(),
                args = emptyArray()
            )
        }
        return invokeHiddenMethod(
            target = device,
            methodName = "removeBond",
            paramTypes = emptyArray(),
            args = emptyArray()
        ) as? Boolean ?: false
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

        invokeHiddenMethod(
            target = proxy,
            methodName = "setConnectionPolicy",
            paramTypes = arrayOf(BluetoothDevice::class.java, Int::class.javaPrimitiveType!!),
            args = arrayOf(device, 100)
        )
        invokeHiddenMethod(
            target = proxy,
            methodName = "setPriority",
            paramTypes = arrayOf(BluetoothDevice::class.java, Int::class.javaPrimitiveType!!),
            args = arrayOf(device, 100)
        )

        val ok = invokeHiddenMethod(
            target = proxy,
            methodName = "connect",
            paramTypes = arrayOf(BluetoothDevice::class.java),
            args = arrayOf(device)
        ) as? Boolean ?: false

        appendLog("$name.connect(${device.address}) -> $ok")
        return ok
    }

    /**
     * Invokes a method on an Android Bluetooth class using standard reflection first,
     * with a meta-reflection fallback (`Class.getDeclaredMethod`) for hidden system APIs.
     */
    private fun invokeHiddenMethod(
        target: Any,
        methodName: String,
        paramTypes: Array<Class<*>>,
        args: Array<Any?>
    ): Any? {
        return try {
            val method = try {
                target.javaClass.getMethod(methodName, *paramTypes)
            } catch (_: NoSuchMethodException) {
                target.javaClass.getDeclaredMethod(methodName, *paramTypes)
            }
            method.isAccessible = true
            method.invoke(target, *args)
        } catch (_: Exception) {
            try {
                val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                    "getDeclaredMethod",
                    String::class.java,
                    arrayOf<Class<*>>()::class.java
                )
                val method = getDeclaredMethod.invoke(target.javaClass, methodName, paramTypes) as? Method
                    ?: return null
                method.isAccessible = true
                method.invoke(target, *args)
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun connectSocketWithTimeout(
        socket: BluetoothSocket,
        timeoutMs: Long = SOCKET_CONNECT_TIMEOUT_MS
    ): Boolean = withContext(Dispatchers.IO) {
        pendingSocket = socket
        val watchdog = scope.launch {
            delay(timeoutMs)
            runCatching { socket.close() }
        }
        try {
            socket.connect()
            watchdog.cancel()
            true
        } catch (_: Exception) {
            watchdog.cancel()
            runCatching { socket.close() }
            false
        } finally {
            if (pendingSocket === socket) {
                pendingSocket = null
            }
        }
    }

    private suspend fun establishInsecureBasebandWakeup(
        device: BluetoothDevice,
        uuidsToTry: List<UUID>,
        channelsToTry: List<Int>,
        keepSocketOpen: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val candidateUuids = buildList {
            device.uuids?.mapNotNull { it?.uuid }?.forEach { add(it) }
            addAll(uuidsToTry)
        }.distinct().take(3)

        for (uuid in candidateUuids) {
            if (!coroutineContext.isActive) return@withContext false
            val socket = runCatching {
                device.createInsecureRfcommSocketToServiceRecord(uuid)
            }.getOrNull() ?: continue

            appendLog("Paging baseband via UUID ${uuid.toString().take(8)}...")
            if (connectSocketWithTimeout(socket)) {
                appendLog("Baseband link active via UUID ${uuid.toString().take(8)}")
                if (keepSocketOpen) {
                    activeSockets.put(device.address, socket)?.let { old -> runCatching { old.close() } }
                } else {
                    delay(200)
                    runCatching { socket.close() }
                }
                return@withContext true
            }
            if (isDeviceAclConnected(device)) {
                return@withContext true
            }
        }

        for (channel in channelsToTry) {
            if (!coroutineContext.isActive) return@withContext false
            val socket = invokeHiddenMethod(
                target = device,
                methodName = "createInsecureRfcommSocket",
                paramTypes = arrayOf(Int::class.javaPrimitiveType!!),
                args = arrayOf(channel)
            ) as? BluetoothSocket ?: continue

            appendLog("Paging insecure RFCOMM channel $channel...")
            if (connectSocketWithTimeout(socket)) {
                appendLog("Connected on RFCOMM channel $channel")
                if (keepSocketOpen) {
                    activeSockets.put(device.address, socket)?.let { old -> runCatching { old.close() } }
                } else {
                    delay(200)
                    runCatching { socket.close() }
                }
                return@withContext true
            }
            if (isDeviceAclConnected(device)) {
                return@withContext true
            }
        }

        isDeviceAclConnected(device)
    }

    private suspend fun connectViaBleGatt(device: BluetoothDevice): Boolean {
        val resultSignal = CompletableDeferred<Boolean>()
        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    appendLog("BLE GATT connected: ${device.address}")
                    runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                    runCatching { gatt.discoverServices() }
                    upsertFromBluetoothDevice(device, forceConnected = true)
                    if (!resultSignal.isCompleted) resultSignal.complete(true)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    appendLog("BLE GATT disconnected: ${device.address}")
                    activeGatts.remove(device.address)?.let { runCatching { it.close() } }
                    upsertFromBluetoothDevice(device, forceConnected = false)
                    if (!resultSignal.isCompleted) resultSignal.complete(false)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val hasHogp = gatt.services?.any { it.uuid == UUID_HID_LE_HOGP } == true
                appendLog("Discovered ${gatt.services?.size ?: 0} BLE services (HOGP=$hasHogp)")
                upsertFromBluetoothDevice(device, forceConnected = true)
            }
        }

        val gatt = withContext(Dispatchers.Main) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } ?: return false

        activeGatts.put(device.address, gatt)?.let { old ->
            runCatching {
                old.disconnect()
                old.close()
            }
        }

        val connected = withTimeoutOrNull(6_500L) {
            resultSignal.await()
        } ?: false

        if (!connected && !isDeviceAclConnected(device)) {
            activeGatts.remove(device.address)?.let {
                runCatching {
                    it.disconnect()
                    it.close()
                }
            }
        }

        return connected || isDeviceAclConnected(device)
    }

    private fun isDeviceAclConnected(device: BluetoothDevice): Boolean {
        if (activeSockets[device.address]?.isConnected == true) return true
        if (activeGatts.containsKey(device.address)) return true

        for ((_, proxy) in profileProxies) {
            val connected = runCatching { proxy.connectedDevices?.any { it.address == device.address } == true }
                .getOrDefault(false)
            if (connected) return true
        }

        return (invokeHiddenMethod(
            target = device,
            methodName = "isConnected",
            paramTypes = emptyArray(),
            args = emptyArray()
        ) as? Boolean) ?: false
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

        val aliasOrName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { device.alias }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
        }

        val resolvedName = aliasOrName
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
        PROFILE_HEADSET -> "Headset"
        PROFILE_PAN -> "PAN"
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
        cancelCurrentConnection()
        stopScanRadioInternal()
        scanJob?.cancel()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(bluetoothReceiver) }
            receiverRegistered = false
        }
        activeSockets.values.forEach { runCatching { it.close() } }
        activeSockets.clear()
        activeGatts.values.forEach {
            runCatching {
                it.disconnect()
                it.close()
            }
        }
        activeGatts.clear()
        profileProxies.forEach { (profileId, proxy) ->
            runCatching { adapter?.closeProfileProxy(profileId, proxy) }
        }
        profileProxies.clear()
    }
}

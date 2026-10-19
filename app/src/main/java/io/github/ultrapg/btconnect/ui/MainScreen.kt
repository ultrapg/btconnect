package io.github.ultrapg.btconnect.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled. Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ultrapg.btconnect.bluetooth.BluetoothBypassController
import io.github.ultrapg.btconnect.model.BtDeviceModel
import io.github.ultrapg.btconnect.model.BypassStrategy
import io.github.ultrapg.btconnect.model.DeviceCategory

enum class DeviceFilter(val label: String) {
    ALL("All Devices"),
    SAVED_OR_CONNECTED("Saved & Active"),
    MICE_KEYBOARDS("Mice & Keyboards"),
    AUDIO("Audio"),
    NAMED_ONLY("Named Only")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    controller: BluetoothBypassController,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onLaunchCompanionAssociation: () -> Unit
) {
    val devices by controller.devices.collectAsState()
    val isScanning by controller.isScanning.collectAsState()
    val isBluetoothEnabled by controller.isBluetoothEnabled.collectAsState()
    val connectingAddress by controller.connectingAddress.collectAsState()
    val statusMessage by controller.statusMessage.collectAsState()
    val logs by controller.logs.collectAsState()
    val autoPairEnabled by controller.autoPairEnabled.collectAsState()
    val defaultPin by controller.defaultPin.collectAsState()

    var selectedFilter by rememberSaveable { mutableStateOf(DeviceFilter.ALL) }
    var selectedDeviceForSheet by remember { mutableStateOf<BtDeviceModel?>(null) }
    var showMacDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomPinDialog by rememberSaveable { mutableStateOf(false) }
    var showLogSheet by rememberSaveable { mutableStateOf(false) }

    val filteredDevices = remember(devices, selectedFilter) {
        devices.filter { device ->
            when (selectedFilter) {
                DeviceFilter.ALL -> true
                DeviceFilter.SAVED_OR_CONNECTED -> device.isBonded || device.isConnected
                DeviceFilter.MICE_KEYBOARDS -> device.category == DeviceCategory.MOUSE_KEYBOARD_HID
                DeviceFilter.AUDIO -> device.category == DeviceCategory.AUDIO_VIDEO
                DeviceFilter.NAMED_ONLY -> !device.name.isNullOrBlank()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isBluetoothEnabled) Icons.Default.BluetoothConnected else Icons.Default.BluetoothDisabled,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "BTConnect",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Direct Bluetooth Bypass • No Root",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showMacDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.AddLink,
                            contentDescription = "Connect by MAC Address"
                        )
                    }
                    IconButton(onClick = onLaunchCompanionAssociation) {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = "System Companion Device Association"
                        )
                    }
                    IconButton(onClick = { showLogSheet = true }) {
                        Box {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "Bypass Activity Log"
                            )
                            if (logs.isNotEmpty()) {
                                Badge(
                                    modifier = Modifier.align(Alignment.TopEnd)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (hasPermissions && isBluetoothEnabled) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (isScanning) controller.stopScan() else controller.startFullScan()
                    },
                    icon = {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                        }
                    },
                    text = {
                        Text(if (isScanning) "Stop Scan" else "Scan All Devices")
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isScanning || connectingAddress != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Permission or Bluetooth Disabled Banner
                if (!hasPermissions) {
                    item {
                        PermissionCard(onRequestPermissions = onRequestPermissions)
                    }
                } else if (!isBluetoothEnabled) {
                    item {
                        BluetoothDisabledCard(onEnableBluetooth = onEnableBluetooth)
                    }
                }

                // 2. Live Status & Legacy Auto-PIN Helper Card
                item {
                    LegacyBypassConfigCard(
                        statusMessage = statusMessage,
                        autoPairEnabled = autoPairEnabled,
                        defaultPin = defaultPin,
                        onToggleAutoPair = { controller.setAutoPairEnabled(it) },
                        onSelectPin = { controller.setDefaultPin(it) },
                        onOpenCustomPin = { showCustomPinDialog = true },
                        onOpenLogs = { showLogSheet = true }
                    )
                }

                // 3. Filter Chips Row
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DeviceFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = selectedFilter == filter,
                                onClick = { selectedFilter = filter },
                                label = { Text(filter.label) }
                            )
                        }
                    }
                }

                // 4. Device Count & Quick Refresh Row
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${filteredDevices.size} devices (${devices.count { it.isConnected }} connected, ${devices.count { it.isBonded }} saved)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                controller.refreshBondedAndConnectedDevices()
                                if (!isScanning) controller.startFullScan()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh")
                        }
                    }
                }

                // 5. Empty State or Device List
                if (filteredDevices.isEmpty()) {
                    item {
                        EmptyDevicesCard(
                            isScanning = isScanning,
                            onStartScan = { controller.startFullScan() },
                            onConnectByMac = { showMacDialog = true }
                        )
                    }
                } else {
                    items(
                        items = filteredDevices,
                        key = { it.address }
                    ) { device ->
                        DeviceItemCard(
                            device = device,
                            isConnecting = connectingAddress == device.address,
                            onPrimaryAction = {
                                if (device.isConnected) {
                                    controller.disconnectDevice(device)
                                } else {
                                    controller.connectDevice(
                                        deviceModel = device,
                                        strategy = BypassStrategy.SMART_AUTO
                                    )
                                }
                            },
                            onOpenOptions = {
                                selectedDeviceForSheet = device
                            }
                        )
                    }
                }
            }
        }
    }

    // Device Bypass Strategy & Actions Bottom Sheet
    selectedDeviceForSheet?.let { targetDevice ->
        val liveDevice = devices.find { it.address == targetDevice.address } ?: targetDevice
        DeviceBypassBottomSheet(
            device = liveDevice,
            defaultPin = defaultPin,
            onDismiss = { selectedDeviceForSheet = null },
            onConnectWithStrategy = { strategy, forceRebond ->
                selectedDeviceForSheet = null
                controller.connectDevice(
                    deviceModel = liveDevice,
                    strategy = strategy,
                    forceRebond = forceRebond
                )
            },
            onDisconnect = {
                selectedDeviceForSheet = null
                controller.disconnectDevice(liveDevice)
            },
            onUnpair = {
                selectedDeviceForSheet = null
                controller.unpairDevice(liveDevice)
            }
        )
    }

    // Direct MAC Address Connect Dialog
    if (showMacDialog) {
        ConnectByMacDialog(
            defaultPin = defaultPin,
            onDismiss = { showMacDialog = false },
            onConnect = { mac, strategy, pin ->
                showMacDialog = false
                controller.connectByMac(
                    rawAddress = mac,
                    strategy = strategy,
                    customPin = pin
                )
            }
        )
    }

    // Custom PIN Dialog
    if (showCustomPinDialog) {
        CustomPinDialog(
            currentPin = defaultPin,
            onDismiss = { showCustomPinDialog = false },
            onConfirm = { newPin ->
                showCustomPinDialog = false
                controller.setDefaultPin(newPin)
            }
        )
    }

    // Live Bypass Log Sheet
    if (showLogSheet) {
        BypassLogBottomSheet(
            logs = logs,
            onClear = { controller.clearLogs() },
            onDismiss = { showLogSheet = false }
        )
    }
}

@Composable
private fun PermissionCard(onRequestPermissions: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Bluetooth & Discovery Permissions Needed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "To discover legacy Classic BR/EDR devices (like older Apple Magic Mice and keyboards) and connect directly without Android Settings, grant Bluetooth & nearby scan permissions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant Permissions")
            }
        }
    }
}

@Composable
private fun BluetoothDisabledCard(onEnableBluetooth: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BluetoothDisabled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Bluetooth is Turned Off",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onEnableBluetooth,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Turn On Bluetooth")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegacyBypassConfigCard(
    statusMessage: String,
    autoPairEnabled: Boolean,
    defaultPin: String,
    onToggleAutoPair: (Boolean) -> Unit,
    onSelectPin: (String) -> Unit,
    onOpenCustomPin: () -> Unit,
    onOpenLogs: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenLogs() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "BYPASS ENGINE STATUS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "View Logs",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Pair Legacy Devices (Magic Mouse / HID)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Automatically injects PIN & confirms pairing before Android Settings fails",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = autoPairEnabled,
                    onCheckedChange = onToggleAutoPair
                )
            }

            AnimatedVisibility(visible = autoPairEnabled) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        val presetPins = listOf("0000", "1234", "1111")
                        presetPins.forEach { pin ->
                            FilterChip(
                                selected = defaultPin == pin,
                                onClick = { onSelectPin(pin) },
                                label = {
                                    Text(if (pin == "0000") "PIN: 0000 (Magic Mouse)" else "PIN: $pin")
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Key,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                        AssistChip(
                            onClick = onOpenCustomPin,
                            label = {
                                val isCustom = defaultPin !in presetPins
                                Text(if (isCustom) "Custom: $defaultPin" else "Custom PIN...")
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceItemCard(
    device: BtDeviceModel,
    isConnecting: Boolean,
    onPrimaryAction: () -> Unit,
    onOpenOptions: () -> Unit
) {
    val containerColor = when {
        device.isConnected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        device.isBonded -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        onClick = onOpenOptions,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (device.isConnected) 2.dp else 1.dp),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (device.isConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = categoryIcon(device.category),
                            contentDescription = null,
                            tint = if (device.isConnected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = device.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = buildString {
                            append(device.address)
                            append(" • ")
                            append(device.transportLabel)
                            device.rssi?.let { append(" • $it dBm") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                FilledTonalIconButton(onClick = onOpenOptions) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Connection Mode & Options",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Status Badges + One-Tap Connect Button Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (device.isConnected) {
                        StatusPill(
                            text = if (device.connectedProfiles.isNotEmpty()) {
                                "Connected (${device.connectedProfiles.joinToString(", ")})"
                            } else {
                                "Connected"
                            },
                            isHighlighted = true
                        )
                    } else if (device.isBonding) {
                        StatusPill(text = "Pairing...", isHighlighted = true)
                    } else if (device.isBonded) {
                        StatusPill(text = "Saved / Paired", isHighlighted = false)
                    }

                    StatusPill(
                        text = if (device.isLikelyLegacyHidOrMagicMouse) {
                            "HID / Mouse Bypass"
                        } else {
                            device.recommendedStrategy.badge
                        },
                        isHighlighted = false
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (device.isConnected) {
                    FilledTonalButton(
                        onClick = onPrimaryAction,
                        enabled = !isConnecting
                    ) {
                        Icon(
                            imageVector = Icons.Default.LinkOff,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Disconnect")
                    }
                } else {
                    Button(
                        onClick = onPrimaryAction,
                        enabled = !isConnecting
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connecting...")
                        } else {
                            Icon(
                                imageVector = Icons.Default.BluetoothConnected,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Connect")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, isHighlighted: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (isHighlighted) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (isHighlighted) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun EmptyDevicesCard(
    isScanning: Boolean,
    onStartScan: () -> Unit,
    onConnectByMac: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.BluetoothSearching,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isScanning) "Scanning for nearby Bluetooth devices..." else "No devices matching filter",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Turn on your Bluetooth mouse, keyboard, or audio device and put it into pairing mode. For Apple Magic Mouse, turn the switch off and back on while holding the button.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isScanning) {
                    Button(onClick = onStartScan) {
                        Text("Start Unfiltered Scan")
                    }
                }
                OutlinedButton(onClick = onConnectByMac) {
                    Text("Connect by MAC")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceBypassBottomSheet(
    device: BtDeviceModel,
    defaultPin: String,
    onDismiss: () -> Unit,
    onConnectWithStrategy: (BypassStrategy, Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onUnpair: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var chosenStrategy by remember(device.address) {
        mutableStateOf(device.recommendedStrategy)
    }
    var forceFreshRebond by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = device.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${device.address} • ${device.category.label} • ${device.transportLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Select Bypass Connection Method",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            BypassStrategy.entries.forEach { strategy ->
                val isSelected = chosenStrategy == strategy
                Surface(
                    onClick = { chosenStrategy = strategy },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { chosenStrategy = strategy }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strategy.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = strategy.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (device.isBonded) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { forceFreshRebond = !forceFreshRebond }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Force Fresh Re-Pair (Clear Stale Bond First)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Use when a saved device refuses to reconnect after battery swap",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = forceFreshRebond,
                        onCheckedChange = { forceFreshRebond = it }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = { onConnectWithStrategy(chosenStrategy, forceFreshRebond) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.BluetoothConnected, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Force Connect Now (PIN: $defaultPin)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (device.isConnected) {
                    FilledTonalButton(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Disconnect")
                    }
                }
                if (device.isBonded) {
                    OutlinedButton(
                        onClick = onUnpair,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Forget / Unpair")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectByMacDialog(
    defaultPin: String,
    onDismiss: () -> Unit,
    onConnect: (String, BypassStrategy, String) -> Unit
) {
    var macInput by rememberSaveable { mutableStateOf("") }
    var pinInput by rememberSaveable { mutableStateOf(defaultPin) }
    var strategy by remember { mutableStateOf(BypassStrategy.LEGACY_HID_MAGIC_MOUSE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Direct Connect by MAC Address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Connect directly to an older or hidden Bluetooth device even if Android discovery cannot see it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = macInput,
                    onValueChange = { macInput = it },
                    label = { Text("Bluetooth MAC (e.g. 00:1A:7D:DA:71:13)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("Legacy Pairing PIN (0000 or 1234)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BypassStrategy.entries.forEach { item ->
                        FilterChip(
                            selected = strategy == item,
                            onClick = { strategy = item },
                            label = { Text(item.badge) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConnect(macInput, strategy, pinInput) },
                enabled = macInput.trim().length >= 11
            ) {
                Text("Force Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CustomPinDialog(
    currentPin: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pinText by rememberSaveable { mutableStateOf(currentPin) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Legacy Pairing PIN") },
        text = {
            Column {
                Text(
                    text = "Apple Magic Mouse (1st Gen) and most older mice/keyboards use '0000' or '1234'.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = pinText,
                    onValueChange = { pinText = it },
                    label = { Text("PIN Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pinText.ifBlank { "0000" }) }
            ) {
                Text("Save PIN")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BypassLogBottomSheet(
    logs: List<String>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Bypass Engine Diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row {
                    TextButton(onClick = onClear) {
                        Text("Clear")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "No bypass events logged yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs.reversed()) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun categoryIcon(category: DeviceCategory): ImageVector = when (category) {
    DeviceCategory.MOUSE_KEYBOARD_HID -> Icons.Default.Mouse
    DeviceCategory.AUDIO_VIDEO -> Icons.Default.Headphones
    DeviceCategory.PHONE_COMPUTER -> Icons.Default.Computer
    DeviceCategory.WEARABLE_HEALTH -> Icons.Default.Watch
    DeviceCategory.OTHER_PERIPHERAL -> Icons.Default.Bluetooth
}

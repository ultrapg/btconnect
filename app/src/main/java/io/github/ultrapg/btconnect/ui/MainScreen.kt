package io.github.ultrapg.btconnect.ui

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
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
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
    ALL("All"),
    SAVED_OR_CONNECTED("Saved"),
    MICE_KEYBOARDS("Mice & HID"),
    AUDIO("Audio"),
    NAMED_ONLY("Named")
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var showPinSettingsSheet by rememberSaveable { mutableStateOf(false) }
    var showMacDialog by rememberSaveable { mutableStateOf(false) }
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
                    Column(
                        modifier = Modifier.clickable { showLogSheet = true }
                    ) {
                        Text(
                            text = "BTConnect",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    AssistChip(
                        onClick = { showPinSettingsSheet = true },
                        label = {
                            Text(
                                text = if (autoPairEnabled) "PIN $defaultPin" else "PIN Off",
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = "Legacy PIN Settings",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                    IconButton(onClick = { showMacDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.AddLink,
                            contentDescription = "Connect by MAC Address"
                        )
                    }
                    IconButton(onClick = onLaunchCompanionAssociation) {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = "System Companion Association"
                        )
                    }
                    IconButton(onClick = { showLogSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Diagnostics Log"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
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
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.BluetoothSearching, contentDescription = null)
                        }
                    },
                    text = {
                        Text(if (isScanning) "Stop" else "Scan")
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

            // Minimal Single-Line Filter Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DeviceFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter.label) }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 92.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!hasPermissions) {
                    item {
                        PermissionCard(onRequestPermissions = onRequestPermissions)
                    }
                } else if (!isBluetoothEnabled) {
                    item {
                        BluetoothDisabledCard(onEnableBluetooth = onEnableBluetooth)
                    }
                }

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
                        MinimalDeviceRow(
                            device = device,
                            isConnecting = connectingAddress == device.address,
                            onPrimaryAction = {
                                when {
                                    connectingAddress == device.address -> controller.cancelCurrentConnection()
                                    device.isConnected -> controller.disconnectDevice(device)
                                    else -> controller.connectDevice(
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

    // Device Options & Bypass Strategy Sheet
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

    // Legacy Auto-PIN Settings Sheet
    if (showPinSettingsSheet) {
        LegacyPinBottomSheet(
            autoPairEnabled = autoPairEnabled,
            defaultPin = defaultPin,
            onToggleAutoPair = { controller.setAutoPairEnabled(it) },
            onSelectPin = { controller.setDefaultPin(it) },
            onDismiss = { showPinSettingsSheet = false }
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

    // Diagnostics Log Sheet
    if (showLogSheet) {
        BypassLogBottomSheet(
            logs = logs,
            onClear = { controller.clearLogs() },
            onDismiss = { showLogSheet = false }
        )
    }
}

@Composable
private fun MinimalDeviceRow(
    device: BtDeviceModel,
    isConnecting: Boolean,
    onPrimaryAction: () -> Unit,
    onOpenOptions: () -> Unit
) {
    val containerColor = when {
        device.isConnected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        device.isBonded -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        onClick = onOpenOptions,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (device.isConnected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = categoryIcon(device.category),
                        contentDescription = null,
                        tint = if (device.isConnected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = device.conciseSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.isConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            when {
                isConnecting -> {
                    FilledTonalButton(
                        onClick = onPrimaryAction,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cancel", style = MaterialTheme.typography.labelLarge)
                    }
                }

                device.isConnected -> {
                    FilledTonalButton(
                        onClick = onPrimaryAction,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text("Disconnect", style = MaterialTheme.typography.labelLarge)
                    }
                }

                else -> {
                    Button(
                        onClick = onPrimaryAction,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Connect", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            IconButton(
                onClick = onOpenOptions,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More Options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermissions: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Bluetooth Permission Needed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Required for unfiltered Classic & BLE discovery",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onRequestPermissions) {
                Text("Grant")
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
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.BluetoothDisabled,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Bluetooth is turned off",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onEnableBluetooth) {
                Text("Turn On")
            }
        }
    }
}

@Composable
private fun EmptyDevicesCard(
    isScanning: Boolean,
    onStartScan: () -> Unit,
    onConnectByMac: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(42.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (isScanning) "Scanning nearby devices..." else "No devices in this view",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Put your mouse, keyboard, or audio device in pairing mode.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isScanning) {
                Button(onClick = onStartScan) {
                    Text("Scan Now")
                }
            }
            OutlinedButton(onClick = onConnectByMac) {
                Text("Connect by MAC")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LegacyPinBottomSheet(
    autoPairEnabled: Boolean,
    defaultPin: String,
    onToggleAutoPair: (Boolean) -> Unit,
    onSelectPin: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customPinText by rememberSaveable(defaultPin) { mutableStateOf(defaultPin) }

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
                text = "Legacy Auto-PIN Bypass",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Automatically answers legacy PIN requests (such as Apple Magic Mouse 1st Gen '0000' or older keyboards) before Android Settings times out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Auto-Inject Pairing PIN",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = autoPairEnabled,
                    onCheckedChange = onToggleAutoPair
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "0000" to "0000 (Magic Mouse / Default)",
                    "1234" to "1234",
                    "1111" to "1111",
                    "8888" to "8888"
                ).forEach { (pin, label) ->
                    FilterChip(
                        selected = defaultPin == pin,
                        onClick = {
                            customPinText = pin
                            onSelectPin(pin)
                        },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = customPinText,
                onValueChange = {
                    customPinText = it
                    if (it.isNotBlank()) onSelectPin(it)
                },
                label = { Text("Custom PIN Code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
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

            BypassStrategy.entries.forEach { strategy ->
                val isSelected = chosenStrategy == strategy
                Surface(
                    onClick = { chosenStrategy = strategy },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { chosenStrategy = strategy }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
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
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { forceFreshRebond = !forceFreshRebond },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Force Fresh Re-Pair",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Clears stale pairing state before connecting",
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
                Icon(Icons.Default.BluetoothConnected, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connect (PIN $defaultPin)")
            }

            if (device.isConnected || device.isBonded) {
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
                            Text("Forget")
                        }
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
    val isValidMac = BtDeviceModel.normalizeMacAddress(macInput) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect by MAC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = macInput,
                    onValueChange = { macInput = it },
                    label = { Text("MAC Address (XX:XX:XX:XX:XX:XX)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("Pairing PIN") },
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
                enabled = isValidMac
            ) {
                Text("Connect")
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
                    text = "Bypass Activity Log",
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
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "No events logged yet.",
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

package io.github.ultrapg.btconnect.model

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import java.util.Locale

enum class DeviceCategory(val label: String, val shortLabel: String) {
    MOUSE_KEYBOARD_HID("Mouse / Keyboard / HID", "HID Input"),
    AUDIO_VIDEO("Audio / Speaker / Headset", "Audio"),
    PHONE_COMPUTER("Computer / Phone", "Computer/Phone"),
    WEARABLE_HEALTH("Wearable / Accessory", "Wearable"),
    OTHER_PERIPHERAL("Bluetooth Peripheral", "Bluetooth")
}

enum class BypassStrategy(
    val title: String,
    val subtitle: String,
    val badge: String
) {
    SMART_AUTO(
        title = "Smart Bypass (Recommended)",
        subtitle = "Auto-detects HID, Audio, Classic, or BLE; injects PIN & forces profile + baseband",
        badge = "Auto"
    ),
    LEGACY_HID_MAGIC_MOUSE(
        title = "Legacy Mouse / Keyboard (Magic Mouse)",
        subtitle = "Forces Classic BR/EDR bond, auto-PIN 0000, HID Host reflection & wakeup channel",
        badge = "HID / Mouse"
    ),
    AUDIO_HEADSET_FORCE(
        title = "Force Audio (A2DP + Headset)",
        subtitle = "Direct A2DP & HFP profile proxy reflection + SDP wakeup",
        badge = "Audio"
    ),
    INSECURE_RFCOMM_DIRECT(
        title = "Direct Insecure Baseband Socket",
        subtitle = "No-MITM insecure RFCOMM link across legacy channels for stubborn devices",
        badge = "Baseband"
    ),
    BLE_GATT_DIRECT(
        title = "Direct BLE GATT Bridge",
        subtitle = "High-priority LE GATT connection & HID-over-GATT service discovery",
        badge = "BLE GATT"
    )
}

data class BtDeviceModel(
    val address: String,
    val name: String?,
    val bondState: Int = BluetoothDevice.BOND_NONE,
    val deviceType: Int = BluetoothDevice.DEVICE_TYPE_UNKNOWN,
    val majorClass: Int = BluetoothClass.Device.Major.UNCATEGORIZED,
    val deviceClass: Int = 0,
    val rssi: Int? = null,
    val isConnected: Boolean = false,
    val connectedProfiles: Set<String> = emptySet(),
    val uuids: List<String> = emptyList(),
    val lastSeenMs: Long = System.currentTimeMillis()
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: "Device ${address.takeLast(8)}"

    val isBonded: Boolean
        get() = bondState == BluetoothDevice.BOND_BONDED

    val isBonding: Boolean
        get() = bondState == BluetoothDevice.BOND_BONDING

    val transportLabel: String
        get() = when (deviceType) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
            BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
            else -> "BT"
        }

    val isLikelyLegacyHidOrMagicMouse: Boolean
        get() {
            val lower = (name ?: "").lowercase(Locale.US)
            if (lower.contains("magic mouse") ||
                lower.contains("magic trackpad") ||
                lower.contains("wireless keyboard") ||
                lower.contains("apple") ||
                lower.contains("mouse") ||
                lower.contains("keyboard") ||
                lower.contains("trackpad") ||
                lower.contains("gamepad") ||
                lower.contains("controller") ||
                lower.contains("joystick")
            ) {
                return true
            }
            if (majorClass == BluetoothClass.Device.Major.PERIPHERAL) {
                return true
            }
            return uuids.any {
                it.startsWith("00001124", ignoreCase = true) ||
                    it.startsWith("00001812", ignoreCase = true)
            }
        }

    val category: DeviceCategory
        get() = when {
            isLikelyLegacyHidOrMagicMouse -> DeviceCategory.MOUSE_KEYBOARD_HID
            majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                uuids.any {
                    it.startsWith("0000110b", ignoreCase = true) ||
                        it.startsWith("00001108", ignoreCase = true) ||
                        it.startsWith("0000111e", ignoreCase = true)
                } ||
                (name ?: "").lowercase(Locale.US).let {
                    it.contains("headphone") || it.contains("speaker") ||
                        it.contains("earbud") || it.contains("airpods") ||
                        it.contains("audio") || it.contains("buds") ||
                        it.contains("headset")
                } -> DeviceCategory.AUDIO_VIDEO
            majorClass == BluetoothClass.Device.Major.COMPUTER ||
                majorClass == BluetoothClass.Device.Major.PHONE -> DeviceCategory.PHONE_COMPUTER
            majorClass == BluetoothClass.Device.Major.WEARABLE ||
                majorClass == BluetoothClass.Device.Major.HEALTH -> DeviceCategory.WEARABLE_HEALTH
            else -> DeviceCategory.OTHER_PERIPHERAL
        }

    val recommendedStrategy: BypassStrategy
        get() = when {
            isLikelyLegacyHidOrMagicMouse -> BypassStrategy.LEGACY_HID_MAGIC_MOUSE
            category == DeviceCategory.AUDIO_VIDEO -> BypassStrategy.AUDIO_HEADSET_FORCE
            deviceType == BluetoothDevice.DEVICE_TYPE_LE -> BypassStrategy.BLE_GATT_DIRECT
            else -> BypassStrategy.SMART_AUTO
        }

    val conciseSubtitle: String
        get() = when {
            isConnected -> {
                if (connectedProfiles.isNotEmpty()) {
                    "Connected • ${connectedProfiles.joinToString(", ")}"
                } else {
                    "Connected • ${category.shortLabel}"
                }
            }
            isBonding -> "Pairing..."
            isBonded -> "Saved • ${category.shortLabel} • $address"
            else -> buildString {
                append(category.shortLabel)
                append(" • ")
                append(address)
                rssi?.let { append(" • ${it}dBm") }
            }
        }

    companion object {
        /**
         * Normalizes any user-entered MAC string (e.g. "001a7dda7113", "00-1A-7D-DA-71-13",
         * or "00:1A:7D:DA:71:13") into standard uppercase "XX:XX:XX:XX:XX:XX" format,
         * or returns null if invalid.
         */
        fun normalizeMacAddress(raw: String): String? {
            val hexOnly = raw.trim().uppercase(Locale.US).filter { it in '0'..'9' || it in 'A'..'F' }
            if (hexOnly.length != 12) return null
            return hexOnly.chunked(2).joinToString(":")
        }
    }
}

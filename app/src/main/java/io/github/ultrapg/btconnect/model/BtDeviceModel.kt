package io.github.ultrapg.btconnect.model

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

    private val isAudioByClassOrName: Boolean
        get() {
            val lower = (name ?: "").lowercase(Locale.US)
            if (lower.contains("audio") || lower.contains("speaker") ||
                lower.contains("headphone") || lower.contains("earbud") ||
                lower.contains("airpods") || lower.contains("buds") ||
                lower.contains("headset") || lower.contains("sound")
            ) {
                return true
            }
            if (majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO) {
                return true
            }
            return uuids.any {
                it.startsWith("0000110b", ignoreCase = true) ||
                    it.startsWith("00001108", ignoreCase = true) ||
                    it.startsWith("0000111e", ignoreCase = true)
            }
        }

    private val isWearableByClassOrName: Boolean
        get() {
            val lower = (name ?: "").lowercase(Locale.US)
            return majorClass == BluetoothClass.Device.Major.WEARABLE ||
                majorClass == BluetoothClass.Device.Major.HEALTH ||
                lower.contains("watch") || lower.contains("band") || lower.contains("ring")
        }

    val isLikelyLegacyHidOrMagicMouse: Boolean
        get() {
            if (isAudioByClassOrName || isWearableByClassOrName) return false
            val lower = (name ?: "").lowercase(Locale.US)
            if (lower.contains("magic mouse") ||
                lower.contains("magic trackpad") ||
                lower.contains("wireless keyboard") ||
                lower.contains("apple") ||
                lower.contains("mouse") ||
                lower.contains("maus") ||
                lower.contains("keyboard") ||
                lower.contains("trackpad") ||
                lower.contains("gamepad") ||
                lower.contains("controller") ||
                lower.contains("8bitdo") ||
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
            isWearableByClassOrName -> DeviceCategory.WEARABLE_HEALTH
            isAudioByClassOrName -> DeviceCategory.AUDIO_VIDEO
            isLikelyLegacyHidOrMagicMouse -> DeviceCategory.MOUSE_KEYBOARD_HID
            majorClass == BluetoothClass.Device.Major.COMPUTER ||
                majorClass == BluetoothClass.Device.Major.PHONE -> DeviceCategory.PHONE_COMPUTER
            else -> DeviceCategory.OTHER_PERIPHERAL
        }

    val recommendedStrategy: BypassStrategy
        get() = when {
            category == DeviceCategory.MOUSE_KEYBOARD_HID -> BypassStrategy.LEGACY_HID_MAGIC_MOUSE
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
        fun normalizeMacAddress(raw: String): String? {
            val hexOnly = raw.trim().uppercase(Locale.US).filter { it in '0'..'9' || it in 'A'..'F' }
            if (hexOnly.length != 12) return null
            return hexOnly.chunked(2).joinToString(":")
        }
    }
}

package io.github.ultrapg.btconnect.model

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice

enum class DeviceCategory(val label: String) {
    MOUSE_KEYBOARD_HID("Mouse / Keyboard / HID"),
    AUDIO_VIDEO("Audio / Speaker / Headset"),
    PHONE_COMPUTER("Computer / Phone"),
    WEARABLE_HEALTH("Wearable / Accessory"),
    OTHER_PERIPHERAL("Bluetooth Device")
}

enum class BypassStrategy(
    val title: String,
    val subtitle: String,
    val badge: String
) {
    SMART_AUTO(
        title = "Smart Bypass (Recommended)",
        subtitle = "Auto-detects HID, Audio, Classic, or BLE; injects legacy PIN & forces profile + baseband link",
        badge = "Auto"
    ),
    LEGACY_HID_MAGIC_MOUSE(
        title = "Legacy Mouse / Keyboard (Magic Mouse)",
        subtitle = "Forces Classic BR/EDR bond, auto-PIN 0000, HID Host Profile reflection & insecure wakeup channel",
        badge = "HID / Mouse"
    ),
    AUDIO_HEADSET_FORCE(
        title = "Force Audio (A2DP + Headset)",
        subtitle = "Bypasses Settings audio queue via direct A2DP & HFP profile proxy reflection",
        badge = "Audio"
    ),
    INSECURE_RFCOMM_DIRECT(
        title = "Direct Insecure Baseband Socket",
        subtitle = "Establishes no-MITM insecure RFCOMM link across legacy channels (1-19) for stubborn devices",
        badge = "Baseband"
    ),
    BLE_GATT_DIRECT(
        title = "Direct BLE GATT Bridge",
        subtitle = "Forces high-priority LE transport GATT connection & discovers HOGP / vendor services",
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
        get() = name?.takeIf { it.isNotBlank() } ?: "Unnamed Device (${address.takeLast(8)})"

    val isBonded: Boolean
        get() = bondState == BluetoothDevice.BOND_BONDED

    val isBonding: Boolean
        get() = bondState == BluetoothDevice.BOND_BONDING

    val transportLabel: String
        get() = when (deviceType) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic BR/EDR"
            BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual (BR/EDR + BLE)"
            else -> "Classic / Auto"
        }

    val isLikelyLegacyHidOrMagicMouse: Boolean
        get() {
            val lower = (name ?: "").lowercase()
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
            // HID UUIDs (00001124 for Classic HID, 00001812 for BLE HOGP)
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
                (name ?: "").lowercase().let {
                    it.contains("headphone") || it.contains("speaker") ||
                        it.contains("earbud") || it.contains("airpods") ||
                        it.contains("audio") || it.contains("buds")
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
}

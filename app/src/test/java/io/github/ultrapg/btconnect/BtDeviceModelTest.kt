package io.github.ultrapg.btconnect

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import io.github.ultrapg.btconnect.model.BtDeviceModel
import io.github.ultrapg.btconnect.model.BypassStrategy
import io.github.ultrapg.btconnect.model.DeviceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BtDeviceModelTest {

    @Test
    fun `detects Apple Magic Mouse and recommends Legacy HID bypass`() {
        val magicMouse = BtDeviceModel(
            address = "00:1A:7D:DA:71:13",
            name = "Apple Magic Mouse",
            bondState = BluetoothDevice.BOND_NONE,
            deviceType = BluetoothDevice.DEVICE_TYPE_CLASSIC,
            majorClass = BluetoothClass.Device.Major.PERIPHERAL
        )
        assertTrue(magicMouse.isLikelyLegacyHidOrMagicMouse)
        assertEquals(DeviceCategory.MOUSE_KEYBOARD_HID, magicMouse.category)
        assertEquals(BypassStrategy.LEGACY_HID_MAGIC_MOUSE, magicMouse.recommendedStrategy)
    }

    @Test
    fun `detects audio device and recommends Audio Force bypass`() {
        val headphones = BtDeviceModel(
            address = "11:22:33:44:55:66",
            name = "Studio Headphones",
            bondState = BluetoothDevice.BOND_BONDED,
            deviceType = BluetoothDevice.DEVICE_TYPE_CLASSIC,
            majorClass = BluetoothClass.Device.Major.AUDIO_VIDEO
        )
        assertEquals(DeviceCategory.AUDIO_VIDEO, headphones.category)
        assertEquals(BypassStrategy.AUDIO_HEADSET_FORCE, headphones.recommendedStrategy)
    }
}

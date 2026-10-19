# BTConnect

**BTConnect** is a lightweight, Material Design 3 Android application whose sole purpose is connecting **any** Bluetooth device (especially older or stubborn devices like the 1st-Gen Apple Magic Mouse, legacy wireless keyboards, gamepads, and older audio/serial peripherals) directly to an Android phone **without requiring root**.

---

## Why BTConnect?

Android's built-in Settings Bluetooth menu frequently refuses to discover, pair, or connect older Bluetooth peripherals:
- **Discovery Filtering**: Android Settings hides unnamed devices, misclassifies dual-mode devices, or stops Classic BR/EDR inquiry scans too early.
- **Broken Legacy PIN Pairing**: Older HID peripherals (such as the Apple Magic Mouse 1st Gen or legacy wireless keyboards) require fixed legacy PIN codes (`0000` or `1234`) over Classic BR/EDR transport, which Android's default pairing prompt often times out or rejects.
- **Silent Profile Refusal**: Even when a device shows as "Saved", Android Settings often refuses to initiate the `BluetoothHidHost` (Input Device), `A2DP`, `Headset`, or `PAN` profile connection.

## How BTConnect Bypasses Android's Bluetooth Settings (No Root)

1. **Unfiltered Dual-Stack Scanner**: Runs simultaneous Classic BR/EDR (`BluetoothAdapter.startDiscovery()`) and Low-Latency BLE (`BluetoothLeScanner` with `SCAN_MODE_LOW_LATENCY`) discovery with zero device filtering.
2. **High-Priority Legacy Auto-PIN Interceptor**: Intercepts `BluetoothDevice.ACTION_PAIRING_REQUEST` at high priority to automatically inject legacy PINs (`0000`, `1234`, `1111`, or custom) and confirm SSP passkeys before Android's dialog fails.
3. **Explicit Transport Bonding**: Uses `createBond(int transport)` reflection to force `TRANSPORT_BREDR` (`1`) for Classic HID/audio devices or `TRANSPORT_LE` (`2`) for BLE devices.
4. **Direct System Profile Proxy Reflection**: Binds directly to `BluetoothHidHost` (`profileId = 4`), `BluetoothA2dp` (`2`), `BluetoothHeadset` (`1`), and `BluetoothPan` (`5`) proxies and invokes `setConnectionPolicy`, `setPriority`, and `connect(BluetoothDevice)` directly.
5. **Insecure Baseband & BLE GATT Wake-Up**: Opens no-MITM insecure RFCOMM sockets (`createInsecureRfcommSocketToServiceRecord` & direct channel reflection `1..19`) and direct `connectGatt(..., TRANSPORT_LE)` bridges to force an active baseband ACL handshake.
6. **Direct MAC Connect & Companion Device Association**: Connect directly by hardware MAC address (`XX:XX:XX:XX:XX:XX`) even if a device is hidden from discovery, or use Android's `CompanionDeviceManager` system association.

---

## Building from Source

```bash
./gradlew assembleRelease
```

> **Note on Signing**: Release builds automatically use the system-wide keystore (`~/release.jks` or `$RELEASE_KEYSTORE`) if present. No signing keys are stored in this repository.

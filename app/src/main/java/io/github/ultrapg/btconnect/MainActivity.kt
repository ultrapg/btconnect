package io.github.ultrapg.btconnect

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.ultrapg.btconnect.bluetooth.BluetoothBypassController
import io.github.ultrapg.btconnect.ui.MainScreen
import io.github.ultrapg.btconnect.ui.theme.BTConnectTheme
import java.util.concurrent.Executor

class MainActivity : ComponentActivity() {

    private lateinit var bypassController: BluetoothBypassController
    private var hasPermissions by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermissions = checkRequiredPermissions()
        if (hasPermissions) {
            bypassController.initialize()
            bypassController.startFullScan()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        bypassController.refreshBondedAndConnectedDevices()
        bypassController.startFullScan()
    }

    private val companionChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        val macAddress = extractCompanionMacAddress(data)
        if (!macAddress.isNullOrBlank()) {
            bypassController.appendLog("CompanionDeviceManager selected: $macAddress")
            bypassController.connectByMac(macAddress)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bypassController = BluetoothBypassController(applicationContext)
        hasPermissions = checkRequiredPermissions()

        if (hasPermissions) {
            bypassController.initialize()
            bypassController.startFullScan()
        } else {
            requestBluetoothPermissions()
        }

        setContent {
            BTConnectTheme {
                MainScreen(
                    controller = bypassController,
                    hasPermissions = hasPermissions,
                    onRequestPermissions = { requestBluetoothPermissions() },
                    onEnableBluetooth = {
                        runCatching {
                            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            enableBluetoothLauncher.launch(enableIntent)
                        }
                    },
                    onLaunchCompanionAssociation = {
                        launchCompanionDeviceAssociation()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasPermissions = checkRequiredPermissions()
        if (hasPermissions) {
            bypassController.initialize()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::bypassController.isInitialized) {
            bypassController.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun extractCompanionMacAddress(data: Intent): String? {
        val extraObj: Any? = data.extras?.get(CompanionDeviceManager.EXTRA_DEVICE)
        return when (extraObj) {
            is BluetoothDevice -> extraObj.address
            is ScanResult -> extraObj.device?.address
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && extraObj is AssociationInfo) {
                    extraObj.deviceMacAddress?.toString()
                } else {
                    null
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    private fun checkRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        permissionLauncher.launch(requiredPermissions())
    }

    private fun launchCompanionDeviceAssociation() {
        val deviceManager = getSystemService(CompanionDeviceManager::class.java) ?: return
        val deviceFilter = BluetoothDeviceFilter.Builder().build()
        val pairingRequest = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(false)
            .build()

        bypassController.appendLog("Opening system CompanionDeviceManager chooser...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val executor = Executor { it.run() }
            deviceManager.associate(
                pairingRequest,
                executor,
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: IntentSender) {
                        val req = IntentSenderRequest.Builder(intentSender).build()
                        companionChooserLauncher.launch(req)
                    }

                    override fun onAssociationCreated(associationInfo: AssociationInfo) {
                        val mac = associationInfo.deviceMacAddress?.toString()
                        if (!mac.isNullOrBlank()) {
                            bypassController.appendLog("Companion association created: $mac")
                            bypassController.connectByMac(mac)
                        }
                    }

                    override fun onFailure(error: CharSequence?) {
                        bypassController.appendLog("Companion association note: $error")
                    }
                }
            )
        } else {
            @Suppress("DEPRECATION")
            deviceManager.associate(
                pairingRequest,
                object : CompanionDeviceManager.Callback() {
                    @Suppress("DEPRECATION")
                    override fun onDeviceFound(chooserLauncher: IntentSender) {
                        val req = IntentSenderRequest.Builder(chooserLauncher).build()
                        companionChooserLauncher.launch(req)
                    }

                    override fun onFailure(error: CharSequence?) {
                        bypassController.appendLog("Companion association note: $error")
                    }
                },
                null
            )
        }
    }
}

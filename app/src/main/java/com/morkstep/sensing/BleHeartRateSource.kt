package com.morkstep.sensing

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real heart-rate source from a Bluetooth LE heart-rate strap
 * (Bluetooth Heart Rate Service, 0x180D / measurement 0x2A37).
 *
 * Scans for the service, connects to the first found device, enables
 * notifications, and parses the Heart Rate Measurement payload
 * (8-bit or 16-bit HR per the flag byte).
 *
 * If BLE is unavailable (no adapter, Bluetooth off, permissions denied, or
 * no strap in range) [hr] simply stays `null` — there is NO simulated
 * fallback; callers must treat `null` as "unknown".
 */
class BleHeartRateSource(context: Context) : HeartRateSource {
    private val appContext = context.applicationContext
    private val _hr = MutableStateFlow<Int?>(null)
    override val hr: StateFlow<Int?> = _hr.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val btManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = btManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var started = false

    init {
        start()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true
        if (!canScan()) return
        val adapter = adapter ?: return
        if (!adapter.isEnabled) return
        scanForStrap()
    }

    fun stop() {
        started = false
        stopScan()
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    // ---- scanning ----

    @SuppressLint("MissingPermission")
    private fun scanForStrap() {
        if (!canScan()) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HR_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (_: SecurityException) {
            return
        }
        // Give up after 60s rather than drain battery forever.
        handler.postDelayed({ if (scanning) stopScan() }, 60_000L)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (gatt != null) return // already connected
            stopScan()
            connect(result.device)
        }
    }

    // ---- connect + notifications ----

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (!canConnect()) return
        gatt = device.connectGatt(appContext, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _hr.value = null
                    g.close()
                    gatt = null
                    // Re-scan so a reconnect can happen automatically.
                    handler.post { if (started && canScan()) scanForStrap() }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = g.getService(HR_SERVICE) ?: return
            val characteristic = service.getCharacteristic(HR_MEASUREMENT) ?: return
            g.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(CCCD)
                ?: return
            val enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Memory-safe API 33+ overload: the value is passed in, not stored on the descriptor.
                g.writeDescriptor(cccd, enableValue)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enableValue
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != HR_MEASUREMENT) return
            parseHeartRate(value)?.let { _hr.value = it }
        }
    }

    private fun canScan(): Boolean =
        adapter != null && ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    private fun canConnect(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Parse Bluetooth Heart Rate Measurement payload (flag byte + HR). Internal for tests. */
        fun parseHeartRate(data: ByteArray): Int? {
            if (data.isEmpty()) return null
            val flags = data[0].toInt() and 0xff
            return if (flags and 0x01 != 0) {
                // 16-bit heart rate
                if (data.size < 3) null
                else (data[1].toInt() and 0xff) or ((data[2].toInt() and 0xff) shl 8)
            } else {
                // 8-bit heart rate
                if (data.size < 2) null else data[1].toInt() and 0xff
            }
        }
    }
}
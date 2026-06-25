package com.debomoy97.ledmood

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val TAG = "LedDmxController"

/**
 * Direct BLE driver for "LEDDMX-00" / "LEDDMX-03" strip controllers.
 *
 * Protocol reverse-engineered by user154lt (GitHub: user154lt/LEDDMX-00).
 * Byte layouts match the verified Python implementation 1:1.
 */
class LedDmxController(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    }

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var connectedDeferred: CompletableDeferred<Boolean>? = null
    private var servicesDiscoveredDeferred: CompletableDeferred<Boolean>? = null
    private var pendingWriteDeferred: CompletableDeferred<Boolean>? = null

    private fun clamp(value: Int, lo: Int, hi: Int): Int = value.coerceIn(lo, hi)

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status newState=$newState")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(TAG, "Connected at GATT level, discovering services...")
                g.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.w(TAG, "Disconnected (status=$status)")
                connectedDeferred?.complete(false)
                servicesDiscoveredDeferred?.complete(false)
                pendingWriteDeferred?.complete(false)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val allServiceUuids = g.services.map { it.uuid.toString() }
                Log.d(TAG, "Services discovered: $allServiceUuids")
                val service = g.getService(SERVICE_UUID)
                if (service == null) {
                    Log.e(TAG, "Service $SERVICE_UUID NOT FOUND among discovered services")
                } else {
                    val allCharUuids = service.characteristics.map { it.uuid.toString() }
                    Log.d(TAG, "Characteristics on matched service: $allCharUuids")
                }
                writeCharacteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                if (writeCharacteristic == null) {
                    Log.e(TAG, "Characteristic $CHARACTERISTIC_UUID NOT FOUND")
                } else {
                    Log.d(TAG, "Write characteristic resolved successfully")
                }
                servicesDiscoveredDeferred?.complete(writeCharacteristic != null)
            } else {
                Log.e(TAG, "onServicesDiscovered failed with status=$status")
                servicesDiscoveredDeferred?.complete(false)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            Log.d(TAG, "onCharacteristicWrite: status=$status")
            pendingWriteDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    /**
     * Scans for a device whose advertised name contains [nameFragment], then connects.
     * Retries the whole scan-connect-discover sequence up to [retries] times, since
     * BLE stacks (even Android's, though far less than Windows') can be flaky on the
     * first attempt.
     */
    private sealed class ScanOutcome {
        data class Found(val device: BluetoothDevice) : ScanOutcome()
        object NotFound : ScanOutcome()
        data class Failed(val errorCode: Int) : ScanOutcome()
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(
        nameFragment: String,
        retries: Int = 3,
        scanTimeoutMs: Long = 8000,
        connectTimeoutMs: Long = 8000,
    ): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            Log.e(TAG, "No Bluetooth adapter on this device")
            return false
        }
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth adapter is not enabled")
            return false
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "bluetoothLeScanner is null (Bluetooth may be off or unsupported)")
            return false
        }

        repeat(retries) { attempt ->
            Log.d(TAG, "Connect attempt ${attempt + 1}/$retries: scanning for '$nameFragment'...")
            val outcome = withTimeoutOrNull(scanTimeoutMs) {
                scanForDevice(scanner, nameFragment)
            } ?: ScanOutcome.NotFound

            val device = when (outcome) {
                is ScanOutcome.Found -> outcome.device
                is ScanOutcome.Failed -> {
                    // errorCode 2 (APPLICATION_REGISTRATION_FAILED) is commonly
                    // Android's anti-spam scan throttle (more than ~5 scan starts
                    // in a 30s window). Retrying immediately just fails again, so
                    // back off long enough to clear the throttle window instead.
                    if (outcome.errorCode == 2) {
                        Log.w(TAG, "Scan throttled by Android (errorCode=2). Backing off 6s before retrying...")
                        delay(6000)
                    } else {
                        Log.w(TAG, "Scan failed (errorCode=${outcome.errorCode}). Backing off 1.5s...")
                        delay(1500)
                    }
                    null
                }
                ScanOutcome.NotFound -> {
                    Log.w(TAG, "Attempt ${attempt + 1}/$retries: device not found within ${scanTimeoutMs}ms")
                    delay(1000)
                    null
                }
            }

            if (device == null) {
                return@repeat
            }

            Log.d(TAG, "Attempt ${attempt + 1}/$retries: found device ${device.name} (${device.address}), connecting...")
            connectedDeferred = CompletableDeferred()
            servicesDiscoveredDeferred = CompletableDeferred()
            gatt = device.connectGatt(context, false, gattCallback)

            val discovered = withTimeoutOrNull(connectTimeoutMs) {
                servicesDiscoveredDeferred?.await()
            } ?: false


            if (discovered) {
                Log.d(TAG, "Attempt ${attempt + 1}/$retries: connected and ready.")
                return true
            } else {
                Log.w(TAG, "Attempt ${attempt + 1}/$retries: connect/discovery failed or timed out, retrying...")
                disconnect()
                delay(1000)
            }
        }
        Log.e(TAG, "All $retries connect attempts failed for '$nameFragment'")
        return false
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanForDevice(
        scanner: android.bluetooth.le.BluetoothLeScanner,
        nameFragment: String,
    ): ScanOutcome {
        val deferred = CompletableDeferred<ScanOutcome>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    val name = result.device.name
                    if (name != null) {
                        Log.v(TAG, "Scan saw device: $name (${result.device.address})")
                    }
                    if (name != null && name.contains(nameFragment, ignoreCase = true)) {
                        if (!deferred.isCompleted) deferred.complete(ScanOutcome.Found(result.device))
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Missing permission while reading scan result", e)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val reason = when (errorCode) {
                    1 -> "SCAN_FAILED_ALREADY_STARTED"
                    2 -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED (likely Android's scan throttle - too many scan starts recently)"
                    3 -> "SCAN_FAILED_INTERNAL_ERROR"
                    4 -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
                    5 -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
                    6 -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY"
                    else -> "unknown"
                }
                Log.e(TAG, "BLE scan failed with errorCode=$errorCode ($reason)")
                if (!deferred.isCompleted) deferred.complete(ScanOutcome.Failed(errorCode))
            }
        }

        // Try to clear any stale scan session before starting a fresh one.
        try {
            scanner.stopScan(callback)
        } catch (e: Exception) {
            Log.v(TAG, "Pre-emptive stopScan threw (expected if nothing was running): $e")
        }

        scanner.startScan(callback)
        val outcome = try {
            deferred.await()
        } finally {
            scanner.stopScan(callback)
        }
        return outcome
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeCharacteristic = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun write(payload: ByteArray, ackTimeoutMs: Long = 2000): Boolean {
        val characteristic = writeCharacteristic ?: return false
        val g = gatt ?: return false

        pendingWriteDeferred = CompletableDeferred()
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = payload

        val queued = g.writeCharacteristic(characteristic)
        if (!queued) {
            pendingWriteDeferred = null
            return false
        }

        // Wait for onCharacteristicWrite to confirm this specific write completed
        // before letting the caller queue the next one. Without this, Android's
        // BLE stack can silently drop subsequent writes fired before the previous
        // one is acknowledged. Some cheap peripherals are also slow/inconsistent
        // about firing this callback even with WRITE_TYPE_NO_RESPONSE, so we fall
        // back to "assume success" after a timeout rather than blocking forever.
        val acked = withTimeoutOrNull(ackTimeoutMs) { pendingWriteDeferred?.await() }
        pendingWriteDeferred = null

        // Small settle delay: several of these controllers need a brief gap
        // between commands even after acknowledging, or the next write gets
        // ignored at the firmware level.
        delay(60)

        return acked ?: true
    }

    // ---------- commands (byte layouts from user154lt/LEDDMX-00) ----------

    suspend fun setPower(on: Boolean): Boolean {
        val payload = byteArrayOf(
            0x7B.toByte(), 0xFF.toByte(), 0x04.toByte(),
            if (on) 0x03.toByte() else 0x02.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xBF.toByte(),
        )
        return write(payload)
    }

    suspend fun setColor(r: Int, g: Int, b: Int): Boolean {
        val red = clamp(r, 0, 255).toByte()
        val green = clamp(g, 0, 255).toByte()
        val blue = clamp(b, 0, 255).toByte()
        val payload = byteArrayOf(
            0x7B.toByte(), 0xFF.toByte(), 0x07.toByte(),
            red, green, blue,
            0x00.toByte(), 0xFF.toByte(), 0xBF.toByte(),
        )
        return write(payload)
    }

    suspend fun setBrightness(percent: Int): Boolean {
        val pct = clamp(percent, 0, 100)
        val adjusted = (pct * 32) / 100
        val payload = byteArrayOf(
            0x7B.toByte(), 0xFF.toByte(), 0x01.toByte(),
            adjusted.toByte(), pct.toByte(),
            0x00.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xBF.toByte(),
        )
        return write(payload)
    }

    suspend fun setPattern(patternIndex: Int): Boolean {
        val idx = clamp(patternIndex, 0, 210)
        val payload = byteArrayOf(
            0x7B.toByte(), 0xFF.toByte(), 0x03.toByte(),
            idx.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xBF.toByte(),
        )
        return write(payload)
    }

    suspend fun setMicEq(eqMode: Int): Boolean {
        val mode = clamp(eqMode, 0, 255)
        val payload = byteArrayOf(
            0x7B.toByte(), 0xFF.toByte(), 0x0B.toByte(),
            mode.toByte(),
            0x00.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xBF.toByte(),
        )
        return write(payload)
    }
}
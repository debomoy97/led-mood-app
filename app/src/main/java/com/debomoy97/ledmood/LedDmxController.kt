package com.debomoy97.ledmood

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

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

    private fun clamp(value: Int, lo: Int, hi: Int): Int = value.coerceIn(lo, hi)

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                connectedDeferred?.complete(false)
                servicesDiscoveredDeferred?.complete(false)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = g.getService(SERVICE_UUID)
                writeCharacteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                servicesDiscoveredDeferred?.complete(writeCharacteristic != null)
            } else {
                servicesDiscoveredDeferred?.complete(false)
            }
        }
    }

    /**
     * Scans for a device whose advertised name contains [nameFragment], then connects.
     * Retries the whole scan-connect-discover sequence up to [retries] times, since
     * BLE stacks (even Android's, though far less than Windows') can be flaky on the
     * first attempt.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(
        nameFragment: String,
        retries: Int = 3,
        scanTimeoutMs: Long = 8000,
        connectTimeoutMs: Long = 8000,
    ): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return false
        val scanner = adapter.bluetoothLeScanner ?: return false

        repeat(retries) { attempt ->
            val device = withTimeoutOrNull(scanTimeoutMs) {
                scanForDevice(scanner, nameFragment)
            }

            if (device == null) {
                delay(1000)
                return@repeat
            }

            connectedDeferred = CompletableDeferred()
            servicesDiscoveredDeferred = CompletableDeferred()
            gatt = device.connectGatt(context, false, gattCallback)

            val discovered = withTimeoutOrNull(connectTimeoutMs) {
                servicesDiscoveredDeferred?.await()
            } ?: false

            if (discovered) {
                return true
            } else {
                disconnect()
                delay(1000)
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanForDevice(
        scanner: android.bluetooth.le.BluetoothLeScanner,
        nameFragment: String,
    ): BluetoothDevice? {
        val deferred = CompletableDeferred<BluetoothDevice?>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (name.contains(nameFragment, ignoreCase = true)) {
                    if (!deferred.isCompleted) deferred.complete(result.device)
                }
            }
        }
        scanner.startScan(callback)
        val device = deferred.await()
        scanner.stopScan(callback)
        return device
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeCharacteristic = null
    }

    @SuppressLint("MissingPermission")
    private fun write(payload: ByteArray): Boolean {
        val characteristic = writeCharacteristic ?: return false
        val g = gatt ?: return false
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = payload
        return g.writeCharacteristic(characteristic)
    }

    // ---------- commands (byte layouts from user154lt/LEDDMX-00) ----------

    fun setPower(on: Boolean): Boolean {
        val payload = byteArrayOf(
            0x7B.toByte(), 0xFF.toByte(), 0x04.toByte(),
            if (on) 0x03.toByte() else 0x02.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xBF.toByte(),
        )
        return write(payload)
    }

    fun setColor(r: Int, g: Int, b: Int): Boolean {
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

    fun setBrightness(percent: Int): Boolean {
        val pct = clamp(percent, 0, 100)
        val adjusted = (pct * 32) / 100
        val payload = byteArrayOf(
            0x7B.toByte(), 0xFF.toByte(), 0x01.toByte(),
            adjusted.toByte(), pct.toByte(),
            0x00.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xBF.toByte(),
        )
        return write(payload)
    }

    fun setPattern(patternIndex: Int): Boolean {
        val idx = clamp(patternIndex, 0, 210)
        val payload = byteArrayOf(
            0x7B.toByte(), 0xFF.toByte(), 0x03.toByte(),
            idx.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xBF.toByte(),
        )
        return write(payload)
    }

    fun setMicEq(eqMode: Int): Boolean {
        val mode = clamp(eqMode, 0, 255)
        val payload = byteArrayOf(
            0x7B.toByte(), 0xFF.toByte(), 0x0B.toByte(),
            mode.toByte(),
            0x00.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xBF.toByte(),
        )
        return write(payload)
    }
}

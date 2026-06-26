package com.debomoy97.ledmood

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var deviceNameInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var moodInput: EditText
    private lateinit var setMoodButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var speedTestPatternInput: EditText
    private lateinit var speedTestByteSlotInput: EditText
    private lateinit var speedTestValueInput: EditText
    private lateinit var speedTestButton: Button

    private lateinit var ledController: LedDmxController

    // Tracks which action to (re)run once permissions are granted, since both
    // the main mood flow and the speed test need the same Bluetooth permissions.
    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            pendingAction?.invoke()
        } else {
            setStatus("Bluetooth permission is required to control the strip.")
        }
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceNameInput = findViewById(R.id.deviceNameInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        moodInput = findViewById(R.id.moodInput)
        setMoodButton = findViewById(R.id.setMoodButton)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)

        speedTestPatternInput = findViewById(R.id.speedTestPatternInput)
        speedTestByteSlotInput = findViewById(R.id.speedTestByteSlotInput)
        speedTestValueInput = findViewById(R.id.speedTestValueInput)
        speedTestButton = findViewById(R.id.speedTestButton)

        ledController = LedDmxController(applicationContext)

        // Prefill with the device name we already confirmed via nRF Connect,
        // for convenience. Editable.
        deviceNameInput.setText("LEDDMX-03-6DAF")

        setMoodButton.setOnClickListener {
            if (hasRequiredPermissions()) {
                runSetMood()
            } else {
                pendingAction = { runSetMood() }
                requestRequiredPermissions()
            }
        }

        speedTestButton.setOnClickListener {
            if (hasRequiredPermissions()) {
                runSpeedTest()
            } else {
                pendingAction = { runSpeedTest() }
                requestRequiredPermissions()
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = requiredPermissions()
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }
    }

    private fun requestRequiredPermissions() {
        permissionLauncher.launch(requiredPermissions())
    }

    private fun setStatus(message: String) {
        statusText.text = message
    }

    private fun setBusy(busy: Boolean) {
        progressBar.visibility = if (busy) android.view.View.VISIBLE else android.view.View.INVISIBLE
        setMoodButton.isEnabled = !busy
        speedTestButton.isEnabled = !busy
    }

    private fun runSetMood() {
        val deviceName = deviceNameInput.text.toString().trim()
        val apiKey = apiKeyInput.text.toString().trim()
        val mood = moodInput.text.toString().trim()

        if (deviceName.isEmpty()) {
            setStatus("Enter the strip's Bluetooth name first.")
            return
        }
        if (apiKey.isEmpty()) {
            setStatus("Enter your OpenAI API key first.")
            return
        }
        if (mood.isEmpty()) {
            setStatus("Describe a mood or scene first.")
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Please turn on Bluetooth first.")
            return
        }

        setBusy(true)
        setStatus("Asking the AI to interpret your mood...")

        lifecycleScope.launch {
            try {
                val command = MoodInterpreter.interpret(apiKey, mood)
                setStatus("Got it: $command\nConnecting to $deviceName...")

                val connected = ledController.connect(deviceName)
                if (!connected) {
                    setStatus("Could not connect to $deviceName. Make sure it's powered on and the LED LAMP app is closed.")
                    setBusy(false)
                    return@launch
                }

                setStatus("Connected. Applying command...")

                command.power?.let { ledController.setPower(it) }
                command.color?.let { (r, g, b) -> ledController.setColor(r, g, b) }
                command.brightness?.let { ledController.setBrightness(it) }
                command.pattern?.let { ledController.setPattern(it) }
                command.micEq?.let { ledController.setMicEq(it) }

                if (command.customColors != null && command.customMode != null) {
                    ledController.applyCustomPattern(
                        colors = command.customColors,
                        mode = command.customMode,
                        forward = command.customForward ?: true,
                    )
                }

                setStatus("Done! $command")
            } catch (e: Exception) {
                setStatus("Error: ${e.message}")
            } finally {
                ledController.disconnect()
                setBusy(false)
            }
        }
    }

    private fun runSpeedTest() {
        val deviceName = deviceNameInput.text.toString().trim()
        val patternStr = speedTestPatternInput.text.toString().trim()
        val byteSlotStr = speedTestByteSlotInput.text.toString().trim()
        val speedStr = speedTestValueInput.text.toString().trim()

        if (deviceName.isEmpty()) {
            setStatus("Enter the strip's Bluetooth name first.")
            return
        }
        val patternIndex = patternStr.toIntOrNull()
        if (patternIndex == null || patternIndex !in 1..210) {
            setStatus("Enter a valid pattern index (1-210) to test speed against.")
            return
        }
        val byteSlot = byteSlotStr.toIntOrNull()
        if (byteSlot == null || byteSlot !in 4..7) {
            setStatus("Byte slot must be 4, 5, 6, or 7.")
            return
        }
        val speedGuess = speedStr.toIntOrNull()
        if (speedGuess == null || speedGuess !in 0..255) {
            setStatus("Speed guess must be 0-255.")
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Please turn on Bluetooth first.")
            return
        }

        setBusy(true)
        setStatus("Connecting to $deviceName for speed test...")

        lifecycleScope.launch {
            try {
                val connected = ledController.connect(deviceName)
                if (!connected) {
                    setStatus("Could not connect to $deviceName.")
                    setBusy(false)
                    return@launch
                }

                setStatus(
                    "Connected. Sending pattern=$patternIndex with experimental " +
                        "byte slot $byteSlot = $speedGuess. Watch the strip now and " +
                        "compare against just setting the pattern normally."
                )

                ledController.experimentalSetPatternSpeed(
                    patternIndex = patternIndex,
                    speedGuess = speedGuess,
                    byteSlot = byteSlot,
                )

                setStatus(
                    "Sent. Pattern=$patternIndex, slot=$byteSlot, value=$speedGuess. " +
                        "Did the animation speed change compared to normal? Try other " +
                        "slot/value combinations to compare."
                )
            } catch (e: Exception) {
                setStatus("Error: ${e.message}")
            } finally {
                ledController.disconnect()
                setBusy(false)
            }
        }
    }
}
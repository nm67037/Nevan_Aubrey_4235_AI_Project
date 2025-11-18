package com.example.myapplication // <-- IMPORTANT: Make sure this package name matches your project's!

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ContentValues // ⭐️ ADDED IMPORT
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment // ⭐️ ADDED IMPORT
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore // ⭐️ ADDED IMPORT
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import android.widget.Toast.LENGTH_SHORT
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat // ⭐️ ADDED IMPORT
import java.util.Date // ⭐️ ADDED IMPORT
import java.util.Locale // ⭐️ ADDED IMPORT
import java.util.UUID

// Define a message type for our Handler
private const val MESSAGE_READ: Int = 0

// Data class for our timeseries data
data class RpmDataPoint(val timestampMs: Long, val rpm: Int)

@SuppressLint("MissingPermission") // We are checking permissions, so this is safe
class MainActivity : AppCompatActivity() {

    // --- Class Variables ---
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val discoveredDevices = ArrayList<BluetoothDevice>()
    private lateinit var deviceListAdapter: ArrayAdapter<String>

    // --- Bluetooth Connection Variables ---
    private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var connectedSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var readDataThread: Thread? = null
    private var isClockwise = true

    // --- State Variables ---
    private var isMotorStopped = true // Default to "stopped"
    private var isAutoMode = false // Default to Manual Mode

    // --- Variables for Data Logging ---
    private val rpmDataLog = ArrayList<RpmDataPoint>()
    private var loggingStartTime: Long = 0L // 0 means "not logging"


    // --- UI Elements ---
    private lateinit var rpmTextView: TextView
    private lateinit var startButton: Button
    private lateinit var slowerButton: Button
    private lateinit var fasterButton: Button
    private lateinit var stopButton: Button
    private lateinit var toggleDirectionButton: Button
    private lateinit var manualModeButton: Button
    private lateinit var autoModeButton: Button
    private lateinit var autoRpmLayout: LinearLayout
    private lateinit var rpmEditText: EditText
    private lateinit var sendRpmButton: Button


    // --- Handler for UI Updates from background thread ---
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_READ -> {
                    val readMessage = msg.obj as String
                    if (readMessage.startsWith("RPM:")) {
                        rpmTextView.text = readMessage.trim() // Update the TextView

                        // --- Data logging logic ---
                        if (loggingStartTime > 0L) {
                            try {
                                val rpmValue = readMessage.removePrefix("RPM:").trim().toInt()
                                val currentTime = System.currentTimeMillis()
                                val relativeTime = currentTime - loggingStartTime
                                rpmDataLog.add(RpmDataPoint(relativeTime, rpmValue))
                            } catch (e: NumberFormatException) {
                                Log.e("RPM_LOG", "Failed to parse RPM: $readMessage")
                            }
                        }
                    }
                }
            }
        }
    }


    // --- Activity Lifecycle ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Get the UI elements
        val scanButton: Button = findViewById(R.id.scanButton)
        startButton = findViewById(R.id.startButton)
        slowerButton = findViewById(R.id.slowerButton)
        fasterButton = findViewById(R.id.fasterButton)
        stopButton = findViewById(R.id.stopButton)
        toggleDirectionButton = findViewById(R.id.toggleDirectionButton)
        manualModeButton = findViewById(R.id.manualModeButton)
        autoModeButton = findViewById(R.id.autoModeButton)
        autoRpmLayout = findViewById(R.id.autoRpmLayout)
        rpmEditText = findViewById(R.id.rpmEditText)
        sendRpmButton = findViewById(R.id.sendRpmButton)

        val devicesListView: ListView = findViewById(R.id.devicesListView)
        rpmTextView = findViewById(R.id.rpmTextView)

        // 1. Initialize the list adapter
        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = deviceListAdapter

        // 2. Get the Bluetooth Adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", LENGTH_LONG).show()
            finish()
            return
        }

        // 3. Request Bluetooth permissions
        checkBluetoothPermissions() // This function already handles the BT permissions

        // 4. Set "Scan" button click
        scanButton.setOnClickListener {
            startBluetoothScan()
        }

        // --- UPDATED: Mode Button Click Listeners ---
        manualModeButton.setOnClickListener {
            sendBluetoothCommand("m") // 'm' for Manual
            isAutoMode = false
            updateUIForMode(isManualMode = true)
            Toast.makeText(this, "Manual Mode Activated", LENGTH_SHORT).show()
        }

        autoModeButton.setOnClickListener {
            sendBluetoothCommand("a") // 'a' for Automatic
            isAutoMode = true
            updateUIForMode(isManualMode = false)
            Toast.makeText(this, "Automatic Mode Activated", LENGTH_SHORT).show()
        }

        // --- NEW: Click Listener for Send RPM Button ---
        sendRpmButton.setOnClickListener {
            val rpmValue = rpmEditText.text.toString()
            if (rpmValue.isNotEmpty()) {
                val command = "r:$rpmValue\n"
                sendBluetoothCommand(command)
                Toast.makeText(this, "Sent RPM: $rpmValue", LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter an RPM value", LENGTH_SHORT).show()
            }
        }


        // --- Manual Control Button Click Listeners ---

        // --- UPDATED: Start Button ---
        startButton.setOnClickListener {
            // --- NEW: Start logging ---
            rpmDataLog.clear() // Clear any previous log
            loggingStartTime = System.currentTimeMillis() // Set time=0

            // Send a sequence: Power ON, Direction CW, Speed 30%
            sendBluetoothCommand("s") // 's' for Start Master Power
            sendBluetoothCommand("c") // 'c' for Clockwise (default direction)
            sendBluetoothCommand("f") // 'f' for Faster (10%)
            sendBluetoothCommand("f") // 'f' for Faster (20%)
            sendBluetoothCommand("f") // 'f' for Faster (30%)

            isMotorStopped = false // Motor is now running
            isClockwise = true     // Reset direction to CW on every start

            Toast.makeText(this, "Start (s, c, f, f, f) sent", LENGTH_SHORT).show()
        }

        slowerButton.setOnClickListener {
            if (isMotorStopped) {
                Toast.makeText(this, "Press START to begin", LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sendBluetoothCommand("d") // 'd' for Slower
            Toast.makeText(this, "Slower (d) sent", LENGTH_SHORT).show()
        }

        fasterButton.setOnClickListener {
            if (isMotorStopped) {
                Toast.makeText(this, "Press START to begin", LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sendBluetoothCommand("f") // 'f' for Faster
            Toast.makeText(this, "Faster (f) sent", LENGTH_SHORT).show()
        }

        // --- UPDATED: Stop Button ---
        stopButton.setOnClickListener {
            sendBluetoothCommand("x") // 'x' for Full Stop
            isMotorStopped = true // Motor is now stopped
            Toast.makeText(this, "Stop (x) sent", LENGTH_SHORT).show()

            // --- UPDATED: Stop logging and save data ---
            loggingStartTime = 0L // Stop logging
            saveDataToFile() // Save the results
        }

        toggleDirectionButton.setOnClickListener {
            if (isMotorStopped) {
                Toast.makeText(this, "Press START to begin", LENGTH_SHORT).show()
                return@setOnClickListener
            }

            isClockwise = !isClockwise
            val command: String
            val direction: String

            if (isClockwise) {
                command = "c" // 'c' for Clockwise
                direction = "Clockwise"
            } else {
                command = "v" // 'v' for Counter-Clockwise
                direction = "Counter-Clockwise"
            }
            sendBluetoothCommand(command)
            Toast.makeText(this, "Direction: $direction ($command) sent", LENGTH_SHORT).show()
        }

        // 5. Set device list click listener (CONNECT)
        devicesListView.setOnItemClickListener { _, _, position, _ ->
            val selectedDevice = discoveredDevices[position]
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
            connectToDevice(selectedDevice)
        }

        // 6. Register discovery receiver
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(discoveryReceiver, filter)

        // --- UPDATED: Set initial button state ---
        updateUIForMode(isManualMode = true) // Start with manual UI
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(discoveryReceiver)
        readDataThread?.interrupt()
        try {
            outputStream?.close()
            inputStream?.close()
            connectedSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // --- Permission Handling ---
    // (This section is unchanged)
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                Toast.makeText(this, "Permissions Granted!", LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Bluetooth and Location permissions are required", LENGTH_LONG).show()
            }
        }

    private fun checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                )
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                )
            }
        }
    }

    // --- Bluetooth Actions ---
    // (This section is unchanged)
    private fun startBluetoothScan() {
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestBluetoothEnableLauncher.launch(enableBtIntent)
        } else {
            Toast.makeText(this, "Scanning...", LENGTH_SHORT).show()
            deviceListAdapter.clear()
            discoveredDevices.clear()

            val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }

            if (hasScanPermission) {
                bluetoothAdapter?.startDiscovery()
            } else {
                Toast.makeText(this, "Scan permission missing", LENGTH_SHORT).show()
            }
        }
    }

    private val requestBluetoothEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startBluetoothScan()
            } else {
                Toast.makeText(this, "Bluetooth must be enabled to scan", LENGTH_SHORT).show()
            }
        }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (device != null && device.name != null) {

                    val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    } else {
                        true
                    }

                    if (hasConnectPermission) {
                        if (!discoveredDevices.contains(device)) {
                            discoveredDevices.add(device)
                            deviceListAdapter.add(device.name + "\n" + device.address)
                        }
                    }
                }
            }
        }
    }

    // --- UPDATED AND NEW FUNCTIONS ---

    // --- This function now controls the UI for both modes ---
    private fun updateUIForMode(isManualMode: Boolean) {
        if (isManualMode) {
            // Manual Mode: Enable speed buttons, HIDE auto layout
            slowerButton.isEnabled = true
            fasterButton.isEnabled = true
            autoRpmLayout.visibility = View.GONE
        } else {
            // Auto Mode: Disable speed buttons, SHOW auto layout
            slowerButton.isEnabled = false
            fasterButton.isEnabled = false
            autoRpmLayout.visibility = View.VISIBLE
        }
    }

    // --- ⭐️ NEW: Replaced logDataToConsole() with this function ⭐️ ---
    private fun saveDataToFile() {
        if (rpmDataLog.isEmpty()) {
            Toast.makeText(this, "No data to save", LENGTH_SHORT).show()
            return
        }

        // 1. Create the content for the file
        val stringBuilder = StringBuilder()
        stringBuilder.append("time(ms),RPM\n") // Add a CSV header
        rpmDataLog.forEach { dataPoint ->
            stringBuilder.append("${dataPoint.timestampMs},${dataPoint.rpm}\n")
        }
        val fileContent = stringBuilder.toString()

        // 2. Create a unique filename
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "rpm_log_$timeStamp.txt"

        // 3. Save the file using MediaStore
        // This is the modern, safe way to save to the Downloads folder
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }

        var uri: android.net.Uri? = null
        try {
            uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri == null) {
                throw IOException("Failed to create new MediaStore entry")
            }

            contentResolver.openOutputStream(uri).use { outputStream ->
                if (outputStream == null) {
                    throw IOException("Failed to open output stream")
                }
                outputStream.write(fileContent.toByteArray())
            }

            runOnUiThread {
                Toast.makeText(this, "Log saved to Downloads folder!", LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("RPM_SAVE", "Error saving file", e)
            runOnUiThread {
                Toast.makeText(this, "Error saving file: ${e.message}", LENGTH_LONG).show()
            }
            // If something went wrong, try to delete the partial file
            if (uri != null) {
                contentResolver.delete(uri, null, null)
            }
        }
    }

    private fun sendBluetoothCommand(command: String) {
        // (This function is unchanged)
        if (outputStream == null) {
            Toast.makeText(this, "Not connected to a device", LENGTH_SHORT).show()
            return
        }

        val commandToSend = command
        Thread {
            try {
                outputStream?.write(commandToSend.toByteArray())
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Failed to send command: ${e.message}", LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun connectToDevice(device: BluetoothDevice) {
        // (This function is unchanged)
        Thread {
            var socket: BluetoothSocket? = null
            try {
                socket = device.javaClass
                    .getMethod("createRfcommSocket", Int::class.java)
                    .invoke(device, 22) as BluetoothSocket

                socket.connect() // Blocking call

                connectedSocket = socket
                outputStream = socket.outputStream
                inputStream = socket.inputStream

                readDataThread = Thread(this::readDataFromSocket)
                readDataThread?.start()

                runOnUiThread {
                    Toast.makeText(this, "Successfully connected to ${device.name}", LENGTH_LONG).show()
                    isMotorStopped = true
                    isAutoMode = false
                    updateUIForMode(isManualMode = true) // Default to manual mode
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Connection failed: ${e.message}", LENGTH_LONG).show()
                }
                try {
                    socket?.close()
                } catch (closeException: IOException) {
                    closeException.printStackTrace()
                }
            }
        }.start()
    }

    private fun readDataFromSocket() {
        // (This function is unchanged)
        val reader = BufferedReader(InputStreamReader(inputStream!!))
        while (true) {
            try {
                val line = reader.readLine()
                if (line != null) {
                    handler.obtainMessage(MESSAGE_READ, line).sendToTarget()
                } else {
                    break
                }
            } catch (e: IOException) {
                break
            }
        }
    }
}
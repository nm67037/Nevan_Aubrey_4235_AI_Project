package com.example.myapplication // <-- IMPORTANT: Make sure this package name matches your project's!

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.widget.ArrayAdapter
import android.widget.Button
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
import java.util.UUID

// --- NEW ---
// Define a message type for our Handler
private const val MESSAGE_READ: Int = 0

@SuppressLint("MissingPermission") // We are checking permissions, so this is safe
class MainActivity : AppCompatActivity() {

    // --- Class Variables ---
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val discoveredDevices = ArrayList<BluetoothDevice>()
    private lateinit var deviceListAdapter: ArrayAdapter<String>

    // --- Bluetooth Connection Variables ---
    // We keep the UUID just as a fallback, but we'll try Channel 22 first
    private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var connectedSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null // <-- NEW: For reading data
    private var readDataThread: Thread? = null // <-- NEW: Thread to listen for data
    private var isClockwise = true

    // --- NEW: UI Elements ---
    private lateinit var rpmTextView: TextView // <-- NEW

    // --- NEW: Handler for UI Updates from background thread ---
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_READ -> {
                    val readMessage = msg.obj as String
                    if (readMessage.startsWith("RPM:")) {
                        rpmTextView.text = readMessage.trim() // Update the TextView
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
        val startButton: Button = findViewById(R.id.startButton)
        val slowerButton: Button = findViewById(R.id.slowerButton)
        val fasterButton: Button = findViewById(R.id.fasterButton)
        val stopButton: Button = findViewById(R.id.stopButton)
        val toggleDirectionButton: Button = findViewById(R.id.toggleDirectionButton)
        val devicesListView: ListView = findViewById(R.id.devicesListView)
        rpmTextView = findViewById(R.id.rpmTextView) // <-- NEW

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
        checkBluetoothPermissions()

        // 4. Set "Scan" button click
        scanButton.setOnClickListener {
            startBluetoothScan()
        }

        // --- UPDATED Button Click Listeners ---
        // (Fixed duplicate slower/faster listeners)
        startButton.setOnClickListener {
            sendBluetoothCommand("s") // 's' for Start Master Power
            Toast.makeText(this, "Start (s) sent", LENGTH_SHORT).show()
        }

        slowerButton.setOnClickListener {
            sendBluetoothCommand("d") // 'd' for Slower
            Toast.makeText(this, "Slower (d) sent", LENGTH_SHORT).show()
        }

        fasterButton.setOnClickListener {
            sendBluetoothCommand("f") // 'f' for Faster
            Toast.makeText(this, "Faster (f) sent", LENGTH_SHORT).show()
        }

        stopButton.setOnClickListener {
            sendBluetoothCommand("f") // 'f' for Faster
            Toast.makeText(this, "Stop (x) sent", LENGTH_SHORT).show()
        }

        toggleDirectionButton.setOnClickListener {
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
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(discoveryReceiver)
        // --- NEW ---
        // Stop the reading thread and close sockets
        readDataThread?.interrupt() // Stop the listener thread
        try {
            outputStream?.close()
            inputStream?.close()
            connectedSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // --- Permission Handling (Unchanged) ---
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
                permissions[Manifest.permission.BLUETOOTH_CONNECT] == true) {
                Toast.makeText(this, "Permissions Granted!", LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Bluetooth permissions are required", LENGTH_LONG).show()
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
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                101
            )
        }
    }

    // --- Bluetooth Actions (startBluetoothScan & launchers are Unchanged) ---
    private fun startBluetoothScan() {
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestBluetoothEnableLauncher.launch(enableBtIntent)
        } else {
            Toast.makeText(this, "Scanning...", LENGTH_SHORT).show()
            deviceListAdapter.clear()
            discoveredDevices.clear()
            bluetoothAdapter?.startDiscovery()
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
                    if (!discoveredDevices.contains(device)) {
                        discoveredDevices.add(device)
                        deviceListAdapter.add(device.name + "\n" + device.address)
                    }
                }
            }
        }
    }

    // --- UPDATED AND NEW FUNCTIONS ---

    /**
     * This function sends the command string over Bluetooth.
     * It runs on a background thread to avoid blocking the UI.
     *
     * --- EDITED ---
     * Removed the "\n" as the C server processes one char at a time.
     */
    private fun sendBluetoothCommand(command: String) {
        if (outputStream == null) {
            Toast.makeText(this, "Not connected to a device", LENGTH_SHORT).show()
            return
        }

        // --- EDITED ---
        // Send the raw command string. The C program is reading char by char.
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

    /**
     * This function connects to the selected device.
     * --- EDITED ---
     * It now connects directly to RFCOMM Channel 22.
     * It also starts the thread to read incoming RPM data.
     */
    private fun connectToDevice(device: BluetoothDevice) {
        Thread {
            var socket: BluetoothSocket? = null
            try {
                // --- THIS IS THE CRUCIAL CHANGE ---
                // We are connecting directly to Channel 22
                // This uses a "hidden" method, so we use reflection
                socket = device.javaClass
                    .getMethod("createRfcommSocket", Int::class.java)
                    .invoke(device, 22) as BluetoothSocket

                socket.connect() // Blocking call

                // If successful, store streams and start listener thread
                connectedSocket = socket
                outputStream = socket.outputStream
                inputStream = socket.inputStream

                // --- NEW ---
                // Start the thread to listen for RPM data
                readDataThread = Thread(this::readDataFromSocket)
                readDataThread?.start()

                runOnUiThread {
                    Toast.makeText(this, "Successfully connected to ${device.name}", LENGTH_LONG).show()
                }

            } catch (e: Exception) { // Catch general Exception for reflection
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Connection failed: ${e.message}", LENGTH_SHORT).show()
                }
                try {
                    socket?.close()
                } catch (closeException: IOException) {
                    closeException.printStackTrace()
                }
            }
        }.start()
    }

    /**
     * --- NEW FUNCTION ---
     * This function runs in a separate thread and continuously
     * listens for incoming data (like "RPM:123") from the Pi.
     */
    private fun readDataFromSocket() {
        // We use a BufferedReader to read line by line, since the C server sends a '\n'
        val reader = BufferedReader(InputStreamReader(inputStream!!))

        while (true) {
            try {
                // Read a line of text (blocks until a newline '\n' is received)
                val line = reader.readLine()
                if (line != null) {
                    // Send the received line to the UI thread's Handler
                    handler.obtainMessage(MESSAGE_READ, line).sendToTarget()
                } else {
                    // line == null means the connection was closed
                    break
                }
            } catch (e: IOException) {
                // This happens when the socket is closed (e.g., in onDestroy or disconnect)
                break
            }
        }
    }
}
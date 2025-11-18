//Nevan comment: This is the final working main app code from Gemini for CP3.

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

// Define a message identifier for our UI Handler
// This tells the main thread "Hey, I have data to read"
private const val MESSAGE_READ: Int = 0

/*
 * @SuppressLint("MissingPermission")
 * We suppress the IDE warnings about missing permissions here because
 * we manually check for permissions in the `checkBluetoothPermissions()` function
 * before attempting any Bluetooth operations.
 */
@SuppressLint("MissingPermission") 
class MainActivity : AppCompatActivity() {

    // --- Class Variables ---
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val discoveredDevices = ArrayList<BluetoothDevice>()
    private lateinit var deviceListAdapter: ArrayAdapter<String>

    // --- Bluetooth Connection Variables ---
    // Standard SPP UUID (Not used in the reflection method, but good to have defined)
    private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var connectedSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var readDataThread: Thread? = null
    
    // State Tracking
    private var isClockwise = true
    private var isMotorStopped = true // Default to "stopped" prevents commands before start

    // --- UI Elements ---
    private lateinit var rpmTextView: TextView

    // --- HANDLER: The Bridge between Background Thread and UI ---
    // Bluetooth input happens on a background thread. You cannot touch UI (TextViews)
    // from a background thread. This Handler accepts messages from the background
    // and updates the UI on the Main Thread.
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_READ -> {
                    val readMessage = msg.obj as String
                    // Filter: Only update UI if the message looks like RPM data
                    if (readMessage.startsWith("RPM:")) {
                        rpmTextView.text = readMessage.trim() 
                    }
                }
            }
        }
    }


    // --- Activity Lifecycle: Where the App Starts ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Get references to all XML UI elements
        val scanButton: Button = findViewById(R.id.scanButton)
        val startButton: Button = findViewById(R.id.startButton)
        val slowerButton: Button = findViewById(R.id.slowerButton)
        val fasterButton: Button = findViewById(R.id.fasterButton)
        val stopButton: Button = findViewById(R.id.stopButton)
        val toggleDirectionButton: Button = findViewById(R.id.toggleDirectionButton)
        val devicesListView: ListView = findViewById(R.id.devicesListView)
        rpmTextView = findViewById(R.id.rpmTextView)

        // 1. Initialize the list adapter to hold device names
        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = deviceListAdapter

        // 2. Get the Bluetooth Hardware Adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", LENGTH_LONG).show()
            finish()
            return
        }

        // 3. Request Permissions (Critical for Android 12+)
        checkBluetoothPermissions()

        // 4. Set "Scan" button listener
        scanButton.setOnClickListener {
            startBluetoothScan()
        }

        // --- CONTROL BUTTON LISTENERS ---

        // START: Sends initialization sequence
        startButton.setOnClickListener {
            // Send a sequence: Power ON, Direction CW, Speed 30%
            // We send multiple 'f' commands to overcome static friction
            sendBluetoothCommand("s") // 's' for Start Master Power
            sendBluetoothCommand("c") // 'c' for Clockwise (default direction)
            sendBluetoothCommand("f") // 'f' for Faster (10%)
            sendBluetoothCommand("f") // 'f' for Faster (20%)
            sendBluetoothCommand("f") // 'f' for Faster (30%)

            // Update internal state
            isMotorStopped = false // Motor is now running
            isClockwise = true     // Reset direction to CW on every start

            Toast.makeText(this, "Start (s, c, f, f, f) sent", LENGTH_SHORT).show()
        }

        // SLOWER: Decrements speed
        slowerButton.setOnClickListener {
            // Safety Check: Don't send commands if the motor is off
            if (isMotorStopped) {
                Toast.makeText(this, "Press START to begin", LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sendBluetoothCommand("d") // 'd' for Slower
            Toast.makeText(this, "Slower (d) sent", LENGTH_SHORT).show()
        }

        // FASTER: Increments speed
        fasterButton.setOnClickListener {
            // Safety Check
            if (isMotorStopped) {
                Toast.makeText(this, "Press START to begin", LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sendBluetoothCommand("f") // 'f' for Faster
            Toast.makeText(this, "Faster (f) sent", LENGTH_SHORT).show()
        }

        // STOP: Kills power immediately
        stopButton.setOnClickListener {
            sendBluetoothCommand("x") // 'x' for Full Stop

            // Update state so other buttons know we are stopped
            isMotorStopped = true 

            Toast.makeText(this, "Stop (x) sent", LENGTH_SHORT).show()
        }

        // DIRECTION: Toggles between 'c' and 'v'
        toggleDirectionButton.setOnClickListener {
            // Safety Check
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

        // 5. List Click Listener: Triggers Connection
        devicesListView.setOnItemClickListener { _, _, position, _ ->
            val selectedDevice = discoveredDevices[position]
            // Stop scanning before connecting to save bandwidth
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
            connectToDevice(selectedDevice)
        }

        // 6. Register the BroadcastReceiver to listen for found devices
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(discoveryReceiver, filter)
    }

    // Cleanup when app closes
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

    // --- PERMISSION HANDLING ---
    // Android 12 (API 31) changed how Bluetooth permissions work.
    // We must check which version of Android the phone is running.
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
            // Android 12+: We need BLUETOOTH_SCAN and BLUETOOTH_CONNECT
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
            // Android 11 and below: We need ACCESS_FINE_LOCATION to scan for devices
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                )
            }
        }
    }

    // --- BLUETOOTH ACTIONS ---
    
    private fun startBluetoothScan() {
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestBluetoothEnableLauncher.launch(enableBtIntent)
        } else {
            Toast.makeText(this, "Scanning...", LENGTH_SHORT).show()
            deviceListAdapter.clear()
            discoveredDevices.clear()

            // Verify permission again before starting discovery
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

    // Receiver: Listens for the OS to say "I found a device!"
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (device != null && device.name != null) {

                    // Permission check for .name access
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

    // --- CONNECTION & DATA TRANSFER ---

    /*
     * sendBluetoothCommand
     * Converts the string (e.g., "f") to bytes and sends it via Output Stream.
     * Runs in a separate thread to avoid blocking the UI.
     */
    private fun sendBluetoothCommand(command: String) {
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

    /*
     * connectToDevice
     * Crucial Logic: This does NOT use the standard UUID connection method.
     * The Raspberry Pi C code creates a server on RFCOMM Channel 22.
     * Android's standard `createRfcommSocketToServiceRecord` does not allow specifying a channel.
     * Therefore, we use Reflection to force a connection specifically to Channel 22.
     */
    private fun connectToDevice(device: BluetoothDevice) {
        Thread {
            var socket: BluetoothSocket? = null
            try {
                // REFLECTION HACK: Force connection to Channel 22
                socket = device.javaClass
                    .getMethod("createRfcommSocket", Int::class.java)
                    .invoke(device, 22) as BluetoothSocket

                socket.connect() // This is a blocking call (waits for connection)

                // If we get here, we are connected!
                connectedSocket = socket
                outputStream = socket.outputStream
                inputStream = socket.inputStream

                // Start the background thread to listen for incoming RPM data
                readDataThread = Thread(this::readDataFromSocket)
                readDataThread?.start()

                runOnUiThread {
                    Toast.makeText(this, "Successfully connected to ${device.name}", LENGTH_LONG).show()
                    // Reset state to "Stopped" on new connection
                    isMotorStopped = true
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

    /*
     * readDataFromSocket
     * Runs in a background thread.
     * It constantly listens to the Input Stream. 
     * Because the C Server sends data ending in "\n", we can use readLine().
     */
    private fun readDataFromSocket() {
        val reader = BufferedReader(InputStreamReader(inputStream!!))

        while (true) {
            try {
                // Blocks here until data arrives
                val line = reader.readLine()
                if (line != null) {
                    // Send the data to the UI Handler to update the TextView
                    handler.obtainMessage(MESSAGE_READ, line).sendToTarget()
                } else {
                    // line == null means the socket was closed by the server
                    break
                }
            } catch (e: IOException) {
                // Connection lost or closed
                break
            }
        }
    }
}

package com.example.myapplication // <-- IMPORTANT: Make sure this package name matches your project's!

import android.Manifest
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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG // <-- ADD THIS LINE
import android.widget.Toast.LENGTH_SHORT // <-- ADD THIS LINE
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // --- Class Variables ---

    // Bluetooth adapter. This is the main "controller" for all Bluetooth things.
    private var bluetoothAdapter: BluetoothAdapter? = null

    // This will hold the devices we find so we can show them in the list
    private val discoveredDevices = ArrayList<BluetoothDevice>()
    private lateinit var deviceListAdapter: ArrayAdapter<String>

    // --- NEW Bluetooth Connection Variables ---

    // This is the "secret handshake" UUID for the Serial Port Profile (SPP)
    // Your RP4 script must be listening for this same UUID
    private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // This will be our "phone line" to the RP4
    private var connectedSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    // This will track the direction for your "toggle" button
    private var isClockwise = true


    // --- Activity Lifecycle ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // This connects our "brain" to our "face" (the XML layout)

        // Get the UI elements from the XML layout file
        val scanButton: Button = findViewById(R.id.scanButton)
        val startButton: Button = findViewById(R.id.startButton)
        val slowerButton: Button = findViewById(R.id.slowerButton)
        val fasterButton: Button = findViewById(R.id.fasterButton)
        val toggleDirectionButton: Button = findViewById(R.id.toggleDirectionButton)

        val devicesListView: ListView = findViewById(R.id.devicesListView)

        // 1. Initialize the list adapter
        // This adapter converts our list of device names into viewable list items
        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = deviceListAdapter

        // 2. Get the Bluetooth Adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", LENGTH_LONG).show() // <-- EDITED
            finish() // Close the app if Bluetooth isn't supported
            return
        }

        // 3. Request Bluetooth permissions at runtime
        checkBluetoothPermissions()

        // 4. Set what happens when the "Scan" button is clicked
        scanButton.setOnClickListener {
            startBluetoothScan()
        }

        // --- UPDATED Button Click Listeners ---

        startButton.setOnClickListener {
            // Send the 's' character, as required by the C program
            sendBluetoothCommand("s")
            Toast.makeText(this, "Start (s) sent", LENGTH_SHORT).show() // <-- EDITED
        }

        slowerButton.setOnClickListener {
            // Send the 'd' character
            sendBluetoothCommand("d")
            Toast.makeText(this, "Slower (d) sent", LENGTH_SHORT).show() // <-- EDITED
        }

        fasterButton.setOnClickListener {
            // Send the 'f' character
            sendBluetoothCommand("f")
            Toast.makeText(this, "Faster (f) sent", LENGTH_SHORT).show() // <-- EDITED
        }

        slowerButton.setOnClickListener {
            // Send the 'd' character
            sendBluetoothCommand("d")
            Toast.makeText(this, "Slower (d) sent", Toast.LENGTH_SHORT).show()
        }

        fasterButton.setOnClickListener {
            // Send the 'f' character
            sendBluetoothCommand("f")
            Toast.makeText(this, "Faster (f) sent", Toast.LENGTH_SHORT).show()
        }

        toggleDirectionButton.setOnClickListener {
            // This button will send 'c' or 'v' and flip the state
            isClockwise = !isClockwise // Flip the state
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
            Toast.makeText(this, "Direction: $direction ($command) sent", LENGTH_SHORT).show() // <-- EDITED
        }


        // 5. Set what happens when a device in the list is clicked (This is the CONNECT part)
        // --- THIS BLOCK IS UPDATED ---
        devicesListView.setOnItemClickListener { _, _, position, _ ->
            // Get the device the user clicked on
            val selectedDevice = discoveredDevices[position]

            // Stop scanning before you connect, as it's resource-intensive
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter?.cancelDiscovery()
            }

            // Start the connection process in a separate thread
            connectToDevice(selectedDevice)
        }

        // 6. Register a "receiver" to listen for when devices are found
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(discoveryReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Un-register the receiver to avoid memory leaks when the app closes
        unregisterReceiver(discoveryReceiver)

        // --- NEW ---
        // Close the Bluetooth socket to free up resources
        try {
            outputStream?.close()
            connectedSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // --- Permission Handling ---

    // This is the new way to ask for permissions at runtime
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
                permissions[Manifest.permission.BLUETOOTH_CONNECT] == true) {
                // Permissions are granted. We are good to go!
                Toast.makeText(this, "Permissions Granted!", LENGTH_SHORT).show() // <-- EDITED
            } else {
                // Permissions were denied.
                Toast.makeText(this, "Bluetooth permissions are required to use this app", LENGTH_LONG).show() // <-- EDITED
            }
        }

    private fun checkBluetoothPermissions() {
        // We only need to ask for runtime permissions on Android 12 (API 31) and newer
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {

                // Show the system pop-up to ask for permissions
                requestPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                )
            }
        }
        // For older versions, permissions are granted in the Manifest, but we might need Location
        else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                101 // A random request code
            )
        }
    }

    // --- Bluetooth Actions ---

    private fun startBluetoothScan() {
        // Check if Bluetooth is even turned on
        if (bluetoothAdapter?.isEnabled == false) {
            // If it's not on, ask the user to turn it on
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // We use a different "launcher" to get the result of this request
            requestBluetoothEnableLauncher.launch(enableBtIntent)
        } else {
            // Bluetooth is on, so we can start scanning
            Toast.makeText(this, "Scanning...", LENGTH_SHORT).show() // <-- EDITED
            deviceListAdapter.clear()
            discoveredDevices.clear()

            // This is an "unsafe" call, so we have to check permissions again
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter?.startDiscovery()
            }
        }
    }

    // This handles the result of the "Turn Bluetooth On" pop-up
    private val requestBluetoothEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Bluetooth was turned on by the user. Now we can scan.
                startBluetoothScan()
            } else {
                // User Canceled
                Toast.makeText(this, "Bluetooth must be enabled to scan", LENGTH_SHORT).show() // <-- EDITED
            }
        }

    // This is our "listener" that gets notified when a device is found
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                // A new device was found!
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

                if (device != null && device.name != null) {
                    // This is an "unsafe" call, so we check permission
                    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        // Add the device to our list
                        if (!discoveredDevices.contains(device)) {
                            discoveredDevices.add(device)
                            // Add its name to the visible list adapter
                            deviceListAdapter.add(device.name + "\n" + device.address)
                        }
                    }
                }
            }
        }
    }

    // This function is no longer called by the list, but is kept just in case
    private fun pairDevice(device: BluetoothDevice) {
        try {
            // This is an "unsafe" call, so we check permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Pairing with ${device.name}...", LENGTH_SHORT).show() // <-- EDITED
                // This one line triggers the system pairing pop-up
                device.createBond()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Pairing failed: ${e.message}", LENGTH_LONG).show() // <-- EDITED
        }
    }

    // --- NEW FUNCTIONS FOR CONNECTION & SENDING DATA ---

    /**
     * This function sends the command string over Bluetooth.
     * It runs on a background thread to avoid blocking the UI.
     */
    private fun sendBluetoothCommand(command: String) {
        if (outputStream == null) {
            Toast.makeText(this, "Not connected to a device", LENGTH_SHORT).show() // <-- EDITED
            return
        }

        // We send the command followed by a newline.
        // The Python bridge script on the Pi will read this.
        val commandToSend = command + "\n"

        // Run in a background thread to avoid blocking the UI
        Thread {
            try {
                // Write the command to the output stream
                outputStream?.write(commandToSend.toByteArray())
            } catch (e: IOException) {
                e.printStackTrace()
                // Show error on UI thread
                runOnUiThread {
                    Toast.makeText(this, "Failed to send command: ${e.message}", LENGTH_SHORT).show() // <-- EDITED
                }
            }
        }.start()
    }

    /**
     * This function connects to the selected device.
     * It runs on a background thread to avoid blocking the UI.
     */
    private fun connectToDevice(device: BluetoothDevice) {
        // Run connection logic in a background thread
        Thread {
            try {
                // This is an "unsafe" call, so we check permission
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread { Toast.makeText(this, "BLUETOOTH_CONNECT permission missing", LENGTH_SHORT).show() } // <-- EDITED
                    return@Thread
                }

                // Get a BluetoothSocket to connect with the given BluetoothDevice
                val socket: BluetoothSocket? = device.createRfcommSocketToServiceRecord(MY_UUID)

                // Connect to the remote device (this is a blocking call)
                socket?.connect()

                // If successful, store the socket and output stream
                connectedSocket = socket
                outputStream = socket?.outputStream

                // Show a success message on the UI thread
                runOnUiThread {
                    Toast.makeText(this, "Successfully connected to ${device.name}", LENGTH_LONG).show() // <-- EDITED
                }

            } catch (e: IOException) {
                // Connection failed.
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Connection failed: ${e.message}", LENGTH_SHORT).show() // <-- EDITED
                }
                // Close the socket on failure
                try {
                    connectedSocket?.close()
                } catch (closeException: IOException) {
                    closeException.printStackTrace()
                }
            }
        }.start() // Don't forget to start the thread!
    }
}
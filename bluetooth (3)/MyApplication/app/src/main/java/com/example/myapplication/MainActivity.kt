package com.example.myapplication // <-- IMPORTANT: Make sure this package name matches your project's!

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    // --- Class Variables ---

    // Bluetooth adapter. This is the main "controller" for all Bluetooth things.
    private var bluetoothAdapter: BluetoothAdapter? = null

    // This will hold the devices we find so we can show them in the list
    private val discoveredDevices = ArrayList<BluetoothDevice>()
    private lateinit var deviceListAdapter: ArrayAdapter<String>

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
        
      //  val slowerButton Button = findViewById(R.id.slowerButton)
        val devicesListView: ListView = findViewById(R.id.devicesListView)

        // 1. Initialize the list adapter
        // This adapter converts our list of device names into viewable list items
        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = deviceListAdapter

        // 2. Get the Bluetooth Adapter
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_LONG).show()
            finish() // Close the app if Bluetooth isn't supported
            return
        }

        // 3. Request Bluetooth permissions at runtime
        checkBluetoothPermissions()

        // 4. Set what happens when the "Scan" button is clicked
        scanButton.setOnClickListener {
            startBluetoothScan()
        }

        startButton.setOnClickListener {
            // TODO: Add the logic for what your "Start" button should do

            // For now, let's just show a simple "Toast" message
            Toast.makeText(this, "Start button clicked!", Toast.LENGTH_SHORT).show()
        }

        slowerButton.setOnClickListener {
            // TODO: Add the logic for what your "Start" button should do

            // For now, let's just show a simple "Toast" message
            Toast.makeText(this, "Slower button clicked!", Toast.LENGTH_SHORT).show()
        }

        fasterButton.setOnClickListener {
            // TODO: Add the logic for what your "Start" button should do

            // For now, let's just show a simple "Toast" message
            Toast.makeText(this, "faster button clicked!", Toast.LENGTH_SHORT).show()
        }

        toggleDirectionButton.setOnClickListener {
            // TODO: Add the logic for what your "Start" button should do

            // For now, let's just show a simple "Toast" message
            Toast.makeText(this, "toggle direction button clicked!", Toast.LENGTH_SHORT).show()
        }


        // 5. Set what happens when a device in the list is clicked (This is the PAIRING part)
        devicesListView.setOnItemClickListener { _, _, position, _ ->
            // Get the device the user clicked on
            val selectedDevice = discoveredDevices[position]

            // Try to pair with it
            pairDevice(selectedDevice)
        }

        // 6. Register a "receiver" to listen for when devices are found
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(discoveryReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Un-register the receiver to avoid memory leaks when the app closes
        unregisterReceiver(discoveryReceiver)
    }

    // --- Permission Handling ---

    // This is the new way to ask for permissions at runtime
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.BLUETOOTH_SCAN] == true &&
                permissions[Manifest.permission.BLUETOOTH_CONNECT] == true) {
                // Permissions are granted. We are good to go!
                Toast.makeText(this, "Permissions Granted!", Toast.LENGTH_SHORT).show()
            } else {
                // Permissions were denied.
                Toast.makeText(this, "Bluetooth permissions are required to use this app", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Bluetooth must be enabled to scan", Toast.LENGTH_SHORT).show()
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
                        discoveredDevices.add(device)
                        // Add its name to the visible list adapter
                        deviceListAdapter.add(device.name + "\n" + device.address)
                    }
                }
            }
        }
    }

    private fun pairDevice(device: BluetoothDevice) {
        try {
            // This is an "unsafe" call, so we check permission
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Pairing with ${device.name}...", Toast.LENGTH_SHORT).show()
                // This one line triggers the system pairing pop-up
                device.createBond()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Pairing failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
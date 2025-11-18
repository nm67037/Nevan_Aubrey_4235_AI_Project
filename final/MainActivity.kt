package com.example.myapplication // <-- This is the "Folder" your code lives in. Must match your project setup!

// --- IMPORTS ---
// Think of these like #include in C. We are grabbing tools Android already made for us.
import android.Manifest // Used for permissions list
import android.annotation.SuppressLint // Used to tell Android "I know what I'm doing, stop warning me"
import android.app.Activity
import android.bluetooth.BluetoothAdapter // The tool that controls the phone's Bluetooth radio
import android.bluetooth.BluetoothDevice  // Represents a specific remote device (like your Pi)
import android.bluetooth.BluetoothSocket  // The "pipe" or connection line between Phone and Pi
import android.content.BroadcastReceiver  // A tool that listens for system events (like "Device Found!")
import android.content.ContentValues      // Used to describe a file we want to save
import android.content.Context
import android.content.Intent             // A message to the Android system to start something
import android.content.IntentFilter       // Tells Android "I only want to hear about these specific events"
import android.content.pm.PackageManager
import android.os.Build                   // To check which version of Android is running
import android.os.Bundle
import android.os.Environment             // To find where the "Downloads" folder is
import android.os.Handler                 // The "Mailman" that sends messages between threads
import android.os.Looper                  // The main message loop of the app
import android.os.Message
import android.provider.MediaStore        // The system used to save files on modern Android
import android.util.Log                   // Used to print debug messages to the developer console
import android.view.View
// These are the visual elements (Buttons, Text boxes)
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast               // The little popup notification bubble
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// --- CONSTANTS ---
// A secret code number. When the background thread sends data to the UI,
// it stamps it with "0" so the UI knows "Ah, this is a read message."
private const val MESSAGE_READ: Int = 0

// --- DATA STRUCTURE ---
// A simple container (like a struct in C) to hold one row of our Excel/CSV file.
// It holds the time (in milliseconds) and the RPM value.
data class RpmDataPoint(val timestampMs: Long, val rpm: Int)

// @SuppressLint: We handle permissions manually in the code, so we tell Android Studio
// not to nag us with red squiggly lines here.
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    // --- CLASS VARIABLES (The "Global" variables for this screen) ---

    // "BluetoothAdapter" is the hardware radio in your phone.
    // The '?' means this variable CAN be null (empty) if the phone has no Bluetooth.
    private var bluetoothAdapter: BluetoothAdapter? = null

    // A list to remember devices we find while scanning.
    private val discoveredDevices = ArrayList<BluetoothDevice>()

    // The "Adapter" takes our list of devices and converts them into
    // visible rows in the ListView on the screen.
    private lateinit var deviceListAdapter: ArrayAdapter<String>

    // --- BLUETOOTH CONNECTION VARIABLES ---
    
    // UUID is a unique ID. This specific long number is the universal ID for "Serial Port Profile" (SPP).
    // However, we mostly rely on the "Channel 22" hack later in the code.
    private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var connectedSocket: BluetoothSocket? = null // The active connection to the Pi
    private var outputStream: OutputStream? = null       // The stream we write "s", "f", "x" into
    private var inputStream: InputStream? = null         // The stream we read "RPM: 500" from
    private var readDataThread: Thread? = null           // A background worker to listen for data constantly

    // --- APP STATE FLAGS ---
    // These are boolean switches to keep track of what the app is doing.
    private var isClockwise = true    // Are we spinning CW or CCW?
    private var isMotorStopped = true // Is the motor off?
    private var isAutoMode = false    // Are we in Manual or Auto (PID) mode?

    // --- LOGGING VARIABLES ---
    // An ArrayList to store our data in memory before we save it to a file.
    private val rpmDataLog = ArrayList<RpmDataPoint>()
    // Keeps track of WHEN we hit the start button (0 means we haven't started yet).
    private var loggingStartTime: Long = 0L

    // --- UI ELEMENTS ---
    // "lateinit var" means: "I promise I will fill this variable with a button/text view later
    // (specifically in the onCreate method). Don't crash because it's empty right now."
    private lateinit var rpmTextView: TextView
    private lateinit var startButton: Button
    private lateinit var slowerButton: Button
    private lateinit var fasterButton: Button
    private lateinit var stopButton: Button
    private lateinit var toggleDirectionButton: Button
    private lateinit var manualModeButton: Button
    private lateinit var autoModeButton: Button
    private lateinit var autoRpmLayout: LinearLayout // The box containing the Auto-Mode tools
    private lateinit var rpmEditText: EditText       // The text box where you type "1000"
    private lateinit var sendRpmButton: Button       // The button to send the RPM

    // --- THE HANDLER (CRITICAL CONCEPT) ---
    // In Android, you CANNOT touch the screen (update text, disable buttons) from a background thread.
    // The "readDataFromSocket" function runs in the background.
    // This "Handler" acts like a mailbox. The background thread drops a message here,
    // and this Handler (which lives on the Main/UI Thread) opens it and updates the screen.
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            // We only care if the message ID is "MESSAGE_READ" (0)
            when (msg.what) {
                MESSAGE_READ -> {
                    // 1. Get the text message (e.g., "RPM: 1200")
                    val readMessage = msg.obj as String

                    if (readMessage.startsWith("RPM:")) {
                        // 2. Update the big text on the screen
                        rpmTextView.text = readMessage.trim()

                        // 3. LOGGING LOGIC
                        // If loggingStartTime is greater than 0, it means the user hit START.
                        if (loggingStartTime > 0L) {
                            try {
                                // Strip off "RPM:" leaving just "1200", then convert to number (Int)
                                val rpmValue = readMessage.removePrefix("RPM:").trim().toInt()
                                
                                // Get current clock time
                                val currentTime = System.currentTimeMillis()
                                
                                // Calculate how many milliseconds passed since we hit start
                                val relativeTime = currentTime - loggingStartTime
                                
                                // Add this data point to our memory list
                                rpmDataLog.add(RpmDataPoint(relativeTime, rpmValue))
                            } catch (e: NumberFormatException) {
                                // If the Pi sent garbage data that wasn't a number, ignore it.
                                Log.e("RPM_LOG", "Failed to parse RPM: $readMessage")
                            }
                        }
                    }
                }
            }
        }
    }

    // --- ON CREATE (The Main Entry Point) ---
    // This function runs automatically when the user taps the App Icon.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // This loads the visual layout file (XML) so the app knows where buttons go.
        setContentView(R.layout.activity_main)

        // 1. LINKING CODE TO XML
        // We find the buttons in the layout by their IDs and assign them to our variables.
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

        // 2. SETTING UP THE LIST
        // This adapter manages the list of devices found. It puts simple text items into the list.
        deviceListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = deviceListAdapter

        // 3. CHECKING HARDWARE
        // Get access to the phone's Bluetooth radio.
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            // If this is null, the phone physically doesn't have Bluetooth.
            Toast.makeText(this, "Bluetooth is not supported on this device", LENGTH_LONG).show()
            finish() // Close the app immediately.
            return
        }

        // 4. CHECKING PERMISSIONS
        // Android requires us to ask the user for permission to use Bluetooth.
        checkBluetoothPermissions()

        // 5. "SCAN" BUTTON CLICK
        scanButton.setOnClickListener {
            startBluetoothScan()
        }

        // --- MODE BUTTONS ---
        
        // User clicked "Manual Mode"
        manualModeButton.setOnClickListener {
            sendBluetoothCommand("m") // Send 'm' to Pi
            isAutoMode = false
            updateUIForMode(isManualMode = true) // Fix the button visibility
            Toast.makeText(this, "Manual Mode Activated", LENGTH_SHORT).show()
        }

        // User clicked "Auto Mode"
        autoModeButton.setOnClickListener {
            sendBluetoothCommand("a") // Send 'a' to Pi
            isAutoMode = true
            updateUIForMode(isManualMode = false) // Fix the button visibility
            Toast.makeText(this, "Automatic Mode Activated", LENGTH_SHORT).show()
        }

        // User clicked "Send RPM" (in Auto Mode)
        sendRpmButton.setOnClickListener {
            // Get the text user typed
            val rpmValue = rpmEditText.text.toString()
            
            // Check if they actually typed something
            if (rpmValue.isNotEmpty()) {
                // Format the command like "r:1000"
                val command = "r:$rpmValue\n"
                sendBluetoothCommand(command)
                Toast.makeText(this, "Sent RPM: $rpmValue", LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter an RPM value", LENGTH_SHORT).show()
            }
        }

        // --- MOTOR CONTROL BUTTONS ---

        // START BUTTON
        startButton.setOnClickListener {
            // A. START LOGGING
            rpmDataLog.clear() // Erase any old data
            loggingStartTime = System.currentTimeMillis() // Save the current time as "Time Zero"

            // B. SEND COMMANDS
            // We send a sequence to get the motor moving immediately.
            sendBluetoothCommand("s") // Turn Master Power ON
            sendBluetoothCommand("c") // Set Direction to Clockwise
            sendBluetoothCommand("f") // Speed up...
            sendBluetoothCommand("f") // Speed up...
            sendBluetoothCommand("f") // Speed up (reach ~30%)

            isMotorStopped = false
            isClockwise = true
            Toast.makeText(this, "Start (s, c, f, f, f) sent", LENGTH_SHORT).show()
        }

        // SLOWER BUTTON
        slowerButton.setOnClickListener {
            // Safety check: Don't send commands if motor is off
            if (isMotorStopped) {
                Toast.makeText(this, "Press START to begin", LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendBluetoothCommand("d")
            Toast.makeText(this, "Slower (d) sent", LENGTH_SHORT).show()
        }

        // FASTER BUTTON
        fasterButton.setOnClickListener {
            if (isMotorStopped) {
                Toast.makeText(this, "Press START to begin", LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendBluetoothCommand("f")
            Toast.makeText(this, "Faster (f) sent", LENGTH_SHORT).show()
        }

        // STOP BUTTON (Also Saves Data)
        stopButton.setOnClickListener {
            sendBluetoothCommand("x") // Kill switch command
            isMotorStopped = true
            Toast.makeText(this, "Stop (x) sent", LENGTH_SHORT).show()

            // STOP LOGGING & SAVE
            loggingStartTime = 0L // Reset timer
            saveDataToFile() // <--- This saves your CSV file to "Downloads"
        }

        // DIRECTION BUTTON
        toggleDirectionButton.setOnClickListener {
            if (isMotorStopped) {
                Toast.makeText(this, "Press START to begin", LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Flip the boolean
            isClockwise = !isClockwise
            // Decide which letter to send
            val command = if (isClockwise) "c" else "v"
            sendBluetoothCommand(command)
            Toast.makeText(this, "Direction toggled ($command)", LENGTH_SHORT).show()
        }

        // DEVICE LIST CLICK LISTENER
        // What happens when you tap a device name in the list?
        devicesListView.setOnItemClickListener { _, _, position, _ ->
            val selectedDevice = discoveredDevices[position]
            // Stop scanning (it wastes battery and slows down connection)
            bluetoothAdapter?.cancelDiscovery()
            // Attempt to connect
            connectToDevice(selectedDevice)
        }

        // SETUP BROADCAST RECEIVER
        // This tells Android: "Let me know when you find a Bluetooth Device."
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(discoveryReceiver, filter)

        // Start the app in Manual Mode UI state
        updateUIForMode(isManualMode = true)
    }

    // --- ON DESTROY ---
    // Runs when the app is closed (swiped away).
    override fun onDestroy() {
        super.onDestroy()
        // Stop listening for devices
        unregisterReceiver(discoveryReceiver)
        // Stop the background data reading thread
        readDataThread?.interrupt()
        // Close all connections
        try {
            outputStream?.close()
            inputStream?.close()
            connectedSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // --- PERMISSION HELPERS ---
    // Android 12+ (SDK 31+) changed how Bluetooth permissions work.
    // This code checks the Android version and asks for the correct permissions.
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                Toast.makeText(this, "Permissions Granted!", LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permissions required for Bluetooth", LENGTH_LONG).show()
            }
        }

    private fun checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // New Android: Needs BLUETOOTH_SCAN and BLUETOOTH_CONNECT
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                )
            }
        } else {
            // Old Android: Needs LOCATION (because knowing what BT devices are near you reveals your location)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }
        }
    }

    // --- BLUETOOTH SCANNING ---
    private fun startBluetoothScan() {
        // If Bluetooth is off, ask user to turn it on
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            requestBluetoothEnableLauncher.launch(enableBtIntent)
        } else {
            Toast.makeText(this, "Scanning...", LENGTH_SHORT).show()
            deviceListAdapter.clear()
            discoveredDevices.clear()

            // Double check permissions before scanning
            val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }

            if (hasScanPermission) {
                bluetoothAdapter?.startDiscovery() // Tells the radio to start looking
            } else {
                Toast.makeText(this, "Scan permission missing", LENGTH_SHORT).show()
            }
        }
    }

    // Callback for when we ask user "Please turn on Bluetooth"
    private val requestBluetoothEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                startBluetoothScan()
            } else {
                Toast.makeText(this, "Bluetooth must be enabled", LENGTH_SHORT).show()
            }
        }

    // The "Ear" that listens for the system saying "I found a device!"
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                // Grab the device object from the message
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (device != null && device.name != null) {
                    // Check permission again (required by Android logic)
                    val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    } else { true }

                    if (hasConnectPermission) {
                        // Add to list if not already there
                        if (!discoveredDevices.contains(device)) {
                            discoveredDevices.add(device)
                            // Add "Name + MAC Address" to the visible list
                            deviceListAdapter.add(device.name + "\n" + device.address)
                        }
                    }
                }
            }
        }
    }

    // --- UI UPDATE HELPER ---
    // Hides/Shows buttons based on mode
    private fun updateUIForMode(isManualMode: Boolean) {
        if (isManualMode) {
            slowerButton.isEnabled = true
            fasterButton.isEnabled = true
            autoRpmLayout.visibility = View.GONE // Hide the Auto Mode box
        } else {
            slowerButton.isEnabled = false
            fasterButton.isEnabled = false
            autoRpmLayout.visibility = View.VISIBLE // Show the Auto Mode box
        }
    }

    // --- FILE SAVING (MEDIASTORE) ---
    // This is how we save the Excel/CSV file to the Downloads folder
    private fun saveDataToFile() {
        if (rpmDataLog.isEmpty()) {
            Toast.makeText(this, "No data to save", LENGTH_SHORT).show()
            return
        }

        // 1. BUILD THE TEXT CONTENT
        // StringBuilder is a fast way to stick strings together
        val stringBuilder = StringBuilder()
        stringBuilder.append("time(ms),RPM\n") // Column Headers
        
        // Loop through our memory list and add lines like "1200,500"
        rpmDataLog.forEach { dataPoint ->
            stringBuilder.append("${dataPoint.timestampMs},${dataPoint.rpm}\n")
        }
        val fileContent = stringBuilder.toString()

        // 2. CREATE A FILENAME
        // Uses current date/time so files don't overwrite each other
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "rpm_log_$timeStamp.txt"

        // 3. PREPARE THE "TICKET" FOR THE SYSTEM
        // In new Android, we can't just write a file. We have to create a "ContentValues"
        // object to tell the system what we plan to save.
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            // Put it in the standard Downloads folder
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }

        // 4. WRITE THE FILE
        var uri: android.net.Uri? = null
        try {
            // Ask the system to create the file and give us a URI (Address)
            uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri == null) throw IOException("Failed to create new MediaStore entry")

            // Open a stream to that URI and write our data
            contentResolver.openOutputStream(uri).use { outputStream ->
                if (outputStream == null) throw IOException("Failed to open output stream")
                outputStream.write(fileContent.toByteArray())
            }

            // If successful, show a popup
            runOnUiThread {
                Toast.makeText(this, "Log saved to Downloads folder!", LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("RPM_SAVE", "Error saving file", e)
            runOnUiThread {
                Toast.makeText(this, "Error saving file: ${e.message}", LENGTH_LONG).show()
            }
        }
    }

    // --- SENDING DATA ---
    private fun sendBluetoothCommand(command: String) {
        if (outputStream == null) {
            Toast.makeText(this, "Not connected to a device", LENGTH_SHORT).show()
            return
        }
        // Run this in a background thread so the UI doesn't freeze if sending is slow
        Thread {
            try {
                outputStream?.write(command.toByteArray())
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Failed to send command: ${e.message}", LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // --- CONNECTING ---
    private fun connectToDevice(device: BluetoothDevice) {
        // Start a background thread. Connecting takes time (1-3 seconds).
        // If we did this on the main thread, the app would freeze and crash.
        Thread {
            var socket: BluetoothSocket? = null
            try {
                // *** TRICKY PART ***
                // Usually, you connect using UUID. But simple Pi setups often fail that.
                // This code uses "Reflection" (advanced Java) to force a connection
                // to Port #22. This matches "RFCOMM_CHANNEL 22" in your C code.
                socket = device.javaClass
                    .getMethod("createRfcommSocket", Int::class.java)
                    .invoke(device, 22) as BluetoothSocket

                socket.connect() // This line waits here until connection happens

                // If we pass this line, we are connected!
                connectedSocket = socket
                outputStream = socket.outputStream
                inputStream = socket.inputStream

                // Start ANOTHER thread to constantly listen for incoming data
                readDataThread = Thread(this::readDataFromSocket)
                readDataThread?.start()

                // Tell the UI we succeeded
                runOnUiThread {
                    Toast.makeText(this, "Successfully connected to ${device.name}", LENGTH_LONG).show()
                    isMotorStopped = true
                    isAutoMode = false
                    updateUIForMode(isManualMode = true)
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

    // --- RECEIVING DATA ---
    // This function runs forever in a background thread as long as we are connected.
    private fun readDataFromSocket() {
        val reader = BufferedReader(InputStreamReader(inputStream!!))
        while (true) {
            try {
                // "readLine()" BLOCKS. This means the code stops right here and waits
                // until the Pi sends a full line of text (ending in \n).
                val line = reader.readLine()
                if (line != null) {
                    // We got a message! Send it to the "Handler" so it can update the UI.
                    handler.obtainMessage(MESSAGE_READ, line).sendToTarget()
                } else {
                    break // If line is null, the connection was broken.
                }
            } catch (e: IOException) {
                break // Error occurred (e.g., connection lost)
            }
        }
    }
}

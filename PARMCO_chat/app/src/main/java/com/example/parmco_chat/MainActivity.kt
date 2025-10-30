package com.example.parmco_chat

import android.Manifest
import android.content.pm.PackageManager
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.parmco_chat.ui.theme.PARMCO_chatTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive   // <-- important
import java.io.IOException
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val btManager = getSystemService(BluetoothManager::class.java)
        val adapter = btManager.adapter ?: throw IllegalStateException("Bluetooth not available")
        val client = BluetoothClient(adapter)

        setContent {
            PARMCO_chatTheme {
                Scaffold(Modifier.fillMaxSize()) { padding ->
                    BluetoothScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        client = client
                    )
                }
            }
        }
    }
}

@Composable
fun BluetoothScreen(modifier: Modifier = Modifier, client: BluetoothClient) {
    val context = LocalContext.current

    val requiredPerms = remember {
        if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else
            emptyArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ignore results for demo */ }

    fun missingPerms(): Array<String> =
        requiredPerms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    var mac by remember { mutableStateOf(TextFieldValue("AA:BB:CC:DD:EE:FF")) } // set your Pi MAC
    var outgoing by remember { mutableStateOf(TextFieldValue("Hello from Android\n")) }
    val isConnected by client.isConnected.collectAsState()
    val logState = remember { mutableStateListOf<String>() }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) {
        client.incoming.collectLatest { msg ->
            msg?.let { logState.add("Pi → $it") }
        }
    }

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336))
        )

        Text("Bluetooth SPP Client", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = mac,
            onValueChange = { mac = it },
            label = { Text("Raspberry Pi MAC address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !isConnected,
                onClick = {
                    val need = missingPerms()
                    if (need.isNotEmpty()) {
                        permissionLauncher.launch(need)
                    } else {
                        client.connectAsync(mac.text)
                    }
                }
            ) { Text("Connect") }

            OutlinedButton(
                enabled = isConnected,
                onClick = { client.disconnect() }
            ) { Text("Disconnect") }
        }

        OutlinedTextField(
            value = outgoing,
            onValueChange = { outgoing = it },
            label = { Text("Message to send") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = isConnected,
                onClick = {
                    client.send(outgoing.text)
                    logState.add("Me → ${outgoing.text.trimEnd()}")
                }
            ) { Text("Send") }

            OutlinedButton(
                enabled = isConnected,
                onClick = {
                    client.send("ping\n")
                    logState.add("Me → ping")
                }
            ) { Text("Send ping") }
        }

        Text("Log", style = MaterialTheme.typography.titleMedium)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp)
        ) { Text(logState.joinToString(separator = "")) }
    }
}

class BluetoothClient(private val adapter: android.bluetooth.BluetoothAdapter) {
    private var socket: BluetoothSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val incoming = MutableStateFlow<String?>(null)
    val isConnected = MutableStateFlow(false)

    fun connectAsync(mac: String) {
        scope.launch { connect(mac) }
    }

    private suspend fun connect(mac: String) = withContext(Dispatchers.IO) {
        val device = adapter.getRemoteDevice(mac)
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // SPP
        val sock = device.createRfcommSocketToServiceRecord(uuid)
        adapter.cancelDiscovery()
        try {
            sock.connect()  // requires BLUETOOTH_CONNECT on API 31+
            socket = sock
            isConnected.value = true
            scope.launch { readLoop(sock) }
        } catch (e: IOException) {
            try { sock.close() } catch (_: Exception) {}
            isConnected.value = false
        }
    }

    private suspend fun readLoop(sock: BluetoothSocket) {
        val input = sock.inputStream.buffered()
        val buf = ByteArray(1024)
        try {
            while (isActive) {
                val n = input.read(buf)
                if (n > 0) {
                    val text = String(buf, 0, n)
                    incoming.emit(text)
                }
            }
        } catch (_: IOException) {
            isConnected.emit(false)
        }
    }

    fun send(text: String) {
        try {
            socket?.outputStream?.write(text.toByteArray())
            socket?.outputStream?.flush()
        } catch (_: IOException) { /* ignore for demo */ }
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        isConnected.tryEmit(false)
        scope.coroutineContext.cancelChildren()
    }
}

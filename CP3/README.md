# PARMCO - Checkpoint 3: Headless Bluetooth Server

This repository contains the AI-generated code and documentation for Checkpoint 3 of the PARMCO (Phone APP RP4 Motor Control) project.

The goal of this checkpoint is to have a "headless" Raspberry Pi server that runs automatically on boot, waiting for a connection from the mobile app.

This setup successfully passes the "forget and reconnect" test, even with difficult-to-pair devices like iPhones.

## 📁 Core Components (Raspberry Pi Server)

* **`parmco_server.c` (C Program):** The main server that runs on the Pi. It initializes the `pigpio` pins, controls the motor, reads the RPM sensor, and manages the Bluetooth connection.
* **`parmco.service` (`systemd` File):** A service file that automatically starts the `parmco_server` program on boot and ensures it runs as `root` (required for `pigpio`).
* **`bt_agent.py` (Python "Doorman"):** A critical script that runs in the background. It automatically **authorizes pairing requests** and **approves service connections** without requiring user input or "trusting" the device, which prevents stale bonds.
* **`fix_bluetooth.sh` (Bash Script):** A simple script that clears a specific (hard-coded) MAC address from the Pi's Bluetooth cache. This solves the "stale bond" issue where a phone "forgets" the Pi, but the Pi still remembers the phone.
* **`/etc/rc.local` (Boot Script):** This file is configured to launch both `fix_bluetooth.sh` and `bt_agent.py` at the very end of the boot sequence.

## ⚙️ How It Works: The Boot & Connection Sequence

This setup is designed to solve several complex race conditions and caching issues.

1.  **Boot Up (Headless):** The Pi is powered on without a monitor. It boots to the command line (GUI is disabled).
2.  **Services Start:**
    * The main `bluetooth.service` starts.
    * The `parmco.service` starts, launching our compiled `parmco_server` program. The C code successfully binds to **RFCOMM Channel 22** and waits.
3.  **Boot Scripts Run:** At the end of the boot, `rc.local` executes:
    * `fix_bluetooth.sh`: This script runs *after* the Bluetooth service is stable. It waits 20 seconds (to be safe) and then runs `bluetoothctl remove [MAC_ADDRESS]` to clear any old, stale bond for our test phone.
    * `bt_agent.py`: This script launches and runs forever in the background, acting as the "doorman."
4.  **App Connection (The "Forget" Test):**
    * The phone (which has "forgotten" the Pi) scans and finds the discoverable Pi.
    * The phone attempts to pair. The `bt_agent.py` script automatically **accepts the pairing** without a PIN.
    * The app attempts to connect to **Channel 22**.
    * The Pi's Bluetooth system asks, "Is this app *authorized* to use this service?"
    * The `bt_agent.py` script automatically **authorizes the connection**.
    * The connection is established with `parmco_server`, and the app can now send/receive data.
5.  **Critical Bug Fix:** The `parmco_server.c` code has been updated to handle non-blocking `write()` errors. This prevents the server from instantly disconnecting the app (the "socket might closed" error).

## 📡 Bluetooth Protocol

* **RFCOMM Channel:** `22`
* **Pi -> App (Data):** The server sends newline-terminated RPM data.
    * `"RPM:0\n"`
    * `"RPM:4500\n"`
* **App -> Pi (Commands):** The app sends single characters.
    * `'s'`: Start (Master Power ON)
    * `'x'`: Stop (Calls `stop_all_activity()` for a full, safe stop)
    * `'c'`: Clockwise
    * `'v'`: Counter-Clockwise
    * `'f'`: Faster
    * `'d'`: Slower

---

## 📱 Android App (Client)

This section describes the main client-side code for the Android app.

> //Nevan comment: This is the final working main app code from Gemini for CP2.

### `MainActivity.kt` (Kotlin/Android)

This file contains all the logic for the Android application. It handles the User Interface (UI), Bluetooth permissions, device scanning, and communication.

**Key Functions:**

* **UI and Permissions:**
    * Handles all button clicks for "Scan," "Start," "Stop," "Faster," etc.
    * Requests the necessary Bluetooth permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) from the user, handling both new (Android 12+) and old Android versions.
* **Device Discovery:**
    * Uses a `BroadcastReceiver` to scan for and display a list of nearby Bluetooth devices.
* **Connection Logic (`connectToDevice`):**
    * When a user taps a device from the list, this function attempts to connect.
    * It **specifically targets RFCOMM Channel 22** using a special method (`createRfcommSocket`) to bypass standard UUID pairing, which is necessary to connect to our custom C server.
* **Sending Data (`sendBluetoothCommand`):**
    * Converts the single-character commands (like `'s'` or `'f'`) into bytes and sends them to the Pi over the Bluetooth socket's `outputStream`.
* **Receiving Data (`readDataFromSocket`):**
    * Runs a dedicated background thread to listen for data from the Pi.
    * It uses a `BufferedReader` to read incoming data line-by-line (waiting for the `\n` sent by the C server).
    * It sends the received string (e.g., `"RPM:4500"`) to the main UI thread using a `Handler`, which then updates the `rpmTextView` on the screen.

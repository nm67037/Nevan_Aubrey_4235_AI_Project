# PARMCO - Checkpoint 3: Headless Bluetooth Server

This checkpoint transitions the project from a simple, keyboard-controlled C program to a fully "headless" Raspberry Pi server. The Pi now runs automatically on boot, waiting for a connection from the mobile app.

This setup successfully passes the "forget and reconnect" test, even with difficult-to-pair devices like iPhones.

## 📁 Core Components

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

# PARMCO - Final Project: Closed-Loop Motor Control System

**Course:** ECSE 4235 - Embedded Systems  
**Project:** Phone APP RP4 Motor Control (PARMCO)

This repository contains the complete source code and documentation for the PARMCO system. The project successfully implements a **headless Raspberry Pi appliance** that accepts Bluetooth connections from an Android app to control a DC motor in both **Manual (Open Loop)** and **Automatic (Closed Loop/PID)** modes.

## 🏆 Key Achievements
* **True Headless Operation:** The Pi boots, clears stale Bluetooth bonds, auto-authorizes connections, and launches the server without any monitor or keyboard attached.
* **Closed-Loop Feedback:** Implements a **Proportional (P) Controller** to maintain a specific target RPM despite load changes.
* **Noise Immunity:** Features sophisticated software filtering (Glitch Filter + Hard Cap + Smoothing) to handle noisy IR sensor data.
* **Robust Communication:** Uses a custom State Machine parser to handle fragmented Bluetooth packets and connection stability logic.

---

## 📁 Core Components (Raspberry Pi)

### 1. `parmco_server.c` (The Brain)
The main C program running as a system service.
* **Control Logic:** Runs a 1-second control loop.
    * **Manual Mode:** Direct duty cycle control via app buttons.
    * **Auto Mode:** Calculates RPM error (`Target - Actual`) and adjusts PWM power using a Proportional controller (`Kp = 0.2`). Includes "Anti-Stall" logic to kickstart the motor if it gets stuck.
* **Sensor Processing:** Uses `pigpio` interrupts (Rising Edge) with a **100µs glitch filter** to count propeller rotations. Includes a physics-based **Hard Cap (12,000 RPM)** to reject electrical noise spikes.
* **Bluetooth Server:** Listens on **RFCOMM Channel 22**. Uses non-blocking sockets to ensure the control loop never freezes, even if the app disconnects.

### 2. System Services & Scripts
* **`parmco.service`:** A `systemd` unit that ensures `parmco_server` runs with root privileges immediately after boot.
* **`bt_agent.py` (Python):** A D-Bus agent that acts as a "Doorman." It automatically accepts pairing requests and authorizes service connections, bypassing the need for a GUI PIN entry.
* **`fix_bluetooth.sh` (Bash):** A startup script that waits 20 seconds for the Bluetooth stack to stabilize, then forcibly removes the phone's MAC address from the cache. This solves the iOS/Android "Stale Bond" issue where the phone forgets the Pi, but the Pi remembers the phone.
* **`/etc/rc.local`:** The boot loader that triggers the helper scripts.
* **`/etc/bluetooth/main.conf`:** Modified to ensure `DiscoverableTimeout = 0` (Always Discoverable) and disables conflicting plugins like `Headset` and `Audio`.

---

## 📡 Bluetooth Protocol

The system uses a text-based protocol over RFCOMM.

### Android -> Pi (Commands)
* `s`: **Start** (Power ON, Reset State).
* `x`: **Stop** (Cut Power, Reset PID).
* `c` / `v`: **Direction** (Clockwise / Counter-Clockwise).
* `f` / `d`: **Manual Speed** (Faster / Slower by 10%).
* `a`: **Switch to Auto Mode** (Enables PID Controller).
* `m`: **Switch to Manual Mode**.
* `r:<number>\n`: **Set Target RPM** (e.g., `r:1200\n` sets target to 1200).  
  *Note: Requires newline `\n` terminator for the C state machine parser.*

### Pi -> Android (Data)
* `RPM:<value>\n`: Sends the current smoothed RPM once every 500ms (e.g., `RPM:1050`).

---

## 📱 Android App (Client)

This section describes the main client-side code developed for the Android app.

### `MainActivity.kt` (Kotlin)
> //Nevan comment: This is the final working main app code from Gemini for CP3.

This Kotlin file contains the core logic for the application. It manages the Bluetooth lifecycle, user interactions, and data processing.

**Key Functions:**
* **UI & Permissions:** Manages button clicks and requests necessary Bluetooth permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) compatible with both new (Android 12+) and older Android versions.
* **Device Discovery:** Uses a `BroadcastReceiver` to scan for nearby Bluetooth devices and populates a list for the user to select.
* **Connection Logic:** Specifically targets **RFCOMM Channel 22** using reflection (`createRfcommSocket`) to connect directly to our custom C server on the Pi.
* **Sending Commands:** Converts UI button presses (Start, Stop, Faster) into the single-character byte commands expected by the Pi.
* **Receiving Data:** Runs a background thread to listen for incoming RPM data strings (e.g., `"RPM:4500"`) and updates the on-screen text view in real-time via a Handler.

### `activity_main.xml` (Layout)
> //Nevan comment: This is the xml file of the cp3 code. The purpose of this code is to set up the buttons and RPM display visuals

This XML file defines the user interface layout. It arranges the buttons ("Scan", "Start", "Stop", "Faster", "Slower", "Toggle Direction") and the RPM display text view in a simple vertical list (`LinearLayout`) for easy interaction. It dynamically shows/hides the "Target RPM" input field depending on whether the user is in Auto or Manual mode.

---

## 🔧 Hardware Wiring

| Component | Pi Pin (BCM) | Physical Pin | Notes |
| :--- | :--- | :--- | :--- |
| **Motor Enable** | GPIO 17 | 11 | Controls MOSFET Gate (Master Power) |
| **Motor PWM** | GPIO 18 | 12 | Connects to L293D Enable 1 |
| **Direction A** | GPIO 27 | 13 | Connects to L293D Input 1 |
| **Direction B** | GPIO 22 | 15 | Connects to L293D Input 2 |
| **IR Sensor** | GPIO 23 | 16 | **Pull-Up** Resistor enabled. Detects Rising Edge. |

---

## 🚀 How to Run (Headless)

1.  **Power on the Pi.** (No monitor needed).
2.  **Wait ~30 seconds** for the boot scripts to initialize Bluetooth and clear old bonds.
3.  **Open the Android App.**
4.  **Scan & Connect** to `group-1-0` (or your specific hostname).
5.  **Control:**
    * Press **Start** (Motor idles).
    * Press **Auto Mode**.
    * Enter **1000** and press **Send**.
    * *Observation:* The motor will spin up, overshoot slightly, and then settle at 1000 RPM. Friction applied to the blade will cause the motor to "whine" and increase power to compensate (Feedback).

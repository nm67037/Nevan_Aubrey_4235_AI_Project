# PARMCO - Checkpoint 2

This repository contains the AI-generated code and documentation for Checkpoint 2 of the PARMCO (Phone APP RP4 Motor Control) project.

The goal of this checkpoint is to have the Raspberry Pi 4 control the DC motor (direction, speed, on/off) using keyboard commands.

## Hardware

The circuit for this checkpoint uses an L293D H-bridge for bidirectional motor control and an IRFZ34N MOSFET as a master power switch. The MOSFET is isolated from the Raspberry Pi using a 4N25 optocoupler.

(You should include the schematic and parts list images/files in this repository as well).

## Software Documentation

The software for this checkpoint consists of a single C program: `cp2_control.c`.

### `cp2_control.c`

This program provides a simple command-line interface to control the motor. It runs on the Raspberry Pi and listens for single-key presses to manage the motor's state.

**Functionality:**
* Initializes the Raspberry Pi's GPIO pins.
* Sets up the terminal for non-blocking, non-echoing input to read single keystrokes.
* Listens for keyboard commands to control the motor.
* On exit, safely shuts down all GPIO activity.

**Keyboard Commands:**
* `s`: **Start** (Master power ON)
* `x`: **Stop** (Master power OFF)
* `c`: **Clockwise** direction
* `v`: **Counter-clockwise** direction
* `f`: **Faster** (increase speed by 10%)
* `d`: **Slower** (decrease speed by 10%)
* `q`: **Quit** (Stops motor and exits program)

### How to Compile and Run

1.  **Start the `pigpio` daemon:** This service must be running in the background.
    ```bash
    sudo pigpiod
    ```
2.  **Compile the program:** The code must be linked against the `libpigpiod_if2`, `lpthread`, and `lrt` libraries.
    ```bash
    gcc -o cp2_control cp2_control.c -lpigpiod_if2 -lpthread -lrt
    ```
3.  **Run the program:**
    ```bash
    sudo ./cp2_control
    ```

---

## Dependencies & Library Documentation

This project requires the **pigpio C library** to function.

### 1. Library Attribution
* **Library Name:** pigpio
* **Author:** joan
* **Source:** `http://abyz.me.uk/rpi/pigpio/`
* **License:** Unlicense (Public Domain)
* **Interface Used:** `libpigpiod_if2.so` (C client library)

### 2. Why Was This Library Needed?

The `pigpio` library was chosen over other methods (like `wiringPi` or direct memory mapping) for one critical reason: **hardware PWM**.

To control the motor's speed, we must send a Pulse Width Modulation (PWM) signal.
* **Software PWM:** Many libraries create a PWM signal in software (i.e., the CPU rapidly toggles a pin). This is unreliable. It is subject to jitter and can be interrupted by other operating system tasks, causing the motor to twitch or run at an inconsistent speed.
* **Hardware PWM:** The Raspberry Pi's BCM chip has dedicated hardware for generating highly stable, precise, and high-frequency PWM signals without using any CPU resources.

The `pigpio` library provides the most robust and simplest C interface to access this dedicated hardware PWM peripheral (`hardware_PWM()` function), which is essential for smooth and reliable motor speed control.

### 3. How It Works

The `pigpio` library operates on a **daemon-client model**.

1.  **The Daemon (`pigpiod`):**
    * This is a background service (a *daemon*) that you start once with `sudo pigpiod`.
    * This daemon runs with root privileges and takes exclusive control of the Raspberry Pi's GPIO hardware.
    * It handles all the low-level, high-speed timing and signal generation (like our hardware PWM).

2.  **The Client (`cp2_control.c`):**
    * Our C program, `cp2_control.c`, acts as a *client*. It does **not** touch the hardware directly.
    * It uses the `libpigpiod_if2` library to send commands (e.g., "set GPIO 18 to 1000Hz PWM at 50% duty cycle") to the daemon over a socket.
    * The daemon receives these commands and executes them on the hardware.

This model is why our program can run as a normal user (after compiling) but still control hardware, and it's what allows the high-performance hardware PWM to function correctly. The `pigpio_start()` function in our code is the command that establishes this connection to the daemon.
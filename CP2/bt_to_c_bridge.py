#!/usr/bin/python3
"""
Bluetooth to C-Program Bridge
Author: Gemini
Date: 10/28/2025

This script acts as a bridge between your Android app and the compiled C
motor control program.

It does two things:
1. Starts the C program (`motor_control_v6`) as a background process.
2. Runs a Bluetooth server that listens for commands from the app.

When the app sends a command (e.g., "s"), this script receives it and "pipes"
it into the C program's standard input (STDIN), simulating a keyboard press.

"""

import bluetooth
import subprocess
import os
import sys

# --- CONFIGURATION ---

# IMPORTANT: Make sure this path is correct!
# This is the name of your friend's compiled C program.
# Assumes it's in the same directory as this Python script.
C_PROGRAM_PATH = "./motor_control_v6"

# This is the same UUID from your Android app
UUID = "00001101-0000-1000-8000-00805F9B34FB"

# List of valid commands the C program accepts
# This acts as a simple allow-list.
VALID_COMMANDS = ['s', 'x', 'c', 'v', 'f', 'd', 'q']

# ---------------------

# --- 1. Start the C program as a background process ---

# We need to make sure the pigpio daemon is running first
# --- DELETED LINE ---
# We will run 'sudo pigpiod' from the terminal manually.
# os.system("sudo pigpiod")

print(f"Starting C program: {C_PROGRAM_PATH}")

if not os.path.exists(C_PROGRAM_PATH):
    print(f"Error: C program not found at '{C_PROGRAM_PATH}'")
    print(f"Did you compile it? (e.g., gcc -o motor_control_v6 motor_control.c -lpigpiod_if2 -lrt)")
    sys.exit(1)

# We use subprocess.Popen to run the C program
# - stdin=subprocess.PIPE: Lets us "pipe" data into the C program's STDIN
# - stdout=sys.stdout: Lets us see the `printf` output from the C program
# - text=True, bufsize=1: Ensures commands are sent immediately (line-buffered)
try:
    c_process = subprocess.Popen(
        [C_PROGRAM_PATH],
        stdin=subprocess.PIPE,
        stdout=sys.stdout,
        stderr=sys.stderr,
        text=True,
        bufsize=1,
        universal_newlines=True
    )
except Exception as e:
    print(f"Failed to start C program: {e}")
    print("Make sure it's compiled and you have permissions.")
    sys.exit(1)

# --- 2. Set up Bluetooth Server ---

server_sock = None
try:
    server_sock = bluetooth.BluetoothSocket(bluetooth.RFCOMM)
    server_sock.bind(("", bluetooth.PORT_ANY))
    server_sock.listen(1)

    port = server_sock.getsockname()[1]

    # Announce the service
    bluetooth.advertise_service(server_sock, "MotorControlBridge",
                                service_id=UUID,
                                service_classes=[UUID, bluetooth.SERIAL_PORT_CLASS],
                                profiles=[bluetooth.SERIAL_PORT_PROFILE],
                                )

    print(f"\n--- Bluetooth Bridge Ready ---")
    print(f"Waiting for connection on RFCOMM channel {port}")

    # --- 3. Main Connection Loop ---
    while True:
        # Wait for the Android app to connect
        client_sock, client_info = server_sock.accept()
        print(f"\nAccepted connection from {client_info}")

        try:
            while True:
                # Read data from the Android app
                data = client_sock.recv(1024)
                if not data:
                    break

                # Decode the command and remove the newline/whitespace
                # Your app sends "s\n", this will make it "s"
                command = data.decode('utf-8').strip()

                if command in VALID_COMMANDS:
                    # This is the magic!
                    # Write the command to the C program's STDIN
                    print(f"Relaying command to C program: '{command}'")
                    c_process.stdin.write(command)
                    c_process.stdin.flush() # Ensure it's sent immediately
                else:
                    print(f"Received unknown command: [{command}] (Not relayed)")

        except bluetooth.BluetoothError as e:
            print(f"Bluetooth connection lost: {e}")
        finally:
            print("Client disconnected.")
            client_sock.close()

except KeyboardInterrupt:
    print("\nShutting down Bluetooth server...")

finally:
    # --- 4. Cleanup ---
    if server_sock:
        server_sock.close()
        print("Bluetooth socket closed.")

    if c_process:
        print("Stopping C program (sending 'q')...")
        try:
            c_process.stdin.write('q\n') # Send 'q' to quit the C program gracefully
            c_process.stdin.flush()
        except (IOError, BrokenPipeError):
            print("C program already terminated.")
        
        c_process.terminate() # Ensure it's stopped
        c_process.wait()
        print("C program stopped.")

    print("Server shut down.")

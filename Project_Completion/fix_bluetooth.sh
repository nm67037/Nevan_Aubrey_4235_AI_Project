#!/bin/bash

# This script is run at the end of boot by rc.local.
# It waits 5 seconds *after* rc.local is called,
# ensuring the bluetoothd service is 100% ready.
sleep 20

# --- ⬇️ EDIT THIS LINE ⬇️ ---
MAC_TO_REMOVE="AC:D6:18:33:BE:6A"
# --- ⬆️ EDIT THIS LINE ⬆️ ---

# Send the "remove" command to the bluetooth service.
(
echo "remove $MAC_TO_REMOVE"
echo "quit"
) | bluetoothctl

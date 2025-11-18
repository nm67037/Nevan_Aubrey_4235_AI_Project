#!/usr/bin/env python3

# AI-Generated Bluetooth Auto-Pairing Agent
# Author: Gemini
# Date: 11/11/2025
#
# This script runs in the background to automatically
# accept any Bluetooth pairing request without a PIN.
# This is required to pass the "headless" re-pairing test.

import dbus
import dbus.service
import dbus.mainloop.glib
from gi.repository import GLib

AGENT_PATH = "/parmco/agent"
AGENT_INTERFACE = "org.bluez.Agent1"
CAPABILITY = "NoInputNoOutput" # Auto-accept, no PIN

class BT_Agent(dbus.service.Object):
    @dbus.service.method(AGENT_INTERFACE, in_signature="", out_signature="")
    def Release(self):
        print("Agent: Release")

    @dbus.service.method(AGENT_INTERFACE, in_signature="o", out_signature="s")
    def RequestPinCode(self, device):
        print(f"Agent: RequestPinCode for {device}")
        return "0000" # We won't use this, but good to have

    @dbus.service.method(AGENT_INTERFACE, in_signature="ou", out_signature="")
    def RequestConfirmation(self, device, passkey):
        print(f"Agent: RequestConfirmation for {device} with passkey {passkey}")
        # Auto accept
        return

    @dbus.service.method(AGENT_INTERFACE, in_signature="o", out_signature="")
    def RequestAuthorization(self, device):
        print(f"Agent: RequestAuthorization for {device}")
        # Auto-authorize any connection
        return

def main():
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    
    try:
        agent = BT_Agent(bus, AGENT_PATH)
        
        manager = dbus.Interface(bus.get_object("org.bluez", "/org/bluez"), "org.bluez.AgentManager1")
        manager.RegisterAgent(AGENT_PATH, CAPABILITY)
        manager.RequestDefaultAgent(AGENT_PATH)
        
        print("Bluetooth Pairing Agent started.")
        GLib.MainLoop().run()
        
    except Exception as e:
        print(f"Failed to start agent: {e}")

if __name__ == "__main__":
    main()

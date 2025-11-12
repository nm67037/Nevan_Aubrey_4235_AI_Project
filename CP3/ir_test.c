/*
 * PARMCO - IR Sensor Test Program
 *
 * This program connects to the pigpio daemon, sets the sensor
 * pin as an input, and continuously reads its state.
 *
 * AI Author: Gemini
 * Date: 11/06/2025
 *
 * This is used to verify the IR sensor on GPIO 23 is working
 * before integrating it into the main controller.
 *
 */

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>      // For time_sleep
#include <pigpiod_if2.h> // Use the daemon interface library
#include <signal.h>      // For catching Ctrl+C

// --- Pin Definition ---
#define SENSOR_PIN 23

// Global variable to control the main loop
static volatile int keep_running = 1;

/**
 * @brief Handles the Ctrl+C signal (SIGINT) for a clean exit.
 */
void int_handler(int sig) {
    keep_running = 0;
}

int main() {
    int pi; // pigpio connection handle
    int level;

    // 1. Register the Ctrl+C interrupt handler
    signal(SIGINT, int_handler);

    // 2. Connect to pigpio daemon
    pi = pigpio_start(NULL, NULL); 
    if (pi < 0) {
        fprintf(stderr, "pigpio initialisation failed! (Could not connect to daemon)\n");
        fprintf(stderr, "Did you run 'sudo pigpiod'?\n");
        return 1;
    }

    // 3. Set sensor pin mode to INPUT
    set_mode(pi, SENSOR_PIN, PI_INPUT);
    
    printf("Testing IR Sensor on GPIO %d...\n", SENSOR_PIN);
    printf("Press Ctrl+C to exit.\n\n");

    // 4. Main loop: Read and print pin state
    while (keep_running) {
        // Read the current logical level of the pin
        level = gpio_read(pi, SENSOR_PIN);

        // Print the state, using \r to overwrite the same line
        // Add spaces at the end to clear any previous text
        printf("\rSensor state: %d   ", level);
        fflush(stdout); // Force the line to print immediately

        // Sleep for 100ms
        time_sleep(0.1); 
    }

    // 5. Cleanup and Exit
    printf("\n\nCleaning up and exiting...\n");
    pigpio_stop(pi); // Disconnect from the daemon
    return 0;
}
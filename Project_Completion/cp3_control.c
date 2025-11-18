/*
 * PARMCO Checkpoint 3 Motor Control (Corrected)
 *
 * AI Author: Gemini
 * Date: 11/11/2025
 *
 * DESCRIPTION:
 * This program extends cp2_control.c by adding RPM (Revolutions Per Minute)
 * detection. It uses the IR sensor on GPIO 23 to count motor revolutions
 * and displays the calculated RPM on the screen.
 *
 * - Uses a pigpio callback (interrupt) on SENSOR_PIN for efficient counting.
 * - Calculates and updates RPM display once per second.
 *
 * FIX:
 * - Replaced 'time_diff()' (which doesn't exist in this library)
 * with standard unsigned integer subtraction '(current_tick - last_rpm_tick)'
 * which correctly handles timer wrap-around.
 *
 * GPIO ASSIGNMENTS:
 * - GPIO 17: MASTER_ON (Active-HIGH)
 * - GPIO 27: DIR_A
 * - GPIO 22: DIR_B
 * - GPIO 18: SPEED (PWM)
 * - GPIO 23: SENSOR_PIN (Input for RPM)
 *
 * KEYBOARD COMMANDS:
 * - 's': Start (Master power ON)
 * - 'x': Stop (Master power OFF)
 * - 'c': Clockwise direction
 * - 'v': Counter-clockwise direction
 * - 'f': Faster (increase speed)
 * - 'd': Slower (decrease speed)
 * - 'q': Quit (Stops motor and exits program)
 *
 */

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>      // For usleep
#include <pigpiod_if2.h> // Use the daemon interface library
#include <termios.h>     // For non-blocking terminal
#include <fcntl.h>       // For non-blocking terminal
#include <signal.h>      // For Ctrl+C handling

// --- Pin Definitions ---
#define MASTER_ON_PIN 17 // Active-HIGH
#define DIR_A_PIN     27
#define DIR_B_PIN     22
#define SPEED_PIN     18 // Hardware PWM capable
#define SENSOR_PIN    23 // IR Sensor Input

// --- PWM Definitions ---
#define PWM_FREQ 1000 // 1000 Hz PWM frequency

// --- Global Variables ---
struct termios old_tio;
static volatile int keep_running = 1; // For Ctrl+C
static volatile int revolution_count = 0; // Incremented by callback
static volatile int rpm = 0; // Calculated RPM
static int speed_percent = 0; // Current speed (0-100)
static int pi; // pigpio connection handle

/**
 * @brief Sets up the terminal for non-canonical, non-echoing mode.
 */
void setup_terminal() {
    struct termios new_tio;
    tcgetattr(STDIN_FILENO, &old_tio);
    new_tio = old_tio;
    new_tio.c_lflag &= (~ICANON & ~ECHO);
    tcsetattr(STDIN_FILENO, TCSANOW, &new_tio);
    fcntl(STDIN_FILENO, F_SETFL, O_NONBLOCK);
}

/**
 * @brief Restores the terminal to its original settings.
 */
void restore_terminal() {
    tcsetattr(STDIN_FILENO, TCSANOW, &old_tio);
}

/**
 * @brief Sets all motor control pins to a safe, OFF state.
 */
void stop_all_activity() {
    printf("\nStopping all activity...\n");
    
    // Stop PWM
    hardware_PWM(pi, SPEED_PIN, PWM_FREQ, 0); 
    
    // Set direction to coast
    gpio_write(pi, DIR_A_PIN, 0);
    gpio_write(pi, DIR_B_PIN, 0);
    
    // Turn master power OFF
    gpio_write(pi, MASTER_ON_PIN, 0);
    
    // Reset globals
    speed_percent = 0;
    revolution_count = 0;
    rpm = 0;
}

/**
 * @brief Handles the Ctrl+C signal (SIGINT) for a clean exit.
 */
void int_handler(int sig) {
    keep_running = 0;
}

/**
 * @brief This callback function is triggered on every edge detection.
 * We are looking for the RISING edge (0 -> 1)
 * which corresponds to the reflective blade detection.
 */
void rpm_callback(int pi, unsigned gpio, unsigned level, uint32_t tick) {
    // Check if this is a RISING edge (0 -> 1)
    if (level == 1) {
        revolution_count++;
    }
}

int main() {
    char c;
    int speed_duty_cycle = 0;
    uint32_t last_rpm_tick; // pigpio_tick() is in microseconds

    // 1. Register Ctrl+C handler and set up terminal
    signal(SIGINT, int_handler);
    setup_terminal();

    // 2. Connect to pigpio daemon
    pi = pigpio_start(NULL, NULL); 
    if (pi < 0) {
        fprintf(stderr, "pigpio initialisation failed! (Could not connect to daemon)\n");
        fprintf(stderr, "Did you run 'sudo pigpiod'?\n");
        restore_terminal();
        return 1;
    }

    // 3. Set GPIO pin modes
    set_mode(pi, MASTER_ON_PIN, PI_OUTPUT);
    set_mode(pi, DIR_A_PIN, PI_OUTPUT);
    set_mode(pi, DIR_B_PIN, PI_OUTPUT);
    set_mode(pi, SPEED_PIN, PI_OUTPUT); 
    set_mode(pi, SENSOR_PIN, PI_INPUT);

    // 4. Set initial safe state (all off)
    stop_all_activity();
    
    // 5. Set up the RPM sensor callback
    // We pull the pin DOWN. When the sensor detects, it sends a HIGH (1) signal.
    // This creates the RISING_EDGE (0 -> 1) we're looking for.
    set_pull_up_down(pi, SENSOR_PIN, PI_PUD_DOWN);
    callback(pi, SENSOR_PIN, RISING_EDGE, rpm_callback);

    printf("Motor control ready (CP3). Use 's', 'x', 'c', 'v', 'f', 'd', 'q'.\n");
    printf("Press Ctrl+C to exit.\n");

    // Get the start time for RPM calculation
    last_rpm_tick = get_current_tick(pi);

    // 6. Main loop
    while (keep_running) {
        uint32_t current_tick = get_current_tick(pi);

        // --- RPM Calculation (runs once per second) ---
        // *** CORRECTION HERE ***
        // Use standard unsigned subtraction to find the time difference.
        // This handles timer wrap-around correctly.
        if ((current_tick - last_rpm_tick) >= 1000000) {
            
            // Calculate RPM (revolutions_per_second * 60)
            rpm = (revolution_count / 3) * 60;
            
            // Reset counter and timestamp for next calculation
            revolution_count = 0;
            last_rpm_tick = current_tick;
        }

        // --- Keyboard Command Handling (non-blocking) ---
        if (read(STDIN_FILENO, &c, 1) > 0) {
            
            switch (c) {
                case 's': 
                    gpio_write(pi, MASTER_ON_PIN, 1);
                    break;

                case 'x': 
                    gpio_write(pi, MASTER_ON_PIN, 0);
                    break;

                case 'c': // Clockwise
                    gpio_write(pi, DIR_A_PIN, 0);
                    gpio_write(pi, DIR_B_PIN, 1);
                    break;

                case 'v': // Counter-Clockwise
                    gpio_write(pi, DIR_A_PIN, 1);
                    gpio_write(pi, DIR_B_PIN, 0);
                    break;
                
                case 'f': // Faster
                    speed_percent += 10;
                    if (speed_percent > 100) speed_percent = 100;
                    // convert 0-100% to 0-1,000,000 duty cycle
                    speed_duty_cycle = speed_percent * 10000; 
                    hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_duty_cycle);
                    break;

                case 'd': // Slower
                    speed_percent -= 10;
                    if (speed_percent < 0) speed_percent = 0;
                    speed_duty_cycle = speed_percent * 10000;
                    hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_duty_cycle);
                    break;

                case 'q': // Quit
                    keep_running = 0;
                    break;
            }
        }

        // --- Persistent Display ---
        // Print to the same line (\r) to create a live display
        // Add spaces at the end to clear previous, longer lines
        printf("\rSpeed Setting: %d%% | Actual RPM: %d   ", speed_percent, rpm);
        fflush(stdout); // Force print to screen
        
        usleep(50000); // 50ms loop delay to prevent 100% CPU usage
    }

    // 7. Cleanup and Exit
    stop_all_activity();
    pigpio_stop(pi); // Disconnect from the daemon
    restore_terminal();
    return 0;
}
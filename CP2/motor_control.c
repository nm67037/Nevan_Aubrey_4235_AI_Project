/*
 * PARMCO Checkpoint 2 Keyboard Control (Version 6)
 *
 * AI Author: Gemini
 * Date: 10/28/2025
 *
 * FIXES IN THIS VERSION:
 * 1. The 'x' (stop) key now calls stop_all_activity() to
 * properly stop the motor, not just cut power.
 *
 * GPIO ASSIGNMENTS:
 * - GPIO 17: MASTER_ON (Active-HIGH)
 * - GPIO 27: DIR_A
 * - GPIO 22: DIR_B
 * - GPIO 18: SPEED (PWM)
 *
 * KEYBOARD COMMANDS:
 * - 's': Start (Master power ON)
 * - 'x': Stop (Full stop: power, speed, and direction)
 * - 'c': Clockwise direction
 * - 'v': Counter-clockwise direction
 * - 'f': Faster (increase speed)
 * - 'd': Slower (decrease speed)
 * - 'q': Quit (Stops motor and exits program)
 *
 * -----------------------------------------------------------------
 * ✅ OPERATING SEQUENCE:
 * 1. Press 's' to turn the Master Power ON.
 * 2. Press 'c' or 'v' to select a direction.
 * 3. Press 'f' repeatedly to increase speed.
 * (The motor may not move until 20-30% speed).
 * -----------------------------------------------------------------
 *
 */

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>      // For usleep, read
#include <pigpiod_if2.h> // Use the daemon interface library
#include <termios.h>     // For non-blocking terminal
#include <fcntl.h>       // For non-blocking terminal

// --- Pin Definitions ---
#define MASTER_ON_PIN 17 // Active-HIGH
#define DIR_A_PIN     27
#define DIR_B_PIN     22
#define SPEED_PIN     18 // Hardware PWM capable

// --- PWM Definitions ---
#define PWM_FREQ 1000 // 1000 Hz PWM frequency
#define PWM_RANGE 100 // We will use 0-100 for speed percentage

// --- Global Terminal Settings ---
struct termios old_tio;

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
 * @param pi The pigpio connection handle.
 */
void stop_all_activity(int pi) {
    printf("Stopping all activity...\n");
    
    // Set speed to 0
    hardware_PWM(pi, SPEED_PIN, PWM_FREQ, 0); 
    
    // Set direction to coast
    gpio_write(pi, DIR_A_PIN, 0);
    gpio_write(pi, DIR_B_PIN, 0);
    
    // Turn master power OFF (Active-HIGH, so write 0)
    gpio_write(pi, MASTER_ON_PIN, 0);
}

int main() {
    char c;
    int speed = 0; // Start speed at 0
    int duty_cycle = 0;
    int pi; 

    // 1. Initialize Terminal
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

    // 4. Set initial safe state (all off)
    stop_all_activity(pi);
    
    printf("Motor control ready (V6). Use 's' (start), 'x' (stop), 'c' (cw), 'v' (ccw), 'f' (faster), 'd' (slower), 'q' (quit).\n");
    printf("Current Speed: %d%%\n", speed);

    // 5. Main loop
    while (1) {
        if (read(STDIN_FILENO, &c, 1) > 0) {
            
            switch (c) {
                case 's': // Start Master Power
                    printf("Master Power ON\n");
                    gpio_write(pi, MASTER_ON_PIN, 1); // Active-HIGH
                    break;

                case 'x': // Stop Master Power
                    printf("Master Power OFF (Full Stop)\n");
                    // *** CORRECTION HERE ***
                    stop_all_activity(pi); // Call the full stop function
                    speed = 0; // Reset the internal speed variable
                    printf("Speed: %d%%\n", speed);
                    break;

                case 'c': // Clockwise
                    printf("Direction: Clockwise\n");
                    gpio_write(pi, DIR_A_PIN, 0);
                    gpio_write(pi, DIR_B_PIN, 1);
                    break;

                case 'v': // Counter-Clockwise
                    printf("Direction: Counter-Clockwise\n");
                    gpio_write(pi, DIR_A_PIN, 1);
                    gpio_write(pi, DIR_B_PIN, 0);
                    break;
                
                case 'f': // Faster
                    speed += 10;
                    if (speed > 100) speed = 100;
                    printf("Speed: %d%%\n", speed);
                    duty_cycle = speed * 10000; 
                    hardware_PWM(pi, SPEED_PIN, PWM_FREQ, duty_cycle);
                    break;

                case 'd': // Slower
                    speed -= 10;
                    if (speed < 0) speed = 0;
                    printf("Speed: %d%%\n", speed);
                    duty_cycle = speed * 10000;
                    hardware_PWM(pi, SPEED_PIN, PWM_FREQ, duty_cycle);
                    break;

                case 'q': // Quit
                    printf("Quitting...\n");
                    goto cleanup;
            }
        }
        
        usleep(10000); 
    }

cleanup:
    // 6. Cleanup and Exit
    stop_all_activity(pi);
    pigpio_stop(pi); // Disconnect from the daemon
    restore_terminal();
    return 0;
}
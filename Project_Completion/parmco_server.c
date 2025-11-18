/*
 * ======================================================================================
 * PROJECT:       PARMCO (Phone APP RP4 Motor Control) - Capstone Design I
 * FILE:          parmco_server.c
 * AUTHOR:        Group 1 (Assisted by AI)
 * DATE:          11/17/2025
 *
 * DESCRIPTION:
 * This program acts as the central control server for the PARMCO system.
 * It runs on a Raspberry Pi 4 and performs the following tasks:
 * 1. Establishes a Bluetooth RFCOMM server to communicate with an Android App.
 * 2. Drives a DC Motor via an L298N H-Bridge (PWM speed control + Direction).
 * 3. Reads an IR Speed Sensor via GPIO interrupts to calculate RPM.
 * 4. Implements a PID Closed-Loop Control system to maintain target RPM automatically.
 * 5. Parses incoming commands (Manual/Auto modes) and transmits telemetry data.
 *
 * DEPENDENCIES:
 * - pigpiod (GPIO Daemon)
 * - libbluetooth (BlueZ development headers)
 *
 * COMPILE INSTRUCTIONS:
 * gcc -o parmco_server parmco_server.c -lpigpiod_if2 -lbluetooth -pthread -Wall
 * ======================================================================================
 */

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <signal.h>
#include <sys/socket.h>
#include <fcntl.h>
#include <pigpiod_if2.h>  // Uses the pigpio daemon interface
#include <bluetooth/bluetooth.h>
#include <bluetooth/rfcomm.h>
#include <errno.h>
#include <ctype.h>

// --- GPIO PIN MAPPING (BCM Numbering) ---
#define MASTER_ON_PIN 17  // Master Enable for L298N
#define DIR_A_PIN     27  // Input 1 for H-Bridge
#define DIR_B_PIN     22  // Input 2 for H-Bridge
#define SPEED_PIN     18  // PWM Pin for Speed Control
#define SENSOR_PIN    23  // Input from IR Speed Sensor

// --- SYSTEM CONSTANTS ---
#define PWM_FREQ 1000           // 1 kHz PWM frequency for smooth motor operation
#define RFCOMM_CHANNEL 22       // Bluetooth Port (Must match Android App)
#define LOOP_PERIOD 1000000     // Main Control Loop Duration in Microseconds (1.0s)

// --- TUNING PARAMETERS ---
/*
 * GLITCH_FILTER_US: Ignores signal changes shorter than 100us.
 * Prevents electrical noise from registering as false RPM counts.
 * Valid for signals up to ~5kHz (well above our 12k RPM target).
 */
#define GLITCH_FILTER_US 100

/*
 * RPM_SMOOTHING: Low-pass filter factor (0.0 - 1.0).
 * 0.5 means new value is 50% previous average + 50% new reading.
 */
#define RPM_SMOOTHING 0.5

/*
 * MAX_PHYSICS_RPM: Hard cap for noise rejection.
 * If the sensor calculates > 12,000 RPM, we assume it's an error/glitch.
 */
#define MAX_PHYSICS_RPM 12000

// --- PID CONTROLLER GAINS ---
#define PID_KP 0.01           // Proportional Gain (Reaction to current error)
#define PID_KI 0.005          // Integral Gain (Reaction to accumulated error)
#define PID_KD 0.0            // Derivative Gain (Reaction to rate of change - not used here)
#define PID_MAX_INTEGRAL 50.0 // Anti-Windup: Max accumulated error positive
#define PID_MIN_INTEGRAL -50.0// Anti-Windup: Max accumulated error negative
#define MAX_CHANGE_PER_LOOP 5 // Safety: Max PWM % change allowed per second

// --- GLOBAL STATE VARIABLES (Volatile for ISR safety) ---
static volatile int keep_running = 1;      // Program termination flag
static volatile int revolution_count = 0;  // Raw ticks from sensor
static volatile int rpm = 0;               // Instantaneous RPM
static volatile int rpm_smooth = 0;        // Averaged RPM for stability
static int speed_percent = 0;              // Current PWM Duty Cycle (0-100)
static int pi;                             // Pigpio Daemon Handle

// Control Modes
typedef enum { MANUAL_MODE, AUTO_MODE } ControlMode;
static ControlMode current_mode = MANUAL_MODE;

static volatile int motor_running = 0;     // Motor State Flag
static volatile int desired_rpm = 0;       // Target RPM for PID
static double pid_integral = 0;            // Accumulator for I-term
static double pid_last_error = 0;          // Previous error for D-term

// --- COMMAND PARSER STATE MACHINE ---
// Used to handle fragmented Bluetooth packets (e.g., "r:1", "50", "0")
typedef enum { STATE_NORMAL, STATE_WAIT_COLON, STATE_READ_NUM } ParseState;
static ParseState p_state = STATE_NORMAL;
static char num_buffer[16];
static int num_buf_idx = 0;

/*
 * FUNCTION: rpm_callback
 * ----------------------
 * Interrupt Service Routine (ISR) triggered by the IR Sensor.
 * Executed every time the sensor pin goes High (Rising Edge).
 * logic: Increments the revolution counter. We assume 3 ticks = 1 full rotation.
 */
void rpm_callback(int pi, unsigned gpio, unsigned level, uint32_t tick) {
    revolution_count++;
}

/*
 * FUNCTION: stop_all_activity
 * ---------------------------
 * Safety Function. Immediately stops the motor, kills PWM,
 * and resets all state variables (RPM, PID errors, etc.).
 * Called on program exit or 'x' command.
 */
void stop_all_activity() {
    if (pi >= 0) {
        hardware_PWM(pi, SPEED_PIN, PWM_FREQ, 0); // 0% Duty Cycle
        gpio_write(pi, DIR_A_PIN, 0);
        gpio_write(pi, DIR_B_PIN, 0);
        gpio_write(pi, MASTER_ON_PIN, 0);
    }
    speed_percent = 0;
    revolution_count = 0;
    rpm = 0;
    rpm_smooth = 0;
    motor_running = 0;
    desired_rpm = 0;
    pid_integral = 0;
    pid_last_error = 0;
    p_state = STATE_NORMAL;
}

/*
 * FUNCTION: int_handler
 * ---------------------
 * Signal handler for Ctrl+C (SIGINT) or Termination (SIGTERM).
 * Allows the main loop to exit gracefully and clean up GPIOs.
 */
void int_handler(int sig) {
    keep_running = 0;
    printf("\nTermination signal received. Shutting down...\n");
}

/*
 * FUNCTION: update_pid_controller
 * -------------------------------
 * The "Brain" of the automatic mode.
 * Calculates the difference between Target RPM and Actual RPM,
 * then adjusts the motor speed (PWM) to minimize that error.
 */
void update_pid_controller() {
    // Only run logic if we are in Auto Mode and the motor is actually on
    if (current_mode != AUTO_MODE || !motor_running) return;

    // 1. Calculate Error
    double error = (double)desired_rpm - (double)rpm_smooth;

    // 2. Calculate Integral (Accumulated Error) with Anti-Windup Clamping
    pid_integral += error;
    if (pid_integral > PID_MAX_INTEGRAL) pid_integral = PID_MAX_INTEGRAL;
    if (pid_integral < PID_MIN_INTEGRAL) pid_integral = PID_MIN_INTEGRAL;

    // 3. Calculate Derivative (Rate of change of error)
    double derivative = error - pid_last_error;

    // 4. Compute Output (P + I + D)
    double output = (PID_KP * error) + (PID_KI * pid_integral) + (PID_KD * derivative);

    // 5. Limit the rate of change (prevents motor jerking)
    int change = (int)output;
    if (change > MAX_CHANGE_PER_LOOP) change = MAX_CHANGE_PER_LOOP;
    if (change < -MAX_CHANGE_PER_LOOP) change = -MAX_CHANGE_PER_LOOP;

    // 6. Apply to global speed
    speed_percent += change;
    if (speed_percent > 100) speed_percent = 100;
    if (speed_percent < 0) speed_percent = 0;

    // 7. Write to Hardware (Duty Cycle range 0 - 1,000,000)
    hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_percent * 10000);

    // 8. Store error for next loop
    pid_last_error = error;

    printf("PID LOG: Target=%d | Actual=%d | Error=%.1f | PWM Adj=%d | New Speed=%d%%\n",
           desired_rpm, rpm_smooth, error, change, speed_percent);
}

/*
 * FUNCTION: process_command
 * -------------------------
 * Executes a single character command received via Bluetooth.
 * s = Start Motor
 * x = Stop Motor
 * c = Clockwise
 * v = Counter-Clockwise
 * f = Faster (Manual)
 * d = Slower (Manual)
 * a = Auto Mode
 * m = Manual Mode
 * + / - = Increment/Decrement Target RPM
 */
void process_command(char cmd) {
    printf("CMD RECEIVED: '%c'\n", cmd);
    switch (cmd) {
        case 's': // START
            gpio_write(pi, MASTER_ON_PIN, 1);
            motor_running = 1;
            pid_integral = 0; pid_last_error = 0; // Reset PID memory
            break;
        case 'x': // STOP (Emergency)
            stop_all_activity();
            break;
        case 'c': // CLOCKWISE
            gpio_write(pi, DIR_A_PIN, 0); gpio_write(pi, DIR_B_PIN, 1);
            break;
        case 'v': // COUNTER-CLOCKWISE
            gpio_write(pi, DIR_A_PIN, 1); gpio_write(pi, DIR_B_PIN, 0);
            break;
        case 'f': // FASTER (Manual only)
            if (current_mode == MANUAL_MODE) {
                speed_percent += 10;
                if (speed_percent > 100) speed_percent = 100;
                hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_percent * 10000);
            }
            break;
        case 'd': // SLOWER (Manual only)
            if (current_mode == MANUAL_MODE) {
                speed_percent -= 10;
                if (speed_percent < 0) speed_percent = 0;
                hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_percent * 10000);
            }
            break;
        case 'a': // SWITCH TO AUTO
            current_mode = AUTO_MODE;
            motor_running = 1;
            gpio_write(pi, MASTER_ON_PIN, 1);
            // Ensure a direction is set if currently stopped
            if (gpio_read(pi, DIR_A_PIN) == 0 && gpio_read(pi, DIR_B_PIN) == 0) {
                 gpio_write(pi, DIR_A_PIN, 0); gpio_write(pi, DIR_B_PIN, 1);
            }
            if (desired_rpm == 0) desired_rpm = 500; // Default start speed
            pid_integral = 0; pid_last_error = 0;
            printf("Switched to AUTO_MODE (Target: %d)\n", desired_rpm);
            break;
        case 'm': // SWITCH TO MANUAL
            current_mode = MANUAL_MODE;
            printf("Switched to MANUAL_MODE\n");
            break;
        case '+': // INC TARGET
            if (current_mode == AUTO_MODE) desired_rpm += 100;
            printf("Target RPM: %d\n", desired_rpm);
            break;
        case '-': // DEC TARGET
            if (current_mode == AUTO_MODE) {
                desired_rpm -= 100;
                if(desired_rpm < 0) desired_rpm=0;
            }
            printf("Target RPM: %d\n", desired_rpm);
            break;
    }
}

/*
 * FUNCTION: parse_input_byte
 * --------------------------
 * State machine to parse specific RPM commands in the format "r:1500"
 * STATE_NORMAL: Processing single char commands.
 * STATE_WAIT_COLON: Saw 'r', waiting for ':'
 * STATE_READ_NUM: Reading digits until non-digit.
 */
void parse_input_byte(char c) {
    switch (p_state) {
        case STATE_NORMAL:
            if (c == 'r') { p_state = STATE_WAIT_COLON; }
            else { process_command(c); }
            break;

        case STATE_WAIT_COLON:
            if (c == ':') {
                p_state = STATE_READ_NUM;
                num_buf_idx = 0;
                memset(num_buffer, 0, sizeof(num_buffer));
            }
            else {
                // If not a colon, treat 'r' as a glitch and process this char normally
                p_state = STATE_NORMAL;
                process_command(c);
            }
            break;

        case STATE_READ_NUM:
            if (isdigit(c)) {
                if (num_buf_idx < 15) num_buffer[num_buf_idx++] = c;
            } else {
                // End of number reached
                if (num_buf_idx > 0) {
                    desired_rpm = atoi(num_buffer);
                    printf("PARSED SPECIFIC RPM TARGET: %d\n", desired_rpm);

                    // Auto-switch to Auto Mode if we receive a target
                    if (current_mode != AUTO_MODE) {
                        current_mode = AUTO_MODE;
                        motor_running = 1;
                        gpio_write(pi, MASTER_ON_PIN, 1);
                        if (gpio_read(pi, DIR_A_PIN) == 0 && gpio_read(pi, DIR_B_PIN) == 0) {
                             gpio_write(pi, DIR_A_PIN, 0); gpio_write(pi, DIR_B_PIN, 1);
                        }
                    }
                }
                p_state = STATE_NORMAL;
                // If the delimiter wasn't a newline, it might be a new command
                if (c != '\n' && c != '\r') process_command(c);
            }
            break;
    }
}

// ======================================================================================
// MAIN FUNCTION
// ======================================================================================
int main() {
    setbuf(stdout, NULL); // Disable stdout buffering so printf shows up immediately

    // Register signal handlers for clean exit
    signal(SIGINT, int_handler);
    signal(SIGTERM, int_handler);

    // Initialize connection to pigpio daemon
    pi = pigpio_start(NULL, NULL);
    if (pi < 0) {
        fprintf(stderr, "Failed to connect to pigpio daemon. Is it running?\n");
        return 1;
    }

    // --- GPIO SETUP ---
    set_mode(pi, MASTER_ON_PIN, PI_OUTPUT);
    set_mode(pi, DIR_A_PIN, PI_OUTPUT);
    set_mode(pi, DIR_B_PIN, PI_OUTPUT);
    set_mode(pi, SPEED_PIN, PI_OUTPUT);

    // --- SENSOR CONFIGURATION ---
    set_mode(pi, SENSOR_PIN, PI_INPUT);
    set_pull_up_down(pi, SENSOR_PIN, PI_PUD_UP); // Internal Pull-Up
    set_glitch_filter(pi, SENSOR_PIN, GLITCH_FILTER_US); // Hardware debouncing

    stop_all_activity(); // Ensure motor is off at start

    // Attach Interrupt: Trigger rpm_callback on Rising Edge
    callback(pi, SENSOR_PIN, RISING_EDGE, rpm_callback);

    // --- BLUETOOTH SOCKET SETUP ---
    struct sockaddr_rc loc_addr = { 0 }, rem_addr = { 0 };
    char buf[1024] = { 0 };
    char data_str[64];
    int server_sock, client_sock;
    socklen_t opt = sizeof(rem_addr);

    // Create RFCOMM socket
    server_sock = socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);

    // Bind to local Bluetooth adapter
    loc_addr.rc_family = AF_BLUETOOTH;
    loc_addr.rc_bdaddr = *BDADDR_ANY;
    loc_addr.rc_channel = (uint8_t) RFCOMM_CHANNEL;
    bind(server_sock, (struct sockaddr *)&loc_addr, sizeof(loc_addr));

    // Listen for connections (Queue size 1)
    listen(server_sock, 1);

    // Set socket to NON-BLOCKING (Allows loop to run while waiting for connection)
    fcntl(server_sock, F_SETFL, O_NONBLOCK);
    printf("Server initialized. Waiting on Channel %d...\n", RFCOMM_CHANNEL);

    // --- OUTER LOOP: Connection Management ---
    while (keep_running) {
        // Accept new connection
        client_sock = accept(server_sock, (struct sockaddr *)&rem_addr, &opt);

        if (client_sock < 0) {
            // No connection yet, sleep briefly and retry
            if (errno == EAGAIN || errno == EWOULDBLOCK) { usleep(100000); continue; }
            if (keep_running) perror("Accept failed"); continue;
        }

        // Connection Established
        ba2str(&rem_addr.rc_bdaddr, buf);
        printf("Bluetooth Connected: %s\n", buf);
        fcntl(client_sock, F_SETFL, O_NONBLOCK); // Set Client socket to non-blocking

        current_mode = MANUAL_MODE;
        stop_all_activity();

        uint32_t last_loop_tick = get_current_tick(pi);
        uint32_t last_send_tick = get_current_tick(pi);

        // --- INNER LOOP: Active Control & Communication ---
        while (keep_running) {
            uint32_t current_tick = get_current_tick(pi);

            // 1. CONTROL LOGIC (Executes once every LOOP_PERIOD / 1.0s)
            if ((current_tick - last_loop_tick) >= LOOP_PERIOD) {
                // Calculate RPM
                double revs = (double)revolution_count / 3.0; // 3 blades on fan/sensor
                double seconds = (double)LOOP_PERIOD / 1000000.0;
                int raw_rpm = (int)((revs / seconds) * 60.0);

                // Noise Filtering
                if (raw_rpm > MAX_PHYSICS_RPM) {
                     printf("NOISE DETECTED: %d RPM ignored\n", raw_rpm);
                } else {
                     rpm = raw_rpm;
                     // Weighted average smoothing
                     rpm_smooth = (int)((RPM_SMOOTHING * rpm_smooth) + ((1.0 - RPM_SMOOTHING) * raw_rpm));
                }

                revolution_count = 0; // Reset counter for next second
                last_loop_tick = current_tick;

                // Run PID calculation
                update_pid_controller();
            }

            // 2. TELEMETRY (Executes every 500ms)
            if ((current_tick - last_send_tick) >= 500000) {
                snprintf(data_str, sizeof(data_str), "RPM:%d\n", rpm_smooth);
                int write_ret = write(client_sock, data_str, strlen(data_str));

                // Detect Disconnection
                if (write_ret < 0) {
                     if (errno != EAGAIN && errno != EWOULDBLOCK) {
                        printf("Client disconnected (Write Error).\n");
                        close(client_sock); break; // Break to outer loop
                     }
                }
                last_send_tick = current_tick;
            }

            // 3. READ INPUT (Non-blocking)
            int bytes_read = read(client_sock, buf, sizeof(buf) - 1);
            if (bytes_read > 0) {
                for (int i = 0; i < bytes_read; i++) {
                    parse_input_byte(buf[i]); // Feed bytes to state machine
                }
            } else if (bytes_read == 0) {
                printf("Client disconnected (EOF).\n");
                close(client_sock); break;
            }

            usleep(10000); // Sleep 10ms to save CPU
        }

        stop_all_activity(); // Safety stop on disconnect
    }

    // --- CLEANUP ---
    stop_all_activity();
    close(server_sock);
    pigpio_stop(pi);
    printf("System Shutdown Complete.\n");
    return 0;
}

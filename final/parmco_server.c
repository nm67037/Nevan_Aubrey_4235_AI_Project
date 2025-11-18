/*
 * ======================================================================================
 * PARMCO - Bluetooth Motor Control Server (IR Sensor Version)
 * ======================================================================================
 * AI Author: Gemini
 * Date: 11/18/2025
 *
 * DESCRIPTION:
 * This program turns a Raspberry Pi into a Bluetooth Server to control a DC Motor.
 *
 * HARDWARE CHANGES (IR VERSION):
 * - Uses an IR Obstacle/Line Sensor (TCRT5000 or similar) on GPIO 23.
 * - Detects transitions between Reflective (White/Tape) and Non-Reflective (Black/Air).
 *
 * COMPILATION:
 * gcc -o parmco_server parmco_server.c -lpigpiod_if2 -lbluetooth -pthread
 * ======================================================================================
 */

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <signal.h>
#include <sys/socket.h>
#include <fcntl.h>
#include <pigpiod_if2.h>
#include <bluetooth/bluetooth.h>
#include <bluetooth/rfcomm.h>
#include <errno.h>
#include <ctype.h>

// -------------------------------------------------------------------------
// GPIO PIN DEFINITIONS
// -------------------------------------------------------------------------
#define MASTER_ON_PIN 17
#define DIR_A_PIN     27
#define DIR_B_PIN     22
#define SPEED_PIN     18
#define SENSOR_PIN    23  // IR Sensor Digital Output (D0)

// -------------------------------------------------------------------------
// TUNING & IR CONFIGURATION
// -------------------------------------------------------------------------
#define PWM_FREQ 1000
#define RFCOMM_CHANNEL 22
#define LOOP_PERIOD 1000000 // 1.0 Seconds

// *** CRITICAL IR SETTING ***
// How many times does the sensor trigger per full wheel turn?
// - Reflective Tape on tire = 1
// - Standard Black/White Encoder Disc = 20 (usually)
// - 3-Magnet setup = 3
#define PULSES_PER_REV 3.0  

// Filter (Microseconds): IR sensors can "flicker" at the edge of a white line.
// 100us ignores extremely fast light flickers.
#define GLITCH_FILTER_US 100

#define RPM_SMOOTHING 0.5
#define MAX_PHYSICS_RPM 12000

#define PID_KP 0.01
#define PID_KI 0.005
#define PID_KD 0.0
#define PID_MAX_INTEGRAL 50.0
#define PID_MIN_INTEGRAL -50.0
#define MAX_CHANGE_PER_LOOP 5

// -------------------------------------------------------------------------
// GLOBAL VARIABLES
// -------------------------------------------------------------------------
static volatile int keep_running = 1;
static volatile int revolution_count = 0;
static volatile int rpm = 0;
static volatile int rpm_smooth = 0;
static int speed_percent = 0;
static int pi;

typedef enum { MANUAL_MODE, AUTO_MODE } ControlMode;
static ControlMode current_mode = MANUAL_MODE;

static volatile int motor_running = 0;
static volatile int desired_rpm = 0;
static double pid_integral = 0;
static double pid_last_error = 0;

// --- Command Parser ---
typedef enum { STATE_NORMAL, STATE_WAIT_COLON, STATE_READ_NUM } ParseState;
static ParseState p_state = STATE_NORMAL;
static char num_buffer[16];
static int num_buf_idx = 0;

// -------------------------------------------------------------------------
// HELPER FUNCTIONS
// -------------------------------------------------------------------------

/**
 * RPM Callback (IR Sensor)
 * Triggered when the IR sensor detects a change in reflectivity.
 * (e.g., White tape passes sensor, or Encoder slot passes).
 */
void rpm_callback(int pi, unsigned gpio, unsigned level, uint32_t tick) {
    revolution_count++;
}

void stop_all_activity() {
    if (pi >= 0) {
        hardware_PWM(pi, SPEED_PIN, PWM_FREQ, 0);
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

void int_handler(int sig) {
    keep_running = 0;
    printf("Termination signal received...\n");
}

// --- PID CONTROL ---
void update_pid_controller() {
    if (current_mode != AUTO_MODE || !motor_running) return;

    double error = (double)desired_rpm - (double)rpm_smooth;
    pid_integral += error;

    if (pid_integral > PID_MAX_INTEGRAL) pid_integral = PID_MAX_INTEGRAL;
    if (pid_integral < PID_MIN_INTEGRAL) pid_integral = PID_MIN_INTEGRAL;

    double derivative = error - pid_last_error;
    double output = (PID_KP * error) + (PID_KI * pid_integral) + (PID_KD * derivative);

    int change = (int)output;
    if (change > MAX_CHANGE_PER_LOOP) change = MAX_CHANGE_PER_LOOP;
    if (change < -MAX_CHANGE_PER_LOOP) change = -MAX_CHANGE_PER_LOOP;

    speed_percent += change;
    if (speed_percent > 100) speed_percent = 100;
    if (speed_percent < 0) speed_percent = 0;

    hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_percent * 10000);
    pid_last_error = error;

    printf("PID: Tgt=%d Act=%d Err=%.1f Chg=%d Speed=%d%%\n",
           desired_rpm, rpm_smooth, error, change, speed_percent);
}

// --- COMMAND PROCESSING ---
void process_command(char cmd) {
    printf("CMD EXEC: '%c'\n", cmd);
    switch (cmd) {
        case 's': // Start
            gpio_write(pi, MASTER_ON_PIN, 1);
            motor_running = 1;
            pid_integral = 0; pid_last_error = 0;
            break;
        case 'x': // Stop
            stop_all_activity();
            break;
        case 'c': // Clockwise
            gpio_write(pi, DIR_A_PIN, 0); gpio_write(pi, DIR_B_PIN, 1);
            break;
        case 'v': // Counter-Clockwise
            gpio_write(pi, DIR_A_PIN, 1); gpio_write(pi, DIR_B_PIN, 0);
            break;
        case 'f': // Faster
            if (current_mode == MANUAL_MODE) {
                speed_percent += 10; if (speed_percent > 100) speed_percent = 100;
                hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_percent * 10000);
            }
            break;
        case 'd': // Slower
            if (current_mode == MANUAL_MODE) {
                speed_percent -= 10; if (speed_percent < 0) speed_percent = 0;
                hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_percent * 10000);
            }
            break;
        case 'a': // Auto Mode
            current_mode = AUTO_MODE;
            motor_running = 1;
            gpio_write(pi, MASTER_ON_PIN, 1);
            if (gpio_read(pi, DIR_A_PIN) == 0 && gpio_read(pi, DIR_B_PIN) == 0) {
                 gpio_write(pi, DIR_A_PIN, 0); gpio_write(pi, DIR_B_PIN, 1);
            }
            if (desired_rpm == 0) desired_rpm = 500;
            pid_integral = 0; pid_last_error = 0;
            printf("Switched to AUTO_MODE (Target: %d)\n", desired_rpm);
            break;
        case 'm': // Manual Mode
            current_mode = MANUAL_MODE;
            printf("Switched to MANUAL_MODE\n");
            break;
        case '+':
            if (current_mode == AUTO_MODE) desired_rpm += 100;
            printf("Target: %d\n", desired_rpm);
            break;
        case '-':
            if (current_mode == AUTO_MODE) { desired_rpm -= 100; if(desired_rpm<0) desired_rpm=0; }
            printf("Target: %d\n", desired_rpm);
            break;
    }
}

void parse_input_byte(char c) {
    switch (p_state) {
        case STATE_NORMAL:
            if (c == 'r') { p_state = STATE_WAIT_COLON; }
            else { process_command(c); }
            break;
        case STATE_WAIT_COLON:
            if (c == ':') { p_state = STATE_READ_NUM; num_buf_idx = 0; memset(num_buffer, 0, sizeof(num_buffer)); }
            else { p_state = STATE_NORMAL; process_command(c); }
            break;
        case STATE_READ_NUM:
            if (isdigit(c)) {
                if (num_buf_idx < 15) num_buffer[num_buf_idx++] = c;
            } else {
                if (num_buf_idx > 0) {
                    desired_rpm = atoi(num_buffer);
                    printf("PARSED RPM TARGET: %d\n", desired_rpm);
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
                if (c != '\n' && c != '\r') process_command(c);
            }
            break;
    }
}

// -------------------------------------------------------------------------
// MAIN LOOP
// -------------------------------------------------------------------------
int main() {
    setbuf(stdout, NULL);
    signal(SIGINT, int_handler);
    signal(SIGTERM, int_handler);

    pi = pigpio_start(NULL, NULL);
    if (pi < 0) return 1;

    set_mode(pi, MASTER_ON_PIN, PI_OUTPUT);
    set_mode(pi, DIR_A_PIN, PI_OUTPUT);
    set_mode(pi, DIR_B_PIN, PI_OUTPUT);
    set_mode(pi, SPEED_PIN, PI_OUTPUT);

    // --- IR SENSOR CONFIG ---
    // Most IR modules have a comparator (LM393) that drives D0 High/Low actively.
    // However, enabling Pull-Up is safe for most modules and prevents floating
    // if the wire disconnects.
    set_mode(pi, SENSOR_PIN, PI_INPUT);
    set_pull_up_down(pi, SENSOR_PIN, PI_PUD_UP);
    set_glitch_filter(pi, SENSOR_PIN, GLITCH_FILTER_US);

    stop_all_activity();

    // IR Sensors toggle when reflectivity changes.
    // RISING_EDGE counts when it goes from Dark->Light (or vice versa depending on module).
    callback(pi, SENSOR_PIN, RISING_EDGE, rpm_callback);

    struct sockaddr_rc loc_addr = { 0 }, rem_addr = { 0 };
    char buf[1024] = { 0 };
    char data_str[64];
    int server_sock, client_sock;
    socklen_t opt = sizeof(rem_addr);

    server_sock = socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);
    loc_addr.rc_family = AF_BLUETOOTH;
    loc_addr.rc_bdaddr = *BDADDR_ANY;
    loc_addr.rc_channel = (uint8_t) RFCOMM_CHANNEL;
    bind(server_sock, (struct sockaddr *)&loc_addr, sizeof(loc_addr));
    listen(server_sock, 1);
    fcntl(server_sock, F_SETFL, O_NONBLOCK);
    printf("Server ready on Channel %d\n", RFCOMM_CHANNEL);

    while (keep_running) {
        client_sock = accept(server_sock, (struct sockaddr *)&rem_addr, &opt);
        if (client_sock < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) { usleep(100000); continue; }
            if (keep_running) perror("Accept failed"); continue;
        }

        ba2str(&rem_addr.rc_bdaddr, buf);
        printf("Connected: %s\n", buf);
        fcntl(client_sock, F_SETFL, O_NONBLOCK);

        current_mode = MANUAL_MODE;
        stop_all_activity();

        uint32_t last_loop_tick = get_current_tick(pi);
        uint32_t last_send_tick = get_current_tick(pi);

        while (keep_running) {
            uint32_t current_tick = get_current_tick(pi);

            if ((current_tick - last_loop_tick) >= LOOP_PERIOD) {
                // --- MATH UPDATE FOR IR SENSOR ---
                // Uses PULSES_PER_REV to calculate correctly for your tape/wheel
                double revs = (double)revolution_count / PULSES_PER_REV;
                double seconds = (double)LOOP_PERIOD / 1000000.0;
                int raw_rpm = (int)((revs / seconds) * 60.0);

                if (raw_rpm > MAX_PHYSICS_RPM) {
                     printf("NOISE IGNORED: %d\n", raw_rpm);
                } else {
                     rpm = raw_rpm;
                     rpm_smooth = (int)((RPM_SMOOTHING * rpm_smooth) + ((1.0 - RPM_SMOOTHING) * raw_rpm));
                }

                revolution_count = 0;
                last_loop_tick = current_tick;
                update_pid_controller();
            }

            if ((current_tick - last_send_tick) >= 500000) {
                snprintf(data_str, sizeof(data_str), "RPM:%d\n", rpm_smooth);
                int write_ret = write(client_sock, data_str, strlen(data_str));
                if (write_ret < 0) {
                     if (errno != EAGAIN && errno != EWOULDBLOCK) {
                        printf("Client disconnected.\n");
                        close(client_sock); break;
                     }
                }
                last_send_tick = current_tick;
            }

            int bytes_read = read(client_sock, buf, sizeof(buf) - 1);
            if (bytes_read > 0) {
                for (int i = 0; i < bytes_read; i++) {
                    parse_input_byte(buf[i]);
                }
            } else if (bytes_read == 0) {
                printf("Client disconnected (EOF).\n");
                close(client_sock); break;
            }
            usleep(10000);
        }
        stop_all_activity();
    }

    stop_all_activity();
    close(server_sock);
    pigpio_stop(pi);
    return 0;
}

/*
 * PARMCO - Bluetooth Server (Final Hybrid Feed-Forward Version)
 * AI Author: Gemini
 * Date: 11/17/2025
 *
 * STRATEGY:
 * - Feed-Forward: Instantly sets motor to estimated power for target RPM.
 * - PID Trim: Uses feedback to fine-tune speed (+/- 20% authority).
 * - Safety: If sensor fails (RPM=0), motor stays at Feed-Forward speed.
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

#define MASTER_ON_PIN 17 
#define DIR_A_PIN     27
#define DIR_B_PIN     22
#define SPEED_PIN     18 
#define SENSOR_PIN    23 

#define PWM_FREQ 1000
#define RFCOMM_CHANNEL 22
#define LOOP_PERIOD 1000000 // 1.0 Second Loop

// --- Tuning ---
#define GLITCH_FILTER_US 2000  
#define RPM_SMOOTHING    0.5   

// --- FEED-FORWARD CONSTANTS ---
// This limits the PID. It can only add/remove 20% power max.
#define PID_MAX_ADJUST   20
#define PID_MIN_ADJUST   -20
#define PID_KP           0.1  // Gentle correction

// --- Global Variables ---
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
static int pid_adjustment = 0; // The "+/-" trim from the sensor

// --- State Machine Parser ---
typedef enum { STATE_NORMAL, STATE_WAIT_COLON, STATE_READ_NUM } ParseState;
static ParseState p_state = STATE_NORMAL;
static char num_buffer[16];
static int num_buf_idx = 0;

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
    pid_adjustment = 0;
    p_state = STATE_NORMAL;
}

void int_handler(int sig) {
    keep_running = 0;
    printf("Termination signal received...\n");
}

int clamp_duty(int d) {
    if (d > 1000000) return 1000000;
    if (d < 0) return 0;
    return d;
}

// --- ESTIMATOR FUNCTION ---
// Returns the baseline power % needed for a target RPM.
// You can tune these "guess" numbers based on your motor!
int get_feedforward_duty(int target) {
    if (target <= 0) return 0;
    if (target <= 500) return 40;  // Start power
    if (target <= 800) return 50;
    if (target <= 1200) return 60;
    if (target <= 1800) return 75;
    if (target <= 2200) return 85;
    return 100; // > 2200 RPM
}

void update_control_loop() {
    if (current_mode != AUTO_MODE || !motor_running) return;

    // 1. Get Baseline Power (Open Loop)
    int base_duty = get_feedforward_duty(desired_rpm);

    // 2. Calculate PID Trim (Closed Loop)
    // Only run PID if we have a valid sensor reading (>0) or we are trying to stop
    if (rpm_smooth > 0 || desired_rpm == 0) {
        double error = (double)desired_rpm - (double)rpm_smooth;
        
        // Simple P-Controller updates the adjustment accumulator
        // We divide by 10 to make it gentle (Integrator-like behavior)
        int step = (int)(error * PID_KP * 0.5); 
        
        pid_adjustment += step;
    } else {
        // SAFETY: If RPM is 0 but we want to move, assume sensor fail/stall.
        // Do NOT wind up the PID. Just stick to the FeedForward base.
        printf("WARN: No RPM signal. Holding Baseline Power.\n");
        // Slowly decay any previous adjustment to return to safe baseline
        if (pid_adjustment > 0) pid_adjustment--;
        if (pid_adjustment < 0) pid_adjustment++;
    }

    // 3. Clamp the Trim
    // Don't let the feedback change the power by more than +/- 20%
    if (pid_adjustment > PID_MAX_ADJUST) pid_adjustment = PID_MAX_ADJUST;
    if (pid_adjustment < PID_MIN_ADJUST) pid_adjustment = PID_MIN_ADJUST;

    // 4. Combine
    speed_percent = base_duty + pid_adjustment;

    // 5. Final Safety Clamps
    if (speed_percent > 100) speed_percent = 100;
    if (speed_percent < 0) speed_percent = 0;

    hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_percent * 10000);
    
    printf("AUTO: Tgt=%d Act=%d | Base=%d%% Trim=%d%% | Final=%d%%\n", 
           desired_rpm, rpm_smooth, base_duty, pid_adjustment, speed_percent);
}

void process_command(char cmd) {
    printf("CMD EXEC: '%c'\n", cmd);
    switch (cmd) {
        case 's':
            gpio_write(pi, MASTER_ON_PIN, 1);
            motor_running = 1;
            pid_adjustment = 0;
            break;
        case 'x': stop_all_activity(); break;
        case 'c': gpio_write(pi, DIR_A_PIN, 0); gpio_write(pi, DIR_B_PIN, 1); break;
        case 'v': gpio_write(pi, DIR_A_PIN, 1); gpio_write(pi, DIR_B_PIN, 0); break;
        case 'f':
            if (current_mode == MANUAL_MODE) {
                speed_percent += 10; if (speed_percent > 100) speed_percent = 100;
                hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_percent * 10000);
            }
            break;
        case 'd': 
            if (current_mode == MANUAL_MODE) {
                speed_percent -= 10; if (speed_percent < 0) speed_percent = 0;
                hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_percent * 10000);
            }
            break;
        case 'a': 
            current_mode = AUTO_MODE;
            motor_running = 1; 
            gpio_write(pi, MASTER_ON_PIN, 1); 
            if (gpio_read(pi, DIR_A_PIN) == 0 && gpio_read(pi, DIR_B_PIN) == 0) {
                 gpio_write(pi, DIR_A_PIN, 0); gpio_write(pi, DIR_B_PIN, 1);
            }
            if (desired_rpm == 0) desired_rpm = 500; 
            pid_adjustment = 0;
            printf("Switched to AUTO_MODE (Target: %d)\n", desired_rpm);
            break;
        case 'm':
            current_mode = MANUAL_MODE;
            printf("Switched to MANUAL_MODE\n");
            break;
        case '+':
            if (current_mode == AUTO_MODE) desired_rpm += 100;
            break;
        case '-':
            if (current_mode == AUTO_MODE) { desired_rpm -= 100; if(desired_rpm<0) desired_rpm=0; }
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
                    pid_adjustment = 0; // Reset trim on new target
                }
                p_state = STATE_NORMAL;
                if (c != '\n' && c != '\r') process_command(c);
            }
            break;
    }
}

int main() {
    setbuf(stdout, NULL); // Unbuffered logs

    signal(SIGINT, int_handler);
    signal(SIGTERM, int_handler);

    pi = pigpio_start(NULL, NULL); 
    if (pi < 0) return 1;

    set_mode(pi, MASTER_ON_PIN, PI_OUTPUT);
    set_mode(pi, DIR_A_PIN, PI_OUTPUT);
    set_mode(pi, DIR_B_PIN, PI_OUTPUT);
    set_mode(pi, SPEED_PIN, PI_OUTPUT); 
    
    set_mode(pi, SENSOR_PIN, PI_INPUT);
    set_pull_up_down(pi, SENSOR_PIN, PI_PUD_UP);
    // Filter set to 2ms to reject noise but allow signal
    set_glitch_filter(pi, SENSOR_PIN, GLITCH_FILTER_US); 
    
    stop_all_activity();
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

            // --- CONTROL LOOP (1 Second) ---
            if ((current_tick - last_loop_tick) >= LOOP_PERIOD) {
                double revs = (double)revolution_count / 3.0;
                double seconds = (double)LOOP_PERIOD / 1000000.0;
                int raw_rpm = (int)((revs / seconds) * 60.0);
                
                // Hard Cap Filter
                if (raw_rpm > 4000) {
                     printf("NOISE IGNORED: %d\n", raw_rpm);
                } else {
                     rpm = raw_rpm;
                     rpm_smooth = (int)((RPM_SMOOTHING * rpm_smooth) + ((1.0 - RPM_SMOOTHING) * raw_rpm));
                }

                revolution_count = 0;
                last_loop_tick = current_tick;
                update_control_loop();
            }

            // --- SEND DATA (500ms) ---
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
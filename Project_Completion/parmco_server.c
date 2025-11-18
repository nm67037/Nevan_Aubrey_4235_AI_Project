/*
 * PARMCO - Bluetooth Server (Final Project)
 * AI Author: Gemini
 * Date: 11/17/2025
 *
 * FEATURES:
 * - Manual Mode: Direct control of duty cycle (s, x, f, d).
 * - Auto Mode: PI Feedback Loop to maintain Target RPM.
 * - Reports RPM and Mode back to App.
 * - Fixed write() error handling to prevent disconnects.
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

// --- Pin Definitions ---
#define MASTER_ON_PIN 17 
#define DIR_A_PIN     27
#define DIR_B_PIN     22
#define SPEED_PIN     18 
#define SENSOR_PIN    23 

#define PWM_FREQ 1000
#define RFCOMM_CHANNEL 22

// --- Control Constants ---
// UPDATE SPEED: 250ms (4 times per second)
#define LOOP_PERIOD 250000 

// PID GAINS
#define KP 0.05  
#define KI 0.01

// --- Global Variables ---
static volatile int keep_running = 1;     
static volatile int revolution_count = 0; 
static volatile int rpm = 0;              
static int pi;                            

// State Variables
static int mode_auto = 0;   // 0 = Manual, 1 = Automatic
static int target_rpm = 0;  // The desired RPM
static int manual_duty = 0; // Power in Manual Mode
static int current_duty = 0;// Actual current duty cycle
static double integral_error = 0; 

void rpm_callback(int pi, unsigned gpio, unsigned level, uint32_t tick) {
    if (level == 1) {
        revolution_count++;
    }
}

void stop_all_activity() {
    if (pi >= 0) {
        hardware_PWM(pi, SPEED_PIN, PWM_FREQ, 0); 
        gpio_write(pi, DIR_A_PIN, 0);
        gpio_write(pi, DIR_B_PIN, 0);
        gpio_write(pi, MASTER_ON_PIN, 0);
    }
    rpm = 0;
    revolution_count = 0;
    mode_auto = 0;
    target_rpm = 0;
    manual_duty = 0;
    current_duty = 0;
    integral_error = 0;
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

void process_command(char cmd) {
    printf("APP->PI: '%c'\n", cmd);
    
    switch (cmd) {
        case 's': // Start
             gpio_write(pi, MASTER_ON_PIN, 1); 
             mode_auto = 0;
             manual_duty = 300000; // Start at 30%
             current_duty = manual_duty;
             hardware_PWM(pi, SPEED_PIN, PWM_FREQ, current_duty);
             break;
             
        case 'x': // Stop
             stop_all_activity();
             break;
             
        case 'c': gpio_write(pi, DIR_A_PIN, 0); gpio_write(pi, DIR_B_PIN, 1); break;
        case 'v': gpio_write(pi, DIR_A_PIN, 1); gpio_write(pi, DIR_B_PIN, 0); break;

        case 'f': // Faster
            if (!mode_auto) {
                manual_duty += 50000; 
                manual_duty = clamp_duty(manual_duty);
                current_duty = manual_duty;
                hardware_PWM(pi, SPEED_PIN, PWM_FREQ, current_duty);
            }
            break;
        case 'd': // Slower
            if (!mode_auto) {
                manual_duty -= 50000; 
                manual_duty = clamp_duty(manual_duty);
                current_duty = manual_duty;
                hardware_PWM(pi, SPEED_PIN, PWM_FREQ, current_duty);
            }
            break;

        case 'a': // Auto Mode
            mode_auto = 1;
            target_rpm = rpm; 
            integral_error = 0; 
            printf("Switched to AUTO mode. Target: %d\n", target_rpm);
            break;
            
        case 'm': // Manual Mode
            mode_auto = 0;
            manual_duty = current_duty; 
            printf("Switched to MANUAL mode.\n");
            break;
            
        case '+': // Target Up
            if (mode_auto) target_rpm += 100;
            break;
            
        case '-': // Target Down
            if (mode_auto) {
                target_rpm -= 100;
                if (target_rpm < 0) target_rpm = 0;
            }
            break;
    }
}

int main() {
    signal(SIGINT, int_handler);
    signal(SIGTERM, int_handler);

    pi = pigpio_start(NULL, NULL); 
    if (pi < 0) return 1;

    set_mode(pi, MASTER_ON_PIN, PI_OUTPUT);
    set_mode(pi, DIR_A_PIN, PI_OUTPUT);
    set_mode(pi, DIR_B_PIN, PI_OUTPUT);
    set_mode(pi, SPEED_PIN, PI_OUTPUT); 
    set_mode(pi, SENSOR_PIN, PI_INPUT);
    set_pull_up_down(pi, SENSOR_PIN, PI_PUD_DOWN);
    stop_all_activity();

    callback(pi, SENSOR_PIN, RISING_EDGE, rpm_callback);

    // Bluetooth Setup
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

    printf("Server ready on Channel %d. Loop Period: %dus\n", RFCOMM_CHANNEL, LOOP_PERIOD);

    while (keep_running) {
        client_sock = accept(server_sock, (struct sockaddr *)&rem_addr, &opt);
        
        if (client_sock < 0) {
            usleep(100000); 
            continue;
        }

        ba2str(&rem_addr.rc_bdaddr, buf);
        printf("Accepted connection from %s\n", buf);
        fcntl(client_sock, F_SETFL, O_NONBLOCK);

        uint32_t last_loop_tick = get_current_tick(pi);
        uint32_t last_send_tick = get_current_tick(pi);

        while (keep_running) {
            uint32_t current_tick = get_current_tick(pi);

            // --- CONTROL LOOP (250ms) ---
            if ((current_tick - last_loop_tick) >= LOOP_PERIOD) {
                
                double revs = (double)revolution_count / 3.0;
                double seconds = (double)LOOP_PERIOD / 1000000.0;
                rpm = (int)((revs / seconds) * 60.0);
                
                revolution_count = 0; 

                if (mode_auto && target_rpm > 0) {
                    int error = target_rpm - rpm;
                    integral_error += error;
                    
                    if (integral_error > 500000) integral_error = 500000;
                    if (integral_error < -500000) integral_error = -500000;

                    double adjustment = (error * KP) + (integral_error * KI);
                    
                    current_duty += (int)adjustment;
                    current_duty = clamp_duty(current_duty);
                    
                    hardware_PWM(pi, SPEED_PIN, PWM_FREQ, current_duty);
                    
                    printf("Auto: Tgt=%d Act=%d Err=%d Adj=%d Duty=%d\n", 
                           target_rpm, rpm, error, (int)adjustment, current_duty);
                }
                last_loop_tick = current_tick;
            }

            // --- DATA REPORTING (500ms) ---
            if ((current_tick - last_send_tick) >= 500000) { 
                snprintf(data_str, sizeof(data_str), "DATA:%d,%d,%d\n", rpm, target_rpm, mode_auto);
                
                int write_ret = write(client_sock, data_str, strlen(data_str));
                if (write_ret < 0) {
                     if (errno != EAGAIN && errno != EWOULDBLOCK) {
                        printf("Client disconnected.\n");
                        close(client_sock); 
                        break; 
                     }
                }
                last_send_tick = current_tick;
            }

            // --- READ COMMANDS ---
            int bytes_read = read(client_sock, buf, sizeof(buf) - 1);
            if (bytes_read > 0) {
                for (int i = 0; i < bytes_read; i++) {
                    process_command(buf[i]);
                }
            } else if (bytes_read == 0) {
                printf("Client disconnected (EOF).\n");
                close(client_sock); 
                break; 
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
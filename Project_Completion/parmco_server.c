/*
 * PARMCO - Bluetooth Server with PID Control
 * AI Author: Gemini
 * Date: 11/17/2025
 *
 * CHANGES (v3):
 * - FIX 1: Corrected integer division bug in RPM calculation.
 * - FIX 2: Made RPM send logic more robust.
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

// --- Pin Definitions ---
#define MASTER_ON_PIN 17
#define DIR_A_PIN 27
#define DIR_B_PIN 22
#define SPEED_PIN 18
#define SENSOR_PIN 23

#define PWM_FREQ 1000
#define RFCOMM_CHANNEL 22

// --- Control Mode State ---
typedef enum {
    MANUAL_MODE,
    AUTO_MODE
} ControlMode;

// --- PID Constants (THESE MUST BE TUNED!) ---
#define PID_KP 0.5
#define PID_KI 0.2
#define PID_KD 0.1
#define PID_MAX_INTEGRAL 100.0
#define PID_MIN_INTEGRAL -100.0

// --- Global Variables ---
static volatile int keep_running = 1;
static volatile int revolution_count = 0;
static volatile int rpm = 0;
static int speed_percent = 0;
static int pi;

// --- State & PID Variables ---
static ControlMode current_mode = MANUAL_MODE;
static volatile int motor_running = 0;
static volatile int desired_rpm = 0;
static double pid_integral = 0;
static double pid_last_error = 0;


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
    speed_percent = 0;
    revolution_count = 0;
    rpm = 0;
    motor_running = 0;
    desired_rpm = 0;
    pid_integral = 0;
    pid_last_error = 0;
}

void int_handler(int sig) {
    keep_running = 0;
    printf("Termination signal received, shutting down...\n");
}

void update_pid_controller() {
    if (current_mode != AUTO_MODE || !motor_running) {
        return;
    }

    double error = (double)desired_rpm - (double)rpm;
    pid_integral += error;
    if (pid_integral > PID_MAX_INTEGRAL) pid_integral = PID_MAX_INTEGRAL;
    if (pid_integral < PID_MIN_INTEGRAL) pid_integral = PID_MIN_INTEGRAL;

    double derivative = error - pid_last_error;
    double output = (PID_KP * error) + (PID_KI * pid_integral) + (PID_KD * derivative);

    speed_percent += (int)output;
    if (speed_percent > 100) speed_percent = 100;
    if (speed_percent < 0) speed_percent = 0;

    int speed_duty_cycle = speed_percent * 10000;
    hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_duty_cycle);

    pid_last_error = error;

    printf("PID: Target=%d, Actual=%d, Err=%.1f, P=%.1f, I=%.1f, D=%.1f, Out=%.1f, NewSpeed=%d%%\n",
           desired_rpm, rpm, error, (PID_KP * error), (PID_KI * pid_integral), (PID_KD * derivative), output, speed_percent);
}

void process_command(char cmd) {
    printf("APP->PI: Processing char '%c'\n", cmd);

    int speed_duty_cycle;
    switch (cmd) {
        case 's':
            gpio_write(pi, MASTER_ON_PIN, 1);
            motor_running = 1;
            pid_integral = 0;
            pid_last_error = 0;
            break;
        case 'x':
            stop_all_activity();
            break;
        case 'c':
            gpio_write(pi, DIR_A_PIN, 0); 
            gpio_write(pi, DIR_B_PIN, 1); 
            break;
        case 'v':
            gpio_write(pi, DIR_A_PIN, 1); 
            gpio_write(pi, DIR_B_PIN, 0); 
            break;
        case 'f':
            if (current_mode == MANUAL_MODE) {
                speed_percent += 10;
                if (speed_percent > 100) speed_percent = 100;
                speed_duty_cycle = speed_percent * 10000;
                hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_duty_cycle);
            } else {
                printf("Ignoring 'f' in AUTO_MODE\n");
            }
            break;
        case 'd':
            if (current_mode == MANUAL_MODE) {
                speed_percent -= 10;
                if (speed_percent < 0) speed_percent = 0;
                speed_duty_cycle = speed_percent * 10000;
                hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_duty_cycle);
            } else {
                 printf("Ignoring 'd' in AUTO_MODE\n");
            }
            break;
        case 'a':
            current_mode = AUTO_MODE;
            printf("Switched to AUTO_MODE\n");
            pid_integral = 0;
            pid_last_error = 0;
            break;
        case 'm':
            current_mode = MANUAL_MODE;
            printf("Switched to MANUAL_MODE\n");
            break;
    }
}

int main() {
    signal(SIGINT, int_handler);
    signal(SIGTERM, int_handler);

    pi = pigpio_start(NULL, NULL);
    if (pi < 0) {
        fprintf(stderr, "pigpio initialisation failed!\n");
        return 1;
    }

    set_mode(pi, MASTER_ON_PIN, PI_OUTPUT);
    set_mode(pi, DIR_A_PIN, PI_OUTPUT);
    set_mode(pi, DIR_B_PIN, PI_OUTPUT);
    set_mode(pi, SPEED_PIN, PI_OUTPUT);
    set_mode(pi, SENSOR_PIN, PI_INPUT);
    set_pull_up_down(pi, SENSOR_PIN, PI_PUD_DOWN);
    stop_all_activity();

    callback(pi, SENSOR_PIN, RISING_EDGE, rpm_callback);

    struct sockaddr_rc loc_addr = { 0 }, rem_addr = { 0 };
    char buf[1024] = { 0 };
    char rpm_str[32];
    int server_sock, client_sock;
    socklen_t opt = sizeof(rem_addr);

    server_sock = socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM);
    if (server_sock < 0) {
        perror("Failed to create socket");
        pigpio_stop(pi);
        return 1;
    }

    loc_addr.rc_family = AF_BLUETOOTH;
    loc_addr.rc_bdaddr = *BDADDR_ANY;
    loc_addr.rc_channel = (uint8_t) RFCOMM_CHANNEL;

    if (bind(server_sock, (struct sockaddr *)&loc_addr, sizeof(loc_addr)) < 0) {
        perror("bind() failed");
        close(server_sock);
        pigpio_stop(pi);
        return 1;
    }

    if (listen(server_sock, 1) < 0) {
        perror("listen() failed");
        close(server_sock);
        pigpio_stop(pi);
        return 1;
    }
    fcntl(server_sock, F_SETFL, O_NONBLOCK);

    printf("Bluetooth server started. Waiting for connection on channel %d...\n", RFCOMM_CHANNEL);

    while (keep_running) {
        client_sock = accept(server_sock, (struct sockaddr *)&rem_addr, &opt);
        if (client_sock < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                usleep(100000); // Sleep 100ms
                continue;
            }
            if (keep_running) perror("Accept failed");
            continue;
        }

        ba2str(&rem_addr.rc_bdaddr, buf);
        fprintf(stdout, "Accepted connection from %s\n", buf);
        fcntl(client_sock, F_SETFL, O_NONBLOCK);
        
        current_mode = MANUAL_MODE;
        stop_all_activity();

        uint32_t last_rpm_calc_tick = get_current_tick(pi);
        uint32_t last_rpm_send_tick = get_current_tick(pi);

        while (keep_running) {
            uint32_t current_tick = get_current_tick(pi);

            if ((current_tick - last_rpm_calc_tick) >= 1000000) {
                
                // --- FIX 1: INTEGER DIVISION ---
                // The old code: rpm = (revolution_count / 3) * 60;
                // If revolution_count was 1 or 2, (1 / 3) = 0, so RPM was 0.
                // This forces floating-point math, so (1.0 / 3.0) * 60.0 = 20.0
                rpm = (int)(((double)revolution_count / 3.0) * 60.0);
                // --- END OF FIX 1 ---

                revolution_count = 0;
                last_rpm_calc_tick = current_tick;
                
                update_pid_controller();
            }

            // --- FIX 2: ROBUST SEND LOGIC ---
            // Only update the 'last_rpm_send_tick' timer *after* a successful
            // write. This prevents the code from just skipping a send if
            // the buffer was full. It will now keep trying on subsequent
            // loops until it succeeds.
            if ((current_tick - last_rpm_send_tick) >= 50000) {
                snprintf(rpm_str, sizeof(rpm_str), "RPM:%d\n", rpm);
                printf("PI->APP: %s", rpm_str);

                int write_ret = write(client_sock, rpm_str, strlen(rpm_str));
                
                if (write_ret < 0) {
                    if (errno == EAGAIN || errno == EWOULDBLOCK) {
                        // Buffer full, DON'T update tick.
                        // We will try again on the next main loop.
                    } else {
                        // Real error
                        fprintf(stdout, "Client %s disconnected (write error).\n", buf);
                        close(client_sock); 
                        break; 
                    }
                } else {
                    // Write was successful (or partially successful)
                    // Update the timer
                    last_rpm_send_tick = current_tick;
                }
            }
            // --- END OF FIX 2 ---

            
            int bytes_read = read(client_sock, buf, sizeof(buf) - 1);
            if (bytes_read > 0) {
                buf[bytes_read] = '\0'; 
                
                char* p = buf; 
                
                while (*p != '\0' && (p - buf) < bytes_read) {
                    
                    if (*p == 'r' && *(p+1) == ':') {
                        p += 2; 
                        char* start = p; 
                        
                        while (isdigit(*p) && (p - buf) < bytes_read) {
                            p++;
                        }
                        
                        char num_buf[16];
                        int len = p - start;
                        if (len > 0 && len < 15) {
                            strncpy(num_buf, start, len);
                            num_buf[len] = '\0';
                            desired_rpm = atoi(num_buf);
                            printf("Parsed command: Set Desired RPM = %d\n", desired_rpm);
                        }
                    } else {
                        process_command(*p);
                        p++;
                    }
                }
            } 
            else if (bytes_read == 0) {
                fprintf(stdout, "Client %s disconnected (read 0).\n", buf);
                close(client_sock);
                break;
            }
            usleep(20000);
        }

        stop_all_activity();
        printf("Waiting for new connection...\n");
    }

    printf("Shutting down server...\n");
    stop_all_activity();
    close(server_sock);
    pigpio_stop(pi);
    return 0;
}
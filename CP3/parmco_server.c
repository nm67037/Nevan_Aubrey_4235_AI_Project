/*
 * PARMCO - Bluetooth Server (Checkpoint 3)
 * AI Author: Gemini
 * Date: 11/12/2025
 *
 * FIX:
 * - Changed RFCOMM channel from 1 to 22 to avoid conflicts.
 * - Added fcntl() to make the server_sock NON-BLOCKING.
 * This fixes the "zombie" bug and allows the service to
 * shut down gracefully.
 * - Updated printf to report the correct channel.
 * - Corrected uint3TICK_T typo to uint32_t.
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
#include <errno.h> // For non-blocking check

// --- Pin Definitions ---
#define MASTER_ON_PIN 17 
#define DIR_A_PIN     27
#define DIR_B_PIN     22
#define SPEED_PIN     18 
#define SENSOR_PIN    23 

#define PWM_FREQ 1000

// --- THIS IS THE NEW CHANNEL ---
#define RFCOMM_CHANNEL 22

// --- Global Variables ---
static volatile int keep_running = 1;     
static volatile int revolution_count = 0; 
static volatile int rpm = 0;              
static int speed_percent = 0;             
static int pi;                            

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
}

void int_handler(int sig) {
    keep_running = 0;
    printf("Termination signal received, shutting down...\n");
}

void process_command(char cmd) {
    int speed_duty_cycle;
    switch (cmd) {
        case 's': gpio_write(pi, MASTER_ON_PIN, 1); break;
        case 'x': gpio_write(pi, MASTER_ON_PIN, 0); break;
        case 'c': gpio_write(pi, DIR_A_PIN, 0); gpio_write(pi, DIR_B_PIN, 1); break;
        case 'v': gpio_write(pi, DIR_A_PIN, 1); gpio_write(pi, DIR_B_PIN, 0); break;
        case 'f': 
            speed_percent += 10;
            if (speed_percent > 100) speed_percent = 100;
            speed_duty_cycle = speed_percent * 10000; 
            hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_duty_cycle);
            break;
        case 'd': 
            speed_percent -= 10;
            if (speed_percent < 0) speed_percent = 0;
            speed_duty_cycle = speed_percent * 10000;
            hardware_PWM(pi, SPEED_PIN, PWM_FREQ, speed_duty_cycle);
            break;
    }
}

int main() {
    signal(SIGINT, int_handler);
    signal(SIGTERM, int_handler);

    pi = pigpio_start(NULL, NULL); 
    if (pi < 0) {
        fprintf(stderr, "pigpio initialisation failed! (Could not connect to daemon)\n");
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

    // This fixes the "zombie" service bug
    fcntl(server_sock, F_SETFL, O_NONBLOCK);

    printf("Bluetooth server started. Waiting for connection on channel %d...\n", RFCOMM_CHANNEL);

    while (keep_running) {
        // --- Non-Blocking Accept ---
        client_sock = accept(server_sock, (struct sockaddr *)&rem_addr, &opt);

        if (client_sock < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // No connection pending, just loop
                usleep(100000); // Sleep 100ms
                continue; // Go back to top of 'while(keep_running)'
            }
            if (keep_running) perror("Accept failed");
            continue;
        }

        // --- Connection Accepted ---
        ba2str(&rem_addr.rc_bdaddr, buf);
        fprintf(stdout, "Accepted connection from %s\n", buf);

        fcntl(client_sock, F_SETFL, O_NONBLOCK);

        uint32_t last_rpm_calc_tick = get_current_tick(pi);
        uint32_t last_rpm_send_tick = get_current_tick(pi);

        while (keep_running) {
            uint32_t current_tick = get_current_tick(pi);

            if ((current_tick - last_rpm_calc_tick) >= 1000000) {
                rpm = (revolution_count / 3) * 60;
                revolution_count = 0;
                last_rpm_calc_tick = current_tick;
            }

            if ((current_tick - last_rpm_send_tick) >= 50000) { 
                snprintf(rpm_str, sizeof(rpm_str), "RPM:%d\n", rpm);
                if (write(client_sock, rpm_str, strlen(rpm_str)) < 0) {
                    fprintf(stdout, "Client %s disconnected.\n", buf);
                    close(client_sock); 
                    break; 
                }
                last_rpm_send_tick = current_tick;
            }

            int bytes_read = read(client_sock, buf, sizeof(buf) - 1);
            if (bytes_read > 0) {
                for (int i = 0; i < bytes_read; i++) {
                    process_command(buf[i]);
                }
            } else if (bytes_read == 0) {
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
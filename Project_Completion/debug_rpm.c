#include <stdio.h>
#include <pigpiod_if2.h>
#include <unistd.h>

#define SENSOR_PIN 23

volatile int count = 0;

// --- Renamed function to avoid conflict ---
void my_sensor_trigger(int pi, unsigned gpio, unsigned level, uint32_t tick) {
    printf("INTERRUPT FIRED! Level: %d\n", level);
    count++;
}

int main() {
    int pi = pigpio_start(NULL, NULL);
    if (pi < 0) return 1;

    // 1. Set Mode
    set_mode(pi, SENSOR_PIN, PI_INPUT);
    
    // 2. Enable Internal Pull-Up (Forces pin to 3.3V if sensor floats)
    set_pull_up_down(pi, SENSOR_PIN, PI_PUD_UP);

    // 3. Listen for Falling Edge (3.3V -> 0V transition)
    // We pass our renamed function 'my_sensor_trigger' here
    callback(pi, SENSOR_PIN, FALLING_EDGE, my_sensor_trigger);

    printf("Debugging RPM on Pin %d... Spin the prop!\n", SENSOR_PIN);

    while(1) {
        printf("Total Count: %d\n", count);
        sleep(1);
    }
    pigpio_stop(pi);
}
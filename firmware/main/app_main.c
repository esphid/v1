#include "esphid.h"
#include "nvs_flash.h"
#include "esp_event.h"
#include "driver/gpio.h"
#include "esp_system.h"
#include "esp_log.h"
#include "led_strip.h"
volatile bool usb_started, transport_started;
static TaskHandle_t manager;
static portMUX_TYPE control_mux = portMUX_INITIALIZER_UNLOCKED;
static int64_t reboot_at;
static bool reset_requested;
void system_reboot(unsigned delay, bool reset) {
    portENTER_CRITICAL(&control_mux);
    reset_requested |= reset;
    int64_t at = now_ms() + (delay < 100 ? 100 : delay);
    if (!reboot_at || at < reboot_at)
        reboot_at = at;
    portEXIT_CRITICAL(&control_mux);
}
void services_changed(void) {
    if (manager)
        xTaskNotifyGive(manager);
}
static void manage(void *arg) {
    led_strip_handle_t led = NULL;
#if CONFIG_ESPHID_LED_GPIO >= 0
    led_strip_config_t lc = {.strip_gpio_num = CONFIG_ESPHID_LED_GPIO, .max_leds = 1};
    led_strip_rmt_config_t rc = {.resolution_hz = 10000000};
    led_strip_new_rmt_device(&lc, &rc, &led);
#endif
    int64_t held = 0;
    bool healthy = false;
    for (;;) {
        if (ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(50))) {
            if (net_enabled()) {
                net_apply();
                http_apply();
                mqtt_apply();
                tailscale_apply();
                mdns_refresh();
            }
            ble_apply();
        }
        int64_t t = now_ms();
        if (!gpio_get_level(0)) {
            if (!held)
                held = t;
            if (t - held >= 10000)
                system_reboot(100, true);
        } else
            held = 0;
        portENTER_CRITICAL(&control_mux);
        bool reboot = reboot_at && t >= reboot_at, reset = reset_requested;
        portEXIT_CRITICAL(&control_mux);
        if (reboot) {
            if (reset) {
                ble_reset_bonds();
                tailscale_reset();
                nvs_erase_all(config_nvs);
                nvs_commit(config_nvs);
            }
            esp_restart();
        }
        if (!healthy && usb_started && transport_started) {
            ota_mark_healthy();
            healthy = true;
        }
        if (led) {
            led_strip_set_pixel(led, 0, hid_mounted() ? 0 : 8, claimed() ? 8 : 0,
                                claimed() ? 0 : 8);
            led_strip_refresh(led);
        }
    }
}
void app_main(void) {
    esp_err_t e = nvs_flash_init();
    if (e == ESP_ERR_NVS_NO_FREE_PAGES || e == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        e = nvs_flash_init();
    }
    ESP_ERROR_CHECK(e);
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    config_init();
    rpc_init();
    hid_init();
    gpio_config_t gpio = {
        .pin_bit_mask = 1ULL << 0, .mode = GPIO_MODE_INPUT, .pull_up_en = GPIO_PULLUP_ENABLE};
    ESP_ERROR_CHECK(gpio_config(&gpio));
    cJSON *c = config_snapshot(false);
    const char *mode = str(obj(c, "radio_mode"));
    if (strcmp(mode, "ble")) {
        net_init();
        http_init();
        mqtt_apply();
        tailscale_apply();
    }
    if (strcmp(mode, "wifi"))
        ble_init();
    cJSON_Delete(c);
    assert(xTaskCreate(manage, "management", 8192, NULL, 4, &manager) == pdPASS);
    services_changed();
}

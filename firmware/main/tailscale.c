#include "esphid.h"
#include "esp_log.h"
#include "esp_heap_caps.h"
#ifdef CONFIG_ESPHID_TAILSCALE
#include "microlink.h"
#include "ml_config_httpd.h"
#include "http_parser.h"
static microlink_t *node;
static char auth_key[96], hostname[48], control[256];
static volatile bool failed;
static void state_changed(microlink_t *ml, microlink_state_t state, void *arg) {
    failed = state == ML_STATE_ERROR || state == ML_STATE_AUTH_FAILED;
}
void tailscale_apply(void) {
    cJSON *c = config_snapshot(true), *t = obj(c, "tailscale");
    bool enabled = cJSON_IsTrue(obj(t, "enabled"));
    const char *key = str(obj(t, "auth_key")), *host = str(obj(t, "hostname")),
               *url = str(obj(t, "control_url"));
    xSemaphoreTakeRecursive(config_mutex, portMAX_DELAY);
    if (node &&
        (!enabled || strcmp(key, auth_key) || strcmp(host, hostname) || strcmp(url, control))) {
        microlink_stop(node);
        microlink_destroy(node);
        node = NULL;
    }
    if (enabled && !node) {
        strlcpy(auth_key, key, sizeof(auth_key));
        strlcpy(hostname, host, sizeof(hostname));
        strlcpy(control, url, sizeof(control));
        /* MicroLink exposes Headscale via its documented persistent settings schema. */
        ml_config_settings_t settings = {.version = ML_CONFIG_SETTINGS_VERSION, .max_peers = 4};
        if (*url) {
            struct http_parser_url parsed;
            http_parser_url_init(&parsed);
            if (http_parser_parse_url(url, strlen(url), 0, &parsed) == 0 &&
                (parsed.field_set & (1 << UF_HOST))) {
                size_t n = parsed.field_data[UF_HOST].len;
                if (n < sizeof(settings.ctrl_host))
                    memcpy(settings.ctrl_host, url + parsed.field_data[UF_HOST].off, n);
            }
        }
        nvs_handle_t nvs;
        if (nvs_open("ml_config", NVS_READWRITE, &nvs) == ESP_OK) {
            nvs_set_blob(nvs, "settings", &settings, sizeof(settings));
            nvs_commit(nvs);
            nvs_close(nvs);
        }
        microlink_config_t cfg = {.auth_key = auth_key,
                                  .device_name = hostname,
                                  .enable_derp = true,
                                  .enable_stun = true,
                                  .enable_disco = true,
                                  .max_peers = 4};
        /* MicroLink logs registration details; suppress them so keys cannot leak through upstream
         * logs. */
        esp_log_level_set("microlink", ESP_LOG_NONE);
        esp_log_level_set("ml_coord", ESP_LOG_NONE);
        node = microlink_init(&cfg);
        failed = !node;
        if (node) {
            microlink_set_state_callback(node, state_changed, NULL);
            failed = microlink_start(node) != ESP_OK;
        }
    }
    if (!enabled)
        failed = false;
    cJSON_Delete(c);
    xSemaphoreGiveRecursive(config_mutex);
}
cJSON *tailscale_info(void) {
    xSemaphoreTakeRecursive(config_mutex, portMAX_DELAY);
    cJSON *r = cJSON_CreateObject(), *c = config_snapshot(false);
    bool enabled = cJSON_IsTrue(obj(obj(c, "tailscale"), "enabled"));
    JB(r, "enabled", enabled);
    JS(r, "state",
       !enabled || !net_enabled()             ? "off"
       : failed                               ? "error"
       : node && microlink_is_connected(node) ? "connected"
                                              : "connecting");
    char ip[16] = "";
    if (node && microlink_get_vpn_ip(node))
        microlink_ip_to_str(microlink_get_vpn_ip(node), ip);
    JS(r, "ip", ip);
    JS(r, "hostname", enabled ? str(obj(obj(c, "tailscale"), "hostname")) : "");
    cJSON_Delete(c);
    xSemaphoreGiveRecursive(config_mutex);
    return r;
}
void tailscale_reset(void) {
    xSemaphoreTakeRecursive(config_mutex, portMAX_DELAY);
    if (node) {
        microlink_stop(node);
        microlink_destroy(node);
        node = NULL;
    }
    microlink_factory_reset();
    nvs_handle_t n;
    if (nvs_open("ml_config", NVS_READWRITE, &n) == ESP_OK) {
        nvs_erase_all(n);
        nvs_commit(n);
        nvs_close(n);
    }
    xSemaphoreGiveRecursive(config_mutex);
}
#else
void tailscale_apply(void) {}
cJSON *tailscale_info(void) {
    cJSON *r = cJSON_CreateObject();
    JB(r, "enabled", false);
    JS(r, "state", "off");
    JS(r, "ip", "");
    JS(r, "hostname", "");
    return r;
}
void tailscale_reset(void) {
    const char *names[] = {"microlink", "ml_config"};
    for (int i = 0; i < 2; i++) {
        nvs_handle_t n;
        if (nvs_open(names[i], NVS_READWRITE, &n) == ESP_OK) {
            nvs_erase_all(n);
            nvs_commit(n);
            nvs_close(n);
        }
    }
}
#endif

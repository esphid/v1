#include "esphid.h"
#include "esp_mac.h"
#include "esp_system.h"
#include <math.h>
char device_id[7];
nvs_handle_t config_nvs;
SemaphoreHandle_t config_mutex;
DRAM_ATTR _Atomic unsigned keyboard_delay;
DRAM_ATTR _Atomic(const esh_layout *) keyboard_layout;
volatile uint32_t auth_epoch = 1;
static cJSON *config;
static uint8_t token[65];
static portMUX_TYPE auth_mux = portMUX_INITIALIZER_UNLOCKED;
typedef struct {
    int64_t fails[5], until;
    unsigned count;
} limiter;
static limiter limits[4];
static void take(void) {
    xSemaphoreTakeRecursive(config_mutex, portMAX_DELAY);
}
static void give(void) {
    xSemaphoreGiveRecursive(config_mutex);
}
static cJSON *defaults(void) {
    cJSON *c = cJSON_CreateObject(), *v;
    char name[64];
    snprintf(name, sizeof(name), "ESPHID-%s", device_id);
    JS(c, "name", name);
    JS(c, "radio_mode", "both");
    char host[64];
    snprintf(host, sizeof(host), "esphid-%s", device_id);
    for (char *p = host; *p; p++)
        if (*p >= 'A' && *p <= 'Z')
            *p += 32;
    v = sub(c, "wifi");
    JS(v, "ssid", "");
    JS(v, "password", "");
    JS(v, "hostname", host);
    JB(v, "ap_fallback", true);
    v = sub(c, "https");
    JB(v, "enabled", false);
    JN(v, "port", 443);
    v = sub(c, "mqtt");
    JB(v, "enabled", false);
    JS(v, "uri", "");
    JS(v, "username", "");
    JS(v, "password", "");
    JS(v, "client_id", "");
    char topic[64];
    snprintf(topic, sizeof(topic), "esphid/%s", device_id);
    JS(v, "topic_prefix", topic);
    JB(v, "require_token", true);
    v = sub(c, "tailscale");
    JB(v, "enabled", false);
    JS(v, "auth_key", "");
    JS(v, "control_url", "");
    JS(v, "hostname", host);
    v = sub(c, "keyboard");
    JS(v, "layout", "us");
    JN(v, "type_delay_ms", 0);
    return c;
}
void config_init(void) {
    config_mutex = xSemaphoreCreateRecursiveMutex();
    assert(config_mutex);
    uint8_t mac[6];
    ESP_ERROR_CHECK(esp_efuse_mac_get_default(mac));
    snprintf(device_id, 7, "%02X%02X%02X", mac[3], mac[4], mac[5]);
    ESP_ERROR_CHECK(nvs_open("esphid", NVS_READWRITE, &config_nvs));
    config = defaults();
    size_t n = 0;
    if (nvs_get_str(config_nvs, "config", NULL, &n) == ESP_OK && n <= 8192) {
        char *s = malloc(n);
        if (s && nvs_get_str(config_nvs, "config", s, &n) == ESP_OK) {
            cJSON *c = cJSON_Parse(s);
            if (c) {
                cJSON_Delete(config);
                config = c;
            }
        }
        free(s);
    }
    n = sizeof(token);
    if (nvs_get_blob(config_nvs, "token", token, &n) != ESP_OK || n != 65)
        memset(token, 0, 65);
    keyboard_delay = obj(obj(config, "keyboard"), "type_delay_ms")->valueint;
    const esh_layout *layout = esh_layout_find(str(obj(obj(config, "keyboard"), "layout")));
    keyboard_layout = layout ? layout : esh_layout_find("us");
}
bool claimed(void) {
    portENTER_CRITICAL(&auth_mux);
    bool b = token[64] != 0;
    portEXIT_CRITICAL(&auth_mux);
    return b;
}
cJSON *config_snapshot(bool secrets) {
    take();
    cJSON *c = cJSON_Duplicate(config, true);
    give();
    if (!secrets) {
        const char *sections[] = {"wifi", "mqtt", "tailscale"},
                   *fields[] = {"password", "password", "auth_key"},
                   *flags[] = {"password_set", "password_set", "auth_key_set"};
        for (int i = 0; i < 3; i++) {
            cJSON *v = obj(c, sections[i]);
            bool set = *str(obj(v, fields[i]));
            cJSON_DeleteItemFromObjectCaseSensitive(v, fields[i]);
            JB(v, flags[i], set);
        }
    }
    return c;
}
cJSON *config_public(void) {
    return config_snapshot(false);
}
static bool token_valid(const char *s) {
    size_t n = strlen(s);
    if (n < 16 || n > 64)
        return false;
    for (size_t i = 0; i < n; i++)
        if ((unsigned char)s[i] < 0x21 || (unsigned char)s[i] > 0x7e)
            return false;
    return true;
}
int token_set(const char *s, bool claim) {
    if (!token_valid(s))
        return 400;
    take();
    if (claim && claimed()) {
        give();
        return 409;
    }
    uint8_t t[65] = {0};
    size_t n = strlen(s);
    memcpy(t, s, n);
    t[64] = n;
    esp_err_t e = nvs_set_blob(config_nvs, "token", t, 65);
    if (e == ESP_OK)
        e = nvs_commit(config_nvs);
    if (e == ESP_OK) {
        portENTER_CRITICAL(&auth_mux);
        memcpy(token, t, 65);
        auth_epoch++;
        portEXIT_CRITICAL(&auth_mux);
    }
    give();
    memset(t, 0, 65);
    if (e != ESP_OK)
        return 500;
    services_changed();
    return 200;
}
static int authenticate_impl(session *s, const uint8_t *p, size_t n, bool optional) {
    uint8_t attempt[65] = {0};
    if (n <= 64) {
        memcpy(attempt, p, n);
        attempt[64] = n;
    }
    int result = 0;
    int64_t now = now_ms();
    portENTER_CRITICAL(&auth_mux);
    limiter *l = &limits[s->kind];
    if (!token[64])
        result = 3;
    else if (!optional && now < l->until)
        result = 2;
    else if (n > 64 || !esh_constant_equal(attempt, 65, token, 65)) {
        result = 1;
        if (optional)
            goto done;
        unsigned valid = 0;
        for (unsigned i = 0; i < l->count; i++)
            if (now - l->fails[i] < 60000)
                l->fails[valid++] = l->fails[i];
        l->count = valid;
        l->fails[l->count++] = now;
        result = 1;
        if (l->count == 5) {
            l->until = now + 30000;
            l->count = 0;
            result = 2;
        }
    } else {
        s->authenticated = true;
        s->epoch = auth_epoch;
    }
done:
    portEXIT_CRITICAL(&auth_mux);
    memset(attempt, 0, sizeof(attempt));
    if (!optional)
        s->status = result == 2 ? 429 : result ? 401 : 200;
    return result;
}
int authenticate(session *s, const uint8_t *p, size_t n) {
    return authenticate_impl(s, p, n, false);
}
int authenticate_optional(session *s, const uint8_t *p, size_t n) {
    return authenticate_impl(s, p, n, true);
}
bool session_authorized(session *s) {
    return claimed() && s->authenticated && s->epoch == auth_epoch;
}
/* Validate against the stored schema and reject read-only/unknown keys recursively. */
static bool merge(cJSON *dst, const cJSON *patch, const char *path, char *err, size_t cap) {
    if (!cJSON_IsObject(patch))
        goto bad;
    const cJSON *v;
    cJSON_ArrayForEach(v, patch) {
        char field[96];
        snprintf(field, sizeof(field), "%s%s%s", path, *path ? "." : "", v->string);
        cJSON *old = obj(dst, v->string);
        if (!old) {
            snprintf(err, cap, "unknown field %s", field);
            return false;
        }
        if (cJSON_IsObject(old)) {
            if (!merge(old, v, field, err, cap))
                return false;
            continue;
        }
        bool ok = false;
        if (cJSON_IsString(old) && cJSON_IsString(v)) {
            size_t n = strlen(v->valuestring);
            ok = n <= 255;
            if (!strcmp(field, "name"))
                ok = n > 0 && n <= 63;
            else if (!strcmp(field, "wifi.ssid"))
                ok = n <= 32;
            else if (!strcmp(field, "wifi.password"))
                ok = n == 0 || (n >= 8 && n <= 63) ||
                     (n == 64 && strspn(v->valuestring, "0123456789abcdefABCDEF") == 64);
            else if (strstr(field, "hostname")) {
                ok = n > 0 && n <= 63 && v->valuestring[0] != '-' && v->valuestring[n - 1] != '-';
                for (size_t i = 0; i < n; i++)
                    if (!((v->valuestring[i] >= 'a' && v->valuestring[i] <= 'z') ||
                          (v->valuestring[i] >= 'A' && v->valuestring[i] <= 'Z') ||
                          (v->valuestring[i] >= '0' && v->valuestring[i] <= '9') ||
                          v->valuestring[i] == '-'))
                        ok = false;
            } else if (!strcmp(field, "radio_mode"))
                ok = !strcmp(v->valuestring, "both") || !strcmp(v->valuestring, "wifi") ||
                     !strcmp(v->valuestring, "ble");
            else if (!strcmp(field, "keyboard.layout"))
                ok = esh_layout_find(v->valuestring) != NULL;
            else if (!strcmp(field, "mqtt.uri"))
                ok = !n || !strncmp(v->valuestring, "mqtt://", 7) ||
                     !strncmp(v->valuestring, "mqtts://", 8) ||
                     !strncmp(v->valuestring, "ws://", 5) || !strncmp(v->valuestring, "wss://", 6);
            else if (!strcmp(field, "mqtt.topic_prefix"))
                ok = n > 0 && n <= 128 && !strpbrk(v->valuestring, "+#");
            else if (!strcmp(field, "tailscale.auth_key"))
                ok = n < 96;
            else if (!strcmp(field, "tailscale.control_url")) {
                ok = !n;
                if (n > 8 && !strncmp(v->valuestring, "https://", 8)) {
                    const char *h = v->valuestring + 8;
                    size_t hn = strcspn(h, "/:");
                    ok = hn > 0 && hn < 64 && (!h[hn] || !strcmp(h + hn, "/"));
                }
            }
        } else if (cJSON_IsBool(old))
            ok = cJSON_IsBool(v);
        else if (cJSON_IsNumber(old) && cJSON_IsNumber(v))
            ok = isfinite(v->valuedouble) && floor(v->valuedouble) == v->valuedouble &&
                 v->valuedouble >= (!strcmp(field, "https.port") ? 1 : 0) &&
                 v->valuedouble <= (!strcmp(field, "https.port") ? 65535 : 5000);
        if (!strcmp(field, "tailscale.hostname") && cJSON_IsString(v) &&
            strlen(v->valuestring) > 47)
            ok = false;
        if (!ok) {
            snprintf(err, cap, "invalid field %s", field);
            return false;
        }
        cJSON_ReplaceItemInObjectCaseSensitive(dst, v->string, cJSON_Duplicate(v, true));
    }
    return true;
bad:
    snprintf(err, cap, "invalid field %s", path);
    return false;
}
int config_set(cJSON *patch, bool *reboot, char *err, size_t cap) {
    take();
    cJSON *next = cJSON_Duplicate(config, true);
    if (!next) {
        give();
        return 500;
    }
    if (!merge(next, patch, "", err, cap)) {
        cJSON_Delete(next);
        give();
        return 400;
    }
#ifndef CONFIG_ESPHID_TAILSCALE
    if (cJSON_IsTrue(obj(obj(next, "tailscale"), "enabled"))) {
        snprintf(err, cap, "tailscale.enabled unavailable in this build");
        cJSON_Delete(next);
        give();
        return 400;
    }
#endif
    if (cJSON_IsTrue(obj(obj(next, "mqtt"), "enabled")) && !*str(obj(obj(next, "mqtt"), "uri"))) {
        snprintf(err, cap, "invalid field mqtt.uri");
        cJSON_Delete(next);
        give();
        return 400;
    }
    *reboot = strcmp(str(obj(next, "radio_mode")), str(obj(config, "radio_mode"))) != 0;
    char *json = cJSON_PrintUnformatted(next);
    esp_err_t e = json ? nvs_set_str(config_nvs, "config", json) : ESP_ERR_NO_MEM;
    if (e == ESP_OK)
        e = nvs_commit(config_nvs);
    free(json);
    if (e == ESP_OK) {
        cJSON_Delete(config);
        config = next;
        keyboard_delay = obj(obj(config, "keyboard"), "type_delay_ms")->valueint;
        const esh_layout *layout = esh_layout_find(str(obj(obj(config, "keyboard"), "layout")));
        keyboard_layout = layout ? layout : esh_layout_find("us");
    } else
        cJSON_Delete(next);
    give();
    if (e != ESP_OK)
        return 500;
    services_changed();
    return 200;
}

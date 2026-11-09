#include "esphid.h"
#include "esp_app_desc.h"
#include "esp_system.h"
#include "esp_idf_version.h"
#include "esp_heap_caps.h"
static QueueHandle_t event_queue;
typedef struct {
    uint8_t data[9];
    size_t len;
} event;
void session_init(session *s, transport_kind kind, event_sender send, void *ctx) {
    *s = (session){.kind = kind, .send = send, .ctx = ctx, .status = 200};
}
void session_leds(session *s) {
    uint8_t e[2];
    if (s->send)
        s->send(s, e, esh_event_leds(e, hid_leds()));
}
void command_error(void *ctx, uint8_t code, uint8_t op) {
    session *s = ctx;
    uint8_t e[3];
    s->status = code == ESH_UNAUTHORIZED ? 401 : code == ESH_QUEUE_FULL ? 503 : 400;
    if (s->send)
        s->send(s, e, esh_event_error(e, code, op));
}
bool command_receive(void *ctx, esh_command *c) {
    session *s = ctx;
    uint8_t e[9];
    if (c->op == ESH_AUTH) {
        int r = authenticate(s, c->data, c->len);
        if (s->send)
            s->send(s, e, esh_event_auth(e, r));
        if (!r && (s->kind == TRANSPORT_WS || s->kind == TRANSPORT_BLE))
            session_leds(s);
        return !r;
    }
    if (c->op == ESH_PING) {
        if (s->send)
            s->send(s, e, esh_event_pong(e, c->nonce, (uint32_t)now_ms()));
        return true;
    }
    if (!session_authorized(s)) {
        command_error(s, ESH_UNAUTHORIZED, c->op);
        if (!claimed() && s->send)
            s->send(s, e, esh_event_auth(e, 3));
        return false;
    }
    if (c->op == ESH_STATE) {
        if (s->send)
            s->send(s, e,
                    esh_event_state(e, (hid_mounted() ? 1 : 0) | (hid_suspended() ? 2 : 0) | 4,
                                    hid_leds(), hid_depth()));
        return true;
    }
    if (!hid_mounted()) {
        if (!s->usb_error_at || now_ms() - s->usb_error_at >= 1000) {
            command_error(s, ESH_USB_NOT_READY, c->op);
            s->usb_error_at = now_ms();
        }
        return true;
    }
    if (!(s->enqueue ? s->enqueue(s, c) : hid_enqueue(c, s->kind))) {
        command_error(s, ESH_QUEUE_FULL, c->op);
        return false;
    }
    return true;
}
bool packet_receive(session *s, const uint8_t *p, size_t n) {
    s->status = 200;
    return esh_parse(p, n, command_receive, command_error, s);
}
void broadcast_event(const uint8_t *p, size_t n) {
    if (!event_queue || n > 9)
        return;
    event e = {.len = n};
    memcpy(e.data, p, n);
    xQueueSend(event_queue, &e, 0);
}
static void events(void *arg) {
    event e;
    for (;;)
        if (xQueueReceive(event_queue, &e, portMAX_DELAY)) {
            http_events(e.data, e.len);
            ble_events(e.data, e.len);
            mqtt_events(e.data, e.len);
        }
}
void rpc_init(void) {
    event_queue = xQueueCreate(32, sizeof(event));
    assert(event_queue);
    assert(xTaskCreate(events, "events", 4096, NULL, 5, NULL) == pdPASS);
}
/* A live session went away (link loss, app killed, socket closed). Anything it was
 * holding (keys, modifiers, mouse buttons, consumer key) must not stay pressed on the host. */
void session_ended(session *s) {
    if (!s || !s->authenticated || !usb_started)
        return;
    hid_abort();
}
void sessions_drop(session *keep) {
    if (keep && keep->identity)
        keep = keep->identity;
    if (usb_started)
        hid_abort();
    http_drop(keep);
    ble_drop(keep);
}
cJSON *device_info(bool full) {
    cJSON *c = config_snapshot(false), *r = cJSON_CreateObject();
    JS(r, "id", device_id);
    JS(r, "name", str(obj(c, "name")));
    JS(r, "model", "esp32s3");
    JS(r, "fw_version", esp_app_get_description()->version);
    JS(r, "fw_variant", CONFIG_ESPHID_FW_VARIANT);
    JN(r, "proto", 1);
    JB(r, "claimed", claimed());
    if (full) {
        JS(r, "idf_version", esp_get_idf_version());
        JN(r, "uptime_s", now_ms() / 1000);
        JN(r, "free_heap", esp_get_free_heap_size());
        JS(r, "radio_mode", str(obj(c, "radio_mode")));
        cJSON *v = sub(r, "usb");
        JB(v, "mounted", hid_mounted());
        JB(v, "suspended", hid_suspended());
        JN(v, "leds", hid_leds());
        cJSON_AddItemToObject(r, "wifi", net_info());
        v = sub(r, "ble");
        JB(v, "enabled", ble_enabled());
        JN(v, "connections", ble_connections());
        v = sub(r, "https");
        JB(v, "enabled", cJSON_IsTrue(obj(obj(c, "https"), "enabled")));
        JN(v, "port", obj(obj(c, "https"), "port")->valueint);
        xSemaphoreTakeRecursive(config_mutex, portMAX_DELAY);
        JS(v, "fingerprint", tls_fingerprint());
        xSemaphoreGiveRecursive(config_mutex);
        v = sub(r, "mqtt");
        JB(v, "enabled", cJSON_IsTrue(obj(obj(c, "mqtt"), "enabled")));
        JB(v, "connected", mqtt_connected());
        cJSON_AddItemToObject(r, "tailscale", tailscale_info());
        cJSON *o = ota_status();
        v = sub(r, "ota");
        JS(v, "state", str(obj(o, "state")));
        JN(v, "progress", obj(o, "progress")->valuedouble);
        cJSON_Delete(o);
        JS(v, "repo", CONFIG_ESPHID_OTA_REPO);
        char url[320] = "";
        if (*CONFIG_ESPHID_OTA_REPO)
            snprintf(url, sizeof(url),
                     "https://github.com/%s/releases/latest/download/esphid-%s.bin",
                     CONFIG_ESPHID_OTA_REPO, CONFIG_ESPHID_FW_VARIANT);
        JS(v, "latest_url", url);
    }
    cJSON_Delete(c);
    return r;
}
static cJSON *ok(void) {
    cJSON *j = cJSON_CreateObject();
    JB(j, "ok", true);
    return j;
}
bool rpc_public(const char *method) {
    return !strcmp(method, "info") || !strcmp(method, "claim") ||
           (!claimed() && (!strcmp(method, "config.get") || !strcmp(method, "config.set") ||
                           !strcmp(method, "wifi.scan")));
}
bool rpc_slow(const cJSON *request) {
    const char *method = str(cJSON_GetObjectItemCaseSensitive(request, "method"));
    return !strcmp(method, "wifi.scan") || !strcmp(method, "https.regenerate");
}
cJSON *rpc_dispatch(session *s, const cJSON *request, int *status) {
    int code = 200;
    const char *message = "bad request";
    cJSON *result = NULL, *response = cJSON_CreateObject();
    const cJSON *id = cJSON_GetObjectItemCaseSensitive(request, "id");
    cJSON_AddItemToObject(response, "id", id ? cJSON_Duplicate(id, true) : cJSON_CreateNull());
    const char *method = str(cJSON_GetObjectItemCaseSensitive(request, "method"));
    cJSON *params = cJSON_GetObjectItemCaseSensitive(request, "params"), *empty = NULL;
    if (!params)
        params = empty = cJSON_CreateObject();
    if (!cJSON_IsObject(request) || !*method || !cJSON_IsObject(params)) {
        code = 400;
        goto end;
    }
    const cJSON *t = cJSON_GetObjectItemCaseSensitive(request, "token");
    if (t && !session_authorized(s)) {
        bool optional = rpc_public(method) && s->kind != TRANSPORT_MQTT;
        int a = optional ? authenticate_optional(s, (uint8_t *)str(t), strlen(str(t)))
                         : authenticate(s, (uint8_t *)str(t), strlen(str(t)));
        if (a && !optional) {
            code = a == 2 ? 429 : 401;
            message = "unauthorized";
            goto end;
        }
    }
    bool setup = !claimed() && (!strcmp(method, "config.get") || !strcmp(method, "config.set") ||
                                !strcmp(method, "wifi.scan"));
    bool public = !strcmp(method, "info") || !strcmp(method, "auth") || !strcmp(method, "claim");
    if (s->kind == TRANSPORT_WS && !session_authorized(s) && strcmp(method, "info") &&
        strcmp(method, "auth")) {
        code = 401;
        message = "unauthorized";
        goto end;
    }
    if (!public && !setup && !session_authorized(s)) {
        code = 401;
        message = "unauthorized";
        goto end;
    }
    if (!strcmp(method, "info"))
        result = device_info(session_authorized(s));
    else if (!strcmp(method, "auth")) {
        if (s->kind != TRANSPORT_WS && s->kind != TRANSPORT_BLE) {
            code = 400;
            message = "auth requires a session";
            goto end;
        }
        int a = authenticate(s, (uint8_t *)str(obj(params, "token")),
                             strlen(str(obj(params, "token"))));
        if (a) {
            code = a == 2 ? 429 : 401;
            message = a == 3 ? "device unclaimed" : "unauthorized";
        } else {
            result = ok();
            session_leds(s);
        }
    } else if (!strcmp(method, "claim") || !strcmp(method, "token.set")) {
        code = token_set(str(obj(params, "token")), !strcmp(method, "claim"));
        if (code == 200) {
            s->authenticated = true;
            s->epoch = auth_epoch;
            sessions_drop(s);
            result = ok();
        } else
            message = code == 409 ? "already claimed" : "invalid token";
    } else if (!strcmp(method, "config.get"))
        result = config_public();
    else if (!strcmp(method, "config.set")) {
        bool reboot = false;
        char error[128] = {0};
        code = config_set(params, &reboot, error, sizeof(error));
        if (code == 200) {
            result = cJSON_CreateObject();
            cJSON_AddItemToObject(result, "config", config_public());
            JB(result, "reboot_required", reboot);
        } else {
            cJSON *e = sub(response, "error");
            JN(e, "code", code);
            JS(e, "message", *error ? error : "internal");
        }
    } else if (!strcmp(method, "wifi.scan"))
        result = net_scan(&code);
    else if (!strcmp(method, "ota.status"))
        result = ota_status();
    else if (!strcmp(method, "ota.start")) {
        code = ota_start(str(obj(params, "url")), str(obj(params, "sha256")));
        if (code == 200)
            result = ok();
        else
            message = code == 503 ? "OTA busy" : "invalid OTA request";
    } else if (!strcmp(method, "system.reboot") || !strcmp(method, "system.factory_reset")) {
        cJSON *d = obj(params, "delay_ms");
        if (d && (!cJSON_IsNumber(d) || d->valuedouble < 0 || d->valuedouble > 60000)) {
            code = 400;
            message = "invalid delay_ms";
        } else {
            system_reboot(d ? d->valueint : 250, !strcmp(method, "system.factory_reset"));
            result = ok();
        }
    } else if (!strcmp(method, "https.regenerate")) {
        if (tls_prepare(true) == ESP_OK) {
            result = cJSON_CreateObject();
            xSemaphoreTakeRecursive(config_mutex, portMAX_DELAY);
            JS(result, "fingerprint", tls_fingerprint());
            xSemaphoreGiveRecursive(config_mutex);
            services_changed();
        } else {
            code = 500;
            message = "certificate generation failed";
        }
    } else if (!strcmp(method, "hid")) {
        char error[80];
        s->status = 200;
        if (esh_json(params, command_receive, s, error, sizeof(error)))
            result = ok();
        else {
            code = s->status == 200 ? 400 : s->status;
            message = "HID command rejected";
        }
    } else {
        code = 404;
        message = "unknown method";
    }
end:
    if (result)
        cJSON_AddItemToObject(response, "result", result);
    else if (!obj(response, "error")) {
        if (code == 200)
            code = 500;
        cJSON *e = sub(response, "error");
        JN(e, "code", code);
        JS(e, "message", message);
    }
    cJSON_Delete(empty);
    *status = code;
    return response;
}

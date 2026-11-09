#pragma once
#include "esphid_proto.h"
#include "esphid_safety.h"
#include <stdatomic.h>
#include "esp_err.h"
#include "esp_attr.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "freertos/queue.h"
#include "esp_http_server.h"
#include "nvs.h"
#include <string.h>
#include <stdlib.h>
#include <stdio.h>

typedef enum {
    TRANSPORT_HTTP = ESH_SOURCE_HTTP,
    TRANSPORT_WS = ESH_SOURCE_WS,
    TRANSPORT_BLE = ESH_SOURCE_BLE,
    TRANSPORT_MQTT = ESH_SOURCE_MQTT
} transport_kind;
typedef struct session session;
typedef void (*event_sender)(session *, const uint8_t *, size_t);
struct session {
    transport_kind kind;
    bool authenticated;
    uint32_t epoch;
    int status;
    int64_t usb_error_at;
    event_sender send;
    void *ctx;
    /* Worker snapshots can retain a session identity and guard HID admission.
     * Normal transport sessions leave both NULL. */
    session *identity;
    bool (*enqueue)(session *, esh_command *);
};
extern char device_id[7];
extern nvs_handle_t config_nvs;
extern SemaphoreHandle_t config_mutex;
extern _Atomic unsigned keyboard_delay;
extern _Atomic(const esh_layout *) keyboard_layout;
extern volatile uint32_t auth_epoch;
extern volatile bool usb_started, transport_started;
static inline int64_t now_ms(void) {
    return esp_timer_get_time() / 1000;
}
static inline cJSON *obj(cJSON *p, const char *k) {
    return cJSON_GetObjectItemCaseSensitive(p, k);
}
static inline const char *str(const cJSON *p) {
    return cJSON_IsString(p) ? p->valuestring : "";
}
static inline cJSON *sub(cJSON *p, const char *k) {
    cJSON *v = cJSON_CreateObject();
    cJSON_AddItemToObject(p, k, v);
    return v;
}
#define JS(p, k, v) cJSON_AddStringToObject(p, k, v)
#define JN(p, k, v) cJSON_AddNumberToObject(p, k, v)
#define JB(p, k, v) cJSON_AddBoolToObject(p, k, v)
void config_init(void);
cJSON *config_snapshot(bool secrets);
cJSON *config_public(void);
int config_set(cJSON *, bool *, char *, size_t);
bool claimed(void);
int token_set(const char *, bool);
int authenticate(session *, const uint8_t *, size_t);
int authenticate_optional(session *, const uint8_t *, size_t);
bool session_authorized(session *);
void session_init(session *, transport_kind, event_sender, void *);
void session_leds(session *);
bool command_receive(void *, esh_command *);
void command_error(void *, uint8_t, uint8_t);
bool packet_receive(session *, const uint8_t *, size_t);
void broadcast_event(const uint8_t *, size_t);
void sessions_drop(session *);
void system_reboot(unsigned, bool);
void services_changed(void);
void hid_init(void);
bool hid_enqueue(esh_command *, transport_kind);
void hid_abort(void);
void hid_release_source(transport_kind);
void session_ended(session *s);
unsigned hid_depth(void);
bool hid_mounted(void);
bool hid_suspended(void);
uint8_t hid_leds(void);
void rpc_init(void);
cJSON *rpc_dispatch(session *, const cJSON *, int *);
bool rpc_public(const char *);
bool rpc_slow(const cJSON *);
cJSON *device_info(bool);
void net_init(void);
bool net_enabled(void);
void net_apply(void);
cJSON *net_info(void);
cJSON *net_scan(int *);
bool net_ap_active(void);
void mdns_refresh(void);
void http_init(void);
void http_apply(void);
void http_events(const uint8_t *, size_t);
void http_drop(session *);
bool http_recv_retryable(int);
void ble_init(void);
void ble_apply(void);
bool ble_enabled(void);
void ble_events(const uint8_t *, size_t);
void ble_drop(session *);
void ble_reset_bonds(void);
unsigned ble_connections(void);
void mqtt_apply(void);
void mqtt_events(const uint8_t *, size_t);
bool mqtt_connected(void);
void tailscale_apply(void);
cJSON *tailscale_info(void);
void tailscale_reset(void);
int ota_start(const char *, const char *);
cJSON *ota_status(void);
int ota_upload(httpd_req_t *);
void ota_mark_healthy(void);
esp_err_t tls_prepare(bool);
/* Mutable credential/fingerprint pointers: callers must hold config_mutex. */
const char *tls_cert(void);
const char *tls_key(void);
const char *tls_fingerprint(void);

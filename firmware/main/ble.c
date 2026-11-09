#include "esphid.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "store/config/ble_store_config.h"
#include "os/os_mbuf.h"
#include "esp_nimble_hci.h"
/* IDF provides the store initializer without a public declaration. */
void ble_store_config_init(void);
static const ble_uuid128_t service_uuid = BLE_UUID128_INIT(
    0xac, 0x7d, 0x7d, 0xed, 0x12, 0x67, 0x7b, 0xb4, 0xf5, 0x40, 0x17, 0xa2, 1, 0, 0x5c, 0x70);
static const ble_uuid128_t hid_uuid = BLE_UUID128_INIT(
    0xac, 0x7d, 0x7d, 0xed, 0x12, 0x67, 0x7b, 0xb4, 0xf5, 0x40, 0x17, 0xa2, 2, 0, 0x5c, 0x70);
static const ble_uuid128_t events_uuid = BLE_UUID128_INIT(
    0xac, 0x7d, 0x7d, 0xed, 0x12, 0x67, 0x7b, 0xb4, 0xf5, 0x40, 0x17, 0xa2, 3, 0, 0x5c, 0x70);
static const ble_uuid128_t rpc_uuid = BLE_UUID128_INIT(
    0xac, 0x7d, 0x7d, 0xed, 0x12, 0x67, 0x7b, 0xb4, 0xf5, 0x40, 0x17, 0xa2, 4, 0, 0x5c, 0x70);
static uint16_t event_handle, rpc_handle;
static uint8_t address_type;
static SemaphoreHandle_t mutex;
static bool enabled;
static char advertised_name[30];
static bool name_complete;
typedef struct {
    bool used, event_sub, rpc_sub, tx_busy, rpc_busy;
    uint32_t generation;
    uint16_t conn;
    struct ble_npl_callout retry;
    session auth;
    char *rx, *tx;
    size_t rx_len, tx_len, tx_off;
} ble_client;
static ble_client peers[2];
static uint32_t next_generation;
static QueueHandle_t rpc_queue;
typedef struct {
    ble_client *peer;
    uint32_t generation;
    char *json;
    size_t len;
} rpc_job;
static ble_client *find(uint16_t conn) {
    for (int i = 0; i < 2; i++)
        if (peers[i].used && peers[i].conn == conn)
            return &peers[i];
    return NULL;
}
static bool encrypted(ble_client *p) {
    struct ble_gap_conn_desc d;
    return ble_gap_conn_find(p->conn, &d) == 0 && d.sec_state.encrypted;
}
static void event_send(session *s, const uint8_t *data, size_t n) {
    ble_client *p = s->ctx;
    if (!p->used || !p->event_sub || !encrypted(p))
        return;
    struct os_mbuf *om = ble_hs_mbuf_from_flat(data, n);
    if (om)
        ble_gatts_notify_custom(p->conn, event_handle, om);
}
static void rpc_next(ble_client *p) {
    if (!p->used || !p->tx || p->tx_busy || p->tx_off >= p->tx_len)
        return;
    uint16_t mtu = ble_att_mtu(p->conn);
    size_t n = p->tx_len - p->tx_off;
    if (n > mtu - 4)
        n = mtu - 4;
    if (n > 511)
        n = 511;
    uint8_t data[514];
    data[0] = (p->tx_off == 0 ? 1 : 0) | (p->tx_off + n == p->tx_len ? 2 : 0);
    memcpy(data + 1, p->tx + p->tx_off, n);
    struct os_mbuf *om = ble_hs_mbuf_from_flat(data, n + 1);
    if (om && ble_gatts_notify_custom(p->conn, rpc_handle, om) == 0) {
        p->tx_off += n;
        p->tx_busy = true;
    } else
        ble_npl_callout_reset(&p->retry, ble_npl_time_ms_to_ticks32(2));
}
static void retry_rpc(struct ble_npl_event *e) {
    ble_client *p = ble_npl_event_get_arg(e);
    xSemaphoreTakeRecursive(mutex, portMAX_DELAY);
    rpc_next(p);
    xSemaphoreGiveRecursive(mutex);
}
/* A worker holds only a snapshot. These short callbacks serialize events and
 * HID admission with disconnect, without keeping the host behind slow RPC work. */
static bool worker_live(rpc_job *job) {
    return job->peer->used && job->peer->generation == job->generation;
}
static void commit_auth(ble_client *p, session *auth) {
    if (!session_authorized(&p->auth) && session_authorized(auth)) {
        p->auth.authenticated = true;
        p->auth.epoch = auth->epoch;
    }
}
static void worker_event(session *auth, const uint8_t *data, size_t n) {
    rpc_job *job = auth->ctx;
    xSemaphoreTakeRecursive(mutex, portMAX_DELAY);
    if (worker_live(job)) {
        commit_auth(job->peer, auth);
        event_send(&job->peer->auth, data, n);
    }
    xSemaphoreGiveRecursive(mutex);
}
static bool worker_enqueue(session *auth, esh_command *command) {
    rpc_job *job = auth->ctx;
    xSemaphoreTakeRecursive(mutex, portMAX_DELAY);
    bool ok = worker_live(job) && session_authorized(auth);
    if (ok) {
        commit_auth(job->peer, auth);
        ok = hid_enqueue(command, TRANSPORT_BLE);
    }
    xSemaphoreGiveRecursive(mutex);
    return ok;
}
static void rpc_worker(void *arg) {
    rpc_job job;
    for (;;) {
        xQueueReceive(rpc_queue, &job, portMAX_DELAY);
        cJSON *req = cJSON_ParseWithLengthOpts(job.json, job.len + 1, NULL, true);
        free(job.json);
        xSemaphoreTakeRecursive(mutex, portMAX_DELAY);
        ble_client *p = job.peer;
        if (!worker_live(&job)) {
            xSemaphoreGiveRecursive(mutex);
            cJSON_Delete(req);
            continue;
        }
        session auth = p->auth;
        auth.identity = &p->auth;
        auth.send = worker_event;
        auth.enqueue = worker_enqueue;
        auth.ctx = &job;
        xSemaphoreGiveRecursive(mutex);
        int status;
        cJSON *res = rpc_dispatch(&auth, req, &status);
        cJSON_Delete(req);
        char *out = cJSON_PrintUnformatted(res);
        cJSON_Delete(res);
        xSemaphoreTakeRecursive(mutex, portMAX_DELAY);
        if (worker_live(&job)) {
            commit_auth(p, &auth);
            p->rpc_busy = false;
            p->tx = out;
            p->tx_len = out ? strlen(out) : 0;
            p->tx_off = 0;
            if (out && p->rpc_sub && encrypted(p))
                rpc_next(p);
            else {
                free(p->tx);
                p->tx = NULL;
            }
        } else
            free(out);
        xSemaphoreGiveRecursive(mutex);
    }
}
static int access_cb(uint16_t conn, uint16_t attr, struct ble_gatt_access_ctxt *ctx, void *arg) {
    xSemaphoreTakeRecursive(mutex, portMAX_DELAY);
    ble_client *p = find(conn);
    int rc = 0;
    uint16_t n = OS_MBUF_PKTLEN(ctx->om);
    if (!p || !encrypted(p)) {
        rc = BLE_ATT_ERR_INSUFFICIENT_ENC;
        goto end;
    }
    if ((intptr_t)arg == 1) {
        uint8_t data[512];
        if (n > sizeof(data) || n > ble_att_mtu(conn) - 3) {
            rc = BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            goto end;
        }
        os_mbuf_copydata(ctx->om, 0, n, data);
        packet_receive(&p->auth, data, n);
    } else {
        if (!p->rpc_sub || n < 1 || p->tx || p->rpc_busy) {
            rc = BLE_ATT_ERR_UNLIKELY;
            goto end;
        }
        uint8_t header;
        os_mbuf_copydata(ctx->om, 0, 1, &header);
        if (header & ~3) {
            rc = BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            goto reset;
        }
        if (header & 1) {
            free(p->rx);
            p->rx = malloc(ESH_RPC_MAX + 1);
            p->rx_len = 0;
        }
        if (!p->rx || p->rx_len + n - 1 > ESH_RPC_MAX) {
            rc = BLE_ATT_ERR_INSUFFICIENT_RES;
            goto reset;
        }
        os_mbuf_copydata(ctx->om, 1, n - 1, p->rx + p->rx_len);
        p->rx_len += n - 1;
        if (header & 2) {
            p->rx[p->rx_len] = 0;
            rpc_job job = {.peer = p, .generation = p->generation, .json = p->rx, .len = p->rx_len};
            if (xQueueSend(rpc_queue, &job, 0) != pdTRUE) {
                rc = BLE_ATT_ERR_INSUFFICIENT_RES;
                goto reset;
            }
            p->rpc_busy = true;
            p->rx = NULL; /* Ownership transferred to the worker. */
            p->rx_len = 0;
        }
    }
    goto end;
reset:
    free(p->rx);
    p->rx = NULL;
    p->rx_len = 0;
end:
    xSemaphoreGiveRecursive(mutex);
    return rc;
}
/* NimBLE validates access_cb even for values with notify-only properties. */
static int notify_only_access(uint16_t conn, uint16_t attr, struct ble_gatt_access_ctxt *ctx,
                              void *arg) {
    return BLE_ATT_ERR_READ_NOT_PERMITTED;
}
static const struct ble_gatt_svc_def services[] = {
    {.type = BLE_GATT_SVC_TYPE_PRIMARY,
     .uuid = &service_uuid.u,
     .characteristics =
         (struct ble_gatt_chr_def[]){{.uuid = &hid_uuid.u,
                                      .access_cb = access_cb,
                                      .arg = (void *)1,
                                      .flags = BLE_GATT_CHR_F_WRITE_NO_RSP | BLE_GATT_CHR_F_WRITE |
                                               BLE_GATT_CHR_F_WRITE_ENC},
                                     {.uuid = &events_uuid.u,
                                      .access_cb = notify_only_access,
                                      .val_handle = &event_handle,
                                      .flags = BLE_GATT_CHR_F_NOTIFY | BLE_GATT_CHR_F_READ_ENC},
                                     {.uuid = &rpc_uuid.u,
                                      .access_cb = access_cb,
                                      .val_handle = &rpc_handle,
                                      .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_NOTIFY |
                                               BLE_GATT_CHR_F_WRITE_ENC | BLE_GATT_CHR_F_READ_ENC},
                                     {0}}},
    {0}};
static void advertise(void);
static int gap_event(struct ble_gap_event *e, void *arg) {
    xSemaphoreTakeRecursive(mutex, portMAX_DELAY);
    switch (e->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (!e->connect.status) {
            ble_client *p = NULL;
            for (int i = 0; i < 2; i++)
                if (!peers[i].used) {
                    p = &peers[i];
                    break;
                }
            if (p) {
                p->used = true;
                p->generation = ++next_generation;
                p->rpc_busy = false;
                p->conn = e->connect.conn_handle;
                p->event_sub = p->rpc_sub = p->tx_busy = false;
                session_init(&p->auth, TRANSPORT_BLE, event_send, p);
                struct ble_gap_upd_params u = {
                    .itvl_min = 6, .itvl_max = 12, .latency = 0, .supervision_timeout = 200};
                ble_gap_update_params(p->conn, &u);
                ble_gap_set_data_len(p->conn, 251, 2120);
                ble_gap_set_prefered_le_phy(p->conn, BLE_GAP_LE_PHY_2M_MASK, BLE_GAP_LE_PHY_2M_MASK,
                                            0);
                ble_gap_security_initiate(p->conn);
            }
        }
        advertise();
        break;
    case BLE_GAP_EVENT_DISCONNECT: {
        ble_client *p = find(e->disconnect.conn.conn_handle);
        if (p) {
            session_ended(&p->auth);
            p->auth.authenticated = false;
            ble_npl_callout_stop(&p->retry);
            free(p->rx);
            free(p->tx);
            p->rx = p->tx = NULL;
            p->rx_len = p->tx_len = p->tx_off = 0;
            p->used = false;
            p->tx_busy = p->rpc_busy = false;
        }
        advertise();
        break;
    }
    case BLE_GAP_EVENT_ADV_COMPLETE:
        advertise();
        break;
    case BLE_GAP_EVENT_SUBSCRIBE: {
        ble_client *p = find(e->subscribe.conn_handle);
        if (p) {
            if (e->subscribe.attr_handle == event_handle) {
                p->event_sub = e->subscribe.cur_notify;
                if (p->event_sub && session_authorized(&p->auth))
                    session_leds(&p->auth);
            }
            if (e->subscribe.attr_handle == rpc_handle)
                p->rpc_sub = e->subscribe.cur_notify;
        }
        break;
    }
    case BLE_GAP_EVENT_NOTIFY_TX: {
        ble_client *p = find(e->notify_tx.conn_handle);
        if (p && e->notify_tx.attr_handle == rpc_handle && p->tx) {
            p->tx_busy = false;
            if (p->tx_off == p->tx_len || e->notify_tx.status) {
                free(p->tx);
                p->tx = NULL;
            } else
                rpc_next(p);
        }
        break;
    }
    case BLE_GAP_EVENT_REPEAT_PAIRING: {
        struct ble_gap_conn_desc d;
        if (!ble_gap_conn_find(e->repeat_pairing.conn_handle, &d))
            ble_store_util_delete_peer(&d.peer_id_addr);
        xSemaphoreGiveRecursive(mutex);
        return BLE_GAP_REPEAT_PAIRING_RETRY;
    }
    default:
        break;
    }
    xSemaphoreGiveRecursive(mutex);
    return 0;
}
static void advertise(void) {
    if (ble_connections() >= 2)
        return;
    struct ble_hs_adv_fields fields = {.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP,
                                       .uuids128 = (ble_uuid128_t *)&service_uuid,
                                       .num_uuids128 = 1,
                                       .uuids128_is_complete = 1};
    ble_gap_adv_set_fields(&fields);
    struct ble_hs_adv_fields response = {.name = (uint8_t *)advertised_name,
                                         .name_len = strlen(advertised_name),
                                         .name_is_complete = name_complete};
    ble_gap_adv_rsp_set_fields(&response);
    struct ble_gap_adv_params params = {.conn_mode = BLE_GAP_CONN_MODE_UND,
                                        .disc_mode = BLE_GAP_DISC_MODE_GEN,
                                        .itvl_min = 32,
                                        .itvl_max = 48};
    ble_gap_adv_start(address_type, NULL, BLE_HS_FOREVER, &params, gap_event, NULL);
}
static void sync_cb(void) {
    xSemaphoreTakeRecursive(mutex, portMAX_DELAY);
    ble_hs_id_infer_auto(0, &address_type);
    ble_gap_set_prefered_default_le_phy(BLE_GAP_LE_PHY_2M_MASK, BLE_GAP_LE_PHY_2M_MASK);
    advertise();
    transport_started = true;
    xSemaphoreGiveRecursive(mutex);
}
static void host(void *arg) {
    nimble_port_run();
    nimble_port_freertos_deinit();
}
unsigned ble_connections(void) {
    unsigned n = 0;
    for (int i = 0; i < 2; i++)
        n += peers[i].used;
    return n;
}
void ble_init(void) {
    mutex = xSemaphoreCreateRecursiveMutex();
    rpc_queue = xQueueCreate(2, sizeof(rpc_job));
    assert(mutex && rpc_queue);
    assert(xTaskCreate(rpc_worker, "ble_rpc", 8192, NULL, 4, NULL) == pdPASS);
    ESP_ERROR_CHECK(nimble_port_init());
    for (int i = 0; i < 2; i++)
        ble_npl_callout_init(&peers[i].retry, nimble_port_get_dflt_eventq(), retry_rpc, &peers[i]);
    enabled = true;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    cJSON *c = config_snapshot(false);
    char name[30];
    const char *configured_name = str(obj(c, "name"));
    size_t name_len = esh_utf8_prefix(configured_name, strlen(configured_name), sizeof(name) - 1);
    memcpy(name, configured_name, name_len);
    name[name_len] = 0;
    memcpy(advertised_name, name, name_len + 1);
    name_complete = name_len == strlen(configured_name);
    ble_svc_gap_device_name_set(advertised_name);
    cJSON_Delete(c);
    ble_hs_cfg.sync_cb = sync_cb;
    ble_hs_cfg.sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_mitm = 0;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_store_config_init();
    ble_att_set_preferred_mtu(517);
    assert(!ble_gatts_count_cfg(services));
    assert(!ble_gatts_add_svcs(services));
    nimble_port_freertos_init(host);
}
bool ble_enabled(void) {
    return enabled;
}
void ble_apply(void) {
    if (!enabled)
        return;
    cJSON *c = config_snapshot(false);
    char name[30];
    const char *configured_name = str(obj(c, "name"));
    size_t name_len = esh_utf8_prefix(configured_name, strlen(configured_name), sizeof(name) - 1);
    memcpy(name, configured_name, name_len);
    name[name_len] = 0;
    bool complete = name_len == strlen(configured_name);
    cJSON_Delete(c);
    xSemaphoreTakeRecursive(mutex, portMAX_DELAY);
    memcpy(advertised_name, name, name_len + 1);
    name_complete = complete;
    ble_svc_gap_device_name_set(advertised_name);
    if (ble_hs_synced()) {
        ble_gap_adv_stop();
        advertise();
    }
    xSemaphoreGiveRecursive(mutex);
}
void ble_events(const uint8_t *p, size_t n) {
    if (!enabled)
        return;
    xSemaphoreTakeRecursive(mutex, portMAX_DELAY);
    for (int i = 0; i < 2; i++)
        if (peers[i].used && session_authorized(&peers[i].auth))
            event_send(&peers[i].auth, p, n);
    xSemaphoreGiveRecursive(mutex);
}
void ble_drop(session *keep) {
    if (!enabled)
        return;
    xSemaphoreTakeRecursive(mutex, portMAX_DELAY);
    for (int i = 0; i < 2; i++)
        if (peers[i].used && &peers[i].auth != keep)
            ble_gap_terminate(peers[i].conn, BLE_ERR_REM_USER_CONN_TERM);
    xSemaphoreGiveRecursive(mutex);
}
void ble_reset_bonds(void) {
    if (enabled) {
        ble_drop(NULL);
        ble_store_clear();
    }
    nvs_handle_t n;
    if (nvs_open("nimble_bond", NVS_READWRITE, &n) == ESP_OK) {
        nvs_erase_all(n);
        nvs_commit(n);
        nvs_close(n);
    }
}

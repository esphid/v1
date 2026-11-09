#include "esphid.h"
#include "mqtt_client.h"
#include "esp_crt_bundle.h"
#include "esp_transport.h"
#include "lwip/sockets.h"
#include "lwip/tcp.h"
static esp_mqtt_client_handle_t client;
static _Atomic bool connected;
static bool require_token = true;
static SemaphoreHandle_t client_mutex;
static QueueHandle_t rpc_queue;
static _Atomic uint32_t broker_generation;
typedef struct {
    session auth;
    cJSON *request;
    char reply[192];
    uint32_t generation;
} mqtt_job;
static void rpc_worker(void *arg) {
    mqtt_job *job;
    for (;;) {
        xQueueReceive(rpc_queue, &job, portMAX_DELAY);
        if (job->generation == atomic_load(&broker_generation)) {
            int code;
            cJSON *res = rpc_dispatch(&job->auth, job->request, &code);
            char *out = cJSON_PrintUnformatted(res);
            cJSON_Delete(res);
            xSemaphoreTake(client_mutex, portMAX_DELAY);
            if (out && client && connected && job->generation == atomic_load(&broker_generation))
                esp_mqtt_client_enqueue(client, job->reply, out, 0, 0, 0, true);
            xSemaphoreGive(client_mutex);
            free(out);
        }
        cJSON_Delete(job->request);
        free(job);
    }
}
static char prefix[129], configuration[2048];
static session auth;
static esp_transport_handle_t transport;
static char scheme[8];
static char *rx;
static size_t rx_size, rx_used;
static char rx_topic[192];
static void topic(char *out, size_t cap, const char *suffix) {
    snprintf(out, cap, "%s/%s", prefix, suffix);
}
static void publish_event_unlocked(const uint8_t *p, size_t n) {
    if (!client || !connected)
        return;
    char t[192];
    topic(t, sizeof(t), "events");
    esp_mqtt_client_publish(client, t, (const char *)p, n, 0, 0);
}
/* MQTT-task callbacks must NOT take client_mutex: mqtt_apply holds it while
 * stop waits for this task. The handle/prefix remain live until stop returns. */
static void mqtt_task_send_event(session *s, const uint8_t *p, size_t n) {
    publish_event_unlocked(p, n);
}
void mqtt_events(const uint8_t *p, size_t n) {
    /* Shared event-fanout task: serialize handle use with stop/destroy, and
     * recheck connection state only after acquiring the lifetime guard. */
    if (!client_mutex)
        return;
    xSemaphoreTake(client_mutex, portMAX_DELAY);
    publish_event_unlocked(p, n);
    xSemaphoreGive(client_mutex);
}
bool mqtt_connected(void) {
    return connected;
}
static void message(const char *t, const uint8_t *data, size_t n) {
    char expected[192];
    session_init(&auth, TRANSPORT_MQTT, mqtt_task_send_event, NULL);
    if (!require_token && claimed()) {
        auth.authenticated = true;
        auth.epoch = auth_epoch;
    }
    topic(expected, sizeof(expected), "hid");
    if (!strcmp(t, expected)) {
        if (require_token && (n < 2 || data[0] != ESH_AUTH)) {
            command_error(&auth, ESH_UNAUTHORIZED, n ? data[0] : 0);
            return;
        }
        packet_receive(&auth, data, n);
        return;
    }
    topic(expected, sizeof(expected), "hid/json");
    bool hid = !strcmp(t, expected);
    topic(expected, sizeof(expected), "rpc/req");
    if (!hid && strcmp(t, expected))
        return;
    cJSON *j = cJSON_ParseWithLengthOpts((char *)data, n + 1, NULL, true);
    if (!j)
        return;
    if (require_token) {
        const cJSON *token = obj(j, "token");
        if (!token || authenticate(&auth, (uint8_t *)str(token), strlen(str(token)))) {
            if (hid) {
                command_error(&auth, ESH_UNAUTHORIZED, 0);
                cJSON_Delete(j);
                return;
            }
        }
    }
    if (hid) {
        char error[80];
        if (!esh_json(j, command_receive, &auth, error, sizeof(error)) && auth.status == 200)
            command_error(&auth, ESH_BAD_ARG, 0);
    } else {
        topic(expected, sizeof(expected), "rpc/res");
        const char *reply = str(obj(j, "reply_to"));
        if (*reply && !strpbrk(reply, "+#"))
            strlcpy(expected, reply, sizeof(expected));
        bool busy = false;
        if (rpc_slow(j) && (!require_token || session_authorized(&auth))) {
            mqtt_job *job = calloc(1, sizeof(*job));
            if (job) {
                job->auth = auth;
                job->auth.send = NULL;
                job->request = j;
                job->generation = atomic_load(&broker_generation);
                strlcpy(job->reply, expected, sizeof(job->reply));
                if (xQueueSend(rpc_queue, &job, 0) == pdTRUE)
                    return; /* Worker owns the parsed request. */
                free(job);
            }
            busy = true;
        }
        int status;
        cJSON *res;
        if (busy || (require_token && !session_authorized(&auth))) {
            res = cJSON_CreateObject();
            cJSON *id = obj(j, "id");
            cJSON_AddItemToObject(res, "id", id ? cJSON_Duplicate(id, true) : cJSON_CreateNull());
            cJSON *e = sub(res, "error");
            JN(e, "code", busy ? 503 : auth.status == 429 ? 429 : 401);
            JS(e, "message", busy ? "busy" : "unauthorized");
        } else
            res = rpc_dispatch(&auth, j, &status);
        char *out = cJSON_PrintUnformatted(res);
        cJSON_Delete(res);
        if (out) {
            esp_mqtt_client_publish(client, expected, out, 0, 0, 0);
            free(out);
        }
    }
    cJSON_Delete(j);
}
static void publish_status(void) {
    if (!client || !connected)
        return;
    char t[192];
    topic(t, sizeof(t), "status");
    cJSON *j = device_info(false);
    JB(j, "online", true);
    char *out = cJSON_PrintUnformatted(j);
    cJSON_Delete(j);
    if (out) {
        esp_mqtt_client_publish(client, t, out, 0, 1, 1);
        free(out);
    }
}
static void mqtt_event(void *arg, esp_event_base_t base, int32_t id, void *data) {
    esp_mqtt_event_handle_t e = data;
    if (id == MQTT_EVENT_BEFORE_CONNECT) {
        transport = esp_mqtt_client_get_transport(client, scheme);
    }
    if (id == MQTT_EVENT_CONNECTED) {
        if (transport) {
            int fd = esp_transport_get_socket(transport), yes = 1;
            if (fd >= 0)
                setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(yes));
        }
        connected = true;
        char t[192];
        const char *topics[] = {"hid", "hid/json", "rpc/req"};
        for (int i = 0; i < 3; i++) {
            topic(t, sizeof(t), topics[i]);
            esp_mqtt_client_subscribe(client, t, 0);
        }
        topic(t, sizeof(t), "status");
        cJSON *j = device_info(false);
        JB(j, "online", true);
        char *s = cJSON_PrintUnformatted(j);
        cJSON_Delete(j);
        if (s) {
            esp_mqtt_client_publish(client, t, s, 0, 1, 1);
            free(s);
        }
    } else if (id == MQTT_EVENT_DISCONNECTED) {
        connected = false;
        atomic_fetch_add(&broker_generation, 1);
        if (usb_started)
            hid_release_source(TRANSPORT_MQTT);
    } else if (id == MQTT_EVENT_DATA) {
        if (!e->current_data_offset) {
            free(rx);
            rx = NULL;
            rx_used = 0;
            rx_size = e->total_data_len;
            if (rx_size > ESH_RPC_MAX || e->topic_len >= (int)sizeof(rx_topic))
                return;
            memcpy(rx_topic, e->topic, e->topic_len);
            rx_topic[e->topic_len] = 0;
            char t[192];
            topic(t, sizeof(t), "hid");
            if (!strcmp(t, rx_topic) && rx_size > ESH_PACKET_MAX)
                return;
            if (e->data_len == e->total_data_len) { /* Binary hot path parses directly from MQTT's
                                                       receive buffer. */
                if (!strcmp(t, rx_topic)) {
                    message(rx_topic, (uint8_t *)e->data, e->data_len);
                    return;
                }
            }
            rx = malloc(rx_size + 1);
        }
        if (!rx || e->current_data_offset != (int)rx_used || rx_used + e->data_len > rx_size)
            return;
        memcpy(rx + rx_used, e->data, e->data_len);
        rx_used += e->data_len;
        if (rx_used == rx_size) {
            rx[rx_size] = 0;
            message(rx_topic, (uint8_t *)rx, rx_size);
            free(rx);
            rx = NULL;
        }
    }
}
void mqtt_apply(void) {
    if (!client_mutex) {
        client_mutex = xSemaphoreCreateMutex();
        rpc_queue = xQueueCreate(2, sizeof(mqtt_job *));
        assert(client_mutex && rpc_queue);
        assert(xTaskCreate(rpc_worker, "mqtt_rpc", 8192, NULL, 4, NULL) == pdPASS);
    }
    cJSON *c = config_snapshot(true), *m = obj(c, "mqtt");
    char *serialized = cJSON_PrintUnformatted(m);
    if (!serialized) {
        cJSON_Delete(c);
        return;
    }
    if (!strcmp(serialized, configuration)) {
        free(serialized);
        cJSON_Delete(c);
        publish_status();
        return;
    }
    strlcpy(configuration, serialized, sizeof(configuration));
    free(serialized);
    xSemaphoreTake(client_mutex, portMAX_DELAY);
    atomic_fetch_add(&broker_generation, 1);
    if (client) {
        if (usb_started)
            hid_release_source(TRANSPORT_MQTT);
        esp_mqtt_client_stop(client);
        esp_mqtt_client_destroy(client);
        client = NULL;
        connected = false;
    }
    require_token = cJSON_IsTrue(obj(m, "require_token"));
    strlcpy(prefix, str(obj(m, "topic_prefix")), sizeof(prefix));
    const char *uri = str(obj(m, "uri"));
    size_t scheme_len = strcspn(uri, ":");
    if (scheme_len >= sizeof(scheme))
        scheme_len = 0;
    memcpy(scheme, uri, scheme_len);
    scheme[scheme_len] = 0;
    if (cJSON_IsTrue(obj(m, "enabled"))) {
        char status_topic[192];
        topic(status_topic, sizeof(status_topic), "status");
        esp_mqtt_client_config_t cfg = {
            .broker.address.uri = str(obj(m, "uri")),
            .broker.verification.crt_bundle_attach = esp_crt_bundle_attach,
            .credentials.username = str(obj(m, "username")),
            .credentials.authentication.password = str(obj(m, "password")),
            .credentials.client_id = *str(obj(m, "client_id")) ? str(obj(m, "client_id")) : NULL,
            .session.keepalive = 30,
            .session.disable_clean_session = false,
            .session.protocol_ver = MQTT_PROTOCOL_V_3_1_1,
            .session.last_will = {.topic = status_topic,
                                  .msg = "{\"online\":false}",
                                  .qos = 1,
                                  .retain = 1},
            .buffer.size = 2048,
            .task.priority = 18,
            .task.stack_size = 8192};
        client = esp_mqtt_client_init(&cfg);
        if (client) {
            esp_mqtt_client_register_event(client, ESP_EVENT_ANY_ID, mqtt_event, NULL);
            esp_mqtt_client_start(client);
        }
    }
    xSemaphoreGive(client_mutex);
    cJSON_Delete(c);
}

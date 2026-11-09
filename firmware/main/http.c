#include "esphid.h"
#include "esp_https_server.h"
#include "esp_tls_errors.h"
#include "lwip/sockets.h"
#include "lwip/tcp.h"
#include <strings.h>
static httpd_handle_t plain, secure;
static int secure_port;
static char served_fingerprint[96];
static SemaphoreHandle_t ws_mutex;
static QueueHandle_t work_queue;
static unsigned secure_pending;
static bool secure_stopping;
static uint32_t next_generation;
static void http_watchdog(void *);
typedef struct {
    bool used, close_requested;
    httpd_handle_t server;
    int fd;
    uint32_t generation;
    int64_t opened_at, last_frame_at;
    session auth;
    uint8_t *fragment;
    size_t fragment_len;
    httpd_ws_type_t fragment_type;
} ws_client;
static ws_client clients[4];
static void status_set(httpd_req_t *r, int code) {
    const char *s = code == 200   ? "200 OK"
                    : code == 204 ? "204 No Content"
                    : code == 400 ? "400 Bad Request"
                    : code == 401 ? "401 Unauthorized"
                    : code == 404 ? "404 Not Found"
                    : code == 409 ? "409 Conflict"
                    : code == 413 ? "413 Content Too Large"
                    : code == 426 ? "426 Upgrade Required"
                    : code == 429 ? "429 Too Many Requests"
                    : code == 503 ? "503 Service Unavailable"
                                  : "500 Internal Server Error";
    httpd_resp_set_status(r, s);
}
static esp_err_t json_reply(httpd_req_t *r, cJSON *j, int code) {
    char *s = cJSON_PrintUnformatted(j);
    cJSON_Delete(j);
    status_set(r, code);
    httpd_resp_set_type(r, "application/json");
    httpd_resp_set_hdr(r, "Cache-Control", "no-store");
    esp_err_t e = httpd_resp_sendstr(r, s ? s : "{}");
    free(s);
    return e;
}
static esp_err_t error_reply(httpd_req_t *r, int code) {
    cJSON *j = cJSON_CreateObject();
    JN(j, "code", code);
    JS(j, "message",
       code == 401   ? "unauthorized"
       : code == 429 ? "locked out"
                     : "request rejected");
    return json_reply(r, j, code);
}
static int header_auth(httpd_req_t *r, session *s, bool query, bool optional) {
    char value[256];
    bool provided = false;
    int a = 0;
    if (httpd_req_get_hdr_value_len(r, "Authorization")) {
        provided = true;
        if (httpd_req_get_hdr_value_str(r, "Authorization", value, sizeof(value)) == ESP_OK &&
            !strncmp(value, "Bearer ", 7))
            a = optional ? authenticate_optional(s, (uint8_t *)value + 7, strlen(value + 7))
                         : authenticate(s, (uint8_t *)value + 7, strlen(value + 7));
        else
            a = optional ? authenticate_optional(s, (uint8_t *)"", 0)
                         : authenticate(s, (uint8_t *)"", 0);
    } else if (query) {
        char q[256];
        if (httpd_req_get_url_query_str(r, q, sizeof(q)) == ESP_OK &&
            httpd_query_key_value(q, "token", value, sizeof(value)) == ESP_OK) {
            provided = true;
            char *src = value, *dst = value;
            while (*src) {
                if (src[0] == '%' && src[1] && src[2]) {
                    char h[3] = {src[1], src[2], 0};
                    char *end;
                    long byte = strtol(h, &end, 16);
                    if (!*end && byte > 0 && byte < 128) {
                        *dst++ = (char)byte;
                        src += 3;
                        continue;
                    }
                }
                *dst++ = *src++;
            }
            *dst = 0;
            a = authenticate(s, (uint8_t *)value, strlen(value));
        }
    }
    memset(value, 0, sizeof(value));
    return provided && !optional ? (a == 2 ? 429 : a ? 401 : 200) : 200;
}
static esp_err_t open_socket(httpd_handle_t h, int fd) {
    int yes = 1, idle = 5, interval = 2, count = 3;
    if (setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(yes)) ||
        setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &yes, sizeof(yes)) ||
        setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle)) ||
        setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &interval, sizeof(interval)) ||
        setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &count, sizeof(count)))
        return ESP_FAIL;
    return ESP_OK;
}
static void no_delay(httpd_req_t *r) {
    int yes = 1;
    setsockopt(httpd_req_to_sockfd(r), IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(yes));
}
bool http_recv_retryable(int n) {
    /* HTTPS's recv override returns raw ESP-TLS errors, unlike plain HTTP. */
    return n == HTTPD_SOCK_ERR_TIMEOUT || n == ESP_TLS_ERR_SSL_WANT_READ ||
           n == ESP_TLS_ERR_SSL_WANT_WRITE || n == ESP_TLS_ERR_SSL_TIMEOUT;
}
static char *body(httpd_req_t *r, size_t max) {
    if (r->content_len > max)
        return NULL;
    char *b = malloc(r->content_len + 1);
    if (!b)
        return NULL;
    size_t off = 0;
    unsigned timeout = 0;
    int64_t deadline = now_ms() + 8000;
    while (off < r->content_len) {
        if (now_ms() >= deadline) {
            free(b);
            return NULL;
        }
        int n = httpd_req_recv(r, b + off, r->content_len - off);
        if (http_recv_retryable(n) && timeout++ < 3)
            continue;
        if (n <= 0) {
            free(b);
            return NULL;
        }
        off += n;
    }
    b[off] = 0;
    return b;
}
static cJSON *parse_body(const char *b, size_t n) {
    const char *end = NULL;
    cJSON *j = cJSON_ParseWithLengthOpts(b, n + 1, &end, true);
    return j;
}
/* Called only on the owning httpd task, before its slot can be freed/reused. */
static void ws_close(ws_client *c) {
    session_ended(&c->auth);
    c->auth.authenticated = false;
    shutdown(c->fd, SHUT_RDWR);
}
static void ws_send(session *s, const uint8_t *p, size_t n) {
    ws_client *c = s->ctx;
    if (!c || !c->used)
        return;
    httpd_ws_frame_t f = {.type = HTTPD_WS_TYPE_BINARY, .payload = (uint8_t *)p, .len = n};
    if (httpd_ws_send_frame_async(c->server, c->fd, &f) != ESP_OK)
        ws_close(c);
}
static void ws_free(void *p) {
    ws_client *c = p;
    xSemaphoreTake(ws_mutex, portMAX_DELAY);
    session_ended(&c->auth);
    free(c->fragment);
    c->fragment = NULL;
    c->fragment_len = 0;
    c->used = false;
    c->auth.authenticated = false;
    xSemaphoreGive(ws_mutex);
}
/* Retain the HTTPS server until worker requests and queued replies complete.
 * The management task defers a requested restart while these references exist. */
static bool retain_server(httpd_handle_t h) {
    xSemaphoreTake(ws_mutex, portMAX_DELAY);
    bool ok = h == plain || (h == secure && !secure_stopping);
    if (ok && h == secure)
        secure_pending++;
    xSemaphoreGive(ws_mutex);
    return ok;
}
static void release_server(httpd_handle_t h) {
    xSemaphoreTake(ws_mutex, portMAX_DELAY);
    if (h == secure) {
        assert(secure_pending);
        secure_pending--;
    }
    bool retry = secure_stopping && !secure_pending;
    xSemaphoreGive(ws_mutex);
    if (retry)
        services_changed();
}
typedef struct {
    httpd_req_t *req;
    httpd_handle_t server;
    session auth;
    cJSON *request;
    bool rpc, upload;
    ws_client *peer;
    uint32_t generation;
    char *out;
} http_job;
static void ws_rpc_reply(void *arg) {
    http_job *job = arg;
    ws_client *c = job->peer;
    xSemaphoreTake(ws_mutex, portMAX_DELAY);
    bool live = c->used && c->generation == job->generation && c->server == job->server;
    xSemaphoreGive(ws_mutex);
    if (live) {
        if (!session_authorized(&c->auth) && session_authorized(&job->auth)) {
            c->auth.authenticated = true;
            c->auth.epoch = job->auth.epoch;
        }
        httpd_ws_frame_t f = {
            .type = HTTPD_WS_TYPE_TEXT, .payload = (uint8_t *)job->out, .len = strlen(job->out)};
        if (httpd_ws_send_frame_async(c->server, c->fd, &f) != ESP_OK)
            ws_close(c);
    }
    release_server(job->server);
    free(job->out);
    free(job);
}
static void http_worker(void *arg) {
    http_job *job;
    for (;;) {
        xQueueReceive(work_queue, &job, portMAX_DELAY);
        int status;
        cJSON *response;
        if (job->upload) {
            status = ota_upload(job->req);
            response = ota_status();
        } else {
            response = rpc_dispatch(&job->auth, job->request, &status);
            cJSON_Delete(job->request);
        }
        if (job->peer) {
            job->out = cJSON_PrintUnformatted(response);
            cJSON_Delete(response);
            if (job->out && httpd_queue_work(job->server, ws_rpc_reply, job) == ESP_OK)
                continue; /* The server owns the reply and its lifetime reference. */
            free(job->out);
        } else {
            if (!job->rpc && obj(response, "result")) {
                cJSON *result = cJSON_DetachItemFromObjectCaseSensitive(response, "result");
                cJSON_Delete(response);
                response = result;
            }
            /* A partial/failed upload must not be reused with unread body bytes. */
            if (job->upload && status != 200)
                httpd_resp_set_hdr(job->req, "Connection", "close");
            esp_err_t e = json_reply(job->req, response, status);
            if (e != ESP_OK || (job->upload && status != 200))
                shutdown(httpd_req_to_sockfd(job->req), SHUT_RDWR);
            /* The session stays reserved until completion; shutdown makes the
             * server observe EOF afterwards without a stale-fd close request. */
            httpd_req_async_handler_complete(job->req);
        }
        release_server(job->server);
        free(job);
    }
}
/* Takes ownership of request on both success and failure. */
static esp_err_t start_http_job(httpd_req_t *r, session *auth, cJSON *request, bool rpc,
                                bool upload) {
    http_job *job = calloc(1, sizeof(*job));
    if (!job || !retain_server(r->handle)) {
        free(job);
        cJSON_Delete(request);
        esp_err_t e = error_reply(r, 503);
        return upload ? ESP_FAIL : e;
    }
    job->server = r->handle;
    job->auth = *auth;
    job->auth.send = NULL;
    job->auth.ctx = NULL;
    job->request = request;
    job->rpc = rpc;
    job->upload = upload;
    if (httpd_req_async_handler_begin(r, &job->req) != ESP_OK) {
        release_server(job->server);
        cJSON_Delete(request);
        free(job);
        esp_err_t e = error_reply(r, 503);
        return upload ? ESP_FAIL : e;
    }
    if (xQueueSend(work_queue, &job, 0) != pdTRUE) {
        esp_err_t e = error_reply(job->req, 503);
        if (upload || e != ESP_OK)
            shutdown(httpd_req_to_sockfd(job->req), SHUT_RDWR);
        httpd_req_async_handler_complete(job->req);
        release_server(job->server);
        cJSON_Delete(request);
        free(job);
        return e;
    }
    return ESP_OK;
}
static esp_err_t start_ws_job(ws_client *c, cJSON *request) {
    http_job *job = calloc(1, sizeof(*job));
    if (!job || !retain_server(c->server)) {
        free(job);
        cJSON_Delete(request);
        return ESP_FAIL; /* Closing also releases held input. */
    }
    job->server = c->server;
    job->peer = c;
    job->generation = c->generation;
    job->auth = c->auth;
    job->auth.send = NULL;
    job->request = request;
    if (xQueueSend(work_queue, &job, 0) != pdTRUE) {
        release_server(job->server);
        cJSON_Delete(request);
        free(job);
        return ESP_FAIL;
    }
    return ESP_OK;
}
static esp_err_t websocket_open(httpd_req_t *r) {
    no_delay(r);
    if (r->method == HTTP_GET) {
        /* IDF 6.0.2 httpd_uri.c invokes the pre-handshake callback only for
         * a detected Upgrade; ordinary GETs instead reach the URI handler. */
        char upgrade[16];
        if (httpd_req_get_hdr_value_str(r, "Upgrade", upgrade, sizeof(upgrade)) != ESP_OK ||
            strcasecmp(upgrade, "websocket")) {
            status_set(r, 426);
            httpd_resp_set_hdr(r, "Upgrade", "websocket");
            return httpd_resp_sendstr(r, "WebSocket upgrade required");
        }
        xSemaphoreTake(ws_mutex, portMAX_DELAY);
        ws_client *c = NULL;
        for (int i = 0; i < 4; i++)
            if (!clients[i].used) {
                c = &clients[i];
                break;
            }
        if (!c) {
            xSemaphoreGive(ws_mutex);
            error_reply(r, 503);
            return ESP_FAIL;
        }
        *c = (ws_client){.used = true,
                         .server = r->handle,
                         .fd = httpd_req_to_sockfd(r),
                         .generation = ++next_generation,
                         .opened_at = now_ms(),
                         .last_frame_at = now_ms()};
        session_init(&c->auth, TRANSPORT_WS, ws_send, c);
        xSemaphoreGive(ws_mutex);
        int status = header_auth(r, &c->auth, true, false);
        if (status != 200) {
            ws_free(c);
            error_reply(r, status);
            return ESP_FAIL;
        }
        r->sess_ctx = c;
        r->free_ctx = ws_free;
        /* LED notification is queued after the upgrade completes. */
        if (session_authorized(&c->auth)) {
            uint8_t e[2];
            broadcast_event(e, esh_event_leds(e, hid_leds()));
        }
        return ESP_OK;
    }
    return ESP_FAIL;
}
static esp_err_t websocket(httpd_req_t *r) {
    no_delay(r);
    /* IDF skips this URI handler on a successful upgrade. Only the separately
     * registered pre-handshake callback may allocate a WebSocket slot. */
    if (r->method == HTTP_GET) {
        status_set(r, 426);
        httpd_resp_set_hdr(r, "Upgrade", "websocket");
        return httpd_resp_sendstr(r, "WebSocket upgrade required");
    }
    ws_client *c = r->sess_ctx;
    if (!c)
        return ESP_FAIL;
    httpd_ws_frame_t f = {0};
    esp_err_t e = httpd_ws_recv_frame(r, &f, 0);
    if (e)
        return e;
    size_t max = f.type == HTTPD_WS_TYPE_BINARY ? ESH_PACKET_MAX : ESH_RPC_MAX;
    if (f.len > max)
        return ESP_FAIL;
    uint8_t *p = malloc(f.len + 1);
    if (!p)
        return ESP_ERR_NO_MEM;
    f.payload = p;
    if (f.len)
        e = httpd_ws_recv_frame(r, &f, f.len);
    p[f.len] = 0;
    if (!e)
        c->last_frame_at = now_ms();
    if (!e && (f.type == HTTPD_WS_TYPE_PING || f.type == HTTPD_WS_TYPE_PONG ||
               f.type == HTTPD_WS_TYPE_CLOSE)) {
        if (f.type != HTTPD_WS_TYPE_PONG) {
            bool close = f.type == HTTPD_WS_TYPE_CLOSE;
            if (!close)
                f.type = HTTPD_WS_TYPE_PONG;
            e = httpd_ws_send_frame(r, &f);
            if (close)
                e = ESP_FAIL;
        }
        free(p);
        return e;
    }
    if (!e && (f.type == HTTPD_WS_TYPE_CONTINUE || !f.final)) {
        if (f.type != HTTPD_WS_TYPE_CONTINUE) {
            if (c->fragment || (f.type != HTTPD_WS_TYPE_BINARY && f.type != HTTPD_WS_TYPE_TEXT)) {
                free(p);
                return ESP_FAIL;
            }
            c->fragment = malloc(max + 1);
            c->fragment_type = f.type;
            c->fragment_len = 0;
        }
        size_t capacity = c->fragment_type == HTTPD_WS_TYPE_BINARY ? ESH_PACKET_MAX : ESH_RPC_MAX;
        if (!c->fragment || c->fragment_len + f.len > capacity) {
            free(p);
            return ESP_FAIL;
        }
        memcpy(c->fragment + c->fragment_len, p, f.len);
        c->fragment_len += f.len;
        free(p);
        if (!f.final)
            return ESP_OK;
        p = c->fragment;
        c->fragment = NULL;
        f.type = c->fragment_type;
        f.len = c->fragment_len;
        c->fragment_len = 0;
        p[f.len] = 0;
    } else if (!e && c->fragment) {
        free(p);
        return ESP_FAIL;
    }
    if (!e) {
        if (f.type == HTTPD_WS_TYPE_BINARY)
            packet_receive(&c->auth, p, f.len);
        else if (f.type == HTTPD_WS_TYPE_TEXT) {
            cJSON *req = parse_body((char *)p, f.len);
            if (rpc_slow(req)) {
                e = start_ws_job(c, req);
                free(p);
                return e;
            }
            int status;
            cJSON *res = rpc_dispatch(&c->auth, req, &status);
            cJSON_Delete(req);
            char *out = cJSON_PrintUnformatted(res);
            cJSON_Delete(res);
            if (out) {
                httpd_ws_frame_t reply = {
                    .type = HTTPD_WS_TYPE_TEXT, .payload = (uint8_t *)out, .len = strlen(out)};
                e = httpd_ws_send_frame(r, &reply);
                free(out);
            }
        }
    }
    free(p);
    return e;
}
typedef struct {
    uint8_t bytes[ESH_PACKET_MAX * 5];
    size_t len;
} http_events_buffer;
static void http_send(session *s, const uint8_t *p, size_t n) {
    http_events_buffer *b = s->ctx;
    if (b && b->len + n <= sizeof(b->bytes)) {
        memcpy(b->bytes + b->len, p, n);
        b->len += n;
    }
}
static bool setup_socket(httpd_req_t *r) {
    struct sockaddr_in local = {0};
    socklen_t size = sizeof(local);
    return net_ap_active() &&
           getsockname(httpd_req_to_sockfd(r), (struct sockaddr *)&local, &size) == 0 &&
           local.sin_addr.s_addr == htonl(0xc0a80401U);
}
static esp_err_t handler(httpd_req_t *r) {
    no_delay(r);
    char path[128];
    strlcpy(path, r->uri, sizeof(path));
    char *query = strchr(path, '?');
    if (query)
        *query = 0;
    if (!strcmp(path, "/")) {
        extern const uint8_t web_start[] asm("_binary_index_html_gz_start"),
            web_end[] asm("_binary_index_html_gz_end");
        httpd_resp_set_type(r, "text/html; charset=utf-8");
        httpd_resp_set_hdr(r, "Content-Encoding", "gzip");
        return httpd_resp_send(r, (const char *)web_start, web_end - web_start);
    }
    if (!strcmp(path, "/generate_204") || !strcmp(path, "/hotspot-detect.html") ||
        !strcmp(path, "/ncsi.txt") || !strcmp(path, "/connecttest.txt")) {
        if (setup_socket(r)) {
            httpd_resp_set_status(r, "302 Found");
            httpd_resp_set_hdr(r, "Location", "http://192.168.4.1/");
            return httpd_resp_sendstr(r, "");
        }
        if (!strcmp(path, "/generate_204")) {
            status_set(r, 204);
            return httpd_resp_sendstr(r, "");
        }
        httpd_resp_set_type(r, "text/plain");
        return httpd_resp_sendstr(
            r, !strcmp(path, "/hotspot-detect.html")
                   ? "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>"
               : !strcmp(path, "/ncsi.txt") ? "Microsoft NCSI"
                                            : "Microsoft Connect Test");
    }
    session s;
    http_events_buffer events = {0};
    session_init(&s, TRANSPORT_HTTP, http_send, &events);
    bool rpc = !strcmp(path, "/api/rpc");
    const char *method =
        !strcmp(path, "/api/info")        ? "info"
        : !strcmp(path, "/api/config")    ? (r->method == HTTP_GET ? "config.get" : "config.set")
        : !strcmp(path, "/api/claim")     ? "claim"
        : !strcmp(path, "/api/wifi/scan") ? "wifi.scan"
        : !strcmp(path, "/api/ota")       ? (r->method == HTTP_GET ? "ota.status" : "ota.start")
        : !strcmp(path, "/api/reboot")    ? "system.reboot"
        : !strcmp(path, "/api/factory-reset") ? "system.factory_reset"
                                              : NULL;
    int status = 200;
    /* RPC authentication is deferred until its method has been parsed. */
    if (!rpc)
        status = header_auth(r, &s, false, method && rpc_public(method));
    if (status != 200)
        return error_reply(r, status);
    if (!strcmp(path, "/api/ota/upload")) {
        if (!session_authorized(&s))
            return error_reply(r, 401);
        return start_http_job(r, &s, NULL, false, true);
    }
    char *b = NULL;
    cJSON *params = NULL;
    bool hid = !strcmp(path, "/api/hid");
    char ct[64] = "";
    if (hid)
        httpd_req_get_hdr_value_str(r, "Content-Type", ct, sizeof(ct));
    bool json_hid = hid && !strncmp(ct, "application/json", 16);
    if (r->content_len) {
        size_t max = hid && !json_hid ? ESH_PACKET_MAX : ESH_RPC_MAX;
        if (r->content_len > max) {
            httpd_resp_set_hdr(r, "Connection", "close");
            error_reply(r, 413);
            return ESP_FAIL; /* Do not drain/reuse a rejected oversized body. */
        }
        b = body(r, max);
        if (!b) {
            httpd_resp_set_hdr(r, "Connection", "close");
            error_reply(r, 400);
            return ESP_FAIL; /* Avoid httpd's post-handler unread-body purge. */
        }
    }
    if (hid) {
        bool ok;
        if (json_hid) {
            params = b ? parse_body(b, r->content_len) : NULL;
            const cJSON *token = obj(params, "token");
            if (token && !session_authorized(&s))
                authenticate(&s, (uint8_t *)str(token), strlen(str(token)));
            char err[80];
            ok = esh_json(params, command_receive, &s, err, sizeof(err));
            cJSON_Delete(params);
        } else
            ok = packet_receive(&s, (uint8_t *)b, r->content_len);
        free(b);
        if (!ok)
            return error_reply(r, s.status == 200 ? 400 : s.status);
        if (events.len) {
            httpd_resp_set_type(r, "application/octet-stream");
            return httpd_resp_send(r, (char *)events.bytes, events.len);
        }
        status_set(r, 204);
        return httpd_resp_sendstr(r, "");
    }
    if (b) {
        params = parse_body(b, r->content_len);
        free(b);
        if (!params)
            return error_reply(r, 400);
    }
    if (rpc) {
        status = header_auth(r, &s, false, rpc_public(str(obj(params, "method"))));
        if (status != 200) {
            cJSON_Delete(params);
            return error_reply(r, status);
        }
    }
    cJSON *request;
    if (rpc) {
        request = params;
        params = NULL;
    } else {
        if (!method) {
            cJSON_Delete(params);
            return error_reply(r, 404);
        }
        request = cJSON_CreateObject();
        JS(request, "method", method);
        cJSON_AddItemToObject(request, "params", params ? params : cJSON_CreateObject());
        params = NULL;
    }
    if (rpc_slow(request))
        return start_http_job(r, &s, request, rpc, false);
    cJSON *response = rpc_dispatch(&s, request, &status);
    cJSON_Delete(request);
    if (!rpc && obj(response, "result")) {
        cJSON *result = cJSON_DetachItemFromObjectCaseSensitive(response, "result");
        cJSON_Delete(response);
        response = result;
    }
    return json_reply(r, response, status);
}
static void register_handlers(httpd_handle_t h) {
    const struct {
        const char *uri;
        httpd_method_t method;
    } routes[] = {{"/", HTTP_GET},
                  {"/api/info", HTTP_GET},
                  {"/api/rpc", HTTP_POST},
                  {"/api/hid", HTTP_POST},
                  {"/api/config", HTTP_GET},
                  {"/api/config", HTTP_PATCH},
                  {"/api/claim", HTTP_POST},
                  {"/api/wifi/scan", HTTP_GET},
                  {"/api/ota", HTTP_POST},
                  {"/api/ota", HTTP_GET},
                  {"/api/ota/upload", HTTP_POST},
                  {"/api/reboot", HTTP_POST},
                  {"/api/factory-reset", HTTP_POST},
                  {"/generate_204", HTTP_GET},
                  {"/hotspot-detect.html", HTTP_GET},
                  {"/ncsi.txt", HTTP_GET},
                  {"/connecttest.txt", HTTP_GET}};
    for (size_t i = 0; i < sizeof(routes) / sizeof(*routes); i++) {
        httpd_uri_t u = {.uri = routes[i].uri, .method = routes[i].method, .handler = handler};
        ESP_ERROR_CHECK(httpd_register_uri_handler(h, &u));
    }
    httpd_uri_t ws = {.uri = "/ws",
                      .method = HTTP_GET,
                      .handler = websocket,
                      .is_websocket = true,
                      .handle_ws_control_frames = true,
                      .ws_pre_handshake_cb = websocket_open};
    ESP_ERROR_CHECK(httpd_register_uri_handler(h, &ws));
}
void http_init(void) {
    ws_mutex = xSemaphoreCreateMutex();
    work_queue = xQueueCreate(4, sizeof(http_job *));
    assert(ws_mutex && work_queue);
    for (unsigned i = 0; i < 2; i++)
        assert(xTaskCreate(http_worker, "http_work", 8192, NULL, 4, NULL) == pdPASS);
    httpd_config_t c = HTTPD_DEFAULT_CONFIG();
    c.max_uri_handlers = 24;
    c.max_open_sockets = 7;
    c.stack_size = 8192;
    c.core_id = 0;
    c.task_priority = 16;
    c.lru_purge_enable = false;
    c.open_fn = open_socket;
    c.send_wait_timeout = 1;
    c.recv_wait_timeout = 2;
    ESP_ERROR_CHECK(httpd_start(&plain, &c));
    register_handlers(plain);
    transport_started = true;
    http_apply();
    assert(xTaskCreate(http_watchdog, "ws_watchdog", 3072, NULL, 5, NULL) == pdPASS);
}
void http_apply(void) {
    if (!plain)
        return;
    cJSON *c = config_snapshot(false), *v = obj(c, "https");
    bool enabled = cJSON_IsTrue(obj(v, "enabled"));
    int port = obj(v, "port")->valueint;
    cJSON_Delete(c);
    char next_fingerprint[sizeof(served_fingerprint)];
    xSemaphoreTakeRecursive(config_mutex, portMAX_DELAY);
    strlcpy(next_fingerprint, tls_fingerprint(), sizeof(next_fingerprint));
    xSemaphoreGiveRecursive(config_mutex);
    if (secure &&
        (!enabled || port != secure_port || strcmp(served_fingerprint, next_fingerprint))) {
        xSemaphoreTake(ws_mutex, portMAX_DELAY);
        secure_stopping = true;
        bool busy = secure_pending != 0;
        xSemaphoreGive(ws_mutex);
        if (busy)
            return;
        httpd_ssl_stop(secure);
        xSemaphoreTake(ws_mutex, portMAX_DELAY);
        secure = NULL;
        secure_stopping = false;
        xSemaphoreGive(ws_mutex);
    } else {
        xSemaphoreTake(ws_mutex, portMAX_DELAY);
        secure_stopping = false;
        xSemaphoreGive(ws_mutex);
    }
    if (enabled && !secure && tls_prepare(false) == ESP_OK) {
        /* https.regenerate runs on an RPC worker. IDF copies these buffers in
         * httpd_ssl_start; keep one locked snapshot through that copy and record
         * its fingerprint, even if another regeneration finishes meanwhile. */
        struct {
            char cert[2048], key[512], fingerprint[96];
        } *identity = calloc(1, sizeof(*identity));
        if (!identity)
            return;
        xSemaphoreTakeRecursive(config_mutex, portMAX_DELAY);
        strlcpy(identity->cert, tls_cert(), sizeof(identity->cert));
        strlcpy(identity->key, tls_key(), sizeof(identity->key));
        strlcpy(identity->fingerprint, tls_fingerprint(), sizeof(identity->fingerprint));
        xSemaphoreGiveRecursive(config_mutex);
        httpd_ssl_config_t ssl = HTTPD_SSL_CONFIG_DEFAULT();
        ssl.httpd.max_uri_handlers = 24;
        ssl.httpd.max_open_sockets = 5;
        ssl.httpd.stack_size = 8192;
        ssl.httpd.core_id = 0;
        ssl.httpd.task_priority = 16;
        ssl.httpd.ctrl_port = 32769;
        ssl.httpd.lru_purge_enable = false;
        ssl.httpd.open_fn = open_socket;
        ssl.httpd.send_wait_timeout = 1;
        ssl.httpd.recv_wait_timeout = 2;
        ssl.port_secure = port;
        ssl.servercert = (uint8_t *)identity->cert;
        ssl.servercert_len = strlen(identity->cert) + 1;
        ssl.prvtkey_pem = (uint8_t *)identity->key;
        ssl.prvtkey_len = strlen(identity->key) + 1;
        httpd_handle_t started = NULL;
        if (httpd_ssl_start(&started, &ssl) == ESP_OK) {
            xSemaphoreTake(ws_mutex, portMAX_DELAY);
            secure = started;
            xSemaphoreGive(ws_mutex);
            secure_port = port;
            strlcpy(served_fingerprint, identity->fingerprint, sizeof(served_fingerprint));
            register_handlers(secure);
        }
        memset(identity, 0, sizeof(*identity));
        free(identity);
    }
}
typedef struct {
    httpd_handle_t h;
    int fd;
    uint8_t p[9];
    size_t n;
    bool idle;
    uint32_t generation;
} ws_work;
static void ws_do_work(void *arg) {
    ws_work *w = arg;
    ws_client *c = NULL;
    xSemaphoreTake(ws_mutex, portMAX_DELAY);
    for (int i = 0; i < 4; i++)
        if (clients[i].used && clients[i].server == w->h && clients[i].fd == w->fd &&
            clients[i].generation == w->generation) {
            c = &clients[i];
            break;
        }
    xSemaphoreGive(ws_mutex);
    /* This callback runs on this client's server; its slot cannot be freed or
     * reused while the callback is executing. Do not hold the registry mutex
     * during a socket send or another queued operation. */
    if (c) {
        xSemaphoreTake(ws_mutex, portMAX_DELAY);
        bool close = c->close_requested;
        xSemaphoreGive(ws_mutex);
        bool authorized = session_authorized(&c->auth);
        bool idle = w->idle && (authorized ? now_ms() - c->last_frame_at >= 20000
                                           : now_ms() - c->opened_at >= 10000);
        if (close || idle) {
            /* Executing on the owning httpd task: EOF removes the session and
             * invokes ws_free on its next receive. No nested control work. */
            ws_close(c);
        } else if (!w->idle && session_authorized(&c->auth))
            ws_send(&c->auth, w->p, w->n);
    }
    release_server(w->h);
    free(w);
}
static void ws_schedule(const uint8_t *p, size_t n, bool idle) {
    if (!ws_mutex)
        return;
    ws_work *pending[4] = {0};
    xSemaphoreTake(ws_mutex, portMAX_DELAY);
    for (int i = 0; i < 4; i++)
        if (clients[i].used && !(clients[i].server == secure && secure_stopping)) {
            ws_work *w = calloc(1, sizeof(*w));
            if (w) {
                w->h = clients[i].server;
                w->fd = clients[i].fd;
                w->generation = clients[i].generation;
                w->idle = idle;
                w->n = n;
                if (n)
                    memcpy(w->p, p, n);
                if (w->h == secure)
                    secure_pending++;
                pending[i] = w;
            }
        }
    xSemaphoreGive(ws_mutex);
    /* queue_work may wait for control-mailbox space. Never wait with the
     * client registry locked, or from a transport receive callback. */
    for (int i = 0; i < 4; i++)
        if (pending[i] && httpd_queue_work(pending[i]->h, ws_do_work, pending[i]) != ESP_OK) {
            release_server(pending[i]->h);
            free(pending[i]);
        }
}
void http_events(const uint8_t *p, size_t n) {
    ws_schedule(p, n, false);
}
void http_drop(session *keep) {
    if (!ws_mutex)
        return;
    xSemaphoreTake(ws_mutex, portMAX_DELAY);
    for (unsigned i = 0; i < 4; i++)
        if (clients[i].used && &clients[i].auth != keep)
            clients[i].close_requested = true;
    xSemaphoreGive(ws_mutex);
}

static void http_watchdog(void *arg) {
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(1000));
        ws_schedule(NULL, 0, true);
    }
}

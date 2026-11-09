#include "esphid.h"
#include "esp_https_ota.h"
#include "esp_ota_ops.h"
#include "esp_app_desc.h"
#include "esp_crt_bundle.h"
#include "psa/crypto.h"
#include <ctype.h>
static portMUX_TYPE mutex = portMUX_INITIALIZER_UNLOCKED;
static bool active;
static struct {
    char state[16], error[96], version[33];
    int progress;
    unsigned bytes, total;
} status = {.state = "idle"};
static void update(const char *state, unsigned bytes, unsigned total, const char *error,
                   const char *version) {
    portENTER_CRITICAL(&mutex);
    if (state)
        strlcpy(status.state, state, sizeof(status.state));
    status.bytes = bytes;
    status.total = total;
    status.progress = total ? (uint64_t)bytes * 100 / total : 0;
    if (status.progress > 100)
        status.progress = 100;
    if (error)
        strlcpy(status.error, error, sizeof(status.error));
    if (version)
        strlcpy(status.version, version, sizeof(status.version));
    portEXIT_CRITICAL(&mutex);
}
cJSON *ota_status(void) {
    portENTER_CRITICAL(&mutex);
    typeof(status) s = status;
    portEXIT_CRITICAL(&mutex);
    cJSON *r = cJSON_CreateObject();
    JS(r, "state", s.state);
    JN(r, "progress", s.progress);
    JN(r, "bytes", s.bytes);
    JN(r, "total", s.total);
    JS(r, "error", s.error);
    JS(r, "version", s.version);
    return r;
}
static bool begin(void) {
    portENTER_CRITICAL(&mutex);
    bool allowed = !active;
    if (allowed) {
        active = true;
        memset(&status, 0, sizeof(status));
        strlcpy(status.state, "downloading", sizeof(status.state));
    }
    portEXIT_CRITICAL(&mutex);
    return allowed;
}
static void complete(esp_err_t e, unsigned n, unsigned total) {
    update(e == ESP_OK ? "done" : "error", n, e == ESP_OK ? n : total,
           e == ESP_OK ? "" : esp_err_to_name(e), NULL);
    portENTER_CRITICAL(&mutex);
    active = e == ESP_OK;
    portEXIT_CRITICAL(&mutex);
    if (e == ESP_OK)
        system_reboot(1000, false);
}
static esp_err_t hash_check(const esp_partition_t *p, size_t n, const char *expected) {
    if (!*expected)
        return ESP_OK;
    psa_hash_operation_t op = PSA_HASH_OPERATION_INIT;
    psa_crypto_init();
    if (psa_hash_setup(&op, PSA_ALG_SHA_256))
        return ESP_FAIL;
    uint8_t *b = malloc(4096);
    if (!b) {
        psa_hash_abort(&op);
        return ESP_ERR_NO_MEM;
    }
    esp_err_t e = ESP_OK;
    for (size_t off = 0; off < n;) {
        size_t k = n - off;
        if (k > 4096)
            k = 4096;
        e = esp_partition_read(p, off, b, k);
        if (e || psa_hash_update(&op, b, k)) {
            e = ESP_FAIL;
            break;
        }
        off += k;
    }
    uint8_t actual[32], wanted[32];
    size_t len = 0;
    if (e == ESP_OK && psa_hash_finish(&op, actual, sizeof(actual), &len))
        e = ESP_FAIL;
    psa_hash_abort(&op);
    free(b);
    for (int i = 0; i < 32; i++) {
        char hex[3] = {expected[2 * i], expected[2 * i + 1], 0};
        wanted[i] = strtoul(hex, NULL, 16);
    }
    if (e == ESP_OK && !esh_constant_equal(actual, 32, wanted, 32))
        e = ESP_ERR_INVALID_CRC;
    return e;
}
typedef struct {
    char url[1024], sha[65];
} download_args;
static void download(void *arg) {
    download_args *a = arg;
    esp_https_ota_handle_t handle = NULL;
    unsigned n = 0, total = 0;
    esp_http_client_config_t http = {.url = a->url,
                                     .crt_bundle_attach = esp_crt_bundle_attach,
                                     .timeout_ms = 15000,
                                     .keep_alive_enable = true,
                                     .max_redirection_count = 10,
                                     .buffer_size = 4096};
    esp_https_ota_config_t cfg = {.http_config = &http};
    esp_err_t e = esp_https_ota_begin(&cfg, &handle);
    const esp_partition_t *partition = esp_ota_get_next_update_partition(NULL);
    if (!e) {
        esp_app_desc_t desc;
        e = esp_https_ota_get_img_desc(handle, &desc);
        if (!e && strcmp(desc.project_name, esp_app_get_description()->project_name))
            e = ESP_ERR_INVALID_VERSION;
        if (!e)
            update(NULL, 0, 0, NULL, desc.version);
    }
    if (!e) {
        do {
            e = esp_https_ota_perform(handle);
            n = esp_https_ota_get_image_len_read(handle);
            int size = esp_https_ota_get_image_size(handle);
            total = size > 0 ? size : 0;
            update(NULL, n, total, NULL, NULL);
        } while (e == ESP_ERR_HTTPS_OTA_IN_PROGRESS);
    }
    if (!e && !esp_https_ota_is_complete_data_received(handle))
        e = ESP_ERR_INVALID_SIZE;
    if (!e) {
        update("verifying", n, total, NULL, NULL);
        e = hash_check(partition, n, a->sha);
    }
    if (!e) {
        e = esp_https_ota_finish(handle);
        handle = NULL;
    }
    if (handle)
        esp_https_ota_abort(handle);
    complete(e, n, total);
    free(a);
    vTaskDelete(NULL);
}
int ota_start(const char *url, const char *sha) {
    if ((strncmp(url, "https://", 8) && strncmp(url, "http://", 7)) || strlen(url) > 1023)
        return 400;
    if (*sha) {
        if (strlen(sha) != 64)
            return 400;
        for (int i = 0; i < 64; i++)
            if (!isxdigit((unsigned char)sha[i]))
                return 400;
    }
    if (!begin())
        return 503;
    download_args *a = calloc(1, sizeof(*a));
    if (!a) {
        complete(ESP_ERR_NO_MEM, 0, 0);
        return 500;
    }
    strlcpy(a->url, url, sizeof(a->url));
    strlcpy(a->sha, sha, sizeof(a->sha));
    if (xTaskCreate(download, "ota", 12288, a, 3, NULL) != pdPASS) {
        free(a);
        complete(ESP_ERR_NO_MEM, 0, 0);
        return 500;
    }
    return 200;
}
int ota_upload(httpd_req_t *req) {
    const esp_partition_t *p = esp_ota_get_next_update_partition(NULL);
    if (!p || !req->content_len || req->content_len > p->size)
        return 400;
    if (!begin())
        return 503;
    uint8_t *b = malloc(4096);
    esp_ota_handle_t handle = 0;
    esp_err_t e = b ? esp_ota_begin(p, req->content_len, &handle) : ESP_ERR_NO_MEM;
    unsigned n = 0, timeouts = 0;
    /* Async workers use the server's 2-second socket receive timeout. Allow
     * up to 20 seconds of consecutive stalls and ten minutes for the upload. */
    int64_t deadline = now_ms() + 10 * 60 * 1000;
    while (!e && n < req->content_len) {
        if (now_ms() >= deadline) {
            e = ESP_ERR_TIMEOUT;
            break;
        }
        int k = httpd_req_recv(req, (char *)b,
                               req->content_len - n < 4096 ? req->content_len - n : 4096);
        if (http_recv_retryable(k) && ++timeouts < 10)
            continue;
        if (k <= 0) {
            e = ESP_ERR_TIMEOUT;
            break;
        }
        timeouts = 0;
        e = esp_ota_write(handle, b, k);
        n += k;
        update(NULL, n, req->content_len, NULL, NULL);
    }
    if (!e) {
        esp_app_desc_t desc;
        update("verifying", n, req->content_len, NULL, NULL);
        e = esp_ota_get_partition_description(p, &desc);
        if (!e && strcmp(desc.project_name, esp_app_get_description()->project_name))
            e = ESP_ERR_INVALID_VERSION;
        if (!e)
            update(NULL, n, req->content_len, NULL, desc.version);
    }
    if (!e) {
        e = esp_ota_end(handle);
        handle = 0;
    }
    if (!e)
        e = esp_ota_set_boot_partition(p);
    if (handle)
        esp_ota_abort(handle);
    free(b);
    complete(e, n, req->content_len);
    return e ? 400 : 200;
}
void ota_mark_healthy(void) {
    esp_ota_img_states_t state;
    const esp_partition_t *running = esp_ota_get_running_partition();
    if (esp_ota_get_state_partition(running, &state) == ESP_OK &&
        state == ESP_OTA_IMG_PENDING_VERIFY)
        ESP_ERROR_CHECK(esp_ota_mark_app_valid_cancel_rollback());
}

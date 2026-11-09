#include "esphid.h"
#include "esp_wifi.h"
#include "esp_netif.h"
#include "esp_netif_sntp.h"
#include "esp_mac.h"
#include "mdns.h"
#include "esp_app_desc.h"

#include "lwip/sockets.h"
#include "lwip/inet.h"
#include <unistd.h>
static esp_netif_t *sta, *ap;
static bool started, connected, ap_active, mdns_started;
static int64_t fail_since, ap_stop;
static char current_ssid[33], current_password[65], boot_radio[8];
static TaskHandle_t network_task;
static void ap_enable(bool enable) {
    if (ap_active == enable)
        return;
    esp_wifi_set_mode(enable ? WIFI_MODE_APSTA : WIFI_MODE_STA);
    ap_active = enable;
    if (enable) {
        wifi_config_t w = {0};
        snprintf((char *)w.ap.ssid, 33, "ESPHID-%s", device_id);
        w.ap.ssid_len = strlen((char *)w.ap.ssid);
        w.ap.channel = 1;
        w.ap.max_connection = 4;
        w.ap.authmode = WIFI_AUTH_OPEN;
        esp_wifi_set_config(WIFI_IF_AP, &w);
    }
}
static void network_event(void *arg, esp_event_base_t base, int32_t id, void *data) {
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
    }
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        connected = false;
        ap_stop = 0;
        if (!fail_since)
            fail_since = now_ms();
        esp_wifi_connect();
    }
    if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        connected = true;
        esp_netif_sntp_start();
        fail_since = 0;
        if (ap_active)
            ap_stop = now_ms() + 60000;
        services_changed();
    }
}
static void dns(void *arg) {
    int fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    struct sockaddr_in bind_addr = {
        .sin_family = AF_INET, .sin_port = htons(53), .sin_addr.s_addr = INADDR_ANY};
    if (fd >= 0) {
        struct timeval tv = {.tv_sec = 1};
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        if (bind(fd, (void *)&bind_addr, sizeof(bind_addr)) < 0) {
            close(fd);
            fd = -1;
        }
    }
    uint8_t b[512];
    for (;;) {
        if (fd >= 0) {
            struct sockaddr_in peer;
            socklen_t len = sizeof(peer);
            int n = recvfrom(fd, b, sizeof(b), 0, (void *)&peer, &len);
            if (n >= 12 && ap_active) {
                /* Reply only to DNS questions sent by setup clients, preserving the question. */
                uint32_t ip = ntohl(peer.sin_addr.s_addr);
                if ((ip & 0xffffff00U) == 0xc0a80400U) {
                    size_t k = 12;
                    while (k < (size_t)n && b[k]) {
                        if (b[k] > 63) {
                            k = n;
                            break;
                        }
                        k += b[k] + 1;
                    }
                    k += 5;
                    if (k <= (size_t)n && k + 16 <= sizeof(b)) {
                        b[2] = 0x81;
                        b[3] = 0x80;
                        b[6] = 0;
                        b[7] = 1;
                        b[8] = b[9] = b[10] = b[11] = 0;
                        const uint8_t answer[] = {0xc0, 0x0c, 0, 1, 0,   1,   0, 0,
                                                  0,    0,    0, 4, 192, 168, 4, 1};
                        memcpy(b + k, answer, 16);
                        sendto(fd, b, k + 16, 0, (void *)&peer, len);
                    }
                }
            }
        } else
            vTaskDelay(pdMS_TO_TICKS(1000));
        cJSON *c = config_snapshot(false);
        bool fallback = cJSON_IsTrue(obj(obj(c, "wifi"), "ap_fallback"));
        cJSON_Delete(c);
        if (!connected && !*current_ssid)
            ap_enable(true);
        else if (!connected && fallback && fail_since && now_ms() - fail_since >= 60000)
            ap_enable(true);
        if (connected && ap_stop && now_ms() >= ap_stop) {
            ap_enable(false);
            ap_stop = 0;
        }
    }
}
void net_apply(void) {
    if (!started)
        return;
    cJSON *c = config_snapshot(true), *w = obj(c, "wifi");
    const char *ssid = str(obj(w, "ssid")), *password = str(obj(w, "password"));
    esp_netif_set_hostname(sta, str(obj(w, "hostname")));
    if (strcmp(current_ssid, ssid) || strcmp(current_password, password)) {
        ap_enable(true);
        esp_wifi_disconnect();
        wifi_config_t wc = {0};
        memcpy(wc.sta.ssid, ssid, strlen(ssid));
        memcpy(wc.sta.password, password, strlen(password));
        wc.sta.scan_method = WIFI_ALL_CHANNEL_SCAN;
        wc.sta.sort_method = WIFI_CONNECT_AP_BY_SIGNAL;
        esp_wifi_set_config(WIFI_IF_STA, &wc);
        strlcpy(current_ssid, ssid, sizeof(current_ssid));
        strlcpy(current_password, password, sizeof(current_password));
        fail_since = now_ms();
        esp_wifi_connect();
    }
    cJSON_Delete(c);
}
void net_init(void) {
    ESP_ERROR_CHECK(esp_netif_init());
    esp_sntp_config_t time_config = ESP_NETIF_SNTP_DEFAULT_CONFIG("pool.ntp.org");
    time_config.start = false;
    ESP_ERROR_CHECK(esp_netif_sntp_init(&time_config));
    sta = esp_netif_create_default_wifi_sta();
    ap = esp_netif_create_default_wifi_ap();
    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&init));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, network_event, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, network_event, NULL));
    cJSON *c = config_snapshot(false);
    strlcpy(boot_radio, str(obj(c, "radio_mode")), sizeof(boot_radio));
    cJSON_Delete(c);
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_ERROR_CHECK(
        esp_wifi_set_ps(!strcmp(boot_radio, "wifi") ? WIFI_PS_NONE : WIFI_PS_MIN_MODEM));
    started = true;
    fail_since = now_ms();
    net_apply();
    if (!*current_ssid)
        ap_enable(true);
    ESP_ERROR_CHECK(mdns_init());
    mdns_started = true;
    mdns_refresh();
    assert(xTaskCreate(dns, "captive_dns", 4096, NULL, 3, &network_task) == pdPASS);
}
bool net_enabled(void) {
    return started;
}
bool net_ap_active(void) {
    return ap_active;
}
void mdns_refresh(void) {
    if (!mdns_started)
        return;
    cJSON *c = config_snapshot(false);
    mdns_hostname_set(str(obj(obj(c, "wifi"), "hostname")));
    mdns_instance_name_set(str(obj(c, "name")));
    char https[8];
    snprintf(https, sizeof(https), "%d",
             cJSON_IsTrue(obj(obj(c, "https"), "enabled")) ? obj(obj(c, "https"), "port")->valueint
                                                           : 0);
    mdns_txt_item_t txt[] = {{"id", device_id},
                             {"name", str(obj(c, "name"))},
                             {"fw", esp_app_get_description()->version},
                             {"proto", "1"},
                             {"claimed", claimed() ? "1" : "0"},
                             {"https", https},
                             {"ws", "/ws"}};
    mdns_service_remove("_esphid", "_tcp");
    mdns_service_add(NULL, "_esphid", "_tcp", 80, txt, 7);
    mdns_service_remove("_http", "_tcp");
    mdns_service_add(NULL, "_http", "_tcp", 80, NULL, 0);
    cJSON_Delete(c);
}
cJSON *net_info(void) {
    cJSON *r = cJSON_CreateObject();
    JS(r, "mode",
       !started    ? "off"
       : ap_active ? (connected       ? "apsta"
                      : *current_ssid ? "apsta"
                                      : "ap")
                   : "sta");
    JB(r, "connected", connected);
    JS(r, "ssid", current_ssid);
    esp_netif_ip_info_t ip = {0};
    if (sta)
        esp_netif_get_ip_info(sta, &ip);
    char buf[32];
    snprintf(buf, sizeof(buf), IPSTR, IP2STR(&ip.ip));
    JS(r, "ip", buf);
    wifi_ap_record_t record = {0};
    if (started)
        esp_wifi_sta_get_ap_info(&record);
    JN(r, "rssi", record.rssi);
    uint8_t mac[6];
    esp_read_mac(mac, ESP_MAC_WIFI_STA);
    snprintf(buf, sizeof(buf), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3],
             mac[4], mac[5]);
    JS(r, "mac", buf);
    JB(r, "ap_active", ap_active);
    JS(r, "ap_ip", "192.168.4.1");
    return r;
}
static int rssi_cmp(const void *a, const void *b) {
    return ((const wifi_ap_record_t *)b)->rssi - ((const wifi_ap_record_t *)a)->rssi;
}
static cJSON *scan(int *code) {
    if (!started) {
        *code = 503;
        return NULL;
    }
    if (esp_wifi_scan_start(NULL, true) != ESP_OK) {
        *code = 503;
        return NULL;
    }
    uint16_t n = 0;
    esp_wifi_scan_get_ap_num(&n);
    if (n > 64)
        n = 64;
    wifi_ap_record_t *records = calloc(n ? n : 1, sizeof(*records));
    if (!records) {
        *code = 500;
        return NULL;
    }
    esp_wifi_scan_get_ap_records(&n, records);
    qsort(records, n, sizeof(*records), rssi_cmp);
    cJSON *r = cJSON_CreateObject(), *a = cJSON_AddArrayToObject(r, "networks");
    for (int i = 0; i < n; i++) {
        cJSON *v = cJSON_CreateObject();
        JS(v, "ssid", (char *)records[i].ssid);
        JN(v, "rssi", records[i].rssi);
        JN(v, "channel", records[i].primary);
        const char *auth = "wpa2";
        switch (records[i].authmode) {
        case WIFI_AUTH_OPEN:
            auth = "open";
            break;
        case WIFI_AUTH_WEP:
            auth = "wep";
            break;
        case WIFI_AUTH_WPA_PSK:
            auth = "wpa";
            break;
        case WIFI_AUTH_WPA3_PSK:
            auth = "wpa3";
            break;
        case WIFI_AUTH_WPA2_ENTERPRISE:
            auth = "wpa2-enterprise";
            break;
        case WIFI_AUTH_WPA2_WPA3_PSK:
            auth = "wpa2/wpa3";
            break;
        default:
            break;
        }
        JS(v, "auth", auth);
        cJSON_AddItemToArray(a, v);
    }
    free(records);
    *code = 200;
    return r;
}

/* One scan owns the driver result buffer. Concurrent workers fail promptly. */
cJSON *net_scan(int *code) {
    static atomic_bool scanning;
    if (atomic_exchange(&scanning, true)) {
        *code = 503;
        return NULL;
    }
    cJSON *result = scan(code);
    atomic_store(&scanning, false);
    return result;
}

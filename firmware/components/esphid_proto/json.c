#include "esphid_proto.h"
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <stdlib.h>

typedef struct {
    const char *name;
    uint16_t value;
} name_value;
static const name_value keys[] = {
    {"enter", 40},     {"esc", 41},      {"backspace", 42},   {"tab", 43},        {"space", 44},
    {"minus", 45},     {"equal", 46},    {"lbracket", 47},    {"rbracket", 48},   {"backslash", 49},
    {"semicolon", 51}, {"quote", 52},    {"grave", 53},       {"comma", 54},      {"period", 55},
    {"slash", 56},     {"capslock", 57}, {"printscreen", 70}, {"scrolllock", 71}, {"pause", 72},
    {"insert", 73},    {"home", 74},     {"pageup", 75},      {"delete", 76},     {"end", 77},
    {"pagedown", 78},  {"right", 79},    {"left", 80},        {"down", 81},       {"up", 82},
    {"numlock", 83},   {"menu", 101},    {"lctrl", 224},      {"lshift", 225},    {"lalt", 226},
    {"lgui", 227},     {"rctrl", 228},   {"rshift", 229},     {"ralt", 230},      {"rgui", 231},
    {"ctrl", 224},     {"shift", 225},   {"alt", 226},        {"gui", 227},       {"cmd", 227},
    {"win", 227},      {"meta", 227}};
static const name_value consumers[] = {
    {"play_pause", 0xcd}, {"next", 0xb5},          {"prev", 0xb6},
    {"stop", 0xb7},       {"mute", 0xe2},          {"vol_up", 0xe9},
    {"vol_down", 0xea},   {"brightness_up", 0x6f}, {"brightness_down", 0x70}};
static int num(const cJSON *j, int lo, int hi) {
    return cJSON_IsNumber(j) && isfinite(j->valuedouble) &&
                   floor(j->valuedouble) == j->valuedouble && j->valuedouble >= lo &&
                   j->valuedouble <= hi
               ? (int)j->valuedouble
               : -2147483647;
}
static int lookup(const cJSON *j, const name_value *t, size_t n, int hi) {
    if (cJSON_IsNumber(j))
        return num(j, 0, hi);
    if (!cJSON_IsString(j))
        return -1;
    for (size_t i = 0; i < n; i++)
        if (!strcmp(j->valuestring, t[i].name))
            return t[i].value;
    return -1;
}
int esh_key(const cJSON *j) {
    int r = lookup(j, keys, sizeof(keys) / sizeof(*keys), 255);
    if (r >= 0)
        return r;
    if (!cJSON_IsString(j))
        return -1;
    const char *s = j->valuestring;
    if (s[0] && !s[1]) {
        if (*s >= 'a' && *s <= 'z')
            return *s - 'a' + 4;
        if (*s >= '1' && *s <= '9')
            return *s - '1' + 30;
        if (*s == '0')
            return 39;
    }
    if (s[0] == 'f') {
        char *end;
        long f = strtol(s + 1, &end, 10);
        if (!*end && f >= 1 && f <= 24)
            return f <= 12 ? 57 + f : 91 + f;
    }
    return -1;
}
int esh_consumer(const cJSON *j) {
    return lookup(j, consumers, sizeof(consumers) / sizeof(*consumers), 65535);
}
int esh_mods(const cJSON *j) {
    if (!j)
        return 0;
    if (!cJSON_IsArray(j))
        return -1;
    int m = 0;
    const cJSON *v;
    cJSON_ArrayForEach(v, j) {
        int k = esh_key(v);
        if (!cJSON_IsString(v) || k < 224 || k > 231)
            return -1;
        m |= 1 << (k - 224);
    }
    return m;
}
#define GET(k) cJSON_GetObjectItemCaseSensitive(v, k)
#define N(field, key, lo, hi)                                                                      \
    do {                                                                                           \
        int z = num(GET(key), lo, hi);                                                             \
        if (z == -2147483647)                                                                      \
            goto bad;                                                                              \
        c.field = z;                                                                               \
    } while (0)
bool esh_json(const cJSON *root, esh_command_fn fn, void *ctx, char *error, size_t cap) {
    const cJSON *a = cJSON_GetObjectItemCaseSensitive(root, "commands"), *v;
    if (!cJSON_IsArray(a))
        goto bad;
    cJSON_ArrayForEach(v, a) {
        esh_command c = {0};
        const cJSON *t = GET("t");
        if (!cJSON_IsString(t))
            goto bad;
        const char *s = t->valuestring;
        if (!strcmp(s, "move")) {
            c.op = ESH_MOVE;
            N(dx, "dx", -32768, 32767);
            N(dy, "dy", -32768, 32767);
        } else if (!strcmp(s, "buttons") || !strcmp(s, "click")) {
            c.op = !strcmp(s, "click") ? ESH_CLICK : ESH_BUTTONS;
            N(buttons, "b", 0, 31);
        } else if (!strcmp(s, "scroll")) {
            c.op = ESH_SCROLL;
            N(wheel, "v", -128, 127);
            N(pan, "h", -128, 127);
        } else if (!strcmp(s, "abs")) {
            c.op = ESH_ABS;
            N(x, "x", 0, 32767);
            N(y, "y", 0, 32767);
            N(buttons, "b", 0, 31);
        } else if (!strcmp(s, "down") || !strcmp(s, "up") || !strcmp(s, "tap")) {
            c.op = !strcmp(s, "down") ? ESH_DOWN : !strcmp(s, "up") ? ESH_UP : ESH_TAP;
            int k = esh_key(GET("k"));
            int m = esh_mods(GET("mods"));
            if (k < 0 || m < 0)
                goto bad;
            c.key = k;
            c.mods = m;
        } else if (!strcmp(s, "mods")) {
            c.op = ESH_MODS;
            int m = esh_mods(GET("mods"));
            if (m < 0)
                goto bad;
            c.mods = m;
        } else if (!strcmp(s, "text")) {
            const cJSON *j = GET("s");
            if (!cJSON_IsString(j))
                goto bad;
            const uint8_t *p = (uint8_t *)j->valuestring;
            size_t n = strlen((char *)p);
            c.op = ESH_TEXT;
            while (n) {
                size_t k = n < 255 ? n : 255;
                while (k < n && (p[k] & 0xc0) == 0x80)
                    k--;
                if (!k)
                    goto bad;
                c.len = k;
                memcpy(c.data, p, k);
                if (!fn(ctx, &c))
                    return false;
                p += k;
                n -= k;
            }
            continue;
        } else if (!strcmp(s, "release"))
            c.op = ESH_RELEASE;
        else if (!strcmp(s, "consumer")) {
            c.op = ESH_CONSUMER_TAP;
            int u = esh_consumer(GET("u"));
            if (u < 0)
                goto bad;
            c.usage = u;
        } else if (!strcmp(s, "delay")) {
            c.op = ESH_DELAY;
            int z = num(GET("ms"), 0, 65535);
            if (z < 0)
                goto bad;
            c.ms = z > 5000 ? 5000 : z;
        } else
            goto bad;
        if (!fn(ctx, &c))
            return false;
    }
    return true;
bad:
    if (cap)
        snprintf(error, cap, "invalid HID command");
    return false;
}

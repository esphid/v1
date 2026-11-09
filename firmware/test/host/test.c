#include "esphid_proto.h"
#include "esphid_safety.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static esh_command got[64];
static unsigned count, errors;
static uint8_t ec, eo;
static bool collect(void *x, esh_command *c) {
    (void)x;
    assert(count < 64);
    got[count++] = *c;
    return true;
}
static void error(void *x, uint8_t c, uint8_t o) {
    (void)x;
    errors++;
    ec = c;
    eo = o;
}
static void parse(const uint8_t *p, size_t n) {
    count = errors = 0;
    assert(esh_parse(p, n, collect, error, NULL));
    assert(!errors);
}
#define VECTOR(...)                                                                                \
    do {                                                                                           \
        const uint8_t p[] = {__VA_ARGS__};                                                         \
        parse(p, sizeof(p));                                                                       \
        assert(count == 1);                                                                        \
    } while (0)
static void vectors(void) {
    VECTOR(1, 10, 0, 251, 255);
    assert(got[0].dx == 10 && got[0].dy == -5);
    VECTOR(1, 212, 254, 44, 1);
    assert(got[0].dx == -300 && got[0].dy == 300);
    VECTOR(2, 3);
    assert(got[0].buttons == 3);
    VECTOR(3, 255, 2);
    assert(got[0].wheel == -1 && got[0].pan == 2);
    VECTOR(4, 1);
    assert(got[0].buttons == 1);
    VECTOR(5, 0, 64, 255, 127, 0);
    assert(got[0].x == 16384 && got[0].y == 32767 && !got[0].buttons);
    VECTOR(6, 1, 1, 0, 2, 0, 255, 0);
    assert(got[0].buttons == 1 && got[0].dx == 1 && got[0].dy == 2 && got[0].wheel == -1 &&
           !got[0].pan);
    VECTOR(0x10, 4);
    assert(got[0].key == 4);
    VECTOR(0x11, 4);
    assert(got[0].key == 4);
    VECTOR(0x12, 1, 6);
    assert(got[0].mods == 1 && got[0].key == 6);
    VECTOR(0x13, 2, 4, 5, 0, 0, 0, 0);
    assert(got[0].mods == 2 && got[0].keys[0] == 4 && got[0].keys[1] == 5);
    VECTOR(0x14, 8);
    assert(got[0].mods == 8);
    VECTOR(0x15, 2, 0x48, 0x69);
    assert(got[0].len == 2 && !memcmp(got[0].data, "Hi", 2));
    VECTOR(0x16);
    VECTOR(0x1a, 0xcd, 0);
    assert(got[0].usage == 0xcd);
    VECTOR(0x20, 100, 0);
    assert(got[0].ms == 100);
    VECTOR(0x30, 4, 3, 2, 1);
    assert(got[0].nonce == 0x01020304);
    VECTOR(0x40, 3, 'a', 'b', 'c');
    assert(got[0].len == 3 && !memcmp(got[0].data, "abc", 3));
    const uint8_t multi[] = {0x40, 3, 'a', 'b', 'c', 0x15, 2, 'H', 'i'};
    parse(multi, sizeof(multi));
    assert(count == 2 && got[0].op == ESH_AUTH && got[1].op == ESH_TEXT);
    uint8_t out[16];
    const uint8_t pong[] = {0x80, 4, 3, 2, 1, 0xe8, 3, 0, 0}, led[] = {0x81, 2},
                  err[] = {0x83, 3, 1};
    assert(esh_event_pong(out, 0x01020304, 1000) == sizeof(pong) &&
           !memcmp(out, pong, sizeof(pong)));
    assert(esh_event_leds(out, 2) == 2 && !memcmp(out, led, 2));
    assert(esh_event_error(out, 3, 1) == 3 && !memcmp(out, err, 3));
    assert(esh_event_auth(out, 3) == 2 && out[0] == 0x84 && out[1] == 3);
    assert(esh_event_state(out, 7, 2, 0x1234) == 5 &&
           !memcmp(out, (uint8_t[]){0x82, 7, 2, 0x34, 0x12}, 5));
    puts("PASS all 22 protocol vectors (command parse / event encode)");
}
static void malformed(void) {
    const uint8_t ops[] = {1,    2,    3,    4,    5,    6,    0x10, 0x11, 0x12,
                           0x13, 0x14, 0x15, 0x18, 0x1a, 0x20, 0x30, 0x40};
    for (size_t i = 0; i < sizeof(ops); i++) {
        uint8_t b[12] = {0};
        b[0] = ops[i];
        b[1] = 3;
        size_t length =
            ops[i] == 1 || ops[i] == 0x30      ? 5
            : ops[i] == 5                      ? 6
            : ops[i] == 6 || ops[i] == 0x13    ? 8
            : ops[i] == 0x15 || ops[i] == 0x40 ? 5
            : ops[i] == 3 || ops[i] == 0x12 || ops[i] == 0x18 || ops[i] == 0x1a || ops[i] == 0x20
                ? 3
                : 2;
        for (size_t n = 1; n < length; n++) {
            count = errors = 0;
            assert(!esh_parse(b, n, collect, error, NULL));
            assert(ec == ESH_TRUNCATED && eo == ops[i]);
        }
    }
    uint8_t b[] = {0x10, 4, 0xff, 0x10, 5};
    count = errors = 0;
    assert(!esh_parse(b, sizeof(b), collect, error, NULL));
    assert(count == 1 && ec == ESH_UNKNOWN && eo == 255);
    VECTOR(0x20, 255, 255);
    assert(got[0].ms == 5000);
    VECTOR(0x18, 0xe9, 0);
    assert(got[0].usage == 0xe9);
    VECTOR(0x19);
    VECTOR(0x31);
    VECTOR(0x40, 0);
    assert(!got[0].len);
    uint8_t huge[513] = {0};
    assert(!esh_parse(huge, sizeof(huge), collect, error, NULL));
    assert(esh_constant_equal("abc", 3, "abc", 3));
    assert(!esh_constant_equal("abc", 3, "abd", 3));
    assert(!esh_constant_equal("abc", 3, "abcx", 4));
    puts("PASS truncation, unknown opcode, ordered prefix, AUTH and bounds");
}
static int key(const char *s) {
    cJSON *j = cJSON_CreateString(s);
    int k = esh_key(j);
    cJSON_Delete(j);
    return k;
}
static void json(void) {
    assert(key("a") == 4 && key("z") == 29 && key("0") == 39 && key("9") == 38 && key("f1") == 58 &&
           key("f12") == 69 && key("f13") == 104 && key("f24") == 115);
    const char *names[] = {
        "enter",    "esc",         "backspace",  "tab",   "space",  "minus",  "equal",   "lbracket",
        "rbracket", "backslash",   "semicolon",  "quote", "grave",  "comma",  "period",  "slash",
        "capslock", "printscreen", "scrolllock", "pause", "insert", "home",   "pageup",  "delete",
        "end",      "pagedown",    "right",      "left",  "down",   "up",     "numlock", "menu",
        "lctrl",    "lshift",      "lalt",       "lgui",  "rctrl",  "rshift", "ralt",    "rgui",
        "ctrl",     "shift",       "alt",        "gui",   "cmd",    "win",    "meta"};
    for (size_t i = 0; i < sizeof(names) / sizeof(*names); i++)
        assert(key(names[i]) >= 0);
    cJSON *j = cJSON_Parse("{\"commands\":[{\"t\":\"tap\",\"k\":\"c\",\"mods\":[\"ctrl\","
                           "\"rshift\"]},{\"t\":\"consumer\",\"u\":\"play_pause\"},{\"t\":\"text\","
                           "\"s\":\"aa\"},{\"t\":\"down\",\"k\":42}]}");
    char e[80];
    count = 0;
    assert(esh_json(j, collect, NULL, e, sizeof(e)));
    assert(count == 4 && got[0].mods == 33 && got[0].key == 6 && got[1].usage == 0xcd &&
           got[3].key == 42);
    cJSON_Delete(j);
    const char *cons[] = {"play_pause", "next",     "prev",          "stop",           "mute",
                          "vol_up",     "vol_down", "brightness_up", "brightness_down"};
    for (size_t i = 0; i < 9; i++) {
        j = cJSON_CreateString(cons[i]);
        assert(esh_consumer(j) > 0);
        cJSON_Delete(j);
    }
    j = cJSON_Parse("{\"commands\":[{\"t\":\"down\",\"k\":\"bogus\"}]}");
    assert(!esh_json(j, collect, NULL, e, sizeof(e)));
    cJSON_Delete(j);
    j = cJSON_Parse("[\"ctrl\",\"shift\",\"alt\",\"gui\",\"rctrl\",\"rshift\",\"ralt\",\"rgui\"]");
    assert(esh_mods(j) == 255);
    cJSON_Delete(j);
    j = cJSON_Parse(
        "{\"commands\":[{\"t\":\"move\",\"dx\":10,\"dy\":-5},{\"t\":\"buttons\",\"b\":3},{\"t\":"
        "\"click\",\"b\":1},{\"t\":\"scroll\",\"v\":-1,\"h\":2},{\"t\":\"abs\",\"x\":16384,\"y\":"
        "32767,\"b\":0},{\"t\":\"up\",\"k\":\"a\"},{\"t\":\"mods\",\"mods\":[\"gui\"]},{\"t\":"
        "\"release\"},{\"t\":\"delay\",\"ms\":6000}]}");
    count = 0;
    assert(esh_json(j, collect, NULL, e, sizeof(e)));
    assert(count == 9 && got[0].dx == 10 && got[0].dy == -5 && got[1].buttons == 3 &&
           got[2].op == ESH_CLICK && got[3].wheel == -1 && got[3].pan == 2 && got[4].y == 32767 &&
           got[5].op == ESH_UP && got[6].mods == 8 && got[7].op == ESH_RELEASE &&
           got[8].ms == 5000);
    cJSON_Delete(j);
    char long_text[4097];
    memset(long_text, 'a', 4096);
    long_text[254] = (char)0xc3;
    long_text[255] = (char)0xa9;
    long_text[4096] = 0;
    j = cJSON_CreateObject();
    cJSON *commands = cJSON_AddArrayToObject(j, "commands"), *text = cJSON_CreateObject();
    cJSON_AddItemToArray(commands, text);
    cJSON_AddStringToObject(text, "t", "text");
    cJSON_AddStringToObject(text, "s", long_text);
    count = 0;
    assert(esh_json(j, collect, NULL, e, sizeof(e)));
    size_t total = 0;
    for (unsigned i = 0; i < count; i++) {
        assert(got[i].op == ESH_TEXT && (got[i].data[0] & 0xc0) != 0x80);
        assert(!memcmp(got[i].data, long_text + total, got[i].len));
        total += got[i].len;
    }
    assert(total == 4096 && got[0].len == 254);
    cJSON_Delete(j);
    puts("PASS JSON commands, key/modifier/consumer names and raw usages");
}
static esh_stroke strokes[32];
static size_t sn;
static bool stroke(void *x, esh_stroke s) {
    (void)x;
    strokes[sn++] = s;
    return true;
}
static void layout(void) {
    const esh_layout *l = esh_layout_find("us");
    assert(l && !esh_layout_find("xx"));
    const uint8_t ascii_us[] = {44, 30, 52, 32, 33, 34, 36, 52, 38, 39, 37, 46, 54, 45, 55, 56,
                                39, 30, 31, 32, 33, 34, 35, 36, 37, 38, 51, 51, 54, 46, 55, 56,
                                31, 4,  5,  6,  7,  8,  9,  10, 11, 12, 13, 14, 15, 16, 17, 18,
                                19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 47, 49, 48, 35, 45,
                                53, 4,  5,  6,  7,  8,  9,  10, 11, 12, 13, 14, 15, 16, 17, 18,
                                19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 47, 49, 48, 53};
    const char *shifted = "!\"#$%&()*+:<>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ^_{|}~";
    assert(sizeof(ascii_us) == 95);
    for (unsigned c = 32; c < 127; c++) {
        esh_stroke s;
        uint8_t b = c;
        assert(esh_utf8_stroke(l, &b, 1, &s) == 1);
        assert(s.usage == ascii_us[c - 32] && s.mods == (strchr(shifted, c) ? 2 : 0));
    }
    const uint8_t controls[] = {'\n', '\t', '\b'};
    for (int i = 0; i < 3; i++) {
        esh_stroke s;
        esh_utf8_stroke(l, controls + i, 1, &s);
        assert((s.usage == (uint8_t[]){40, 43, 42}[i]));
    }
    assert(esh_text_expand(l, (uint8_t *)"aaAA", 4, stroke, NULL));
    assert(sn == 8);
    for (size_t i = 0; i < 8; i += 2) {
        assert(strokes[i].usage == 4 && !strokes[i + 1].usage && !strokes[i + 1].mods);
    }
    esh_stroke s;
    assert(esh_utf8_stroke(l, (uint8_t *)"\xc3\xa9", 2, &s) == 2 && !s.usage);
    esh_motion m = {0};
    esh_motion_add(&m, 32767, -32768, 127, -128);
    esh_motion_add(&m, 10, -10, 2, -2);
    int16_t x, y;
    int8_t w, p;
    assert(esh_motion_take(&m, &x, &y, &w, &p) && x == 32767 && y == -32768 && w == 127 &&
           p == -128);
    assert(esh_motion_take(&m, &x, &y, &w, &p) && x == 10 && y == -10 && w == 2 && p == -2);
    assert(!esh_motion_take(&m, &x, &y, &w, &p));
    puts(
        "PASS all printable US ASCII, controls, repeated-key releases, UTF-8 and motion remainder");
}
static void json_depth(void) {
    char input[36];
    for (unsigned i = 0; i < 17; i++)
        input[i] = '[';
    input[17] = '0';
    for (unsigned i = 0; i < 17; i++)
        input[18 + i] = ']';
    input[35] = 0;
    cJSON *j = cJSON_Parse(input);
    assert(j == NULL);
    puts("PASS bounded JSON nesting for embedded task stacks");
}
static void safety(void) {
    const char *names[] = {"ASCII", "1234567890123456789012345678é", "123456789012345678901234567€",
                           "12345678901234567890123456😀", "é€😀"};
    for (unsigned i = 0; i < sizeof(names) / sizeof(*names); i++) {
        size_t len = strlen(names[i]);
        for (size_t max = 0; max <= len + 2; max++) {
            size_t n = esh_utf8_prefix(names[i], len, max);
            assert(n <= max && n <= len);
            assert(n == len || ((unsigned char)names[i][n] & 0xc0) != 0x80);
            /* Every included scalar is complete and the next scalar cannot fit. */
            size_t next = n;
            if (next < len) {
                next++;
                while (next < len && ((unsigned char)names[i][next] & 0xc0) == 0x80)
                    next++;
                assert(next > max);
            }
        }
    }
    assert(esh_utf8_prefix(names[1], strlen(names[1]), 29) == 28);
    assert(esh_utf8_prefix(names[2], strlen(names[2]), 29) == 27);
    assert(esh_utf8_prefix(names[3], strlen(names[3]), 29) == 26);
    esh_holds h = {0};
    esh_command c = {.op = ESH_DOWN, .key = 4, .source = ESH_SOURCE_HTTP};
    esh_holds_apply(&h, &c);
    assert(!esh_holds_expired(&h, ESH_SOURCE_HTTP, 10999, 1000, 10000));
    assert(esh_holds_expired(&h, ESH_SOURCE_HTTP, 11000, 1000, 10000));
    assert(!esh_holds_expired(&h, ESH_SOURCE_HTTP, 11000, 1001, 10000));
    assert(!esh_holds_expired(&h, ESH_SOURCE_MQTT, 11000, 0, 10000));
    c.source = ESH_SOURCE_BLE; /* A live writer takes over this same key. */
    esh_holds_apply(&h, &c);
    assert(!esh_holds_expired(&h, ESH_SOURCE_HTTP, 11000, 0, 10000));
    assert(!esh_holds_expired(&h, ESH_SOURCE_BLE, 100000, 0, 10000));
    c = (esh_command){.op = ESH_BUTTONS, .buttons = 1, .source = ESH_SOURCE_MQTT};
    esh_holds_apply(&h, &c);
    assert(esh_holds_expired(&h, ESH_SOURCE_MQTT, 11000, 0, 10000));
    c.op = ESH_CLICK;
    c.source = ESH_SOURCE_WS;
    esh_holds_apply(&h, &c); /* A click preserves the previous persistent button. */
    assert(esh_holds_expired(&h, ESH_SOURCE_MQTT, 11000, 0, 10000));
    c.op = ESH_REPORT;
    c.buttons = 0;
    esh_holds_apply(&h, &c);
    assert(!esh_holds_expired(&h, ESH_SOURCE_MQTT, 11000, 0, 10000));
    c = (esh_command){.op = ESH_MODS, .mods = 8, .source = ESH_SOURCE_HTTP};
    esh_holds_apply(&h, &c);
    assert(esh_holds_expired(&h, ESH_SOURCE_HTTP, 11000, 0, 10000));
    c.op = ESH_UP;
    c.key = 227;
    esh_holds_apply(&h, &c);
    assert(!esh_holds_expired(&h, ESH_SOURCE_HTTP, 11000, 0, 10000));
    c = (esh_command){.op = ESH_KEY_REPORT, .keys = {4, 5}, .source = ESH_SOURCE_HTTP};
    esh_holds_apply(&h, &c);
    c.op = ESH_UP;
    c.key = 4;
    esh_holds_apply(&h, &c);
    assert(esh_holds_expired(&h, ESH_SOURCE_HTTP, 11000, 0, 10000));
    c.op = ESH_TEXT; /* Empty/unmapped TEXT emits no stroke and preserves held input. */
    esh_holds_apply(&h, &c);
    assert(esh_holds_expired(&h, ESH_SOURCE_HTTP, 11000, 0, 10000));
    c.op = ESH_TAP;
    esh_holds_apply(&h, &c);
    assert(!esh_holds_expired(&h, ESH_SOURCE_HTTP, 11000, 0, 10000));
    c = (esh_command){.op = ESH_CONSUMER_DOWN, .usage = 0xcd, .source = ESH_SOURCE_MQTT};
    esh_holds_apply(&h, &c);
    assert(esh_holds_expired(&h, ESH_SOURCE_MQTT, 9999, UINT32_MAX - 10, 10000));
    c.op = ESH_CONSUMER_UP;
    esh_holds_apply(&h, &c);
    assert(!esh_holds_expired(&h, ESH_SOURCE_MQTT, 11000, 0, 10000));
    c = (esh_command){.op = ESH_ABS, .buttons = 31, .source = ESH_SOURCE_HTTP};
    esh_holds_apply(&h, &c);
    c.op = ESH_RELEASE;
    esh_holds_apply(&h, &c);
    assert(!esh_holds_expired(&h, ESH_SOURCE_HTTP, 11000, 0, 10000));
    puts("PASS UTF-8 name boundaries, hold ownership, mixed transports, timeout and clock wrap");
}
static bool cancel_stroke(void *ctx, esh_stroke s) {
    (void)s;
    unsigned *remaining = ctx;
    return --*remaining != 0;
}
static void source_release(void) {
    esh_holds h = {0};
    esh_hold_report r;
    for (unsigned source = 0; source < ESH_SOURCE_COUNT; source++) {
        esh_command c = {.op = ESH_DOWN, .key = 4 + source, .source = source};
        esh_holds_apply(&h, &c);
        c.key = 224 + source;
        esh_holds_apply(&h, &c);
    }
    /* Mixed persistent/temporary buttons, as during an additive CLICK. */
    h.buttons[0] = ESH_SOURCE_WS + 1;
    h.buttons[1] = ESH_SOURCE_HTTP + 1;
    h.buttons[2] = ESH_SOURCE_MQTT + 1;
    esh_command c = {.op = ESH_CONSUMER_DOWN, .usage = 0xcd, .source = ESH_SOURCE_MQTT};
    esh_holds_apply(&h, &c);
    assert(esh_holds_release(&h, ESH_SOURCE_HTTP, &r));
    assert(!h.keys[4] && !h.mods[0] && !h.buttons[1]);
    assert(h.keys[5] == ESH_SOURCE_WS + 1 && h.keys[6] == ESH_SOURCE_BLE + 1 &&
           h.keys[7] == ESH_SOURCE_MQTT + 1);
    assert(r.mods == 14 && !memcmp(r.keys, (uint8_t[]){5, 6, 7, 0, 0, 0}, 6));
    assert(r.buttons == 5 && r.consumer == 0xcd);
    assert(esh_holds_release(&h, ESH_SOURCE_MQTT, &r));
    assert(!h.keys[7] && !h.mods[3] && !h.buttons[2] && !h.consumer && !h.consumer_usage);
    assert(r.mods == 6 && !memcmp(r.keys, (uint8_t[]){5, 6, 0, 0, 0, 0}, 6));
    assert(r.buttons == 1 && !r.consumer);
    assert(!esh_holds_release(&h, ESH_SOURCE_HTTP, &r));
    assert(r.mods == 6 && r.keys[0] == 5 && r.buttons == 1);
    /* A repeated press transfers ownership; expiry must respect the new owner. */
    c = (esh_command){.op = ESH_DOWN, .key = 5, .source = ESH_SOURCE_BLE};
    esh_holds_apply(&h, &c);
    c = (esh_command){.op = ESH_CONSUMER_DOWN, .usage = 0xe9, .source = ESH_SOURCE_BLE};
    esh_holds_apply(&h, &c);
    assert(esh_holds_release(&h, ESH_SOURCE_WS, &r));
    assert(r.mods == 4 && r.keys[0] == 5 && r.keys[1] == 6 && !r.buttons && r.consumer == 0xe9);
    assert(esh_holds_release(&h, ESH_SOURCE_BLE, &r));
    assert(!r.mods && !memcmp(r.keys, (uint8_t[6]){0}, 6) && !r.buttons && !r.consumer);

    /* Rebuild boot rollover from the remaining keys, then recover to six keys. */
    for (unsigned i = 4; i < 12; i++) {
        c = (esh_command){.op = ESH_DOWN,
                          .key = i,
                          .source = i == 4   ? ESH_SOURCE_HTTP
                                    : i == 5 ? ESH_SOURCE_MQTT
                                             : ESH_SOURCE_BLE};
        esh_holds_apply(&h, &c);
    }
    assert(esh_holds_release(&h, ESH_SOURCE_HTTP, &r));
    assert(!memcmp(r.keys, (uint8_t[]){1, 1, 1, 1, 1, 1}, 6));
    assert(esh_holds_release(&h, ESH_SOURCE_MQTT, &r));
    assert(!memcmp(r.keys, (uint8_t[]){6, 7, 8, 9, 10, 11}, 6));
    for (unsigned i = 6; i < 12; i++)
        assert(h.keys[i] == ESH_SOURCE_BLE + 1);
    puts("PASS source-only release: remaining keys/modifiers/buttons/consumer, takeover and "
         "rollover");
}
static void text_cancel(void) {
    unsigned remaining = 3;
    assert(!esh_text_expand(esh_layout_find("us"), (const uint8_t *)"abcdef", 6, cancel_stroke,
                            &remaining));
    assert(remaining == 0);
    puts("PASS TEXT expansion stops immediately when its stroke callback cancels");
}
int main(void) {
    vectors();
    malformed();
    json();
    layout();
    json_depth();
    safety();
    source_release();
    text_cancel();
    puts("ALL HOST TESTS PASSED");
    return 0;
}

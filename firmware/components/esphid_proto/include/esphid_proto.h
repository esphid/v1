#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include "cJSON.h"

#define ESH_PACKET_MAX 512
#define ESH_RPC_MAX 8192
#define ESH_DELAY_MAX 5000

enum {
    ESH_MOVE = 1,
    ESH_BUTTONS,
    ESH_SCROLL,
    ESH_CLICK,
    ESH_ABS,
    ESH_REPORT,
    ESH_DOWN = 0x10,
    ESH_UP,
    ESH_TAP,
    ESH_KEY_REPORT,
    ESH_MODS,
    ESH_TEXT,
    ESH_RELEASE,
    ESH_CONSUMER_DOWN = 0x18,
    ESH_CONSUMER_UP,
    ESH_CONSUMER_TAP,
    ESH_DELAY = 0x20,
    ESH_PING = 0x30,
    ESH_STATE,
    ESH_AUTH = 0x40
};
enum {
    ESH_UNKNOWN = 1,
    ESH_TRUNCATED,
    ESH_UNAUTHORIZED,
    ESH_QUEUE_FULL,
    ESH_USB_NOT_READY,
    ESH_BAD_ARG
};
typedef struct {
    uint8_t op, buttons, mods, key, keys[6], len;
    uint8_t source; /* Internal queue metadata, never encoded on the wire. */
    int16_t dx, dy;
    int8_t wheel, pan;
    uint16_t x, y, usage, ms;
    uint32_t nonce;
    uint8_t data[255];
} esh_command;
typedef bool (*esh_command_fn)(void *, esh_command *);
typedef void (*esh_error_fn)(void *, uint8_t, uint8_t);
bool esh_parse(const uint8_t *, size_t, esh_command_fn, esh_error_fn, void *);
size_t esh_event_pong(uint8_t *, uint32_t, uint32_t);
size_t esh_event_leds(uint8_t *, uint8_t);
size_t esh_event_state(uint8_t *, uint8_t, uint8_t, uint16_t);
size_t esh_event_error(uint8_t *, uint8_t, uint8_t);
size_t esh_event_auth(uint8_t *, uint8_t);
bool esh_constant_equal(const void *, size_t, const void *, size_t);
int esh_key(const cJSON *);
int esh_consumer(const cJSON *);
int esh_mods(const cJSON *);
bool esh_json(const cJSON *, esh_command_fn, void *, char *, size_t);
typedef struct {
    uint8_t mods, usage;
} esh_stroke;
typedef struct {
    const char *name;
    const esh_stroke *ascii;
} esh_layout;
const esh_layout *esh_layout_find(const char *);
/* Consumes a complete UTF-8 scalar, including unmapped characters; invalid input consumes one byte.
 */
size_t esh_utf8_stroke(const esh_layout *, const uint8_t *, size_t, esh_stroke *);
typedef bool (*esh_stroke_fn)(void *, esh_stroke);
bool esh_text_expand(const esh_layout *, const uint8_t *, size_t, esh_stroke_fn, void *);
typedef struct {
    int64_t x, y, wheel, pan;
} esh_motion;
void esh_motion_add(esh_motion *, int16_t, int16_t, int8_t, int8_t);
bool esh_motion_take(esh_motion *, int16_t *, int16_t *, int8_t *, int8_t *);

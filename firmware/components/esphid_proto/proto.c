#include "esphid_proto.h"
#include <string.h>
#include <limits.h>
static uint16_t u16(const uint8_t *p) {
    return p[0] | (uint16_t)p[1] << 8;
}
static uint32_t u32(const uint8_t *p) {
    return u16(p) | (uint32_t)u16(p + 2) << 16;
}
static void w16(uint8_t *p, uint16_t v) {
    p[0] = v;
    p[1] = v >> 8;
}
static void w32(uint8_t *p, uint32_t v) {
    w16(p, v);
    w16(p + 2, v >> 16);
}
bool esh_parse(const uint8_t *p, size_t n, esh_command_fn fn, esh_error_fn err, void *ctx) {
    if (n > ESH_PACKET_MAX) {
        if (err)
            err(ctx, ESH_BAD_ARG, n ? p[0] : 0);
        return false;
    }
    while (n) {
        esh_command c = {.op = *p++};
        n--;
        size_t need = 0;
        switch (c.op) {
        case ESH_MOVE:
        case ESH_PING:
            need = 4;
            break;
        case ESH_BUTTONS:
        case ESH_CLICK:
        case ESH_DOWN:
        case ESH_UP:
        case ESH_MODS:
            need = 1;
            break;
        case ESH_SCROLL:
        case ESH_TAP:
        case ESH_CONSUMER_DOWN:
        case ESH_CONSUMER_TAP:
        case ESH_DELAY:
            need = 2;
            break;
        case ESH_ABS:
            need = 5;
            break;
        case ESH_REPORT:
        case ESH_KEY_REPORT:
            need = 7;
            break;
        case ESH_TEXT:
        case ESH_AUTH:
            if (!n) {
                if (err)
                    err(ctx, ESH_TRUNCATED, c.op);
                return false;
            }
            need = 1 + p[0];
            break;
        case ESH_RELEASE:
        case ESH_CONSUMER_UP:
        case ESH_STATE:
            break;
        default:
            if (err)
                err(ctx, ESH_UNKNOWN, c.op);
            return false;
        }
        if (n < need) {
            if (err)
                err(ctx, ESH_TRUNCATED, c.op);
            return false;
        }
        switch (c.op) {
        case ESH_MOVE:
            c.dx = (int16_t)u16(p);
            c.dy = (int16_t)u16(p + 2);
            break;
        case ESH_BUTTONS:
        case ESH_CLICK:
            c.buttons = p[0] & 31;
            break;
        case ESH_SCROLL:
            c.wheel = (int8_t)p[0];
            c.pan = (int8_t)p[1];
            break;
        case ESH_ABS:
            c.x = u16(p);
            c.y = u16(p + 2);
            c.buttons = p[4] & 31;
            if (c.x > 32767 || c.y > 32767) {
                if (err)
                    err(ctx, ESH_BAD_ARG, c.op);
                return false;
            }
            break;
        case ESH_REPORT:
            c.buttons = p[0] & 31;
            c.dx = (int16_t)u16(p + 1);
            c.dy = (int16_t)u16(p + 3);
            c.wheel = (int8_t)p[5];
            c.pan = (int8_t)p[6];
            break;
        case ESH_DOWN:
        case ESH_UP:
            c.key = p[0];
            break;
        case ESH_TAP:
            c.mods = p[0];
            c.key = p[1];
            break;
        case ESH_KEY_REPORT:
            c.mods = p[0];
            memcpy(c.keys, p + 1, 6);
            break;
        case ESH_MODS:
            c.mods = p[0];
            break;
        case ESH_TEXT:
        case ESH_AUTH:
            c.len = p[0];
            memcpy(c.data, p + 1, c.len);
            break;
        case ESH_CONSUMER_DOWN:
        case ESH_CONSUMER_TAP:
            c.usage = u16(p);
            break;
        case ESH_DELAY:
            c.ms = u16(p);
            if (c.ms > ESH_DELAY_MAX)
                c.ms = ESH_DELAY_MAX;
            break;
        case ESH_PING:
            c.nonce = u32(p);
            break;
        default:
            break;
        }
        if (fn && !fn(ctx, &c))
            return false;
        p += need;
        n -= need;
    }
    return true;
}
size_t esh_event_pong(uint8_t *p, uint32_t n, uint32_t t) {
    p[0] = 0x80;
    w32(p + 1, n);
    w32(p + 5, t);
    return 9;
}
size_t esh_event_leds(uint8_t *p, uint8_t l) {
    p[0] = 0x81;
    p[1] = l;
    return 2;
}
size_t esh_event_state(uint8_t *p, uint8_t f, uint8_t l, uint16_t q) {
    p[0] = 0x82;
    p[1] = f;
    p[2] = l;
    w16(p + 3, q);
    return 5;
}
size_t esh_event_error(uint8_t *p, uint8_t c, uint8_t o) {
    p[0] = 0x83;
    p[1] = c;
    p[2] = o;
    return 3;
}
size_t esh_event_auth(uint8_t *p, uint8_t r) {
    p[0] = 0x84;
    p[1] = r;
    return 2;
}
bool esh_constant_equal(const void *a, size_t an, const void *b, size_t bn) {
    const volatile uint8_t *x = a, *y = b;
    volatile size_t d = an ^ bn;
    /* The caller uses a fixed token capacity for the secret side; no early-exit byte comparison. */
    size_t n = an > bn ? an : bn;
    for (size_t i = 0; i < n; i++) {
        uint8_t av = i < an ? x[i] : 0, bv = i < bn ? y[i] : 0;
        d |= av ^ bv;
    }
    return d == 0;
}
void esh_motion_add(esh_motion *m, int16_t x, int16_t y, int8_t w, int8_t p) {
    m->x += x;
    m->y += y;
    m->wheel += w;
    m->pan += p;
}
static int64_t clamp(int64_t v, int64_t lo, int64_t hi) {
    return v < lo ? lo : v > hi ? hi : v;
}
bool esh_motion_take(esh_motion *m, int16_t *x, int16_t *y, int8_t *w, int8_t *p) {
    if (!(m->x || m->y || m->wheel || m->pan))
        return false;
    *x = clamp(m->x, INT16_MIN, INT16_MAX);
    *y = clamp(m->y, INT16_MIN, INT16_MAX);
    *w = clamp(m->wheel, INT8_MIN, INT8_MAX);
    *p = clamp(m->pan, INT8_MIN, INT8_MAX);
    m->x -= *x;
    m->y -= *y;
    m->wheel -= *w;
    m->pan -= *p;
    return true;
}

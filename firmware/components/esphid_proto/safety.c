#include "esphid_safety.h"
#include <string.h>

static void bits(uint8_t *owners, unsigned n, unsigned value, uint8_t owner) {
    for (unsigned i = 0; i < n; i++)
        owners[i] = value & (1U << i) ? owner : 0;
}
void esh_holds_keyboard(esh_holds *h, uint8_t mods, const uint8_t keys[6], unsigned source) {
    uint8_t owner = source + 1;
    bits(h->mods, 8, mods, owner);
    memset(h->keys, 0, sizeof(h->keys));
    for (unsigned i = 0; i < 6; i++)
        if (keys[i])
            h->keys[keys[i]] = owner;
}
void esh_holds_apply(esh_holds *h, const esh_command *c) {
    uint8_t owner = c->source + 1;
    switch (c->op) {
    case ESH_DOWN:
    case ESH_UP:
        if (c->key >= 224 && c->key <= 231)
            h->mods[c->key - 224] = c->op == ESH_DOWN ? owner : 0;
        else if (c->key)
            h->keys[c->key] = c->op == ESH_DOWN ? owner : 0;
        break;
    case ESH_MODS:
        bits(h->mods, 8, c->mods, owner);
        break;
    case ESH_KEY_REPORT:
        esh_holds_keyboard(h, c->mods, c->keys, c->source);
        break;
    case ESH_TAP:
        esh_holds_keyboard(h, 0, (const uint8_t[6]){0}, c->source);
        break;
    case ESH_BUTTONS:
    case ESH_REPORT:
    case ESH_ABS:
        bits(h->buttons, 5, c->buttons, owner);
        break;
    case ESH_CONSUMER_DOWN:
        h->consumer = c->usage ? owner : 0;
        h->consumer_usage = c->usage;
        break;
    case ESH_CONSUMER_UP:
    case ESH_CONSUMER_TAP:
        h->consumer = 0;
        h->consumer_usage = 0;
        break;
    case ESH_RELEASE:
        memset(h, 0, sizeof(*h));
        break;
    default:
        break;
    }
}
bool esh_holds_release(esh_holds *h, unsigned source, esh_hold_report *report) {
    uint8_t owner = source + 1;
    bool changed = false;
    uint8_t *groups[] = {h->keys, h->mods, h->buttons, &h->consumer};
    const unsigned sizes[] = {256, 8, 5, 1};
    for (unsigned g = 0; g < 4; g++)
        for (unsigned i = 0; i < sizes[g]; i++)
            if (groups[g][i] == owner) {
                groups[g][i] = 0;
                changed = true;
            }
    if (!h->consumer)
        h->consumer_usage = 0;
    *report = (esh_hold_report){.consumer = h->consumer_usage};
    for (unsigned i = 0; i < 8; i++)
        if (h->mods[i])
            report->mods |= 1U << i;
    for (unsigned i = 0; i < 5; i++)
        if (h->buttons[i])
            report->buttons |= 1U << i;
    unsigned count = 0;
    for (unsigned i = 1; i < 256; i++)
        if (h->keys[i]) {
            if (count < 6)
                report->keys[count] = i;
            count++;
        }
    if (count > 6)
        memset(report->keys, 1, sizeof(report->keys));
    return changed;
}
bool esh_holds_expired(const esh_holds *h, unsigned source, uint32_t now, uint32_t last,
                       uint32_t timeout) {
    if ((source != ESH_SOURCE_HTTP && source != ESH_SOURCE_MQTT) ||
        (uint32_t)(now - last) < timeout)
        return false;
    uint8_t owner = source + 1;
    for (unsigned i = 0; i < 256; i++)
        if (h->keys[i] == owner)
            return true;
    for (unsigned i = 0; i < 8; i++)
        if (h->mods[i] == owner)
            return true;
    for (unsigned i = 0; i < 5; i++)
        if (h->buttons[i] == owner)
            return true;
    return h->consumer == owner;
}
size_t esh_utf8_prefix(const char *s, size_t len, size_t max) {
    if (len <= max)
        return len;
    while (max && ((unsigned char)s[max] & 0xc0) == 0x80)
        max--;
    return max;
}

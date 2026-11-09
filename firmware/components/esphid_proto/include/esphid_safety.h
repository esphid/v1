#pragma once
#include "esphid_proto.h"

enum { ESH_SOURCE_HTTP, ESH_SOURCE_WS, ESH_SOURCE_BLE, ESH_SOURCE_MQTT, ESH_SOURCE_COUNT };
/* Zero is unheld; otherwise the byte identifies the most recent writer + 1. */
typedef struct {
    uint8_t keys[256], mods[8], buttons[5], consumer;
    uint16_t consumer_usage;
} esh_holds;
typedef struct {
    uint8_t mods, keys[6], buttons;
    uint16_t consumer;
} esh_hold_report;
void esh_holds_apply(esh_holds *, const esh_command *);
void esh_holds_keyboard(esh_holds *, uint8_t, const uint8_t[6], unsigned);
/* Remove only this source's holds and synthesize the remaining USB state. */
bool esh_holds_release(esh_holds *, unsigned, esh_hold_report *);
bool esh_holds_expired(const esh_holds *, unsigned, uint32_t, uint32_t, uint32_t);
/* A prefix of a valid UTF-8 string, never ending inside a scalar. */
size_t esh_utf8_prefix(const char *, size_t, size_t);

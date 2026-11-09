#include "esphid.h"
#include "tinyusb.h"
#include "tinyusb_default_config.h"
#include "tusb.h"
static QueueHandle_t commands;
static TaskHandle_t worker;
static uint8_t leds;
static uint8_t mods, keys[6], buttons;
static bool pressed[256];
static esh_motion motion;
static esh_holds holds;
static DRAM_ATTR _Atomic uint32_t abort_generation;
static DRAM_ATTR _Atomic uint32_t release_sources;
static DRAM_ATTR _Atomic uint32_t last_command[ESH_SOURCE_COUNT];
static uint32_t handled_generation, watchdog_at;
static unsigned text_source;
static unsigned current_source;
static uint32_t command_releases;
static bool command_active, source_cancelled;
static bool releasing, absolute_used;
static uint16_t absolute_x, absolute_y;
static void release_source(unsigned);
/* PSRAM builds route C11 atomics through IDF's address-checking helpers.
 * DRAM_ATTR keeps these words on their hardware-atomic (no spinlock) branch. */
_Static_assert(sizeof(esh_command) == 288, "Source metadata must fit existing queue padding");
void hid_abort(void) {
    atomic_fetch_add_explicit(&abort_generation, 1, memory_order_release);
    if (worker)
        xTaskNotifyGive(worker);
}
void hid_release_source(transport_kind source) {
    atomic_fetch_or_explicit(&release_sources, 1U << source, memory_order_release);
    if (worker)
        xTaskNotifyGive(worker);
}
/* Only the HID worker reads hold ownership. Transport admission only stores a
 * lock-free 32-bit timestamp and tags the existing queue item. */
static bool cancelled(void) {
    if (releasing)
        return false;
    uint32_t pending = atomic_load_explicit(&release_sources, memory_order_acquire);
    if (pending)
        pending = atomic_exchange_explicit(&release_sources, 0, memory_order_acquire);
    uint32_t now = (uint32_t)now_ms();
    if ((uint32_t)(now - watchdog_at) >= 100) {
        watchdog_at = now;
        const unsigned sources[] = {ESH_SOURCE_HTTP, ESH_SOURCE_MQTT};
        for (unsigned i = 0; i < 2; i++) {
            unsigned source = sources[i];
            if (esh_holds_expired(&holds, source, now, atomic_load(&last_command[source]), 10000)) {
                pending |= 1U << source;
            }
        }
    }
    for (unsigned source = 0; source < ESH_SOURCE_COUNT; source++)
        if (pending & (1U << source)) {
            command_releases |= 1U << source;
            if (command_active && current_source == source)
                source_cancelled = true;
            release_source(source);
        }
    return source_cancelled ||
           atomic_load_explicit(&abort_generation, memory_order_acquire) != handled_generation;
}
static bool wait_ms(unsigned ms) {
    int64_t until = now_ms() + ms;
    while (!cancelled()) {
        int64_t left = until - now_ms();
        if (left <= 0)
            return true;
        ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(left > 100 ? 100 : left));
    }
    return false;
}
static const uint8_t keyboard_desc[] = {TUD_HID_REPORT_DESC_KEYBOARD()};
static const uint8_t pointer_desc[] = {
    /* Relative mouse: 5 buttons + pad, signed 16-bit X/Y, signed wheel and AC pan. */
    0x05, 0x01, 0x09, 0x02, 0xa1, 0x01, 0x85, 1, 0x09, 0x01, 0xa1, 0x00, 0x05, 0x09, 0x19, 1, 0x29,
    5, 0x15, 0, 0x25, 1, 0x75, 1, 0x95, 5, 0x81, 2, 0x75, 3, 0x95, 1, 0x81, 3, 0x05, 1, 0x09, 0x30,
    0x09, 0x31, 0x16, 0, 0x80, 0x26, 0xff, 0x7f, 0x75, 16, 0x95, 2, 0x81, 6, 0x09, 0x38, 0x15, 0x80,
    0x25, 0x7f, 0x75, 8, 0x95, 1, 0x81, 6, 0x05, 0x0c, 0x0a, 0x38, 0x02, 0x81, 6, 0xc0, 0xc0, 0x05,
    0x0c, 0x09, 0x01, 0xa1, 0x01, 0x85, 2, 0x15, 0, 0x27, 0xff, 0xff, 0, 0, 0x19, 0, 0x2a, 0xff,
    0xff, 0x75, 16, 0x95, 1, 0x81, 0, 0xc0,
    /* Absolute mouse: 5 buttons + pad, 0..32767 X/Y, signed wheel. */
    0x05, 1, 0x09, 2, 0xa1, 1, 0x85, 3, 0x09, 1, 0xa1, 0, 0x05, 9, 0x19, 1, 0x29, 5, 0x15, 0, 0x25,
    1, 0x75, 1, 0x95, 5, 0x81, 2, 0x75, 3, 0x95, 1, 0x81, 3, 0x05, 1, 0x09, 0x30, 0x09, 0x31, 0x15,
    0, 0x26, 0xff, 0x7f, 0x75, 16, 0x95, 2, 0x81, 2, 0x09, 0x38, 0x15, 0x80, 0x25, 0x7f, 0x75, 8,
    0x95, 1, 0x81, 6, 0xc0, 0xc0};
static const tusb_desc_device_t device = {.bLength = sizeof(tusb_desc_device_t),
                                          .bDescriptorType = TUSB_DESC_DEVICE,
                                          .bcdUSB = 0x0200,
                                          .bMaxPacketSize0 = 64,
                                          .idVendor = 0x303a,
                                          .idProduct = 0x4005,
                                          .bcdDevice = 0x0100,
                                          .iManufacturer = 1,
                                          .iProduct = 2,
                                          .iSerialNumber = 3,
                                          .bNumConfigurations = 1};
static const uint8_t configuration[] = {
    TUD_CONFIG_DESCRIPTOR(1, 2, 0, TUD_CONFIG_DESC_LEN + 2 * TUD_HID_DESC_LEN,
                          TUSB_DESC_CONFIG_ATT_REMOTE_WAKEUP, 100),
    TUD_HID_DESCRIPTOR(0, 0, HID_ITF_PROTOCOL_KEYBOARD, sizeof(keyboard_desc), 0x81, 16, 1),
    TUD_HID_DESCRIPTOR(1, 0, HID_ITF_PROTOCOL_NONE, sizeof(pointer_desc), 0x82, 16, 1)};
static const char *strings[] = {(const char[]){0x09, 0x04}, "ESPHID", "ESPHID Keyboard + Mouse",
                                device_id};
uint8_t const *tud_hid_descriptor_report_cb(uint8_t instance) {
    return instance ? pointer_desc : keyboard_desc;
}
uint16_t tud_hid_get_report_cb(uint8_t instance, uint8_t report_id, hid_report_type_t type,
                               uint8_t *buffer, uint16_t reqlen) {
    if (!instance && type == HID_REPORT_TYPE_OUTPUT && reqlen) {
        buffer[0] = leds;
        return 1;
    }
    return 0;
}
void tud_hid_set_report_cb(uint8_t instance, uint8_t report_id, hid_report_type_t type,
                           uint8_t const *p, uint16_t n) {
    if (!instance && type == HID_REPORT_TYPE_OUTPUT && n) {
        leds = p[0] & 31;
        uint8_t e[2];
        broadcast_event(e, esh_event_leds(e, leds));
    }
}
void tud_hid_report_complete_cb(uint8_t instance, uint8_t const *report, uint16_t len) {
    if (worker)
        xTaskNotifyGive(worker);
}
static bool ready(unsigned i) {
    for (;;) {
        if (!releasing && cancelled())
            return false;
        if (!tud_mounted())
            return false;
        /* cancelled() may have emitted source-release reports. Recheck the
         * endpoint afterwards rather than submitting into a now-busy endpoint. */
        if (tud_hid_n_ready(i))
            return true;
        if (tud_suspended())
            tinyusb_remote_wakeup();
        ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(2));
    }
}
static void keyboard(void) {
    if (ready(0))
        tud_hid_n_keyboard_report(0, 0, mods, keys);
}
static void relative(int16_t x, int16_t y, int8_t w, int8_t p) {
    if (ready(1)) {
        uint8_t r[] = {buttons,           (uint8_t)x, (uint8_t)(x >> 8), (uint8_t)y,
                       (uint8_t)(y >> 8), (uint8_t)w, (uint8_t)p};
        tud_hid_n_report(1, 1, r, sizeof(r));
    }
}
static void flush(void) {
    int16_t x, y;
    int8_t w, p;
    while (!cancelled() && esh_motion_take(&motion, &x, &y, &w, &p))
        relative(x, y, w, p);
}
static void consumer(uint16_t u) {
    uint8_t b[] = {u, u >> 8};
    if (ready(1))
        tud_hid_n_report(1, 2, b, 2);
}
/* Only this worker mutates provenance or USB state. Source release does not
 * reset motion/queue or cancel another source's in-progress TEXT/DELAY. */
static void release_source(unsigned source) {
    esh_hold_report report;
    /* Send even if provenance is already clear: a key-up may have updated the
     * desired state but still be waiting for its USB endpoint when cancelled. */
    esh_holds_release(&holds, source, &report);
    mods = report.mods;
    memcpy(keys, report.keys, sizeof(keys));
    for (unsigned i = 0; i < 256; i++)
        pressed[i] = holds.keys[i] != 0;
    buttons = report.buttons;
    releasing = true;
    keyboard();
    relative(0, 0, 0, 0);
    uint8_t absolute[] = {buttons, absolute_x, absolute_x >> 8, absolute_y, absolute_y >> 8, 0};
    if (absolute_used && ready(1))
        tud_hid_n_report(1, 3, absolute, sizeof(absolute));
    consumer(report.consumer);
    releasing = false;
}
static bool text_stroke(void *ctx, esh_stroke s) {
    if (cancelled())
        return false;
    mods = s.mods;
    memset(pressed, 0, sizeof(pressed));
    memset(keys, 0, 6);
    keys[0] = s.usage;
    esh_holds_keyboard(&holds, mods, keys, text_source);
    keyboard();
    unsigned delay = atomic_load(&keyboard_delay);
    return !cancelled() && (s.usage || !delay || wait_ms(delay));
}
static void key_change(uint8_t k, bool down) {
    if (k >= 224 && k <= 231) {
        if (down)
            mods |= 1 << (k - 224);
        else
            mods &= ~(1 << (k - 224));
    } else if (k) {
        pressed[k] = down;
        unsigned count = 0;
        memset(keys, 0, 6);
        for (unsigned i = 1; i < 256; i++)
            if (pressed[i]) {
                if (count < 6)
                    keys[count] = i;
                count++;
            }
        if (count > 6)
            memset(keys, 1, 6);
    }
    keyboard();
}
static void clear_state(void) {
    memset(&motion, 0, sizeof(motion));
    memset(&holds, 0, sizeof(holds));
    mods = buttons = 0;
    memset(pressed, 0, sizeof(pressed));
    memset(keys, 0, sizeof(keys));
}
static void release_all(void) {
    clear_state();
    releasing = true;
    keyboard();
    relative(0, 0, 0, 0);
    /* Clear the button state for hosts that last received an absolute report too. */
    uint8_t absolute[] = {0, absolute_x, absolute_x >> 8, absolute_y, absolute_y >> 8, 0};
    if (absolute_used && ready(1))
        tud_hid_n_report(1, 3, absolute, sizeof(absolute));
    consumer(0);
    releasing = false;
}
static void run(void *unused) {
    esh_command c;
    for (;;) {
        command_active = source_cancelled = false;
        command_releases = 0;
        if (cancelled()) {
            handled_generation = atomic_load_explicit(&abort_generation, memory_order_acquire);
            xQueueReset(commands);
            release_all();
            continue;
        }
        if (xQueueReceive(commands, &c, 0) != pdTRUE) {
            if (tud_hid_n_ready(1))
                flush();
            ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(100));
            continue;
        }
        current_source = c.source;
        command_active = true;
        if (!tud_mounted()) {
            clear_state();
            continue;
        }
        if (cancelled())
            continue;
        if (tud_suspended())
            tinyusb_remote_wakeup();
        if (c.op == ESH_MOVE || c.op == ESH_SCROLL) {
            esh_motion_add(&motion, c.dx, c.dy, c.wheel, c.pan);
            if (tud_hid_n_ready(1))
                flush();
            continue;
        }
        flush();
        if (cancelled())
            continue;
        esh_holds_apply(&holds, &c);
        switch (c.op) {
        case ESH_BUTTONS:
            buttons = c.buttons;
            relative(0, 0, 0, 0);
            break;
        case ESH_CLICK: {
            uint8_t old = buttons, old_owners[5];
            memcpy(old_owners, holds.buttons, sizeof(old_owners));
            for (unsigned i = 0; i < 5; i++)
                if ((c.buttons & (1U << i)) && !(old & (1U << i)))
                    holds.buttons[i] = c.source + 1;
            buttons |= c.buttons;
            relative(0, 0, 0, 0);
            if (cancelled())
                break;
            /* A different source may expire while the endpoint is busy. Never
             * restore its saved persistent buttons after the click. */
            buttons = 0;
            for (unsigned i = 0; i < 5; i++) {
                if (old_owners[i] && (command_releases & (1U << (old_owners[i] - 1))))
                    old_owners[i] = 0;
                if (old_owners[i])
                    buttons |= 1U << i;
            }
            memcpy(holds.buttons, old_owners, sizeof(old_owners));
            relative(0, 0, 0, 0);
            break;
        }
        case ESH_REPORT:
            buttons = c.buttons;
            relative(c.dx, c.dy, c.wheel, c.pan);
            break;
        case ESH_ABS: {
            buttons = c.buttons;
            absolute_x = c.x;
            absolute_y = c.y;
            absolute_used = true;
            if (ready(1)) {
                uint8_t b[] = {buttons, c.x, c.x >> 8, c.y, c.y >> 8, 0};
                tud_hid_n_report(1, 3, b, sizeof(b));
            }
            break;
        }
        case ESH_DOWN:
            key_change(c.key, true);
            break;
        case ESH_UP:
            key_change(c.key, false);
            break;
        case ESH_MODS:
            mods = c.mods;
            keyboard();
            break;
        case ESH_KEY_REPORT:
            mods = c.mods;
            memset(pressed, 0, sizeof(pressed));
            for (unsigned i = 0; i < 6; i++)
                if (c.keys[i])
                    pressed[c.keys[i]] = true;
            memcpy(keys, c.keys, 6);
            keyboard();
            break;
        case ESH_TAP:
            mods = c.mods;
            memset(pressed, 0, sizeof(pressed));
            memset(keys, 0, 6);
            if (c.key >= 224 && c.key <= 231)
                mods |= 1 << (c.key - 224);
            else
                keys[0] = c.key;
            esh_holds_keyboard(&holds, mods, keys, c.source);
            keyboard();
            if (cancelled())
                break;
            mods = 0;
            memset(keys, 0, 6);
            esh_holds_apply(&holds, &c);
            keyboard();
            break;
        case ESH_TEXT:
            text_source = c.source;
            esh_text_expand(atomic_load(&keyboard_layout), c.data, c.len, text_stroke, NULL);
            break;
        case ESH_CONSUMER_DOWN:
            consumer(c.usage);
            break;
        case ESH_CONSUMER_UP:
            consumer(0);
            break;
        case ESH_CONSUMER_TAP:
            holds.consumer = c.usage ? c.source + 1 : 0;
            holds.consumer_usage = c.usage;
            consumer(c.usage);
            if (cancelled())
                break;
            holds.consumer = 0;
            holds.consumer_usage = 0;
            consumer(0);
            break;
        case ESH_RELEASE:
            release_all();
            break;
        case ESH_DELAY:
            wait_ms(c.ms);
            break;
        default:
            break;
        }
    }
}
bool hid_enqueue(esh_command *c, transport_kind source) {
    c->source = source;
    bool ok = xQueueSend(commands, c, 0) == pdTRUE;
    if (ok) {
        atomic_store(&last_command[source], (uint32_t)now_ms());
        xTaskNotifyGive(worker);
    }
    return ok;
}
unsigned hid_depth(void) {
    return uxQueueMessagesWaiting(commands);
}
bool hid_mounted(void) {
    return tud_mounted();
}
bool hid_suspended(void) {
    return tud_suspended();
}
uint8_t hid_leds(void) {
    return leds;
}
void hid_init(void) {
    commands = xQueueCreate(64, sizeof(esh_command));
    assert(commands);
    BaseType_t ok = xTaskCreatePinnedToCore(run, "hid", 4096, NULL, 21, &worker, 1);
    assert(ok == pdPASS);
    tinyusb_config_t c = TINYUSB_DEFAULT_CONFIG();
    c.task.priority = 22;
    c.task.xCoreID = 1;
    c.descriptor.device = &device;
    c.descriptor.full_speed_config = configuration;
    c.descriptor.string = strings;
    c.descriptor.string_count = 4;
    ESP_ERROR_CHECK(tinyusb_driver_install(&c));
    usb_started = true;
}

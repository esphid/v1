#include "esphid_proto.h"
#include <string.h>
#define S(k) {2, k}
static const esh_stroke us[128] = {
    ['\b'] = {0, 42}, ['\t'] = {0, 43}, ['\n'] = {0, 40}, [' '] = {0, 44},  ['!'] = S(30),
    ['"'] = S(52),    ['#'] = S(32),    ['$'] = S(33),    ['%'] = S(34),    ['&'] = S(36),
    ['\''] = {0, 52}, ['('] = S(38),    [')'] = S(39),    ['*'] = S(37),    ['+'] = S(46),
    [','] = {0, 54},  ['-'] = {0, 45},  ['.'] = {0, 55},  ['/'] = {0, 56},  ['0'] = {0, 39},
    ['1'] = {0, 30},  ['2'] = {0, 31},  ['3'] = {0, 32},  ['4'] = {0, 33},  ['5'] = {0, 34},
    ['6'] = {0, 35},  ['7'] = {0, 36},  ['8'] = {0, 37},  ['9'] = {0, 38},  [':'] = S(51),
    [';'] = {0, 51},  ['<'] = S(54),    ['='] = {0, 46},  ['>'] = S(55),    ['?'] = S(56),
    ['@'] = S(31),    ['A'] = S(4),     ['B'] = S(5),     ['C'] = S(6),     ['D'] = S(7),
    ['E'] = S(8),     ['F'] = S(9),     ['G'] = S(10),    ['H'] = S(11),    ['I'] = S(12),
    ['J'] = S(13),    ['K'] = S(14),    ['L'] = S(15),    ['M'] = S(16),    ['N'] = S(17),
    ['O'] = S(18),    ['P'] = S(19),    ['Q'] = S(20),    ['R'] = S(21),    ['S'] = S(22),
    ['T'] = S(23),    ['U'] = S(24),    ['V'] = S(25),    ['W'] = S(26),    ['X'] = S(27),
    ['Y'] = S(28),    ['Z'] = S(29),    ['['] = {0, 47},  ['\\'] = {0, 49}, [']'] = {0, 48},
    ['^'] = S(35),    ['_'] = S(45),    ['`'] = {0, 53},  ['a'] = {0, 4},   ['b'] = {0, 5},
    ['c'] = {0, 6},   ['d'] = {0, 7},   ['e'] = {0, 8},   ['f'] = {0, 9},   ['g'] = {0, 10},
    ['h'] = {0, 11},  ['i'] = {0, 12},  ['j'] = {0, 13},  ['k'] = {0, 14},  ['l'] = {0, 15},
    ['m'] = {0, 16},  ['n'] = {0, 17},  ['o'] = {0, 18},  ['p'] = {0, 19},  ['q'] = {0, 20},
    ['r'] = {0, 21},  ['s'] = {0, 22},  ['t'] = {0, 23},  ['u'] = {0, 24},  ['v'] = {0, 25},
    ['w'] = {0, 26},  ['x'] = {0, 27},  ['y'] = {0, 28},  ['z'] = {0, 29},  ['{'] = S(47),
    ['|'] = S(49),    ['}'] = S(48),    ['~'] = S(53)};
static const esh_layout layouts[] = {{"us", us}};
const esh_layout *esh_layout_find(const char *n) {
    for (size_t i = 0; i < sizeof(layouts) / sizeof(*layouts); i++)
        if (!strcmp(n, layouts[i].name))
            return &layouts[i];
    return NULL;
}
size_t esh_utf8_stroke(const esh_layout *l, const uint8_t *p, size_t n, esh_stroke *s) {
    *s = (esh_stroke){0};
    if (!n)
        return 0;
    if (p[0] < 128) {
        *s = l->ascii[p[0]];
        return 1;
    }
    size_t k = p[0] >= 0xc2 && p[0] <= 0xdf   ? 2
               : p[0] >= 0xe0 && p[0] <= 0xef ? 3
               : p[0] >= 0xf0 && p[0] <= 0xf4 ? 4
                                              : 1;
    if (k > n)
        return 1;
    for (size_t i = 1; i < k; i++)
        if ((p[i] & 0xc0) != 0x80)
            return 1;
    return k;
}
bool esh_text_expand(const esh_layout *l, const uint8_t *p, size_t n, esh_stroke_fn fn, void *ctx) {
    while (n) {
        esh_stroke s;
        size_t k = esh_utf8_stroke(l, p, n, &s);
        p += k;
        n -= k;
        if (s.usage && (!fn(ctx, s) || !fn(ctx, (esh_stroke){0})))
            return false;
    }
    return true;
}

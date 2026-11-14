/* DX Render - Memory Manager: aligned allocation and pooled bump arenas. */
#include "dxr_internal.h"

#include <stdarg.h>
#include <stdio.h>
#include <time.h>

static _Thread_local char g_error[512];

void dxr_set_error(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g_error, sizeof(g_error), fmt, ap);
    va_end(ap);
}

const char *dxr_last_error(void) { return g_error; }

uint64_t dxr_now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

void *dxr_alloc_raw(size_t size) {
    void *p = NULL;
    if (size == 0) size = 64;
    size = (size + 63) & ~(size_t)63;
    if (posix_memalign(&p, 64, size) != 0) return NULL;
    return p;
}

void *dxr_alloc(size_t size) {
    void *p = dxr_alloc_raw(size);
    if (p) memset(p, 0, (size + 63) & ~(size_t)63);
    return p;
}

void dxr_free(void *p) { free(p); }

void dxr_arena_init(dxr_arena *a, size_t block_size) {
    memset(a, 0, sizeof(*a));
    a->block_size = block_size;
}

void dxr_arena_destroy(dxr_arena *a) {
    dxr_arena_block *b = a->head;
    while (b) {
        dxr_arena_block *n = b->next;
        dxr_free(b);
        b = n;
    }
    memset(a, 0, sizeof(*a));
}

void dxr_arena_reset(dxr_arena *a) {
    if (a->total > a->peak) a->peak = a->total;
    for (dxr_arena_block *b = a->head; b; b = b->next) b->used = 0;
    a->cur = a->head;
    a->total = 0;
}

void *dxr_arena_alloc(dxr_arena *a, size_t size) {
    size = (size + 15) & ~(size_t)15;
    dxr_arena_block *b = a->cur;
    while (b) {
        if (b->cap - b->used >= size) {
            void *p = b->data + b->used;
            b->used += size;
            a->total += size;
            a->cur = b;
            return p;
        }
        b = b->next;
    }
    /* No pooled block fits: append a new one and keep it for later frames. */
    size_t cap = a->block_size;
    if (cap < size) cap = size;
    size_t header = (sizeof(dxr_arena_block) + 63) & ~(size_t)63;
    dxr_arena_block *nb = (dxr_arena_block *)dxr_alloc_raw(header + cap + 64);
    if (!nb) return NULL;
    nb->next = NULL;
    nb->cap = cap;
    nb->used = size;
    /* data[] starts right after the header struct; keep 16 byte alignment */
    if (!a->head) {
        a->head = nb;
    } else {
        dxr_arena_block *t = a->head;
        while (t->next) t = t->next;
        t->next = nb;
    }
    a->cur = nb;
    a->total += size;
    return nb->data;
}

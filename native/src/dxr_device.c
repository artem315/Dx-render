/* DX Render - Device: owns the scheduler, the arenas and the statistics. */
#include "dxr_internal.h"

#if defined(__ANDROID__)
#  include <android/native_window.h>
#endif

const char *dxr_simd_name(void) {
#if defined(DXR_NEON)
    return "ARM64 NEON";
#elif defined(DXR_SSE2)
    return "x86 SSE2";
#else
    return "scalar";
#endif
}

static void sanitize(dxr_config *c) {
    int cpus = dxr_cpu_count();
    if (c->worker_threads <= 0) c->worker_threads = cpus;
    if (c->worker_threads > DXR_MAX_THREADS) c->worker_threads = DXR_MAX_THREADS;
    if (c->tile_size != 16 && c->tile_size != 32 && c->tile_size != 64 && c->tile_size != 128) c->tile_size = 32;
}

dxr_device *dxr_device_create(const dxr_config *cfg) {
    dxr_device *dev = (dxr_device *)dxr_alloc(sizeof(dxr_device));
    if (!dev) return NULL;
    if (cfg) dev->cfg = *cfg;
    else { dev->cfg.occlusion_culling = 1; dev->cfg.fast_shaders = 1; dev->cfg.simd = 1; dev->cfg.adaptive = 1; }
    sanitize(&dev->cfg);
    dev->pool = dxr_pool_create(dev->cfg.worker_threads);
    if (!dev->pool) { dxr_free(dev); return NULL; }
    dev->cfg.worker_threads = dxr_pool_threads(dev->pool);
    for (int t = 0; t < DXR_MAX_THREADS; t++) dxr_arena_init(&dev->arena[t], 1u << 20);
    dxr_arena_init(&dev->pass_arena, 1u << 20);
    dxr_arena_init(&dev->vtx_arena, 1u << 20);
    return dev;
}

void dxr_device_destroy(dxr_device *dev) {
    if (!dev) return;
    dxr_pass_abort(dev);
    dxr_pool_destroy(dev->pool);
    for (int t = 0; t < DXR_MAX_THREADS; t++) dxr_arena_destroy(&dev->arena[t]);
    dxr_arena_destroy(&dev->pass_arena);
    dxr_arena_destroy(&dev->vtx_arena);
    dxr_free(dev);
}

void dxr_device_configure(dxr_device *dev, const dxr_config *cfg) {
    dxr_config c = *cfg;
    sanitize(&c);
    if (dev->pass) dxr_pass_end(dev);
    if (c.worker_threads != dxr_pool_threads(dev->pool)) {
        dxr_pool *np = dxr_pool_create(c.worker_threads);
        if (np) { dxr_pool_destroy(dev->pool); dev->pool = np; }
        c.worker_threads = dxr_pool_threads(dev->pool);
    }
    dev->cfg = c;
}

void dxr_device_get_config(const dxr_device *dev, dxr_config *out) { *out = dev->cfg; }

void dxr_device_get_stats(dxr_device *dev, dxr_stats *out, int reset) {
    dev->stats.texture_bytes = atomic_load(&dev->texture_bytes);
    if (out) *out = dev->stats;
    if (reset) {
        uint64_t peak = dev->stats.arena_bytes_peak;
        memset(&dev->stats, 0, sizeof(dev->stats));
        dev->stats.arena_bytes_peak = peak;
    }
}

void dxr_device_end_frame(dxr_device *dev) {
    if (dev->pass) dxr_pass_end(dev);
    dev->stats.frames++;
}

/* ---- presentation --------------------------------------------------------------------- */
typedef struct blit_ctx {
    const uint8_t *src; int src_w, src_h, src_stride;
    uint8_t *dst; int dst_w, dst_h, dst_stride, dst_format;
    int rows_per_job;
} blit_ctx;

static void blit_job(void *vctx, int index, int thread) {
    (void)thread;
    blit_ctx *b = (blit_ctx *)vctx;
    int y0 = index * b->rows_per_job, y1 = DXR_MIN(b->dst_h, y0 + b->rows_per_job);
    const int same = b->src_w == b->dst_w && b->src_h == b->dst_h;
    for (int y = y0; y < y1; y++) {
        /* destination is top-down, source bottom-up */
        int sy = same ? b->src_h - 1 - y : b->src_h - 1 - (int)((int64_t)y * b->src_h / b->dst_h);
        const uint8_t *s = b->src + (size_t)sy * b->src_stride;
        uint8_t *d = b->dst + (size_t)y * b->dst_stride;
        if (same && b->dst_format == 0) {
            memcpy(d, s, (size_t)b->dst_w * 4);
        } else if (same) {
            int x = 0;
#if defined(DXR_NEON)
            for (; x + 16 <= b->dst_w; x += 16) {
                uint8x16x4_t p = vld4q_u8(s + (size_t)x * 4);
                uint8x16_t r = p.val[0];
                p.val[0] = p.val[2];
                p.val[2] = r;
                vst4q_u8(d + (size_t)x * 4, p);
            }
#endif
            for (; x < b->dst_w; x++) {
                const uint8_t *sp = s + (size_t)x * 4;
                uint8_t *dp = d + (size_t)x * 4;
                dp[0] = sp[2]; dp[1] = sp[1]; dp[2] = sp[0]; dp[3] = sp[3];
            }
        } else {
            uint32_t step = (uint32_t)(((uint64_t)b->src_w << 16) / (uint32_t)b->dst_w), fx = 0;
            for (int x = 0; x < b->dst_w; x++, fx += step) {
                const uint8_t *sp = s + (size_t)(fx >> 16) * 4;
                uint8_t *dp = d + (size_t)x * 4;
                if (b->dst_format == 0) { dp[0] = sp[0]; dp[1] = sp[1]; dp[2] = sp[2]; dp[3] = sp[3]; }
                else { dp[0] = sp[2]; dp[1] = sp[1]; dp[2] = sp[0]; dp[3] = sp[3]; }
            }
        }
    }
}

void dxr_present_blit(dxr_device *dev, const uint8_t *src, int src_w, int src_h, int src_stride,
                      uint8_t *dst, int dst_w, int dst_h, int dst_stride, int dst_format) {
    if (!src || !dst || src_w <= 0 || src_h <= 0 || dst_w <= 0 || dst_h <= 0) return;
    uint64_t t0 = dxr_now_ns();
    blit_ctx b = {src, src_w, src_h, src_stride, dst, dst_w, dst_h, dst_stride, dst_format, 32};
    int jobs = (dst_h + b.rows_per_job - 1) / b.rows_per_job;
    if (dev) { dxr_pool_run(dev->pool, blit_job, &b, jobs, 1); dev->stats.ns_present += dxr_now_ns() - t0; }
    else for (int i = 0; i < jobs; i++) blit_job(&b, i, 0);
}

int dxr_present_android(dxr_device *dev, void *native_window, const uint8_t *src, int src_w, int src_h, int src_stride) {
#if defined(__ANDROID__)
    /* CPU buffer path of the Android compositor: lock a buffer of the Surface, write the
     * pixels, post it. No EGL context, no GLES and no Vulkan swapchain is involved. */
    ANativeWindow *win = (ANativeWindow *)native_window;
    if (!win || !src) return -1;
    if (ANativeWindow_getWidth(win) != src_w || ANativeWindow_getHeight(win) != src_h || ANativeWindow_getFormat(win) != WINDOW_FORMAT_RGBX_8888) {
        /* The compositor scales the buffer to the surface size, so a reduced render scale costs no CPU time here. */
        if (ANativeWindow_setBuffersGeometry(win, src_w, src_h, WINDOW_FORMAT_RGBX_8888) != 0) return -2;
    }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(win, &buf, NULL) != 0) return -3;
    if (buf.bits && buf.width > 0 && buf.height > 0)
        dxr_present_blit(dev, src, src_w, src_h, src_stride, (uint8_t *)buf.bits, DXR_MIN(buf.width, src_w), DXR_MIN(buf.height, src_h), buf.stride * 4, 0);
    return ANativeWindow_unlockAndPost(win) == 0 ? 0 : -4;
#else
    (void)dev; (void)native_window; (void)src; (void)src_w; (void)src_h; (void)src_stride;
    return -100;
#endif
}

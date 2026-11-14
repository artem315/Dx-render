/*
 * DX Render - independent software graphics core.
 * ARTYONIX STUDIOS.
 *
 * Public C API of the native core. Nothing in this library calls OpenGL,
 * OpenGL ES, Vulkan, Direct3D or Metal: every pixel is produced by the CPU
 * rasterizer implemented in native/src.
 */
#ifndef DXR_H
#define DXR_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define DXR_VERSION_STRING "1.0.0-ALPHA.1"

#define DXR_MAX_THREADS 16
#define DXR_MAX_MIPS 16
#define DXR_MAX_COLOR_TARGETS 8
#define DXR_MAX_UNIFORMS 24
#define DXR_MAX_VERTEX_BUFFERS 8
#define DXR_MAX_ATTRIBS 16
#define DXR_MAX_VARYING_CELLS 64

/* Component encodings of texel / vertex attribute data. */
enum {
    DXR_CT_UNORM8 = 0, DXR_CT_SNORM8, DXR_CT_UNORM16, DXR_CT_SNORM16,
    DXR_CT_UINT8, DXR_CT_SINT8, DXR_CT_UINT16, DXR_CT_SINT16,
    DXR_CT_UINT32, DXR_CT_SINT32, DXR_CT_FLOAT16, DXR_CT_FLOAT32,
    DXR_CT_DEPTH32F, DXR_CT_COUNT
};

enum { DXR_UNIFORM_NONE = 0, DXR_UNIFORM_BUFFER = 1, DXR_UNIFORM_SAMPLER = 2, DXR_UNIFORM_TEXEL_BUFFER = 3 };

enum { DXR_TOPO_TRIANGLES = 0, DXR_TOPO_TRIANGLE_STRIP, DXR_TOPO_TRIANGLE_FAN, DXR_TOPO_LINES, DXR_TOPO_LINE_STRIP, DXR_TOPO_POINTS };

enum { DXR_CMP_ALWAYS = 0, DXR_CMP_LESS, DXR_CMP_LEQUAL, DXR_CMP_EQUAL, DXR_CMP_NOTEQUAL, DXR_CMP_GEQUAL, DXR_CMP_GREATER, DXR_CMP_NEVER };

enum {
    DXR_BF_CONSTANT_ALPHA = 0, DXR_BF_CONSTANT_COLOR, DXR_BF_DST_ALPHA, DXR_BF_DST_COLOR, DXR_BF_ONE,
    DXR_BF_ONE_MINUS_CONSTANT_ALPHA, DXR_BF_ONE_MINUS_CONSTANT_COLOR, DXR_BF_ONE_MINUS_DST_ALPHA,
    DXR_BF_ONE_MINUS_DST_COLOR, DXR_BF_ONE_MINUS_SRC_ALPHA, DXR_BF_ONE_MINUS_SRC_COLOR, DXR_BF_SRC_ALPHA,
    DXR_BF_SRC_ALPHA_SATURATE, DXR_BF_SRC_COLOR, DXR_BF_ZERO
};
enum { DXR_BOP_ADD = 0, DXR_BOP_SUBTRACT, DXR_BOP_REVERSE_SUBTRACT, DXR_BOP_MIN, DXR_BOP_MAX };

typedef struct dxr_device dxr_device;
typedef struct dxr_texture dxr_texture;
typedef struct dxr_pipeline dxr_pipeline;

/* ---- configuration ------------------------------------------------------ */
typedef struct dxr_config {
    int32_t worker_threads;     /* total threads used for rendering, including the caller (1..DXR_MAX_THREADS) */
    int32_t tile_size;          /* 16, 32, 64 or 128 pixels */
    int32_t occlusion_culling;  /* hierarchical-Z triangle rejection per tile */
    int32_t fast_shaders;       /* allow verified native shader kernels */
    int32_t simd;               /* allow SIMD paths (NEON / SSE2) */
    int32_t adaptive;           /* cost sorted dynamic tile scheduling */
} dxr_config;

/* ---- statistics (Profiler) ---------------------------------------------- */
typedef struct dxr_stats {
    uint64_t frames;
    uint64_t draw_calls;
    uint64_t vertices_shaded;
    uint64_t triangles_in;
    uint64_t triangles_clipped;
    uint64_t triangles_culled;      /* back face / zero area / outside */
    uint64_t triangles_binned;
    uint64_t tile_tri_refs;
    uint64_t tris_hiz_rejected;
    uint64_t fragments_shaded;
    uint64_t fragments_fast;
    uint64_t fragments_discarded;
    uint64_t fragments_depth_failed;
    uint64_t tiles_rendered;
    uint64_t passes;
    uint64_t ns_geometry;
    uint64_t ns_raster;
    uint64_t ns_clear;
    uint64_t ns_present;
    uint64_t arena_bytes_peak;
    uint64_t texture_bytes;
    uint64_t interp_ops;
} dxr_stats;

/* ---- uniform binding ----------------------------------------------------- */
typedef struct dxr_uniform_binding {
    uint8_t kind;               /* DXR_UNIFORM_* */
    uint8_t min_linear, mag_linear, wrap_u, wrap_v; /* wrap: 0 repeat, 1 clamp */
    uint8_t base_mip, mip_count;
    uint8_t ctype, ncomp;       /* texel buffer format */
    float max_lod;
    const uint8_t *data;        /* buffer memory */
    uint32_t size;
    const dxr_texture *tex;
} dxr_uniform_binding;

/* ---- draw description ----------------------------------------------------- */
typedef struct dxr_draw {
    const dxr_pipeline *pipeline;
    const uint8_t *vb[DXR_MAX_VERTEX_BUFFERS];
    uint64_t vb_size[DXR_MAX_VERTEX_BUFFERS];
    const uint8_t *index_data;  /* NULL for non indexed draws */
    uint64_t index_size;        /* bytes available behind index_data */
    int32_t index_bytes;        /* 2 or 4 */
    dxr_uniform_binding uniforms[DXR_MAX_UNIFORMS];
    const uint8_t *push_constants;
    uint32_t push_constants_size;
    int32_t scissor_enabled;
    int32_t scissor[4];         /* x, y, w, h in framebuffer rows (row 0 = bottom, as in GL window space) */
    int32_t first;              /* first index / first vertex */
    int32_t count;
    int32_t base_vertex;
    int32_t instance_count;
    int32_t first_instance;
} dxr_draw;

typedef struct dxr_pass_desc {
    dxr_texture *color[DXR_MAX_COLOR_TARGETS];
    int32_t color_mip[DXR_MAX_COLOR_TARGETS];
    int32_t num_color;
    dxr_texture *depth;
    int32_t depth_mip;
    int32_t area[4];            /* x, y, w, h */
    int32_t clear_color_mask;   /* bit per target */
    float clear_color[DXR_MAX_COLOR_TARGETS][4];
    int32_t clear_depth_enabled;
    float clear_depth;
} dxr_pass_desc;

/* ---- device -------------------------------------------------------------- */
dxr_device *dxr_device_create(const dxr_config *cfg);
void dxr_device_destroy(dxr_device *dev);
void dxr_device_configure(dxr_device *dev, const dxr_config *cfg);
void dxr_device_get_config(const dxr_device *dev, dxr_config *out);
void dxr_device_get_stats(dxr_device *dev, dxr_stats *out, int reset);
void dxr_device_end_frame(dxr_device *dev);
const char *dxr_simd_name(void);
int dxr_cpu_count(void);
const char *dxr_last_error(void);

/* ---- textures ------------------------------------------------------------ */
dxr_texture *dxr_texture_create(dxr_device *dev, int ctype, int ncomp, int width, int height, int layers, int mips, int cube);
void dxr_texture_destroy(dxr_device *dev, dxr_texture *tex);
uint8_t *dxr_texture_level(const dxr_texture *tex, int layer, int mip, int *out_w, int *out_h, int *out_texel_bytes);
void dxr_texture_write(dxr_texture *tex, int layer, int mip, int x, int y, int w, int h, const uint8_t *src, int src_row_texels);
void dxr_texture_read(const dxr_texture *tex, int layer, int mip, int x, int y, int w, int h, uint8_t *dst);
void dxr_texture_copy(const dxr_texture *src, dxr_texture *dst, int mip, int dx, int dy, int sx, int sy, int w, int h);
void dxr_texture_clear_color(dxr_texture *tex, int mip, const float rgba[4], int x, int y, int w, int h);
void dxr_texture_clear_depth(dxr_texture *tex, int mip, float depth, int x, int y, int w, int h);

/* ---- pipelines ----------------------------------------------------------- */
/* blob: little endian stream written by the Java side, see dxr_pipeline.c */
dxr_pipeline *dxr_pipeline_create(dxr_device *dev, const uint8_t *blob, size_t len);
void dxr_pipeline_destroy(dxr_device *dev, dxr_pipeline *p);
/* 0 = interpreter only, 1 = verified fast kernel active */
int dxr_pipeline_fast_path(const dxr_pipeline *p);
const char *dxr_pipeline_info(const dxr_pipeline *p);

/* ---- render passes -------------------------------------------------------- */
int dxr_pass_begin(dxr_device *dev, const dxr_pass_desc *desc);
int dxr_pass_draw(dxr_device *dev, const dxr_draw *draw);
void dxr_pass_end(dxr_device *dev);

/* ---- presentation helpers -------------------------------------------------- */
/* Copies an RGBA8 image stored bottom-up (GL row order) into a top-down destination,
 * optionally scaling (nearest) and swizzling. dst_format: 0 = RGBA/RGBX, 1 = BGRA/BGRX (x8r8g8b8 little endian). */
void dxr_present_blit(dxr_device *dev, const uint8_t *src, int src_w, int src_h, int src_stride,
                      uint8_t *dst, int dst_w, int dst_h, int dst_stride, int dst_format);
/* Android only: posts the image to an ANativeWindow through ANativeWindow_lock (CPU buffer path, no EGL). */
int dxr_present_android(dxr_device *dev, void *native_window, const uint8_t *src, int src_w, int src_h, int src_stride);

#ifdef __cplusplus
}
#endif
#endif

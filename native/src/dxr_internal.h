/* DX Render - internal declarations shared by the native modules. */
#ifndef DXR_INTERNAL_H
#define DXR_INTERNAL_H

#include "dxr.h"

#include <math.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#if defined(__aarch64__) || defined(__ARM_NEON)
#  include <arm_neon.h>
#  define DXR_NEON 1
#elif defined(__SSE2__)
#  include <emmintrin.h>
#  define DXR_SSE2 1
#endif

#define DXR_LIKELY(x) __builtin_expect(!!(x), 1)
#define DXR_UNLIKELY(x) __builtin_expect(!!(x), 0)
#define DXR_INLINE static inline __attribute__((always_inline))
#define DXR_MIN(a, b) ((a) < (b) ? (a) : (b))
#define DXR_MAX(a, b) ((a) > (b) ? (a) : (b))
#define DXR_CLAMP(v, lo, hi) ((v) < (lo) ? (lo) : ((v) > (hi) ? (hi) : (v)))

void dxr_set_error(const char *fmt, ...);
uint64_t dxr_now_ns(void);

/* ======================= Memory Manager ================================== */
void *dxr_alloc(size_t size);            /* 64 byte aligned, zero filled */
void *dxr_alloc_raw(size_t size);        /* 64 byte aligned */
void dxr_free(void *p);

typedef struct dxr_arena_block {
    struct dxr_arena_block *next;
    size_t cap, used;
    uint8_t data[];
} dxr_arena_block;

/* Bump allocator. Blocks are kept between frames (pooling): reset only rewinds. */
typedef struct dxr_arena {
    dxr_arena_block *head;     /* first block */
    dxr_arena_block *cur;      /* block currently allocated from */
    size_t total;              /* bytes handed out since last reset */
    size_t peak;
    size_t block_size;
} dxr_arena;

void dxr_arena_init(dxr_arena *a, size_t block_size);
void dxr_arena_destroy(dxr_arena *a);
void dxr_arena_reset(dxr_arena *a);
void *dxr_arena_alloc(dxr_arena *a, size_t size);   /* 16 byte aligned, not zeroed */

/* ======================= Frame Scheduler ================================== */
typedef void (*dxr_job_fn)(void *ctx, int index, int thread);

typedef struct dxr_pool dxr_pool;
dxr_pool *dxr_pool_create(int threads);
void dxr_pool_destroy(dxr_pool *p);
int dxr_pool_threads(const dxr_pool *p);
/* Runs fn(ctx, i, thread) for i in [0, count) on all threads (caller included, thread 0).
 * Items are claimed dynamically one "grain" at a time, so faster cores take more work. */
void dxr_pool_run(dxr_pool *p, dxr_job_fn fn, void *ctx, int count, int grain);

/* ======================= Texture System =================================== */
struct dxr_texture {
    uint8_t ctype, ncomp, is_depth, is_cube;
    uint32_t texel_bytes;
    int width, height, layers, mips;
    uint8_t *data;
    size_t data_size;
    uint8_t *level[6][DXR_MAX_MIPS];
};

typedef union dxr_cell { float f; int32_t i; uint32_t u; } dxr_cell;

void dxr_texel_load(const uint8_t *p, int ctype, int ncomp, dxr_cell out[4]);
void dxr_texel_store(uint8_t *p, int ctype, int ncomp, const float in[4]);
void dxr_texel_store_cells(uint8_t *p, int ctype, int ncomp, const dxr_cell in[4]);
int dxr_ctype_bytes(int ctype);
int dxr_ctype_is_int(int ctype);

/* Samples with explicit level of detail (trilinear when the sampler allows mips). */
void dxr_sample_lod(const dxr_uniform_binding *b, float u, float v, float lod, dxr_cell out[4]);
void dxr_sample_cube_lod(const dxr_uniform_binding *b, float x, float y, float z, float lod, dxr_cell out[4]);
void dxr_fetch(const dxr_uniform_binding *b, int x, int y, int lod, dxr_cell out[4]);
float dxr_lod_from_grad(const dxr_uniform_binding *b, float dudx, float dvdx, float dudy, float dvdy);

/* ======================= Shader System ==================================== */
typedef struct dxr_shader dxr_shader;
typedef struct dxr_vm dxr_vm;      /* per thread machine state for one shader */

typedef struct dxr_shader_io {
    uint16_t location;
    uint16_t cells;        /* flattened 32 bit cells */
    uint32_t cell;         /* offset of the variable storage inside the machine state */
    uint8_t flat;
    uint8_t is_int;
    uint8_t vec;           /* components of the base vector type (for attribute defaults) */
} dxr_shader_io;

typedef struct dxr_frag_ctx {
    const dxr_uniform_binding *uniforms;
    const uint8_t *push_constants;
    const float *varyings;         /* interpolated inputs, slot indexed */
    const float *dvdx, *dvdy;      /* screen space derivatives per slot */
    float frag_coord[4];
    int front_facing;
    int vertex_index, instance_index;
} dxr_frag_ctx;

dxr_shader *dxr_shader_create(const uint32_t *words, size_t nwords, int fragment,
                              const char *const *uniform_names, int num_uniforms);
void dxr_shader_destroy(dxr_shader *s);
int dxr_shader_num_inputs(const dxr_shader *s);
int dxr_shader_num_outputs(const dxr_shader *s);
const dxr_shader_io *dxr_shader_inputs(const dxr_shader *s);
const dxr_shader_io *dxr_shader_outputs(const dxr_shader *s);
int dxr_shader_flags(const dxr_shader *s);          /* DXR_SHF_* */
uint32_t dxr_shader_position_cell(const dxr_shader *s);
uint32_t dxr_shader_fragdepth_cell(const dxr_shader *s);
uint32_t dxr_shader_num_cells(const dxr_shader *s);
uint64_t dxr_shader_hash(const dxr_shader *s);
/* Assigns the varying slot of every fragment input (used for derivative ops). */
void dxr_shader_set_input_slot(dxr_shader *s, int input_index, int slot);
void dxr_shader_finalize(dxr_shader *s);

#define DXR_SHF_DISCARD 1
#define DXR_SHF_FRAGDEPTH 2
#define DXR_SHF_DERIVATIVES 4
#define DXR_SHF_READS_FRAGCOORD 8

dxr_vm *dxr_vm_create(const dxr_shader *s);
void dxr_vm_destroy(dxr_vm *vm);
dxr_cell *dxr_vm_cells(dxr_vm *vm);
/* Returns 1 on success, 0 if the fragment was discarded. */
int dxr_vm_run(dxr_vm *vm, const dxr_frag_ctx *ctx);
uint64_t dxr_vm_ops(dxr_vm *vm);

/* ======================= Pipelines ======================================== */
typedef struct dxr_attrib {
    uint8_t slot, location, ctype, ncomp;
    uint32_t offset;
} dxr_attrib;

typedef struct dxr_blend_state {
    uint8_t present, enabled, write_mask;
    uint8_t src_c, dst_c, op_c, src_a, dst_a, op_a;
} dxr_blend_state;

typedef struct dxr_varying_map {
    uint32_t vs_cell;       /* source cells in the vertex machine */
    uint32_t fs_cell;       /* destination cells in the fragment machine */
    uint16_t slot;          /* first varying slot */
    uint16_t cells;
    uint8_t flat;
} dxr_varying_map;

struct dxr_fast_kernel;

struct dxr_pipeline {
    char name[96];
    char info[256];
    dxr_shader *vs, *fs;
    dxr_vm *vs_vm[DXR_MAX_THREADS];
    dxr_vm *fs_vm[DXR_MAX_THREADS];
    int num_attribs;
    dxr_attrib attribs[DXR_MAX_ATTRIBS];
    uint32_t attrib_cell[DXR_MAX_ATTRIBS];   /* vertex machine cell per attribute (UINT32_MAX = unused) */
    uint8_t attrib_is_int[DXR_MAX_ATTRIBS];
    uint8_t attrib_vec[DXR_MAX_ATTRIBS];
    int num_vbufs;
    struct { uint8_t slot, step_rate; uint32_t stride; } vbufs[DXR_MAX_VERTEX_BUFFERS];
    int num_uniforms;
    uint8_t uniform_kind[DXR_MAX_UNIFORMS];
    int num_varyings;
    dxr_varying_map varyings[32];
    int varying_cells;                       /* total slots */
    uint8_t slot_flat[DXR_MAX_VARYING_CELLS];
    int depth_enabled, depth_func, depth_write;
    float depth_bias_scale, depth_bias_const;
    int cull, topology, wireframe;
    int num_targets;
    dxr_blend_state targets[DXR_MAX_COLOR_TARGETS];
    uint32_t out_cell[DXR_MAX_COLOR_TARGETS]; /* fragment output cells per color target, UINT32_MAX = none */
    uint8_t out_ncomp[DXR_MAX_COLOR_TARGETS];
    uint8_t out_is_int[DXR_MAX_COLOR_TARGETS];
    int fs_flags;
    const struct dxr_fast_kernel *fast;      /* verified native kernel or NULL */
    void *fast_data;
};

/* ======================= Lighting System / fast kernels ==================== */
struct dxr_tri;
struct dxr_draw_state;

typedef struct dxr_fast_kernel {
    const char *name;
    /* Returns 1 if the pipeline matches the kernel's structural requirements. */
    int (*match)(struct dxr_pipeline *p);
    /* Shades vertices [first, first+count) of one instance. Writes clip positions (4 floats)
     * and varyings (p->varying_cells floats) per vertex. */
    void (*vertex)(const struct dxr_pipeline *p, const dxr_draw *d, int first, int count, int instance,
                   float *clip, float *varyings);
    /* Shades a span of one row: returns 0 on a per pixel basis through mask (bit per pixel, max 64). */
    int (*fragment)(const struct dxr_pipeline *p, const struct dxr_draw_state *ds, const float *varyings,
                    const float *dvdx, const float *dvdy, const float frag_coord[4], float out_rgba[4]);
} dxr_fast_kernel;

const dxr_fast_kernel *dxr_fast_find(struct dxr_pipeline *p, dxr_device *dev);

/* Minecraft lighting math implemented natively (used by fast kernels and tests). */
void dxr_light_lightmap_uv(int u, int v, float out_uv[2]);
float dxr_light_diffuse(const float l0[3], const float l1[3], const float n[3]);
float dxr_light_linear_fog(float dist, float start, float end);
float dxr_light_total_fog(float spherical, float cylindrical, float env_start, float env_end, float rd_start, float rd_end);

/* ======================= Device ============================================ */
struct dxr_pass;

struct dxr_device {
    dxr_config cfg;
    dxr_pool *pool;
    dxr_arena arena[DXR_MAX_THREADS];   /* per thread pass arenas (triangles, bins) */
    dxr_arena pass_arena;               /* draw states, uniform snapshots */
    dxr_arena vtx_arena;                /* shaded vertex cache of the draw being processed */
    struct dxr_pass *pass;
    dxr_stats stats;
    _Atomic uint64_t texture_bytes;
    /* per thread counters merged at pass end */
    struct dxr_thread_stats {
        uint64_t fragments_shaded, fragments_fast, fragments_discarded, fragments_depth_failed;
        uint64_t tris_hiz_rejected, tile_tri_refs, triangles_binned, triangles_culled, triangles_clipped;
        uint64_t vertices_shaded, triangles_in, interp_ops;
        uint8_t pad[64];
    } tstats[DXR_MAX_THREADS];
};

void dxr_pass_abort(dxr_device *dev);

/* Blend one fragment. dst/src are float RGBA. */
void dxr_blend(const dxr_blend_state *bs, const float src[4], const float dst[4], float out[4]);

#endif

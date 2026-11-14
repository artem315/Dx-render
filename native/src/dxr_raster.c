/* DX Render - Geometry Engine and tile based rasterizer.
 *
 * A render pass is processed in two phases, like a tile based deferred renderer:
 *   1. Geometry (at draw time): vertices are shaded, primitives clipped, set up as plane
 *      equations and binned into screen tiles. Large draws are split over all threads.
 *   2. Raster (at pass end): every non empty tile is rendered by exactly one thread,
 *      replaying its triangles in submission order. Tiles never share pixels, so no locks.
 *
 * Framebuffer rows are stored bottom-up (row 0 = NDC y -1), which is the convention the
 * game's renderer was written against; presentation flips the image.
 */
#include "dxr_internal.h"

#define BIN_CHUNK 62
#define SUBPIX 256
#define SUBPIX_SHIFT 8
#define GUARD_PIXELS 16384.0f
#define HIZ_BLOCK 8
#define HIZ_REFRESH 12

typedef struct dxr_draw_state {
    const dxr_pipeline *pipe;
    dxr_uniform_binding uniforms[DXR_MAX_UNIFORMS];
    const uint8_t *push_constants;
    int sx0, sy0, sx1, sy1; /* scissor, max exclusive */
} dxr_draw_state;

typedef struct dxr_tri {
    const dxr_draw_state *ds;
    int32_t bx0, by0, bx1, by1;      /* inclusive pixel bounds */
    int64_t A[3], B[3], C[3];        /* edge functions in sub pixel units, fill rule bias included */
    float x0, y0;                    /* reference position in pixels */
    float z0, dzdx, dzdy;
    float w0, dwdx, dwdy;            /* 1/w */
    float zmin, zmax;
    uint8_t front;
    float var[];                     /* a0[V], dx[V], dy[V] */
} dxr_tri;

typedef struct bin_entry { uint64_t key; dxr_tri *tri; } bin_entry;
typedef struct bin_chunk { struct bin_chunk *next; uint32_t count, pad; bin_entry e[BIN_CHUNK]; } bin_chunk;
typedef struct bin_list { bin_chunk *head, *tail; } bin_list;

struct dxr_pass {
    dxr_pass_desc desc;
    int width, height;
    int ax0, ay0, ax1, ay1;
    int num_color;
    uint8_t *color[DXR_MAX_COLOR_TARGETS];
    const dxr_texture *color_tex[DXR_MAX_COLOR_TARGETS];
    float *depth;
    int tile, tiles_x, tiles_y, ntiles;
    bin_list *bins[DXR_MAX_THREADS];
    uint64_t draw_seq;
    int nthreads;
    struct { const uint8_t *src; uint32_t size; const uint8_t *copy; } snap[DXR_MAX_UNIFORMS];
    int *order;
    uint32_t *cost;
};

/* ======================================================================================= */
/* Pass management                                                                         */
/* ======================================================================================= */

int dxr_pass_begin(dxr_device *dev, const dxr_pass_desc *desc) {
    if (dev->pass) dxr_pass_end(dev);
    uint64_t t0 = dxr_now_ns();
    struct dxr_pass *p = (struct dxr_pass *)dxr_arena_alloc(&dev->pass_arena, sizeof(struct dxr_pass));
    if (!p) return 0;
    memset(p, 0, sizeof(*p));
    p->desc = *desc;
    int w = 0, h = 0;
    p->num_color = desc->num_color > DXR_MAX_COLOR_TARGETS ? DXR_MAX_COLOR_TARGETS : desc->num_color;
    for (int i = 0; i < p->num_color; i++) {
        dxr_texture *t = desc->color[i];
        if (!t) continue;
        int lw, lh, tb;
        p->color[i] = dxr_texture_level(t, 0, desc->color_mip[i], &lw, &lh, &tb);
        p->color_tex[i] = t;
        if (!p->color[i]) { p->color_tex[i] = NULL; continue; }
        if (w == 0 || lw < w) w = lw;
        if (h == 0 || lh < h) h = lh;
    }
    if (desc->depth && desc->depth->ctype == DXR_CT_DEPTH32F) {
        int lw, lh, tb;
        p->depth = (float *)dxr_texture_level(desc->depth, 0, desc->depth_mip, &lw, &lh, &tb);
        if (p->depth) {
            if (w == 0 || lw < w) w = lw;
            if (h == 0 || lh < h) h = lh;
        }
    }
    if (w <= 0 || h <= 0) { dxr_set_error("render pass without attachments"); dxr_arena_reset(&dev->pass_arena); return 0; }
    p->width = w;
    p->height = h;
    p->ax0 = DXR_MAX(0, desc->area[0]);
    p->ay0 = DXR_MAX(0, desc->area[1]);
    p->ax1 = DXR_MIN(w, desc->area[0] + desc->area[2]);
    p->ay1 = DXR_MIN(h, desc->area[1] + desc->area[3]);
    if (desc->area[2] <= 0 || desc->area[3] <= 0) { p->ax0 = 0; p->ay0 = 0; p->ax1 = w; p->ay1 = h; }
    for (int i = 0; i < p->num_color; i++)
        if (p->color_tex[i] && (desc->clear_color_mask >> i & 1))
            dxr_texture_clear_color(desc->color[i], desc->color_mip[i], desc->clear_color[i], p->ax0, p->ay0, p->ax1 - p->ax0, p->ay1 - p->ay0);
    if (p->depth && desc->clear_depth_enabled)
        dxr_texture_clear_depth(desc->depth, desc->depth_mip, desc->clear_depth, p->ax0, p->ay0, p->ax1 - p->ax0, p->ay1 - p->ay0);
    int tile = dev->cfg.tile_size;
    if (tile != 16 && tile != 32 && tile != 64 && tile != 128) tile = 32;
    p->tile = tile;
    p->tiles_x = (w + tile - 1) / tile;
    p->tiles_y = (h + tile - 1) / tile;
    p->ntiles = p->tiles_x * p->tiles_y;
    p->nthreads = dxr_pool_threads(dev->pool);
    for (int t = 0; t < p->nthreads; t++) {
        p->bins[t] = (bin_list *)dxr_arena_alloc(&dev->pass_arena, sizeof(bin_list) * (size_t)p->ntiles);
        if (!p->bins[t]) { dxr_arena_reset(&dev->pass_arena); return 0; }
        memset(p->bins[t], 0, sizeof(bin_list) * (size_t)p->ntiles);
    }
    dev->pass = p;
    dev->stats.passes++;
    dev->stats.ns_clear += dxr_now_ns() - t0;
    return 1;
}

static void reset_pass_memory(dxr_device *dev) {
    size_t used = dev->pass_arena.total + dev->vtx_arena.total;
    for (int t = 0; t < DXR_MAX_THREADS; t++) used += dev->arena[t].total;
    if (used > dev->stats.arena_bytes_peak) dev->stats.arena_bytes_peak = used;
    dxr_arena_reset(&dev->pass_arena);
    dxr_arena_reset(&dev->vtx_arena);
    for (int t = 0; t < DXR_MAX_THREADS; t++) dxr_arena_reset(&dev->arena[t]);
    dev->pass = NULL;
}

void dxr_pass_abort(dxr_device *dev) {
    if (dev->pass) reset_pass_memory(dev);
}

/* ======================================================================================= */
/* Geometry                                                                                */
/* ======================================================================================= */

typedef struct geom_ctx {
    dxr_device *dev;
    struct dxr_pass *pass;
    const dxr_draw *draw;
    const dxr_pipeline *pipe;
    dxr_draw_state *ds;
    int instance;
    int vmin, nverts;
    float *clip, *vary;
    int V;
    int nprims;
    uint64_t key_base;
    float half_w, half_h, guard_x, guard_y;
    uint32_t vb_stride[DXR_MAX_VERTEX_BUFFERS];
    uint8_t vb_step[DXR_MAX_VERTEX_BUFFERS];
    int vchunk, pchunk;
    int use_fast_vs;
    dxr_kdata vs_data;
} geom_ctx;

static dxr_vm *get_vm(const dxr_pipeline *pipe, int thread, int fragment) {
    dxr_pipeline *p = (dxr_pipeline *)pipe; /* per thread slot: no other thread touches it */
    dxr_vm **slot = fragment ? &p->fs_vm[thread] : &p->vs_vm[thread];
    if (DXR_UNLIKELY(!*slot)) *slot = dxr_vm_create(fragment ? p->fs : p->vs);
    return *slot;
}

static void fetch_attribs(const geom_ctx *g, dxr_cell *R, int vertex, int instance) {
    const dxr_pipeline *p = g->pipe;
    const dxr_draw *d = g->draw;
    for (int i = 0; i < p->num_attribs; i++) {
        uint32_t cell = p->attrib_cell[i];
        if (cell == UINT32_MAX) continue;
        const dxr_attrib *a = &p->attribs[i];
        int64_t idx = g->vb_step[a->slot] ? (int64_t)instance : (int64_t)vertex;
        uint64_t off = (uint64_t)idx * g->vb_stride[a->slot] + a->offset;
        uint32_t need = (uint32_t)dxr_ctype_bytes(a->ctype) * a->ncomp;
        dxr_cell tmp[4];
        int src_int = dxr_ctype_is_int(a->ctype);
        if (DXR_UNLIKELY(!d->vb[a->slot] || idx < 0 || off + need > d->vb_size[a->slot])) {
            tmp[0].u = tmp[1].u = tmp[2].u = 0;
            if (src_int) tmp[3].i = 1; else tmp[3].f = 1.0f;
        } else if (a->ctype == DXR_CT_FLOAT32) {
            tmp[0].f = tmp[1].f = tmp[2].f = 0.0f; tmp[3].f = 1.0f;
            memcpy(tmp, d->vb[a->slot] + off, need);
        } else {
            dxr_texel_load(d->vb[a->slot] + off, a->ctype, a->ncomp, tmp);
        }
        if (p->attrib_is_int[i] != src_int) {
            if (src_int) for (int k = 0; k < 4; k++) tmp[k].f = (float)tmp[k].i;
            else for (int k = 0; k < 4; k++) tmp[k].i = (int32_t)tmp[k].f;
        }
        memcpy(&R[cell], tmp, (size_t)p->attrib_vec[i] * 4);
    }
}

static void shade_job_fast(geom_ctx *g, int chunk, int thread) {
    const dxr_pipeline *p = g->pipe;
    const dxr_draw *d = g->draw;
    dxr_cell attr[DXR_MAX_ATTRIBS][4];
    memset(attr, 0, sizeof(attr));
    int start = chunk * g->vchunk, end = DXR_MIN(g->nverts, start + g->vchunk);
    const int instance = g->instance + d->first_instance;
    for (int i = start; i < end; i++) {
        int vertex = g->vmin + i;
        for (int k = 0; k < p->num_attribs; k++) {
            const dxr_attrib *a = &p->attribs[k];
            if (p->attrib_cell[k] == UINT32_MAX || a->location >= DXR_MAX_ATTRIBS) continue;
            int64_t idx = g->vb_step[a->slot] ? (int64_t)instance : (int64_t)vertex;
            uint64_t off = (uint64_t)idx * g->vb_stride[a->slot] + a->offset;
            uint32_t need = (uint32_t)dxr_ctype_bytes(a->ctype) * a->ncomp;
            dxr_cell *tmp = attr[a->location];
            int src_int = dxr_ctype_is_int(a->ctype);
            if (DXR_UNLIKELY(!d->vb[a->slot] || idx < 0 || off + need > d->vb_size[a->slot])) {
                tmp[0].u = tmp[1].u = tmp[2].u = 0;
                if (src_int) tmp[3].i = 1; else tmp[3].f = 1.0f;
            } else if (a->ctype == DXR_CT_FLOAT32) {
                tmp[0].f = tmp[1].f = tmp[2].f = 0.0f; tmp[3].f = 1.0f;
                memcpy(tmp, d->vb[a->slot] + off, need);
            } else {
                dxr_texel_load(d->vb[a->slot] + off, a->ctype, a->ncomp, tmp);
            }
            if (p->attrib_is_int[k] != src_int) {
                if (src_int) for (int c = 0; c < 4; c++) tmp[c].f = (float)tmp[c].i;
                else for (int c = 0; c < 4; c++) tmp[c].i = (int32_t)tmp[c].f;
            }
        }
        p->fast_vs->vertex(&g->vs_data, d->uniforms, (const dxr_cell (*)[4])attr, &g->clip[(size_t)i * 4], &g->vary[(size_t)i * g->V]);
    }
    g->dev->tstats[thread].vertices_shaded += (uint64_t)(end - start);
}

static void shade_job(void *vctx, int chunk, int thread) {
    geom_ctx *g = (geom_ctx *)vctx;
    const dxr_pipeline *p = g->pipe;
    if (g->use_fast_vs) { shade_job_fast(g, chunk, thread); return; }
    dxr_vm *vm = get_vm(p, thread, 0);
    if (!vm) return;
    dxr_cell *R = dxr_vm_cells(vm);
    dxr_frag_ctx ctx;
    memset(&ctx, 0, sizeof(ctx));
    ctx.uniforms = g->draw->uniforms;
    ctx.push_constants = g->draw->push_constants;
    ctx.instance_index = g->instance;
    uint32_t pos_cell = dxr_shader_position_cell(p->vs);
    int start = chunk * g->vchunk, end = DXR_MIN(g->nverts, start + g->vchunk);
    for (int i = start; i < end; i++) {
        int vertex = g->vmin + i;
        fetch_attribs(g, R, vertex, g->instance + g->draw->first_instance);
        ctx.vertex_index = vertex;
        dxr_vm_run(vm, &ctx);
        memcpy(&g->clip[(size_t)i * 4], &R[pos_cell], 16);
        float *vo = &g->vary[(size_t)i * g->V];
        for (int m = 0; m < p->num_varyings; m++) {
            const dxr_varying_map *vm_ = &p->varyings[m];
            if (vm_->vs_cell != UINT32_MAX) memcpy(&vo[vm_->slot], &R[vm_->vs_cell], (size_t)vm_->cells * 4);
            else memset(&vo[vm_->slot], 0, (size_t)vm_->cells * 4);
        }
    }
    g->dev->tstats[thread].vertices_shaded += (uint64_t)(end - start);
}

DXR_INLINE int64_t floor_div(int64_t a, int64_t b) { /* b > 0 */
    int64_t q = a / b;
    return (a % b < 0) ? q - 1 : q;
}

static void bin_tri(geom_ctx *g, int thread, dxr_tri *tri, uint64_t key) {
    struct dxr_pass *pass = g->pass;
    const int T = pass->tile;
    int tx0 = tri->bx0 / T, tx1 = tri->bx1 / T, ty0 = tri->by0 / T, ty1 = tri->by1 / T;
    int multi = tx1 > tx0 || ty1 > ty0;
    dxr_arena *arena = &g->dev->arena[thread];
    bin_list *bins = pass->bins[thread];
    uint64_t refs = 0;
    for (int ty = ty0; ty <= ty1; ty++) {
        for (int tx = tx0; tx <= tx1; tx++) {
            if (multi) {
                /* reject tiles entirely outside one edge: test the corner most inside for that edge */
                int64_t xl = (int64_t)(tx * T) * SUBPIX + SUBPIX / 2, xr = (int64_t)(tx * T + T - 1) * SUBPIX + SUBPIX / 2;
                int64_t yb = (int64_t)(ty * T) * SUBPIX + SUBPIX / 2, yt = (int64_t)(ty * T + T - 1) * SUBPIX + SUBPIX / 2;
                int out = 0;
                for (int e = 0; e < 3; e++) {
                    int64_t cx = tri->A[e] > 0 ? xr : xl, cy = tri->B[e] > 0 ? yt : yb;
                    if (tri->A[e] * cx + tri->B[e] * cy + tri->C[e] < 0) { out = 1; break; }
                }
                if (out) continue;
            }
            bin_list *bl = &bins[ty * pass->tiles_x + tx];
            bin_chunk *c = bl->tail;
            if (!c || c->count == BIN_CHUNK) {
                bin_chunk *nc = (bin_chunk *)dxr_arena_alloc(arena, sizeof(bin_chunk));
                if (!nc) return;
                nc->next = NULL;
                nc->count = 0;
                if (c) c->next = nc; else bl->head = nc;
                bl->tail = nc;
                c = nc;
            }
            c->e[c->count].key = key;
            c->e[c->count].tri = tri;
            c->count++;
            refs++;
        }
    }
    g->dev->tstats[thread].tile_tri_refs += refs;
    g->dev->tstats[thread].triangles_binned++;
}

/* Sets up one triangle given in clip space (already inside the clip volume / guard band). */
static void setup_tri(geom_ctx *g, int thread, const float *p0, const float *p1, const float *p2,
                      const float *v0, const float *v1, const float *v2, const float *vflat, uint64_t key, int no_cull) {
    struct dxr_pass *pass = g->pass;
    const dxr_pipeline *pipe = g->pipe;
    float iw[3] = {1.0f / p0[3], 1.0f / p1[3], 1.0f / p2[3]};
    const float *P[3] = {p0, p1, p2};
    const float *VV[3] = {v0, v1, v2};
    int64_t X[3], Y[3];
    float z[3];
    for (int i = 0; i < 3; i++) {
        float sx = (P[i][0] * iw[i] * 0.5f + 0.5f) * (float)pass->width;
        float sy = (P[i][1] * iw[i] * 0.5f + 0.5f) * (float)pass->height;
        if (!(sx > -40000.0f && sx < 40000.0f && sy > -40000.0f && sy < 40000.0f)) { g->dev->tstats[thread].triangles_culled++; return; }
        X[i] = (int64_t)lrintf(sx * (float)SUBPIX);
        Y[i] = (int64_t)lrintf(sy * (float)SUBPIX);
        z[i] = P[i][2] * iw[i];
    }
    int64_t area2 = (X[1] - X[0]) * (Y[2] - Y[0]) - (X[2] - X[0]) * (Y[1] - Y[0]);
    if (area2 == 0) { g->dev->tstats[thread].triangles_culled++; return; }
    int front = area2 > 0;
    if (pipe->cull && !front && !no_cull) { g->dev->tstats[thread].triangles_culled++; return; }
    int i1 = 1, i2 = 2;
    if (!front) { i1 = 2; i2 = 1; area2 = -area2; }
    const int idx[3] = {0, i1, i2};
    int64_t minx = DXR_MIN(X[0], DXR_MIN(X[1], X[2])), maxx = DXR_MAX(X[0], DXR_MAX(X[1], X[2]));
    int64_t miny = DXR_MIN(Y[0], DXR_MIN(Y[1], Y[2])), maxy = DXR_MAX(Y[0], DXR_MAX(Y[1], Y[2]));
    const dxr_draw_state *ds = g->ds;
    int bx0 = (int)((minx - SUBPIX / 2 + SUBPIX - 1) >> SUBPIX_SHIFT), bx1 = (int)((maxx - SUBPIX / 2) >> SUBPIX_SHIFT);
    int by0 = (int)((miny - SUBPIX / 2 + SUBPIX - 1) >> SUBPIX_SHIFT), by1 = (int)((maxy - SUBPIX / 2) >> SUBPIX_SHIFT);
    if (bx0 < ds->sx0) bx0 = ds->sx0;
    if (by0 < ds->sy0) by0 = ds->sy0;
    if (bx1 > ds->sx1 - 1) bx1 = ds->sx1 - 1;
    if (by1 > ds->sy1 - 1) by1 = ds->sy1 - 1;
    if (bx0 > bx1 || by0 > by1) { g->dev->tstats[thread].triangles_culled++; return; }
    const int V = g->V;
    dxr_tri *t = (dxr_tri *)dxr_arena_alloc(&g->dev->arena[thread], sizeof(dxr_tri) + sizeof(float) * 3 * (size_t)V);
    if (!t) return;
    t->ds = ds;
    t->bx0 = bx0; t->by0 = by0; t->bx1 = bx1; t->by1 = by1;
    t->front = (uint8_t)front;
    for (int e = 0; e < 3; e++) {
        int a = idx[e], b = idx[(e + 1) % 3];
        int64_t A = -(Y[b] - Y[a]), B = X[b] - X[a];
        int64_t C = -(A * X[a] + B * Y[a]);
        if (!(A > 0 || (A == 0 && B > 0))) C -= 1;
        t->A[e] = A; t->B[e] = B; t->C[e] = C;
    }
    float fx[3], fy[3];
    for (int i = 0; i < 3; i++) { fx[i] = (float)X[idx[i]] * (1.0f / SUBPIX); fy[i] = (float)Y[idx[i]] * (1.0f / SUBPIX); }
    float e1x = fx[1] - fx[0], e1y = fy[1] - fy[0], e2x = fx[2] - fx[0], e2y = fy[2] - fy[0];
    float inv_area = 1.0f / (e1x * e2y - e2x * e1y);
    t->x0 = fx[0]; t->y0 = fy[0];
#define PLANE(q0, q1, q2, OUT0, OUTX, OUTY) { float d1 = (q1) - (q0), d2 = (q2) - (q0); OUT0 = (q0); OUTX = (d1 * e2y - d2 * e1y) * inv_area; OUTY = (d2 * e1x - d1 * e2x) * inv_area; }
    float za = z[0], zb = z[i1], zc = z[i2];
    PLANE(za, zb, zc, t->z0, t->dzdx, t->dzdy);
    float wa = iw[0], wb = iw[i1], wc = iw[i2];
    PLANE(wa, wb, wc, t->w0, t->dwdx, t->dwdy);
    float zmin = DXR_MIN(za, DXR_MIN(zb, zc)), zmax = DXR_MAX(za, DXR_MAX(zb, zc));
    if (pipe->depth_bias_scale != 0.0f || pipe->depth_bias_const != 0.0f) {
        float slope = DXR_MAX(fabsf(t->dzdx), fabsf(t->dzdy));
        int ex;
        frexpf(DXR_MAX(fabsf(zmax), fabsf(zmin)), &ex);
        float r = ldexpf(1.0f, ex - 24);
        float bias = pipe->depth_bias_scale * slope + pipe->depth_bias_const * r;
        t->z0 += bias; zmin += bias; zmax += bias;
    }
    t->zmin = zmin; t->zmax = zmax;
    float *a0 = t->var, *dx = t->var + V, *dy = t->var + 2 * V;
    const float *va = VV[0], *vb = VV[i1], *vc = VV[i2];
    for (int s = 0; s < V; s++) {
        if (pipe->slot_flat[s]) {
            a0[s] = vflat[s]; dx[s] = 0.0f; dy[s] = 0.0f;
        } else {
            float q0 = va[s] * wa, q1 = vb[s] * wb, q2 = vc[s] * wc;
            PLANE(q0, q1, q2, a0[s], dx[s], dy[s]);
        }
    }
#undef PLANE
    bin_tri(g, thread, t, key);
}

typedef struct cvert { float p[4]; float v[DXR_MAX_VARYING_CELLS]; } cvert;

/* Signed distance of a clip space vertex to plane k (>= 0 is inside). */
DXR_INLINE float plane_dist(const geom_ctx *g, const float *p, int k) {
    switch (k) {
    case 0: return p[3] - 1.0e-6f;
    case 1: return p[2];
    case 2: return p[3] - p[2];
    case 3: return p[3] * g->guard_x + p[0];
    case 4: return p[3] * g->guard_x - p[0];
    case 5: return p[3] * g->guard_y + p[1];
    default: return p[3] * g->guard_y - p[1];
    }
}

DXR_INLINE int outcode(const geom_ctx *g, const float *p) {
    int oc = 0;
    for (int k = 0; k < 7; k++) if (!(plane_dist(g, p, k) >= 0.0f)) oc |= 1 << k;
    return oc;
}

/* View frustum outcode used only for trivial rejection. */
DXR_INLINE int frustum_code(const float *p) {
    int oc = 0;
    if (p[0] < -p[3]) oc |= 1;
    if (p[0] > p[3]) oc |= 2;
    if (p[1] < -p[3]) oc |= 4;
    if (p[1] > p[3]) oc |= 8;
    if (p[2] < 0.0f) oc |= 16;
    if (p[2] > p[3]) oc |= 32;
    return oc;
}

static int clip_polygon(const geom_ctx *g, cvert *in, int n, cvert *tmp, int mask, int V) {
    cvert *src = in, *dst = tmp;
    for (int k = 0; k < 7 && n >= 2; k++) {
        if (!(mask >> k & 1)) continue;
        int m = 0;
        for (int i = 0; i < n; i++) {
            const cvert *a = &src[i], *b = &src[(i + 1) % n];
            float da = plane_dist(g, a->p, k), db = plane_dist(g, b->p, k);
            int ia = da >= 0.0f, ib = db >= 0.0f;
            if (ia) dst[m++] = *a;
            if (ia != ib) {
                float t = da / (da - db);
                cvert *o = &dst[m++];
                for (int c = 0; c < 4; c++) o->p[c] = a->p[c] + (b->p[c] - a->p[c]) * t;
                for (int c = 0; c < V; c++) o->v[c] = a->v[c] + (b->v[c] - a->v[c]) * t;
            }
        }
        n = m;
        cvert *sw = src; src = dst; dst = sw;
    }
    if (src != in) memcpy(in, src, sizeof(cvert) * (size_t)n);
    return n;
}

static void process_tri(geom_ctx *g, int thread, int a, int b, int c, uint64_t key) {
    if ((unsigned)a >= (unsigned)g->nverts || (unsigned)b >= (unsigned)g->nverts || (unsigned)c >= (unsigned)g->nverts) return;
    const int V = g->V;
    const float *p0 = &g->clip[(size_t)a * 4], *p1 = &g->clip[(size_t)b * 4], *p2 = &g->clip[(size_t)c * 4];
    const float *v0 = &g->vary[(size_t)a * V], *v1 = &g->vary[(size_t)b * V], *v2 = &g->vary[(size_t)c * V];
    g->dev->tstats[thread].triangles_in++;
    if (frustum_code(p0) & frustum_code(p1) & frustum_code(p2)) { g->dev->tstats[thread].triangles_culled++; return; }
    int oc0 = outcode(g, p0), oc1 = outcode(g, p1), oc2 = outcode(g, p2);
    int any = oc0 | oc1 | oc2;
    if (DXR_LIKELY(any == 0)) {
        setup_tri(g, thread, p0, p1, p2, v0, v1, v2, v2, key, 0);
        return;
    }
    if (oc0 & oc1 & oc2) { g->dev->tstats[thread].triangles_culled++; return; }
    cvert poly[12], tmp[12];
    memcpy(poly[0].p, p0, 16); memcpy(poly[0].v, v0, (size_t)V * 4);
    memcpy(poly[1].p, p1, 16); memcpy(poly[1].v, v1, (size_t)V * 4);
    memcpy(poly[2].p, p2, 16); memcpy(poly[2].v, v2, (size_t)V * 4);
    int n = clip_polygon(g, poly, 3, tmp, any, V);
    g->dev->tstats[thread].triangles_clipped++;
    for (int i = 1; i + 1 < n; i++) setup_tri(g, thread, poly[0].p, poly[i].p, poly[i + 1].p, poly[0].v, poly[i].v, poly[i + 1].v, v2, key, 0);
}

/* Lines and points are expanded to screen aligned quads of one (lines) or three (points) pixels. */
static void process_line(geom_ctx *g, int thread, int a, int b, uint64_t key, float width) {
    if ((unsigned)a >= (unsigned)g->nverts || (unsigned)b >= (unsigned)g->nverts) return;
    const int V = g->V;
    cvert seg[4], tmp[4];
    memcpy(seg[0].p, &g->clip[(size_t)a * 4], 16); memcpy(seg[0].v, &g->vary[(size_t)a * V], (size_t)V * 4);
    memcpy(seg[1].p, &g->clip[(size_t)b * 4], 16); memcpy(seg[1].v, &g->vary[(size_t)b * V], (size_t)V * 4);
    int mask = outcode(g, seg[0].p) | outcode(g, seg[1].p);
    if (mask) {
        /* clip the segment as a degenerate polygon, plane by plane */
        for (int k = 0; k < 7; k++) {
            if (!(mask >> k & 1)) continue;
            float da = plane_dist(g, seg[0].p, k), db = plane_dist(g, seg[1].p, k);
            if (da < 0.0f && db < 0.0f) return;
            if (da < 0.0f || db < 0.0f) {
                float t = da / (da - db);
                cvert *o = &tmp[0];
                for (int c = 0; c < 4; c++) o->p[c] = seg[0].p[c] + (seg[1].p[c] - seg[0].p[c]) * t;
                for (int c = 0; c < V; c++) o->v[c] = seg[0].v[c] + (seg[1].v[c] - seg[0].v[c]) * t;
                if (da < 0.0f) seg[0] = *o; else seg[1] = *o;
            }
        }
    }
    /* offset in clip space so that the quad is `width` pixels wide on screen */
    float ax = seg[0].p[0] / seg[0].p[3] * g->half_w, ay = seg[0].p[1] / seg[0].p[3] * g->half_h;
    float bx = seg[1].p[0] / seg[1].p[3] * g->half_w, by = seg[1].p[1] / seg[1].p[3] * g->half_h;
    float dx = bx - ax, dy = by - ay, len = sqrtf(dx * dx + dy * dy);
    float nx, ny;
    if (len < 1.0e-6f) { nx = 0.5f * width; ny = 0.0f; dx = 0.0f; dy = 0.5f * width; }
    else { nx = -dy / len * 0.5f * width; ny = dx / len * 0.5f * width; dx = 0.0f; dy = 0.0f; }
    cvert q[4];
    q[0] = seg[0]; q[1] = seg[0]; q[2] = seg[1]; q[3] = seg[1];
    const float sgn[4] = {1.0f, -1.0f, -1.0f, 1.0f};
    for (int i = 0; i < 4; i++) {
        float w = q[i].p[3];
        float ex = (i < 2 ? -dx : dx), ey = (i < 2 ? -dy : dy);
        q[i].p[0] += (nx * sgn[i] + ex) / g->half_w * w;
        q[i].p[1] += (ny * sgn[i] + ey) / g->half_h * w;
    }
    setup_tri(g, thread, q[0].p, q[1].p, q[2].p, q[0].v, q[1].v, q[2].v, seg[1].v, key, 1);
    setup_tri(g, thread, q[0].p, q[2].p, q[3].p, q[0].v, q[2].v, q[3].v, seg[1].v, key, 1);
}

DXR_INLINE int draw_index(const geom_ctx *g, int k) {
    const dxr_draw *d = g->draw;
    if (d->index_data) {
        uint64_t off = (uint64_t)(d->first + k) * (uint64_t)d->index_bytes;
        if (off + (uint64_t)d->index_bytes > d->index_size) return -1;
        uint32_t idx;
        if (d->index_bytes == 2) { uint16_t v; memcpy(&v, d->index_data + off, 2); idx = v; }
        else memcpy(&idx, d->index_data + off, 4);
        return (int)idx + d->base_vertex - g->vmin;
    }
    return d->first + k - g->vmin;
}

static void prim_job(void *vctx, int chunk, int thread) {
    geom_ctx *g = (geom_ctx *)vctx;
    int start = chunk * g->pchunk, end = DXR_MIN(g->nprims, start + g->pchunk);
    const int topo = g->pipe->topology;
    for (int i = start; i < end; i++) {
        uint64_t key = g->key_base | (uint64_t)(uint32_t)i;
        switch (topo) {
        case DXR_TOPO_TRIANGLES: process_tri(g, thread, draw_index(g, 3 * i), draw_index(g, 3 * i + 1), draw_index(g, 3 * i + 2), key); break;
        case DXR_TOPO_TRIANGLE_STRIP:
            if (i & 1) process_tri(g, thread, draw_index(g, i + 1), draw_index(g, i), draw_index(g, i + 2), key);
            else process_tri(g, thread, draw_index(g, i), draw_index(g, i + 1), draw_index(g, i + 2), key);
            break;
        case DXR_TOPO_TRIANGLE_FAN: process_tri(g, thread, draw_index(g, 0), draw_index(g, i + 1), draw_index(g, i + 2), key); break;
        case DXR_TOPO_LINES: process_line(g, thread, draw_index(g, 2 * i), draw_index(g, 2 * i + 1), key, 1.0f); break;
        case DXR_TOPO_LINE_STRIP: process_line(g, thread, draw_index(g, i), draw_index(g, i + 1), key, 1.0f); break;
        default: { int v = draw_index(g, i); process_line(g, thread, v, v, key, 3.0f); break; }
        }
    }
}

static const uint8_t *snapshot_uniform(dxr_device *dev, struct dxr_pass *pass, int slot, const uint8_t *src, uint32_t size) {
    if (!src || size == 0) return src;
    if (size > 65536) return src; /* large buffers are referenced; the API forbids changing them before submit */
    if (pass->snap[slot].src == src && pass->snap[slot].size == size && memcmp(pass->snap[slot].copy, src, size) == 0) return pass->snap[slot].copy;
    uint8_t *copy = (uint8_t *)dxr_arena_alloc(&dev->pass_arena, size);
    if (!copy) return src;
    memcpy(copy, src, size);
    pass->snap[slot].src = src;
    pass->snap[slot].size = size;
    pass->snap[slot].copy = copy;
    return copy;
}

int dxr_pass_draw(dxr_device *dev, const dxr_draw *draw) {
    struct dxr_pass *pass = dev->pass;
    const dxr_pipeline *pipe = draw->pipeline;
    if (!pass || !pipe || draw->count <= 0) return 0;
    uint64_t t0 = dxr_now_ns();
    dxr_draw_state *ds = (dxr_draw_state *)dxr_arena_alloc(&dev->pass_arena, sizeof(dxr_draw_state));
    if (!ds) return 0;
    ds->pipe = pipe;
    memset(ds->uniforms, 0, sizeof(ds->uniforms));
    for (int i = 0; i < pipe->num_uniforms; i++) {
        ds->uniforms[i] = draw->uniforms[i];
        if (ds->uniforms[i].kind == DXR_UNIFORM_BUFFER) ds->uniforms[i].data = snapshot_uniform(dev, pass, i, draw->uniforms[i].data, draw->uniforms[i].size);
    }
    ds->push_constants = NULL;
    if (draw->push_constants && draw->push_constants_size) {
        uint8_t *pc = (uint8_t *)dxr_arena_alloc(&dev->pass_arena, 256);
        if (pc) {
            memset(pc, 0, 256);
            memcpy(pc, draw->push_constants, DXR_MIN(draw->push_constants_size, 256u));
            ds->push_constants = pc;
        }
    }
    ds->sx0 = pass->ax0; ds->sy0 = pass->ay0; ds->sx1 = pass->ax1; ds->sy1 = pass->ay1;
    if (draw->scissor_enabled) {
        ds->sx0 = DXR_MAX(ds->sx0, draw->scissor[0]);
        ds->sy0 = DXR_MAX(ds->sy0, draw->scissor[1]);
        ds->sx1 = DXR_MIN(ds->sx1, draw->scissor[0] + draw->scissor[2]);
        ds->sy1 = DXR_MIN(ds->sy1, draw->scissor[1] + draw->scissor[3]);
    }
    dev->stats.draw_calls++;
    if (ds->sx0 >= ds->sx1 || ds->sy0 >= ds->sy1) return 1;

    geom_ctx g;
    memset(&g, 0, sizeof(g));
    g.dev = dev; g.pass = pass; g.draw = draw; g.pipe = pipe; g.ds = ds;
    g.V = pipe->varying_cells;
    g.half_w = 0.5f * (float)pass->width;
    g.half_h = 0.5f * (float)pass->height;
    g.guard_x = GUARD_PIXELS / g.half_w;
    g.guard_y = GUARD_PIXELS / g.half_h;
    if (g.guard_x < 1.0f) g.guard_x = 1.0f;
    if (g.guard_y < 1.0f) g.guard_y = 1.0f;
    for (int i = 0; i < pipe->num_vbufs; i++) {
        g.vb_stride[pipe->vbufs[i].slot] = pipe->vbufs[i].stride;
        g.vb_step[pipe->vbufs[i].slot] = pipe->vbufs[i].step_rate;
    }
    /* vertex range referenced by the draw */
    int vmin, vmax;
    if (draw->index_data) {
        vmin = INT32_MAX; vmax = INT32_MIN;
        if (draw->index_bytes != 2 && draw->index_bytes != 4) return 0;
        uint64_t avail = draw->index_size / (uint64_t)draw->index_bytes;
        int64_t last = (int64_t)draw->first + draw->count;
        if (draw->first < 0 || (uint64_t)last > avail) return 0;
        if (draw->index_bytes == 2) {
            const uint8_t *ip = draw->index_data + (size_t)draw->first * 2;
            for (int i = 0; i < draw->count; i++) { uint16_t v; memcpy(&v, ip + (size_t)i * 2, 2); if (v < vmin) vmin = v; if (v > vmax) vmax = v; }
        } else {
            const uint8_t *ip = draw->index_data + (size_t)draw->first * 4;
            for (int i = 0; i < draw->count; i++) { uint32_t v; memcpy(&v, ip + (size_t)i * 4, 4); if ((int)v < vmin) vmin = (int)v; if ((int)v > vmax) vmax = (int)v; }
        }
        vmin += draw->base_vertex; vmax += draw->base_vertex;
    } else {
        vmin = draw->first; vmax = draw->first + draw->count - 1;
    }
    if (vmin < 0 || vmax < vmin || (int64_t)vmax - vmin > 16 * 1024 * 1024) return 0;
    g.vmin = vmin;
    g.nverts = vmax - vmin + 1;
    switch (pipe->topology) {
    case DXR_TOPO_TRIANGLES: g.nprims = draw->count / 3; break;
    case DXR_TOPO_TRIANGLE_STRIP: case DXR_TOPO_TRIANGLE_FAN: g.nprims = draw->count - 2; break;
    case DXR_TOPO_LINES: g.nprims = draw->count / 2; break;
    case DXR_TOPO_LINE_STRIP: g.nprims = draw->count - 1; break;
    default: g.nprims = draw->count; break;
    }
    if (g.nprims <= 0) return 1;
    int instances = draw->instance_count > 0 ? draw->instance_count : 1;
    for (int inst = 0; inst < instances; inst++) {
        g.instance = inst;
        g.clip = (float *)dxr_arena_alloc(&dev->vtx_arena, sizeof(float) * 4 * (size_t)g.nverts);
        g.vary = (float *)dxr_arena_alloc(&dev->vtx_arena, sizeof(float) * (size_t)DXR_MAX(1, g.V) * (size_t)g.nverts);
        if (!g.clip || !g.vary) return 0;
        g.vchunk = 128;
        g.pchunk = 256;
        g.key_base = (pass->draw_seq++) << 26;
        g.use_fast_vs = 0;
        if (pipe->fast_vs && dev->cfg.fast_shaders) {
            g.vs_data = *pipe->vs_data;
            g.use_fast_vs = pipe->fast_vs->vertex_begin(&g.vs_data, draw->uniforms);
        }
        dxr_pool_run(dev->pool, shade_job, &g, (g.nverts + g.vchunk - 1) / g.vchunk, 1);
        dxr_pool_run(dev->pool, prim_job, &g, (g.nprims + g.pchunk - 1) / g.pchunk, 1);
        dxr_arena_reset(&dev->vtx_arena);
    }
    {
        uint64_t dt = dxr_now_ns() - t0;
        dxr_pipeline *pp = (dxr_pipeline *)pipe;
        pp->prof_vs_ns += dt;
        pp->prof_verts += (uint64_t)g.nverts * (uint64_t)instances;
        pp->prof_draws++;
        dev->stats.ns_geometry += dt;
    }
    return 1;
}

/* ======================================================================================= */
/* Rasterization                                                                           */
/* ======================================================================================= */

typedef struct tile_state {
    int x0, y0, x1, y1;      /* tile bounds, max exclusive */
    int hiz_valid, hiz_dirty;
    int nbx, nby;
    float hiz[(128 / HIZ_BLOCK) * (128 / HIZ_BLOCK)];
} tile_state;

static void hiz_refresh(const struct dxr_pass *pass, tile_state *ts) {
    ts->nbx = (ts->x1 - ts->x0 + HIZ_BLOCK - 1) / HIZ_BLOCK;
    ts->nby = (ts->y1 - ts->y0 + HIZ_BLOCK - 1) / HIZ_BLOCK;
    for (int by = 0; by < ts->nby; by++) {
        int ya = ts->y0 + by * HIZ_BLOCK, yb = DXR_MIN(ts->y1, ya + HIZ_BLOCK);
        for (int bx = 0; bx < ts->nbx; bx++) {
            int xa = ts->x0 + bx * HIZ_BLOCK, xb = DXR_MIN(ts->x1, xa + HIZ_BLOCK);
            float m = 3.0e38f;
            for (int y = ya; y < yb; y++) {
                const float *row = pass->depth + (size_t)y * pass->width;
#if defined(DXR_NEON)
                if (xb - xa == 8) {
                    float32x4_t v = vminq_f32(vld1q_f32(row + xa), vld1q_f32(row + xa + 4));
                    float lm = vminvq_f32(v);
                    if (lm < m) m = lm;
                    continue;
                }
#endif
                for (int x = xa; x < xb; x++) if (row[x] < m) m = row[x];
            }
            ts->hiz[by * ts->nbx + bx] = m;
        }
    }
    ts->hiz_valid = 1;
    ts->hiz_dirty = 0;
}

DXR_INLINE int depth_test(int func, float z, float stored) {
    switch (func) {
    case DXR_CMP_GEQUAL: return z >= stored;
    case DXR_CMP_GREATER: return z > stored;
    case DXR_CMP_LEQUAL: return z <= stored;
    case DXR_CMP_LESS: return z < stored;
    case DXR_CMP_EQUAL: return z == stored;
    case DXR_CMP_NOTEQUAL: return z != stored;
    case DXR_CMP_NEVER: return 0;
    default: return 1;
    }
}

DXR_INLINE void write_color(const struct dxr_pass *pass, const dxr_pipeline *pipe, int t, int x, int y, const dxr_cell *out_cells) {
    const dxr_texture *tex = pass->color_tex[t];
    static const dxr_blend_state def_state = {1, 0, 15, 0, 0, 0, 0, 0, 0};
    const dxr_blend_state *bs = t < pipe->num_targets && pipe->targets[t].present ? &pipe->targets[t] : &def_state;
    if (!bs->write_mask) return;
    uint8_t *px = pass->color[t] + ((size_t)y * pass->width + x) * tex->texel_bytes;
    int nc = pipe->out_ncomp[t];
    if (dxr_ctype_is_int(tex->ctype)) {
        dxr_cell c[4] = {{.u = 0}, {.u = 0}, {.u = 0}, {.u = 1}};
        memcpy(c, out_cells, (size_t)nc * 4);
        dxr_texel_store_cells(px, tex->ctype, tex->ncomp, c);
        return;
    }
    float src[4] = {0.0f, 0.0f, 0.0f, 1.0f};
    memcpy(src, out_cells, (size_t)nc * 4);
    if (bs->enabled || bs->write_mask != 15) {
        dxr_cell dc[4];
        float dst[4], res[4];
        dxr_texel_load(px, tex->ctype, tex->ncomp, dc);
        dst[0] = dc[0].f; dst[1] = dc[1].f; dst[2] = dc[2].f; dst[3] = dc[3].f;
        if (bs->enabled) dxr_blend(bs, src, dst, res); else memcpy(res, src, 16);
        for (int i = 0; i < 4; i++) src[i] = (bs->write_mask >> i & 1) ? res[i] : dst[i];
    }
    if (tex->ctype == DXR_CT_UNORM8 && tex->ncomp == 4) {
        for (int i = 0; i < 4; i++) { float v = src[i]; v = v >= 0.0f ? (v <= 1.0f ? v : 1.0f) : 0.0f; px[i] = (uint8_t)(v * 255.0f + 0.5f); }
        return;
    }
    dxr_texel_store(px, tex->ctype, tex->ncomp, src);
}

static void raster_tri(dxr_device *dev, const struct dxr_pass *pass, const dxr_tri *tri, tile_state *ts, int thread) {
    const dxr_draw_state *ds = tri->ds;
    const dxr_pipeline *pipe = ds->pipe;
    struct dxr_thread_stats *st = &dev->tstats[thread];
    int xa = DXR_MAX(tri->bx0, ts->x0), xb = DXR_MIN(tri->bx1, ts->x1 - 1);
    int ya = DXR_MAX(tri->by0, ts->y0), yb = DXR_MIN(tri->by1, ts->y1 - 1);
    if (xa > xb || ya > yb) return;
    const int depth_on = pass->depth && pipe->depth_enabled;
    const int func = pipe->depth_func;
    const int depth_write = depth_on && pipe->depth_write;
    const int monotonic = func == DXR_CMP_GEQUAL || func == DXR_CMP_GREATER;
    if (depth_on && monotonic && dev->cfg.occlusion_culling) {
        if (!ts->hiz_valid || ts->hiz_dirty >= HIZ_REFRESH) hiz_refresh(pass, ts);
        int bx0 = (xa - ts->x0) / HIZ_BLOCK, bx1 = (xb - ts->x0) / HIZ_BLOCK, by0 = (ya - ts->y0) / HIZ_BLOCK, by1 = (yb - ts->y0) / HIZ_BLOCK;
        float far = 3.0e38f;
        for (int by = by0; by <= by1; by++) for (int bx = bx0; bx <= bx1; bx++) { float v = ts->hiz[by * ts->nbx + bx]; if (v < far) far = v; }
        if (tri->zmax < far || (func == DXR_CMP_GREATER && tri->zmax <= far)) { st->tris_hiz_rejected++; return; }
    }
    dxr_vm *vm = get_vm(pipe, thread, 1);
    if (!vm) return;
    const uint64_t prof_t0 = dxr_now_ns();
    dxr_cell *R = dxr_vm_cells(vm);
    const int V = pipe->varying_cells;
    const float *a0 = tri->var, *dvx = tri->var + V, *dvy = tri->var + 2 * V;
    const int flags = pipe->fs_flags;
    const int need_grad = (flags & DXR_SHF_DERIVATIVES) != 0;
    const int writes_fragdepth = (flags & DXR_SHF_FRAGDEPTH) != 0;
    const int can_discard = (flags & DXR_SHF_DISCARD) != 0;
    const int early_write = depth_write && !can_discard && !writes_fragdepth;
    const uint32_t fragdepth_cell = dxr_shader_fragdepth_cell(pipe->fs);
    float vary[DXR_MAX_VARYING_CELLS], gx[DXR_MAX_VARYING_CELLS], gy[DXR_MAX_VARYING_CELLS];
    dxr_frag_ctx ctx;
    memset(&ctx, 0, sizeof(ctx));
    ctx.uniforms = ds->uniforms;
    ctx.push_constants = ds->push_constants;
    ctx.varyings = vary;
    ctx.dvdx = need_grad ? gx : NULL;
    ctx.dvdy = need_grad ? gy : NULL;
    ctx.front_facing = tri->front;
    uint64_t shaded = 0, discarded = 0, zfail = 0, fast = 0;
    int wrote_depth = 0;
    const dxr_fast_kernel *kern = (dev->cfg.fast_shaders && !writes_fragdepth) ? pipe->fast : NULL;
    float kout[4];
    for (int y = ya; y <= yb; y++) {
        /* exact span of pixel centers inside all three edges */
        int64_t Yc = (int64_t)y * SUBPIX + SUBPIX / 2;
        int xs = xa, xe = xb;
        for (int e = 0; e < 3; e++) {
            int64_t A = tri->A[e], D = tri->B[e] * Yc + tri->C[e];
            if (A > 0) {
                int64_t lim = -floor_div(D, A);                 /* ceil(-D / A) */
                int64_t px = (lim - SUBPIX / 2 + SUBPIX - 1) >> SUBPIX_SHIFT;
                if (px > xs) xs = px > INT32_MAX ? INT32_MAX : (int)px;
            } else if (A < 0) {
                int64_t lim = floor_div(D, -A);
                int64_t px = (lim - SUBPIX / 2) >> SUBPIX_SHIFT;
                if (px < xe) xe = px < INT32_MIN ? INT32_MIN : (int)px;
            } else if (D < 0) {
                xs = 1; xe = 0;
                break;
            }
        }
        if (xs > xe) continue;
        const float py = (float)y + 0.5f - tri->y0;
        float *drow = pass->depth ? pass->depth + (size_t)y * pass->width : NULL;
        for (int x = xs; x <= xe; x++) {
            const float px = (float)x + 0.5f - tri->x0;
            float z = tri->z0 + tri->dzdx * px + tri->dzdy * py;
            z = z < 0.0f ? 0.0f : (z > 1.0f ? 1.0f : z);
            if (depth_on && !writes_fragdepth) {
                if (!depth_test(func, z, drow[x])) { zfail++; continue; }
                if (early_write) { drow[x] = z; wrote_depth = 1; }
            }
            const float iw = tri->w0 + tri->dwdx * px + tri->dwdy * py;
            const float w = 1.0f / iw;
            for (int s = 0; s < V; s++) {
                if (pipe->slot_flat[s]) { vary[s] = a0[s]; if (need_grad) { gx[s] = 0.0f; gy[s] = 0.0f; } }
                else {
                    float v = (a0[s] + dvx[s] * px + dvy[s] * py) * w;
                    vary[s] = v;
                    if (need_grad) { gx[s] = (dvx[s] - v * tri->dwdx) * w; gy[s] = (dvy[s] - v * tri->dwdy) * w; }
                }
            }
            ctx.frag_coord[0] = (float)x + 0.5f;
            ctx.frag_coord[1] = (float)y + 0.5f;
            ctx.frag_coord[2] = z;
            ctx.frag_coord[3] = iw;
            shaded++;
            int handled = kern ? kern->fragment(pipe->fs_data, &ctx, kout) : -1;
            if (handled < 0) {
                for (int m = 0; m < pipe->num_varyings; m++) memcpy(&R[pipe->varyings[m].fs_cell], &vary[pipe->varyings[m].slot], (size_t)pipe->varyings[m].cells * 4);
                if (!dxr_vm_run(vm, &ctx)) { discarded++; continue; }
            } else if (handled == 0) {
                discarded++; fast++;
                continue;
            } else {
                fast++;
                if (depth_on && depth_write && !early_write) { drow[x] = z; wrote_depth = 1; }
                if (pass->color_tex[0]) write_color(pass, pipe, 0, x, y, (const dxr_cell *)kout);
                continue;
            }
            if (depth_on) {
                if (writes_fragdepth) {
                    z = R[fragdepth_cell].f;
                    z = z < 0.0f ? 0.0f : (z > 1.0f ? 1.0f : z);
                    if (!depth_test(func, z, drow[x])) { zfail++; continue; }
                }
                if (depth_write && !early_write) { drow[x] = z; wrote_depth = 1; }
            }
            for (int t = 0; t < pass->num_color; t++) {
                if (!pass->color_tex[t] || pipe->out_cell[t] == UINT32_MAX) continue;
                write_color(pass, pipe, t, x, y, &R[pipe->out_cell[t]]);
            }
        }
    }
    if (wrote_depth) { if (monotonic) ts->hiz_dirty++; else ts->hiz_valid = 0; }
    {
        dxr_pipeline *pp = (dxr_pipeline *)pipe;
        pp->prof[thread].frags += shaded;
        pp->prof[thread].tris++;
        pp->prof[thread].ns += dxr_now_ns() - prof_t0;
    }
    st->fragments_shaded += shaded;
    st->fragments_fast += fast;
    st->fragments_discarded += discarded;
    st->fragments_depth_failed += zfail;
}

typedef struct raster_ctx {
    dxr_device *dev;
    struct dxr_pass *pass;
} raster_ctx;

static void raster_job(void *vctx, int index, int thread) {
    raster_ctx *rc = (raster_ctx *)vctx;
    struct dxr_pass *pass = rc->pass;
    int tile = pass->order[index];
    tile_state ts;
    ts.x0 = (tile % pass->tiles_x) * pass->tile;
    ts.y0 = (tile / pass->tiles_x) * pass->tile;
    ts.x1 = DXR_MIN(pass->width, ts.x0 + pass->tile);
    ts.y1 = DXR_MIN(pass->height, ts.y0 + pass->tile);
    ts.hiz_valid = 0;
    ts.hiz_dirty = 0;
    ts.nbx = ts.nby = 0;
    /* k-way merge of the per thread lists, each already ordered by key */
    bin_chunk *cur[DXR_MAX_THREADS];
    uint32_t pos[DXR_MAX_THREADS];
    int live = 0;
    for (int t = 0; t < pass->nthreads; t++) {
        cur[live] = pass->bins[t][tile].head;
        pos[live] = 0;
        if (cur[live]) live++;
    }
    while (live > 0) {
        int best = 0;
        if (live > 1) {
            uint64_t bk = cur[0]->e[pos[0]].key;
            for (int t = 1; t < live; t++) { uint64_t k = cur[t]->e[pos[t]].key; if (k < bk) { bk = k; best = t; } }
        }
        raster_tri(rc->dev, pass, cur[best]->e[pos[best]].tri, &ts, thread);
        if (++pos[best] == cur[best]->count) {
            cur[best] = cur[best]->next;
            pos[best] = 0;
            if (!cur[best]) { live--; cur[best] = cur[live]; pos[best] = pos[live]; }
        }
    }
}

void dxr_pass_end(dxr_device *dev) {
    struct dxr_pass *pass = dev->pass;
    if (!pass) return;
    uint64_t t0 = dxr_now_ns();
    pass->order = (int *)dxr_arena_alloc(&dev->pass_arena, sizeof(int) * (size_t)pass->ntiles);
    pass->cost = (uint32_t *)dxr_arena_alloc(&dev->pass_arena, sizeof(uint32_t) * (size_t)pass->ntiles);
    int n = 0;
    if (pass->order && pass->cost) {
        for (int tile = 0; tile < pass->ntiles; tile++) {
            uint32_t cost = 0;
            for (int t = 0; t < pass->nthreads; t++) for (const bin_chunk *c = pass->bins[t][tile].head; c; c = c->next) cost += c->count;
            if (cost) { pass->order[n] = tile; pass->cost[tile] = cost; n++; }
        }
        if (dev->cfg.adaptive && n > 2) {
            /* heaviest tiles first: the dynamic scheduler then balances the tail with cheap tiles */
            for (int gap = n / 2; gap > 0; gap /= 2)
                for (int i = gap; i < n; i++) {
                    int v = pass->order[i], j = i;
                    while (j >= gap && pass->cost[pass->order[j - gap]] < pass->cost[v]) { pass->order[j] = pass->order[j - gap]; j -= gap; }
                    pass->order[j] = v;
                }
        }
        raster_ctx rc = {dev, pass};
        dxr_pool_run(dev->pool, raster_job, &rc, n, 1);
        dev->stats.tiles_rendered += (uint64_t)n;
    }
    for (int t = 0; t < DXR_MAX_THREADS; t++) {
        struct dxr_thread_stats *s = &dev->tstats[t];
        dev->stats.fragments_shaded += s->fragments_shaded;
        dev->stats.fragments_fast += s->fragments_fast;
        dev->stats.fragments_discarded += s->fragments_discarded;
        dev->stats.fragments_depth_failed += s->fragments_depth_failed;
        dev->stats.tris_hiz_rejected += s->tris_hiz_rejected;
        dev->stats.tile_tri_refs += s->tile_tri_refs;
        dev->stats.triangles_binned += s->triangles_binned;
        dev->stats.triangles_culled += s->triangles_culled;
        dev->stats.triangles_clipped += s->triangles_clipped;
        dev->stats.vertices_shaded += s->vertices_shaded;
        dev->stats.triangles_in += s->triangles_in;
        memset(s, 0, sizeof(*s));
    }
    reset_pass_memory(dev);
    dev->stats.ns_raster += dxr_now_ns() - t0;
}

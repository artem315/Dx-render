/* DX Render - pipeline objects: links the two SPIR-V stages, vertex layout and fixed state. */
#include "dxr_internal.h"

#include <stdio.h>

typedef struct reader {
    const uint8_t *p, *end;
    int failed;
} reader;

static uint32_t rd_u32(reader *r) {
    uint32_t v = 0;
    if (r->p + 4 > r->end) { r->failed = 1; return 0; }
    memcpy(&v, r->p, 4);
    r->p += 4;
    return v;
}

static float rd_f32(reader *r) {
    uint32_t v = rd_u32(r);
    float f;
    memcpy(&f, &v, 4);
    return f;
}

static const uint8_t *rd_bytes(reader *r, size_t n) {
    if ((size_t)(r->end - r->p) < n) { r->failed = 1; return NULL; }
    const uint8_t *p = r->p;
    r->p += n;
    return p;
}

static void rd_str(reader *r, char *dst, size_t cap) {
    uint32_t n = rd_u32(r);
    const uint8_t *p = rd_bytes(r, n);
    dst[0] = 0;
    if (!p) return;
    if (n >= cap) n = (uint32_t)cap - 1;
    memcpy(dst, p, n);
    dst[n] = 0;
}

static uint32_t *rd_words(reader *r, uint32_t *count) {
    uint32_t n = rd_u32(r);
    const uint8_t *p = rd_bytes(r, (size_t)n * 4);
    *count = n;
    if (!p || n == 0) return NULL;
    uint32_t *w = (uint32_t *)dxr_alloc_raw((size_t)n * 4);
    memcpy(w, p, (size_t)n * 4);
    return w;
}

/*
 * Blob layout (little endian):
 *   u32 magic 'DXRP', str name
 *   u32 nwords + vertex SPIR-V, u32 nwords + fragment SPIR-V
 *   u32 nattribs  { u32 slot, location, ctype, ncomp, offset }
 *   u32 nvbufs    { u32 slot, stride, step_rate }
 *   u32 nuniforms { str name, u32 kind, ctype, ncomp }
 *   u32 depth_enabled, depth_func, depth_write, f32 bias_scale, bias_const
 *   u32 cull, topology, wireframe
 *   u32 ntargets  { u32 present, blend, write_mask, src_c, dst_c, op_c, src_a, dst_a, op_a }
 */
dxr_pipeline *dxr_pipeline_create(dxr_device *dev, const uint8_t *blob, size_t len) {
    reader r = {blob, blob + len, 0};
    if (rd_u32(&r) != 0x50525844u) { dxr_set_error("bad pipeline blob"); return NULL; }
    dxr_pipeline *p = (dxr_pipeline *)dxr_alloc(sizeof(dxr_pipeline));
    if (!p) return NULL;
    rd_str(&r, p->name, sizeof(p->name));
    uint32_t vs_n = 0, fs_n = 0;
    uint32_t *vs_w = rd_words(&r, &vs_n);
    uint32_t *fs_w = rd_words(&r, &fs_n);
    p->num_attribs = (int)rd_u32(&r);
    if (p->num_attribs > DXR_MAX_ATTRIBS) r.failed = 1;
    for (int i = 0; i < p->num_attribs && !r.failed; i++) {
        p->attribs[i].slot = (uint8_t)rd_u32(&r);
        p->attribs[i].location = (uint8_t)rd_u32(&r);
        p->attribs[i].ctype = (uint8_t)rd_u32(&r);
        p->attribs[i].ncomp = (uint8_t)rd_u32(&r);
        p->attribs[i].offset = rd_u32(&r);
        if (p->attribs[i].slot >= DXR_MAX_VERTEX_BUFFERS) r.failed = 1;
    }
    p->num_vbufs = (int)rd_u32(&r);
    if (p->num_vbufs > DXR_MAX_VERTEX_BUFFERS) r.failed = 1;
    for (int i = 0; i < p->num_vbufs && !r.failed; i++) {
        p->vbufs[i].slot = (uint8_t)rd_u32(&r);
        p->vbufs[i].stride = rd_u32(&r);
        p->vbufs[i].step_rate = (uint8_t)rd_u32(&r);
    }
    p->num_uniforms = (int)rd_u32(&r);
    if (p->num_uniforms > DXR_MAX_UNIFORMS) r.failed = 1;
    char names[DXR_MAX_UNIFORMS][96];
    const char *name_ptrs[DXR_MAX_UNIFORMS];
    for (int i = 0; i < p->num_uniforms && !r.failed; i++) {
        rd_str(&r, names[i], sizeof(names[i]));
        name_ptrs[i] = names[i];
        p->uniform_kind[i] = (uint8_t)rd_u32(&r);
        rd_u32(&r);
        rd_u32(&r);
    }
    p->depth_enabled = (int)rd_u32(&r);
    p->depth_func = (int)rd_u32(&r);
    p->depth_write = (int)rd_u32(&r);
    p->depth_bias_scale = rd_f32(&r);
    p->depth_bias_const = rd_f32(&r);
    p->cull = (int)rd_u32(&r);
    p->topology = (int)rd_u32(&r);
    p->wireframe = (int)rd_u32(&r);
    p->num_targets = (int)rd_u32(&r);
    if (p->num_targets > DXR_MAX_COLOR_TARGETS) r.failed = 1;
    for (int i = 0; i < p->num_targets && !r.failed; i++) {
        dxr_blend_state *b = &p->targets[i];
        b->present = (uint8_t)rd_u32(&r); b->enabled = (uint8_t)rd_u32(&r); b->write_mask = (uint8_t)rd_u32(&r);
        b->src_c = (uint8_t)rd_u32(&r); b->dst_c = (uint8_t)rd_u32(&r); b->op_c = (uint8_t)rd_u32(&r);
        b->src_a = (uint8_t)rd_u32(&r); b->dst_a = (uint8_t)rd_u32(&r); b->op_a = (uint8_t)rd_u32(&r);
    }
    if (r.failed || !vs_w || !fs_w) {
        dxr_set_error("truncated pipeline blob for '%s'", p->name);
        dxr_free(vs_w); dxr_free(fs_w); dxr_free(p);
        return NULL;
    }
    p->vs = dxr_shader_create(vs_w, vs_n, 0, name_ptrs, p->num_uniforms);
    if (p->vs) p->fs = dxr_shader_create(fs_w, fs_n, 1, name_ptrs, p->num_uniforms);
    dxr_free(vs_w);
    dxr_free(fs_w);
    if (!p->vs || !p->fs) {
        char msg[400];
        snprintf(msg, sizeof(msg), "pipeline '%s': %s", p->name, dxr_last_error());
        dxr_set_error("%s", msg);
        dxr_pipeline_destroy(dev, p);
        return NULL;
    }
    if (dxr_shader_position_cell(p->vs) == UINT32_MAX) {
        dxr_set_error("pipeline '%s': vertex shader does not write gl_Position", p->name);
        dxr_pipeline_destroy(dev, p);
        return NULL;
    }
    /* vertex attributes -> vertex machine cells */
    const dxr_shader_io *vin = dxr_shader_inputs(p->vs);
    int nvin = dxr_shader_num_inputs(p->vs);
    for (int i = 0; i < p->num_attribs; i++) {
        p->attrib_cell[i] = UINT32_MAX;
        for (int k = 0; k < nvin; k++) {
            int vec = vin[k].vec ? vin[k].vec : 1;
            int span = vin[k].cells / vec;
            int loc = p->attribs[i].location;
            if (loc >= vin[k].location && loc < vin[k].location + span) {
                p->attrib_cell[i] = vin[k].cell + (uint32_t)((loc - vin[k].location) * vec);
                p->attrib_is_int[i] = vin[k].is_int;
                p->attrib_vec[i] = (uint8_t)vec;
                break;
            }
        }
    }
    /* varyings: fragment inputs matched to vertex outputs by location */
    const dxr_shader_io *fin = dxr_shader_inputs(p->fs);
    const dxr_shader_io *vout = dxr_shader_outputs(p->vs);
    int nfin = dxr_shader_num_inputs(p->fs), nvout = dxr_shader_num_outputs(p->vs);
    int slot = 0;
    for (int i = 0; i < nfin && p->num_varyings < 32; i++) {
        dxr_varying_map *m = &p->varyings[p->num_varyings];
        m->fs_cell = fin[i].cell;
        m->cells = fin[i].cells;
        m->flat = fin[i].flat || fin[i].is_int;
        m->vs_cell = UINT32_MAX;
        for (int k = 0; k < nvout; k++) if (vout[k].location == fin[i].location && vout[k].cells >= fin[i].cells) { m->vs_cell = vout[k].cell; break; }
        if (slot + m->cells > DXR_MAX_VARYING_CELLS) {
            dxr_set_error("pipeline '%s': too many varyings", p->name);
            dxr_pipeline_destroy(dev, p);
            return NULL;
        }
        m->slot = (uint16_t)slot;
        for (int c = 0; c < m->cells; c++) p->slot_flat[slot + c] = m->flat;
        dxr_shader_set_input_slot(p->fs, i, slot);
        slot += m->cells;
        p->num_varyings++;
    }
    p->varying_cells = slot;
    /* fragment outputs -> color targets */
    const dxr_shader_io *fout = dxr_shader_outputs(p->fs);
    int nfout = dxr_shader_num_outputs(p->fs);
    for (int t = 0; t < DXR_MAX_COLOR_TARGETS; t++) p->out_cell[t] = UINT32_MAX;
    for (int k = 0; k < nfout; k++) {
        int vec = fout[k].vec ? fout[k].vec : 1;
        int span = fout[k].cells / vec;
        for (int j = 0; j < span; j++) {
            int t = fout[k].location + j;
            if (t < 0 || t >= DXR_MAX_COLOR_TARGETS) continue;
            p->out_cell[t] = fout[k].cell + (uint32_t)(j * vec);
            p->out_ncomp[t] = (uint8_t)vec;
            p->out_is_int[t] = fout[k].is_int;
        }
    }
    p->fs_flags = dxr_shader_flags(p->fs);
    p->fast = dev && dev->cfg.fast_shaders ? dxr_fast_find(p, dev) : NULL;
    snprintf(p->info, sizeof(p->info), "%s vs=%016llx fs=%016llx varyings=%d cells=%u/%u %s", p->name,
             (unsigned long long)dxr_shader_hash(p->vs), (unsigned long long)dxr_shader_hash(p->fs), p->varying_cells,
             dxr_shader_num_cells(p->vs), dxr_shader_num_cells(p->fs), p->fast ? p->fast->name : "interpreter");
    return p;
}

void dxr_pipeline_destroy(dxr_device *dev, dxr_pipeline *p) {
    (void)dev;
    if (!p) return;
    for (int i = 0; i < DXR_MAX_THREADS; i++) {
        dxr_vm_destroy(p->vs_vm[i]);
        dxr_vm_destroy(p->fs_vm[i]);
    }
    dxr_shader_destroy(p->vs);
    dxr_shader_destroy(p->fs);
    dxr_free(p->fast_data);
    dxr_free(p);
}

int dxr_pipeline_fast_path(const dxr_pipeline *p) { return p && p->fast != NULL; }

const char *dxr_pipeline_info(const dxr_pipeline *p) { return p ? p->info : ""; }

/* ---- blending ---------------------------------------------------------------------- */
DXR_INLINE void blend_factor(int f, const float s[4], const float d[4], float out[4], int alpha) {
    float sa = s[3], da = d[3];
    switch (f) {
    case DXR_BF_ZERO: out[0] = out[1] = out[2] = out[3] = 0.0f; break;
    case DXR_BF_ONE: out[0] = out[1] = out[2] = out[3] = 1.0f; break;
    case DXR_BF_SRC_COLOR: memcpy(out, s, 16); break;
    case DXR_BF_ONE_MINUS_SRC_COLOR: for (int i = 0; i < 4; i++) out[i] = 1.0f - s[i]; break;
    case DXR_BF_DST_COLOR: memcpy(out, d, 16); break;
    case DXR_BF_ONE_MINUS_DST_COLOR: for (int i = 0; i < 4; i++) out[i] = 1.0f - d[i]; break;
    case DXR_BF_SRC_ALPHA: out[0] = out[1] = out[2] = out[3] = sa; break;
    case DXR_BF_ONE_MINUS_SRC_ALPHA: out[0] = out[1] = out[2] = out[3] = 1.0f - sa; break;
    case DXR_BF_DST_ALPHA: out[0] = out[1] = out[2] = out[3] = da; break;
    case DXR_BF_ONE_MINUS_DST_ALPHA: out[0] = out[1] = out[2] = out[3] = 1.0f - da; break;
    case DXR_BF_SRC_ALPHA_SATURATE: { float v = sa < 1.0f - da ? sa : 1.0f - da; out[0] = out[1] = out[2] = v; out[3] = 1.0f; break; }
    case DXR_BF_CONSTANT_COLOR: case DXR_BF_CONSTANT_ALPHA: out[0] = out[1] = out[2] = out[3] = 0.0f; break; /* blend constant is (0,0,0,0) */
    default: out[0] = out[1] = out[2] = out[3] = 1.0f; break;
    }
    (void)alpha;
}

DXR_INLINE float blend_op(int op, float s, float sf, float d, float df) {
    switch (op) {
    case DXR_BOP_SUBTRACT: return s * sf - d * df;
    case DXR_BOP_REVERSE_SUBTRACT: return d * df - s * sf;
    case DXR_BOP_MIN: return s < d ? s : d;
    case DXR_BOP_MAX: return s > d ? s : d;
    default: return s * sf + d * df;
    }
}

void dxr_blend(const dxr_blend_state *bs, const float src[4], const float dst[4], float out[4]) {
    if (bs->src_c == DXR_BF_SRC_ALPHA && bs->dst_c == DXR_BF_ONE_MINUS_SRC_ALPHA && bs->op_c == DXR_BOP_ADD &&
        bs->src_a == DXR_BF_ONE && bs->dst_a == DXR_BF_ONE_MINUS_SRC_ALPHA && bs->op_a == DXR_BOP_ADD) {
        float a = src[3], ia = 1.0f - a;
        out[0] = src[0] * a + dst[0] * ia;
        out[1] = src[1] * a + dst[1] * ia;
        out[2] = src[2] * a + dst[2] * ia;
        out[3] = a + dst[3] * ia;
        return;
    }
    float sfc[4], dfc[4], sfa[4], dfa[4];
    blend_factor(bs->src_c, src, dst, sfc, 0);
    blend_factor(bs->dst_c, src, dst, dfc, 0);
    blend_factor(bs->src_a, src, dst, sfa, 1);
    blend_factor(bs->dst_a, src, dst, dfa, 1);
    out[0] = blend_op(bs->op_c, src[0], sfc[0], dst[0], dfc[0]);
    out[1] = blend_op(bs->op_c, src[1], sfc[1], dst[1], dfc[1]);
    out[2] = blend_op(bs->op_c, src[2], sfc[2], dst[2], dfc[2]);
    out[3] = blend_op(bs->op_a, src[3], sfa[3], dst[3], dfa[3]);
}

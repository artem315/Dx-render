/* DX Render - Shader System: SPIR-V front end and interpreter.
 *
 * Minecraft hands every backend the SPIR-V it compiled from the game's (or a resource
 * pack's) GLSL. This module decodes that SPIR-V into a compact register program and
 * executes it on the CPU: vertex stage per vertex, fragment stage per pixel.
 *
 * GLSL has no recursion, so every SSA id owns a fixed range of 32 bit cells inside a
 * per thread machine state; no frames are needed.
 */
#include "dxr_internal.h"

#include <stdarg.h>
#include <stdio.h>

/* ---- type model -------------------------------------------------------------------- */
enum { TY_NONE = 0, TY_VOID, TY_BOOL, TY_INT, TY_FLOAT, TY_VEC, TY_MAT, TY_ARRAY, TY_STRUCT, TY_PTR, TY_IMAGE, TY_SAMPLER, TY_SAMPLED_IMAGE, TY_FUNC };

typedef struct sp_type {
    uint8_t kind, is_signed, dim, storage;
    uint32_t elem;        /* component / column / element / pointee / image type id */
    uint32_t count;       /* components / columns / array length / member count */
    uint32_t cells;       /* flattened size in cells */
    uint32_t array_stride;
    uint32_t *members, *member_cell, *member_off, *member_mstride;
    int32_t builtin_position_member;
} sp_type;

enum { ID_NONE = 0, ID_TYPE, ID_VALUE, ID_VAR, ID_FUNC, ID_LABEL, ID_EXTSET, ID_PTR };
enum { PK_NULL = 0, PK_CELL, PK_UBO, PK_PUSH, PK_SAMPLER, PK_MEM };

typedef struct sp_sptr {     /* pointer known at decode time */
    uint8_t valid, kind;
    uint16_t mstride;
    uint32_t type;           /* pointee type */
    uint32_t off;            /* cell index or byte offset */
    uint32_t binding;
} sp_sptr;

typedef struct sp_id {
    uint8_t kind, is_const, storage, flat;
    uint8_t ext_glsl;
    uint32_t type;           /* value type, or pointer type for variables / pointer ids */
    uint32_t cell;           /* value cells / variable storage cells */
    uint32_t slot;           /* runtime pointer slot (pointer typed ids) */
    int32_t location, binding, builtin;
    uint32_t array_stride;
    const char *name;
    sp_sptr sptr;
    uint32_t func;           /* index into funcs for ID_FUNC */
    uint16_t tag[4];         /* derivative tags, see analyse_tags */
    uint8_t tag_state;       /* variables: 0 top, 1 value, 2 poisoned */
    uint8_t param_stored;
} sp_id;

typedef struct sp_func {
    uint32_t id, ret_type, ret_cell;
    uint32_t first_word, end_word;
    uint32_t pc;
    uint32_t params[16];
    int num_params;
} sp_func;

typedef struct dxr_op {
    uint16_t op;
    uint8_t n, aux;
    uint32_t d, a, b, c;
} dxr_op;

typedef struct rt_ptr {
    uint8_t kind;
    uint16_t mstride;
    uint32_t type;
    uint32_t cell;
    const uint8_t *mem, *end;
} rt_ptr;

struct dxr_shader {
    int fragment;
    uint32_t bound;
    sp_type *types;          /* indexed by id */
    sp_id *ids;
    sp_func funcs[64];
    int num_funcs;
    uint32_t entry_func;
    dxr_op *code;
    uint32_t ncode, code_cap;
    uint32_t *extra;
    uint32_t nextra, extra_cap;
    dxr_cell *init;
    uint32_t ncells, cells_cap;
    uint32_t nslots;
    uint32_t *reinit;        /* pairs (cell, n) restored before every run */
    uint32_t nreinit;
    dxr_shader_io inputs[32], outputs[32];
    int num_inputs, num_outputs;
    uint16_t input_slot[32];
    uint32_t position_cell, fragdepth_cell, fragcoord_cell, frontfacing_cell, vertexindex_cell, instanceindex_cell;
    int flags;
    uint64_t hash;
    const char *const *uniform_names;
    int num_uniforms;
    uint32_t *words_copy;
    char error[256];
    int failed;
};

struct dxr_vm {
    const dxr_shader *sh;
    dxr_cell *cells;
    rt_ptr *ptrs;
    uint64_t runs;
};

/* ---- internal opcodes --------------------------------------------------------------- */
enum {
    I_END = 0, I_COPY, I_SETU, I_GATHER, I_LOAD_UBO, I_LOAD_EXT, I_PTR_STATIC, I_PTR_COPY, I_ACCESS, I_LOAD_PTR, I_STORE_PTR,
    I_JMP, I_BR, I_SWITCH, I_CALL, I_RET, I_KILL,
    I_FADD, I_FSUB, I_FMUL, I_FDIV, I_FMOD, I_FREM, I_FNEG,
    I_IADD, I_ISUB, I_IMUL, I_SDIV, I_UDIV, I_SMOD, I_UMOD, I_SREM, I_INEG,
    I_VTS, I_MTV, I_VTM, I_MTM, I_DOT, I_OUTER, I_TRANSPOSE,
    I_SHL, I_SHR, I_SAR, I_OR, I_XOR, I_AND, I_NOT,
    I_LOR, I_LAND, I_LNOT, I_SELECT,
    I_IEQ, I_INE, I_UGT, I_SGT, I_UGE, I_SGE, I_ULT, I_SLT, I_ULE, I_SLE,
    I_FEQ, I_FNE, I_FLT, I_FGT, I_FLE, I_FGE, I_FUEQ, I_FUNE, I_FULT, I_FUGT, I_FULE, I_FUGE,
    I_ANY, I_ALL, I_ISNAN, I_ISINF,
    I_F2U, I_F2S, I_S2F, I_U2F,
    I_VEXTRACT, I_VINSERT,
    I_DPDX, I_DPDY, I_FWIDTH,
    I_SAMPLE_IMPLICIT, I_SAMPLE_LOD, I_SAMPLE_GRAD, I_SAMPLE_PROJ, I_FETCH, I_QUERY_SIZE, I_QUERY_LEVELS,
    I_GLSL
};

#define NOCELL 0xFFFFFFFFu

static void sh_fail(dxr_shader *s, const char *fmt, ...) {
    if (s->failed) return;
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(s->error, sizeof(s->error), fmt, ap);
    va_end(ap);
    s->failed = 1;
}

static uint32_t alloc_cells(dxr_shader *s, uint32_t n) {
    if (n == 0) n = 1;
    if (s->ncells + n > s->cells_cap) {
        uint32_t cap = s->cells_cap ? s->cells_cap * 2 : 1024;
        while (cap < s->ncells + n) cap *= 2;
        dxr_cell *ni = (dxr_cell *)dxr_alloc(sizeof(dxr_cell) * cap);
        if (s->init) { memcpy(ni, s->init, sizeof(dxr_cell) * s->ncells); dxr_free(s->init); }
        s->init = ni;
        s->cells_cap = cap;
    }
    uint32_t r = s->ncells;
    s->ncells += n;
    return r;
}

static uint32_t push_extra(dxr_shader *s, uint32_t v) {
    if (s->nextra == s->extra_cap) {
        uint32_t cap = s->extra_cap ? s->extra_cap * 2 : 256;
        uint32_t *ne = (uint32_t *)dxr_alloc(sizeof(uint32_t) * cap);
        if (s->extra) { memcpy(ne, s->extra, sizeof(uint32_t) * s->nextra); dxr_free(s->extra); }
        s->extra = ne;
        s->extra_cap = cap;
    }
    s->extra[s->nextra] = v;
    return s->nextra++;
}

static uint32_t emit(dxr_shader *s, int op, int n, int aux, uint32_t d, uint32_t a, uint32_t b, uint32_t c) {
    if (s->ncode == s->code_cap) {
        uint32_t cap = s->code_cap ? s->code_cap * 2 : 256;
        dxr_op *nc = (dxr_op *)dxr_alloc(sizeof(dxr_op) * cap);
        if (s->code) { memcpy(nc, s->code, sizeof(dxr_op) * s->ncode); dxr_free(s->code); }
        s->code = nc;
        s->code_cap = cap;
    }
    dxr_op *o = &s->code[s->ncode];
    o->op = (uint16_t)op; o->n = (uint8_t)n; o->aux = (uint8_t)aux;
    o->d = d; o->a = a; o->b = b; o->c = c;
    return s->ncode++;
}

static uint32_t type_cells(const dxr_shader *s, uint32_t tid) { return tid < s->bound ? s->types[tid].cells : 0; }

/* Value cells of an id. Pointer typed ids have none. */
static uint32_t vcell(dxr_shader *s, uint32_t id) {
    if (id >= s->bound || (s->ids[id].kind != ID_VALUE)) {
        sh_fail(s, "id %u is not a value", id);
        return 0;
    }
    return s->ids[id].cell;
}

static uint32_t id_cells(const dxr_shader *s, uint32_t id) { return id < s->bound ? type_cells(s, s->ids[id].type) : 0; }

/* Operand broadcast: SPIR-V requires equal sizes, but be tolerant of scalars. */
static uint32_t operand(dxr_shader *s, uint32_t id, uint32_t n) {
    uint32_t c = vcell(s, id);
    uint32_t have = id_cells(s, id);
    if (have >= n || have != 1) return c;
    uint32_t t = alloc_cells(s, n);
    uint32_t e = s->nextra;
    for (uint32_t i = 0; i < n; i++) push_extra(s, c);
    emit(s, I_GATHER, (int)n, 0, t, 0, 0, e);
    return t;
}

static int const_uint(const dxr_shader *s, uint32_t id, uint32_t *out) {
    if (id >= s->bound || !s->ids[id].is_const || s->ids[id].kind != ID_VALUE) return 0;
    const sp_type *t = &s->types[s->ids[id].type];
    if (t->kind != TY_INT && t->kind != TY_BOOL) return 0;
    *out = s->init[s->ids[id].cell].u;
    return 1;
}

/* Step a static pointer through one access chain index. Returns 0 if not possible. */
static int sptr_step(const dxr_shader *s, sp_sptr *p, uint32_t idx) {
    const sp_type *t = &s->types[p->type];
    switch (t->kind) {
    case TY_STRUCT:
        if (idx >= t->count) return 0;
        if (p->kind == PK_CELL) p->off += t->member_cell[idx];
        else { p->off += t->member_off[idx]; if (t->member_mstride[idx]) p->mstride = (uint16_t)t->member_mstride[idx]; }
        p->type = t->members[idx];
        return 1;
    case TY_ARRAY:
        if (idx >= t->count) idx = t->count ? t->count - 1 : 0;
        if (p->kind == PK_CELL) p->off += idx * type_cells(s, t->elem);
        else p->off += idx * t->array_stride;
        p->type = t->elem;
        return 1;
    case TY_MAT:
        if (idx >= t->count) idx = t->count - 1;
        if (p->kind == PK_CELL) p->off += idx * type_cells(s, t->elem);
        else p->off += idx * (p->mstride ? p->mstride : 16u);
        p->type = t->elem;
        return 1;
    case TY_VEC:
        if (idx >= t->count) idx = t->count - 1;
        if (p->kind == PK_CELL) p->off += idx;
        else p->off += idx * 4;
        p->type = t->elem;
        return 1;
    default:
        return 0;
    }
}

/* Contiguous in std140 memory: scalars, vectors, and matrices whose stride equals the column size. */
static int ext_contiguous(const dxr_shader *s, uint32_t tid, uint32_t mstride) {
    const sp_type *t = &s->types[tid];
    if (t->kind == TY_BOOL || t->kind == TY_INT || t->kind == TY_FLOAT || t->kind == TY_VEC) return 1;
    if (t->kind == TY_MAT) return (mstride ? mstride : 16u) == s->types[t->elem].count * 4;
    return 0;
}

/* ---- derivative tag analysis ---------------------------------------------------------- *
 * dFdx/dFdy and implicit LOD need screen space derivatives. The rasterizer knows the exact
 * derivative of every interpolated input, so we track which values are verbatim copies of
 * fragment inputs (through loads, stores, call arguments, swizzles). Anything computed
 * falls back to a derivative of zero.                                                      */
#define TAG_OF(input, comp) ((uint16_t)(((input) << 2 | (comp)) + 1))

static int tags_equal(const uint16_t *a, const uint16_t *b) { return a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3]; }

static int var_meet(sp_id *var, const uint16_t tag[4]) {
    static const uint16_t none[4] = {0, 0, 0, 0};
    if (var->tag_state == 2) return 0;
    if (var->tag_state == 0) {
        if (tags_equal(tag, none)) { var->tag_state = 2; }
        else { memcpy(var->tag, tag, sizeof(var->tag)); var->tag_state = 1; }
        return 1;
    }
    if (tags_equal(var->tag, tag)) return 0;
    var->tag_state = 2;
    memset(var->tag, 0, sizeof(var->tag));
    return 1;
}

static int var_poison(sp_id *var) {
    if (var->tag_state == 2) return 0;
    var->tag_state = 2;
    memset(var->tag, 0, sizeof(var->tag));
    return 1;
}

static void analyse_tags(dxr_shader *s, const uint32_t *w, size_t nwords) {
    if (!s->fragment) return;
    /* seed: input variables */
    for (int i = 0; i < s->num_inputs; i++) {
        for (uint32_t id = 0; id < s->bound; id++) {
            sp_id *v = &s->ids[id];
            if (v->kind == ID_VAR && v->storage == 1 && v->cell == s->inputs[i].cell && !s->inputs[i].is_int && s->inputs[i].cells <= 4) {
                for (int c = 0; c < s->inputs[i].cells; c++) v->tag[c] = TAG_OF(i, c);
                v->tag_state = 1;
            }
        }
    }
    for (int iter = 0; iter < 16; iter++) {
        int changed = 0;
        size_t pos = 5;
        while (pos < nwords) {
            uint32_t op = w[pos] & 0xffff, wc = w[pos] >> 16;
            if (wc == 0) break;
            const uint32_t *in = w + pos;
            uint16_t nt[4] = {0, 0, 0, 0};
            uint32_t rid = 0;
            switch (op) {
            case 61: { /* OpLoad */
                rid = in[2];
                sp_id *p = &s->ids[in[3]];
                if ((p->kind == ID_VAR || p->kind == ID_PTR) && p->tag_state == 1) memcpy(nt, p->tag, sizeof(nt));
                break;
            }
            case 62: { /* OpStore */
                sp_id *p = &s->ids[in[1]];
                sp_id *v = &s->ids[in[2]];
                if (p->kind == ID_VAR && p->storage == 7) changed |= var_meet(p, v->tag);
                else if (p->kind == ID_PTR) {
                    if (p->sptr.valid && p->sptr.kind == PK_CELL) {
                        /* partial store through a static access chain: poison every function variable covering it */
                        for (uint32_t id = 0; id < s->bound; id++) {
                            sp_id *q = &s->ids[id];
                            if (q->kind == ID_VAR && q->storage == 7 && p->sptr.off >= q->cell &&
                                p->sptr.off < q->cell + type_cells(s, s->types[q->type].elem)) changed |= var_poison(q);
                        }
                    } else if (!p->param_stored) { p->param_stored = 1; changed = 1; }
                }
                break;
            }
            case 57: { /* OpFunctionCall */
                sp_id *f = &s->ids[in[3]];
                if (f->kind != ID_FUNC) break;
                sp_func *fn = &s->funcs[f->func];
                for (int i = 0; i < fn->num_params && (uint32_t)(4 + i) < wc; i++) {
                    sp_id *param = &s->ids[fn->params[i]];
                    sp_id *arg = &s->ids[in[4 + i]];
                    if (param->kind != ID_PTR) continue;
                    if (arg->kind == ID_VAR && arg->storage == 7) {
                        if (arg->tag_state == 1) changed |= var_meet(param, arg->tag);
                        else if (arg->tag_state == 2) changed |= var_poison(param);
                        if (param->param_stored) changed |= var_poison(arg);
                    } else if (arg->kind == ID_VAR && arg->storage == 1 && arg->tag_state == 1) {
                        changed |= var_meet(param, arg->tag);
                    } else {
                        changed |= var_poison(param);
                    }
                }
                break;
            }
            case 79: { /* OpVectorShuffle */
                rid = in[2];
                uint32_t n1 = id_cells(s, in[3]);
                for (uint32_t i = 0; i + 5 < wc && i < 4; i++) {
                    uint32_t c = in[5 + i];
                    if (c == 0xFFFFFFFFu) continue;
                    if (c < n1) { if (c < 4) nt[i] = s->ids[in[3]].tag[c]; }
                    else if (c - n1 < 4) nt[i] = s->ids[in[4]].tag[c - n1];
                }
                break;
            }
            case 81: /* OpCompositeExtract */
                rid = in[2];
                if (wc == 5 && s->types[s->ids[in[3]].type].kind == TY_VEC && in[4] < 4) nt[0] = s->ids[in[3]].tag[in[4]];
                break;
            case 80: { /* OpCompositeConstruct */
                rid = in[2];
                if (s->types[in[1]].kind != TY_VEC) break;
                uint32_t k = 0;
                for (uint32_t i = 3; i < wc; i++) {
                    uint32_t c = id_cells(s, in[i]);
                    for (uint32_t j = 0; j < c && k < 4; j++, k++) nt[k] = j < 4 ? s->ids[in[i]].tag[j] : 0;
                }
                break;
            }
            case 83: /* OpCopyObject */
                rid = in[2];
                memcpy(nt, s->ids[in[3]].tag, sizeof(nt));
                break;
            default:
                break;
            }
            if (rid && rid < s->bound && s->ids[rid].kind == ID_VALUE && !tags_equal(s->ids[rid].tag, nt)) {
                memcpy(s->ids[rid].tag, nt, sizeof(nt));
                changed = 1;
            }
            pos += wc;
        }
        if (!changed) break;
    }
}

/* ---- decoding --------------------------------------------------------------------------- */
typedef struct member_deco { uint32_t type, member, deco, value; } member_deco;

static int op_has_no_result(uint32_t op) {
    switch (op) {
    case 0: case 8: case 317: case 62: case 63: case 64: case 249: case 250: case 251: case 252: case 253: case 254: case 255:
    case 246: case 247: case 56: case 4416: case 99: case 330:
        return 1;
    default:
        return 0;
    }
}

typedef struct phi_rec { uint32_t block, result, tmp, n; const uint32_t *pairs; uint32_t npairs; } phi_rec;
typedef struct fixup { uint32_t index; uint8_t field; uint8_t in_extra; uint32_t label; } fixup;

typedef struct emit_ctx {
    dxr_shader *s;
    phi_rec *phis; uint32_t nphis, phis_cap;
    fixup *fix; uint32_t nfix, fix_cap;
    uint32_t *label_pc;
    uint32_t cur_block;
    struct { uint32_t op_index; uint32_t func; } calls[512];
    uint32_t ncalls;
} emit_ctx;

static void add_fixup(emit_ctx *e, uint32_t index, int field, int in_extra, uint32_t label) {
    if (e->nfix == e->fix_cap) {
        uint32_t cap = e->fix_cap ? e->fix_cap * 2 : 128;
        fixup *nf = (fixup *)dxr_alloc(sizeof(fixup) * cap);
        if (e->fix) { memcpy(nf, e->fix, sizeof(fixup) * e->nfix); dxr_free(e->fix); }
        e->fix = nf;
        e->fix_cap = cap;
    }
    e->fix[e->nfix].index = index;
    e->fix[e->nfix].field = (uint8_t)field;
    e->fix[e->nfix].in_extra = (uint8_t)in_extra;
    e->fix[e->nfix].label = label;
    e->nfix++;
}

static int block_has_phis_from(const emit_ctx *e, uint32_t target, uint32_t pred) {
    for (uint32_t i = 0; i < e->nphis; i++) {
        if (e->phis[i].block != target) continue;
        for (uint32_t k = 0; k < e->phis[i].npairs; k++) if (e->phis[i].pairs[2 * k + 1] == pred) return 1;
    }
    return 0;
}

/* Parallel copies for the edge pred -> target. */
static void emit_phi_copies(emit_ctx *e, uint32_t target, uint32_t pred) {
    dxr_shader *s = e->s;
    for (int phase = 0; phase < 2; phase++) {
        for (uint32_t i = 0; i < e->nphis; i++) {
            phi_rec *p = &e->phis[i];
            if (p->block != target) continue;
            for (uint32_t k = 0; k < p->npairs; k++) {
                if (p->pairs[2 * k + 1] != pred) continue;
                if (phase == 0) emit(s, I_COPY, 0, 0, p->tmp, vcell(s, p->pairs[2 * k]), p->n, 0);
                else emit(s, I_COPY, 0, 0, vcell(s, p->result), p->tmp, p->n, 0);
                break;
            }
        }
    }
}

/* I_COPY keeps its length in field b (lengths may exceed 255 for arrays). */
static void emit_copy(dxr_shader *s, uint32_t d, uint32_t a, uint32_t n) { if (n) emit(s, I_COPY, 0, 0, d, a, n, 0); }

/* Makes sure the pointer id has a runtime representation in its slot. */
static uint32_t ptr_slot(dxr_shader *s, uint32_t id) {
    sp_id *p = &s->ids[id];
    if (p->kind != ID_VAR && p->kind != ID_PTR) { sh_fail(s, "id %u is not a pointer", id); return 0; }
    if (p->sptr.valid) {
        uint32_t e = push_extra(s, p->sptr.type);
        push_extra(s, p->sptr.mstride);
        emit(s, I_PTR_STATIC, 0, p->sptr.kind, p->slot, p->sptr.off, p->sptr.binding, e);
    }
    return p->slot;
}

static int find_uniform(const dxr_shader *s, const char *name, int fallback) {
    if (name) for (int i = 0; i < s->num_uniforms; i++) if (s->uniform_names[i] && strcmp(s->uniform_names[i], name) == 0) return i;
    return fallback;
}

static void decode_function(emit_ctx *e, const uint32_t *w, sp_func *fn) {
    dxr_shader *s = e->s;
    /* collect phis of this function */
    e->nphis = 0;
    uint32_t block = 0;
    for (uint32_t pos = fn->first_word; pos < fn->end_word; pos += w[pos] >> 16) {
        uint32_t op = w[pos] & 0xffff, wc = w[pos] >> 16;
        if (op == 248) block = w[pos + 1];
        if (op == 245) {
            if (e->nphis == e->phis_cap) {
                uint32_t cap = e->phis_cap ? e->phis_cap * 2 : 32;
                phi_rec *np = (phi_rec *)dxr_alloc(sizeof(phi_rec) * cap);
                if (e->phis) { memcpy(np, e->phis, sizeof(phi_rec) * e->nphis); dxr_free(e->phis); }
                e->phis = np;
                e->phis_cap = cap;
            }
            phi_rec *p = &e->phis[e->nphis++];
            p->block = block; p->result = w[pos + 2]; p->n = type_cells(s, w[pos + 1]);
            p->tmp = alloc_cells(s, p->n);
            p->pairs = w + pos + 3; p->npairs = (wc - 3) / 2;
        }
    }
    fn->pc = s->ncode;
    for (uint32_t pos = fn->first_word; pos < fn->end_word && !s->failed; pos += w[pos] >> 16) {
        const uint32_t *in = w + pos;
        uint32_t op = in[0] & 0xffff, wc = in[0] >> 16;
        uint32_t rt = wc > 2 ? in[1] : 0, rid = wc > 2 ? in[2] : 0;
        uint32_t n = (!op_has_no_result(op) && op != 248 && rt < s->bound) ? type_cells(s, rt) : 0;
        uint32_t d = (!op_has_no_result(op) && op != 248 && rid < s->bound && s->ids[rid].kind == ID_VALUE) ? s->ids[rid].cell : 0;
#define BIN(I) emit(s, I, (int)n, 0, d, operand(s, in[3], n), operand(s, in[4], n), 0)
#define UNA(I) emit(s, I, (int)n, 0, d, operand(s, in[3], n), 0, 0)
        switch (op) {
        case 8: case 317: case 55: case 59: case 246: case 247: case 245: case 1: case 54: case 56:
            break; /* OpLine, OpNoLine, params/vars (pass 1), merges, phis (edges), undef */
        case 248: { /* OpLabel */
            e->label_pc[in[1]] = s->ncode;
            e->cur_block = in[1];
            break;
        }
        case 61: { /* OpLoad */
            sp_id *p = &s->ids[in[3]];
            if (p->sptr.valid) {
                switch (p->sptr.kind) {
                case PK_CELL: emit_copy(s, d, p->sptr.off, n); break;
                case PK_SAMPLER: emit(s, I_SETU, 0, 0, d, p->sptr.binding, 0, 0); break;
                case PK_UBO: case PK_PUSH:
                    if (ext_contiguous(s, rt, p->sptr.mstride)) emit(s, I_LOAD_UBO, (int)n, p->sptr.kind, d, p->sptr.binding, p->sptr.off, 0);
                    else {
                        uint32_t ex = push_extra(s, rt);
                        push_extra(s, p->sptr.mstride);
                        emit(s, I_LOAD_EXT, 0, p->sptr.kind, d, p->sptr.binding, p->sptr.off, ex);
                    }
                    break;
                default: sh_fail(s, "load from unsupported pointer"); break;
                }
            } else {
                emit(s, I_LOAD_PTR, 0, 0, d, p->slot, rt, 0);
            }
            break;
        }
        case 62: { /* OpStore */
            sp_id *p = &s->ids[in[1]];
            uint32_t vn = id_cells(s, in[2]);
            if (p->sptr.valid) {
                if (p->sptr.kind == PK_CELL) emit_copy(s, p->sptr.off, vcell(s, in[2]), vn);
                else sh_fail(s, "store to read only storage");
            } else {
                emit(s, I_STORE_PTR, 0, 0, 0, p->slot, vcell(s, in[2]), vn);
            }
            break;
        }
        case 65: case 66: { /* OpAccessChain / OpInBoundsAccessChain */
            sp_id *r = &s->ids[rid];
            sp_id *base = &s->ids[in[3]];
            int all_const = base->sptr.valid;
            sp_sptr sp = base->sptr;
            for (uint32_t i = 4; i < wc && all_const; i++) {
                uint32_t v;
                if (!const_uint(s, in[i], &v) || !sptr_step(s, &sp, v)) all_const = 0;
            }
            if (all_const) { r->sptr = sp; break; }
            r->sptr.valid = 0;
            uint32_t bslot = ptr_slot(s, in[3]);
            uint32_t ex = push_extra(s, wc - 4);
            for (uint32_t i = 4; i < wc; i++) {
                uint32_t v;
                if (const_uint(s, in[i], &v)) { push_extra(s, 1); push_extra(s, v); }
                else { push_extra(s, 0); push_extra(s, vcell(s, in[i])); }
            }
            emit(s, I_ACCESS, 0, 0, r->slot, bslot, 0, ex);
            break;
        }
        case 57: { /* OpFunctionCall */
            sp_id *f = &s->ids[in[3]];
            if (f->kind != ID_FUNC) { sh_fail(s, "call to non function %u", in[3]); break; }
            sp_func *callee = &s->funcs[f->func];
            for (int i = 0; i < callee->num_params && (uint32_t)(4 + i) < wc; i++) {
                sp_id *param = &s->ids[callee->params[i]];
                if (param->kind == ID_PTR) {
                    uint32_t aslot = ptr_slot(s, in[4 + i]);
                    emit(s, I_PTR_COPY, 0, 0, param->slot, aslot, 0, 0);
                } else {
                    emit_copy(s, param->cell, vcell(s, in[4 + i]), type_cells(s, param->type));
                }
            }
            if (e->ncalls < 512) { e->calls[e->ncalls].op_index = emit(s, I_CALL, 0, 0, 0, 0, 0, 0); e->calls[e->ncalls].func = f->func; e->ncalls++; }
            else sh_fail(s, "too many calls");
            if (s->types[rt].kind != TY_VOID) emit_copy(s, d, callee->ret_cell, n);
            break;
        }
        case 253: emit(s, I_RET, 0, 0, 0, 0, 0, 0); break;                   /* OpReturn */
        case 254: emit_copy(s, fn->ret_cell, vcell(s, in[1]), id_cells(s, in[1])); emit(s, I_RET, 0, 0, 0, 0, 0, 0); break;
        case 252: case 4416: emit(s, I_KILL, 0, 0, 0, 0, 0, 0); break;      /* OpKill / OpTerminateInvocation */
        case 255: emit(s, I_KILL, 0, 0, 0, 0, 0, 0); break;                  /* OpUnreachable */
        case 249: { /* OpBranch */
            emit_phi_copies(e, in[1], e->cur_block);
            add_fixup(e, emit(s, I_JMP, 0, 0, 0, 0, 0, 0), 1, 0, in[1]);
            break;
        }
        case 250: { /* OpBranchConditional */
            uint32_t br = emit(s, I_BR, 0, 0, 0, vcell(s, in[1]), 0, 0);
            for (int side = 0; side < 2; side++) {
                uint32_t target = in[2 + side];
                if (block_has_phis_from(e, target, e->cur_block)) {
                    if (side == 0) s->code[br].b = s->ncode; else s->code[br].c = s->ncode;
                    emit_phi_copies(e, target, e->cur_block);
                    add_fixup(e, emit(s, I_JMP, 0, 0, 0, 0, 0, 0), 1, 0, target);
                } else {
                    add_fixup(e, br, side == 0 ? 2 : 3, 0, target);
                }
            }
            break;
        }
        case 251: { /* OpSwitch */
            uint32_t ncase = (wc - 3) / 2;
            uint32_t ex = push_extra(s, ncase);
            uint32_t def = push_extra(s, 0);
            for (uint32_t i = 0; i < ncase; i++) { push_extra(s, in[3 + 2 * i]); push_extra(s, 0); }
            emit(s, I_SWITCH, 0, 0, 0, vcell(s, in[1]), 0, ex);
            for (uint32_t i = 0; i <= ncase; i++) {
                uint32_t target = i == 0 ? in[2] : in[4 + 2 * (i - 1)];
                uint32_t where = i == 0 ? def : ex + 2 + 2 * (i - 1) + 1;
                if (block_has_phis_from(e, target, e->cur_block)) {
                    s->extra[where] = s->ncode;
                    emit_phi_copies(e, target, e->cur_block);
                    add_fixup(e, emit(s, I_JMP, 0, 0, 0, 0, 0, 0), 1, 0, target);
                } else {
                    add_fixup(e, where, 0, 1, target);
                }
            }
            break;
        }
        /* ---- composites ---- */
        case 79: { /* OpVectorShuffle */
            uint32_t n1 = id_cells(s, in[3]);
            uint32_t ex = s->nextra;
            for (uint32_t i = 5; i < wc; i++) {
                uint32_t c = in[i];
                if (c == 0xFFFFFFFFu) push_extra(s, 0);
                else if (c < n1) push_extra(s, vcell(s, in[3]) + c);
                else push_extra(s, vcell(s, in[4]) + (c - n1));
            }
            emit(s, I_GATHER, (int)(wc - 5), 0, d, 0, 0, ex);
            break;
        }
        case 80: { /* OpCompositeConstruct */
            uint32_t off = 0;
            for (uint32_t i = 3; i < wc; i++) {
                uint32_t c = id_cells(s, in[i]);
                emit_copy(s, d + off, vcell(s, in[i]), c);
                off += c;
            }
            /* vector splat written as a single scalar constituent */
            if (off < n && wc == 4 && id_cells(s, in[3]) == 1) for (uint32_t i = 1; i < n; i++) emit_copy(s, d + i, vcell(s, in[3]), 1);
            break;
        }
        case 81: case 82: { /* OpCompositeExtract / OpCompositeInsert */
            int insert = op == 82;
            uint32_t comp = insert ? in[4] : in[3];
            sp_sptr sp = {1, PK_CELL, 0, s->ids[comp].type, 0, 0};
            for (uint32_t i = insert ? 5 : 4; i < wc; i++) if (!sptr_step(s, &sp, in[i])) sh_fail(s, "bad composite index");
            if (insert) {
                emit_copy(s, d, vcell(s, comp), n);
                emit_copy(s, d + sp.off, vcell(s, in[3]), id_cells(s, in[3]));
            } else {
                emit_copy(s, d, vcell(s, comp) + sp.off, n);
            }
            break;
        }
        case 77: emit(s, I_VEXTRACT, (int)id_cells(s, in[3]), 0, d, vcell(s, in[3]), vcell(s, in[4]), 0); break;
        case 78: emit(s, I_VINSERT, (int)n, 0, d, vcell(s, in[3]), vcell(s, in[4]), vcell(s, in[5])); break;
        case 83: case 124: case 113: case 114: case 115: case 400:  /* CopyObject, Bitcast, U/S/FConvert, CopyLogical */
            emit_copy(s, d, vcell(s, in[3]), n);
            break;
        case 84: { /* OpTranspose: result cols x rows */
            const sp_type *t = &s->types[rt];
            emit(s, I_TRANSPOSE, (int)s->types[t->elem].count, (int)t->count, d, vcell(s, in[3]), 0, 0);
            break;
        }
        /* ---- arithmetic ---- */
        case 127: UNA(I_FNEG); break;
        case 126: UNA(I_INEG); break;
        case 129: BIN(I_FADD); break;
        case 131: BIN(I_FSUB); break;
        case 133: BIN(I_FMUL); break;
        case 136: BIN(I_FDIV); break;
        case 141: BIN(I_FMOD); break;
        case 140: BIN(I_FREM); break;
        case 128: BIN(I_IADD); break;
        case 130: BIN(I_ISUB); break;
        case 132: BIN(I_IMUL); break;
        case 135: BIN(I_SDIV); break;
        case 134: BIN(I_UDIV); break;
        case 139: BIN(I_SMOD); break;
        case 137: BIN(I_UMOD); break;
        case 138: BIN(I_SREM); break;
        case 142: case 143: emit(s, I_VTS, (int)n, 0, d, vcell(s, in[3]), vcell(s, in[4]), 0); break; /* VectorTimesScalar, MatrixTimesScalar */
        case 145: { /* OpMatrixTimesVector */
            const sp_type *m = &s->types[s->ids[in[3]].type];
            emit(s, I_MTV, (int)s->types[m->elem].count, (int)m->count, d, vcell(s, in[3]), vcell(s, in[4]), 0);
            break;
        }
        case 144: { /* OpVectorTimesMatrix */
            const sp_type *m = &s->types[s->ids[in[4]].type];
            emit(s, I_VTM, (int)s->types[m->elem].count, (int)m->count, d, vcell(s, in[3]), vcell(s, in[4]), 0);
            break;
        }
        case 146: { /* OpMatrixTimesMatrix */
            const sp_type *ma = &s->types[s->ids[in[3]].type];
            const sp_type *mb = &s->types[s->ids[in[4]].type];
            emit(s, I_MTM, (int)s->types[ma->elem].count, (int)(ma->count | (mb->count << 4)), d, vcell(s, in[3]), vcell(s, in[4]), 0);
            break;
        }
        case 147: { /* OpOuterProduct: result cols = |b|, rows = |a| */
            emit(s, I_OUTER, (int)id_cells(s, in[3]), (int)id_cells(s, in[4]), d, vcell(s, in[3]), vcell(s, in[4]), 0);
            break;
        }
        case 148: emit(s, I_DOT, (int)id_cells(s, in[3]), 0, d, vcell(s, in[3]), vcell(s, in[4]), 0); break;
        /* ---- bits ---- */
        case 194: BIN(I_SHR); break;
        case 195: BIN(I_SAR); break;
        case 196: BIN(I_SHL); break;
        case 197: BIN(I_OR); break;
        case 198: BIN(I_XOR); break;
        case 199: BIN(I_AND); break;
        case 200: UNA(I_NOT); break;
        /* ---- logic / comparison ---- */
        case 164: BIN(I_IEQ); break;   /* LogicalEqual */
        case 165: BIN(I_INE); break;   /* LogicalNotEqual */
        case 166: BIN(I_LOR); break;
        case 167: BIN(I_LAND); break;
        case 168: UNA(I_LNOT); break;
        case 169: emit(s, I_SELECT, (int)n, id_cells(s, in[3]) == 1 && n > 1, d, vcell(s, in[3]), vcell(s, in[4]), vcell(s, in[5])); break;
        case 170: BIN(I_IEQ); break;
        case 171: BIN(I_INE); break;
        case 172: BIN(I_UGT); break;
        case 173: BIN(I_SGT); break;
        case 174: BIN(I_UGE); break;
        case 175: BIN(I_SGE); break;
        case 176: BIN(I_ULT); break;
        case 177: BIN(I_SLT); break;
        case 178: BIN(I_ULE); break;
        case 179: BIN(I_SLE); break;
        case 180: BIN(I_FEQ); break;
        case 181: BIN(I_FUEQ); break;
        case 182: BIN(I_FNE); break;
        case 183: BIN(I_FUNE); break;
        case 184: BIN(I_FLT); break;
        case 185: BIN(I_FULT); break;
        case 186: BIN(I_FGT); break;
        case 187: BIN(I_FUGT); break;
        case 188: BIN(I_FLE); break;
        case 189: BIN(I_FULE); break;
        case 190: BIN(I_FGE); break;
        case 191: BIN(I_FUGE); break;
        case 154: emit(s, I_ANY, (int)id_cells(s, in[3]), 0, d, vcell(s, in[3]), 0, 0); break;
        case 155: emit(s, I_ALL, (int)id_cells(s, in[3]), 0, d, vcell(s, in[3]), 0, 0); break;
        case 156: UNA(I_ISNAN); break;
        case 157: UNA(I_ISINF); break;
        /* ---- conversions ---- */
        case 109: UNA(I_F2U); break;
        case 110: UNA(I_F2S); break;
        case 111: UNA(I_S2F); break;
        case 112: UNA(I_U2F); break;
        /* ---- derivatives ---- */
        case 207: case 208: case 209: case 210: case 211: case 212: case 213: case 214: case 215: {
            int which = (op - 207) % 3; /* x, y, fwidth (also Fine/Coarse variants) */
            uint32_t ex = s->nextra;
            const sp_id *v = &s->ids[in[3]];
            for (uint32_t i = 0; i < n; i++) push_extra(s, i < 4 && v->tag[i] ? (uint32_t)(v->tag[i] - 1) : NOCELL);
            emit(s, which == 0 ? I_DPDX : which == 1 ? I_DPDY : I_FWIDTH, (int)n, 0, d, 0, 0, ex);
            s->flags |= DXR_SHF_DERIVATIVES;
            break;
        }
        /* ---- images ---- */
        case 86: emit_copy(s, d, vcell(s, in[3]), 1); break;   /* OpSampledImage(image, sampler) */
        case 100: emit_copy(s, d, vcell(s, in[3]), 1); break;  /* OpImage */
        case 87: case 88: { /* OpImageSampleImplicitLod / ExplicitLod */
            const sp_type *sit = &s->types[s->ids[in[3]].type];
            const sp_type *it = sit->kind == TY_SAMPLED_IMAGE ? &s->types[sit->elem] : sit;
            int dim = it->dim;
            uint32_t mask = wc > 5 ? in[5] : 0;
            uint32_t coord = vcell(s, in[4]);
            if (op == 87 || (mask & 6) == 0) {
                const sp_id *cv = &s->ids[in[4]];
                uint32_t ex = push_extra(s, cv->tag[0] ? (uint32_t)(cv->tag[0] - 1) : NOCELL);
                push_extra(s, cv->tag[1] ? (uint32_t)(cv->tag[1] - 1) : NOCELL);
                push_extra(s, (mask & 1) ? vcell(s, in[6]) : NOCELL);
                emit(s, I_SAMPLE_IMPLICIT, 0, dim, d, vcell(s, in[3]), coord, ex);
                if (cv->tag[0]) s->flags |= DXR_SHF_DERIVATIVES;
            } else if (mask & 2) {
                emit(s, I_SAMPLE_LOD, 0, dim, d, vcell(s, in[3]), coord, vcell(s, in[6]));
            } else {
                uint32_t ex = push_extra(s, vcell(s, in[6]));
                push_extra(s, vcell(s, in[7]));
                emit(s, I_SAMPLE_GRAD, 0, dim, d, vcell(s, in[3]), coord, ex);
            }
            break;
        }
        case 91: case 92: { /* OpImageSampleProjImplicitLod / ExplicitLod: divide by the last coordinate component */
            uint32_t mask = wc > 5 ? in[5] : 0;
            emit(s, I_SAMPLE_PROJ, (int)id_cells(s, in[4]), 0, d, vcell(s, in[3]), vcell(s, in[4]), (op == 92 && (mask & 2)) ? vcell(s, in[6]) : NOCELL);
            break;
        }
        case 95: { /* OpImageFetch */
            uint32_t mask = wc > 5 ? in[5] : 0;
            emit(s, I_FETCH, (int)id_cells(s, in[4]), 0, d, vcell(s, in[3]), vcell(s, in[4]), (mask & 2) ? vcell(s, in[6]) : NOCELL);
            break;
        }
        case 103: emit(s, I_QUERY_SIZE, (int)n, 0, d, vcell(s, in[3]), vcell(s, in[4]), 0); break;
        case 104: emit(s, I_QUERY_SIZE, (int)n, 0, d, vcell(s, in[3]), NOCELL, 0); break;
        case 106: emit(s, I_QUERY_LEVELS, 0, 0, d, vcell(s, in[3]), 0, 0); break;
        case 12: { /* OpExtInst */
            if (!s->ids[in[3]].ext_glsl) break; /* non semantic debug info */
            uint32_t g = in[4];
            uint32_t a = wc > 5 ? in[5] : 0, b = wc > 6 ? in[6] : 0, c = wc > 7 ? in[7] : 0;
            switch (g) {
            case 66: case 33: /* Length, Determinant: scalar result, n = operand cells */
                emit(s, I_GLSL, (int)id_cells(s, a), (int)g, d, vcell(s, a), 0, 0);
                break;
            case 67: /* Distance */
                emit(s, I_GLSL, (int)id_cells(s, a), (int)g, d, vcell(s, a), vcell(s, b), 0);
                break;
            case 72: /* Refract: eta is scalar */
                emit(s, I_GLSL, (int)n, (int)g, d, vcell(s, a), vcell(s, b), vcell(s, c));
                break;
            case 35: { /* Modf: whole part through pointer */
                sp_id *p = &s->ids[b];
                if (!p->sptr.valid || p->sptr.kind != PK_CELL) { sh_fail(s, "modf with dynamic pointer"); break; }
                emit(s, I_GLSL, (int)n, 35, d, vcell(s, a), p->sptr.off, 0);
                break;
            }
            case 36: /* ModfStruct: {fract, whole} */
                emit(s, I_GLSL, (int)(n / 2), 35, d, vcell(s, a), d + n / 2, 0);
                break;
            default:
                if (g < 1 || g > 81 || (g >= 51 && g <= 65) || g == 76 || g == 77 || g == 78) { sh_fail(s, "unsupported GLSL.std.450 instruction %u", g); break; }
                emit(s, I_GLSL, (int)n, (int)g, d, a ? operand(s, a, n) : 0, b ? operand(s, b, n) : 0, c ? operand(s, c, n) : 0);
                break;
            }
            break;
        }
        default:
            sh_fail(s, "unsupported SPIR-V opcode %u", op);
            break;
        }
#undef BIN
#undef UNA
    }
    /* resolve branch targets */
    for (uint32_t i = 0; i < e->nfix; i++) {
        fixup *f = &e->fix[i];
        uint32_t pc = e->label_pc[f->label];
        if (f->in_extra) s->extra[f->index] = pc;
        else if (f->field == 1) s->code[f->index].a = pc;
        else if (f->field == 2) s->code[f->index].b = pc;
        else s->code[f->index].c = pc;
    }
    e->nfix = 0;
}

static void init_const(dxr_shader *s, const uint32_t *in, uint32_t op, uint32_t wc) {
    uint32_t rt = in[1], rid = in[2];
    sp_id *id = &s->ids[rid];
    id->kind = ID_VALUE; id->is_const = 1; id->type = rt;
    uint32_t n = type_cells(s, rt);
    id->cell = alloc_cells(s, n);
    switch (op) {
    case 41: case 48: s->init[id->cell].u = 1; break;
    case 42: case 49: s->init[id->cell].u = 0; break;
    case 43: case 50: s->init[id->cell].u = in[3]; break;
    case 44: case 51: {
        uint32_t off = 0;
        for (uint32_t i = 3; i < wc; i++) {
            uint32_t c = id_cells(s, in[i]);
            if (off + c > n) break;
            memcpy(&s->init[id->cell + off], &s->init[s->ids[in[i]].cell], sizeof(dxr_cell) * c);
            off += c;
        }
        break;
    }
    default: break; /* OpConstantNull / OpUndef: zeros */
    }
}

dxr_shader *dxr_shader_create(const uint32_t *words, size_t nwords, int fragment, const char *const *uniform_names, int num_uniforms) {
    if (!words || nwords < 5 || words[0] != 0x07230203u) { dxr_set_error("not a SPIR-V module"); return NULL; }
    dxr_shader *s = (dxr_shader *)dxr_alloc(sizeof(dxr_shader));
    if (!s) return NULL;
    s->fragment = fragment;
    s->bound = words[3];
    s->uniform_names = uniform_names;
    s->num_uniforms = num_uniforms;
    s->types = (sp_type *)dxr_alloc(sizeof(sp_type) * s->bound);
    s->ids = (sp_id *)dxr_alloc(sizeof(sp_id) * s->bound);
    s->words_copy = (uint32_t *)dxr_alloc_raw(nwords * 4);
    memcpy(s->words_copy, words, nwords * 4);
    const uint32_t *w = s->words_copy;
    s->position_cell = s->fragdepth_cell = s->fragcoord_cell = s->frontfacing_cell = s->vertexindex_cell = s->instanceindex_cell = NOCELL;
    alloc_cells(s, 4); /* cell 0..3: always zero (undef operands) */
    for (uint32_t i = 0; i < s->bound; i++) { s->ids[i].location = -1; s->ids[i].binding = -1; s->ids[i].builtin = -1; }
    uint64_t h = 1469598103934665603ull;
    for (size_t i = 0; i < nwords; i++) { h ^= w[i]; h *= 1099511628211ull; }
    s->hash = h;

    member_deco *mdeco = (member_deco *)dxr_alloc(sizeof(member_deco) * (nwords / 4 + 1));
    uint32_t nmdeco = 0;
    sp_func *cur = NULL;
    uint32_t entry = 0;
    /* ---- pass 1: declarations, types, constants, variables, cell allocation ---- */
    for (size_t pos = 5; pos < nwords && !s->failed;) {
        const uint32_t *in = w + pos;
        uint32_t op = in[0] & 0xffff, wc = in[0] >> 16;
        if (wc == 0 || pos + wc > nwords) { sh_fail(s, "truncated module"); break; }
        switch (op) {
        case 5: if (in[1] < s->bound) s->ids[in[1]].name = (const char *)(in + 2); break;
        case 11: s->ids[in[1]].kind = ID_EXTSET; s->ids[in[1]].ext_glsl = strcmp((const char *)(in + 2), "GLSL.std.450") == 0; break;
        case 15: if ((in[1] == 0 && !fragment) || (in[1] == 4 && fragment) || !entry) entry = in[2]; break;
        case 71: {
            sp_id *t = &s->ids[in[1]];
            uint32_t v = wc > 3 ? in[3] : 0;
            switch (in[2]) {
            case 30: t->location = (int32_t)v; break;
            case 33: t->binding = (int32_t)v; break;
            case 11: t->builtin = (int32_t)v; break;
            case 14: t->flat = 1; break;
            case 6: t->array_stride = v; break;
            default: break;
            }
            break;
        }
        case 72:
            mdeco[nmdeco].type = in[1]; mdeco[nmdeco].member = in[2]; mdeco[nmdeco].deco = in[3]; mdeco[nmdeco].value = wc > 4 ? in[4] : 0;
            nmdeco++;
            break;
        case 19: s->ids[in[1]].kind = ID_TYPE; s->types[in[1]].kind = TY_VOID; break;
        case 20: s->ids[in[1]].kind = ID_TYPE; s->types[in[1]].kind = TY_BOOL; s->types[in[1]].cells = 1; break;
        case 21: s->ids[in[1]].kind = ID_TYPE; s->types[in[1]].kind = TY_INT; s->types[in[1]].cells = 1; s->types[in[1]].is_signed = (uint8_t)in[3];
            if (in[2] != 32) sh_fail(s, "only 32 bit integers are supported"); break;
        case 22: s->ids[in[1]].kind = ID_TYPE; s->types[in[1]].kind = TY_FLOAT; s->types[in[1]].cells = 1;
            if (in[2] != 32) sh_fail(s, "only 32 bit floats are supported"); break;
        case 23: { sp_type *t = &s->types[in[1]]; s->ids[in[1]].kind = ID_TYPE; t->kind = TY_VEC; t->elem = in[2]; t->count = in[3]; t->cells = in[3]; break; }
        case 24: { sp_type *t = &s->types[in[1]]; s->ids[in[1]].kind = ID_TYPE; t->kind = TY_MAT; t->elem = in[2]; t->count = in[3]; t->cells = in[3] * s->types[in[2]].cells; break; }
        case 25: { sp_type *t = &s->types[in[1]]; s->ids[in[1]].kind = ID_TYPE; t->kind = TY_IMAGE; t->elem = in[2]; t->dim = (uint8_t)in[3]; t->cells = 1; break; }
        case 26: s->ids[in[1]].kind = ID_TYPE; s->types[in[1]].kind = TY_SAMPLER; s->types[in[1]].cells = 1; break;
        case 27: { sp_type *t = &s->types[in[1]]; s->ids[in[1]].kind = ID_TYPE; t->kind = TY_SAMPLED_IMAGE; t->elem = in[2]; t->dim = s->types[in[2]].dim; t->cells = 1; break; }
        case 28: case 29: {
            sp_type *t = &s->types[in[1]];
            s->ids[in[1]].kind = ID_TYPE; t->kind = TY_ARRAY; t->elem = in[2];
            t->count = 1;
            if (op == 28 && !const_uint(s, in[3], &t->count)) sh_fail(s, "array length is not constant");
            t->cells = t->count * s->types[in[2]].cells;
            t->array_stride = s->ids[in[1]].array_stride;
            break;
        }
        case 30: {
            sp_type *t = &s->types[in[1]];
            s->ids[in[1]].kind = ID_TYPE; t->kind = TY_STRUCT; t->count = wc - 2;
            t->members = (uint32_t *)dxr_alloc(sizeof(uint32_t) * 4 * (t->count + 1));
            t->member_cell = t->members + t->count + 1;
            t->member_off = t->member_cell + t->count + 1;
            t->member_mstride = t->member_off + t->count + 1;
            t->builtin_position_member = -1;
            uint32_t cells = 0;
            for (uint32_t i = 0; i < t->count; i++) { t->members[i] = in[2 + i]; t->member_cell[i] = cells; cells += s->types[in[2 + i]].cells; }
            t->cells = cells;
            for (uint32_t i = 0; i < nmdeco; i++) {
                if (mdeco[i].type != in[1] || mdeco[i].member >= t->count) continue;
                if (mdeco[i].deco == 35) t->member_off[mdeco[i].member] = mdeco[i].value;
                else if (mdeco[i].deco == 7) t->member_mstride[mdeco[i].member] = mdeco[i].value;
                else if (mdeco[i].deco == 11 && mdeco[i].value == 0) t->builtin_position_member = (int32_t)mdeco[i].member;
            }
            break;
        }
        case 32: { sp_type *t = &s->types[in[1]]; s->ids[in[1]].kind = ID_TYPE; t->kind = TY_PTR; t->storage = (uint8_t)in[2]; t->elem = in[3]; break; }
        case 33: s->ids[in[1]].kind = ID_TYPE; s->types[in[1]].kind = TY_FUNC; break;
        case 41: case 42: case 43: case 44: case 46: case 48: case 49: case 50: case 51: case 1:
            init_const(s, in, op, wc);
            break;
        case 59: { /* OpVariable */
            sp_id *v = &s->ids[in[2]];
            const sp_type *pt = &s->types[in[1]];
            const sp_type *vt = &s->types[pt->elem];
            v->kind = ID_VAR; v->type = in[1]; v->storage = (uint8_t)in[3];
            v->slot = s->nslots++;
            v->sptr.valid = 1; v->sptr.type = pt->elem;
            switch (in[3]) {
            case 1: case 3: case 6: case 7: { /* Input, Output, Private, Function */
                v->cell = alloc_cells(s, vt->cells);
                v->sptr.kind = PK_CELL; v->sptr.off = v->cell;
                if (wc > 4) {
                    memcpy(&s->init[v->cell], &s->init[s->ids[in[4]].cell], sizeof(dxr_cell) * vt->cells);
                    uint32_t *nr = (uint32_t *)dxr_alloc(sizeof(uint32_t) * 2 * (s->nreinit + 1));
                    if (s->reinit) { memcpy(nr, s->reinit, sizeof(uint32_t) * 2 * s->nreinit); dxr_free(s->reinit); }
                    s->reinit = nr;
                    s->reinit[2 * s->nreinit] = v->cell; s->reinit[2 * s->nreinit + 1] = vt->cells;
                    s->nreinit++;
                }
                if (in[3] == 1 || in[3] == 3) {
                    int is_out = in[3] == 3;
                    if (vt->kind == TY_STRUCT && vt->builtin_position_member >= 0) {
                        if (is_out) s->position_cell = v->cell + vt->member_cell[vt->builtin_position_member];
                    } else if (v->builtin >= 0) {
                        switch (v->builtin) {
                        case 15: s->fragcoord_cell = v->cell; s->flags |= DXR_SHF_READS_FRAGCOORD; break;
                        case 17: s->frontfacing_cell = v->cell; break;
                        case 22: s->fragdepth_cell = v->cell; s->flags |= DXR_SHF_FRAGDEPTH; break;
                        case 42: case 5: s->vertexindex_cell = v->cell; break;
                        case 43: case 6: s->instanceindex_cell = v->cell; break;
                        case 0: if (is_out) s->position_cell = v->cell; break;
                        default: break;
                        }
                    } else if (v->location >= 0) {
                        dxr_shader_io *io = is_out ? &s->outputs[s->num_outputs] : &s->inputs[s->num_inputs];
                        if ((is_out ? s->num_outputs : s->num_inputs) < 32) {
                            const sp_type *base = vt;
                            while (base->kind == TY_ARRAY || base->kind == TY_MAT) base = &s->types[base->elem];
                            io->vec = (uint8_t)(base->kind == TY_VEC ? base->count : 1);
                            if (base->kind == TY_VEC) base = &s->types[base->elem];
                            io->location = (uint16_t)v->location;
                            io->cells = (uint16_t)vt->cells;
                            io->cell = v->cell;
                            io->flat = v->flat;
                            io->is_int = base->kind == TY_INT || base->kind == TY_BOOL;
                            if (is_out) s->num_outputs++; else s->num_inputs++;
                        }
                    }
                }
                break;
            }
            case 2: /* Uniform block */
                v->sptr.kind = PK_UBO;
                v->sptr.binding = (uint32_t)find_uniform(s, s->ids[pt->elem].name, find_uniform(s, v->name, v->binding >= 0 ? v->binding : 0));
                break;
            case 0: /* UniformConstant: samplers, texel buffers */
                v->sptr.kind = PK_SAMPLER;
                v->sptr.binding = (uint32_t)find_uniform(s, v->name, v->binding >= 0 ? v->binding : 0);
                break;
            case 9:
                v->sptr.kind = PK_PUSH;
                break;
            default:
                sh_fail(s, "unsupported storage class %u", in[3]);
                break;
            }
            break;
        }
        case 54: { /* OpFunction */
            if (s->num_funcs >= 64) { sh_fail(s, "too many functions"); break; }
            cur = &s->funcs[s->num_funcs];
            memset(cur, 0, sizeof(*cur));
            cur->id = in[2]; cur->ret_type = in[1]; cur->first_word = (uint32_t)pos;
            cur->ret_cell = alloc_cells(s, type_cells(s, in[1]));
            s->ids[in[2]].kind = ID_FUNC; s->ids[in[2]].func = (uint32_t)s->num_funcs;
            s->num_funcs++;
            break;
        }
        case 56: if (cur) { cur->end_word = (uint32_t)pos; cur = NULL; } break;
        case 248: s->ids[in[1]].kind = ID_LABEL; break;
        default:
            if (cur && !op_has_no_result(op) && wc > 2 && in[2] < s->bound && in[1] < s->bound) {
                sp_id *id = &s->ids[in[2]];
                const sp_type *t = &s->types[in[1]];
                id->type = in[1];
                if (t->kind == TY_PTR) {
                    id->kind = ID_PTR;
                    id->slot = s->nslots++;
                } else if (t->kind != TY_VOID) {
                    id->kind = ID_VALUE;
                    id->cell = alloc_cells(s, t->cells);
                }
                if (op == 55 && cur->num_params < 16) cur->params[cur->num_params++] = in[2];
            }
            if (op == 252 || op == 4416) s->flags |= DXR_SHF_DISCARD;
            break;
        }
        pos += wc;
    }
    dxr_free(mdeco);
    if (!s->failed && !entry) sh_fail(s, "no entry point");
    /* static access chains must be known before tag analysis; do a light pre-pass */
    if (!s->failed) {
        for (int f = 0; f < s->num_funcs; f++) {
            for (uint32_t pos = s->funcs[f].first_word; pos < s->funcs[f].end_word; pos += w[pos] >> 16) {
                const uint32_t *in = w + pos;
                uint32_t op = in[0] & 0xffff, wc = in[0] >> 16;
                if (op != 65 && op != 66) continue;
                sp_sptr sp = s->ids[in[3]].sptr;
                int ok = sp.valid;
                for (uint32_t i = 4; i < wc && ok; i++) { uint32_t v; if (!const_uint(s, in[i], &v) || !sptr_step(s, &sp, v)) ok = 0; }
                if (ok) s->ids[in[2]].sptr = sp;
            }
        }
        analyse_tags(s, w, nwords);
    }
    /* ---- pass 2: code generation ---- */
    if (!s->failed) {
        emit_ctx e;
        memset(&e, 0, sizeof(e));
        e.s = s;
        e.label_pc = (uint32_t *)dxr_alloc(sizeof(uint32_t) * s->bound);
        emit(s, I_END, 0, 0, 0, 0, 0, 0); /* pc 0: safe landing */
        for (int f = 0; f < s->num_funcs && !s->failed; f++) decode_function(&e, w, &s->funcs[f]);
        for (uint32_t i = 0; i < e.ncalls; i++) s->code[e.calls[i].op_index].a = s->funcs[e.calls[i].func].pc;
        if (!s->failed) {
            if (s->ids[entry].kind != ID_FUNC) sh_fail(s, "entry point is not a function");
            else s->entry_func = s->ids[entry].func;
        }
        dxr_free(e.label_pc);
        dxr_free(e.phis);
        dxr_free(e.fix);
    }
    if (s->failed) {
        dxr_set_error("SPIR-V: %s", s->error);
        dxr_shader_destroy(s);
        return NULL;
    }
    for (int i = 0; i < 32; i++) s->input_slot[i] = 0xFFFF;
    return s;
}

void dxr_shader_destroy(dxr_shader *s) {
    if (!s) return;
    if (s->types) for (uint32_t i = 0; i < s->bound; i++) if (s->types[i].members) dxr_free(s->types[i].members);
    dxr_free(s->types); dxr_free(s->ids); dxr_free(s->code); dxr_free(s->extra); dxr_free(s->init);
    dxr_free(s->reinit); dxr_free(s->words_copy);
    dxr_free(s);
}

int dxr_shader_num_inputs(const dxr_shader *s) { return s->num_inputs; }
int dxr_shader_num_outputs(const dxr_shader *s) { return s->num_outputs; }
const dxr_shader_io *dxr_shader_inputs(const dxr_shader *s) { return s->inputs; }
const dxr_shader_io *dxr_shader_outputs(const dxr_shader *s) { return s->outputs; }
int dxr_shader_flags(const dxr_shader *s) { return s->flags; }
uint32_t dxr_shader_position_cell(const dxr_shader *s) { return s->position_cell; }
uint32_t dxr_shader_fragdepth_cell(const dxr_shader *s) { return s->fragdepth_cell; }
uint32_t dxr_shader_num_cells(const dxr_shader *s) { return s->ncells; }
uint64_t dxr_shader_hash(const dxr_shader *s) { return s->hash; }
void dxr_shader_set_input_slot(dxr_shader *s, int input_index, int slot) { if (input_index >= 0 && input_index < 32) s->input_slot[input_index] = (uint16_t)slot; }
void dxr_shader_finalize(dxr_shader *s) { (void)s; }

dxr_vm *dxr_vm_create(const dxr_shader *s) {
    dxr_vm *vm = (dxr_vm *)dxr_alloc(sizeof(dxr_vm));
    if (!vm) return NULL;
    vm->sh = s;
    vm->cells = (dxr_cell *)dxr_alloc(sizeof(dxr_cell) * (s->ncells + 16));
    vm->ptrs = (rt_ptr *)dxr_alloc(sizeof(rt_ptr) * (s->nslots + 1));
    memcpy(vm->cells, s->init, sizeof(dxr_cell) * s->ncells);
    return vm;
}

void dxr_vm_destroy(dxr_vm *vm) {
    if (!vm) return;
    dxr_free(vm->cells);
    dxr_free(vm->ptrs);
    dxr_free(vm);
}

dxr_cell *dxr_vm_cells(dxr_vm *vm) { return vm->cells; }
uint64_t dxr_vm_ops(dxr_vm *vm) { return vm->runs; }

/* ---- interpreter -------------------------------------------------------------------------- */
static void load_ext(const dxr_shader *s, uint32_t tid, const uint8_t *mem, const uint8_t *end, uint32_t mstride, dxr_cell *dst) {
    const sp_type *t = &s->types[tid];
    switch (t->kind) {
    case TY_BOOL: case TY_INT: case TY_FLOAT: case TY_VEC:
        if (mem && mem + t->cells * 4 <= end) memcpy(dst, mem, t->cells * 4);
        else memset(dst, 0, t->cells * 4);
        break;
    case TY_MAT: {
        uint32_t rows = s->types[t->elem].count, st = mstride ? mstride : 16u;
        for (uint32_t c = 0; c < t->count; c++) {
            const uint8_t *m = mem ? mem + c * st : NULL;
            if (m && m + rows * 4 <= end) memcpy(dst + c * rows, m, rows * 4);
            else memset(dst + c * rows, 0, rows * 4);
        }
        break;
    }
    case TY_ARRAY: {
        uint32_t ec = s->types[t->elem].cells;
        for (uint32_t i = 0; i < t->count; i++) load_ext(s, t->elem, mem ? mem + i * t->array_stride : NULL, end, mstride, dst + i * ec);
        break;
    }
    case TY_STRUCT:
        for (uint32_t i = 0; i < t->count; i++)
            load_ext(s, t->members[i], mem ? mem + t->member_off[i] : NULL, end, t->member_mstride[i] ? t->member_mstride[i] : mstride, dst + t->member_cell[i]);
        break;
    default:
        break;
    }
}

DXR_INLINE int32_t f2s(float f) { return f != f ? 0 : f >= 2147483520.0f ? 2147483647 : f <= -2147483648.0f ? (int32_t)0x80000000 : (int32_t)f; }
DXR_INLINE uint32_t f2u(float f) { return !(f > 0.0f) ? 0u : f >= 4294967040.0f ? 0xFFFFFFFFu : (uint32_t)f; }
DXR_INLINE float fmodg(float a, float b) { return a - b * floorf(a / b); }

static float det2(const float *m) { return m[0] * m[3] - m[2] * m[1]; }
static float det3(const float *m) {
    return m[0] * (m[4] * m[8] - m[7] * m[5]) - m[3] * (m[1] * m[8] - m[7] * m[2]) + m[6] * (m[1] * m[5] - m[4] * m[2]);
}
static int inv4(const float *m, float *inv) {
    float t[16];
    t[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
    t[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
    t[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
    t[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
    t[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
    t[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
    t[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
    t[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
    t[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
    t[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
    t[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
    t[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
    t[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
    t[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
    t[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
    t[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
    float det = m[0] * t[0] + m[1] * t[4] + m[2] * t[8] + m[3] * t[12];
    if (det == 0.0f) { memset(inv, 0, 16 * sizeof(float)); return 0; }
    det = 1.0f / det;
    for (int i = 0; i < 16; i++) inv[i] = t[i] * det;
    return 1;
}
static float det4(const float *m) {
    float a = m[10] * m[15] - m[14] * m[11], b = m[6] * m[15] - m[14] * m[7], c = m[6] * m[11] - m[10] * m[7];
    float d = m[2] * m[15] - m[14] * m[3], e = m[2] * m[11] - m[10] * m[3], f = m[2] * m[7] - m[6] * m[3];
    return m[0] * (m[5] * a - m[9] * b + m[13] * c) - m[4] * (m[1] * a - m[9] * d + m[13] * e) +
           m[8] * (m[1] * b - m[5] * d + m[13] * f) - m[12] * (m[1] * c - m[5] * e + m[9] * f);
}

static void glsl_op(int g, int n, dxr_cell *D, const dxr_cell *A, const dxr_cell *B, const dxr_cell *C) {
#define FOR for (int i = 0; i < n; i++)
    switch (g) {
    case 1: FOR D[i].f = roundf(A[i].f); break;
    case 2: FOR D[i].f = rintf(A[i].f); break;
    case 3: FOR D[i].f = truncf(A[i].f); break;
    case 4: FOR D[i].f = fabsf(A[i].f); break;
    case 5: FOR D[i].i = A[i].i < 0 ? -A[i].i : A[i].i; break;
    case 6: FOR D[i].f = A[i].f > 0.0f ? 1.0f : A[i].f < 0.0f ? -1.0f : 0.0f; break;
    case 7: FOR D[i].i = A[i].i > 0 ? 1 : A[i].i < 0 ? -1 : 0; break;
    case 8: FOR D[i].f = floorf(A[i].f); break;
    case 9: FOR D[i].f = ceilf(A[i].f); break;
    case 10: FOR D[i].f = A[i].f - floorf(A[i].f); break;
    case 11: FOR D[i].f = A[i].f * 0.017453292519943295f; break;
    case 12: FOR D[i].f = A[i].f * 57.29577951308232f; break;
    case 13: FOR D[i].f = sinf(A[i].f); break;
    case 14: FOR D[i].f = cosf(A[i].f); break;
    case 15: FOR D[i].f = tanf(A[i].f); break;
    case 16: FOR D[i].f = asinf(A[i].f); break;
    case 17: FOR D[i].f = acosf(A[i].f); break;
    case 18: FOR D[i].f = atanf(A[i].f); break;
    case 19: FOR D[i].f = sinhf(A[i].f); break;
    case 20: FOR D[i].f = coshf(A[i].f); break;
    case 21: FOR D[i].f = tanhf(A[i].f); break;
    case 22: FOR D[i].f = asinhf(A[i].f); break;
    case 23: FOR D[i].f = acoshf(A[i].f); break;
    case 24: FOR D[i].f = atanhf(A[i].f); break;
    case 25: FOR D[i].f = atan2f(A[i].f, B[i].f); break;
    case 26: FOR D[i].f = powf(A[i].f, B[i].f); break;
    case 27: FOR D[i].f = expf(A[i].f); break;
    case 28: FOR D[i].f = logf(A[i].f); break;
    case 29: FOR D[i].f = exp2f(A[i].f); break;
    case 30: FOR D[i].f = log2f(A[i].f); break;
    case 31: FOR D[i].f = sqrtf(A[i].f); break;
    case 32: FOR D[i].f = 1.0f / sqrtf(A[i].f); break;
    case 33: D[0].f = n == 4 ? det2(&A[0].f) : n == 9 ? det3(&A[0].f) : det4(&A[0].f); break;
    case 34: {
        float m[16], r[16];
        memcpy(m, A, sizeof(float) * (size_t)n);
        if (n == 4) {
            float det = det2(m), id = det != 0.0f ? 1.0f / det : 0.0f;
            r[0] = m[3] * id; r[1] = -m[1] * id; r[2] = -m[2] * id; r[3] = m[0] * id;
        } else if (n == 9) {
            float det = det3(m), id = det != 0.0f ? 1.0f / det : 0.0f;
            r[0] = (m[4] * m[8] - m[7] * m[5]) * id; r[1] = (m[7] * m[2] - m[1] * m[8]) * id; r[2] = (m[1] * m[5] - m[4] * m[2]) * id;
            r[3] = (m[6] * m[5] - m[3] * m[8]) * id; r[4] = (m[0] * m[8] - m[6] * m[2]) * id; r[5] = (m[3] * m[2] - m[0] * m[5]) * id;
            r[6] = (m[3] * m[7] - m[6] * m[4]) * id; r[7] = (m[6] * m[1] - m[0] * m[7]) * id; r[8] = (m[0] * m[4] - m[3] * m[1]) * id;
        } else {
            inv4(m, r);
        }
        memcpy(D, r, sizeof(float) * (size_t)n);
        break;
    }
    case 35: FOR { float w = truncf(A[i].f); D[i].f = A[i].f - w; ((dxr_cell *)B)[i].f = w; } break;
    case 37: case 79: FOR D[i].f = B[i].f < A[i].f ? B[i].f : A[i].f; break;
    case 38: FOR D[i].u = B[i].u < A[i].u ? B[i].u : A[i].u; break;
    case 39: FOR D[i].i = B[i].i < A[i].i ? B[i].i : A[i].i; break;
    case 40: case 80: FOR D[i].f = A[i].f < B[i].f ? B[i].f : A[i].f; break;
    case 41: FOR D[i].u = A[i].u < B[i].u ? B[i].u : A[i].u; break;
    case 42: FOR D[i].i = A[i].i < B[i].i ? B[i].i : A[i].i; break;
    case 43: case 81: FOR { float v = A[i].f < B[i].f ? B[i].f : A[i].f; D[i].f = v > C[i].f ? C[i].f : v; } break;
    case 44: FOR { uint32_t v = A[i].u < B[i].u ? B[i].u : A[i].u; D[i].u = v > C[i].u ? C[i].u : v; } break;
    case 45: FOR { int32_t v = A[i].i < B[i].i ? B[i].i : A[i].i; D[i].i = v > C[i].i ? C[i].i : v; } break;
    case 46: FOR D[i].f = A[i].f * (1.0f - C[i].f) + B[i].f * C[i].f; break;
    case 47: FOR D[i].i = (int32_t)((float)A[i].i * (1.0f - C[i].f) + (float)B[i].i * C[i].f); break;
    case 48: FOR D[i].f = B[i].f < A[i].f ? 0.0f : 1.0f; break;
    case 49: FOR { float t = (C[i].f - A[i].f) / (B[i].f - A[i].f); t = t < 0.0f ? 0.0f : t > 1.0f ? 1.0f : t; D[i].f = t * t * (3.0f - 2.0f * t); } break;
    case 50: FOR D[i].f = A[i].f * B[i].f + C[i].f; break;
    case 66: { float sum = 0.0f; FOR sum += A[i].f * A[i].f; D[0].f = sqrtf(sum); break; }
    case 67: { float sum = 0.0f; FOR { float df = A[i].f - B[i].f; sum += df * df; } D[0].f = sqrtf(sum); break; }
    case 68: {
        float x = A[1].f * B[2].f - A[2].f * B[1].f, y = A[2].f * B[0].f - A[0].f * B[2].f, z = A[0].f * B[1].f - A[1].f * B[0].f;
        D[0].f = x; D[1].f = y; D[2].f = z;
        break;
    }
    case 69: { float sum = 0.0f; FOR sum += A[i].f * A[i].f; float inv = 1.0f / sqrtf(sum); FOR D[i].f = A[i].f * inv; break; }
    case 70: { float dt = 0.0f; FOR dt += C[i].f * B[i].f; FOR D[i].f = dt < 0.0f ? A[i].f : -A[i].f; break; }
    case 71: { float dt = 0.0f; FOR dt += B[i].f * A[i].f; FOR D[i].f = A[i].f - 2.0f * dt * B[i].f; break; }
    case 72: {
        float eta = C[0].f, dt = 0.0f;
        FOR dt += B[i].f * A[i].f;
        float k = 1.0f - eta * eta * (1.0f - dt * dt);
        if (k < 0.0f) { FOR D[i].f = 0.0f; }
        else { float sk = sqrtf(k); FOR D[i].f = eta * A[i].f - (eta * dt + sk) * B[i].f; }
        break;
    }
    case 73: FOR D[i].i = A[i].u ? __builtin_ctz(A[i].u) : -1; break;
    case 74: FOR { int32_t v = A[i].i; if (v < 0) v = ~v; D[i].i = v ? 31 - __builtin_clz((uint32_t)v) : -1; } break;
    case 75: FOR D[i].i = A[i].u ? 31 - __builtin_clz(A[i].u) : -1; break;
    default: FOR D[i].u = 0; break;
    }
#undef FOR
}

#define VM_STACK 64
#define VM_LOOP_BUDGET 200000

int dxr_vm_run(dxr_vm *vm, const dxr_frag_ctx *ctx) {
    const dxr_shader *s = vm->sh;
    dxr_cell *R = vm->cells;
    const dxr_op *code = s->code;
    const uint32_t *X = s->extra;
    uint32_t stack[VM_STACK];
    int sp = 0;
    int budget = VM_LOOP_BUDGET;
    vm->runs++;
    for (uint32_t i = 0; i < s->nreinit; i++) memcpy(&R[s->reinit[2 * i]], &s->init[s->reinit[2 * i]], sizeof(dxr_cell) * s->reinit[2 * i + 1]);
    if (s->fragment) {
        if (s->fragcoord_cell != NOCELL) memcpy(&R[s->fragcoord_cell], ctx->frag_coord, 16);
        if (s->frontfacing_cell != NOCELL) R[s->frontfacing_cell].u = (uint32_t)ctx->front_facing;
        if (s->fragdepth_cell != NOCELL) R[s->fragdepth_cell].f = ctx->frag_coord[2];
    } else {
        if (s->vertexindex_cell != NOCELL) R[s->vertexindex_cell].i = ctx->vertex_index;
        if (s->instanceindex_cell != NOCELL) R[s->instanceindex_cell].i = ctx->instance_index;
    }
    uint32_t pc = s->funcs[s->entry_func].pc;
#define BINF(EXPR) { const dxr_cell *a = &R[o->a], *b = &R[o->b]; dxr_cell *d = &R[o->d]; for (int i = 0; i < o->n; i++) d[i].f = (EXPR); } break
#define BINI(EXPR) { const dxr_cell *a = &R[o->a], *b = &R[o->b]; dxr_cell *d = &R[o->d]; for (int i = 0; i < o->n; i++) d[i].i = (EXPR); } break
#define BINU(EXPR) { const dxr_cell *a = &R[o->a], *b = &R[o->b]; dxr_cell *d = &R[o->d]; for (int i = 0; i < o->n; i++) d[i].u = (EXPR); } break
#define UNF(EXPR) { const dxr_cell *a = &R[o->a]; dxr_cell *d = &R[o->d]; for (int i = 0; i < o->n; i++) d[i].f = (EXPR); } break
#define UNU(EXPR) { const dxr_cell *a = &R[o->a]; dxr_cell *d = &R[o->d]; for (int i = 0; i < o->n; i++) d[i].u = (EXPR); } break
    for (;;) {
        const dxr_op *o = &code[pc++];
        switch (o->op) {
        case I_END: return 1;
        case I_COPY: {
            uint32_t n = o->b;
            if (n == 1) R[o->d] = R[o->a];
            else if (n == 4) { R[o->d] = R[o->a]; R[o->d + 1] = R[o->a + 1]; R[o->d + 2] = R[o->a + 2]; R[o->d + 3] = R[o->a + 3]; }
            else memmove(&R[o->d], &R[o->a], sizeof(dxr_cell) * n);
            break;
        }
        case I_SETU: R[o->d].u = o->a; break;
        case I_GATHER: { dxr_cell *d = &R[o->d]; const uint32_t *e = &X[o->c]; for (int i = 0; i < o->n; i++) d[i] = R[e[i]]; break; }
        case I_LOAD_UBO: {
            const uint8_t *mem; uint32_t size;
            if (o->aux == PK_PUSH) { mem = ctx->push_constants; size = mem ? 256 : 0; }
            else { const dxr_uniform_binding *u = &ctx->uniforms[o->a]; mem = u->data; size = u->size; }
            uint32_t bytes = (uint32_t)o->n * 4;
            if (DXR_LIKELY(mem && o->b + bytes <= size)) memcpy(&R[o->d], mem + o->b, bytes);
            else memset(&R[o->d], 0, bytes);
            break;
        }
        case I_LOAD_EXT: {
            const uint8_t *mem; uint32_t size;
            if (o->aux == PK_PUSH) { mem = ctx->push_constants; size = mem ? 256 : 0; }
            else { const dxr_uniform_binding *u = &ctx->uniforms[o->a]; mem = u->data; size = u->size; }
            load_ext(s, X[o->c], mem ? mem + o->b : NULL, mem ? mem + size : NULL, X[o->c + 1], &R[o->d]);
            break;
        }
        case I_PTR_STATIC: {
            rt_ptr *p = &vm->ptrs[o->d];
            p->kind = o->aux; p->type = X[o->c]; p->mstride = (uint16_t)X[o->c + 1];
            p->cell = o->a; p->mem = NULL; p->end = NULL;
            if (o->aux == PK_UBO) {
                const dxr_uniform_binding *u = &ctx->uniforms[o->b];
                p->kind = PK_MEM; p->mem = u->data ? u->data + o->a : NULL; p->end = u->data ? u->data + u->size : NULL;
            } else if (o->aux == PK_PUSH) {
                p->kind = PK_MEM; p->mem = ctx->push_constants ? ctx->push_constants + o->a : NULL; p->end = ctx->push_constants ? ctx->push_constants + 256 : NULL;
            } else if (o->aux == PK_SAMPLER) {
                p->cell = o->b;
            }
            break;
        }
        case I_PTR_COPY: vm->ptrs[o->d] = vm->ptrs[o->a]; break;
        case I_ACCESS: {
            rt_ptr p = vm->ptrs[o->a];
            const uint32_t *e = &X[o->c];
            uint32_t ni = e[0];
            for (uint32_t i = 0; i < ni; i++) {
                uint32_t idx = e[1 + 2 * i] ? e[2 + 2 * i] : R[e[2 + 2 * i]].u;
                const sp_type *t = &s->types[p.type];
                if (t->kind != TY_STRUCT && t->count && idx >= t->count) idx = t->count - 1;
                switch (t->kind) {
                case TY_STRUCT:
                    if (idx >= t->count) idx = 0;
                    if (p.kind == PK_CELL) p.cell += t->member_cell[idx];
                    else if (p.mem) { p.mem += t->member_off[idx]; if (t->member_mstride[idx]) p.mstride = (uint16_t)t->member_mstride[idx]; }
                    p.type = t->members[idx];
                    break;
                case TY_ARRAY:
                    if (p.kind == PK_CELL) p.cell += idx * s->types[t->elem].cells; else if (p.mem) p.mem += idx * t->array_stride;
                    p.type = t->elem;
                    break;
                case TY_MAT:
                    if (p.kind == PK_CELL) p.cell += idx * s->types[t->elem].cells; else if (p.mem) p.mem += idx * (p.mstride ? p.mstride : 16u);
                    p.type = t->elem;
                    break;
                case TY_VEC:
                    if (p.kind == PK_CELL) p.cell += idx; else if (p.mem) p.mem += idx * 4;
                    p.type = t->elem;
                    break;
                default: break;
                }
            }
            vm->ptrs[o->d] = p;
            break;
        }
        case I_LOAD_PTR: {
            const rt_ptr *p = &vm->ptrs[o->a];
            uint32_t n = s->types[o->b].cells;
            if (p->kind == PK_CELL) memmove(&R[o->d], &R[p->cell], sizeof(dxr_cell) * n);
            else if (p->kind == PK_SAMPLER) R[o->d].u = p->cell;
            else if (p->kind == PK_MEM) load_ext(s, o->b, p->mem, p->end, p->mstride, &R[o->d]);
            else memset(&R[o->d], 0, sizeof(dxr_cell) * n);
            break;
        }
        case I_STORE_PTR: {
            const rt_ptr *p = &vm->ptrs[o->a];
            if (p->kind == PK_CELL) memmove(&R[p->cell], &R[o->b], sizeof(dxr_cell) * o->c);
            break;
        }
        case I_JMP:
            if (o->a < pc && --budget <= 0) return 0;
            pc = o->a;
            break;
        case I_BR: {
            uint32_t t = R[o->a].u ? o->b : o->c;
            if (t < pc && --budget <= 0) return 0;
            pc = t;
            break;
        }
        case I_SWITCH: {
            const uint32_t *e = &X[o->c];
            uint32_t sel = R[o->a].u, t = e[1];
            for (uint32_t i = 0; i < e[0]; i++) if (e[2 + 2 * i] == sel) { t = e[3 + 2 * i]; break; }
            if (t < pc && --budget <= 0) return 0;
            pc = t;
            break;
        }
        case I_CALL:
            if (sp >= VM_STACK) return 0;
            stack[sp++] = pc;
            pc = o->a;
            break;
        case I_RET:
            if (sp == 0) return 1;
            pc = stack[--sp];
            break;
        case I_KILL: return 0;
        case I_FADD: BINF(a[i].f + b[i].f);
        case I_FSUB: BINF(a[i].f - b[i].f);
        case I_FMUL: BINF(a[i].f * b[i].f);
        case I_FDIV: BINF(a[i].f / b[i].f);
        case I_FMOD: BINF(fmodg(a[i].f, b[i].f));
        case I_FREM: BINF(fmodf(a[i].f, b[i].f));
        case I_FNEG: UNF(-a[i].f);
        case I_IADD: BINU(a[i].u + b[i].u);
        case I_ISUB: BINU(a[i].u - b[i].u);
        case I_IMUL: BINU(a[i].u * b[i].u);
        case I_SDIV: BINI(b[i].i == 0 || (a[i].i == INT32_MIN && b[i].i == -1) ? 0 : a[i].i / b[i].i);
        case I_UDIV: BINU(b[i].u == 0 ? 0 : a[i].u / b[i].u);
        case I_SREM: BINI(b[i].i == 0 || b[i].i == -1 ? 0 : a[i].i % b[i].i);
        case I_SMOD: BINI(b[i].i == 0 || b[i].i == -1 ? 0 : ((a[i].i % b[i].i) + b[i].i) % b[i].i);
        case I_UMOD: BINU(b[i].u == 0 ? 0 : a[i].u % b[i].u);
        case I_INEG: UNU(0u - a[i].u);
        case I_VTS: { const dxr_cell *a = &R[o->a]; float k = R[o->b].f; dxr_cell *d = &R[o->d]; for (int i = 0; i < o->n; i++) d[i].f = a[i].f * k; break; }
        case I_MTV: { /* n rows, aux cols */
            const dxr_cell *m = &R[o->a], *v = &R[o->b]; dxr_cell *d = &R[o->d];
            float r[4] = {0, 0, 0, 0};
            for (int c = 0; c < o->aux; c++) { float k = v[c].f; for (int i = 0; i < o->n; i++) r[i] += m[c * o->n + i].f * k; }
            for (int i = 0; i < o->n; i++) d[i].f = r[i];
            break;
        }
        case I_VTM: {
            const dxr_cell *v = &R[o->a], *m = &R[o->b]; dxr_cell *d = &R[o->d];
            for (int c = 0; c < o->aux; c++) { float sum = 0.0f; for (int i = 0; i < o->n; i++) sum += v[i].f * m[c * o->n + i].f; d[c].f = sum; }
            break;
        }
        case I_MTM: {
            int rows = o->n, ca = o->aux & 15, cb = o->aux >> 4;
            const dxr_cell *A = &R[o->a], *B = &R[o->b]; dxr_cell *d = &R[o->d];
            for (int c = 0; c < cb; c++)
                for (int r = 0; r < rows; r++) {
                    float sum = 0.0f;
                    for (int k = 0; k < ca; k++) sum += A[k * rows + r].f * B[c * ca + k].f;
                    d[c * rows + r].f = sum;
                }
            break;
        }
        case I_DOT: { const dxr_cell *a = &R[o->a], *b = &R[o->b]; float sum = 0.0f; for (int i = 0; i < o->n; i++) sum += a[i].f * b[i].f; R[o->d].f = sum; break; }
        case I_OUTER: { const dxr_cell *a = &R[o->a], *b = &R[o->b]; dxr_cell *d = &R[o->d]; for (int c = 0; c < o->aux; c++) for (int r = 0; r < o->n; r++) d[c * o->n + r].f = a[r].f * b[c].f; break; }
        case I_TRANSPOSE: { /* result: aux cols x n rows; source: n cols x aux rows */
            const dxr_cell *a = &R[o->a]; dxr_cell *d = &R[o->d];
            for (int c = 0; c < o->aux; c++) for (int r = 0; r < o->n; r++) d[c * o->n + r] = a[r * o->aux + c];
            break;
        }
        case I_SHL: BINU(a[i].u << (b[i].u & 31));
        case I_SHR: BINU(a[i].u >> (b[i].u & 31));
        case I_SAR: BINI(a[i].i >> (b[i].u & 31));
        case I_OR: BINU(a[i].u | b[i].u);
        case I_XOR: BINU(a[i].u ^ b[i].u);
        case I_AND: BINU(a[i].u & b[i].u);
        case I_NOT: UNU(~a[i].u);
        case I_LOR: BINU((a[i].u | b[i].u) != 0);
        case I_LAND: BINU(a[i].u && b[i].u);
        case I_LNOT: UNU(!a[i].u);
        case I_SELECT: {
            const dxr_cell *cnd = &R[o->a], *b = &R[o->b], *c = &R[o->c]; dxr_cell *d = &R[o->d];
            if (o->aux) { const dxr_cell *src = cnd[0].u ? b : c; for (int i = 0; i < o->n; i++) d[i] = src[i]; }
            else for (int i = 0; i < o->n; i++) d[i] = cnd[i].u ? b[i] : c[i];
            break;
        }
        case I_IEQ: BINU(a[i].u == b[i].u);
        case I_INE: BINU(a[i].u != b[i].u);
        case I_UGT: BINU(a[i].u > b[i].u);
        case I_SGT: BINU(a[i].i > b[i].i);
        case I_UGE: BINU(a[i].u >= b[i].u);
        case I_SGE: BINU(a[i].i >= b[i].i);
        case I_ULT: BINU(a[i].u < b[i].u);
        case I_SLT: BINU(a[i].i < b[i].i);
        case I_ULE: BINU(a[i].u <= b[i].u);
        case I_SLE: BINU(a[i].i <= b[i].i);
        case I_FEQ: BINU(a[i].f == b[i].f);
        case I_FNE: BINU(a[i].f < b[i].f || a[i].f > b[i].f);
        case I_FLT: BINU(a[i].f < b[i].f);
        case I_FGT: BINU(a[i].f > b[i].f);
        case I_FLE: BINU(a[i].f <= b[i].f);
        case I_FGE: BINU(a[i].f >= b[i].f);
        case I_FUEQ: BINU(!(a[i].f < b[i].f || a[i].f > b[i].f));
        case I_FUNE: BINU(!(a[i].f == b[i].f));
        case I_FULT: BINU(!(a[i].f >= b[i].f));
        case I_FUGT: BINU(!(a[i].f <= b[i].f));
        case I_FULE: BINU(!(a[i].f > b[i].f));
        case I_FUGE: BINU(!(a[i].f < b[i].f));
        case I_ANY: { uint32_t r = 0; for (int i = 0; i < o->n; i++) r |= R[o->a + i].u; R[o->d].u = r != 0; break; }
        case I_ALL: { uint32_t r = 1; for (int i = 0; i < o->n; i++) r &= R[o->a + i].u != 0; R[o->d].u = r; break; }
        case I_ISNAN: UNU(a[i].f != a[i].f);
        case I_ISINF: UNU(isinf(a[i].f) != 0);
        case I_F2U: UNU(f2u(a[i].f));
        case I_F2S: UNU((uint32_t)f2s(a[i].f));
        case I_S2F: UNF((float)a[i].i);
        case I_U2F: UNF((float)a[i].u);
        case I_VEXTRACT: { uint32_t idx = R[o->b].u; if (idx >= o->n) idx = o->n - 1u; R[o->d] = R[o->a + idx]; break; }
        case I_VINSERT: { uint32_t idx = R[o->c].u; memmove(&R[o->d], &R[o->a], sizeof(dxr_cell) * o->n); if (idx < o->n) R[o->d + idx] = R[o->b]; break; }
        case I_DPDX: case I_DPDY: case I_FWIDTH: {
            const uint32_t *e = &X[o->c]; dxr_cell *d = &R[o->d];
            for (int i = 0; i < o->n; i++) {
                float v = 0.0f;
                if (e[i] != NOCELL && ctx->dvdx) {
                    uint32_t slot = (uint32_t)s->input_slot[e[i] >> 2] + (e[i] & 3);
                    if (slot < DXR_MAX_VARYING_CELLS)
                        v = o->op == I_DPDX ? ctx->dvdx[slot] : o->op == I_DPDY ? ctx->dvdy[slot] : fabsf(ctx->dvdx[slot]) + fabsf(ctx->dvdy[slot]);
                }
                d[i].f = v;
            }
            break;
        }
        case I_SAMPLE_IMPLICIT: {
            uint32_t bi = R[o->a].u;
            const dxr_uniform_binding *u = bi < DXR_MAX_UNIFORMS ? &ctx->uniforms[bi] : NULL;
            const uint32_t *e = &X[o->c];
            const dxr_cell *co = &R[o->b];
            float lod = 0.0f;
            if (o->aux == 3) { dxr_sample_cube_lod(u, co[0].f, co[1].f, co[2].f, 0.0f, &R[o->d]); break; }
            if (e[0] != NOCELL && e[1] != NOCELL && ctx->dvdx && u) {
                uint32_t su = (uint32_t)s->input_slot[e[0] >> 2] + (e[0] & 3), sv = (uint32_t)s->input_slot[e[1] >> 2] + (e[1] & 3);
                if (su < DXR_MAX_VARYING_CELLS && sv < DXR_MAX_VARYING_CELLS)
                    lod = dxr_lod_from_grad(u, ctx->dvdx[su], ctx->dvdx[sv], ctx->dvdy[su], ctx->dvdy[sv]);
            }
            if (e[2] != NOCELL) lod += R[e[2]].f;
            dxr_sample_lod(u, co[0].f, co[1].f, lod, &R[o->d]);
            break;
        }
        case I_SAMPLE_LOD: {
            uint32_t bi = R[o->a].u;
            const dxr_uniform_binding *u = bi < DXR_MAX_UNIFORMS ? &ctx->uniforms[bi] : NULL;
            const dxr_cell *co = &R[o->b];
            if (o->aux == 3) dxr_sample_cube_lod(u, co[0].f, co[1].f, co[2].f, R[o->c].f, &R[o->d]);
            else dxr_sample_lod(u, co[0].f, co[1].f, R[o->c].f, &R[o->d]);
            break;
        }
        case I_SAMPLE_GRAD: {
            uint32_t bi = R[o->a].u;
            const dxr_uniform_binding *u = bi < DXR_MAX_UNIFORMS ? &ctx->uniforms[bi] : NULL;
            const dxr_cell *co = &R[o->b], *gx = &R[X[o->c]], *gy = &R[X[o->c + 1]];
            if (o->aux == 3) { dxr_sample_cube_lod(u, co[0].f, co[1].f, co[2].f, 0.0f, &R[o->d]); break; }
            dxr_sample_lod(u, co[0].f, co[1].f, dxr_lod_from_grad(u, gx[0].f, gx[1].f, gy[0].f, gy[1].f), &R[o->d]);
            break;
        }
        case I_SAMPLE_PROJ: {
            uint32_t bi = R[o->a].u;
            const dxr_uniform_binding *u = bi < DXR_MAX_UNIFORMS ? &ctx->uniforms[bi] : NULL;
            const dxr_cell *co = &R[o->b];
            float q = co[o->n - 1].f;
            float iq = q != 0.0f ? 1.0f / q : 0.0f;
            dxr_sample_lod(u, co[0].f * iq, co[1].f * iq, o->c != NOCELL ? R[o->c].f : 0.0f, &R[o->d]);
            break;
        }
        case I_FETCH: {
            uint32_t bi = R[o->a].u;
            const dxr_uniform_binding *u = bi < DXR_MAX_UNIFORMS ? &ctx->uniforms[bi] : NULL;
            dxr_fetch(u, R[o->b].i, o->n > 1 ? R[o->b + 1].i : 0, o->c != NOCELL ? R[o->c].i : 0, &R[o->d]);
            break;
        }
        case I_QUERY_SIZE: {
            uint32_t bi = R[o->a].u;
            const dxr_uniform_binding *u = bi < DXR_MAX_UNIFORMS ? &ctx->uniforms[bi] : NULL;
            int lod = o->b != NOCELL ? R[o->b].i : 0, w = 0, h = 0;
            if (u && u->kind == DXR_UNIFORM_TEXEL_BUFFER) { int tb = dxr_ctype_bytes(u->ctype) * u->ncomp; w = tb ? (int)(u->size / (uint32_t)tb) : 0; }
            else if (u && u->tex) { int l = u->base_mip + lod; w = DXR_MAX(1, u->tex->width >> l); h = DXR_MAX(1, u->tex->height >> l); }
            R[o->d].i = w;
            if (o->n > 1) R[o->d + 1].i = h;
            for (int i = 2; i < o->n; i++) R[o->d + i].i = 1;
            break;
        }
        case I_QUERY_LEVELS: {
            uint32_t bi = R[o->a].u;
            const dxr_uniform_binding *u = bi < DXR_MAX_UNIFORMS ? &ctx->uniforms[bi] : NULL;
            R[o->d].i = u && u->tex ? (u->mip_count ? u->mip_count : u->tex->mips) : 1;
            break;
        }
        case I_GLSL:
            glsl_op(o->aux, o->n, &R[o->d], &R[o->a], &R[o->b], &R[o->c]);
            break;
        default:
            return 0;
        }
    }
}

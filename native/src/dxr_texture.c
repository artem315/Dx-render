/* DX Render - Texture System: storage, format conversion, filtering, mip selection. */
#include "dxr_internal.h"

static const uint8_t g_ctype_bytes[DXR_CT_COUNT] = {1, 1, 2, 2, 1, 1, 2, 2, 4, 4, 2, 4, 4};

int dxr_ctype_bytes(int ctype) { return (ctype >= 0 && ctype < DXR_CT_COUNT) ? g_ctype_bytes[ctype] : 0; }

int dxr_ctype_is_int(int ctype) { return ctype >= DXR_CT_UINT8 && ctype <= DXR_CT_SINT32; }

static float half_to_float(uint16_t h) {
    uint32_t sign = (uint32_t)(h & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1f;
    uint32_t man = h & 0x3ffu;
    uint32_t bits;
    if (exp == 0) {
        if (man == 0) {
            bits = sign;
        } else {
            exp = 1;
            while (!(man & 0x400u)) { man <<= 1; exp--; }
            man &= 0x3ffu;
            bits = sign | ((exp + 112) << 23) | (man << 13);
        }
    } else if (exp == 31) {
        bits = sign | 0x7f800000u | (man << 13);
    } else {
        bits = sign | ((exp + 112) << 23) | (man << 13);
    }
    float f;
    memcpy(&f, &bits, 4);
    return f;
}

static uint16_t float_to_half(float f) {
    uint32_t x;
    memcpy(&x, &f, 4);
    uint32_t sign = (x >> 16) & 0x8000u;
    int exp = (int)((x >> 23) & 0xff) - 127 + 15;
    uint32_t man = x & 0x7fffffu;
    if (((x >> 23) & 0xff) == 0xff) return (uint16_t)(sign | 0x7c00u | (man ? 0x200u : 0));
    if (exp >= 31) return (uint16_t)(sign | 0x7c00u);
    if (exp <= 0) {
        if (exp < -10) return (uint16_t)sign;
        man |= 0x800000u;
        uint32_t shift = (uint32_t)(14 - exp);
        uint32_t half = man >> shift;
        if ((man >> (shift - 1)) & 1u) half++;
        return (uint16_t)(sign | half);
    }
    uint32_t half = ((uint32_t)exp << 10) | (man >> 13);
    if (man & 0x1000u) half++;
    return (uint16_t)(sign | half);
}

void dxr_texel_load(const uint8_t *p, int ctype, int ncomp, dxr_cell out[4]) {
    switch (ctype) {
    case DXR_CT_UNORM8:
        if (ncomp == 4) {
            out[0].f = p[0] * (1.0f / 255.0f); out[1].f = p[1] * (1.0f / 255.0f);
            out[2].f = p[2] * (1.0f / 255.0f); out[3].f = p[3] * (1.0f / 255.0f);
            return;
        }
        out[0].f = out[1].f = out[2].f = 0.0f; out[3].f = 1.0f;
        for (int i = 0; i < ncomp; i++) out[i].f = p[i] * (1.0f / 255.0f);
        return;
    case DXR_CT_SNORM8:
        out[0].f = out[1].f = out[2].f = 0.0f; out[3].f = 1.0f;
        for (int i = 0; i < ncomp; i++) { float v = (int8_t)p[i] * (1.0f / 127.0f); out[i].f = v < -1.0f ? -1.0f : v; }
        return;
    case DXR_CT_UNORM16:
        out[0].f = out[1].f = out[2].f = 0.0f; out[3].f = 1.0f;
        for (int i = 0; i < ncomp; i++) { uint16_t v; memcpy(&v, p + 2 * i, 2); out[i].f = v * (1.0f / 65535.0f); }
        return;
    case DXR_CT_SNORM16:
        out[0].f = out[1].f = out[2].f = 0.0f; out[3].f = 1.0f;
        for (int i = 0; i < ncomp; i++) { int16_t v; memcpy(&v, p + 2 * i, 2); float f = v * (1.0f / 32767.0f); out[i].f = f < -1.0f ? -1.0f : f; }
        return;
    case DXR_CT_UINT8:
        out[0].u = out[1].u = out[2].u = 0; out[3].u = 1;
        for (int i = 0; i < ncomp; i++) out[i].u = p[i];
        return;
    case DXR_CT_SINT8:
        out[0].i = out[1].i = out[2].i = 0; out[3].i = 1;
        for (int i = 0; i < ncomp; i++) out[i].i = (int8_t)p[i];
        return;
    case DXR_CT_UINT16:
        out[0].u = out[1].u = out[2].u = 0; out[3].u = 1;
        for (int i = 0; i < ncomp; i++) { uint16_t v; memcpy(&v, p + 2 * i, 2); out[i].u = v; }
        return;
    case DXR_CT_SINT16:
        out[0].i = out[1].i = out[2].i = 0; out[3].i = 1;
        for (int i = 0; i < ncomp; i++) { int16_t v; memcpy(&v, p + 2 * i, 2); out[i].i = v; }
        return;
    case DXR_CT_UINT32:
    case DXR_CT_SINT32:
        out[0].u = out[1].u = out[2].u = 0; out[3].u = 1;
        memcpy(out, p, (size_t)ncomp * 4);
        return;
    case DXR_CT_FLOAT16:
        out[0].f = out[1].f = out[2].f = 0.0f; out[3].f = 1.0f;
        for (int i = 0; i < ncomp; i++) { uint16_t v; memcpy(&v, p + 2 * i, 2); out[i].f = half_to_float(v); }
        return;
    case DXR_CT_FLOAT32:
        out[0].f = out[1].f = out[2].f = 0.0f; out[3].f = 1.0f;
        memcpy(out, p, (size_t)ncomp * 4);
        return;
    case DXR_CT_DEPTH32F:
    default:
        memcpy(&out[0], p, 4);
        out[1].f = out[2].f = 0.0f; out[3].f = 1.0f;
        return;
    }
}

DXR_INLINE float sat(float v) { return v >= 0.0f ? (v <= 1.0f ? v : 1.0f) : 0.0f; /* NaN -> 0 */ }

void dxr_texel_store(uint8_t *p, int ctype, int ncomp, const float in[4]) {
    switch (ctype) {
    case DXR_CT_UNORM8:
        for (int i = 0; i < ncomp; i++) p[i] = (uint8_t)(sat(in[i]) * 255.0f + 0.5f);
        return;
    case DXR_CT_SNORM8:
        for (int i = 0; i < ncomp; i++) { float v = DXR_CLAMP(in[i], -1.0f, 1.0f); p[i] = (uint8_t)(int8_t)lrintf(v * 127.0f); }
        return;
    case DXR_CT_UNORM16:
        for (int i = 0; i < ncomp; i++) { uint16_t v = (uint16_t)(sat(in[i]) * 65535.0f + 0.5f); memcpy(p + 2 * i, &v, 2); }
        return;
    case DXR_CT_SNORM16:
        for (int i = 0; i < ncomp; i++) { int16_t v = (int16_t)lrintf(DXR_CLAMP(in[i], -1.0f, 1.0f) * 32767.0f); memcpy(p + 2 * i, &v, 2); }
        return;
    case DXR_CT_FLOAT16:
        for (int i = 0; i < ncomp; i++) { uint16_t v = float_to_half(in[i]); memcpy(p + 2 * i, &v, 2); }
        return;
    case DXR_CT_FLOAT32:
        memcpy(p, in, (size_t)ncomp * 4);
        return;
    case DXR_CT_DEPTH32F:
        memcpy(p, in, 4);
        return;
    default: {
        /* integer targets written from float data: plain conversion */
        dxr_cell c[4];
        for (int i = 0; i < 4; i++) c[i].i = (int32_t)in[i];
        dxr_texel_store_cells(p, ctype, ncomp, c);
        return;
    }
    }
}

void dxr_texel_store_cells(uint8_t *p, int ctype, int ncomp, const dxr_cell in[4]) {
    switch (ctype) {
    case DXR_CT_UINT8: case DXR_CT_SINT8:
        for (int i = 0; i < ncomp; i++) p[i] = (uint8_t)in[i].u;
        return;
    case DXR_CT_UINT16: case DXR_CT_SINT16:
        for (int i = 0; i < ncomp; i++) { uint16_t v = (uint16_t)in[i].u; memcpy(p + 2 * i, &v, 2); }
        return;
    case DXR_CT_UINT32: case DXR_CT_SINT32:
        memcpy(p, in, (size_t)ncomp * 4);
        return;
    default: {
        float f[4] = {in[0].f, in[1].f, in[2].f, in[3].f};
        dxr_texel_store(p, ctype, ncomp, f);
        return;
    }
    }
}

dxr_texture *dxr_texture_create(dxr_device *dev, int ctype, int ncomp, int width, int height, int layers, int mips, int cube) {
    if (ctype < 0 || ctype >= DXR_CT_COUNT || ncomp < 1 || ncomp > 4 || width < 1 || height < 1 || mips < 1 || mips > DXR_MAX_MIPS) {
        dxr_set_error("invalid texture parameters (%d,%d,%dx%d,mips %d)", ctype, ncomp, width, height, mips);
        return NULL;
    }
    if (layers < 1) layers = 1;
    if (layers > 6) layers = 6;
    dxr_texture *t = (dxr_texture *)dxr_alloc(sizeof(dxr_texture));
    if (!t) return NULL;
    t->ctype = (uint8_t)ctype;
    t->ncomp = (uint8_t)ncomp;
    t->is_depth = ctype == DXR_CT_DEPTH32F;
    t->is_cube = (uint8_t)(cube != 0);
    t->texel_bytes = (uint32_t)(dxr_ctype_bytes(ctype) * ncomp);
    t->width = width; t->height = height; t->layers = layers; t->mips = mips;
    size_t total = 0;
    for (int m = 0; m < mips; m++) {
        size_t w = (size_t)DXR_MAX(1, width >> m), h = (size_t)DXR_MAX(1, height >> m);
        total += ((w * h * t->texel_bytes + 63) & ~(size_t)63) * (size_t)layers;
    }
    t->data = (uint8_t *)dxr_alloc(total + 64);
    if (!t->data) { dxr_free(t); dxr_set_error("out of memory allocating %zu texture bytes", total); return NULL; }
    t->data_size = total;
    size_t off = 0;
    for (int l = 0; l < layers; l++) {
        for (int m = 0; m < mips; m++) {
            size_t w = (size_t)DXR_MAX(1, width >> m), h = (size_t)DXR_MAX(1, height >> m);
            t->level[l][m] = t->data + off;
            off += (w * h * t->texel_bytes + 63) & ~(size_t)63;
        }
    }
    if (dev) atomic_fetch_add(&dev->texture_bytes, total);
    return t;
}

void dxr_texture_destroy(dxr_device *dev, dxr_texture *tex) {
    if (!tex) return;
    if (dev) atomic_fetch_sub(&dev->texture_bytes, tex->data_size);
    dxr_free(tex->data);
    dxr_free(tex);
}

uint8_t *dxr_texture_level(const dxr_texture *tex, int layer, int mip, int *out_w, int *out_h, int *out_texel_bytes) {
    if (layer < 0 || layer >= tex->layers || mip < 0 || mip >= tex->mips) return NULL;
    if (out_w) *out_w = DXR_MAX(1, tex->width >> mip);
    if (out_h) *out_h = DXR_MAX(1, tex->height >> mip);
    if (out_texel_bytes) *out_texel_bytes = (int)tex->texel_bytes;
    return tex->level[layer][mip];
}

static int clip_region(int lw, int lh, int *x, int *y, int *w, int *h, int *skip_x, int *skip_y) {
    *skip_x = 0; *skip_y = 0;
    if (*x < 0) { *skip_x = -*x; *w += *x; *x = 0; }
    if (*y < 0) { *skip_y = -*y; *h += *y; *y = 0; }
    if (*x + *w > lw) *w = lw - *x;
    if (*y + *h > lh) *h = lh - *y;
    return *w > 0 && *h > 0;
}

void dxr_texture_write(dxr_texture *tex, int layer, int mip, int x, int y, int w, int h, const uint8_t *src, int src_row_texels) {
    int lw, lh, tb, sx, sy;
    uint8_t *dst = dxr_texture_level(tex, layer, mip, &lw, &lh, &tb);
    if (!dst || !src) return;
    if (src_row_texels <= 0) src_row_texels = w;
    if (!clip_region(lw, lh, &x, &y, &w, &h, &sx, &sy)) return;
    for (int r = 0; r < h; r++)
        memcpy(dst + ((size_t)(y + r) * lw + x) * tb, src + ((size_t)(sy + r) * src_row_texels + sx) * tb, (size_t)w * tb);
}

void dxr_texture_read(const dxr_texture *tex, int layer, int mip, int x, int y, int w, int h, uint8_t *dst) {
    int lw, lh, tb;
    const uint8_t *src = dxr_texture_level(tex, layer, mip, &lw, &lh, &tb);
    if (!src || !dst) return;
    for (int r = 0; r < h; r++) {
        int yy = y + r;
        uint8_t *drow = dst + (size_t)r * w * tb;
        if (yy < 0 || yy >= lh) { memset(drow, 0, (size_t)w * tb); continue; }
        int x0 = x, n = w, skip = 0;
        if (x0 < 0) { skip = -x0; n += x0; x0 = 0; }
        if (x0 + n > lw) n = lw - x0;
        if (skip > 0) memset(drow, 0, (size_t)DXR_MIN(skip, w) * tb);
        if (n > 0) memcpy(drow + (size_t)skip * tb, src + ((size_t)yy * lw + x0) * tb, (size_t)n * tb);
        if (n > 0 && skip + n < w) memset(drow + (size_t)(skip + n) * tb, 0, (size_t)(w - skip - n) * tb);
    }
}

void dxr_texture_copy(const dxr_texture *src, dxr_texture *dst, int mip, int dx, int dy, int sx, int sy, int w, int h) {
    int sw, sh, stb, dw, dh, dtb;
    const uint8_t *s = dxr_texture_level(src, 0, DXR_MIN(mip, src->mips - 1), &sw, &sh, &stb);
    uint8_t *d = dxr_texture_level(dst, 0, DXR_MIN(mip, dst->mips - 1), &dw, &dh, &dtb);
    if (!s || !d) return;
    if (sx < 0) { w += sx; dx -= sx; sx = 0; }
    if (sy < 0) { h += sy; dy -= sy; sy = 0; }
    if (dx < 0) { w += dx; sx -= dx; dx = 0; }
    if (dy < 0) { h += dy; sy -= dy; dy = 0; }
    if (sx + w > sw) w = sw - sx;
    if (sy + h > sh) h = sh - sy;
    if (dx + w > dw) w = dw - dx;
    if (dy + h > dh) h = dh - dy;
    if (w <= 0 || h <= 0) return;
    if (src->ctype == dst->ctype && src->ncomp == dst->ncomp) {
        for (int r = 0; r < h; r++)
            memmove(d + ((size_t)(dy + r) * dw + dx) * dtb, s + ((size_t)(sy + r) * sw + sx) * stb, (size_t)w * stb);
        return;
    }
    for (int r = 0; r < h; r++) {
        for (int c = 0; c < w; c++) {
            dxr_cell v[4];
            dxr_texel_load(s + ((size_t)(sy + r) * sw + sx + c) * stb, src->ctype, src->ncomp, v);
            dxr_texel_store_cells(d + ((size_t)(dy + r) * dw + dx + c) * dtb, dst->ctype, dst->ncomp, v);
        }
    }
}

void dxr_texture_clear_color(dxr_texture *tex, int mip, const float rgba[4], int x, int y, int w, int h) {
    for (int layer = 0; layer < tex->layers; layer++) {
        int lw, lh, tb, sx, sy, cx = x, cy = y, cw = w, ch = h;
        uint8_t *d = dxr_texture_level(tex, layer, mip, &lw, &lh, &tb);
        if (!d || !clip_region(lw, lh, &cx, &cy, &cw, &ch, &sx, &sy)) continue;
        uint8_t texel[16];
        dxr_texel_store(texel, tex->ctype, tex->ncomp, rgba);
        for (int r = 0; r < ch; r++) {
            uint8_t *row = d + ((size_t)(cy + r) * lw + cx) * tb;
            if (tb == 4) {
                uint32_t v;
                memcpy(&v, texel, 4);
                uint32_t *p = (uint32_t *)row;
                for (int c = 0; c < cw; c++) p[c] = v;
            } else {
                for (int c = 0; c < cw; c++) memcpy(row + (size_t)c * tb, texel, (size_t)tb);
            }
        }
    }
}

void dxr_texture_clear_depth(dxr_texture *tex, int mip, float depth, int x, int y, int w, int h) {
    float v[4] = {depth, 0, 0, 0};
    dxr_texture_clear_color(tex, mip, v, x, y, w, h);
}

/* ---- sampling ----------------------------------------------------------------- */

DXR_INLINE int wrap_coord(int x, int size, int clamp) {
    if (clamp) return x < 0 ? 0 : (x >= size ? size - 1 : x);
    x %= size;
    return x < 0 ? x + size : x;
}

DXR_INLINE int ifloor(float f) {
    if (!(f > -1.0e9f)) return -1000000000;
    if (!(f < 1.0e9f)) return 1000000000;
    int i = (int)f;
    return i - (f < (float)i);
}

static void sample_level(const dxr_texture *t, int layer, int level, float u, float v, int linear, int cu, int cv, dxr_cell out[4]) {
    const int w = DXR_MAX(1, t->width >> level), h = DXR_MAX(1, t->height >> level);
    const uint8_t *base = t->level[layer][level];
    const int tb = (int)t->texel_bytes;
    if (!linear || dxr_ctype_is_int(t->ctype)) {
        int x = wrap_coord(ifloor(u * (float)w), w, cu);
        int y = wrap_coord(ifloor(v * (float)h), h, cv);
        dxr_texel_load(base + ((size_t)y * w + x) * tb, t->ctype, t->ncomp, out);
        return;
    }
    float fx = u * (float)w - 0.5f, fy = v * (float)h - 0.5f;
    int x0 = ifloor(fx), y0 = ifloor(fy);
    float ax = fx - (float)x0, ay = fy - (float)y0;
    int xa = wrap_coord(x0, w, cu), xb = wrap_coord(x0 + 1, w, cu);
    int ya = wrap_coord(y0, h, cv), yb = wrap_coord(y0 + 1, h, cv);
    dxr_cell c00[4], c10[4], c01[4], c11[4];
    dxr_texel_load(base + ((size_t)ya * w + xa) * tb, t->ctype, t->ncomp, c00);
    dxr_texel_load(base + ((size_t)ya * w + xb) * tb, t->ctype, t->ncomp, c10);
    dxr_texel_load(base + ((size_t)yb * w + xa) * tb, t->ctype, t->ncomp, c01);
    dxr_texel_load(base + ((size_t)yb * w + xb) * tb, t->ctype, t->ncomp, c11);
    for (int i = 0; i < 4; i++) {
        float top = c00[i].f + (c10[i].f - c00[i].f) * ax;
        float bot = c01[i].f + (c11[i].f - c01[i].f) * ax;
        out[i].f = top + (bot - top) * ay;
    }
}

static void sample_layer_lod(const dxr_uniform_binding *b, int layer, float u, float v, float lod, int cu, int cv, dxr_cell out[4]) {
    const dxr_texture *t = b->tex;
    if (u != u) u = 0.0f;
    if (v != v) v = 0.0f;
    int base = b->base_mip < t->mips ? b->base_mip : t->mips - 1;
    int count = b->mip_count ? b->mip_count : 1;
    if (base + count > t->mips) count = t->mips - base;
    float maxl = (float)(count - 1);
    if (b->max_lod < maxl) maxl = b->max_lod;
    if (maxl < 0.0f) maxl = 0.0f;
    if (!(lod > 0.0f)) {
        sample_level(t, layer, base, u, v, b->mag_linear, cu, cv, out);
        return;
    }
    if (lod > maxl) lod = maxl;
    int l0 = (int)lod;
    float f = lod - (float)l0;
    if (f < 1.0e-3f || l0 + 1 > (int)maxl) {
        sample_level(t, layer, base + l0, u, v, b->min_linear, cu, cv, out);
        return;
    }
    dxr_cell a[4], c[4];
    sample_level(t, layer, base + l0, u, v, b->min_linear, cu, cv, a);
    sample_level(t, layer, base + l0 + 1, u, v, b->min_linear, cu, cv, c);
    for (int i = 0; i < 4; i++) out[i].f = a[i].f + (c[i].f - a[i].f) * f;
}

static void no_texture(dxr_cell out[4]) {
    out[0].f = out[1].f = out[2].f = 0.0f;
    out[3].f = 1.0f;
}

void dxr_sample_lod(const dxr_uniform_binding *b, float u, float v, float lod, dxr_cell out[4]) {
    if (DXR_UNLIKELY(!b || !b->tex)) { no_texture(out); return; }
    sample_layer_lod(b, 0, u, v, lod, b->wrap_u, b->wrap_v, out);
}

void dxr_sample_cube_lod(const dxr_uniform_binding *b, float x, float y, float z, float lod, dxr_cell out[4]) {
    if (DXR_UNLIKELY(!b || !b->tex)) { no_texture(out); return; }
    float ax = fabsf(x), ay = fabsf(y), az = fabsf(z), sc, tc, ma;
    int face;
    if (ax >= ay && ax >= az) { ma = ax; if (x >= 0) { face = 0; sc = -z; tc = -y; } else { face = 1; sc = z; tc = -y; } }
    else if (ay >= az) { ma = ay; if (y >= 0) { face = 2; sc = x; tc = z; } else { face = 3; sc = x; tc = -z; } }
    else { ma = az; if (z >= 0) { face = 4; sc = x; tc = -y; } else { face = 5; sc = -x; tc = -y; } }
    if (ma < 1.0e-20f) ma = 1.0e-20f;
    float u = 0.5f * (sc / ma + 1.0f), v = 0.5f * (tc / ma + 1.0f);
    if (face >= b->tex->layers) face = 0;
    sample_layer_lod(b, face, u, v, lod, 1, 1, out);
}

void dxr_fetch(const dxr_uniform_binding *b, int x, int y, int lod, dxr_cell out[4]) {
    if (DXR_UNLIKELY(!b)) { no_texture(out); return; }
    if (b->kind == DXR_UNIFORM_TEXEL_BUFFER) {
        int tb = dxr_ctype_bytes(b->ctype) * b->ncomp;
        if (!b->data || x < 0 || tb == 0 || (uint64_t)(x + 1) * (uint64_t)tb > b->size) { memset(out, 0, sizeof(dxr_cell) * 4); return; }
        dxr_texel_load(b->data + (size_t)x * tb, b->ctype, b->ncomp, out);
        return;
    }
    const dxr_texture *t = b->tex;
    if (DXR_UNLIKELY(!t)) { no_texture(out); return; }
    int level = b->base_mip + lod;
    if (level < 0 || level >= t->mips) { memset(out, 0, sizeof(dxr_cell) * 4); return; }
    int w = DXR_MAX(1, t->width >> level), h = DXR_MAX(1, t->height >> level);
    if (x < 0 || y < 0 || x >= w || y >= h) { memset(out, 0, sizeof(dxr_cell) * 4); return; }
    dxr_texel_load(t->level[0][level] + ((size_t)y * w + x) * t->texel_bytes, t->ctype, t->ncomp, out);
}

float dxr_lod_from_grad(const dxr_uniform_binding *b, float dudx, float dvdx, float dudy, float dvdy) {
    if (!b || !b->tex) return 0.0f;
    int base = b->base_mip < b->tex->mips ? b->base_mip : b->tex->mips - 1;
    float w = (float)DXR_MAX(1, b->tex->width >> base), h = (float)DXR_MAX(1, b->tex->height >> base);
    float rx = dudx * dudx * w * w + dvdx * dvdx * h * h;
    float ry = dudy * dudy * w * w + dvdy * dvdy * h * h;
    float rho2 = rx > ry ? rx : ry;
    if (!(rho2 > 1.0f)) return 0.0f;
    return 0.5f * log2f(rho2);
}

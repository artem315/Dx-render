/* DX Render - Lighting System and verified native shader kernels.
 *
 * The interpreter in dxr_spirv.c is the reference: it executes whatever SPIR-V the game
 * provides. For the handful of shaders that produce almost every pixel, this file holds
 * hand written native versions built on the Minecraft lighting model below (light map
 * addressing, two light diffuse term, linear fog).
 *
 * A kernel is never trusted by name. When a pipeline is created, each structurally
 * compatible kernel is run against the interpreter on random uniforms, textures and
 * inputs; only a kernel that reproduces the interpreter's results is attached. A resource
 * pack that changes a core shader therefore silently gets the interpreter instead.
 */
#include "dxr_internal.h"

/* ---- Lighting System --------------------------------------------------------------- */
void dxr_light_lightmap_uv(int u, int v, float out_uv[2]) {
    /* sample_lightmap(): clamp(uv / 256 + 0.5 / 16, 0.5 / 16, 15.5 / 16) */
    float fu = (float)u / 256.0f + 0.03125f, fv = (float)v / 256.0f + 0.03125f;
    fu = fu < 0.03125f ? 0.03125f : fu; fu = fu > 0.96875f ? 0.96875f : fu;
    fv = fv < 0.03125f ? 0.03125f : fv; fv = fv > 0.96875f ? 0.96875f : fv;
    out_uv[0] = fu;
    out_uv[1] = fv;
}

float dxr_light_diffuse(const float l0[3], const float l1[3], const float n[3]) {
    /* minecraft_mix_light(): min(1, (max(0, L0.N) + max(0, L1.N)) * 0.6 + 0.4) */
    float a = l0[0] * n[0] + l0[1] * n[1] + l0[2] * n[2];
    float b = l1[0] * n[0] + l1[1] * n[1] + l1[2] * n[2];
    a = 0.0f < a ? a : 0.0f;
    b = 0.0f < b ? b : 0.0f;
    float v = (a + b) * 0.6f + 0.4f;
    return v < 1.0f ? v : 1.0f;
}

float dxr_light_linear_fog(float dist, float start, float end) {
    if (dist <= start) return 0.0f;
    if (dist >= end) return 1.0f;
    return (dist - start) / (end - start);
}

float dxr_light_total_fog(float spherical, float cylindrical, float env_start, float env_end, float rd_start, float rd_end) {
    float a = dxr_light_linear_fog(spherical, env_start, env_end);
    float b = dxr_light_linear_fog(cylindrical, rd_start, rd_end);
    return a < b ? b : a;
}

DXR_INLINE float mixf(float x, float y, float a) { return x * (1.0f - a) + y * a; }

/* apply_fog() from fog.glsl */
DXR_INLINE void apply_fog(const float in[4], float sph, float cyl, float es, float ee, float rs, float re, const float fog_color[4], float out[4]) {
    float fv = dxr_light_total_fog(sph, cyl, es, ee, rs, re) * fog_color[3];
    out[0] = mixf(in[0], fog_color[0], fv);
    out[1] = mixf(in[1], fog_color[1], fv);
    out[2] = mixf(in[2], fog_color[2], fv);
    out[3] = in[3];
}

/* ---- helpers ----------------------------------------------------------------------- */
static int find_uniform(const dxr_pipeline *p, const char *name, int kind) {
    for (int i = 0; i < p->num_uniforms; i++) if (p->uniform_kind[i] == kind && strcmp(p->uniform_name[i], name) == 0) return i;
    return -1;
}

static int varying_slot(const dxr_pipeline *p, int location, int cells) {
    for (int i = 0; i < p->num_varyings; i++) if (p->varyings[i].location == location) return p->varyings[i].cells == cells && !p->varyings[i].flat ? p->varyings[i].slot : -1;
    return -1;
}

DXR_INLINE const uint8_t *ubo(const dxr_uniform_binding *u, int idx, uint32_t need) {
    const dxr_uniform_binding *b = &u[idx];
    return (b->data && b->size >= need) ? b->data : NULL;
}

static uint32_t rnd(uint32_t *s) {
    uint32_t x = *s;
    x ^= x << 13; x ^= x >> 17; x ^= x << 5;
    return *s = x;
}
static float rndf(uint32_t *s, float lo, float hi) { return lo + (hi - lo) * (float)(rnd(s) >> 8) * (1.0f / 16777216.0f); }
static void put_i32(dxr_uniform_binding *u, int idx, uint32_t off, int32_t v) { if (idx >= 0 && u[idx].data && off + 4 <= u[idx].size) memcpy((uint8_t *)u[idx].data + off, &v, 4); }
static void put_f32(dxr_uniform_binding *u, int idx, uint32_t off, float v) { if (idx >= 0 && u[idx].data && off + 4 <= u[idx].size) memcpy((uint8_t *)u[idx].data + off, &v, 4); }

/* Uniform block layouts (std140) of the vanilla shaders. */
enum { DT_COLOR_MODULATOR = 128, DT_SIZE = 160 };
enum { FOG_COLOR = 0, FOG_ENV_START = 16, FOG_ENV_END = 20, FOG_RD_START = 24, FOG_RD_END = 28, FOG_SKY_END = 32, FOG_SIZE = 40 };
enum { GLOBALS_CAMERA_BLOCK = 0, GLOBALS_CAMERA_OFFSET = 16, GLOBALS_USE_RGSS = 44, GLOBALS_SIZE = 48 };
enum { TERRAIN_MODELVIEW = 0, TERRAIN_TEXTURE_SIZE = 64, TERRAIN_SIZE = 72 };

static void fixup_fog(dxr_uniform_binding *u, int idx, uint32_t *rng) {
    float a = rndf(rng, 0.0f, 3.0f), b = rndf(rng, 0.0f, 3.0f);
    put_f32(u, idx, FOG_ENV_START, a);
    put_f32(u, idx, FOG_ENV_END, a + rndf(rng, 0.01f, 3.0f));
    put_f32(u, idx, FOG_RD_START, b);
    put_f32(u, idx, FOG_RD_END, b + rndf(rng, 0.01f, 3.0f));
    put_f32(u, idx, FOG_SKY_END, rndf(rng, 0.2f, 3.0f));
    put_f32(u, idx, FOG_COLOR + 12, rndf(rng, 0.0f, 1.0f));
}

/* ======================= fragment: terrain ============================================= */
static int terrain_fs_bind(const dxr_pipeline *p, dxr_kdata *d, int variant) {
    if (p->num_varyings != 5 || p->out_ncomp[0] != 4 || p->out_is_int[0]) return 0;
    d->slot[0] = (int16_t)varying_slot(p, 0, 1); d->slot[1] = (int16_t)varying_slot(p, 1, 1); d->slot[2] = (int16_t)varying_slot(p, 2, 4);
    d->slot[3] = (int16_t)varying_slot(p, 3, 2); d->slot[4] = (int16_t)varying_slot(p, 4, 1);
    d->u[0] = (int8_t)find_uniform(p, "Sampler0", DXR_UNIFORM_SAMPLER); d->u[1] = (int8_t)find_uniform(p, "Fog", DXR_UNIFORM_BUFFER);
    d->u[2] = (int8_t)find_uniform(p, "Globals", DXR_UNIFORM_BUFFER); d->u[3] = (int8_t)find_uniform(p, "TerrainUniform", DXR_UNIFORM_BUFFER);
    for (int i = 0; i < 5; i++) if (d->slot[i] < 0) return 0;
    for (int i = 0; i < 4; i++) if (d->u[i] < 0) return 0;
    d->i[0] = variant != 0;
    d->f[0] = variant == 1 ? 0.1f : 0.5f;
    return 1;
}

static void terrain_fs_fixup(const dxr_kdata *d, dxr_uniform_binding *u, uint32_t *rng) {
    fixup_fog(u, d->u[1], rng);
    put_i32(u, d->u[2], GLOBALS_USE_RGSS, (int32_t)(rnd(rng) & 1));
    put_i32(u, d->u[3], TERRAIN_TEXTURE_SIZE, 16 << (rnd(rng) % 4));
    put_i32(u, d->u[3], TERRAIN_TEXTURE_SIZE + 4, 16 << (rnd(rng) % 4));
}

static int terrain_fs(const dxr_kdata *d, const dxr_frag_ctx *c, float out[4]) {
    const uint8_t *fog = ubo(c->uniforms, d->u[1], FOG_SIZE), *glob = ubo(c->uniforms, d->u[2], GLOBALS_SIZE), *ter = ubo(c->uniforms, d->u[3], TERRAIN_SIZE);
    if (DXR_UNLIKELY(!fog || !glob || !ter || !c->dvdx)) return -1;
    int32_t rgss, ts[2];
    memcpy(&rgss, glob + GLOBALS_USE_RGSS, 4);
    memcpy(ts, ter + TERRAIN_TEXTURE_SIZE, 8);
    float F[10];
    memcpy(F, fog, 40);
    const float *v = c->varyings;
    const int su = d->slot[3];
    const float *vc = &v[d->slot[2]];
    const float vis = v[d->slot[4]];
    const float uvx = v[su], uvy = v[su + 1];
    const float psx = 1.0f / (float)ts[0], psy = 1.0f / (float)ts[1];
    const float dux = c->dvdx[su], duy = c->dvdx[su + 1], dvx = c->dvdy[su], dvy = c->dvdy[su + 1];
    const float tsx = sqrtf(dux * dux + dvx * dvx), tsy = sqrtf(duy * duy + dvy * dvy);
    const dxr_uniform_binding *tex = &c->uniforms[d->u[0]];
    dxr_cell t[4];
    /* Rotated grid super sampling (the game's default texture filter): blend = 0 means the
     * texture is magnified and the result is exactly the nearest sample; blend = 1 means it is
     * exactly the supersampled colour. Only the transition band needs both. */
    float blend = 0.0f;
    if (rgss == 1) {
        const float max_texel = tsx < tsy ? tsy : tsx, min_pixel = psy < psx ? psy : psx;
        const float e0 = min_pixel * 1.0f, e1 = min_pixel * 2.0f;
        float st = (max_texel - e0) / (e1 - e0);
        st = st < 0.0f ? 0.0f : st; st = st > 1.0f ? 1.0f : st;
        blend = st * st * (3.0f - 2.0f * st);
        if (DXR_UNLIKELY(blend != blend)) return -1;
    }
    float near_c[4] = {0, 0, 0, 0}, ss[4] = {0, 0, 0, 0};
    if (blend != 1.0f) {
        /* sampleNearest(): snap to the texel centre, antialias only across texel edges */
        const float tx = uvx / psx, ty = uvy / psy;
        const float cx = roundf(tx) - 0.5f, cy = roundf(ty) - 0.5f;
        float ox = (tx - cx - 0.5f) * psx / tsx + 0.5f, oy = (ty - cy - 0.5f) * psy / tsy + 0.5f;
        ox = ox < 0.0f ? 0.0f : ox; ox = ox > 1.0f ? 1.0f : ox;
        oy = oy < 0.0f ? 0.0f : oy; oy = oy > 1.0f ? 1.0f : oy;
        dxr_sample_lod(tex, (cx + ox) * psx, (cy + oy) * psy, dxr_lod_from_grad(tex, dux, duy, dvx, dvy), t);
        near_c[0] = t[0].f; near_c[1] = t[1].f; near_c[2] = t[2].f; near_c[3] = t[3].f;
    }
    if (blend != 0.0f) {
        static const float offs[4][2] = {{0.125f, 0.375f}, {-0.125f, -0.375f}, {0.375f, -0.125f}, {-0.375f, 0.125f}};
        const float min_pixel = psy < psx ? psy : psx;
        const float du_len = sqrtf(0.0f + dux * dux + duy * duy), dv_len = sqrtf(0.0f + dvx * dvx + dvy * dvy);
        const float min_d = dv_len < du_len ? dv_len : du_len, max_d = du_len < dv_len ? dv_len : du_len;
        const float eff = sqrtf(min_d * max_d);
        float mip = log2f(eff / min_pixel);
        mip = 0.0f < mip ? mip : 0.0f;
        const float lo = floorf(mip), hi = lo + 1.0f, mb = mip - floorf(mip);
        float low[4] = {0, 0, 0, 0}, high[4] = {0, 0, 0, 0};
        for (int i = 0; i < 4; i++) {
            const float sx = uvx + offs[i][0] * psx, sy = uvy + offs[i][1] * psy;
            dxr_sample_lod(tex, sx, sy, lo, t);
            low[0] += t[0].f; low[1] += t[1].f; low[2] += t[2].f; low[3] += t[3].f;
            dxr_sample_lod(tex, sx, sy, hi, t);
            high[0] += t[0].f; high[1] += t[1].f; high[2] += t[2].f; high[3] += t[3].f;
        }
        for (int k = 0; k < 4; k++) ss[k] = mixf(low[k] * 0.25f, high[k] * 0.25f, mb);
    }
    float col[4];
    if (rgss == 1) { for (int k = 0; k < 4; k++) col[k] = mixf(near_c[k], ss[k], blend) * vc[k]; }
    else { for (int k = 0; k < 4; k++) col[k] = near_c[k] * vc[k]; }
    /* fade towards the fog colour while the chunk is still appearing */
    const float fa = F[3] * col[3];
    col[0] = mixf(F[0], col[0], vis); col[1] = mixf(F[1], col[1], vis); col[2] = mixf(F[2], col[2], vis); col[3] = mixf(fa, col[3], vis);
    if (d->i[0] && col[3] < d->f[0]) return 0;
    apply_fog(col, v[d->slot[0]], v[d->slot[1]], F[4], F[5], F[6], F[7], F, out);
    return 1;
}

/* ======================= fragment: texture * colour (GUI sprites, vignette, sun, ...) === */
static int tex_color_fs_bind(const dxr_pipeline *p, dxr_kdata *d, int variant) {
    (void)variant;
    if (p->num_varyings != 2 || p->out_ncomp[0] != 4 || p->out_is_int[0]) return 0;
    d->slot[0] = (int16_t)varying_slot(p, 0, 2); d->slot[1] = (int16_t)varying_slot(p, 1, 4);
    d->u[0] = (int8_t)find_uniform(p, "Sampler0", DXR_UNIFORM_SAMPLER); d->u[1] = (int8_t)find_uniform(p, "DynamicTransforms", DXR_UNIFORM_BUFFER);
    return d->slot[0] >= 0 && d->slot[1] >= 0 && d->u[0] >= 0 && d->u[1] >= 0;
}

DXR_INLINE float implicit_lod(const dxr_frag_ctx *c, const dxr_uniform_binding *tex, int su) {
    return c->dvdx ? dxr_lod_from_grad(tex, c->dvdx[su], c->dvdx[su + 1], c->dvdy[su], c->dvdy[su + 1]) : 0.0f;
}

static int tex_color_fs(const dxr_kdata *d, const dxr_frag_ctx *c, float out[4]) {
    const uint8_t *dt = ubo(c->uniforms, d->u[1], DT_COLOR_MODULATOR + 16);
    if (DXR_UNLIKELY(!dt)) return -1;
    const float *v = c->varyings, *vc = &v[d->slot[1]];
    const int su = d->slot[0];
    const dxr_uniform_binding *tex = &c->uniforms[d->u[0]];
    dxr_cell t[4];
    dxr_sample_lod(tex, v[su], v[su + 1], implicit_lod(c, tex, su), t);
    float a = t[3].f * vc[3];
    if (a == 0.0f) return 0;
    float cm[4];
    memcpy(cm, dt + DT_COLOR_MODULATOR, 16);
    out[0] = t[0].f * vc[0] * cm[0]; out[1] = t[1].f * vc[1] * cm[1]; out[2] = t[2].f * vc[2] * cm[2]; out[3] = a * cm[3];
    return 1;
}

/* ======================= fragment: vertex colour (GUI fills, sunrise, lines...) ========= */
static int color_fs_bind(const dxr_pipeline *p, dxr_kdata *d, int variant) {
    (void)variant;
    if (p->num_varyings != 1 || p->out_ncomp[0] != 4 || p->out_is_int[0]) return 0;
    d->slot[0] = (int16_t)varying_slot(p, 0, 4);
    d->u[0] = (int8_t)find_uniform(p, "DynamicTransforms", DXR_UNIFORM_BUFFER);
    return d->slot[0] >= 0 && d->u[0] >= 0;
}

static int color_fs(const dxr_kdata *d, const dxr_frag_ctx *c, float out[4]) {
    const uint8_t *dt = ubo(c->uniforms, d->u[0], DT_COLOR_MODULATOR + 16);
    if (DXR_UNLIKELY(!dt)) return -1;
    const float *vc = &c->varyings[d->slot[0]];
    if (vc[3] == 0.0f) return 0;
    float cm[4];
    memcpy(cm, dt + DT_COLOR_MODULATOR, 16);
    out[0] = vc[0] * cm[0]; out[1] = vc[1] * cm[1]; out[2] = vc[2] * cm[2]; out[3] = vc[3] * cm[3];
    return 1;
}

/* ======================= fragment: sky ================================================== */
static int sky_fs_bind(const dxr_pipeline *p, dxr_kdata *d, int variant) {
    (void)variant;
    if (p->num_varyings != 2 || p->out_ncomp[0] != 4 || p->out_is_int[0]) return 0;
    d->slot[0] = (int16_t)varying_slot(p, 0, 1); d->slot[1] = (int16_t)varying_slot(p, 1, 1);
    d->u[0] = (int8_t)find_uniform(p, "DynamicTransforms", DXR_UNIFORM_BUFFER); d->u[1] = (int8_t)find_uniform(p, "Fog", DXR_UNIFORM_BUFFER);
    return d->slot[0] >= 0 && d->slot[1] >= 0 && d->u[0] >= 0 && d->u[1] >= 0 && find_uniform(p, "Sampler0", DXR_UNIFORM_SAMPLER) < 0;
}

static void sky_fs_fixup(const dxr_kdata *d, dxr_uniform_binding *u, uint32_t *rng) { fixup_fog(u, d->u[1], rng); }

static int sky_fs(const dxr_kdata *d, const dxr_frag_ctx *c, float out[4]) {
    const uint8_t *dt = ubo(c->uniforms, d->u[0], DT_COLOR_MODULATOR + 16), *fog = ubo(c->uniforms, d->u[1], FOG_SIZE);
    if (DXR_UNLIKELY(!dt || !fog)) return -1;
    float cm[4], F[10];
    memcpy(cm, dt + DT_COLOR_MODULATOR, 16);
    memcpy(F, fog, 40);
    apply_fog(cm, c->varyings[d->slot[0]], c->varyings[d->slot[1]], 0.0f, F[8], F[8], F[8], F, out);
    return 1;
}

/* ======================= fragment: text ================================================= */
static int text_fs_bind(const dxr_pipeline *p, dxr_kdata *d, int variant) {
    if (p->out_ncomp[0] != 4 || p->out_is_int[0]) return 0;
    d->slot[2] = (int16_t)varying_slot(p, 2, 4); d->slot[3] = (int16_t)varying_slot(p, 3, 2);
    d->u[0] = (int8_t)find_uniform(p, "Sampler0", DXR_UNIFORM_SAMPLER); d->u[1] = (int8_t)find_uniform(p, "DynamicTransforms", DXR_UNIFORM_BUFFER);
    if (d->slot[2] < 0 || d->slot[3] < 0 || d->u[0] < 0 || d->u[1] < 0) return 0;
    d->i[0] = variant & 1; /* grayscale glyph atlas */
    if (p->num_varyings == 2) { d->i[1] = 0; return 1; }
    if (p->num_varyings != 4) return 0;
    d->slot[0] = (int16_t)varying_slot(p, 0, 1); d->slot[1] = (int16_t)varying_slot(p, 1, 1);
    d->u[2] = (int8_t)find_uniform(p, "Fog", DXR_UNIFORM_BUFFER);
    d->i[1] = 1;
    return d->slot[0] >= 0 && d->slot[1] >= 0 && d->u[2] >= 0;
}

static void text_fs_fixup(const dxr_kdata *d, dxr_uniform_binding *u, uint32_t *rng) { if (d->i[1]) fixup_fog(u, d->u[2], rng); }

static int text_fs(const dxr_kdata *d, const dxr_frag_ctx *c, float out[4]) {
    const uint8_t *dt = ubo(c->uniforms, d->u[1], DT_COLOR_MODULATOR + 16);
    if (DXR_UNLIKELY(!dt)) return -1;
    const float *v = c->varyings, *vc = &v[d->slot[2]];
    const int su = d->slot[3];
    const dxr_uniform_binding *tex = &c->uniforms[d->u[0]];
    dxr_cell t[4];
    dxr_sample_lod(tex, v[su], v[su + 1], implicit_lod(c, tex, su), t);
    if (d->i[0]) { t[1].f = t[0].f; t[2].f = t[0].f; t[3].f = t[0].f; }
    float cm[4], col[4];
    memcpy(cm, dt + DT_COLOR_MODULATOR, 16);
    col[0] = t[0].f * vc[0] * cm[0]; col[1] = t[1].f * vc[1] * cm[1]; col[2] = t[2].f * vc[2] * cm[2]; col[3] = t[3].f * vc[3] * cm[3];
    if (col[3] < 0.1f) return 0;
    if (!d->i[1]) { memcpy(out, col, 16); return 1; }
    const uint8_t *fog = ubo(c->uniforms, d->u[2], FOG_SIZE);
    if (DXR_UNLIKELY(!fog)) return -1;
    float F[10];
    memcpy(F, fog, 40);
    apply_fog(col, v[d->slot[0]], v[d->slot[1]], F[4], F[5], F[6], F[7], F, out);
    return 1;
}

/* ======================= vertex: terrain ================================================ */
static int terrain_vs_bind(const dxr_pipeline *p, dxr_kdata *d, int variant) {
    (void)variant;
    if (p->num_varyings != 5) return 0;
    d->slot[0] = (int16_t)varying_slot(p, 0, 1); d->slot[1] = (int16_t)varying_slot(p, 1, 1); d->slot[2] = (int16_t)varying_slot(p, 2, 4);
    d->slot[3] = (int16_t)varying_slot(p, 3, 2); d->slot[4] = (int16_t)varying_slot(p, 4, 1);
    d->u[0] = (int8_t)find_uniform(p, "Globals", DXR_UNIFORM_BUFFER); d->u[1] = (int8_t)find_uniform(p, "Projection", DXR_UNIFORM_BUFFER);
    d->u[2] = (int8_t)find_uniform(p, "TerrainUniform", DXR_UNIFORM_BUFFER); d->u[3] = (int8_t)find_uniform(p, "ChunkSection", DXR_UNIFORM_BUFFER);
    d->u[4] = (int8_t)find_uniform(p, "Sampler2", DXR_UNIFORM_SAMPLER);
    for (int i = 0; i < 5; i++) if (d->slot[i] < 0 || d->u[i] < 0) return 0;
    /* attributes: Position vec3 @0, Color vec4 @1, UV0 vec2 @2, UV2 ivec2 @3 */
    int seen = 0;
    for (int i = 0; i < p->num_attribs; i++) {
        if (p->attrib_cell[i] == UINT32_MAX) continue;
        int loc = p->attribs[i].location;
        if (loc > 3) return 0;
        if ((loc == 3) != (p->attrib_is_int[i] != 0)) return 0;
        seen |= 1 << loc;
    }
    return seen == 15;
}

static void terrain_vs_fixup(const dxr_kdata *d, dxr_uniform_binding *u, uint32_t *rng) {
    for (int k = 0; k < 3; k++) {
        put_i32(u, d->u[0], GLOBALS_CAMERA_BLOCK + 4 * (uint32_t)k, (int32_t)(rnd(rng) % 2001) - 1000);
        put_i32(u, d->u[3], 4 * (uint32_t)k, (int32_t)(rnd(rng) % 2001) - 1000);
    }
}

static int terrain_vs_begin(dxr_kdata *d, const dxr_uniform_binding *u) {
    const uint8_t *glob = ubo(u, d->u[0], GLOBALS_SIZE), *proj = ubo(u, d->u[1], 64), *ter = ubo(u, d->u[2], 64), *sec = ubo(u, d->u[3], 16);
    if (!glob || !proj || !ter || !sec) return 0;
    float P[16], MV[16];
    memcpy(P, proj, 64);
    memcpy(MV, ter + TERRAIN_MODELVIEW, 64);
    for (int c = 0; c < 4; c++)
        for (int r = 0; r < 4; r++) {
            float sum = 0.0f;
            for (int k = 0; k < 4; k++) sum += P[k * 4 + r] * MV[c * 4 + k];
            d->m[c * 4 + r] = sum;
        }
    int32_t cam[3], chunk[3];
    memcpy(cam, glob + GLOBALS_CAMERA_BLOCK, 12);
    memcpy(chunk, sec, 12);
    for (int k = 0; k < 3; k++) d->f[k] = (float)(int32_t)((uint32_t)chunk[k] - (uint32_t)cam[k]);
    memcpy(&d->f[3], sec + 12, 4); /* ChunkVisibility */
    return 1;
}

static void terrain_vs(const dxr_kdata *d, const dxr_uniform_binding *u, const dxr_cell attr[][4], float clip[4], float *vary) {
    const float *m = d->m;
    float co[3];
    memcpy(co, u[d->u[0]].data + GLOBALS_CAMERA_OFFSET, 12);
    const float x = attr[0][0].f + d->f[0] + co[0], y = attr[0][1].f + d->f[1] + co[1], z = attr[0][2].f + d->f[2] + co[2];
    for (int r = 0; r < 4; r++) clip[r] = 0.0f + m[r] * x + m[4 + r] * y + m[8 + r] * z + m[12 + r] * 1.0f;
    const float sph = sqrtf(0.0f + x * x + y * y + z * z);
    const float dxz = sqrtf(0.0f + x * x + z * z), dy = fabsf(y);
    vary[d->slot[0]] = sph;
    vary[d->slot[1]] = dxz < dy ? dy : dxz;
    float luv[2];
    dxr_light_lightmap_uv(attr[3][0].i, attr[3][1].i, luv);
    dxr_cell light[4];
    dxr_sample_lod(&u[d->u[4]], luv[0], luv[1], 0.0f, light);
    float *vc = &vary[d->slot[2]];
    vc[0] = attr[1][0].f * light[0].f; vc[1] = attr[1][1].f * light[1].f; vc[2] = attr[1][2].f * light[2].f; vc[3] = attr[1][3].f * light[3].f;
    vary[d->slot[3]] = attr[2][0].f;
    vary[d->slot[3] + 1] = attr[2][1].f;
    float t = (sph - 16.0f) / 16.0f;
    t = t < 0.0f ? 0.0f : t; t = t > 1.0f ? 1.0f : t;
    vary[d->slot[4]] = mixf(1.0f, d->f[3], t);
}

/* ---- registry ------------------------------------------------------------------------- */
static const dxr_fast_kernel g_kernels[] = {
    {"terrain", 1, 3, terrain_fs_bind, terrain_fs_fixup, terrain_fs, NULL, NULL},
    {"texture*color", 1, 1, tex_color_fs_bind, NULL, tex_color_fs, NULL, NULL},
    {"color", 1, 1, color_fs_bind, NULL, color_fs, NULL, NULL},
    {"sky", 1, 1, sky_fs_bind, sky_fs_fixup, sky_fs, NULL, NULL},
    {"text", 1, 2, text_fs_bind, text_fs_fixup, text_fs, NULL, NULL},
    {"terrain", 0, 1, terrain_vs_bind, terrain_vs_fixup, NULL, terrain_vs_begin, terrain_vs},
};

/* ---- verification against the interpreter ----------------------------------------------- */
#define VERIFY_TRIALS 160
#define VERIFY_UBO_BYTES 256

static int close_enough(float a, float b) {
    if (a != a && b != b) return 1;
    float diff = fabsf(a - b), scale = fabsf(a) > fabsf(b) ? fabsf(a) : fabsf(b);
    return diff <= 2.0e-4f + 2.0e-4f * scale;
}

typedef struct verify_env {
    dxr_uniform_binding uniforms[DXR_MAX_UNIFORMS];
    uint8_t *ubo_mem;
    dxr_texture *tex;
} verify_env;

static int env_init(verify_env *e, const dxr_pipeline *p, uint32_t *rng) {
    memset(e, 0, sizeof(*e));
    e->ubo_mem = (uint8_t *)dxr_alloc(VERIFY_UBO_BYTES * DXR_MAX_UNIFORMS);
    e->tex = dxr_texture_create(NULL, DXR_CT_UNORM8, 4, 32, 32, 1, 4, 0);
    if (!e->ubo_mem || !e->tex) return 0;
    for (size_t i = 0; i < e->tex->data_size; i++) e->tex->data[i] = (uint8_t)rnd(rng);
    for (int i = 0; i < p->num_uniforms; i++) {
        dxr_uniform_binding *b = &e->uniforms[i];
        b->kind = p->uniform_kind[i];
        if (b->kind == DXR_UNIFORM_SAMPLER) {
            b->tex = e->tex;
            b->mip_count = 4;
            b->max_lod = 1000.0f;
        } else {
            b->data = e->ubo_mem + (size_t)i * VERIFY_UBO_BYTES;
            b->size = VERIFY_UBO_BYTES;
            b->ctype = DXR_CT_SINT8;
            b->ncomp = 1;
        }
    }
    return 1;
}

static void env_randomize(verify_env *e, const dxr_pipeline *p, const dxr_fast_kernel *k, const dxr_kdata *d, uint32_t *rng) {
    float *f = (float *)e->ubo_mem;
    for (size_t i = 0; i < (size_t)VERIFY_UBO_BYTES * DXR_MAX_UNIFORMS / 4; i++) f[i] = rndf(rng, -2.0f, 2.0f);
    for (int i = 0; i < p->num_uniforms; i++) {
        dxr_uniform_binding *b = &e->uniforms[i];
        if (b->kind != DXR_UNIFORM_SAMPLER) continue;
        b->min_linear = rnd(rng) & 1; b->mag_linear = rnd(rng) & 1; b->wrap_u = rnd(rng) & 1; b->wrap_v = rnd(rng) & 1;
    }
    if (k->fixup) k->fixup(d, e->uniforms, rng);
}

static void env_free(verify_env *e) {
    dxr_free(e->ubo_mem);
    dxr_texture_destroy(NULL, e->tex);
}

static int verify_fragment(const dxr_pipeline *p, const dxr_fast_kernel *k, const dxr_kdata *d) {
    uint32_t rng = 0x9E3779B9u ^ (uint32_t)dxr_shader_hash(p->fs);
    verify_env env;
    dxr_vm *vm = dxr_vm_create(p->fs);
    int ok = vm && env_init(&env, p, &rng) && p->out_cell[0] != UINT32_MAX;
    int handled = 0, shaded = 0;
    const int need_grad = (p->fs_flags & DXR_SHF_DERIVATIVES) != 0;
    for (int trial = 0; ok && trial < VERIFY_TRIALS; trial++) {
        env_randomize(&env, p, k, d, &rng);
        float vary[DXR_MAX_VARYING_CELLS], gx[DXR_MAX_VARYING_CELLS], gy[DXR_MAX_VARYING_CELLS];
        for (int s = 0; s < DXR_MAX_VARYING_CELLS; s++) {
            vary[s] = (trial & 3) == 3 ? rndf(&rng, -3.0f, 3.0f) : rndf(&rng, 0.0f, 1.2f);
            if ((rnd(&rng) & 31) == 0) vary[s] = 0.0f;
            float mag = (trial & 7) == 7 ? 0.0f : ldexpf(1.0f, -(int)(rnd(&rng) % 12));
            gx[s] = rndf(&rng, -mag, mag);
            gy[s] = rndf(&rng, -mag, mag);
        }
        dxr_frag_ctx ctx;
        memset(&ctx, 0, sizeof(ctx));
        ctx.uniforms = env.uniforms;
        ctx.varyings = vary;
        ctx.dvdx = need_grad ? gx : NULL;
        ctx.dvdy = need_grad ? gy : NULL;
        ctx.frag_coord[0] = rndf(&rng, 0.0f, 1000.0f); ctx.frag_coord[1] = rndf(&rng, 0.0f, 1000.0f);
        ctx.frag_coord[2] = rndf(&rng, 0.0f, 1.0f); ctx.frag_coord[3] = rndf(&rng, 0.01f, 2.0f);
        ctx.front_facing = 1;
        float kout[4] = {0, 0, 0, 0};
        int kr = k->fragment(d, &ctx, kout);
        if (kr < 0) continue;
        dxr_cell *R = dxr_vm_cells(vm);
        for (int m = 0; m < p->num_varyings; m++) memcpy(&R[p->varyings[m].fs_cell], &vary[p->varyings[m].slot], (size_t)p->varyings[m].cells * 4);
        int vr = dxr_vm_run(vm, &ctx);
        handled++;
        if (vr != kr) { ok = 0; break; }
        if (!vr) continue;
        shaded++;
        const dxr_cell *o = &R[p->out_cell[0]];
        for (int c = 0; c < 4; c++) if (!close_enough(o[c].f, kout[c])) { ok = 0; break; }
    }
    if (handled < VERIFY_TRIALS / 2 || shaded < VERIFY_TRIALS / 8) ok = 0;
    env_free(&env);
    dxr_vm_destroy(vm);
    return ok;
}

static int verify_vertex(const dxr_pipeline *p, const dxr_fast_kernel *k, const dxr_kdata *d0) {
    uint32_t rng = 0x85EBCA6Bu ^ (uint32_t)dxr_shader_hash(p->vs);
    verify_env env;
    dxr_vm *vm = dxr_vm_create(p->vs);
    int ok = vm && env_init(&env, p, &rng);
    int handled = 0;
    const uint32_t pos_cell = dxr_shader_position_cell(p->vs);
    for (int trial = 0; ok && trial < VERIFY_TRIALS; trial++) {
        dxr_kdata d = *d0;
        env_randomize(&env, p, k, &d, &rng);
        if (!k->vertex_begin(&d, env.uniforms)) continue;
        dxr_cell attr[DXR_MAX_ATTRIBS][4];
        memset(attr, 0, sizeof(attr));
        dxr_cell *R = dxr_vm_cells(vm);
        for (int a = 0; a < p->num_attribs; a++) {
            if (p->attrib_cell[a] == UINT32_MAX || p->attribs[a].location >= DXR_MAX_ATTRIBS) continue;
            dxr_cell *t = attr[p->attribs[a].location];
            for (int c = 0; c < 4; c++) {
                if (p->attrib_is_int[a]) t[c].i = (int32_t)(rnd(&rng) % 300) - 20;
                else t[c].f = (a & 1) ? rndf(&rng, 0.0f, 1.0f) : rndf(&rng, -40.0f, 40.0f);
            }
            memcpy(&R[p->attrib_cell[a]], t, (size_t)p->attrib_vec[a] * 4);
        }
        float clip[4], vary[DXR_MAX_VARYING_CELLS];
        memset(vary, 0, sizeof(vary));
        k->vertex(&d, env.uniforms, (const dxr_cell (*)[4])attr, clip, vary);
        dxr_frag_ctx ctx;
        memset(&ctx, 0, sizeof(ctx));
        ctx.uniforms = env.uniforms;
        dxr_vm_run(vm, &ctx);
        handled++;
        for (int c = 0; c < 4; c++) if (!close_enough(R[pos_cell + (uint32_t)c].f, clip[c])) ok = 0;
        for (int m = 0; ok && m < p->num_varyings; m++) {
            const dxr_varying_map *vm_ = &p->varyings[m];
            if (vm_->vs_cell == UINT32_MAX) { ok = 0; break; }
            for (int c = 0; c < vm_->cells; c++) if (!close_enough(R[vm_->vs_cell + (uint32_t)c].f, vary[vm_->slot + c])) ok = 0;
        }
    }
    if (handled < VERIFY_TRIALS / 2) ok = 0;
    env_free(&env);
    dxr_vm_destroy(vm);
    return ok;
}

void dxr_fast_attach(struct dxr_pipeline *p) {
    for (size_t i = 0; i < sizeof(g_kernels) / sizeof(g_kernels[0]); i++) {
        const dxr_fast_kernel *k = &g_kernels[i];
        if (k->fragment_stage ? p->fast != NULL : p->fast_vs != NULL) continue;
        for (int variant = 0; variant < k->variants; variant++) {
            dxr_kdata d;
            memset(&d, 0, sizeof(d));
            memset(d.u, -1, sizeof(d.u));
            if (!k->bind(p, &d, variant)) break;
            if (!(k->fragment_stage ? verify_fragment(p, k, &d) : verify_vertex(p, k, &d))) continue;
            dxr_kdata *copy = (dxr_kdata *)dxr_alloc(sizeof(dxr_kdata));
            if (!copy) return;
            *copy = d;
            if (k->fragment_stage) { p->fast = k; p->fs_data = copy; }
            else { p->fast_vs = k; p->vs_data = copy; }
            break;
        }
    }
}

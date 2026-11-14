/* DX Render - Lighting System and verified native shader kernels.
 *
 * The functions below are the Minecraft lighting model (light map addressing, two light
 * diffuse term, linear fog) written natively. Kernels built on them replace the
 * interpreter for hot pipelines, but only after dxr_fast_find() has proven on random
 * inputs that kernel and interpreter agree.
 */
#include "dxr_internal.h"

void dxr_light_lightmap_uv(int u, int v, float out_uv[2]) {
    /* sample_lightmap(): clamp(uv / 256 + 0.5 / 16, 0.5 / 16, 15.5 / 16) */
    float fu = (float)u * (1.0f / 256.0f) + 0.5f / 16.0f, fv = (float)v * (1.0f / 256.0f) + 0.5f / 16.0f;
    out_uv[0] = DXR_CLAMP(fu, 0.5f / 16.0f, 15.5f / 16.0f);
    out_uv[1] = DXR_CLAMP(fv, 0.5f / 16.0f, 15.5f / 16.0f);
}

float dxr_light_diffuse(const float l0[3], const float l1[3], const float n[3]) {
    /* minecraft_mix_light(): min(1, (max(0, L0.N) + max(0, L1.N)) * 0.6 + 0.4) */
    float a = l0[0] * n[0] + l0[1] * n[1] + l0[2] * n[2];
    float b = l1[0] * n[0] + l1[1] * n[1] + l1[2] * n[2];
    if (a < 0.0f) a = 0.0f;
    if (b < 0.0f) b = 0.0f;
    float v = (a + b) * 0.6f + 0.4f;
    return v > 1.0f ? 1.0f : v;
}

float dxr_light_linear_fog(float dist, float start, float end) {
    if (dist <= start) return 0.0f;
    if (dist >= end) return 1.0f;
    return (dist - start) / (end - start);
}

float dxr_light_total_fog(float spherical, float cylindrical, float env_start, float env_end, float rd_start, float rd_end) {
    float a = dxr_light_linear_fog(spherical, env_start, env_end);
    float b = dxr_light_linear_fog(cylindrical, rd_start, rd_end);
    return a > b ? a : b;
}

const dxr_fast_kernel *dxr_fast_find(struct dxr_pipeline *p, dxr_device *dev) {
    (void)p; (void)dev;
    return NULL;
}

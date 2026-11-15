/* DX Render - JNI bridge for studios.artyonix.dxrender.core.DxNative.
 *
 * Draw and pass descriptions are passed as blocks of 64 bit slots written by the Java
 * side (see DxNative.java for the slot numbers); this file must stay in sync with it.
 */
#include "dxr_internal.h"

#include <jni.h>

#define JFN(ret, name) JNIEXPORT ret JNICALL Java_studios_artyonix_dxrender_core_DxNative_##name
#define DEV(h) ((dxr_device *)(intptr_t)(h))
#define TEX(h) ((dxr_texture *)(intptr_t)(h))
#define PIPE(h) ((dxr_pipeline *)(intptr_t)(h))

/* ---- draw block slots (keep in sync with DxNative) ---- */
enum {
    DS_PIPELINE = 0, DS_VB = 1, DS_VB_SIZE = 9, DS_INDEX = 17, DS_INDEX_SIZE = 18, DS_INDEX_BYTES = 19,
    DS_PUSH = 20, DS_PUSH_SIZE = 21, DS_SCISSOR_ON = 22, DS_SCISSOR = 23,
    DS_FIRST = 27, DS_COUNT = 28, DS_BASE_VERTEX = 29, DS_INSTANCES = 30, DS_FIRST_INSTANCE = 31,
    DS_UNIFORMS = 32, DS_UNIFORM_STRIDE = 4, DS_SLOTS = 32 + 4 * DXR_MAX_UNIFORMS
};
/* ---- pass block slots ---- */
enum { PS_NUM_COLOR = 0, PS_COLOR = 1, PS_COLOR_MIP = 9, PS_DEPTH = 17, PS_DEPTH_MIP = 18, PS_AREA = 19, PS_CLEAR_MASK = 23, PS_CLEAR_COLOR = 24, PS_CLEAR_DEPTH_ON = 56, PS_CLEAR_DEPTH = 57, PS_SLOTS = 58 };

static float bits_to_float(int64_t v) {
    uint32_t u = (uint32_t)v;
    float f;
    memcpy(&f, &u, 4);
    return f;
}

static void decode_draw(const int64_t *s, dxr_draw *d) {
    memset(d, 0, sizeof(*d));
    d->pipeline = PIPE(s[DS_PIPELINE]);
    for (int i = 0; i < DXR_MAX_VERTEX_BUFFERS; i++) {
        d->vb[i] = (const uint8_t *)(intptr_t)s[DS_VB + i];
        d->vb_size[i] = (uint64_t)s[DS_VB_SIZE + i];
    }
    d->index_data = (const uint8_t *)(intptr_t)s[DS_INDEX];
    d->index_size = (uint64_t)s[DS_INDEX_SIZE];
    d->index_bytes = (int32_t)s[DS_INDEX_BYTES];
    d->push_constants = (const uint8_t *)(intptr_t)s[DS_PUSH];
    d->push_constants_size = (uint32_t)s[DS_PUSH_SIZE];
    d->scissor_enabled = (int32_t)s[DS_SCISSOR_ON];
    for (int i = 0; i < 4; i++) d->scissor[i] = (int32_t)s[DS_SCISSOR + i];
    d->first = (int32_t)s[DS_FIRST];
    d->count = (int32_t)s[DS_COUNT];
    d->base_vertex = (int32_t)s[DS_BASE_VERTEX];
    d->instance_count = (int32_t)s[DS_INSTANCES];
    d->first_instance = (int32_t)s[DS_FIRST_INSTANCE];
    for (int i = 0; i < DXR_MAX_UNIFORMS; i++) {
        const int64_t *u = s + DS_UNIFORMS + i * DS_UNIFORM_STRIDE;
        dxr_uniform_binding *b = &d->uniforms[i];
        uint64_t f = (uint64_t)u[0];
        b->kind = (uint8_t)(f & 0xff);
        if (b->kind == DXR_UNIFORM_NONE) continue;
        b->min_linear = (uint8_t)(f >> 8 & 0xff);
        b->mag_linear = (uint8_t)(f >> 16 & 0xff);
        b->wrap_u = (uint8_t)(f >> 24 & 0xff);
        b->wrap_v = (uint8_t)(f >> 32 & 0xff);
        b->base_mip = (uint8_t)(f >> 40 & 0xff);
        b->mip_count = (uint8_t)(f >> 48 & 0xff);
        b->max_lod = bits_to_float(u[3]);
        b->ctype = (uint8_t)((uint64_t)u[3] >> 32 & 0xff);
        b->ncomp = (uint8_t)((uint64_t)u[3] >> 40 & 0xff);
        if (b->kind == DXR_UNIFORM_SAMPLER) {
            b->tex = TEX(u[1]);
        } else {
            b->data = (const uint8_t *)(intptr_t)u[1];
            b->size = (uint32_t)u[2];
        }
    }
}

JFN(jstring, nVersion)(JNIEnv *env, jclass cls) { (void)cls; return (*env)->NewStringUTF(env, DXR_VERSION_STRING); }
JFN(jstring, nSimdName)(JNIEnv *env, jclass cls) { (void)cls; return (*env)->NewStringUTF(env, dxr_simd_name()); }
JFN(jstring, nLastError)(JNIEnv *env, jclass cls) { (void)cls; return (*env)->NewStringUTF(env, dxr_last_error()); }
JFN(jint, nCpuCount)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return dxr_cpu_count(); }
JFN(jint, nDrawSlots)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return DS_SLOTS; }
JFN(jint, nPassSlots)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return PS_SLOTS; }
JFN(jboolean, nIsAndroid)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
#if defined(__ANDROID__)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

static dxr_config make_config(jint threads, jint tile, jboolean occlusion, jboolean fast, jboolean simd, jboolean adaptive) {
    dxr_config c;
    c.worker_threads = threads; c.tile_size = tile; c.occlusion_culling = occlusion; c.fast_shaders = fast; c.simd = simd; c.adaptive = adaptive;
    return c;
}

JFN(jlong, nDeviceCreate)(JNIEnv *env, jclass cls, jint threads, jint tile, jboolean occlusion, jboolean fast, jboolean simd, jboolean adaptive) {
    (void)env; (void)cls;
    dxr_config c = make_config(threads, tile, occlusion, fast, simd, adaptive);
    return (jlong)(intptr_t)dxr_device_create(&c);
}

JFN(void, nDeviceDestroy)(JNIEnv *env, jclass cls, jlong dev) { (void)env; (void)cls; dxr_device_destroy(DEV(dev)); }

JFN(void, nDeviceConfigure)(JNIEnv *env, jclass cls, jlong dev, jint threads, jint tile, jboolean occlusion, jboolean fast, jboolean simd, jboolean adaptive) {
    (void)env; (void)cls;
    dxr_config c = make_config(threads, tile, occlusion, fast, simd, adaptive);
    dxr_device_configure(DEV(dev), &c);
}

JFN(jint, nDeviceThreads)(JNIEnv *env, jclass cls, jlong dev) {
    (void)env; (void)cls;
    dxr_config c;
    dxr_device_get_config(DEV(dev), &c);
    return c.worker_threads;
}

JFN(void, nDeviceStats)(JNIEnv *env, jclass cls, jlong dev, jlongArray out, jboolean reset) {
    (void)cls;
    dxr_stats st;
    dxr_device_get_stats(DEV(dev), &st, reset);
    jsize n = (*env)->GetArrayLength(env, out);
    jsize have = (jsize)(sizeof(st) / sizeof(uint64_t));
    (*env)->SetLongArrayRegion(env, out, 0, n < have ? n : have, (const jlong *)&st);
}

JFN(void, nEndFrame)(JNIEnv *env, jclass cls, jlong dev) { (void)env; (void)cls; dxr_device_end_frame(DEV(dev)); }

JFN(jlong, nTextureCreate)(JNIEnv *env, jclass cls, jlong dev, jint ctype, jint ncomp, jint w, jint h, jint layers, jint mips, jboolean cube) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)dxr_texture_create(DEV(dev), ctype, ncomp, w, h, layers, mips, cube);
}

JFN(void, nTextureDestroy)(JNIEnv *env, jclass cls, jlong dev, jlong tex) { (void)env; (void)cls; dxr_texture_destroy(DEV(dev), TEX(tex)); }

JFN(jlong, nTextureLevelAddress)(JNIEnv *env, jclass cls, jlong tex, jint layer, jint mip) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)dxr_texture_level(TEX(tex), layer, mip, NULL, NULL, NULL);
}

JFN(void, nTextureWrite)(JNIEnv *env, jclass cls, jlong tex, jint layer, jint mip, jint x, jint y, jint w, jint h, jlong src, jint row_texels) {
    (void)env; (void)cls;
    dxr_texture_write(TEX(tex), layer, mip, x, y, w, h, (const uint8_t *)(intptr_t)src, row_texels);
}

JFN(void, nTextureRead)(JNIEnv *env, jclass cls, jlong tex, jint layer, jint mip, jint x, jint y, jint w, jint h, jlong dst) {
    (void)env; (void)cls;
    dxr_texture_read(TEX(tex), layer, mip, x, y, w, h, (uint8_t *)(intptr_t)dst);
}

JFN(void, nTextureCopy)(JNIEnv *env, jclass cls, jlong src, jlong dst, jint mip, jint dx, jint dy, jint sx, jint sy, jint w, jint h) {
    (void)env; (void)cls;
    dxr_texture_copy(TEX(src), TEX(dst), mip, dx, dy, sx, sy, w, h);
}

JFN(void, nTextureClearColor)(JNIEnv *env, jclass cls, jlong tex, jint mip, jfloat r, jfloat g, jfloat b, jfloat a, jint x, jint y, jint w, jint h) {
    (void)env; (void)cls;
    float c[4] = {r, g, b, a};
    dxr_texture_clear_color(TEX(tex), mip, c, x, y, w, h);
}

JFN(void, nTextureClearDepth)(JNIEnv *env, jclass cls, jlong tex, jint mip, jfloat depth, jint x, jint y, jint w, jint h) {
    (void)env; (void)cls;
    dxr_texture_clear_depth(TEX(tex), mip, depth, x, y, w, h);
}

JFN(jlong, nPipelineCreate)(JNIEnv *env, jclass cls, jlong dev, jlong blob, jint len) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)dxr_pipeline_create(DEV(dev), (const uint8_t *)(intptr_t)blob, (size_t)len);
}

JFN(void, nPipelineDestroy)(JNIEnv *env, jclass cls, jlong dev, jlong pipe) { (void)env; (void)cls; dxr_pipeline_destroy(DEV(dev), PIPE(pipe)); }

JFN(jstring, nPipelineInfo)(JNIEnv *env, jclass cls, jlong pipe) { (void)cls; return (*env)->NewStringUTF(env, dxr_pipeline_info(PIPE(pipe))); }

JFN(jboolean, nPipelineFast)(JNIEnv *env, jclass cls, jlong pipe) { (void)env; (void)cls; return dxr_pipeline_fast_path(PIPE(pipe)) ? JNI_TRUE : JNI_FALSE; }

JFN(jint, nPassBegin)(JNIEnv *env, jclass cls, jlong dev, jlong block) {
    (void)env; (void)cls;
    const int64_t *s = (const int64_t *)(intptr_t)block;
    dxr_pass_desc d;
    memset(&d, 0, sizeof(d));
    d.num_color = (int32_t)s[PS_NUM_COLOR];
    for (int i = 0; i < DXR_MAX_COLOR_TARGETS; i++) {
        d.color[i] = TEX(s[PS_COLOR + i]);
        d.color_mip[i] = (int32_t)s[PS_COLOR_MIP + i];
        for (int c = 0; c < 4; c++) d.clear_color[i][c] = bits_to_float(s[PS_CLEAR_COLOR + i * 4 + c]);
    }
    d.depth = TEX(s[PS_DEPTH]);
    d.depth_mip = (int32_t)s[PS_DEPTH_MIP];
    for (int i = 0; i < 4; i++) d.area[i] = (int32_t)s[PS_AREA + i];
    d.clear_color_mask = (int32_t)s[PS_CLEAR_MASK];
    d.clear_depth_enabled = (int32_t)s[PS_CLEAR_DEPTH_ON];
    d.clear_depth = bits_to_float(s[PS_CLEAR_DEPTH]);
    return dxr_pass_begin(DEV(dev), &d);
}

JFN(jint, nDraw)(JNIEnv *env, jclass cls, jlong dev, jlong block) {
    (void)env; (void)cls;
    dxr_draw d;
    decode_draw((const int64_t *)(intptr_t)block, &d);
    return dxr_pass_draw(DEV(dev), &d);
}

/* Several draws that share all state: (first, count, base_vertex) triples as 32 bit ints. */
JFN(jint, nMultiDraw)(JNIEnv *env, jclass cls, jlong dev, jlong block, jlong params, jint draw_count) {
    (void)env; (void)cls;
    dxr_draw d;
    decode_draw((const int64_t *)(intptr_t)block, &d);
    const int32_t *p = (const int32_t *)(intptr_t)params;
    int ok = 1;
    for (int i = 0; i < draw_count; i++) {
        d.first = p[3 * i];
        d.count = p[3 * i + 1];
        d.base_vertex = p[3 * i + 2];
        ok &= dxr_pass_draw(DEV(dev), &d);
    }
    return ok;
}

JFN(void, nPassEnd)(JNIEnv *env, jclass cls, jlong dev) { (void)env; (void)cls; dxr_pass_end(DEV(dev)); }

JFN(void, nPresentBlit)(JNIEnv *env, jclass cls, jlong dev, jlong src, jint sw, jint sh, jint sstride, jlong dst, jint dw, jint dh, jint dstride, jint fmt) {
    (void)env; (void)cls;
    dxr_present_blit(DEV(dev), (const uint8_t *)(intptr_t)src, sw, sh, sstride, (uint8_t *)(intptr_t)dst, dw, dh, dstride, fmt);
}

JFN(jint, nPresentAndroid)(JNIEnv *env, jclass cls, jlong dev, jlong window, jlong src, jint sw, jint sh, jint sstride) {
    (void)env; (void)cls;
    return dxr_present_android(DEV(dev), (void *)(intptr_t)window, (const uint8_t *)(intptr_t)src, sw, sh, sstride);
}

JFN(jstring, nProfileText)(JNIEnv *env, jclass cls, jlong dev, jboolean reset) {
    (void)cls;
    static _Thread_local char buf[32768];
    dxr_device_profile(DEV(dev), buf, sizeof(buf), reset);
    return (*env)->NewStringUTF(env, buf);
}

JFN(jstring, nKbaseProbe)(JNIEnv *env, jclass cls, jboolean run_commands) {
    (void)cls;
    char *buf = (char *)malloc(16384);
    if (!buf) return (*env)->NewStringUTF(env, "out of memory");
    dxr_kbase_probe(buf, 16384, run_commands);
    jstring s = (*env)->NewStringUTF(env, buf);
    free(buf);
    return s;
}

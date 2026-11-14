#!/usr/bin/env bash
# Builds libdxrender.so. Usage: native/build.sh <linux-x86_64|android-arm64|linux-arm64> <output dir>
# android-arm64 needs ANDROID_NDK_HOME (NDK r27+); linux targets need clang and a JDK (JAVA_HOME).
set -euo pipefail
target="$1"
out="$2"
here="$(cd "$(dirname "$0")" && pwd)"
src=("$here"/src/dxr_memory.c "$here"/src/dxr_sched.c "$here"/src/dxr_texture.c "$here"/src/dxr_spirv.c "$here"/src/dxr_pipeline.c
     "$here"/src/dxr_raster.c "$here"/src/dxr_device.c "$here"/src/dxr_fast.c "$here"/src/dxr_jni.c)
common=(-std=c11 -O3 -fPIC -shared -fvisibility=hidden -fno-math-errno -fno-trapping-math -ffp-contract=off -Wall -Wextra -D_GNU_SOURCE -I"$here/include")
mkdir -p "$out"
case "$target" in
  linux-x86_64)
    jni="${JAVA_HOME:?JAVA_HOME is required}/include"
    clang "${common[@]}" -msse2 -I"$jni" -I"$jni/linux" "${src[@]}" -o "$out/libdxrender.so" -lm -lpthread
    ;;
  linux-arm64)
    jni="${JAVA_HOME:?JAVA_HOME is required}/include"
    clang --target=aarch64-linux-gnu "${common[@]}" -I"$jni" -I"$jni/linux" "${src[@]}" -o "$out/libdxrender.so" -lm -lpthread
    ;;
  android-arm64)
    ndk="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is required}"
    cc="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"
    # 16 KiB page alignment is required by current Android releases.
    "$cc" "${common[@]}" -march=armv8.2-a "${src[@]}" -o "$out/libdxrender.so" -lm -landroid -llog \
      -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 -Wl,--no-undefined
    "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$out/libdxrender.so"
    ;;
  *) echo "unknown target $target" >&2; exit 2 ;;
esac
echo "built $out/libdxrender.so"

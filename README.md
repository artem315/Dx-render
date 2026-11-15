# DX Render

Graphics backend for **Minecraft Java 26.3** (Fabric Loader 0.20.1, Fabric API 0.166.2+26.3, Java 25),
made by ARTYONIX STUDIOS for DX Launcher-X on Android ARM64.

The game talks only to the DX Render API. Two backends execute it:

* **GPU** — a Vulkan driver written for DX Render (`gpu/vulkan`).
* **CPU** — DX Render's own tile rasterizer and SPIR-V interpreter in C (`native/`), with NEON on ARM64.

`AUTO` picks the GPU backend when a hardware Vulkan device works, otherwise the CPU backend.
F3 shows `DX Render GPU …` / `DX Render CPU …` on the graphics line; F8 opens the settings.

* Experimental GPU access **without Vulkan and OpenGL** (Mali kernel driver, F8 → *Kernel GPU probe*):
  [docs/GPU_KERNEL_INTERFACE.md](docs/GPU_KERNEL_INTERFACE.md)
* Architecture, tests, measured results, Android instructions, limitations: [docs/GPU_BACKEND.md](docs/GPU_BACKEND.md)
* Release jar: [release/dxrender-1.0.0-ALPHA.3.jar](release/dxrender-1.0.0-ALPHA.3.jar)

## Build

```
# JDK 25, clang; Android NDK r27+ for the arm64 library (ANDROID_NDK_HOME or -PndkHome=)
./gradlew build            # → build/libs/dxrender-<version>.jar
./runclient.sh 120 -Ddxrender.mode=gpu -Ddxrender.selftest=menu   # real client under Xvfb
```

Version: `mod_version` + `mod_stage` in `gradle.properties` (stage increases by one per change).

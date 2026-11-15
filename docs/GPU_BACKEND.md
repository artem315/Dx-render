# DX Render — GPU Backend 1.0

## Architecture

```
Minecraft 26.3 (renderpearl SPI)
        │  GpuDevice / CommandEncoder / RenderPass / GpuSurface
        ▼
DX Render API  (backend independent)
  device/   DxDevice, DxBuffer, DxTexture, DxTextureView, DxSampler,
            DxFramebuffer, DxSwapchain, DxSurface, DxFence
  command/  DxCommandEncoder, DxRenderPass  → record into
            DxCommandBuffer (flat 64-bit command stream)
            DxCommandQueue  (submission, frame numbering, fences)
            DxDescriptorSet (resources bound to a pipeline's slots)
  shader/   DxShader   (SPIR-V validation, reflection, binding remap)
            DxPipeline (vertex layout + fixed-function state)
  memory/   DxMemoryAllocator, DxMemoryManager (deferred release),
            DxTransientMemory (per-frame pooled scratch memory)
        │  gpu.DxDriver
        ├──────────────────────────────┐
        ▼                              ▼
gpu/vulkan  VkDriver (GPU)       gpu/cpu  CpuDriver (CPU)
  VkMemory, VkSwapchain,           native/src: tile rasterizer,
  VkFormats                        SPIR-V interpreter, verified
        │                          native shader kernels
        ▼                              ▼
Vulkan driver → GPU → surface    ANativeWindow_lock / SDL window surface
```

The game never sees Vulkan objects. It creates resources through `DxDevice`
and records commands through `DxCommandEncoder`/`DxRenderPass`; both write
into a `DxCommandBuffer`. The active `DxDriver` replays that stream:
the CPU driver immediately, command by command, the Vulkan driver once per
render pass.

The Vulkan driver is written for DX Render against the Vulkan API directly
(LWJGL bindings). It does not use or wrap Minecraft's own OpenGL or Vulkan
backends (`com.mojang.renderpearl.backend.opengl/vulkan`).

## Render modes

`config/dxrender.json` → `"renderMode"` (or the in-game screen, or
`-Ddxrender.mode=auto|gpu|cpu` for one run):

| Mode | Behaviour |
|------|-----------|
| `AUTO` | Vulkan GPU backend if a **hardware** Vulkan 1.2 device can render and present to the window; otherwise the CPU backend. Software Vulkan implementations (lavapipe, SwiftShader) are not accepted in AUTO. |
| `GPU`  | Vulkan GPU backend only. If it cannot start, the game stops with a message saying why (missing loader, device, extension, feature, surface). No silent fallback. |
| `CPU`  | The DX Render CPU rasterizer and SPIR-V interpreter (unchanged). |

Settings screen: **F8**, or *Options → Video Settings → DX Render...*.
The render mode applies after a restart; the other options apply immediately.

## What the Vulkan driver does

| Area | Implementation |
|------|----------------|
| Device selection | Enumerates physical devices; requires Vulkan ≥ 1.2 (the game's shaders are SPIR-V 1.5), `VK_KHR_swapchain`, a queue family with graphics **and** presentation support for the window. Prefers discrete > integrated > virtual > other. `-Ddxrender.vkDevice=<substring>` forces one. Rejected devices are listed with the reason. |
| Features | Queried with `vkGetPhysicalDeviceFeatures2`; only existing features are enabled: `independentBlend`, `multiDrawIndirect`, `drawIndirectFirstInstance`, `fillModeNonSolid`, `samplerAnisotropy`, `shaderDrawParameters`. The game is told exactly what is available (e.g. no multi-draw-indirect → it uses per-section draws). |
| Formats | `vkGetPhysicalDeviceFormatProperties` checked before creating textures (sampled / color / depth attachment), vertex attributes (vertex buffer feature, 3-component fallback), texel buffers and the swapchain blit. |
| Memory | `VkMemory`: 32 MiB host-visible coherent blocks for buffers (persistently mapped: the API writes them with plain memory copies), 64 MiB device-local blocks for images, first-fit sub-allocation with coalescing, dedicated allocations for large resources, empty blocks returned to the driver. Every release is delayed two frames (`DxMemoryManager`). |
| Buffers | Vertex, index, uniform (dynamic offsets up to the device limit, plain descriptors beyond), storage, indirect and uniform texel buffers (per-frame `VkBufferView` cache). |
| Textures | 2D and cube images, mip levels, layout tracking per mip, copies, blits, read-back for screenshots. |
| Pipelines | One `VkPipeline` per pipeline and render-pass compatibility class, built on the game's compile threads where possible, `VkPipelineCache` persisted to `dxrender/vulkan-pipeline-cache.bin`. Shader modules are cached by content hash (hit/miss counters in the profiler). |
| Render passes | Cached by attachment formats and load ops. A whole-texture clear issued right before a pass becomes that pass's `LOAD_OP_CLEAR` (tile GPUs never read the old contents); otherwise `LOAD_OP_LOAD`; store is always `STORE` because results are sampled or presented later. Partial clears use `vkCmdClearAttachments`. |
| Descriptors | Per-frame descriptor pools; identical binding sets are reused within the frame through a hash cache. |
| Command buffers | Two frames in flight, one command pool per frame reset and reused every other frame; a setup command buffer carries first-use image transitions. |
| Synchronisation | One fence per frame (the only CPU wait), swapchain acquire/present semaphores, explicit image and buffer barriers. Fences given to the game are "frame N finished". |
| Swapchain | FIFO for vsync, IMMEDIATE/MAILBOX when available otherwise; recreated on resize, `SUBOPTIMAL`/`OUT_OF_DATE`; surface destroyed and recreated on `SURFACE_LOST` (Android background/foreground). |
| GPU timing | Timestamp queries at the start and end of each frame when the queue supports them. |
| Debugging | `-Ddxrender.vkValidation=true` enables `VK_LAYER_KHRONOS_validation` if installed and logs its messages. |

## SPIR-V

`DxShader.parse` validates every module before any backend sees it: magic
and endianness, version (1.0–1.6), instruction stream bounds, `Shader`
capability, Logical addressing model, a `main` entry point of the right
stage, `OriginUpperLeft`, and that a vertex shader writes `gl_Position`.
It reflects the module's descriptors and fragment outputs.

For Vulkan, `DxShader.checkVulkan` rejects modules whose SPIR-V version is
newer than the device's Vulkan version accepts, and capabilities not backed
by enabled features. `DxShader.withBindings` rewrites (or inserts)
`DescriptorSet`/`Binding` decorations so that both stages agree with the
pipeline's uniform order, and fails with a message if a shader uses a
resource the pipeline does not declare or declares it with another type.
Errors appear in the log as `DX Render could not build pipeline <name>: <reason>`.

The CPU backend still executes the same SPIR-V with its interpreter.

## Tests

`./runclient.sh <seconds> -D...` starts the real client (Xvfb) with the
self-test (`integration/DxSelfTest`):

```
# menu, GPU backend, Vulkan validation on
./runclient.sh 100 -Ddxrender.mode=gpu -Ddxrender.vkValidation=true -Ddxrender.selftest=menu -Ddxrender.selftest.seconds=25
# world: creates a fixed-seed creative world, fixed spectator view, screenshots every 12 s, two window resizes
./runclient.sh 300 -Ddxrender.mode=gpu -Ddxrender.selftest=world -Ddxrender.selftest.seconds=60 \
   -Ddxrender.selftest.renderDistance=8 -Ddxrender.selftest.screenshotEvery=12 -Ddxrender.selftest.resize=true
# same scene on the CPU backend for comparison
./runclient.sh 300 -Ddxrender.mode=cpu -Ddxrender.selftest=world -Ddxrender.selftest.seconds=50 -Ddxrender.selftest.renderDistance=8
```

Screenshots are taken with the game's own screenshot function, i.e. through
the backend's texture read-back. Statistics are logged every 5 s.

### Results in the development sandbox (no GPU available)

The sandbox has 4 x86 cores and **no GPU**. The GPU backend was exercised on
Mesa *lavapipe* (llvmpipe), a Vulkan implementation that itself runs on the
CPU. These numbers therefore validate correctness and the driver's own
overhead, not GPU speed.

Same scene (fixed seed and camera, render distance 8, 854×480, ≈256k
submitted triangles per frame):

| Backend | FPS | Frame | 99% | DX Render CPU time | Draw calls |
|---------|-----|-------|-----|--------------------|------------|
| GPU on lavapipe | 15.9 | 61 ms | 80 ms | 1.5 ms | 235 (multi-draw indirect) |
| CPU rasterizer  | 7.5  | 140 ms | 175 ms | 124 ms | 632 |

Checked on the GPU backend: start-up, menu, textures and fonts, single
player world creation, chunks and blocks, water, grass/flower cut-outs,
lighting, entities (villagers' animals), GUI and HUD, window resizes
(854×480 → 1024×600 → 640×400). Vulkan validation reported no errors; the
only warnings (`Undefined-Value-ShaderInputNotProduced`, attachment 1) are a
false positive of validation layer 1.3.275 for arrayed fragment outputs
(`out vec4 coeff[2]` in the game's OIT shaders writes locations 0 and 1).

Stability: 5 minutes in the same world on the GPU backend: frame rate steady
(15–17 FPS on lavapipe), GPU memory constant (576 MiB reserved / 503 MiB
used), framebuffer and descriptor caches bounded, no errors.

The settings screen (F8) and the profiler overlay were checked in the same
run (screenshots in the self-test output).

**Not tested here:** any real GPU, Android, the Immortalis-G925, surface loss
on Android, particles-heavy scenes, the Nether/End.

## Android (DX Launcher-X) — how to verify

1. Install Fabric Loader 0.20.1 + Fabric API 0.166.2+26.3 for Minecraft 26.3
   and put `dxrender-1.0.0-ALPHA.2.jar` into `mods/`. The jar contains
   `natives/android-arm64/libdxrender.so` (CPU backend; the GPU backend is
   pure Java on top of the system Vulkan driver).
2. The GPU backend needs the system Vulkan driver and an `ANativeWindow`
   that no other API has claimed. In the launcher pick a renderer mode that
   does **not** create an EGL surface for the game window (the Vulkan option
   used for the game's own Vulkan backend). If the surface is already owned
   by EGL, the log says `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`.
3. First run with `"renderMode": "GPU"` in `config/dxrender.json` so that a
   failure is reported instead of hidden by AUTO. Check `latest.log` for
   `DX Render GPU backend: Mali-G925 ...` (or the reason it refused).
4. Press F3: the graphics line reads `DX Render GPU 1.0.0-ALPHA.2 (Vulkan …)`.
   Press F8 for the settings screen; the overlay shows FPS, frame time, CPU
   and GPU time (Mali supports timestamp queries), draw calls, triangles,
   VRAM estimate, shader cache and pipeline compile time.
5. Enable *Benchmark Log* and compare `dxrender-bench.csv` between GPU and
   CPU mode in the same place of the same world.
6. Background the app and return: the swapchain must be recreated (log line
   `Couldn't acquire/configure surface` once, then normal frames).

## Known limitations (1.0)

* Not run on Android or on any hardware GPU yet (see above).
* AUTO refuses software Vulkan; use `GPU` mode to test on lavapipe/SwiftShader.
* No MSAA, no wireframe unless the device has `fillModeNonSolid`.
* Push constants are limited to the device's `maxPushConstantsSize`
  (pipelines asking for more are rejected with a message).
* GPU texture memory is not tracked per texture against the device budget;
  allocation failure is reported as out of memory.
* The CPU backend options (threads, tiles, occlusion culling, texture cache,
  native shader kernels) do not affect the GPU backend.
* The pipeline is built for the most likely render target on a worker
  thread; other target combinations compile on first use on the render
  thread (shown as pipeline compile time in the profiler).

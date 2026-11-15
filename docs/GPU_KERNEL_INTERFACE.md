# DX Render — GPU without Vulkan and OpenGL (kernel interface, experimental)

## What exists today

`native/src/dxr_kbase.c` talks to the Arm Mali kernel driver (**kbase**,
`/dev/mali0`) directly. No Vulkan, no OpenGL ES, no vendor user-space driver
is involved. It climbs six steps and reports each one:

| Stage | What it proves |
|-------|----------------|
| 1 | The app can open the GPU device node (SELinux allows `gpu_device`). |
| 2 | Interface handshake: CSF (Mali-G610 and newer) or Job Manager, kernel API version. |
| 3 | A GPU context was created; kernel driver (DDK) version string. |
| 4 | GPU properties: GPU_ID → model (e.g. *Immortalis-G925*, arch v13), shader core mask, tiler, L2, max frequency, memory. |
| 5 | GPU memory: allocated by the GPU's MMU, mapped into the process at the same address for CPU and GPU, written and read back, freed. |
| 6 | **GPU command execution:** a queue group and a queue are created, and the GPU's command stream frontend runs a program written by DX Render (store two words to memory, flush L2/LSC caches). The CPU sees the GPU's write. |

Run it on the phone: **F8 → Kernel GPU probe… →** *Probe driver + memory*
(stages 1–5) or *+ GPU command test* (stage 6). The report is shown on screen,
written to `latest.log` and saved to `.minecraft/dxrender/gpu-kernel-probe.txt`.
On a PC (`-Ddxrender.kernelProbe=true`) it reports stage 0: there is no Mali GPU.

The ABI (structures, ioctl numbers, memory flags, user I/O page registers)
follows the kbase UAPI headers, checked against CSF interface versions 1.30
(Rockchip kernel) and 1.33 (Pixel kernel). Older layouts of
`QUEUE_GROUP_CREATE` (1.6, 1.18) are used automatically. Command stream
instruction encodings (MOVE48, MOVE32, STORE_MULTIPLE, WAIT, FLUSH_CACHE2)
follow the publicly documented, reverse engineered CSF format, identical for
architectures v10–v13 (Mali-G610 … Immortalis-G925).

### Tested

* Host simulation (`native/tests/kbase_sim_test.c`, built with ASan/UBSan):
  the probe runs against a simulated CSF kbase that answers the ioctls, maps
  memory and emulates the command stream frontend for the instructions used.
  9/9 checks pass: full ladder, G925 decoding, memory released, timeout path,
  fallback to the 1.18 ioctl layout, missing device, report truncation.
* Real Minecraft client on PC: probe runs at start-up and from the screen,
  reports stage 0 cleanly, nothing else affected.
* **Not tested on any Mali GPU.** Whether stages 1–6 pass on the
  Dimensity 9500s depends on MediaTek's kernel driver and SELinux policy, which
  only the phone can tell.

## Answers to the original questions

1. **Which GPU driver is installed?** Arm's Mali kbase kernel driver (CSF
   variant for 5th-gen GPUs) plus Arm's proprietary user-space GLES/Vulkan
   libraries shipped by MediaTek. The exact kernel driver version is printed
   by stage 3.
2. **Which kernel interfaces can an ordinary app use?** `/dev/mali0`
   (ioctl + mmap). Android allows it for apps because the vendor GPU driver runs
   inside the app process. Vendors may narrow the allowed ioctls with SELinux;
   the probe shows the failing step and `errno`. Display hardware
   (`/dev/dri/*`, HWC) is **not** accessible to apps.
3. **Can commands be sent to the GPU without a standard API?** Technically yes,
   through kbase CSF queues, exactly as the vendor driver does. Stage 6 tests it.
4. **Can GPU memory be managed?** Yes: MEM_ALLOC / mmap / MEM_FREE (stage 5);
   also growable heaps, imports and aliases exist in the same interface.
5. **How are frames shown?** Not by the GPU directly. Every frame must reach
   an Android `Surface`. Available path: copy the GPU-rendered image into a
   `ANativeWindow_lock` buffer (the CPU backend already presents this way).
   Zero-copy would need the dma-buf of the Surface's buffers, which the public
   NDK does not expose to apps.
6. **Restrictions:** SELinux (device node and ioctl allowlists), kbase ABI
   changes between driver releases (handled by version handshake + fallbacks
   where known), GPU firmware validates and terminates faulty queue groups
   (a bad command stream kills only our context), and no documentation from
   Arm for the formats below.

## What is missing before this path can draw Minecraft

| Component | Status |
|-----------|--------|
| Tiler heap and tiler context (HEAP_SET, chunked heap protocol) | not started — needs hardware to develop |
| Render target / framebuffer descriptors, RUN_FRAGMENT | not started |
| Vertex/IDVS jobs: shader program, attribute, buffer and resource-table descriptors, RUN_IDVS2 | not started |
| Texture and sampler descriptors, texture layouts (AFBC/u-interleaved) | not started |
| **Shader compiler SPIR-V → Mali v13 machine code** (register allocation, scheduling, instruction encoding) | not started — the largest piece; the instruction set is undocumented by Arm |
| Synchronisation (sync objects, CS events) | not started |
| Presentation (copy to ANativeWindow) | exists in the CPU backend, reusable |

None of these can be written blind: every format error shows up only as a
GPU fault on the device. Development needs a loop of *build → run on the phone
→ read the report*. Stage 6 passing on the Dimensity 9500s is the prerequisite
for starting that loop; the next step would be a compute job running a
hand-encoded shader, then the compiler.

Until then, the backends that draw the game are the CPU rasterizer and the
Vulkan GPU driver (see GPU_BACKEND.md).

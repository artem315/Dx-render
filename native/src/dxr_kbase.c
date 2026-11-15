/* DX Render - GPU Interface, kernel level (experimental).
 *
 * Talks to the Arm Mali kernel driver (kbase, /dev/mali0) directly: no Vulkan, no OpenGL ES,
 * no vendor user space driver. This is the interface the vendor's own GLES/Vulkan driver uses
 * from inside every app process, so it involves no privilege the app does not already have;
 * whether SELinux and the vendor kernel allow it on a given phone is exactly what the probe
 * reports.
 *
 * The probe climbs these steps and reports each one:
 *   1. open the device node
 *   2. API version handshake (CSF or Job Manager interface)
 *   3. create the context (SET_FLAGS)
 *   4. read the GPU properties (model, cores, L2, memory)
 *   5. allocate GPU memory, map it into the process, write and read it, free it
 *   6. (CSF GPUs, on request) create a command queue group and a queue, and let the GPU's
 *      command stream frontend execute a short program that stores two words to memory and
 *      flushes the GPU caches - i.e. GPU command execution without any graphics API.
 *
 * ABI: structures and ioctl numbers follow the kbase UAPI headers (CSF interface 1.x, checked
 * against versions 1.30 and 1.33). Command stream instruction encodings follow the publicly
 * documented (reverse engineered) CSF format, identical for architectures v10 to v13
 * (Mali-G610 ... Immortalis-G925). Everything here was written for DX Render.
 *
 * What is NOT here, and why: drawing a frame through this path additionally needs the tiler
 * heap protocol, the fragment/IDVS job descriptors and a compiler from SPIR-V to the GPU's
 * shader instruction set. Arm documents none of them and they cannot be developed without the
 * hardware at hand; see docs/GPU_KERNEL_INTERFACE.md.
 */
#include "dxr_internal.h"

#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

/* ---- kbase UAPI (subset) ---------------------------------------------------------------- */
#define KB_TYPE 0x80

struct kb_version_check { uint16_t major, minor; };
struct kb_set_flags { uint32_t create_flags; };
struct kb_get_gpuprops { uint64_t buffer; uint32_t size, flags; };
union kb_mem_alloc {
    struct { uint64_t va_pages, commit_pages, extension, flags; } in;
    struct { uint64_t flags, gpu_va; } out;
};
struct kb_mem_free { uint64_t gpu_addr; };
struct kb_get_ddk_version { uint64_t version_buffer; uint32_t size, padding; };
struct kb_cs_queue_register { uint64_t buffer_gpu_addr; uint32_t buffer_size; uint8_t priority, padding[3]; };
struct kb_cs_queue_kick { uint64_t buffer_gpu_addr; };
union kb_cs_queue_bind {
    struct { uint64_t buffer_gpu_addr; uint8_t group_handle, csi_index, padding[6]; } in;
    struct { uint64_t mmap_handle; } out;
};
struct kb_cs_queue_terminate { uint64_t buffer_gpu_addr; };
struct kb_group_out { uint8_t group_handle, padding[3]; uint32_t group_uid; };
union kb_group_create_1_6 {
    struct { uint64_t tiler_mask, fragment_mask, compute_mask; uint8_t cs_min, priority, tiler_max, fragment_max, compute_max, padding[3]; } in;
    struct kb_group_out out;
};
union kb_group_create_1_18 {
    struct { uint64_t tiler_mask, fragment_mask, compute_mask; uint8_t cs_min, priority, tiler_max, fragment_max, compute_max, csi_handlers, padding[2];
             uint64_t dvs_buf; } in;
    struct kb_group_out out;
};
union kb_group_create {
    struct { uint64_t tiler_mask, fragment_mask, compute_mask; uint8_t cs_min, priority, tiler_max, fragment_max, compute_max, csi_handlers, neural_max,
             cs_fault_report_enable; uint64_t dvs_buf, neural_mask; uint8_t comp_pri_threshold, comp_pri_ratio, padding[62]; } in;
    struct kb_group_out out;
};
struct kb_group_term { uint8_t group_handle, padding[7]; };
union kb_get_glb_iface {
    struct { uint32_t max_group_num, max_total_stream_num; uint64_t groups_ptr, streams_ptr; } in;
    struct { uint32_t glb_version, features, group_num, prfcnt_size, total_stream_num, instr_features; } out;
};

_Static_assert(sizeof(struct kb_get_gpuprops) == 16, "abi");
_Static_assert(sizeof(union kb_mem_alloc) == 32, "abi");
_Static_assert(sizeof(struct kb_cs_queue_register) == 16, "abi");
_Static_assert(sizeof(union kb_cs_queue_bind) == 16, "abi");
_Static_assert(sizeof(union kb_group_create_1_6) == 32, "abi");
_Static_assert(sizeof(union kb_group_create_1_18) == 40, "abi");
_Static_assert(sizeof(union kb_group_create) == 112, "abi");
_Static_assert(sizeof(union kb_get_glb_iface) == 24, "abi");

#define KB_VERSION_CHECK_JM   _IOWR(KB_TYPE, 0, struct kb_version_check)
#define KB_SET_FLAGS          _IOW(KB_TYPE, 1, struct kb_set_flags)
#define KB_GET_GPUPROPS       _IOW(KB_TYPE, 3, struct kb_get_gpuprops)
#define KB_MEM_ALLOC          _IOWR(KB_TYPE, 5, union kb_mem_alloc)
#define KB_MEM_FREE           _IOW(KB_TYPE, 7, struct kb_mem_free)
#define KB_GET_DDK_VERSION    _IOW(KB_TYPE, 13, struct kb_get_ddk_version)
#define KB_CS_QUEUE_REGISTER  _IOW(KB_TYPE, 36, struct kb_cs_queue_register)
#define KB_CS_QUEUE_KICK      _IOW(KB_TYPE, 37, struct kb_cs_queue_kick)
#define KB_CS_QUEUE_BIND      _IOWR(KB_TYPE, 39, union kb_cs_queue_bind)
#define KB_CS_QUEUE_TERMINATE _IOW(KB_TYPE, 41, struct kb_cs_queue_terminate)
#define KB_CS_GROUP_CREATE_1_6  _IOWR(KB_TYPE, 42, union kb_group_create_1_6)
#define KB_CS_GROUP_TERMINATE _IOW(KB_TYPE, 43, struct kb_group_term)
#define KB_CS_GET_GLB_IFACE   _IOWR(KB_TYPE, 51, union kb_get_glb_iface)
#define KB_VERSION_CHECK_CSF  _IOWR(KB_TYPE, 52, struct kb_version_check)
#define KB_CS_GROUP_CREATE_1_18 _IOWR(KB_TYPE, 58, union kb_group_create_1_18)
#define KB_CS_GROUP_CREATE    _IOWR(KB_TYPE, 58, union kb_group_create)

#define KB_CSF_UK_MAJOR 1
#define KB_CSF_UK_MINOR 33
#define KB_JM_UK_MAJOR 11
#define KB_JM_UK_MINOR 46

#define MEM_CPU_RD (1ull << 0)
#define MEM_CPU_WR (1ull << 1)
#define MEM_GPU_RD (1ull << 2)
#define MEM_GPU_WR (1ull << 3)
#define MEM_SAME_VA (1ull << 13)

/* GPU property ids (KBASE_GPUPROP_*) */
enum {
    PROP_PRODUCT_ID = 1, PROP_VERSION_STATUS = 2, PROP_MINOR_REVISION = 3, PROP_MAJOR_REVISION = 4, PROP_GPU_FREQ_KHZ_MAX = 6,
    PROP_GPU_AVAILABLE_MEMORY_SIZE = 12, PROP_L2_LOG2_CACHE_SIZE = 14, PROP_L2_NUM_L2_SLICES = 15, PROP_MAX_THREADS = 18,
    PROP_RAW_SHADER_PRESENT = 25, PROP_RAW_TILER_PRESENT = 26, PROP_RAW_L2_PRESENT = 27, PROP_RAW_GPU_ID = 55, PROP_RAW_COHERENCY_MODE = 60,
    PROP_MAX = 128
};

/* CS user I/O page registers */
#define CS_INSERT 0x00
#define CS_EXTRACT_INIT 0x08
#define CS_EXTRACT 0x00
#define CS_ACTIVE 0x08

/* ---- command stream instructions (64 bit, opcode in bits 56..63) ------------------------ */
static uint64_t cs_move48(unsigned reg, uint64_t imm) { return (1ull << 56) | ((uint64_t)reg << 48) | (imm & 0xFFFFFFFFFFFFull); }
static uint64_t cs_move32(unsigned reg, uint32_t imm) { return (2ull << 56) | ((uint64_t)reg << 48) | imm; }
static uint64_t cs_wait(uint16_t slots) { return (3ull << 56) | ((uint64_t)slots << 16); }
static uint64_t cs_store_multiple(unsigned first_reg, unsigned addr_reg, uint16_t mask, int16_t offset) {
    return (21ull << 56) | ((uint64_t)first_reg << 48) | ((uint64_t)addr_reg << 40) | ((uint64_t)mask << 16) | (uint16_t)offset;
}
static uint64_t cs_flush_cache2(unsigned l2, unsigned lsc, unsigned other, uint16_t wait_slots, unsigned flush_id_reg, unsigned signal_slot) {
    return (36ull << 56) | ((uint64_t)signal_slot << 48) | ((uint64_t)flush_id_reg << 40) | ((uint64_t)wait_slots << 16) | ((uint64_t)other << 8) |
           ((uint64_t)lsc << 4) | l2;
}

/* ---- report -------------------------------------------------------------------------- */
typedef struct report {
    char *buf;
    size_t cap, len;
} report;

static void say(report *r, const char *fmt, ...) {
    if (r->len + 1 >= r->cap) return;
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(r->buf + r->len, r->cap - r->len, fmt, ap);
    va_end(ap);
    if (n > 0) r->len = DXR_MIN(r->cap - 1, r->len + (size_t)n);
}

static int xioctl(int fd, unsigned long req, void *arg) {
    int rc;
    do { rc = ioctl(fd, req, arg); } while (rc < 0 && errno == EINTR);
    return rc;
}

static const char *model_name(uint32_t arch_major, uint32_t arch_minor, uint32_t product_major) {
    uint32_t id = arch_major << 16 | arch_minor << 8 | product_major;
    switch (id) {
    case 0x090001: case 0x090003: return "Mali-G57/G77";
    case 0x090204: return "Mali-G68/G78";
    case 0x0A0807: return "Mali-G610/G710";
    case 0x0A0C04: return "Mali-G310";
    case 0x0B0802: return "Mali-G715 / Immortalis-G715";
    case 0x0B0803: return "Mali-G615";
    case 0x0C0800: return "Mali-G720 / Immortalis-G720";
    case 0x0D0800: return "Mali-G725 / Immortalis-G925";
    default: return NULL;
    }
}

typedef struct gpu_info {
    uint64_t prop[PROP_MAX];
    uint8_t have[PROP_MAX];
} gpu_info;

static void parse_props(const uint8_t *p, size_t n, gpu_info *g) {
    size_t i = 0;
    while (i + 4 <= n) {
        uint32_t key;
        memcpy(&key, p + i, 4);
        i += 4;
        uint32_t id = key >> 2, size = key & 3;
        uint64_t v = 0;
        size_t bytes = size == 0 ? 1 : size == 1 ? 2 : size == 2 ? 4 : 8;
        if (i + bytes > n) break;
        memcpy(&v, p + i, bytes);
        i += bytes;
        if (id < PROP_MAX) { g->prop[id] = v; g->have[id] = 1; }
    }
}

static uint64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000u + (uint64_t)ts.tv_nsec / 1000000u;
}

typedef struct gpu_buf {
    void *cpu;
    uint64_t va, size;
} gpu_buf;

/* SAME_VA allocation: the kernel returns a cookie, mmap turns it into an address that is
 * the same for the CPU and the GPU. */
static int buf_alloc(int fd, uint64_t pages, uint64_t flags, long page, gpu_buf *b, report *r, const char *what) {
    union kb_mem_alloc a;
    memset(&a, 0, sizeof(a));
    a.in.va_pages = pages;
    a.in.commit_pages = pages;
    a.in.flags = flags | MEM_SAME_VA;
    if (xioctl(fd, KB_MEM_ALLOC, &a) < 0) {
        say(r, "  %s: MEM_ALLOC failed: %s\n", what, strerror(errno));
        return 0;
    }
    int prot = ((flags & MEM_CPU_RD) ? PROT_READ : 0) | ((flags & MEM_CPU_WR) ? PROT_WRITE : 0);
    void *cpu = mmap(NULL, (size_t)(pages * (uint64_t)page), prot ? prot : PROT_READ, MAP_SHARED, fd, (off_t)a.out.gpu_va);
    if (cpu == MAP_FAILED) {
        say(r, "  %s: mmap of GPU memory failed: %s\n", what, strerror(errno));
        struct kb_mem_free f = {a.out.gpu_va};
        xioctl(fd, KB_MEM_FREE, &f);
        return 0;
    }
    b->cpu = cpu;
    b->va = (uint64_t)(uintptr_t)cpu;
    b->size = pages * (uint64_t)page;
    return 1;
}

static void buf_free(int fd, gpu_buf *b) {
    if (!b->cpu) return;
    munmap(b->cpu, (size_t)b->size);
    struct kb_mem_free f = {b->va};
    xioctl(fd, KB_MEM_FREE, &f);
    b->cpu = NULL;
}

static int group_create(int fd, const gpu_info *g, int minor, uint8_t *handle, report *r) {
    uint64_t shaders = g->have[PROP_RAW_SHADER_PRESENT] ? g->prop[PROP_RAW_SHADER_PRESENT] : 1;
    uint64_t tiler = g->have[PROP_RAW_TILER_PRESENT] && g->prop[PROP_RAW_TILER_PRESENT] ? g->prop[PROP_RAW_TILER_PRESENT] : 1;
    int ns = __builtin_popcountll(shaders), nt = __builtin_popcountll(tiler);
    if (ns > 255) ns = 255;
    if (nt > 255) nt = 255;
    if (minor >= 19) {
        union kb_group_create c;
        memset(&c, 0, sizeof(c));
        c.in.tiler_mask = tiler; c.in.fragment_mask = shaders; c.in.compute_mask = shaders;
        c.in.cs_min = 1; c.in.priority = 1; c.in.tiler_max = (uint8_t)nt; c.in.fragment_max = (uint8_t)ns; c.in.compute_max = (uint8_t)ns;
        if (xioctl(fd, KB_CS_GROUP_CREATE, &c) == 0) { *handle = c.out.group_handle; say(r, "  queue group created (interface >= 1.19), handle %u\n", c.out.group_handle); return 1; }
        say(r, "  QUEUE_GROUP_CREATE (1.19+ layout): %s\n", strerror(errno));
    }
    if (minor >= 7) {
        union kb_group_create_1_18 c;
        memset(&c, 0, sizeof(c));
        c.in.tiler_mask = tiler; c.in.fragment_mask = shaders; c.in.compute_mask = shaders;
        c.in.cs_min = 1; c.in.priority = 1; c.in.tiler_max = (uint8_t)nt; c.in.fragment_max = (uint8_t)ns; c.in.compute_max = (uint8_t)ns;
        if (xioctl(fd, KB_CS_GROUP_CREATE_1_18, &c) == 0) { *handle = c.out.group_handle; say(r, "  queue group created (1.18 layout), handle %u\n", c.out.group_handle); return 1; }
        say(r, "  QUEUE_GROUP_CREATE (1.18 layout): %s\n", strerror(errno));
    }
    union kb_group_create_1_6 c;
    memset(&c, 0, sizeof(c));
    c.in.tiler_mask = tiler; c.in.fragment_mask = shaders; c.in.compute_mask = shaders;
    c.in.cs_min = 1; c.in.priority = 1; c.in.tiler_max = (uint8_t)nt; c.in.fragment_max = (uint8_t)ns; c.in.compute_max = (uint8_t)ns;
    if (xioctl(fd, KB_CS_GROUP_CREATE_1_6, &c) == 0) { *handle = c.out.group_handle; say(r, "  queue group created (1.6 layout), handle %u\n", c.out.group_handle); return 1; }
    say(r, "  QUEUE_GROUP_CREATE (1.6 layout): %s\n", strerror(errno));
    return 0;
}

/* Step 6: the GPU executes our command stream. Returns 1 when the GPU wrote the expected data. */
static int command_test(int fd, const gpu_info *g, int minor, long page, report *r) {
    const uint32_t magic0 = 0xD3D3D3D3u, magic1 = 0x0DC0FFEEu;
    gpu_buf ring = {0}, target = {0};
    uint8_t group = 0;
    int have_group = 0, registered = 0, ok = 0;
    void *io = MAP_FAILED;
    size_t io_size = 0;
    say(r, "[6] GPU command stream test\n");
    if (!buf_alloc(fd, 1, MEM_CPU_RD | MEM_CPU_WR | MEM_GPU_RD, page, &ring, r, "ring buffer")) goto done;
    if (!buf_alloc(fd, 1, MEM_CPU_RD | MEM_CPU_WR | MEM_GPU_RD | MEM_GPU_WR, page, &target, r, "target buffer")) goto done;
    memset(ring.cpu, 0, (size_t)ring.size);
    memset(target.cpu, 0, (size_t)target.size);

    struct kb_cs_queue_register reg;
    memset(&reg, 0, sizeof(reg));
    reg.buffer_gpu_addr = ring.va;
    reg.buffer_size = (uint32_t)ring.size;
    reg.priority = 1;
    if (xioctl(fd, KB_CS_QUEUE_REGISTER, &reg) < 0) { say(r, "  QUEUE_REGISTER: %s\n", strerror(errno)); goto done; }
    registered = 1;
    if (!group_create(fd, g, minor, &group, r)) goto done;
    have_group = 1;

    union kb_cs_queue_bind bind;
    memset(&bind, 0, sizeof(bind));
    bind.in.buffer_gpu_addr = ring.va;
    bind.in.group_handle = group;
    bind.in.csi_index = 0;
    if (xioctl(fd, KB_CS_QUEUE_BIND, &bind) < 0) { say(r, "  QUEUE_BIND: %s\n", strerror(errno)); goto done; }
    /* input page, output page, and on newer drivers a doorbell page */
    for (int pages = 3; pages >= 2 && io == MAP_FAILED; pages--) {
        io_size = (size_t)pages * (size_t)page;
        io = mmap(NULL, io_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, (off_t)bind.out.mmap_handle);
    }
    if (io == MAP_FAILED) { say(r, "  mmap of the queue's user I/O pages failed: %s\n", strerror(errno)); goto done; }
    say(r, "  queue bound, user I/O pages mapped (%zu pages)\n", io_size / (size_t)page);
    volatile uint64_t *input = (volatile uint64_t *)io;
    volatile uint64_t *output = (volatile uint64_t *)((uint8_t *)io + page);

    /* program: store two words to the target, wait for the store, flush L2 and load/store
     * caches so the CPU sees the data, wait for the flush. */
    uint64_t *cs = (uint64_t *)ring.cpu;
    int n = 0;
    cs[n++] = cs_move48(0, target.va);
    cs[n++] = cs_move32(2, magic0);
    cs[n++] = cs_move32(3, magic1);
    cs[n++] = cs_store_multiple(2, 0, 0x3, 0);
    cs[n++] = cs_wait(0xFF);
    cs[n++] = cs_move32(4, 0);
    cs[n++] = cs_flush_cache2(3, 3, 0, 0, 4, 0);
    cs[n++] = cs_wait(0x1);
    __sync_synchronize();
    input[CS_EXTRACT_INIT / 8] = 0;
    input[CS_INSERT / 8] = (uint64_t)n * 8u;
    __sync_synchronize();

    struct kb_cs_queue_kick kick = {ring.va};
    if (xioctl(fd, KB_CS_QUEUE_KICK, &kick) < 0) { say(r, "  QUEUE_KICK: %s\n", strerror(errno)); goto done; }
    say(r, "  %d instructions submitted\n", n);

    volatile uint32_t *t = (volatile uint32_t *)target.cpu;
    uint64_t t0 = now_ms(), elapsed = 0;
    while ((elapsed = now_ms() - t0) < 2000) {
        if (t[0] == magic0 && t[1] == magic1) { ok = 1; break; }
        usleep(500);
    }
    say(r, "  target after %llu ms: %08x %08x (expected %08x %08x), CS_EXTRACT=%llu CS_ACTIVE=%llu\n", (unsigned long long)elapsed, t[0], t[1], magic0,
        magic1, (unsigned long long)output[CS_EXTRACT / 8], (unsigned long long)output[CS_ACTIVE / 8]);
    say(r, ok ? "  RESULT: the GPU executed DX Render's command stream (no Vulkan, no OpenGL)\n"
              : "  RESULT: no GPU write observed within 2 s\n");
done:
    if (registered) {
        struct kb_cs_queue_terminate term = {ring.va};
        xioctl(fd, KB_CS_QUEUE_TERMINATE, &term);
    }
    if (have_group) {
        struct kb_group_term gt;
        memset(&gt, 0, sizeof(gt));
        gt.group_handle = group;
        xioctl(fd, KB_CS_GROUP_TERMINATE, &gt);
    }
    if (io != MAP_FAILED) munmap(io, io_size);
    buf_free(fd, &target);
    buf_free(fd, &ring);
    return ok;
}

int dxr_kbase_probe(char *out, size_t cap, int run_commands) {
    report r = {out, cap, 0};
    if (cap) out[0] = 0;
    int stage = 0;
    const char *nodes[] = {"/dev/mali0", "/dev/mali", NULL};
    int fd = -1;
    const char *node = NULL;
    say(&r, "DX Render kernel GPU interface probe (kbase)\n[1] device node\n");
    for (int i = 0; nodes[i]; i++) {
        fd = open(nodes[i], O_RDWR | O_CLOEXEC);
        if (fd >= 0) { node = nodes[i]; break; }
        say(&r, "  %s: %s\n", nodes[i], strerror(errno));
    }
    if (fd < 0) {
        say(&r, "  no Mali kernel driver reachable (not a Mali device, or access denied)\nSTAGE 0\n");
        return 0;
    }
    stage = 1;
    say(&r, "  %s opened\n[2] interface handshake\n", node);

    int csf = 0, major = 0, minor = 0;
    struct kb_version_check v = {KB_CSF_UK_MAJOR, KB_CSF_UK_MINOR};
    if (xioctl(fd, KB_VERSION_CHECK_CSF, &v) == 0) {
        csf = 1; major = v.major; minor = v.minor;
    } else {
        int e = errno;
        struct kb_version_check j = {KB_JM_UK_MAJOR, KB_JM_UK_MINOR};
        if (xioctl(fd, KB_VERSION_CHECK_JM, &j) == 0) {
            major = j.major; minor = j.minor;
        } else {
            say(&r, "  CSF handshake: %s; Job Manager handshake: %s\nSTAGE 1\n", strerror(e), strerror(errno));
            close(fd);
            return stage;
        }
    }
    if ((csf && major != KB_CSF_UK_MAJOR) || (!csf && major != KB_JM_UK_MAJOR)) {
        say(&r, "  kernel interface %d.%d is incompatible with this probe\nSTAGE 1\n", major, minor);
        close(fd);
        return stage;
    }
    stage = 2;
    say(&r, "  %s interface %d.%d\n", csf ? "CSF (command stream frontend)" : "Job Manager", major, minor);

    say(&r, "[3] context\n");
    struct kb_set_flags fl = {0};
    if (xioctl(fd, KB_SET_FLAGS, &fl) < 0) {
        say(&r, "  SET_FLAGS: %s\nSTAGE 2\n", strerror(errno));
        close(fd);
        return stage;
    }
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) page = 4096;
    /* Interfaces before 1.18 (CSF) require the tracking page to be mapped before allocating. */
    void *tracking = mmap(NULL, (size_t)page, PROT_NONE, MAP_SHARED, fd, (off_t)(3ul * (unsigned long)page));
    stage = 3;
    struct kb_get_ddk_version dv = {0, 0, 0};
    int dlen = xioctl(fd, KB_GET_DDK_VERSION, &dv);
    if (dlen > 0 && dlen < 256) {
        char ddk[257];
        memset(ddk, 0, sizeof(ddk));
        dv.version_buffer = (uint64_t)(uintptr_t)ddk;
        dv.size = (uint32_t)dlen;
        if (xioctl(fd, KB_GET_DDK_VERSION, &dv) > 0) say(&r, "  kernel driver version: %s\n", ddk);
    }
    say(&r, "  context created (page size %ld)\n[4] GPU properties\n", page);

    gpu_info g;
    memset(&g, 0, sizeof(g));
    struct kb_get_gpuprops gp = {0, 0, 0};
    int psize = xioctl(fd, KB_GET_GPUPROPS, &gp);
    if (psize > 0 && psize < (1 << 20)) {
        uint8_t *pb = (uint8_t *)malloc((size_t)psize);
        gp.buffer = (uint64_t)(uintptr_t)pb;
        gp.size = (uint32_t)psize;
        if (pb && xioctl(fd, KB_GET_GPUPROPS, &gp) > 0) {
            parse_props(pb, (size_t)psize, &g);
            stage = 4;
        } else {
            say(&r, "  GET_GPUPROPS: %s\n", strerror(errno));
        }
        free(pb);
    } else {
        say(&r, "  GET_GPUPROPS size query: %s\n", psize < 0 ? strerror(errno) : "unexpected size");
    }
    if (stage >= 4) {
        uint64_t id = g.prop[PROP_RAW_GPU_ID];
        uint32_t am, an, ar, pm, vmaj, vmin;
        if (id > 0xFFFFFFFFull) { /* 64 bit GPU_ID of newer drivers */
            am = (uint32_t)(id >> 56) & 0xFF; an = (uint32_t)(id >> 48) & 0xFF; ar = (uint32_t)(id >> 40) & 0xFF; pm = (uint32_t)(id >> 32) & 0xFF;
            vmaj = (uint32_t)(id >> 16) & 0xFF; vmin = (uint32_t)(id >> 8) & 0xFF;
        } else {
            am = (uint32_t)(id >> 28) & 0xF; an = (uint32_t)(id >> 24) & 0xF; ar = (uint32_t)(id >> 20) & 0xF; pm = (uint32_t)(id >> 16) & 0xF;
            vmaj = (uint32_t)(id >> 12) & 0xF; vmin = (uint32_t)(id >> 4) & 0xFF;
        }
        const char *name = model_name(am, an, pm);
        say(&r, "  GPU_ID 0x%llx: arch v%u.%u r%u, product %u, revision r%up%u -> %s\n", (unsigned long long)id, am, an, ar, pm, vmaj, vmin,
            name ? name : "unknown model");
        say(&r, "  shader cores: %d (mask 0x%llx), tiler 0x%llx, L2 slices %llu, L2 %llu KiB, max freq %llu MHz\n",
            __builtin_popcountll(g.prop[PROP_RAW_SHADER_PRESENT]), (unsigned long long)g.prop[PROP_RAW_SHADER_PRESENT],
            (unsigned long long)g.prop[PROP_RAW_TILER_PRESENT], (unsigned long long)g.prop[PROP_L2_NUM_L2_SLICES],
            g.prop[PROP_L2_LOG2_CACHE_SIZE] ? (unsigned long long)((1ull << g.prop[PROP_L2_LOG2_CACHE_SIZE]) >> 10) : 0ull,
            (unsigned long long)(g.prop[PROP_GPU_FREQ_KHZ_MAX] / 1000));
        if (g.prop[PROP_GPU_AVAILABLE_MEMORY_SIZE])
            say(&r, "  memory available to the GPU: %llu MiB\n", (unsigned long long)(g.prop[PROP_GPU_AVAILABLE_MEMORY_SIZE] >> 20));
    }

    say(&r, "[5] GPU memory\n");
    gpu_buf mb = {0};
    if (buf_alloc(fd, 4, MEM_CPU_RD | MEM_CPU_WR | MEM_GPU_RD | MEM_GPU_WR, page, &mb, &r, "test buffer")) {
        volatile uint32_t *w = (volatile uint32_t *)mb.cpu;
        size_t words = (size_t)(mb.size / 4);
        for (size_t i = 0; i < words; i++) w[i] = (uint32_t)(i * 2654435761u);
        size_t bad = 0;
        for (size_t i = 0; i < words; i++) bad += w[i] != (uint32_t)(i * 2654435761u);
        say(&r, "  %llu KiB allocated at GPU VA 0x%llx (same address in the CPU), write/read check: %s\n", (unsigned long long)(mb.size >> 10),
            (unsigned long long)mb.va, bad ? "FAILED" : "ok");
        buf_free(fd, &mb);
        if (!bad && stage >= 3) stage = 5;
    }

    if (csf && stage >= 4) {
        union kb_get_glb_iface gi;
        memset(&gi, 0, sizeof(gi));
        if (xioctl(fd, KB_CS_GET_GLB_IFACE, &gi) == 0)
            say(&r, "  firmware interface %u.%u.%u: %u queue group slots, %u streams\n", gi.out.glb_version >> 24, (gi.out.glb_version >> 16) & 0xFF,
                gi.out.glb_version & 0xFFFF, gi.out.group_num, gi.out.total_stream_num);
        if (run_commands && stage >= 5) {
            if (command_test(fd, &g, minor, page, &r)) stage = 6;
        } else if (!run_commands) {
            say(&r, "[6] command stream test not requested\n");
        }
    } else if (!csf) {
        say(&r, "[6] command stream test is implemented for CSF GPUs (Mali-G610 and newer) only\n");
    }
    if (tracking != MAP_FAILED) munmap(tracking, (size_t)page);
    close(fd);
    say(&r, "STAGE %d\n", stage);
    return stage;
}

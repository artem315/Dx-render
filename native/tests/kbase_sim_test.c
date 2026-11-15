/* Host test of the kernel GPU interface probe (native/src/dxr_kbase.c).
 *
 * The probe is compiled together with a small simulation of the kbase kernel interface:
 * ioctls are answered as a CSF kbase driver would, GPU memory is ordinary memory, and the
 * command stream frontend is emulated for the instructions the probe uses. This checks the
 * call sequence, structure layouts, property parsing and the encoded program; it does not
 * and cannot replace a run on real Mali hardware.
 *
 * Build: clang -std=c11 -D_GNU_SOURCE -Inative/include -Inative/src native/tests/kbase_sim_test.c -o kbase_sim_test
 */
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

static int sim_open(const char *path, int flags, ...);
static int sim_ioctl(int fd, unsigned long req, void *arg);
static void *sim_mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off);
static int sim_munmap(void *addr, size_t len);
static int sim_close(int fd);

#define open sim_open
#define ioctl sim_ioctl
#define mmap sim_mmap
#define munmap sim_munmap
#define close sim_close
#include "../src/dxr_kbase.c"
#undef open
#undef ioctl
#undef mmap
#undef munmap
#undef close

#define SIM_FD 4242
static int sim_kernel_minor = 30;
static int sim_present = 1;
static int sim_execute = 1;
static long sim_page = 4096;

typedef struct { void *mem; size_t size; uint64_t cookie; int live; } sim_alloc;
static sim_alloc allocs[64];
static int nallocs;
static uint64_t ring_va, ring_size;
static uint8_t *io_pages;
static int ioctl_calls;

static int sim_open(const char *path, int flags, ...) {
    (void)flags;
    if (sim_present && strcmp(path, "/dev/mali0") == 0) return SIM_FD;
    errno = ENOENT;
    return -1;
}

static int sim_close(int fd) { return fd == SIM_FD ? 0 : -1; }

static void *sim_mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off) {
    if (fd != SIM_FD) return mmap(addr, len, prot, flags, fd, off);
    if ((uint64_t)off == 3ull * (uint64_t)sim_page) return mmap(NULL, len, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if ((uint64_t)off == 48ull * (uint64_t)sim_page) {
        if (len != 3 * (size_t)sim_page || !io_pages) { errno = EINVAL; return MAP_FAILED; }
        return io_pages;
    }
    for (int i = 0; i < nallocs; i++)
        if (allocs[i].live && allocs[i].cookie == (uint64_t)off) {
            if (len != allocs[i].size) { errno = EINVAL; return MAP_FAILED; }
            return allocs[i].mem;
        }
    errno = EINVAL;
    return MAP_FAILED;
}

static int sim_munmap(void *addr, size_t len) {
    for (int i = 0; i < nallocs; i++) if (allocs[i].mem == addr) return 0; /* released by MEM_FREE */
    if (addr == io_pages) return 0;
    return munmap(addr, len);
}

static void put_prop(uint8_t *b, size_t *n, uint32_t id, int size_code, uint64_t v) {
    uint32_t key = id << 2 | (uint32_t)size_code;
    memcpy(b + *n, &key, 4);
    *n += 4;
    size_t bytes = size_code == 0 ? 1 : size_code == 1 ? 2 : size_code == 2 ? 4 : 8;
    memcpy(b + *n, &v, bytes);
    *n += bytes;
}

static size_t make_props(uint8_t *b) {
    size_t n = 0;
    put_prop(b, &n, PROP_PRODUCT_ID, 2, 0x0D080000u);
    put_prop(b, &n, PROP_RAW_GPU_ID, 3, 0xD8001010ull);       /* arch 13.8 r0, product 0, r1p1 */
    put_prop(b, &n, PROP_RAW_SHADER_PRESENT, 3, 0x7FFull);    /* MC11 */
    put_prop(b, &n, PROP_RAW_TILER_PRESENT, 3, 1);
    put_prop(b, &n, PROP_L2_NUM_L2_SLICES, 0, 2);
    put_prop(b, &n, PROP_L2_LOG2_CACHE_SIZE, 0, 21);
    put_prop(b, &n, PROP_GPU_FREQ_KHZ_MAX, 2, 1612000);
    put_prop(b, &n, PROP_GPU_AVAILABLE_MEMORY_SIZE, 3, 12ull << 30);
    return n;
}

/* Emulated command stream frontend: executes the instructions the probe emits. */
static int run_cs(void) {
    uint64_t regs[96];
    memset(regs, 0, sizeof(regs));
    uint64_t insert, extract;
    memcpy(&insert, io_pages + CS_INSERT, 8);
    memcpy(&extract, io_pages + CS_EXTRACT_INIT, 8);
    const uint64_t *ring = (const uint64_t *)(uintptr_t)ring_va;
    while (extract < insert) {
        uint64_t ins = ring[(extract % ring_size) / 8];
        extract += 8;
        unsigned op = (unsigned)(ins >> 56);
        switch (op) {
        case 1: { unsigned d = (ins >> 48) & 0xFF; uint64_t imm = ins & 0xFFFFFFFFFFFFull; regs[d] = imm & 0xFFFFFFFF; regs[d + 1] = imm >> 32; break; }
        case 2: regs[(ins >> 48) & 0xFF] = ins & 0xFFFFFFFF; break;
        case 3: case 36: break; /* WAIT, FLUSH_CACHE2: memory is coherent here */
        case 21: {
            unsigned first = (ins >> 48) & 0xFF, areg = (ins >> 40) & 0xFF, mask = (ins >> 16) & 0xFFFF;
            int16_t off = (int16_t)(ins & 0xFFFF);
            uint64_t addr = regs[areg] | regs[areg + 1] << 32;
            uint32_t *dst = (uint32_t *)(uintptr_t)(addr + (int64_t)off);
            for (unsigned i = 0, k = 0; i < 16; i++) if (mask >> i & 1) dst[k++] = (uint32_t)regs[first + i];
            break;
        }
        default:
            fprintf(stderr, "sim: unexpected CS opcode %u\n", op);
            return 0;
        }
    }
    memcpy(io_pages + sim_page + CS_EXTRACT, &extract, 8);
    return 1;
}

static int sim_ioctl(int fd, unsigned long req, void *arg) {
    if (fd != SIM_FD) { errno = EBADF; return -1; }
    ioctl_calls++;
    if (req == KB_VERSION_CHECK_CSF) {
        struct kb_version_check *v = arg;
        if (v->major == 1 && v->minor > sim_kernel_minor) v->minor = (uint16_t)sim_kernel_minor;
        return 0;
    }
    if (req == KB_VERSION_CHECK_JM) { errno = EINVAL; return -1; }
    if (req == KB_SET_FLAGS) return 0;
    if (req == KB_GET_DDK_VERSION) {
        struct kb_get_ddk_version *d = arg;
        const char *s = "K:r49p0-simulated";
        if (!d->version_buffer) return (int)strlen(s);
        memcpy((void *)(uintptr_t)d->version_buffer, s, strlen(s));
        return (int)strlen(s);
    }
    if (req == KB_GET_GPUPROPS) {
        struct kb_get_gpuprops *g = arg;
        uint8_t tmp[512];
        size_t n = make_props(tmp);
        if (!g->buffer) return (int)n;
        if (g->size < n) { errno = EINVAL; return -1; }
        memcpy((void *)(uintptr_t)g->buffer, tmp, n);
        return (int)n;
    }
    if (req == KB_MEM_ALLOC) {
        union kb_mem_alloc *a = arg;
        if (!(a->in.flags & MEM_SAME_VA) || a->in.va_pages == 0 || nallocs == 64) { errno = EINVAL; return -1; }
        size_t size = (size_t)(a->in.va_pages * (uint64_t)sim_page);
        void *m = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        allocs[nallocs].mem = m;
        allocs[nallocs].size = size;
        allocs[nallocs].cookie = (64ull + (uint64_t)nallocs) * (uint64_t)sim_page;
        allocs[nallocs].live = 1;
        a->out.flags = a->in.flags;
        a->out.gpu_va = allocs[nallocs].cookie;
        nallocs++;
        return 0;
    }
    if (req == KB_MEM_FREE) {
        struct kb_mem_free *f = arg;
        for (int i = 0; i < nallocs; i++)
            if (allocs[i].live && (uint64_t)(uintptr_t)allocs[i].mem == f->gpu_addr) {
                allocs[i].live = 0;
                munmap(allocs[i].mem, allocs[i].size);
                return 0;
            }
        errno = EINVAL;
        return -1;
    }
    if (req == KB_CS_GET_GLB_IFACE) {
        union kb_get_glb_iface *g = arg;
        memset(&g->out, 0, sizeof(g->out));
        g->out.glb_version = 3u << 24 | 9u << 16;
        g->out.group_num = 31;
        g->out.total_stream_num = 31 * 8;
        return 0;
    }
    if (req == KB_CS_QUEUE_REGISTER) {
        struct kb_cs_queue_register *q = arg;
        if (q->buffer_size < (uint32_t)sim_page || (q->buffer_size & (q->buffer_size - 1))) { errno = EINVAL; return -1; }
        ring_va = q->buffer_gpu_addr;
        ring_size = q->buffer_size;
        return 0;
    }
    if (req == KB_CS_GROUP_CREATE) {
        union kb_group_create *c = arg;
        if (sim_kernel_minor < 19) { errno = ENOTTY; return -1; }
        if (c->in.compute_max > __builtin_popcountll(c->in.compute_mask) || c->in.cs_min < 1) { errno = EINVAL; return -1; }
        c->out.group_handle = 0;
        c->out.group_uid = 7;
        return 0;
    }
    if (req == KB_CS_GROUP_CREATE_1_18 || req == KB_CS_GROUP_CREATE_1_6) {
        if (sim_kernel_minor >= 19) { errno = EINVAL; return -1; }
        ((union kb_group_create_1_18 *)arg)->out.group_handle = 0;
        return 0;
    }
    if (req == KB_CS_QUEUE_BIND) {
        union kb_cs_queue_bind *b = arg;
        if (b->in.buffer_gpu_addr != ring_va) { errno = EINVAL; return -1; }
        io_pages = mmap(NULL, 3 * (size_t)sim_page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        b->out.mmap_handle = 48ull * (uint64_t)sim_page;
        return 0;
    }
    if (req == KB_CS_QUEUE_KICK) return sim_execute && run_cs() ? 0 : 0;
    if (req == KB_CS_QUEUE_TERMINATE || req == KB_CS_GROUP_TERMINATE) return 0;
    errno = ENOTTY;
    return -1;
}

static int check(const char *name, int cond) {
    printf("%s %s\n", cond ? "PASS" : "FAIL", name);
    return cond;
}

int main(void) {
    static char rep[16384];
    int ok = 1;
    sim_page = sysconf(_SC_PAGESIZE);

    int stage = dxr_kbase_probe(rep, sizeof(rep), 1);
    fputs(rep, stdout);
    ok &= check("full ladder reaches stage 6 on a simulated CSF kbase 1.30", stage == 6);
    ok &= check("model decoded as G925", strstr(rep, "Immortalis-G925") != NULL);
    ok &= check("11 shader cores reported", strstr(rep, "shader cores: 11") != NULL);
    ok &= check("driver version string read", strstr(rep, "K:r49p0-simulated") != NULL);
    int live = 0;
    for (int i = 0; i < nallocs; i++) live += allocs[i].live;
    ok &= check("all GPU memory released", live == 0);

    /* GPU does not execute: the probe must time out cleanly at stage 5 */
    nallocs = 0;
    sim_execute = 0;
    stage = dxr_kbase_probe(rep, sizeof(rep), 1);
    ok &= check("no GPU write -> stage 5 and a clear message", stage == 5 && strstr(rep, "no GPU write observed") != NULL);

    /* older kernel interface: group creation falls back to the 1.18 layout */
    nallocs = 0;
    sim_execute = 1;
    sim_kernel_minor = 12;
    stage = dxr_kbase_probe(rep, sizeof(rep), 1);
    ok &= check("interface 1.12 falls back to the 1.18 group layout", stage == 6 && strstr(rep, "1.18 layout") != NULL);

    /* no Mali device */
    sim_present = 0;
    stage = dxr_kbase_probe(rep, sizeof(rep), 1);
    ok &= check("missing device -> stage 0", stage == 0 && strstr(rep, "No such file") != NULL);

    /* tiny report buffer must not overflow */
    sim_present = 1;
    nallocs = 0;
    char small[64];
    dxr_kbase_probe(small, sizeof(small), 1);
    ok &= check("report truncates safely", strlen(small) < sizeof(small));

    printf(ok ? "ALL PASS\n" : "SOME FAILED\n");
    return ok ? 0 : 1;
}

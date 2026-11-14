/* DX Render - Frame Scheduler: persistent worker pool with dynamic work claiming.
 *
 * Work is described as an index range. Every thread (the caller is thread 0) claims
 * "grain" indices at a time from a shared atomic cursor, so threads that run on faster
 * cores (big.LITTLE) automatically take a larger share: adaptive workload distribution
 * without any per-device tuning table.
 */
#include "dxr_internal.h"

#include <pthread.h>
#include <unistd.h>

struct dxr_pool {
    int threads;                 /* including the caller */
    pthread_t workers[DXR_MAX_THREADS];
    pthread_mutex_t mutex;
    pthread_cond_t wake;
    pthread_cond_t done;
    uint64_t generation;         /* incremented per job */
    int stop;
    int active;                  /* workers still inside the current job */
    dxr_job_fn fn;
    void *ctx;
    int count, grain;
    _Atomic int cursor;
    struct dxr_worker_arg { dxr_pool *pool; int index; } args[DXR_MAX_THREADS];
};

int dxr_cpu_count(void) {
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    if (n < 1) n = 1;
    if (n > DXR_MAX_THREADS) n = DXR_MAX_THREADS;
    return (int)n;
}

static void run_items(dxr_pool *p, int thread) {
    const int count = p->count, grain = p->grain;
    for (;;) {
        int start = atomic_fetch_add_explicit(&p->cursor, grain, memory_order_relaxed);
        if (start >= count) break;
        int end = start + grain;
        if (end > count) end = count;
        for (int i = start; i < end; i++) p->fn(p->ctx, i, thread);
    }
}

static void *worker_main(void *arg) {
    dxr_pool *p = ((struct dxr_worker_arg *)arg)->pool;
    int index = ((struct dxr_worker_arg *)arg)->index;
    uint64_t seen = 0;
    pthread_mutex_lock(&p->mutex);
    for (;;) {
        while (!p->stop && p->generation == seen) pthread_cond_wait(&p->wake, &p->mutex);
        if (p->stop) break;
        seen = p->generation;
        pthread_mutex_unlock(&p->mutex);
        run_items(p, index);
        pthread_mutex_lock(&p->mutex);
        if (--p->active == 0) pthread_cond_signal(&p->done);
    }
    pthread_mutex_unlock(&p->mutex);
    return NULL;
}

dxr_pool *dxr_pool_create(int threads) {
    if (threads < 1) threads = 1;
    if (threads > DXR_MAX_THREADS) threads = DXR_MAX_THREADS;
    dxr_pool *p = (dxr_pool *)dxr_alloc(sizeof(dxr_pool));
    if (!p) return NULL;
    p->threads = 1;
    pthread_mutex_init(&p->mutex, NULL);
    pthread_cond_init(&p->wake, NULL);
    pthread_cond_init(&p->done, NULL);
    for (int i = 1; i < threads; i++) {
        p->args[i].pool = p;
        p->args[i].index = i;
        if (pthread_create(&p->workers[i], NULL, worker_main, &p->args[i]) != 0) break;
        p->threads = i + 1;
    }
    return p;
}

void dxr_pool_destroy(dxr_pool *p) {
    if (!p) return;
    pthread_mutex_lock(&p->mutex);
    p->stop = 1;
    pthread_cond_broadcast(&p->wake);
    pthread_mutex_unlock(&p->mutex);
    for (int i = 1; i < p->threads; i++) pthread_join(p->workers[i], NULL);
    pthread_mutex_destroy(&p->mutex);
    pthread_cond_destroy(&p->wake);
    pthread_cond_destroy(&p->done);
    dxr_free(p);
}

int dxr_pool_threads(const dxr_pool *p) { return p->threads; }

void dxr_pool_run(dxr_pool *p, dxr_job_fn fn, void *ctx, int count, int grain) {
    if (count <= 0) return;
    if (grain < 1) grain = 1;
    p->fn = fn;
    p->ctx = ctx;
    p->count = count;
    p->grain = grain;
    atomic_store(&p->cursor, 0);
    if (p->threads == 1 || count <= grain) {
        run_items(p, 0);
        return;
    }
    pthread_mutex_lock(&p->mutex);
    p->active = p->threads - 1;
    p->generation++;
    pthread_cond_broadcast(&p->wake);
    pthread_mutex_unlock(&p->mutex);
    run_items(p, 0);
    pthread_mutex_lock(&p->mutex);
    while (p->active > 0) pthread_cond_wait(&p->done, &p->mutex);
    pthread_mutex_unlock(&p->mutex);
}

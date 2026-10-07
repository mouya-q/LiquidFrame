#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdint.h>
#include <time.h>
#include <pthread.h>
#include <unistd.h>

/* ---------- 输入 ---------- */
int n, W64;
double W0, V, T, X0, Y0, V2;

typedef struct {
    double w, x, y, p, q;
    double speed_sq;
    int orig_id;
} Shrimp;

Shrimp *S = NULL;
uint64_t *Z = NULL;

/* ---------- 共享最优 ---------- */
pthread_mutex_t g_mtx = PTHREAD_MUTEX_INITIALIZER;
double g_best_gain = -1;
int *g_best_path = NULL;
int g_best_path_len = 0;

/* ---------- 时间 ---------- */
static double get_time(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec * 1e-9;
}

/* ---------- 随机数 ---------- */
static inline uint64_t xs64(uint64_t *s) {
    uint64_t x = *s;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    *s = x;
    return x;
}

/* ---------- 掩码（位数组） ---------- */
typedef struct {
    uint64_t *bits;
} Mask;

static Mask *mask_create(int w64) {
    Mask *m = (Mask *)malloc(sizeof(Mask));
    m->bits = (uint64_t *)calloc(w64, sizeof(uint64_t));
    return m;
}

static Mask *mask_add(Mask *m, int w64, int bit) {
    Mask *nm = (Mask *)malloc(sizeof(Mask));
    nm->bits = (uint64_t *)malloc(w64 * sizeof(uint64_t));
    memcpy(nm->bits, m->bits, w64 * sizeof(uint64_t));
    nm->bits[bit >> 6] |= 1ULL << (bit & 63);
    return nm;
}

static void mask_free(Mask *m) {
    if (!m) return;
    free(m->bits);
    free(m);
}

static inline int mask_test(const Mask *m, int bit) {
    return (m->bits[bit >> 6] >> (bit & 63)) & 1;
}

/* ---------- 节点 ---------- */
typedef struct {
    double t, x, y, wt;
    uint64_t hash;
    int par;
    int shrimp;
    Mask *mask;
} Node;

typedef struct {
    int node_idx;
    uint64_t hash;
    double wt;
    double t;
} BeamEntry;

typedef struct {
    double dt;
    int i;
} Cand;

/* ---------- 开放寻址哈希集合 ---------- */
typedef struct {
    uint64_t *keys;
    int size;
    int cap;
} HSet;

static void hset_init(HSet *h, int cap) {
    h->cap = 1;
    while (h->cap < cap * 2) h->cap <<= 1;
    h->keys = (uint64_t *)calloc(h->cap, sizeof(uint64_t));
    h->size = 0;
}

static void hset_free(HSet *h) {
    free(h->keys);
    h->keys = NULL;
}

static int hset_insert(HSet *h, uint64_t key) {
    if (key == 0) key = 1;
    uint64_t mask = (uint64_t)h->cap - 1;
    uint64_t idx = key & mask;
    while (h->keys[idx] != 0) {
        if (h->keys[idx] == key) return 0;
        idx = (idx + 1) & mask;
    }
    h->keys[idx] = key;
    h->size++;
    return 1;
}

/* ---------- meet：从 (t0,px,py) 到虾 i 的最早到达时间增量 ---------- */
static double meet_func(double t0, double px, double py, int i) {
    double dx = S[i].x + S[i].p * t0 - px;
    double dy = S[i].y + S[i].q * t0 - py;
    double c = dx * dx + dy * dy;
    if (c < 1e-18) return 0;
    double a = S[i].speed_sq - V2;
    double b = dx * S[i].p + dy * S[i].q;
    if (fabs(a) < 1e-12) {
        if (b >= 0) return -1;
        return -c / (2 * b);
    }
    double D = b * b - a * c;
    if (D < 0) return -1;
    double sd = sqrt(D);
    double s = (-b - sd) / a;
    if (s < 0) return -1;
    return s;
}

/* ---------- 比较器 ---------- */
static int cmp_cand(const void *a, const void *b) {
    double da = ((const Cand *)a)->dt;
    double db = ((const Cand *)b)->dt;
    return (da > db) - (da < db);
}

static int cmp_beam(const void *a, const void *b) {
    const BeamEntry *ea = (const BeamEntry *)a;
    const BeamEntry *eb = (const BeamEntry *)b;
    if (ea->wt > eb->wt) return -1;
    if (ea->wt < eb->wt) return 1;
    return (ea->t > eb->t) - (ea->t < eb->t);
}

/* ---------- 线程参数 ---------- */
typedef struct {
    uint64_t seed;
    double time_limit;
    size_t max_pool;
} ThreadArg;

/* ---------- 工作线程 ---------- */
static void *worker_thread(void *arg) {
    ThreadArg *ta = (ThreadArg *)arg;
    uint64_t rng_state = ta->seed;
    double time_limit = ta->time_limit;
    size_t max_pool = ta->max_pool;
    double start_time = get_time();

    size_t pool_cap = 1 << 18;
    if (pool_cap > max_pool) pool_cap = max_pool;
    if (pool_cap < 4096) pool_cap = 4096;

    Node *pool = (Node *)malloc(pool_cap * sizeof(Node));
    if (!pool) return NULL;
    size_t pool_size = 0;

    Mask *init_mask = mask_create(W64);
    pool[pool_size++] = (Node){0, X0, Y0, W0, 0, -1, -1, init_mask};

    size_t beam_cap = 1 << 18;
    size_t next_cap = 1 << 18;
    if (beam_cap > max_pool) beam_cap = max_pool;
    if (next_cap > max_pool * 2) next_cap = max_pool * 2;
    if (beam_cap < 4096) beam_cap = 4096;
    if (next_cap < 4096) next_cap = 4096;

    BeamEntry *beam = (BeamEntry *)malloc(beam_cap * sizeof(BeamEntry));
    BeamEntry *next_beam = (BeamEntry *)malloc(next_cap * sizeof(BeamEntry));
    if (!beam || !next_beam) return NULL;

    size_t beam_size = 1;
    beam[0] = (BeamEntry){0, 0, W0, 0};

    Cand *cands = (Cand *)malloc(n * sizeof(Cand));
    int *path_buf = (int *)malloc((n + 1) * sizeof(int));
    HSet hset;
    hset_init(&hset, 1 << 18);

    int BW = 64;
    const int BW_CAP = 1000000;

    while (beam_size > 0) {
        double elapsed = get_time() - start_time;
        if (elapsed > time_limit) break;

        BW = BW * 2;
        if (BW > BW_CAP) BW = BW_CAP;

        int K = BW / 60;
        if (K < 20) K = 20;
        if (K > n) K = n;
        if (K > 120) K = 120;

        size_t next_size = 0;
        int stop = 0;

        for (size_t bi = 0; bi < beam_size && !stop; bi++) {
            /* 每 128 个 beam 节点检查一次时间 */
            if ((bi & 127) == 0 && get_time() - start_time > time_limit) {
                stop = 1; break;
            }
            if (pool_size >= max_pool) { stop = 1; break; }

            int cur_idx = beam[bi].node_idx;
            Node cur = pool[cur_idx];
            double dt_max = T - cur.t;
            if (dt_max < -1e-9) continue;

            size_t ncands = 0;
            for (int i = 0; i < n; i++) {
                if (S[i].w > cur.wt + 1e-12) break;
                if (mask_test(cur.mask, i)) continue;
                double dt = meet_func(cur.t, cur.x, cur.y, i);
                if (dt < 0 || dt > dt_max + 1e-9) continue;
                cands[ncands].dt = dt;
                cands[ncands].i = i;
                ncands++;
            }
            if (ncands == 0) continue;

            if (ncands > (size_t)K) {
                qsort(cands, ncands, sizeof(Cand), cmp_cand);
                ncands = K;
            }

            for (size_t ci = 0; ci < ncands; ci++) {
                if (pool_size >= max_pool) { stop = 1; break; }

                int i = cands[ci].i;
                double dt = cands[ci].dt;
                double nt = cur.t + dt;
                double nx = S[i].x + S[i].p * nt;
                double ny = S[i].y + S[i].q * nt;
                double nwt = cur.wt + S[i].w;
                uint64_t nh = cur.hash ^ Z[i];

                /* 池扩容 */
                if (pool_size >= pool_cap) {
                    size_t new_cap = pool_cap * 2;
                    if (new_cap > max_pool) new_cap = max_pool;
                    Node *np = (Node *)realloc(pool, new_cap * sizeof(Node));
                    if (!np) { stop = 1; break; }
                    pool = np;
                    pool_cap = new_cap;
                }
                /* next 扩容 */
                if (next_size >= next_cap) {
                    size_t new_cap = next_cap * 2;
                    BeamEntry *nb = (BeamEntry *)realloc(next_beam, new_cap * sizeof(BeamEntry));
                    if (!nb) { stop = 1; break; }
                    next_beam = nb;
                    next_cap = new_cap;
                }

                Mask *nmask = mask_add(cur.mask, W64, i);
                pool[pool_size] = (Node){nt, nx, ny, nwt, nh, cur_idx, i, nmask};
                next_beam[next_size].node_idx = (int)pool_size;
                next_beam[next_size].hash = nh;
                next_beam[next_size].wt = nwt;
                next_beam[next_size].t = nt;
                next_size++;
                pool_size++;
            }
        }

        if (next_size == 0) break;

        qsort(next_beam, next_size, sizeof(BeamEntry), cmp_beam);

        /* 去重 */
        hset_init(&hset, (int)(BW * 2));
        size_t filtered = 0;
        for (size_t i = 0; i < next_size; i++) {
            if (filtered >= (size_t)BW) break;
            if (!hset_insert(&hset, next_beam[i].hash)) continue;
            next_beam[filtered++] = next_beam[i];
        }
        hset_free(&hset);
        beam_size = filtered;

        BeamEntry *tmp_b = beam;
        beam = next_beam;
        next_beam = tmp_b;
        size_t tmp_c = beam_cap;
        beam_cap = next_cap;
        next_cap = tmp_c;

        /* 更新全局最优 */
        if (beam_size > 0) {
            Node *bn = &pool[beam[0].node_idx];
            double gain = bn->wt - W0;
            if (gain > 0) {
                int plen = 0;
                int u = beam[0].node_idx;
                while (u != -1 && pool[u].par != -1) {
                    if (plen >= n) break;
                    path_buf[plen++] = S[pool[u].shrimp].orig_id;
                    u = pool[u].par;
                }
                for (int i = 0; i < plen / 2; i++) {
                    int t2 = path_buf[i];
                    path_buf[i] = path_buf[plen - 1 - i];
                    path_buf[plen - 1 - i] = t2;
                }

                pthread_mutex_lock(&g_mtx);
                if (gain > g_best_gain) {
                    g_best_gain = gain;
                    g_best_path_len = plen;
                    free(g_best_path);
                    g_best_path = (int *)malloc(plen * sizeof(int));
                    memcpy(g_best_path, path_buf, plen * sizeof(int));
                }
                pthread_mutex_unlock(&g_mtx);
            }
        }
    }

    /* 清理 */
    for (size_t i = 0; i < pool_size; i++) mask_free(pool[i].mask);
    free(pool);
    free(cands);
    free(path_buf);
    free(beam);
    free(next_beam);

    return NULL;
}

/* ---------- 按体重升序排序虾 ---------- */
static int cmp_shrimp(const void *a, const void *b) {
    double wa = ((const Shrimp *)a)->w;
    double wb = ((const Shrimp *)b)->w;
    return (wa > wb) - (wa < wb);
}

/* ---------- 主函数 ---------- */
int main(void) {
    if (scanf("%lf%lf%lf%lf%lf%d", &W0, &V, &T, &X0, &Y0, &n) != 6) {
        fprintf(stderr, "Input error\n");
        return 1;
    }
    V *= (1 - 1e-9);
    V2 = V * V;

    S = (Shrimp *)malloc(n * sizeof(Shrimp));
    for (int i = 0; i < n; i++) {
        if (scanf("%lf%lf%lf%lf%lf",
                  &S[i].w, &S[i].x, &S[i].y, &S[i].p, &S[i].q) != 5) {
            fprintf(stderr, "Input error at shrimp %d\n", i);
            return 1;
        }
        S[i].speed_sq = S[i].p * S[i].p + S[i].q * S[i].q;
        S[i].orig_id = i;
    }

    /* 按体重升序，使内层可提前 break */
    qsort(S, n, sizeof(Shrimp), cmp_shrimp);

    W64 = (n + 63) / 64;

    /* 随机哈希指纹 */
    uint64_t rng_state = 0xC0FFEEULL;
    Z = (uint64_t *)malloc(n * sizeof(uint64_t));
    for (int i = 0; i < n; i++) Z[i] = xs64(&rng_state);

    int nthreads = 8;
    const char *env_threads = getenv("NEMO_THREADS");
    if (env_threads) nthreads = atoi(env_threads);
    if (nthreads < 1) nthreads = 1;
    if (nthreads > 64) nthreads = 64;

    double time_limit = 3600.0;
    const char *env_time = getenv("NEMO_TIME");
    if (env_time) time_limit = atof(env_time);

    size_t mem_limit = 4ULL * 1024 * 1024 * 1024;
    const char *env_mem = getenv("NEMO_MEM");
    if (env_mem) mem_limit = (size_t)(atof(env_mem) * 1024 * 1024 * 1024);
    size_t node_size = sizeof(Node) + (size_t)W64 * 8 + 64;
    size_t max_pool = mem_limit / node_size / nthreads;
    if (max_pool < 100000) max_pool = 100000;
    if (max_pool > 20000000) max_pool = 20000000;

    pthread_t *threads = (pthread_t *)malloc(nthreads * sizeof(pthread_t));
    ThreadArg *args = (ThreadArg *)malloc(nthreads * sizeof(ThreadArg));

    for (int t = 0; t < nthreads; t++) {
        args[t].seed = 0x123456789ABCDEF0ULL
                     + (uint64_t)t * 0x9E3779B97F4A7C15ULL;
        args[t].time_limit = time_limit;
        args[t].max_pool = max_pool;
        pthread_create(&threads[t], NULL, worker_thread, &args[t]);
    }
    for (int t = 0; t < nthreads; t++) pthread_join(threads[t], NULL);

    free(threads);
    free(args);

    printf("%d\n%.6f\n", g_best_path_len, g_best_gain);

    int *id_to_idx = (int *)malloc(n * sizeof(int));
    for (int i = 0; i < n; i++) id_to_idx[S[i].orig_id] = i;

    double t = 0, px = X0, py = Y0;
    for (int k = 0; k < g_best_path_len; k++) {
        int orig_id = g_best_path[k];
        int i = id_to_idx[orig_id];
        double dt = meet_func(t, px, py, i);
        if (dt < 0) dt = 0;
        t += dt;
        px = S[i].x + S[i].p * t;
        py = S[i].y + S[i].q * t;
        printf("%.9f %.9f %.9f %d\n", t, px, py, orig_id + 1);
    }

    free(id_to_idx);
    free(S);
    free(Z);
    free(g_best_path);
    return 0;
}
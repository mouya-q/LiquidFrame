#include <bits/stdc++.h>
#include <thread>
#include <mutex>
using namespace std;

struct Shrimp {
    double w, x, y, p, q;
    double speed_sq;
    int orig_id;
};

int n, W64;
double W0, V, T, X0, Y0, V2;
vector<Shrimp> S;
vector<uint64_t> Z;

struct Node {
    double t, x, y, wt;
    uint64_t hash;
    int par, shrimp;
};

struct BeamEntry {
    int node_idx;
    uint64_t hash;
    shared_ptr<vector<uint64_t>> mask;
};

struct SharedState {
    mutex mtx;
    double best_gain = -1;
    vector<int> best_path;   // 原始 id
};

// ---------- 单线程 Beam Search ----------
void worker(uint64_t seed, double time_limit, SharedState& shared) {
    mt19937_64 rng(seed);
    double start_time = (double)clock() / CLOCKS_PER_SEC;

    vector<Node> pool;
    pool.reserve(1 << 20);

    pool.push_back({0, X0, Y0, W0, 0, -1, -1});
    auto init_mask = make_shared<vector<uint64_t>>(W64, 0);

    vector<BeamEntry> beam;
    beam.push_back({0, 0, init_mask});

    int BW = 64;
    const int BW_cap = 1000000;

    struct Cand { double dt; int i; };
    vector<Cand> cands;
    cands.reserve(256);

    while (!beam.empty()) {
        double el = (double)clock() / CLOCKS_PER_SEC - start_time;
        if (el > time_limit) break;

        if (BW < BW_cap) BW = min(BW * 2, BW_cap);

        int K = min(n, max(20, BW / 60));
        if (K > 120) K = 120;

        vector<BeamEntry> next;
        next.reserve(BW * 2);

        for (auto& entry : beam) {
            const Node& cur = pool[entry.node_idx];
            const auto& mask = *entry.mask;

            double dt_max = T - cur.t;
            if (dt_max < 0) continue;

            // 预计算上界: 2(V^2 + |v_shrimp|^2) * dt^2
            double limit_base = 2.0 * V2 * dt_max * dt_max;
            double dt2 = dt_max * dt_max;

            cands.clear();

            for (int i = 0; i < n; ++i) {
                // 因为按 w[i] 升序，可以提前退出
                if (S[i].w > cur.wt + 1e-12) break;

                // 掩码检查（O(1)）
                if ((mask[i >> 6] >> (i & 63)) & 1) continue;

                double dx = S[i].x + S[i].p * cur.t - cur.x;
                double dy = S[i].y + S[i].q * cur.t - cur.y;
                double d2 = dx * dx + dy * dy;

                // d^2 预筛：无 sqrt
                if (d2 > limit_base + 2.0 * S[i].speed_sq * dt2 + 1.0)
                    continue;

                if (d2 < 1e-18) {
                    cands.push_back({0.0, i});
                    continue;
                }

                double a = S[i].speed_sq - V2;
                double b = dx * S[i].p + dy * S[i].q;
                double c = d2;
                double dt;

                if (fabs(a) < 1e-12) {
                    if (b >= 0) continue;
                    dt = -c / (2 * b);
                    if (dt < 0 || dt > dt_max) continue;
                } else {
                    double D = b * b - a * c;
                    if (D < 0) continue;
                    double sd = sqrt(D);
                    dt = (-b - sd) / a;
                    if (dt < 0 || dt > dt_max) {
                        dt = (-b + sd) / a;
                        if (dt < 0 || dt > dt_max) continue;
                    }
                }
                cands.push_back({dt, i});
            }

            if (cands.empty()) continue;

            // 选 top-K（O(c)）
            if ((int)cands.size() > K) {
                nth_element(cands.begin(), cands.begin() + K, cands.end(),
                            [](const Cand& a, const Cand& b) { return a.dt < b.dt; });
                cands.resize(K);
            }

            for (auto& cand : cands) {
                int i = cand.i;
                double dt = cand.dt;
                double nt = cur.t + dt;
                double nx = S[i].x + S[i].p * nt;
                double ny = S[i].y + S[i].q * nt;
                double nwt = cur.wt + S[i].w;
                uint64_t nh = cur.hash ^ Z[i];

                pool.push_back({nt, nx, ny, nwt, nh, entry.node_idx, i});
                int nid = (int)pool.size() - 1;

                auto nmask = make_shared<vector<uint64_t>>(mask);
                (*nmask)[i >> 6] |= 1ULL << (i & 63);

                next.push_back({nid, nh, std::move(nmask)});
            }
        }

        if (next.empty()) break;

        // 先粗筛 top BW*2
        if ((int)next.size() > BW * 2) {
            nth_element(next.begin(), next.begin() + BW * 2, next.end(),
                [&](const BeamEntry& a, const BeamEntry& b) {
                    return pool[a.node_idx].wt > pool[b.node_idx].wt;
                });
            next.resize(BW * 2);
        }

        sort(next.begin(), next.end(),
             [&](const BeamEntry& a, const BeamEntry& b) {
                 double wa = pool[a.node_idx].wt;
                 double wb = pool[b.node_idx].wt;
                 if (wa != wb) return wa > wb;
                 return pool[a.node_idx].t < pool[b.node_idx].t;
             });

        // 哈希去重
        vector<BeamEntry> filtered;
        filtered.reserve(BW);
        unordered_set<uint64_t> seen;
        seen.reserve(BW * 2);
        for (auto& e : next) {
            if ((int)filtered.size() >= BW) break;
            if (!seen.insert(e.hash).second) continue;
            filtered.push_back(std::move(e));
        }
        beam = std::move(filtered);

        // 更新全局最优
        if (!beam.empty()) {
            const Node& bn = pool[beam[0].node_idx];
            double gain = bn.wt - W0;
            if (gain > 0) {
                vector<int> path;
                for (int u = beam[0].node_idx;
                     u != -1 && pool[u].par != -1; u = pool[u].par)
                    path.push_back(S[pool[u].shrimp].orig_id);
                reverse(path.begin(), path.end());

                lock_guard<mutex> lg(shared.mtx);
                if (gain > shared.best_gain) {
                    shared.best_gain = gain;
                    shared.best_path = path;
                }
            }
        }
    }
}

int main() {
    scanf("%lf%lf%lf%lf%lf%d", &W0, &V, &T, &X0, &Y0, &n);
    V *= (1 - 1e-9);
    V2 = V * V;

    vector<Shrimp> raw(n);
    for (int i = 0; i < n; ++i) {
        scanf("%lf%lf%lf%lf%lf",
              &raw[i].w, &raw[i].x, &raw[i].y, &raw[i].p, &raw[i].q);
        raw[i].speed_sq = raw[i].p * raw[i].p + raw[i].q * raw[i].q;
        raw[i].orig_id = i;
    }

    // 按体重升序 —— 使内层循环可提前 break
    sort(raw.begin(), raw.end(),
         [](const Shrimp& a, const Shrimp& b) { return a.w < b.w; });
    S = std::move(raw);

    W64 = (n + 63) / 64;

    mt19937_64 rng(0xC0FFEE);
    Z.resize(n);
    for (auto& z : Z) z = rng();

    int nthreads = thread::hardware_concurrency();
    if (nthreads < 1) nthreads = 1;

    double total_time = 1500.0;
    if (const char* e = getenv("NEMO_TIME")) total_time = atof(e);

    SharedState shared;

    vector<thread> threads;
    for (int t = 0; t < nthreads; ++t) {
        threads.emplace_back([&, t]() {
            uint64_t seed = 0x123456789ABCDEF0ULL
                          + (uint64_t)t * 0x9E3779B97F4A7C15ULL;
            worker(seed, total_time, shared);
        });
    }
    for (auto& th : threads) th.join();

    // 重放最优路径，输出精确时刻位置
    vector<int> id_to_idx(n);
    for (int i = 0; i < n; ++i) id_to_idx[S[i].orig_id] = i;

    printf("%d\n%.6f\n", (int)shared.best_path.size(), shared.best_gain);

    double t = 0, px = X0, py = Y0;
    for (int orig_id : shared.best_path) {
        int i = id_to_idx[orig_id];
        double dx = S[i].x + S[i].p * t - px;
        double dy = S[i].y + S[i].q * t - py;
        double c = dx * dx + dy * dy;
        double a = S[i].speed_sq - V2;
        double b = dx * S[i].p + dy * S[i].q;
        double dt;
        if (c < 1e-18) dt = 0;
        else if (fabs(a) < 1e-12) dt = -c / (2 * b);
        else {
            double D = b * b - a * c;
            double sd = sqrt(max(0.0, D));
            dt = (-b - sd) / a;
            if (dt < 0) dt = (-b + sd) / a;
        }
        t += dt;
        px = S[i].x + S[i].p * t;
        py = S[i].y + S[i].q * t;
        printf("%.9f %.9f %.9f %d\n", t, px, py, orig_id + 1);
    }
    return 0;
}
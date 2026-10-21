package com.doudizhu.nn;

/**
 * 手数下界 oracle: 一手牌最少几步出完 (PerfectDou 论文附录 E.1 算法 1)
 *
 * <p>逐分支移植自 PerfectDouCPP {@code src/ddz/oracle.cpp}: 无王牌手牌按「组计数」
 * (a 单张 / b 对子 / c 三张 / d 炸弹) 做 DP 查表, 顺子/连对/飞机由外层记忆化 DFS 枚举。
 * 该值直接参与神经网络输入特征 (每手候选牌减去后的剩余手数), 因此必须与训练侧逐位一致。
 *
 * <p>牌索引统一采用训练侧顺序 0..14 = 3 4 5 6 7 8 9 T J Q K A 2 小王 大王,
 * 即 {@code com.doudizhu.model.Rank.getValue() - 3}。
 */
final class Oracle {

    /** 花色无关的点数序, 相邻差值为 1 才能连成链; A(14) 到 2(17) 的断档即由此而来 */
    private static final int[] RANK_VALUES = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30};

    private static final int RANKS = 15;
    private static final int NORMAL_RANKS = 13;
    private static final int MIN_STRAIGHT = 5;
    private static final int MIN_PAIR_CHAIN = 3;
    private static final int MIN_PLANE = 2;

    private static final int MAX_A = 22;
    private static final int MAX_B = 15;
    private static final int MAX_C = 9;
    private static final int MAX_D = 6;

    private static final int IMPOSSIBLE = 100;

    /** F(a,b,c,d) 打平成一维, -1 表示尚未计算 */
    private static final byte[] F = new byte[MAX_A * MAX_B * MAX_C * MAX_D];

    static {
        java.util.Arrays.fill(F, (byte) -1);
        fSet(0, 0, 0, 0, 0);
        for (int total = 1; total <= 20; ++total) {
            for (int a = 0; a < MAX_A; ++a) {
                for (int b = 0; b < MAX_B; ++b) {
                    for (int c = 0; c < MAX_C; ++c) {
                        for (int d = 0; d < MAX_D; ++d) {
                            if (a + 2 * b + 3 * c + 4 * d != total) {
                                continue;
                            }
                            int[] best = {a + b + c + d};
                            if (a >= 1) best[0] = Math.min(best[0], 1 + fGet(a - 1, b, c, d));
                            if (b >= 1) best[0] = Math.min(best[0], 1 + fGet(a, b - 1, c, d));
                            if (c >= 1) best[0] = Math.min(best[0], 1 + fGet(a, b, c - 1, d));
                            if (d >= 1) best[0] = Math.min(best[0], 1 + fGet(a, b, c, d - 1));
                            if (c >= 1) {
                                enumLooseKickers(a, b, c - 1, d, 1,
                                        (na, nb, nc, nd) -> best[0] = Math.min(best[0], 1 + fGet(na, nb, nc, nd)));
                            }
                            if (c >= 1) {
                                if (b >= 1) best[0] = Math.min(best[0], 1 + fGet(a, b - 1, c - 1, d));
                                if (c >= 2) best[0] = Math.min(best[0], 1 + fGet(a + 1, b, c - 2, d));
                                if (d >= 1) best[0] = Math.min(best[0], 1 + fGet(a, b, c - 1, d - 1));
                            }
                            if (d >= 1) {
                                enumLooseKickers(a, b, c, d - 1, 2,
                                        (na, nb, nc, nd) -> best[0] = Math.min(best[0], 1 + fGet(na, nb, nc, nd)));
                            }
                            if (d >= 1) {
                                if (b >= 2) best[0] = Math.min(best[0], 1 + fGet(a, b - 2, c, d - 1));
                                if (b >= 1 && c >= 1) {
                                    best[0] = Math.min(best[0], 1 + fGet(a + 1, b - 1, c - 1, d - 1));
                                }
                                if (c >= 2) best[0] = Math.min(best[0], 1 + fGet(a + 2, b, c - 2, d - 1));
                            }
                            fSet(a, b, c, d, best[0]);
                        }
                    }
                }
            }
        }
    }

    private Oracle() {
    }

    private static int fIndex(int a, int b, int c, int d) {
        return ((a * MAX_B + b) * MAX_C + c) * MAX_D + d;
    }

    private static void fSet(int a, int b, int c, int d, int value) {
        F[fIndex(a, b, c, d)] = (byte) value;
    }

    private static int fGet(int a, int b, int c, int d) {
        if (a < 0 || b < 0 || c < 0 || d < 0) {
            return IMPOSSIBLE;
        }
        if (a >= MAX_A || b >= MAX_B || c >= MAX_C || d >= MAX_D) {
            return a + b + c + d;
        }
        int v = F[fIndex(a, b, c, d)];
        return v < 0 ? a + b + c + d : v;
    }

    /** 组计数表的读取入口; 表在类初始化时已建好, 与 C++ 的 call_once 语义等价 */
    private static int fDP(int a, int b, int c, int d) {
        return fGet(a, b, c, d);
    }

    /** 枚举「拿 need 张散牌」的所有拆组方式, 拆剩的组自动降档 */
    private interface KickerSink {
        void accept(int a, int b, int c, int d);
    }

    private static void enumLooseKickers(int a, int b, int c, int d, int need, KickerSink fn) {
        for (int s = 0; s <= Math.min(need, a); ++s) {
            int r1 = need - s;
            if (r1 == 0) {
                fn.accept(a - s, b, c, d);
                continue;
            }
            for (int bp = 0; bp <= Math.min(b, r1); ++bp) {
                for (int pu = Math.max(bp, 0); pu <= Math.min(2 * bp, r1); ++pu) {
                    int r2 = r1 - pu;
                    int pairLeftSingles = 2 * bp - pu;
                    if (r2 == 0) {
                        fn.accept(a - s + pairLeftSingles, b - bp, c, d);
                        continue;
                    }
                    for (int bt = 0; bt <= Math.min(c, r2); ++bt) {
                        for (int tu = bt; tu <= Math.min(3 * bt, r2); ++tu) {
                            int r3 = r2 - tu;
                            for (int t2 = 0; t2 <= bt; ++t2) {
                                for (int t1 = 0; t1 + t2 <= bt; ++t1) {
                                    int t3 = bt - t1 - t2;
                                    if (t1 + 2 * t2 + 3 * t3 != tu) {
                                        continue;
                                    }
                                    int r4 = r3;
                                    if (r4 == 0) {
                                        fn.accept(a - s + pairLeftSingles + t2,
                                                  b - bp + t1, c - bt, d);
                                        continue;
                                    }
                                    for (int bd = 0; bd <= Math.min(d, r4); ++bd) {
                                        for (int bu = bd; bu <= Math.min(4 * bd, r4); ++bu) {
                                            if (bu != r4) {
                                                continue;
                                            }
                                            for (int u3 = 0; u3 <= bd; ++u3) {
                                                for (int u2 = 0; u2 + u3 <= bd; ++u2) {
                                                    for (int u1 = 0; u1 + u2 + u3 <= bd; ++u1) {
                                                        int u4 = bd - u1 - u2 - u3;
                                                        if (u1 + 2 * u2 + 3 * u3 + 4 * u4 != bu) {
                                                            continue;
                                                        }
                                                        fn.accept(a - s + pairLeftSingles + t2 + u3,
                                                                  b - bp + t1 + u2,
                                                                  c - bt + u1, d - bd);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 直接映射缓存, 与 C++ 同为 65536 槽; 按线程隔离, 对应 thread_local */
    private static final int CACHE_SLOTS = 65536;
    private static final int CACHE_MASK = CACHE_SLOTS - 1;

    private static final class Memo {
        final long[] keys = new long[CACHE_SLOTS];
        final byte[] vals = new byte[CACHE_SLOTS];
    }

    private static final ThreadLocal<Memo> MEMO = ThreadLocal.withInitial(Memo::new);

    private static long packKey(int[] cnt) {
        long k = 0;
        for (int r = 0; r < RANKS; ++r) {
            k |= ((long) cnt[r]) << (3 * r);
        }
        return k;
    }

    /** MurmurHash3 64 位混淆 */
    private static int hashKey(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return (int) (k & CACHE_MASK);
    }

    /** 是否存在可能的顺子/连对/飞机链, 用于走无链快路径 */
    private static boolean hasPotentialChain(int[] cnt) {
        int maxConsecutive = 0;
        int curConsecutive = 0;
        int maxPairChain = 0;
        int curPairChain = 0;
        int maxPlane = 0;
        int curPlane = 0;
        for (int r = 0; r < NORMAL_RANKS; ++r) {
            int c = cnt[r];
            if (c >= 1) {
                ++curConsecutive;
                maxConsecutive = Math.max(maxConsecutive, curConsecutive);
            } else {
                curConsecutive = 0;
            }
            if (c >= 2) {
                ++curPairChain;
                maxPairChain = Math.max(maxPairChain, curPairChain);
            } else {
                curPairChain = 0;
            }
            if (c >= 3) {
                ++curPlane;
                maxPlane = Math.max(maxPlane, curPlane);
            } else {
                curPlane = 0;
            }
        }
        return maxConsecutive >= MIN_STRAIGHT || maxPairChain >= MIN_PAIR_CHAIN || maxPlane >= MIN_PLANE;
    }

    /** 忽略链牌的组数下界 */
    private static int lowerBound(int[] cnt) {
        int a = 0;
        int b = 0;
        int c = 0;
        int d = 0;
        for (int r = 0; r < NORMAL_RANKS; ++r) {
            if (cnt[r] == 1) {
                ++a;
            } else if (cnt[r] == 2) {
                ++b;
            } else if (cnt[r] == 3) {
                ++c;
            } else if (cnt[r] == 4) {
                ++d;
            }
        }
        int jokers = (cnt[13] != 0 ? 1 : 0) + (cnt[14] != 0 ? 1 : 0);
        if (cnt[13] != 0 && cnt[14] != 0) {
            jokers = 1; // 王炸算一步
        }
        return fDP(a, b, c, d) + jokers;
    }

    /** 逐点枚举 need 张带的全部多重集合 */
    private interface WingSink {
        void accept(int[] pick);
    }

    private static void enumWingMultisets(int[] cnt, int rank, int need, int[] pick, WingSink fn) {
        if (need == 0) {
            fn.accept(pick);
            return;
        }
        if (rank == RANKS) {
            return;
        }
        int mx = Math.min(cnt[rank], need);
        for (int take = 0; take <= mx; ++take) {
            pick[rank] = take;
            enumWingMultisets(cnt, rank + 1, need - take, pick, fn);
        }
        pick[rank] = 0;
    }

    private static int solve(int[] cnt) {
        long key = packKey(cnt);
        if (key == 0) {
            return 0;
        }
        Memo memo = MEMO.get();
        int h = hashKey(key);
        if (memo.keys[h] == key && memo.vals[h] > 0) {
            return memo.vals[h];
        }

        // 无链可能时组数下界即为精确解
        if (!hasPotentialChain(cnt)) {
            int ans = lowerBound(cnt);
            memo.keys[h] = key;
            memo.vals[h] = (byte) ans;
            return ans;
        }

        int[] best = {IMPOSSIBLE};
        if (cnt[13] != 0 || cnt[14] != 0) {
            int[] rem = cnt.clone();
            rem[13] = 0;
            rem[14] = 0;
            best[0] = Math.min(best[0], 1 + solve(rem));
        }

        int a = 0;
        int b = 0;
        int c = 0;
        int d = 0;
        for (int r = 0; r < NORMAL_RANKS; ++r) {
            if (cnt[r] == 1) {
                ++a;
            } else if (cnt[r] == 2) {
                ++b;
            } else if (cnt[r] == 3) {
                ++c;
            } else if (cnt[r] == 4) {
                ++d;
            }
        }
        int jokerGroups = (cnt[13] != 0 ? 1 : 0) + (cnt[14] != 0 ? 1 : 0);
        if (cnt[13] != 0 && cnt[14] != 0) {
            jokerGroups = 1;
        }
        best[0] = Math.min(best[0], fDP(a, b, c, d) + jokerGroups);

        if (best[0] > 1) {
            enumerateChains(cnt, 1, MIN_STRAIGHT, false, best);
            enumerateChains(cnt, 2, MIN_PAIR_CHAIN, false, best);
            enumerateChains(cnt, 3, MIN_PLANE, true, best);
        }

        memo.keys[h] = key;
        memo.vals[h] = (byte) best[0];
        return best[0];
    }

    /** 在连续可连的区段上枚举所有链长与起点, 必要时再枚举带牌 */
    private static void enumerateChains(int[] cnt, int repeat, int minLen, boolean trios, int[] best) {
        int i = 0;
        while (i < NORMAL_RANKS) {
            if (cnt[i] < repeat) {
                ++i;
                continue;
            }
            int j = i;
            while (j + 1 < NORMAL_RANKS && cnt[j + 1] >= repeat
                    && RANK_VALUES[j + 1] - RANK_VALUES[j] == 1) {
                ++j;
            }
            int runLen = j - i + 1;
            for (int len = minLen; len <= runLen; ++len) {
                for (int s = i; s + len <= j + 1; ++s) {
                    int[] rem = cnt.clone();
                    for (int x = 0; x < len; ++x) {
                        rem[s + x] -= repeat;
                    }
                    if (1 + lowerBound(rem) < best[0]) {
                        best[0] = Math.min(best[0], 1 + solve(rem));
                    }
                    if (trios) {
                        if (4 * len <= 20) {
                            int[] pick = new int[RANKS];
                            enumWingMultisets(rem, 0, len, pick, w -> {
                                int[] after = rem.clone();
                                for (int r = 0; r < RANKS; ++r) {
                                    after[r] -= w[r];
                                }
                                if (1 + lowerBound(after) < best[0]) {
                                    best[0] = Math.min(best[0], 1 + solve(after));
                                }
                            });
                        }
                        if (5 * len <= 20) {
                            int[] pairRanks = new int[NORMAL_RANKS];
                            int np = 0;
                            for (int r = 0; r < NORMAL_RANKS; ++r) {
                                if (rem[r] >= 2) {
                                    pairRanks[np++] = r;
                                }
                            }
                            if (np >= len) {
                                choosePairCombos(rem, pairRanks, np, len, 0, 0, new int[len], best);
                            }
                        }
                    }
                }
            }
            i = j + 1;
        }
    }

    /** 飞机带对: 从可对的点数里选 len 个不同点数的组合 */
    private static void choosePairCombos(int[] rem, int[] pairRanks, int np, int len,
                                     int idx, int start, int[] sel, int[] best) {
        if (idx == len) {
            int[] after = rem.clone();
            for (int r : sel) {
                after[r] -= 2;
            }
            if (1 + lowerBound(after) < best[0]) {
                best[0] = Math.min(best[0], 1 + solve(after));
            }
            return;
        }
        for (int x = start; x + (len - idx - 1) < np; ++x) {
            sel[idx] = pairRanks[x];
            choosePairCombos(rem, pairRanks, np, len, idx + 1, x + 1, sel, best);
        }
    }

    /**
     * 一手牌的最少出牌步数。
     *
     * @param cnt 15 个点数上的张数, 索引 0..14 对应 3..A,2,小王,大王
     */
    static int minSteps(int[] cnt) {
        return solve(cnt);
    }
}

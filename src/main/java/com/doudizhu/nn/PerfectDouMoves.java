package com.doudizhu.nn;

import com.doudizhu.model.CardType;
import com.doudizhu.model.Move;
import com.doudizhu.model.Rank;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 与训练侧一致的动作枚举与牌型判定 (移植自 PerfectDouCPP {@code src/ddz/moves.cpp})
 *
 * <p>{@link com.doudizhu.rules.MoveGenerator} 对「飞机带翅」只生成一种带牌组合, 四带二也不允许
 * 用一对当两单; 而策略网络是在完整枚举所有具体带牌组合的动作空间上训练的。为了让网络拿到与训练
 * 时一致的动作集合, 这里独立实现完整枚举, 不改动引擎原有的生成器。
 *
 * <p>牌索引 0..14 = 3 4 5 6 7 8 9 T J Q K A 2 小王 大王, 数值用 {@link #RANK_VALUES}
 * (3..14,17,20,30), 相邻数值差 1 才能连成链, 因此 2 与王天然被切断。
 */
public final class PerfectDouMoves {

    // 牌型编码, 与 C++ MoveType 一一对应
    public static final int MT_PASS = 0;
    public static final int MT_SINGLE = 1;
    public static final int MT_PAIR = 2;
    public static final int MT_TRIPLE = 3;
    public static final int MT_BOMB = 4;
    public static final int MT_ROCKET = 5;
    public static final int MT_TRIPLE_ONE = 6;
    public static final int MT_TRIPLE_TWO = 7;
    public static final int MT_STRAIGHT = 8;
    public static final int MT_PAIR_STRAIGHT = 9;
    public static final int MT_PLANE = 10;
    public static final int MT_PLANE_SOLO = 11;
    public static final int MT_PLANE_PAIR = 12;
    public static final int MT_FOUR_TWO = 13;
    public static final int MT_FOUR_TWO_PAIR = 14;
    public static final int MT_WRONG = 15;

    private static final int RANKS = 15;
    private static final int NORMAL_RANKS = 13;
    private static final int MIN_STRAIGHT = 5;
    private static final int MIN_PAIR_CHAIN = 3;
    private static final int MIN_PLANE = 2;

    private static final int[] RANK_VALUES = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30};

    private PerfectDouMoves() {
    }

    /** 牌型信息: type + 主牌点数索引 (链牌为链的最小点数) + 链长 */
    public record Info(int type, int rank, int len) {
    }

    public static int rankValue(int rankIndex) {
        return RANK_VALUES[rankIndex];
    }

    public static int valueRank(int value) {
        return switch (value) {
            case 17 -> 12;
            case 20 -> 13;
            case 30 -> 14;
            default -> value - 3;
        };
    }

    public static int total(int[] counts) {
        int s = 0;
        for (int c : counts) {
            s += c;
        }
        return s;
    }

    private static boolean isEmpty(int[] counts) {
        return total(counts) == 0;
    }

    /** 点数多重集的稳定 45 位指纹, 每点数占 3 位 */
    static long key(int[] counts) {
        long k = 0;
        for (int r = 0; r < RANKS; ++r) {
            k |= ((long) counts[r]) << (3 * r);
        }
        return k;
    }

    // ---------------------------------------------------------------------------
    // 牌型判定
    // ---------------------------------------------------------------------------

    private static int[] valuesOf(int[] m) {
        List<Integer> v = new ArrayList<>();
        for (int r = 0; r < RANKS; ++r) {
            for (int k = 0; k < m[r]; ++k) {
                v.add(RANK_VALUES[r]);
            }
        }
        return v.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean continuous(int[] v) {
        for (int i = 1; i < v.length; ++i) {
            if (v[i] - v[i - 1] != 1) {
                return false;
            }
        }
        return true;
    }

    /** 判定一手工牌; 无法归类时 type 为 {@link #MT_WRONG} */
    public static Info detect(int[] m) {
        int size = total(m);
        if (size == 0) {
            return new Info(MT_PASS, -1, 1);
        }
        if (size == 1) {
            for (int r = 0; r < RANKS; ++r) {
                if (m[r] != 0) {
                    return new Info(MT_SINGLE, r, 1);
                }
            }
        }
        if (size == 2) {
            for (int r = 0; r < NORMAL_RANKS; ++r) {
                if (m[r] == 2) {
                    return new Info(MT_PAIR, r, 1);
                }
            }
            if (m[13] == 1 && m[14] == 1) {
                return new Info(MT_ROCKET, -1, 1);
            }
            return new Info(MT_WRONG, -1, 1);
        }
        if (size == 3) {
            for (int r = 0; r < RANKS; ++r) {
                if (m[r] == 3) {
                    return new Info(MT_TRIPLE, r, 1);
                }
            }
            return new Info(MT_WRONG, -1, 1);
        }
        if (size == 4) {
            for (int r = 0; r < NORMAL_RANKS; ++r) {
                if (m[r] == 4) {
                    return new Info(MT_BOMB, r, 1);
                }
            }
            int distinct = 0;
            int triRank = -1;
            for (int r = 0; r < RANKS; ++r) {
                if (m[r] == 3) {
                    triRank = r;
                    ++distinct;
                } else if (m[r] == 1) {
                    ++distinct;
                } else if (m[r] != 0) {
                    return new Info(MT_WRONG, -1, 1);
                }
            }
            if (distinct == 2 && triRank >= 0) {
                return new Info(MT_TRIPLE_ONE, triRank, 1);
            }
            return new Info(MT_WRONG, -1, 1);
        }

        int[] vals = valuesOf(m);
        if (continuous(vals)) {
            return new Info(MT_STRAIGHT, valueRank(vals[0]), size);
        }

        if (size == 5) {
            int distinct = 0;
            int triRank = -1;
            for (int r = 0; r < RANKS; ++r) {
                if (m[r] == 3) {
                    triRank = r;
                    ++distinct;
                } else if (m[r] == 2) {
                    ++distinct;
                } else if (m[r] != 0) {
                    return new Info(MT_WRONG, -1, 1);
                }
            }
            if (distinct == 2 && triRank >= 0) {
                return new Info(MT_TRIPLE_TWO, triRank, 1);
            }
            return new Info(MT_WRONG, -1, 1);
        }

        int[] byCount = new int[5];
        int distinct = 0;
        for (int r = 0; r < RANKS; ++r) {
            if (m[r] > 0) {
                ++byCount[m[r]];
                ++distinct;
            }
        }

        if (size == 6) {
            boolean shape = (distinct == 2 || distinct == 3) && byCount[4] == 1
                    && (byCount[2] == 1 || byCount[1] == 2);
            if (shape) {
                int rank = -1;
                for (int r = 0; r < RANKS; ++r) {
                    if (m[r] == 4) {
                        rank = r;
                        break;
                    }
                }
                return new Info(MT_FOUR_TWO, rank, 1);
            }
        }
        if (size == 8) {
            boolean shape = ((distinct == 3 || distinct == 2) && byCount[4] == 1 && byCount[2] == 2)
                    || byCount[4] == 2;
            if (shape) {
                int rank = -1;
                for (int r = RANKS - 1; r >= 0; --r) {
                    if (m[r] == 4) {
                        rank = r;
                        break;
                    }
                }
                return new Info(MT_FOUR_TWO_PAIR, rank, 1);
            }
        }

        int[] present = new int[RANKS];
        int np = 0;
        for (int r = 0; r < RANKS; ++r) {
            if (m[r] > 0) {
                present[np++] = r;
            }
        }

        if (distinct == byCount[2]) {
            int[] pairVals = new int[np];
            for (int i = 0; i < np; ++i) {
                pairVals[i] = RANK_VALUES[present[i]];
            }
            if (continuous(pairVals)) {
                return new Info(MT_PAIR_STRAIGHT, present[0], np);
            }
        }
        if (distinct == byCount[3]) {
            int[] trioVals = new int[np];
            for (int i = 0; i < np; ++i) {
                trioVals[i] = RANK_VALUES[present[i]];
            }
            if (continuous(trioVals)) {
                return new Info(MT_PLANE, present[0], np);
            }
        }

        if (byCount[3] >= MIN_PLANE) {
            List<Integer> triRanks = new ArrayList<>();
            List<Integer> soloRanks = new ArrayList<>();
            List<Integer> pairRanks = new ArrayList<>();
            boolean bad = false;
            for (int r = 0; r < RANKS; ++r) {
                if (m[r] == 3) {
                    triRanks.add(r);
                } else if (m[r] == 1) {
                    soloRanks.add(r);
                } else if (m[r] == 2) {
                    pairRanks.add(r);
                } else if (m[r] != 0) {
                    bad = true;
                    break;
                }
            }
            if (!bad) {
                int[] triVals = new int[triRanks.size()];
                for (int i = 0; i < triVals.length; ++i) {
                    triVals[i] = RANK_VALUES[triRanks.get(i)];
                }
                if (continuous(triVals)) {
                    int nTri = triRanks.size();
                    if (nTri == soloRanks.size() + 2 * pairRanks.size()) {
                        return new Info(MT_PLANE_SOLO, triRanks.get(0), nTri);
                    }
                    if (nTri == pairRanks.size() && distinct == 2 * nTri) {
                        return new Info(MT_PLANE_PAIR, triRanks.get(0), nTri);
                    }
                }
                // DouZero 边界: 四个连续三张可以当作「三张链 + 第四个三张拆成三张单翅」
                if (triRanks.size() == 4) {
                    if (continuousSub(triRanks, 1, 3)) {
                        return new Info(MT_PLANE_SOLO, triRanks.get(1), 3);
                    }
                    if (continuousSub(triRanks, 0, 3)) {
                        return new Info(MT_PLANE_SOLO, triRanks.get(0), 3);
                    }
                }
            }
        }
        return new Info(MT_WRONG, -1, 1);
    }

    private static boolean continuousSub(List<Integer> ranks, int from, int len) {
        int[] v = new int[len];
        for (int i = 0; i < len; ++i) {
            v[i] = RANK_VALUES[ranks.get(from + i)];
        }
        return continuous(v);
    }

    // ---------------------------------------------------------------------------
    // 生成器
    // ---------------------------------------------------------------------------

    private static int[] blank() {
        return new int[RANKS];
    }

    /** 在 items 上枚举取 num 个位置的所有组合 (同值的不同位置会产生重复多重集, 由 dedupe 去除) */
    private interface ComboSink {
        void accept(int[] pickedIndices, int len);
    }

    private static void forEachCombination(int[] items, int num, ComboSink fn) {
        int n = items.length;
        if (num > n || num <= 0) {
            return;
        }
        int[] idx = new int[num];
        for (int i = 0; i < num; ++i) {
            idx[i] = i;
        }
        while (true) {
            fn.accept(idx, num);
            int i = num - 1;
            while (i >= 0 && idx[i] == i + n - num) {
                --i;
            }
            if (i < 0) {
                return;
            }
            ++idx[i];
            for (int j = i + 1; j < num; ++j) {
                idx[j] = idx[j - 1] + 1;
            }
        }
    }

    private static int[] fromRanks(List<Integer> ranks, int repeat) {
        int[] m = blank();
        for (int r : ranks) {
            m[r] += repeat;
        }
        return m;
    }

    /** 与训练侧一致的枚举器, 逐类型对应 C++ MoveGenerator 的成员函数 */
    static final class Generator {
        private final int[] hand;
        private final int[] presentRanks;

        Generator(int[] hand) {
            this.hand = hand.clone();
            int n = 0;
            for (int r = 0; r < RANKS; ++r) {
                if (hand[r] > 0) {
                    ++n;
                }
            }
            this.presentRanks = new int[n];
            int i = 0;
            for (int r = 0; r < RANKS; ++r) {
                if (hand[r] > 0) {
                    this.presentRanks[i++] = r;
                }
            }
        }

        List<int[]> genAll() {
            List<int[]> out = new ArrayList<>();
            out.addAll(genSingles());
            out.addAll(genPairs());
            out.addAll(genTriples());
            out.addAll(genBombs());
            out.addAll(genRocket());
            out.addAll(genTripleSolo());
            out.addAll(genTriplePair());
            out.addAll(genSerials(1, MIN_STRAIGHT, 0));
            out.addAll(genSerials(2, MIN_PAIR_CHAIN, 0));
            out.addAll(genSerials(3, MIN_PLANE, 0));
            out.addAll(genPlaneSolo(0));
            out.addAll(genPlanePair(0));
            out.addAll(genFourTwo());
            out.addAll(genFourTwoPair());
            return dedupe(out);
        }

        List<int[]> genByType(int type, int len) {
            List<int[]> out = switch (type) {
                case MT_SINGLE -> genSingles();
                case MT_PAIR -> genPairs();
                case MT_TRIPLE -> genTriples();
                case MT_BOMB -> {
                    List<int[]> bombs = new ArrayList<>(genBombs());
                    bombs.addAll(genRocket());
                    yield bombs;
                }
                case MT_TRIPLE_ONE -> genTripleSolo();
                case MT_TRIPLE_TWO -> genTriplePair();
                case MT_STRAIGHT -> genSerials(1, MIN_STRAIGHT, len);
                case MT_PAIR_STRAIGHT -> genSerials(2, MIN_PAIR_CHAIN, len);
                case MT_PLANE -> genSerials(3, MIN_PLANE, len);
                case MT_PLANE_SOLO -> genPlaneSolo(len);
                case MT_PLANE_PAIR -> genPlanePair(len);
                case MT_FOUR_TWO -> genFourTwo();
                case MT_FOUR_TWO_PAIR -> genFourTwoPair();
                default -> new ArrayList<>();
            };
            return dedupe(out);
        }

        private List<int[]> genSingles() {
            List<int[]> r = new ArrayList<>();
            for (int rank : presentRanks) {
                int[] m = blank();
                m[rank] = 1;
                r.add(m);
            }
            return r;
        }

        private List<int[]> genPairs() {
            List<int[]> r = new ArrayList<>();
            for (int rank = 0; rank < RANKS; ++rank) {
                if (hand[rank] >= 2) {
                    int[] m = blank();
                    m[rank] = 2;
                    r.add(m);
                }
            }
            return r;
        }

        private List<int[]> genTriples() {
            List<int[]> r = new ArrayList<>();
            for (int rank = 0; rank < RANKS; ++rank) {
                if (hand[rank] >= 3) {
                    int[] m = blank();
                    m[rank] = 3;
                    r.add(m);
                }
            }
            return r;
        }

        private List<int[]> genBombs() {
            List<int[]> r = new ArrayList<>();
            for (int rank = 0; rank < NORMAL_RANKS; ++rank) {
                if (hand[rank] == 4) {
                    int[] m = blank();
                    m[rank] = 4;
                    r.add(m);
                }
            }
            return r;
        }

        private List<int[]> genRocket() {
            List<int[]> r = new ArrayList<>();
            if (hand[13] != 0 && hand[14] != 0) {
                int[] m = blank();
                m[13] = 1;
                m[14] = 1;
                r.add(m);
            }
            return r;
        }

        private List<int[]> genTripleSolo() {
            List<int[]> r = new ArrayList<>();
            for (int t = 0; t < RANKS; ++t) {
                if (hand[t] < 3) {
                    continue;
                }
                for (int k : presentRanks) {
                    if (k == t) {
                        continue;
                    }
                    int[] m = blank();
                    m[t] = 3;
                    m[k] += 1;
                    r.add(m);
                }
            }
            return r;
        }

        private List<int[]> genTriplePair() {
            List<int[]> r = new ArrayList<>();
            for (int t = 0; t < RANKS; ++t) {
                if (hand[t] < 3) {
                    continue;
                }
                for (int p = 0; p < NORMAL_RANKS; ++p) {
                    if (p == t || hand[p] < 2) {
                        continue;
                    }
                    int[] m = blank();
                    m[t] = 3;
                    m[p] = 2;
                    r.add(m);
                }
            }
            return r;
        }

        /** repeat = 1 顺子 / 2 连对 / 3 飞机; fixedLen 为 0 时枚举所有长度 */
        private List<int[]> genSerials(int repeat, int minLen, int fixedLen) {
            List<Integer> ranks = new ArrayList<>();
            for (int r = 0; r < RANKS; ++r) {
                if (hand[r] >= repeat) {
                    ranks.add(r);
                }
            }
            List<int[]> runs = new ArrayList<>();   // 每项 {起始下标, 段长}
            int i = 0;
            while (i < ranks.size()) {
                int j = i;
                while (j + 1 < ranks.size()
                        && RANK_VALUES[ranks.get(j + 1)] - RANK_VALUES[ranks.get(j)] == 1) {
                    ++j;
                }
                runs.add(new int[]{i, j - i + 1});
                i = j + 1;
            }
            List<int[]> out = new ArrayList<>();
            for (int[] run : runs) {
                int start = run[0];
                int longest = run[1];
                if (longest < minLen) {
                    continue;
                }
                if (fixedLen > 0) {
                    if (longest < fixedLen) {
                        continue;
                    }
                    for (int idx = 0; idx + fixedLen <= longest; ++idx) {
                        out.add(fromRanks(ranks.subList(start + idx, start + idx + fixedLen), repeat));
                    }
                } else {
                    for (int len = minLen; len <= longest; ++len) {
                        for (int idx = 0; idx + len <= longest; ++idx) {
                            out.add(fromRanks(ranks.subList(start + idx, start + idx + len), repeat));
                        }
                    }
                }
            }
            return out;
        }

        private List<int[]> genPlaneSolo(int fixedLen) {
            List<int[]> planes = genSerials(3, MIN_PLANE, fixedLen);
            List<int[]> out = new ArrayList<>();
            for (int[] pl : planes) {
                List<Integer> rest = new ArrayList<>();   // 拆掉整条三张后的实际牌
                int chainLen = 0;
                for (int r = 0; r < RANKS; ++r) {
                    if (pl[r] == 3) {
                        ++chainLen;
                        continue;
                    }
                    for (int k = 0; k < hand[r]; ++k) {
                        rest.add(r);
                    }
                }
                int[] restArr = rest.stream().mapToInt(Integer::intValue).toArray();
                forEachCombination(restArr, chainLen, (idx, len) -> {
                    int[] m = pl.clone();
                    for (int i = 0; i < len; ++i) {
                        m[restArr[idx[i]]] += 1;
                    }
                    out.add(m);
                });
            }
            return out;
        }

        private List<int[]> genPlanePair(int fixedLen) {
            List<int[]> planes = genSerials(3, MIN_PLANE, fixedLen);
            List<int[]> out = new ArrayList<>();
            for (int[] pl : planes) {
                List<Integer> candidates = new ArrayList<>();
                int chainLen = 0;
                for (int r = 0; r < RANKS; ++r) {
                    if (pl[r] == 3) {
                        ++chainLen;
                        continue;
                    }
                    if (hand[r] >= 2 && r < NORMAL_RANKS) {
                        candidates.add(r);
                    }
                }
                int[] cand = candidates.stream().mapToInt(Integer::intValue).toArray();
                forEachCombination(cand, chainLen, (idx, len) -> {
                    int[] m = pl.clone();
                    for (int i = 0; i < len; ++i) {
                        m[cand[idx[i]]] += 2;
                    }
                    out.add(m);
                });
            }
            return out;
        }

        private List<int[]> genFourTwo() {
            List<int[]> out = new ArrayList<>();
            for (int f = 0; f < NORMAL_RANKS; ++f) {
                if (hand[f] != 4) {
                    continue;
                }
                List<Integer> rest = new ArrayList<>();
                for (int r = 0; r < RANKS; ++r) {
                    int n = hand[r] - (r == f ? 4 : 0);
                    for (int k = 0; k < n; ++k) {
                        rest.add(r);
                    }
                }
                int[] restArr = rest.stream().mapToInt(Integer::intValue).toArray();
                final int quad = f;
                forEachCombination(restArr, 2, (idx, len) -> {
                    int[] m = blank();
                    m[quad] = 4;
                    m[restArr[idx[0]]] += 1;
                    m[restArr[idx[1]]] += 1;
                    out.add(m);
                });
            }
            return out;
        }

        private List<int[]> genFourTwoPair() {
            List<int[]> out = new ArrayList<>();
            for (int f = 0; f < NORMAL_RANKS; ++f) {
                if (hand[f] != 4) {
                    continue;
                }
                List<Integer> pairRanks = new ArrayList<>();
                for (int r = 0; r < NORMAL_RANKS; ++r) {
                    if (r != f && hand[r] >= 2) {
                        pairRanks.add(r);
                    }
                }
                int[] pr = pairRanks.stream().mapToInt(Integer::intValue).toArray();
                final int quad = f;
                forEachCombination(pr, 2, (idx, len) -> {
                    int[] m = blank();
                    m[quad] = 4;
                    m[pr[idx[0]]] += 2;
                    m[pr[idx[1]]] += 2;
                    out.add(m);
                });
            }
            return out;
        }
    }

    private static List<int[]> dedupe(List<int[]> moves) {
        Set<Long> seen = new HashSet<>(moves.size() * 2);
        List<int[]> out = new ArrayList<>(moves.size());
        for (int[] m : moves) {
            if (seen.add(key(m))) {
                out.add(m);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------
    // 压牌过滤
    // ---------------------------------------------------------------------------

    private static boolean beats(int[] move, int[] rival, int type) {
        Info a = detect(move);
        Info b = detect(rival);
        if (a.type() == MT_ROCKET) {
            return true;
        }
        if (a.type() == MT_BOMB && type != MT_BOMB) {
            return true;
        }
        switch (type) {
            case MT_SINGLE:
            case MT_PAIR:
            case MT_TRIPLE:
            case MT_BOMB:
            case MT_STRAIGHT:
            case MT_PAIR_STRAIGHT:
            case MT_PLANE:
                return a.type() == type && a.len() == b.len() && a.rank() > b.rank();
            case MT_TRIPLE_ONE:
            case MT_TRIPLE_TWO:
            case MT_FOUR_TWO:
            case MT_FOUR_TWO_PAIR:
                return a.type() == type && a.rank() > b.rank();
            case MT_PLANE_SOLO:
            case MT_PLANE_PAIR: {
                if (a.type() != type || a.len() != b.len()) {
                    return false;
                }
                int ra = -1;
                int rb = -1;
                for (int r = 0; r < RANKS; ++r) {
                    if (move[r] == 3) {
                        ra = r;   // 最高的三张点数
                    }
                    if (rival[r] == 3) {
                        rb = r;
                    }
                }
                return ra > rb;
            }
            default:
                return false;
        }
    }

    /**
     * 当前手的合法动作 (多重集)。应牌时最后一项恒为过牌; 自由出牌不含过牌。
     *
     * @param hand    当前手牌 15 维张数
     * @param toBeat  需要压过的牌, 全零表示自由出牌
     */
    public static List<int[]> legal(int[] hand, int[] toBeat) {
        if (isEmpty(toBeat)) {
            return new Generator(hand).genAll();
        }
        Info rival = detect(toBeat);
        if (rival.type() == MT_ROCKET) {
            List<int[]> only = new ArrayList<>();
            only.add(blank());
            return only;
        }
        Generator gen = new Generator(hand);
        List<int[]> moves = new ArrayList<>();
        if (rival.type() == MT_BOMB) {
            for (int[] m : gen.genByType(MT_BOMB, 0)) {
                if (beats(m, toBeat, MT_BOMB)) {
                    moves.add(m);
                }
            }
        } else {
            for (int[] m : gen.genByType(rival.type(), rival.len())) {
                if (beats(m, toBeat, rival.type())) {
                    moves.add(m);
                }
            }
            for (int[] m : gen.genByType(MT_BOMB, 0)) {
                if (beats(m, toBeat, rival.type())) {
                    moves.add(m);
                }
            }
        }
        moves.add(blank());   // 过牌
        return moves;
    }

    // ---------------------------------------------------------------------------
    // 与引擎 Move 的互转
    // ---------------------------------------------------------------------------

    private static CardType cardTypeOf(int mtType) {
        return switch (mtType) {
            case MT_PASS -> CardType.PASS;
            case MT_SINGLE -> CardType.SINGLE;
            case MT_PAIR -> CardType.PAIR;
            case MT_TRIPLE -> CardType.TRIPLE;
            case MT_BOMB -> CardType.BOMB;
            case MT_ROCKET -> CardType.ROCKET;
            case MT_TRIPLE_ONE -> CardType.TRIPLE_PLUS_ONE;
            case MT_TRIPLE_TWO -> CardType.TRIPLE_PLUS_PAIR;
            case MT_STRAIGHT -> CardType.STRAIGHT;
            case MT_PAIR_STRAIGHT -> CardType.CONSECUTIVE_PAIRS;
            case MT_PLANE -> CardType.AIRPLANE;
            case MT_PLANE_SOLO -> CardType.AIRPLANE_PLUS_SINGLES;
            case MT_PLANE_PAIR -> CardType.AIRPLANE_PLUS_PAIRS;
            case MT_FOUR_TWO -> CardType.FOUR_PLUS_TWO;
            case MT_FOUR_TWO_PAIR -> CardType.FOUR_PLUS_TWO_PAIRS;
            default -> null;
        };
    }

    /** 把多重集转成引擎的 Move; 链牌的 mainRank 用链的最大点数, 与 MoveGenerator 的约定一致 */
    public static Move toMove(int[] counts, int playerId, Info info) {
        if (info.type() == MT_PASS) {
            return Move.pass(playerId);
        }
        CardType type = cardTypeOf(info.type());
        if (type == null) {
            throw new IllegalArgumentException("无法归类的牌: " + PerfectDouFeatures.debugCards(counts));
        }
        int topIndex = info.type() == MT_ROCKET
                ? 14
                : info.rank() + (info.len() > 1 ? info.len() - 1 : 0);
        List<Rank> cards = new ArrayList<>(total(counts));
        for (int r = 0; r < RANKS; ++r) {
            for (int k = 0; k < counts[r]; ++k) {
                cards.add(Rank.fromValue(r + 3));
            }
        }
        return Move.of(type, topIndex + 3, cards, playerId);
    }

    public static Move toMove(int[] counts, int playerId) {
        return toMove(counts, playerId, detect(counts));
    }
}

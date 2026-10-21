package com.doudizhu.nn;

import com.doudizhu.game.PublicView;
import com.doudizhu.model.Move;
import com.doudizhu.model.Rank;

/**
 * PerfectDou 网络输入编码 (移植自 PerfectDouCPP {@code src/ddz/features.cpp})
 *
 * <p>牌索引 0..14 = 3 4 5 6 7 8 9 T J Q K A 2 小王 大王, 即 {@code Rank.getValue() - 3}。
 * 每份牌用一个 12x15 的 0/1 矩阵描述, 展平为 180 个 float, 行主序 {@code row * 15 + rank}。
 *
 * <p>一次决策的输入:
 * <ul>
 *   <li>{@code xImp} 1676 = 9 个当前状态矩阵 (1620) + 56 个标量</li>
 *   <li>{@code seq} 5400 = 最近 30 手动作矩阵, 每 3 手拼成 LSTM 的一个时间步, 共 10 步, 前部补零</li>
 *   <li>每个候选动作 {@code actionFeat} 186 = 该手牌的矩阵 (180) + 6 个动态标量</li>
 * </ul>
 * 所有输入均可由公开信息推出, 不读取任何玩家私有手牌。
 */
public final class PerfectDouFeatures {

    public static final int RANKS = 15;
    public static final int CARD_MATRIX = 12 * RANKS;      // 180
    public static final int STATIC_MATRICES = 9;
    public static final int HISTORY_LEN = 30;
    public static final int LSTM_STEPS = 10;
    public static final int LSTM_IN = 3 * CARD_MATRIX;     // 540
    public static final int NODE_BIN = STATIC_MATRICES * CARD_MATRIX;  // 1620
    public static final int IMP_SCALARS = 20 + 20 + 15 + 1;            // 56
    public static final int NODE_SIZE = NODE_BIN + IMP_SCALARS;        // 1676
    public static final int SEQ_SIZE = HISTORY_LEN * CARD_MATRIX;      // 5400
    public static final int ACTION_SIZE = CARD_MATRIX + 6;             // 186

    private static final int NORMAL_RANKS = 13;
    private static final int MIN_STRAIGHT = 5;
    private static final int MIN_PAIR_CHAIN = 3;
    private static final int MIN_PLANE = 2;
    private static final int[] RANK_VALUES = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30};

    // 矩阵内的行偏移
    private static final int ROW_SOLO = 4;
    private static final int ROW_PAIR = 5;
    private static final int ROW_TRIO = 6;
    private static final int ROW_BOMB = 7;
    private static final int ROW_ROCKET = 8;
    private static final int ROW_SOLO_CHAIN = 9;
    private static final int ROW_PAIR_CHAIN = 10;
    private static final int ROW_TRIO_CHAIN = 11;

    private PerfectDouFeatures() {
    }

    /** 一手牌在 15 个点数上的张数; 过牌为全零 */
    public static int[] countsOf(Move move) {
        int[] c = new int[RANKS];
        if (move == null || move.isPass()) {
            return c;
        }
        for (Rank r : move.getCards()) {
            c[r.getValue() - 3]++;
        }
        return c;
    }

    /** 12x15 牌矩阵, 写入 dst 的 [off, off+180) */
    static void cardMatrix(int[] cnt, float[] dst, int off) {
        java.util.Arrays.fill(dst, off, off + CARD_MATRIX, 0f);
        for (int r = 0; r < RANKS; ++r) {
            for (int k = 0; k < cnt[r]; ++k) {
                dst[off + k * RANKS + r] = 1f;          // 第 k 行: 该点数至少出现 k+1 次
            }
            if (cnt[r] >= 1) dst[off + ROW_SOLO * RANKS + r] = 1f;
            if (cnt[r] >= 2) dst[off + ROW_PAIR * RANKS + r] = 1f;
            if (cnt[r] >= 3) dst[off + ROW_TRIO * RANKS + r] = 1f;
            if (r < NORMAL_RANKS && cnt[r] == 4) dst[off + ROW_BOMB * RANKS + r] = 1f;
        }
        if (cnt[13] != 0 && cnt[14] != 0) {             // 王炸同时点亮两张
            dst[off + ROW_ROCKET * RANKS + 13] = 1f;
            dst[off + ROW_ROCKET * RANKS + 14] = 1f;
        }
        for (int r = 0; r < NORMAL_RANKS; ++r) {
            if (inChain(cnt, r, MIN_STRAIGHT, 1)) dst[off + ROW_SOLO_CHAIN * RANKS + r] = 1f;
            if (inChain(cnt, r, MIN_PAIR_CHAIN, 2)) dst[off + ROW_PAIR_CHAIN * RANKS + r] = 1f;
            if (inChain(cnt, r, MIN_PLANE, 3)) dst[off + ROW_TRIO_CHAIN * RANKS + r] = 1f;
        }
    }

    /** 该点数是否落在一条长度足够的连续链里 (只有 3..A 能成链, 2 与王被点数差自动切断) */
    private static boolean inChain(int[] cnt, int r, int minLen, int need) {
        int lo = r;
        int hi = r;
        while (lo - 1 >= 0 && cnt[lo - 1] >= need && RANK_VALUES[lo] - RANK_VALUES[lo - 1] == 1) {
            --lo;
        }
        while (hi + 1 < NORMAL_RANKS && cnt[hi + 1] >= need
                && RANK_VALUES[hi + 1] - RANK_VALUES[hi] == 1) {
            ++hi;
        }
        return hi - lo + 1 >= minLen;
    }

    /**
     * 一次决策的公开观察, 座位号沿用引擎的 playerId 体系。
     *
     * <p>训练侧用 seat 0/1/2 = 地主/地主下家/地主上家, 这里以
     * {@code (playerId - landlordId + 3) % 3} 还原为对应的 actor 编号。
     */
    public static final class View {
        int seat;
        int prev;
        int nxt;
        int landlordId;
        final int[] myHand = new int[RANKS];
        final int[] others = new int[RANKS];
        final int[] bottom = new int[RANKS];
        final int[][] played = new int[3][RANKS];
        final int[][] lastAction = new int[3][RANKS];
        final int[] handCount = new int[3];
        final int[] history = new int[HISTORY_LEN * RANKS];
        int lastPlayer;
        int bombCount;
        int actorIndex;

        /** 行动者自己的手牌张数 (15 维, 索引 = Rank.getValue() - 3) */
        public int[] myHandCounts() {
            return myHand;
        }

        /** 应使用的 actor 编号: 0=地主, 1=地主下家, 2=地主上家 */
        public int actorIndex() {
            return actorIndex;
        }
    }

    /** 由玩家视角构造观察; 调用时该玩家必须正好是行动者 */
    public static View observe(PublicView view) {
        int seat = view.getViewingPlayerId();
        if (seat != view.getActivePlayerId()) {
            throw new IllegalArgumentException("observe() needs the acting player's view");
        }
        View v = new View();
        v.seat = seat;
        v.prev = (seat + 2) % 3;
        v.nxt = (seat + 1) % 3;
        v.landlordId = view.getLandlordId();
        v.actorIndex = (seat - view.getLandlordId() + 3) % 3;
        v.lastPlayer = view.getLandlordId();

        for (int value = 3; value <= 17; ++value) {
            v.myHand[value - 3] = view.getMyHand().getCount(value);
        }
        for (Rank r : view.getBottomCards()) {
            v.bottom[r.getValue() - 3]++;
        }
        for (int id = 0; id < 3; ++id) {
            v.handCount[id] = view.getCardCount(id);
        }

        // 逐手回放公开历史: 各家已出牌、各家最近一手动作、炸弹数、最后实际出牌者
        java.util.List<Move> moves = view.getMoveHistory();
        int[] lastOf = new int[3 * RANKS];
        for (Move m : moves) {
            int id = m.getPlayerId();
            int[] c = countsOf(m);
            System.arraycopy(c, 0, lastOf, id * RANKS, RANKS);
            if (!m.isPass()) {
                v.lastPlayer = id;
                for (int r = 0; r < RANKS; ++r) {
                    v.played[id][r] += c[r];
                }
                if (m.isBomb() || m.isRocket()) {
                    v.bombCount++;
                }
            }
        }
        for (int id = 0; id < 3; ++id) {
            System.arraycopy(lastOf, id * RANKS, v.lastAction[id], 0, RANKS);
        }

        // 历史矩阵: 取最近 30 手 (含过牌), 按时间顺序放在尾部, 前部补空矩阵
        int hlen = Math.min(HISTORY_LEN, moves.size());
        for (int i = 0; i < hlen; ++i) {
            Move m = moves.get(moves.size() - hlen + i);
            int slot = (HISTORY_LEN - hlen + i) * RANKS;
            int[] c = countsOf(m);
            System.arraycopy(c, 0, v.history, slot, RANKS);
        }

        // 底牌要扣掉地主已经打出的那部分 (按点数取交集)
        for (int r = 0; r < RANKS; ++r) {
            v.bottom[r] -= Math.min(v.bottom[r], v.played[v.landlordId][r]);
        }

        // 两家合计的未知牌池 = 整副牌 - 我的手牌 - 场上已打出的牌
        for (int r = 0; r < RANKS; ++r) {
            int deck = r < NORMAL_RANKS ? 4 : 1;
            int seen = v.myHand[r] + v.played[0][r] + v.played[1][r] + v.played[2][r];
            v.others[r] = deck - seen;
        }
        return v;
    }

    /** 写入 1676 维节点向量; seq 传 null 则跳过 5400 维历史序列 */
    public static void encode(View v, float[] xImp, float[] seq) {
        cardMatrix(v.myHand, xImp, 0 * CARD_MATRIX);
        cardMatrix(v.others, xImp, 1 * CARD_MATRIX);
        cardMatrix(v.played[v.seat], xImp, 2 * CARD_MATRIX);
        cardMatrix(v.played[v.prev], xImp, 3 * CARD_MATRIX);
        cardMatrix(v.played[v.nxt], xImp, 4 * CARD_MATRIX);
        cardMatrix(v.bottom, xImp, 5 * CARD_MATRIX);
        cardMatrix(v.lastAction[v.seat], xImp, 6 * CARD_MATRIX);
        cardMatrix(v.lastAction[v.prev], xImp, 7 * CARD_MATRIX);
        cardMatrix(v.lastAction[v.nxt], xImp, 8 * CARD_MATRIX);

        // 56 个标量 (复用缓冲区时须先清零)
        int s = NODE_BIN;
        java.util.Arrays.fill(xImp, s, s + IMP_SCALARS, 0f);
        oneHot(xImp, s, 20, v.handCount[v.prev] - 1);
        oneHot(xImp, s + 20, 20, v.handCount[v.nxt] - 1);
        oneHot(xImp, s + 40, 15, Math.min(v.bombCount, 14));
        xImp[s + 55] = v.lastPlayer == v.seat ? 1f : 0f;

        if (seq != null) {
            // 30 手历史矩阵顺序铺平, 每 3 手构成一个 LSTM 时间步
            int[] cnt = new int[RANKS];
            for (int k = 0; k < HISTORY_LEN; ++k) {
                System.arraycopy(v.history, k * RANKS, cnt, 0, RANKS);
                cardMatrix(cnt, seq, k * CARD_MATRIX);
            }
        }
    }

    private static void oneHot(float[] dst, int off, int n, int value) {
        if (value >= 0 && value < n) {
            dst[off + value] = 1f;
        }
    }

    /** 候选动作特征: 180 维牌矩阵 + 6 个动态标量, 写入 out 的 [off, off+186) */
    public static void actionFeature(View v, Move move, float[] out, int off) {
        int[] chosen = countsOf(move);
        cardMatrix(chosen, out, off);
        int size = move.isPass() ? 0 : move.getCardCount();

        int e = off + CARD_MATRIX;
        out[e] = move.isBomb() || move.isRocket() ? 1f : 0f;
        out[e + 1] = isLargest(move) ? 1f : 0f;
        out[e + 2] = size > 0 && size == v.handCount[v.nxt] ? 1f : 0f;
        out[e + 3] = size > 0 && size == v.handCount[v.prev] ? 1f : 0f;

        int[] after = new int[RANKS];
        for (int r = 0; r < RANKS; ++r) {
            after[r] = v.myHand[r] - chosen[r];
        }
        out[e + 4] = Oracle.minSteps(after) / 20f;
        out[e + 5] = 1f;   // 有效动作标记, 用于区分全零矩阵的过牌
    }

    /** 同类型中的最大一手 (再没有能压它的同型牌) */
    private static boolean isLargest(Move move) {
        int rankIdx = move.getMainRank() - 3;   // 链牌的 mainRank 已是链的最大点数
        switch (move.getType()) {
            case ROCKET:
                return true;
            case SINGLE:
                return rankIdx == 14;           // 大王
            case BOMB:
            case PAIR:
            case TRIPLE:
            case TRIPLE_PLUS_ONE:
            case TRIPLE_PLUS_PAIR:
            case FOUR_PLUS_TWO:
            case FOUR_PLUS_TWO_PAIRS:
                return rankIdx == 12;           // 2
            case STRAIGHT:
            case CONSECUTIVE_PAIRS:
            case AIRPLANE:
            case AIRPLANE_PLUS_SINGLES:
            case AIRPLANE_PLUS_PAIRS:
                return rankIdx == 11;           // 链尾到 A
            default:
                return false;
        }
    }

    /** 训练侧的牌面写法, 用于日志与测试比对 */
    static String debugCards(int[] counts) {
        char[] chars = {'3', '4', '5', '6', '7', '8', '9', 'T', 'J', 'Q', 'K', 'A', '2', 'B', 'R'};
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < counts.length; ++r) {
            for (int k = 0; k < counts[r]; ++k) {
                sb.append(chars[r]);
            }
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }

    /** 该玩家应使用的 actor 编号: 0=地主, 1=地主下家, 2=地主上家 */
    public static int actorIndex(int playerId, int landlordId) {
        return (playerId - landlordId + 3) % 3;
    }
}

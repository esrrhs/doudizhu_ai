package com.farmer.ai;

import com.farmer.model.CardType;
import com.farmer.model.Hand;
import com.farmer.model.Move;
import com.farmer.model.Rank;

/**
 * 手牌结构启发：减少「拆对留死单、双王空手」这类散牌死局。
 */
public final class HandShape {

    private HandShape() {
    }

    /** 落单张数（该点数恰好 1 张，含王） */
    public static int countSingles(Hand hand) {
        int n = 0;
        for (int v = 3; v <= 17; v++) {
            if (hand.getCount(v) == 1) {
                n++;
            }
        }
        return n;
    }

    /** 非控场落单（点数 &lt; 2），最容易变成死单 */
    public static int countWeakSingles(Hand hand) {
        int n = 0;
        for (int v = 3; v < Rank.TWO.getValue(); v++) {
            if (hand.getCount(v) == 1) {
                n++;
            }
        }
        return n;
    }

    public static boolean hasControl(Hand hand) {
        return hand.getCount(Rank.TWO.getValue()) > 0
                || hand.getCount(Rank.BLACK_JOKER.getValue()) > 0
                || hand.getCount(Rank.RED_JOKER.getValue()) > 0
                || hand.hasRocket();
    }

    /**
     * 出这手是否拆散对子/三张（例如用对子里的一张去压单）。
     */
    public static boolean breaksSet(Hand hand, Move move) {
        if (move == null || move.isPass() || move.isBomb() || move.isRocket()) {
            return false;
        }
        if (move.getType() == CardType.SINGLE) {
            return hand.getCount(move.getMainRank()) >= 2;
        }
        if (move.getType() == CardType.PAIR) {
            return hand.getCount(move.getMainRank()) >= 3;
        }
        // 三带一/二的带牌若拆了对子也算
        if (move.getType() == CardType.TRIPLE_PLUS_ONE || move.getType() == CardType.TRIPLE_PLUS_PAIR) {
            for (Rank r : move.getCards()) {
                if (r.getValue() == move.getMainRank()) {
                    continue;
                }
                int need = (move.getType() == CardType.TRIPLE_PLUS_PAIR) ? 2 : 1;
                if (hand.getCount(r) > need) {
                    // 从更多张里抽走，可能拆炸弹等，偏保守不算 breaksSet
                    continue;
                }
                if (move.getType() == CardType.TRIPLE_PLUS_ONE && hand.getCount(r) >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 出完后弱单数量是否明显变多（制造死手）。
     */
    public static int weakSinglesDelta(Hand hand, Move move) {
        if (move == null || move.isPass()) {
            return 0;
        }
        int before = countWeakSingles(hand);
        Hand after = hand.copy();
        after.play(move);
        return countWeakSingles(after) - before;
    }

    /** 是否已陷入散牌死形：弱单很多且几乎没有结构可出 */
    public static boolean isDeadScattered(Hand hand) {
        int total = hand.getTotalCards();
        if (total <= 2) {
            return false;
        }
        int weak = countWeakSingles(hand);
        return weak >= 4 && weak * 2 >= total;
    }

    /** 出完后对子/三张数量（结构点数种数） */
    public static int countStructuredRanks(Hand hand) {
        int n = 0;
        for (int v = 3; v <= 15; v++) {
            if (hand.getCount(v) >= 2) {
                n++;
            }
        }
        return n;
    }

    /** 这手是否用到 2/王 */
    public static boolean usesControlCards(Move move) {
        if (move == null || move.isPass()) {
            return false;
        }
        for (Rank r : move.getCards()) {
            if (r.getValue() >= Rank.TWO.getValue()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 控场被当带牌/夹心烧掉：牌里有 2/王，但主牌并不是控场（也不是纯炸）。
     * 例如三带二把 2 当对子带走、飞机带 2。
     */
    public static boolean burnsControlAsAccessory(Move move) {
        if (move == null || move.isPass() || move.isBomb() || move.isRocket()) {
            return false;
        }
        if (!usesControlCards(move)) {
            return false;
        }
        return move.getMainRank() < Rank.TWO.getValue();
    }

    /**
     * 这手会制造「死散还握控场」或「烧掉控场只留弱单」——典型 DEAD_HAND 前兆。
     */
    public static boolean createsDeadWithControl(Hand hand, Move move) {
        if (hand == null || move == null || move.isPass()) {
            return false;
        }
        Hand after = hand.copy();
        after.play(move);
        int weak = countWeakSingles(after);
        int structured = countStructuredRanks(after);
        boolean controlLeft = hasControl(after);
        int ctrlUsed = 0;
        for (Rank r : move.getCards()) {
            if (r.getValue() >= Rank.TWO.getValue()) {
                ctrlUsed++;
            }
        }

        // 2/王当带牌烧掉，留下大量弱单
        if (burnsControlAsAccessory(move) && weak >= 3 && after.getTotalCards() >= 4) {
            return true;
        }
        // 一口气打出多张控场（如三张2带对），出完仍握王且弱单成堆
        if (ctrlUsed >= 2 && !move.isBomb() && !move.isRocket()
                && weak >= 4 && controlLeft && after.getTotalCards() >= 5) {
            return true;
        }
        // 出完后已是纯死散且还握着控场
        if (weak >= 4 && structured == 0 && controlLeft && after.getTotalCards() >= 5) {
            return true;
        }
        if (hasControl(hand) && weakSinglesDelta(hand, move) >= 2 && controlLeft && weak >= 4) {
            return true;
        }
        return false;
    }

    /** 跟牌时用控场夺权（同型最小控场可压，或同型安全小牌） */
    public static Move findRescueBeater(java.util.List<Move> legalMoves, Hand hand, Move lastMove) {
        if (lastMove == null || lastMove.isPass() || legalMoves == null || hand == null) {
            return null;
        }
        Move bestSafe = findCheapestSafeBeater(legalMoves, hand, lastMove);
        if (bestSafe != null) {
            return bestSafe;
        }
        Move bestControl = null;
        for (Move m : legalMoves) {
            if (m.isPass() || m.isBomb() || m.isRocket()) {
                continue;
            }
            if (m.getType() != lastMove.getType() || !m.canBeat(lastMove)) {
                continue;
            }
            if (m.getMainRank() < Rank.TWO.getValue()) {
                continue;
            }
            if (burnsControlAsAccessory(m) || createsDeadWithControl(hand, m)) {
                continue;
            }
            if (bestControl == null || m.getMainRank() < bestControl.getMainRank()) {
                bestControl = m;
            }
        }
        return bestControl;
    }

    /**
     * 跟牌时最小的「安全」同型可压着法（不拆对、非炸）。
     */
    public static Move findCheapestSafeBeater(java.util.List<Move> legalMoves, Hand hand, Move lastMove) {
        if (lastMove == null || lastMove.isPass() || legalMoves == null) {
            return null;
        }
        Move best = null;
        for (Move m : legalMoves) {
            if (m.isPass() || m.isBomb() || m.isRocket()) {
                continue;
            }
            if (m.getType() != lastMove.getType()) {
                continue;
            }
            if (!m.canBeat(lastMove) || breaksSet(hand, m)) {
                continue;
            }
            if (best == null || m.getMainRank() < best.getMainRank()) {
                best = m;
            }
        }
        return best;
    }

    /**
     * 主动出牌时优先的不拆结构着法：结构牌 &gt; 小对 &gt; 小落单。
     */
    public static Move findBestSafeLead(java.util.List<Move> legalMoves, Hand hand) {
        if (legalMoves == null || hand == null) {
            return null;
        }
        Move best = null;
        int bestKey = Integer.MAX_VALUE;
        for (Move m : legalMoves) {
            if (m.isPass() || m.isBomb() || m.isRocket() || breaksSet(hand, m)) {
                continue;
            }
            if (createsDeadWithControl(hand, m) || burnsControlAsAccessory(m)) {
                continue;
            }
            int key;
            if (m.getType() == CardType.STRAIGHT
                    || m.getType() == CardType.CONSECUTIVE_PAIRS
                    || m.getType() == CardType.AIRPLANE
                    || m.getType() == CardType.AIRPLANE_PLUS_SINGLES
                    || m.getType() == CardType.AIRPLANE_PLUS_PAIRS
                    || m.getType() == CardType.TRIPLE_PLUS_ONE
                    || m.getType() == CardType.TRIPLE_PLUS_PAIR
                    || m.getType() == CardType.TRIPLE) {
                // 优先带走弱单的结构
                key = m.getMainRank() + weakSinglesDelta(hand, m) * 40;
            } else if (m.getType() == CardType.PAIR) {
                key = 100 + m.getMainRank();
            } else {
                key = 200 + m.getMainRank();
            }
            if (key < bestKey) {
                bestKey = key;
                best = m;
            }
        }
        return best;
    }

    /** 相对安全最小可压，是否属于严重超压（普通大 3 阶；2/王大 2 阶即算） */
    public static boolean isSevereOvershoot(Move chosen, Move cheapestSafe) {
        if (chosen == null || cheapestSafe == null || chosen.isPass()) {
            return false;
        }
        if (chosen.getType() != cheapestSafe.getType()) {
            return false;
        }
        int gap = chosen.getMainRank() - cheapestSafe.getMainRank();
        if (gap <= 0) {
            return false;
        }
        if (chosen.getMainRank() >= Rank.TWO.getValue()) {
            return gap >= 2;
        }
        return gap >= 3;
    }
}

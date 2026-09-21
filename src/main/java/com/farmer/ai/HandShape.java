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
}

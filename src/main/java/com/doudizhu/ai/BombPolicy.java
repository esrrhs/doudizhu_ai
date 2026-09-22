package com.doudizhu.ai;

import com.doudizhu.game.GameState;
import com.doudizhu.game.Player;
import com.doudizhu.game.PublicView;
import com.doudizhu.model.Move;
import com.doudizhu.model.Role;

import java.util.List;

/**
 * 炸弹 / 王炸使用先验：非紧急局面坚决留着控场，避免早炸翻车。
 */
public final class BombPolicy {

    /** 普通炸弹在非紧急时的胜率惩罚 */
    public static final double BOMB_WINRATE_PENALTY = 0.18;
    /** 王炸在非紧急时的胜率惩罚（更重） */
    public static final double ROCKET_WINRATE_PENALTY = 0.28;
    /** 普通炸弹访问量折扣 */
    public static final double BOMB_VISIT_SCALE = 0.25;
    /** 王炸访问量折扣 */
    public static final double ROCKET_VISIT_SCALE = 0.12;

    private BombPolicy() {
    }

    public static boolean isBombOrRocket(Move move) {
        return move != null && (move.isBomb() || move.isRocket());
    }

    /**
     * 基于完全信息状态判断：当前是否属于「值得考虑炸弹」的紧急局面。
     */
    public static boolean isBombUrgent(GameState state) {
        Move lastMove = state.getLastMove();
        Player me = state.getActivePlayer();
        int myCards = me.getCardCount();

        // 主动出牌：有非炸可出时绝不紧急炸
        if (lastMove == null || lastMove.isPass()) {
            return false;
        }

        int lastPlayerId = state.getLastMovePlayerId();
        Player lastPlayer = state.getPlayer(lastPlayerId);

        // 农民绝不炸队友
        if (me.getRole().isTeammateWith(lastPlayer.getRole())) {
            return false;
        }

        int lastPlayerCards = lastPlayer.getCardCount();

        // 对手报单/报双，必须封堵
        if (lastPlayerCards <= 2) {
            return true;
        }
        // 自己牌很少，需要夺权冲刺
        if (myCards <= 3) {
            return true;
        }
        // 场上已是炸弹/王炸，只能硬刚
        if (lastMove.isBomb() || lastMove.isRocket()) {
            return true;
        }
        // 任意对手已进入绝杀威胁区（地主视角尤其重要）
        for (int i = 0; i < 3; i++) {
            if (i == me.getId()) {
                continue;
            }
            Player p = state.getPlayer(i);
            if (p.getRole().isTeammateWith(me.getRole())) {
                continue;
            }
            if (p.getCardCount() <= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 基于不完全信息视角判断紧急度（供最终决策先验使用）。
     */
    public static boolean isBombUrgent(PublicView view) {
        Move lastMove = view.getLastMove();
        int myId = view.getViewingPlayerId();
        int myCards = view.getMyHand().getTotalCards();

        if (lastMove == null || lastMove.isPass()) {
            return false;
        }

        int lastPlayerId = view.getLastMovePlayerId();
        Role myRole = view.getViewingRole();
        boolean lastIsFarmer = (lastPlayerId != view.getLandlordId());

        if (myRole.isFarmer() && lastIsFarmer) {
            return false;
        }

        if (view.getCardCount(lastPlayerId) <= 2) {
            return true;
        }
        if (myCards <= 3) {
            return true;
        }
        if (lastMove.isBomb() || lastMove.isRocket()) {
            return true;
        }

        for (int i = 0; i < 3; i++) {
            if (i == myId) {
                continue;
            }
            boolean isFarmer = (i != view.getLandlordId());
            if (myRole.isFarmer() && isFarmer) {
                continue;
            }
            if (view.getCardCount(i) <= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 候选剪枝：非紧急且仍有普通出法或可过牌时，不把炸弹/王炸放进搜索树。
     * 例外：炸弹本身就是一手清空获胜、或别无选择只能炸。
     */
    public static boolean shouldKeepBombCandidates(GameState state, List<Move> legalMoves) {
        int myCards = state.getActivePlayer().getCardCount();
        for (Move m : legalMoves) {
            if (isBombOrRocket(m) && m.getCardCount() == myCards) {
                return true;
            }
        }

        boolean hasNonBombPlay = false;
        boolean hasPass = false;
        for (Move m : legalMoves) {
            if (m.isPass()) {
                hasPass = true;
            } else if (!isBombOrRocket(m)) {
                hasNonBombPlay = true;
            }
        }

        // 无普通牌可出且不能过 → 被迫炸
        if (!hasNonBombPlay && !hasPass) {
            return true;
        }

        return isBombUrgent(state);
    }

    /**
     * 决策层综合分：非紧急时对炸弹/王炸、以及拆对留死单的着法施加强先验惩罚。
     */
    public static double adjustedScore(Move move, int visits, double winRate, boolean urgent,
                                      boolean hasSafeAlternative) {
        return adjustedScore(move, visits, winRate, urgent, hasSafeAlternative, null);
    }

    public static double adjustedScore(Move move, int visits, double winRate, boolean urgent,
                                      boolean hasSafeAlternative, com.doudizhu.model.Hand hand) {
        return adjustedScore(move, visits, winRate, urgent, hasSafeAlternative, hand, null);
    }

    public static double adjustedScore(Move move, int visits, double winRate, boolean urgent,
                                      boolean hasSafeAlternative, com.doudizhu.model.Hand hand,
                                      Move lastMove) {
        double adjVisits = visits;
        double adjWinRate = winRate;

        if (!urgent && isBombOrRocket(move) && hasSafeAlternative) {
            if (move.isRocket()) {
                adjWinRate -= ROCKET_WINRATE_PENALTY;
                adjVisits *= ROCKET_VISIT_SCALE;
            } else {
                adjWinRate -= BOMB_WINRATE_PENALTY;
                adjVisits *= BOMB_VISIT_SCALE;
            }
        }

        if (hand != null && !urgent && hasSafeAlternative && HandShape.breaksSet(hand, move)) {
            adjWinRate -= 0.12;
            adjVisits *= 0.55;
        }
        if (hand != null && HandShape.weakSinglesDelta(hand, move) > 0 && !urgent) {
            adjWinRate -= 0.06 * HandShape.weakSinglesDelta(hand, move);
        }

        // 抑制 DEAD_HAND：烧掉控场留散牌 / 出完后死散仍握控场
        if (hand != null && !move.isPass()) {
            int weakNow = HandShape.countWeakSingles(hand);
            boolean holdingControl = HandShape.hasControl(hand);
            if (HandShape.burnsControlAsAccessory(move) && !urgent) {
                adjWinRate -= 0.16;
                adjVisits *= 0.4;
            }
            if (HandShape.createsDeadWithControl(hand, move)) {
                adjWinRate -= 0.22;
                adjVisits *= 0.3;
            }
            // 已有较多弱单且握控场：奖励带走弱单的结构，惩罚继续摊散
            if (holdingControl && weakNow >= 3 && !urgent) {
                int delta = HandShape.weakSinglesDelta(hand, move);
                if (delta < 0) {
                    adjWinRate += 0.07 * (-delta);
                } else if (delta > 0) {
                    adjWinRate -= 0.05 * delta;
                }
            }
        }

        // 有更小同型可压时，惩罚大牌超压（有 9 却出 2）
        if (hand != null && lastMove != null && !lastMove.isPass() && !move.isPass()
                && !isBombOrRocket(move) && move.getType() == lastMove.getType()
                && move.getMainRank() > lastMove.getMainRank() + 2) {
            boolean hasCheaper = false;
            for (int v = lastMove.getMainRank() + 1; v < move.getMainRank(); v++) {
                if (move.getType() == com.doudizhu.model.CardType.SINGLE
                        && hand.getCount(v) == 1) {
                    hasCheaper = true;
                    break;
                }
                if (move.getType() == com.doudizhu.model.CardType.PAIR && hand.getCount(v) >= 2) {
                    hasCheaper = true;
                    break;
                }
                if (move.getType() == com.doudizhu.model.CardType.TRIPLE && hand.getCount(v) >= 3) {
                    hasCheaper = true;
                    break;
                }
            }
            if (hasCheaper && !urgent) {
                adjWinRate -= 0.16;
                adjVisits *= 0.45;
            }
        }

        // 主动回合：严禁早出控场、严禁拆对出单
        boolean leading = lastMove == null || lastMove.isPass();
        if (hand != null && leading && !move.isPass() && !urgent) {
            if (move.getType() == com.doudizhu.model.CardType.SINGLE
                    && move.getMainRank() >= com.doudizhu.model.Rank.TWO.getValue()
                    && hand.getTotalCards() > 3) {
                adjWinRate -= 0.18;
                adjVisits *= 0.35;
            }
            if (HandShape.breaksSet(hand, move)) {
                adjWinRate -= 0.16;
                adjVisits *= 0.4;
            }
        }

        return adjVisits + adjWinRate * 80.0;
    }

    public static boolean hasSafeAlternative(List<Move> legalMoves) {
        for (Move m : legalMoves) {
            if (m.isPass() || (!m.isBomb() && !m.isRocket())) {
                return true;
            }
        }
        return false;
    }
}

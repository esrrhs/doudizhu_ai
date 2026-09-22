package com.farmer.ai;

import com.farmer.game.GameState;
import com.farmer.game.Player;
import com.farmer.model.CardType;
import com.farmer.model.Hand;
import com.farmer.model.Move;
import com.farmer.model.Rank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 斗地主高智能快速推演策略 (Rollout Policy)
 * 具备回手牌控场、消灭小单牌、避免拆对留死单、团队协同等专家级博弈行为
 */
public class FastRolloutPolicy {

    public static void simulate(GameState state, Random random) {
        int movesCount = 0;
        int maxMoves = 250;

        while (!state.isGameOver() && movesCount++ < maxMoves) {
            List<Move> legalMoves = state.getLegalMoves();
            if (legalMoves.isEmpty()) {
                break;
            }

            Move chosen = selectRolloutMove(state, legalMoves, random);
            state.applyMove(chosen);
        }
    }

    public static Move selectRolloutMove(GameState state, List<Move> legalMoves, Random random) {
        if (legalMoves.size() == 1) {
            return legalMoves.get(0);
        }

        Move lastMove = state.getLastMove();
        Player activePlayer = state.getActivePlayer();
        Hand myHand = activePlayer.getHand();
        boolean isFarmer = activePlayer.getRole().isFarmer();
        int myCardsCount = activePlayer.getCardCount();

        // 1. 主动出牌 (Lead)
        if (lastMove == null || lastMove.isPass()) {
            for (Move m : legalMoves) {
                if (m.getCardCount() == myCardsCount && !m.isPass()) {
                    return m;
                }
            }

            List<Move> nonBombs = new ArrayList<>();
            List<Move> bombs = new ArrayList<>();
            for (Move m : legalMoves) {
                if (m.isBomb() || m.isRocket()) {
                    bombs.add(m);
                } else if (!m.isPass()) {
                    nonBombs.add(m);
                }
            }

            if (!nonBombs.isEmpty()) {
                List<Move> structures = new ArrayList<>();
                for (Move m : nonBombs) {
                    if (m.getType() == CardType.STRAIGHT
                            || m.getType() == CardType.CONSECUTIVE_PAIRS
                            || m.getType() == CardType.AIRPLANE
                            || m.getType() == CardType.AIRPLANE_PLUS_SINGLES
                            || m.getType() == CardType.AIRPLANE_PLUS_PAIRS
                            || m.getType() == CardType.TRIPLE_PLUS_ONE
                            || m.getType() == CardType.TRIPLE_PLUS_PAIR) {
                        structures.add(m);
                    }
                }
                if (!structures.isEmpty()) {
                    // 优先能带走弱单的结构，再按点数从小到大；禁止烧掉控场留死散
                    structures.sort(Comparator
                            .comparingInt((Move m) -> HandShape.createsDeadWithControl(myHand, m) ? 1 : 0)
                            .thenComparingInt((Move m) -> HandShape.burnsControlAsAccessory(m) ? 1 : 0)
                            .thenComparingInt((Move m) -> HandShape.weakSinglesDelta(myHand, m))
                            .thenComparingInt(Move::getMainRank));
                    Move bestStruct = structures.get(0);
                    if (!HandShape.createsDeadWithControl(myHand, bestStruct)
                            && !HandShape.burnsControlAsAccessory(bestStruct)) {
                        return bestStruct;
                    }
                    // 结构都会造死散则改出对/单，不硬出
                }

                List<Move> trios = nonBombs.stream()
                        .filter(m -> m.getType() == CardType.TRIPLE)
                        .sorted(Comparator.comparingInt(Move::getMainRank))
                        .toList();
                if (!trios.isEmpty()) {
                    return trios.get(0);
                }

                // 先出小对，再出真正的落单；严禁主动拆对出单
                List<Move> pairs = new ArrayList<>();
                List<Move> trueSingles = new ArrayList<>();
                for (Move m : nonBombs) {
                    if (m.getType() == CardType.PAIR && m.getMainRank() < Rank.TWO.getValue()) {
                        pairs.add(m);
                    } else if (m.getType() == CardType.SINGLE
                            && m.getMainRank() < Rank.TWO.getValue()
                            && !HandShape.breaksSet(myHand, m)) {
                        trueSingles.add(m);
                    }
                }
                pairs.sort(Comparator.comparingInt(Move::getMainRank));
                trueSingles.sort(Comparator.comparingInt(Move::getMainRank));
                if (!pairs.isEmpty()) {
                    return pairs.get(0);
                }
                if (!trueSingles.isEmpty()) {
                    return trueSingles.get(0);
                }

                nonBombs.sort(Comparator
                        .comparingInt((Move m) -> HandShape.breaksSet(myHand, m) ? 1 : 0)
                        .thenComparingInt(Move::getMainRank));
                return nonBombs.get(0);
            }
            bombs.sort(Comparator.comparingInt(Move::getMainRank));
            return bombs.get(0);
        }

        // 2. 被动应牌
        Move passMove = legalMoves.stream().filter(Move::isPass).findFirst().orElse(null);
        int lastPlayerCards = state.getPlayer(state.getLastMovePlayerId()).getCardCount();

        if (isFarmer) {
            Player lastPlayer = state.getPlayer(state.getLastMovePlayerId());
            if (lastPlayer.getRole().isFarmer() && passMove != null) {
                return passMove;
            }
        }

        List<Move> normalBeaters = new ArrayList<>();
        List<Move> bombs = new ArrayList<>();
        for (Move m : legalMoves) {
            if (m.isBomb() || m.isRocket()) {
                bombs.add(m);
            } else if (!m.isPass()) {
                normalBeaters.add(m);
            }
        }

        if (!normalBeaters.isEmpty()) {
            // 优先用落单去压，避免拆对；再选最小点
            normalBeaters.sort(Comparator
                    .comparingInt((Move m) -> HandShape.breaksSet(myHand, m) ? 1 : 0)
                    .thenComparingInt((Move m) -> HandShape.weakSinglesDelta(myHand, m))
                    .thenComparingInt(Move::getMainRank));
            Move bestBeater = normalBeaters.get(0);

            // 若存在不拆对的更小同型可压，禁止用控场牌/拆对去超压
            Move cheapSafe = null;
            for (Move m : normalBeaters) {
                if (!HandShape.breaksSet(myHand, m) && m.getType() == lastMove.getType()) {
                    cheapSafe = m;
                    break; // 已按点数排序
                }
            }
            if (cheapSafe != null && HandShape.isSevereOvershoot(bestBeater, cheapSafe)) {
                bestBeater = cheapSafe;
            } else if (cheapSafe != null && HandShape.breaksSet(myHand, bestBeater)
                    && !HandShape.breaksSet(myHand, cheapSafe)) {
                bestBeater = cheapSafe;
            }

            // 非紧急：烧掉控场造死散 → 过牌
            if (passMove != null && myCardsCount >= 4 && lastPlayerCards > 3
                    && (HandShape.burnsControlAsAccessory(bestBeater)
                    || HandShape.createsDeadWithControl(myHand, bestBeater))) {
                return passMove;
            }

            boolean breaks = HandShape.breaksSet(myHand, bestBeater);
            if (bestBeater.getMainRank() >= Rank.TWO.getValue() || breaks) {
                boolean isUrgent = (lastPlayerCards <= 3)
                        || (myCardsCount <= 2)
                        || (lastMove.getMainRank() >= Rank.KING.getValue())
                        || HandShape.isDeadScattered(myHand);

                // 拆对压中小牌 / 用控场牌压中小牌：非紧急则过牌
                if (!isUrgent && passMove != null && myCardsCount >= 4 && lastMove.getMainRank() <= 10) {
                    if (breaks || bestBeater.getMainRank() >= Rank.TWO.getValue()) {
                        if (random.nextDouble() < (breaks ? 0.85 : 0.75)) {
                            return passMove;
                        }
                    }
                }
            }

            return bestBeater;
        }

        if (!bombs.isEmpty()) {
            boolean urgent = BombPolicy.isBombUrgent(state)
                    || passMove == null
                    || HandShape.isDeadScattered(myHand);
            if (urgent) {
                bombs.sort(Comparator.comparingInt(Move::getMainRank));
                for (Move b : bombs) {
                    if (b.isBomb() && !b.isRocket()) {
                        return b;
                    }
                }
                return bombs.get(0);
            }
            if (passMove == null && random.nextDouble() < 0.02) {
                bombs.sort(Comparator.comparingInt(Move::getMainRank));
                return bombs.get(0);
            }
        }

        return (passMove != null) ? passMove : legalMoves.get(0);
    }
}

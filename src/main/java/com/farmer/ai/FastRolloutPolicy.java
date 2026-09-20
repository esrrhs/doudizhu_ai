package com.farmer.ai;

import com.farmer.game.GameState;
import com.farmer.model.Move;
import com.farmer.game.Player;
import com.farmer.model.Role;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 斗地主快速推演策略 (Rollout Policy)
 * 考虑 2v1 阵营对抗：农民互不压制/配合送牌，针对地主积极拦截
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

    private static Move selectRolloutMove(GameState state, List<Move> legalMoves, Random random) {
        if (legalMoves.size() == 1) {
            return legalMoves.get(0);
        }

        Move lastMove = state.getLastMove();
        Player activePlayer = state.getActivePlayer();
        boolean isFarmer = activePlayer.getRole().isFarmer();

        if (lastMove == null || lastMove.isPass()) {
            // 主动出牌：优先多张组合、优先出小牌、避免开局直接甩炸弹/王炸
            List<Move> nonBombs = new ArrayList<>();
            List<Move> bombs = new ArrayList<>();
            for (Move m : legalMoves) {
                if (m.isBomb() || m.isRocket()) {
                    bombs.add(m);
                } else {
                    nonBombs.add(m);
                }
            }

            if (!nonBombs.isEmpty()) {
                nonBombs.sort(Comparator.comparingInt(Move::getMainRank));
                for (Move m : nonBombs) {
                    if (m.getCardCount() >= 2) {
                        return m;
                    }
                }
                return nonBombs.get(0);
            } else {
                return bombs.get(0);
            }
        } else {
            // 被动应牌
            Move passMove = legalMoves.stream().filter(Move::isPass).findFirst().orElse(null);

            // 队友协同：如果上家出牌者是农民队友
            if (isFarmer) {
                Player lastPlayer = state.getPlayer(state.getLastMovePlayerId());
                if (lastPlayer.getRole().isFarmer()) {
                    // 队友出了大牌 (>=10) 或队友牌很少时，主动让牌过牌 (PASS)
                    if (lastMove.getMainRank() >= 10 || lastPlayer.getCardCount() <= 2) {
                        if (passMove != null) {
                            return passMove;
                        }
                    }
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
                normalBeaters.sort(Comparator.comparingInt(Move::getMainRank));
                return normalBeaters.get(0); // 最小压牌
            }

            if (!bombs.isEmpty()) {
                int lastPlayerCards = state.getPlayer(state.getLastMovePlayerId()).getCardCount();
                // 当对手手牌很少时（<=3张）积极放炸弹拦截
                if (lastPlayerCards <= 3 || passMove == null || random.nextDouble() < 0.2) {
                    bombs.sort(Comparator.comparingInt(Move::getMainRank));
                    return bombs.get(0);
                }
            }

            return (passMove != null) ? passMove : legalMoves.get(0);
        }
    }
}

package com.farmer.ai;

import com.farmer.game.GameState;
import com.farmer.game.Player;
import com.farmer.model.CardType;
import com.farmer.model.Move;
import com.farmer.model.Rank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 斗地主高智能快速推演策略 (Rollout Policy)
 * 具备回手牌控场、消灭小单牌、避免大牌早耗、团队协同等专家级博弈行为
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
        boolean isFarmer = activePlayer.getRole().isFarmer();
        int myCardsCount = activePlayer.getCardCount();

        // 1. 主动出牌 (Lead - 桌面清空，自由选牌)
        if (lastMove == null || lastMove.isPass()) {
            // A. 如果手牌只剩最后一手且能一次性走完，直接打出获胜！
            for (Move m : legalMoves) {
                if (m.getCardCount() == myCardsCount && !m.isPass()) {
                    return m;
                }
            }

            // 分离非炸弹与炸弹
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
                // B. 优先出长结构组合牌 (顺子、连对、飞机、三带一、三带二)，一口气消耗多张牌并带走散单牌
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
                    structures.sort(Comparator.comparingInt(Move::getMainRank));
                    return structures.get(0);
                }

                // C. 三张不带牌
                List<Move> trios = nonBombs.stream()
                        .filter(m -> m.getType() == CardType.TRIPLE)
                        .sorted(Comparator.comparingInt(Move::getMainRank))
                        .toList();
                if (!trios.isEmpty()) {
                    return trios.get(0);
                }

                // D. 普通单牌与对子：
                // 关键策略：保留大牌 (2、大王、小王) 控场，优先打出手中点数最低的小牌 (散单、小对)，
                // 避免在终局落入“大牌耗尽、只剩死单牌”的绝境！
                nonBombs.sort(Comparator.comparingInt(Move::getMainRank));

                // 筛选出非控制牌 (Rank <= A，即 <= 14) 的低点数动作
                List<Move> normalRanks = nonBombs.stream()
                        .filter(m -> m.getMainRank() < Rank.TWO.getValue())
                        .toList();

                if (!normalRanks.isEmpty()) {
                    // 如果手里有散小单牌 (<= 9)，积极主动引出，利用手里的 2/王 等回手牌随时收回出牌权
                    return normalRanks.get(0);
                } else {
                    // 全是大牌 (2、王) 了，从小到大打出
                    return nonBombs.get(0);
                }
            } else {
                // 只剩炸弹了，从小炸弹打起
                bombs.sort(Comparator.comparingInt(Move::getMainRank));
                return bombs.get(0);
            }
        }

        // 2. 被动应牌 (Follow - 桌面有上家牌需压制)
        Move passMove = legalMoves.stream().filter(Move::isPass).findFirst().orElse(null);
        int lastPlayerCards = state.getPlayer(state.getLastMovePlayerId()).getCardCount();

        // A. 队友协同：如果是农民，且上家出牌者也是农民队友
        if (isFarmer) {
            Player lastPlayer = state.getPlayer(state.getLastMovePlayerId());
            if (lastPlayer.getRole().isFarmer()) {
                // 队友出了较大牌 (>= 10) 或队友牌很少 (<= 3 张) 时，坚决让牌过牌 (PASS)
                if (lastMove.getMainRank() >= 10 || lastPlayer.getCardCount() <= 3) {
                    if (passMove != null) {
                        return passMove;
                    }
                }
            }
        }

        // 分离普通压牌与炸弹
        List<Move> normalBeaters = new ArrayList<>();
        List<Move> bombs = new ArrayList<>();

        for (Move m : legalMoves) {
            if (m.isBomb() || m.isRocket()) {
                bombs.add(m);
            } else if (!m.isPass()) {
                normalBeaters.add(m);
            }
        }

        // B. 普通合法压制牌
        if (!normalBeaters.isEmpty()) {
            normalBeaters.sort(Comparator.comparingInt(Move::getMainRank));
            Move minBeater = normalBeaters.get(0);

            // 核心策略：防范“大牌早耗”陷阱！
            // 如果最小可压牌是顶级控制牌 (2、小王、大王)：
            if (minBeater.getMainRank() >= Rank.TWO.getValue()) {
                // 判断局势是否紧急：
                boolean isUrgent = (lastPlayerCards <= 3) // 对手报单/双，必须封堵
                        || (myCardsCount <= 2) // 自己马上走完
                        || (lastMove.getMainRank() >= Rank.KING.getValue()); // 对手出的本来就是大牌(K, A)

                // 如果对手牌还很多 (>= 5 张)，且对手出的只是中小牌 (<= 10)，且自身手牌较多 (>= 4 张)：
                // 此时用 2 或 王 强行压牌属于极大浪费，应优先选择 PASS 保存控制力！
                if (!isUrgent && passMove != null && myCardsCount >= 4 && lastMove.getMainRank() <= 10) {
                    if (random.nextDouble() < 0.75) {
                        return passMove;
                    }
                }
            }

            return minBeater;
        }

        // C. 炸弹使用决策
        if (!bombs.isEmpty()) {
            // 只有在对手手牌极少 (<= 3 张) 形成绝杀威胁，或者自己即将出完时才果断炸
            if (lastPlayerCards <= 3 || myCardsCount <= 4 || passMove == null || random.nextDouble() < 0.15) {
                bombs.sort(Comparator.comparingInt(Move::getMainRank));
                return bombs.get(0);
            }
        }

        // D. 默认过牌
        return (passMove != null) ? passMove : legalMoves.get(0);
    }
}

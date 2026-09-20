package com.farmer.ai;

import com.farmer.game.GameState;
import com.farmer.model.CardType;
import com.farmer.model.Move;
import com.farmer.model.Rank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 斗地主 MCTS 搜索器 (支持动作候选剪枝聚焦与深层发散推演)
 */
public class MctsSearcher {
    private final double explorationParam;
    private final Random random;

    public MctsSearcher(double explorationParam, Random random) {
        this.explorationParam = explorationParam;
        this.random = random;
    }

    public MctsSearcher() {
        this(Math.sqrt(2.0), new Random());
    }

    public MctsNode search(GameState state, int iterations) {
        List<Move> rootLegalMoves = state.getLegalMoves();
        List<Move> prunedRootMoves = pruneCandidateMoves(rootLegalMoves, state.getActivePlayer().getCardCount(), 15);
        MctsNode root = new MctsNode(null, -1, state.getActivePlayerIndex(), null, prunedRootMoves);

        if (prunedRootMoves.size() <= 1) {
            return root;
        }

        for (int i = 0; i < iterations; i++) {
            GameState simState = state.copy();
            MctsNode node = root;

            // 1. Selection
            while (node.isFullyExpanded() && !node.getChildren().isEmpty() && !simState.isGameOver()) {
                node = node.selectChild(explorationParam, random);
                simState.applyMove(node.getMove());
            }

            // 2. Expansion (深层扩展)
            if (!simState.isGameOver() && !node.getUntriedMoves().isEmpty()) {
                Move untriedMove = node.getUntriedMoves().get(random.nextInt(node.getUntriedMoves().size()));
                simState.applyMove(untriedMove);
                List<Move> nextLegalMoves = pruneCandidateMoves(simState.getLegalMoves(), simState.getActivePlayer().getCardCount(), 10);
                node = node.expand(untriedMove, simState.getActivePlayerIndex(), nextLegalMoves);
            }

            // 3. Simulation (Rollout)
            if (!simState.isGameOver()) {
                FastRolloutPolicy.simulate(simState, random);
            }

            // 4. Backpropagation
            node.backpropagate(simState);
        }

        return root;
    }

    /**
     * 启发式动作候选过滤：从繁杂的合法动作池中提炼出最有价值的 Top 候选动作
     * 使 MCTS 树得以迅速向下深层发散展开 (达到 4~6 层深度)，避免算力被浅层均摊浪费
     */
    public static List<Move> pruneCandidateMoves(List<Move> moves, int handCardCount, int maxCandidates) {
        if (moves.size() <= maxCandidates) {
            return moves;
        }

        List<Move> selected = new ArrayList<>(maxCandidates);

        // 1. 终局一手清空动作 (绝对最高优先级)
        for (Move m : moves) {
            if (!m.isPass() && m.getCardCount() == handCardCount) {
                selected.add(m);
            }
        }

        // 2. PASS (被动应牌时的关键避让选择)
        for (Move m : moves) {
            if (m.isPass()) {
                selected.add(m);
                break;
            }
        }

        // 3. 结构牌 (顺子、连对、飞机、三带一/二，快速出多张牌)
        List<Move> combos = new ArrayList<>();
        for (Move m : moves) {
            if (m.getType() == CardType.STRAIGHT
                    || m.getType() == CardType.CONSECUTIVE_PAIRS
                    || m.getType() == CardType.AIRPLANE
                    || m.getType() == CardType.AIRPLANE_PLUS_SINGLES
                    || m.getType() == CardType.AIRPLANE_PLUS_PAIRS
                    || m.getType() == CardType.TRIPLE_PLUS_ONE
                    || m.getType() == CardType.TRIPLE_PLUS_PAIR) {
                combos.add(m);
            }
        }
        combos.sort(Comparator.comparingInt(Move::getMainRank));
        int takeCombos = Math.min(4, combos.size());
        for (int i = 0; i < takeCombos; i++) {
            if (!selected.contains(combos.get(i))) selected.add(combos.get(i));
        }

        // 4. 普通非炸弹单/对/三张牌 (优先挑选低点数散牌，先出小牌)
        List<Move> normals = new ArrayList<>();
        for (Move m : moves) {
            if (!m.isBomb() && !m.isRocket() && !m.isPass() && !selected.contains(m)) {
                normals.add(m);
            }
        }
        normals.sort(Comparator.comparingInt(Move::getMainRank));
        int takeNormals = Math.min(6, normals.size());
        for (int i = 0; i < takeNormals; i++) {
            selected.add(normals.get(i));
        }

        // 5. 炸弹 / 王炸 (保留 1-2 个最小炸弹用于反制)
        List<Move> bombs = new ArrayList<>();
        for (Move m : moves) {
            if ((m.isBomb() || m.isRocket()) && !selected.contains(m)) {
                bombs.add(m);
            }
        }
        bombs.sort(Comparator.comparingInt(Move::getMainRank));
        int takeBombs = Math.min(2, bombs.size());
        for (int i = 0; i < takeBombs; i++) {
            selected.add(bombs.get(i));
        }

        return selected.isEmpty() ? moves : selected;
    }
}

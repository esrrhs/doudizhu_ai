package com.farmer.ai;

import com.farmer.game.GameState;
import com.farmer.model.Move;

import java.util.List;
import java.util.Random;

/**
 * 斗地主 MCTS 搜索器
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
        MctsNode root = new MctsNode(null, -1, state.getActivePlayerIndex(), null, rootLegalMoves);

        if (rootLegalMoves.size() <= 1) {
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

            // 2. Expansion
            if (!simState.isGameOver() && !node.getUntriedMoves().isEmpty()) {
                Move untriedMove = node.getUntriedMoves().get(random.nextInt(node.getUntriedMoves().size()));
                simState.applyMove(untriedMove);
                node = node.expand(untriedMove, simState.getActivePlayerIndex(), simState.getLegalMoves());
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
}

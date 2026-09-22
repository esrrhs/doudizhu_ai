package com.doudizhu.ai;

import com.doudizhu.game.GameState;
import com.doudizhu.model.Move;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 斗地主蒙特卡洛树节点 (支持 2v1 阵营协同估值)
 */
public class MctsNode {
    private final Move move;
    private final int actingPlayerId;
    private final int nextPlayerId;
    private final MctsNode parent;
    private final Map<Move, MctsNode> children = new LinkedHashMap<>();
    private final List<Move> untriedMoves;
    private int visits = 0;
    private final double[] totalWins = new double[3]; // 三位玩家的累积胜率 (农民两家同赢同输)

    public MctsNode(Move move, int actingPlayerId, int nextPlayerId, MctsNode parent, List<Move> legalMoves) {
        this.move = move;
        this.actingPlayerId = actingPlayerId;
        this.nextPlayerId = nextPlayerId;
        this.parent = parent;
        this.untriedMoves = new ArrayList<>(legalMoves);
    }

    public boolean isFullyExpanded() {
        return untriedMoves.isEmpty();
    }

    public boolean isTerminal() {
        return untriedMoves.isEmpty() && children.isEmpty();
    }

    public MctsNode selectChild(double explorationParam, Random random) {
        MctsNode bestChild = null;
        double bestValue = Double.NEGATIVE_INFINITY;

        double logParentVisits = Math.log(Math.max(1, this.visits));

        for (MctsNode child : children.values()) {
            if (child.visits == 0) {
                return child;
            }

            // 当前行动玩家（或所属阵营）的胜率
            double exploitation = child.totalWins[nextPlayerId] / child.visits;
            double exploration = explorationParam * Math.sqrt(logParentVisits / child.visits);
            double ucbValue = exploitation + exploration + (random.nextDouble() * 1e-6);

            if (ucbValue > bestValue) {
                bestValue = ucbValue;
                bestChild = child;
            }
        }

        return bestChild;
    }

    public MctsNode expand(Move move, int newNextPlayerId, List<Move> newLegalMoves) {
        untriedMoves.remove(move);
        MctsNode child = new MctsNode(move, nextPlayerId, newNextPlayerId, this, newLegalMoves);
        children.put(move, child);
        return child;
    }

    /**
     * 阵营胜负反向传播
     */
    public void backpropagate(GameState terminalState) {
        boolean[] won = new boolean[3];
        for (int i = 0; i < 3; i++) {
            won[i] = terminalState.isPlayerWinner(i);
        }

        MctsNode current = this;
        while (current != null) {
            current.visits++;
            for (int i = 0; i < 3; i++) {
                if (won[i]) {
                    current.totalWins[i] += 1.0;
                }
            }
            current = current.parent;
        }
    }

    public Move getMove() {
        return move;
    }

    public int getActingPlayerId() {
        return actingPlayerId;
    }

    public int getNextPlayerId() {
        return nextPlayerId;
    }

    public MctsNode getParent() {
        return parent;
    }

    public Map<Move, MctsNode> getChildren() {
        return children;
    }

    public List<Move> getUntriedMoves() {
        return untriedMoves;
    }

    public int getVisits() {
        return visits;
    }

    public double[] getTotalWins() {
        return totalWins;
    }

    public double getWinRate(int playerId) {
        return visits == 0 ? 0.0 : totalWins[playerId] / visits;
    }
}

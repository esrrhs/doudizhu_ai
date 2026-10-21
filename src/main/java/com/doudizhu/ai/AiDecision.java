package com.doudizhu.ai;

import com.doudizhu.model.Move;

import java.util.List;

/**
 * AI 一次出牌决策的产出：选定的动作、全部候选动作的策略概率、耗时。
 */
public final class AiDecision {
    private final Move selectedMove;
    private final List<MoveScore> scores;
    private final long durationMillis;

    public AiDecision(Move selectedMove, List<MoveScore> scores, long durationMillis) {
        this.selectedMove = selectedMove;
        this.scores = List.copyOf(scores);
        this.durationMillis = durationMillis;
    }

    /** 只有一个合法动作等无需比较的场景 */
    public static AiDecision of(Move move, long durationMillis) {
        return new AiDecision(move, List.of(new MoveScore(move, 1.0)), durationMillis);
    }

    public Move getSelectedMove() {
        return selectedMove;
    }

    /** 按策略概率从高到低排列 */
    public List<MoveScore> getScores() {
        return scores;
    }

    public long getDurationMillis() {
        return durationMillis;
    }
}

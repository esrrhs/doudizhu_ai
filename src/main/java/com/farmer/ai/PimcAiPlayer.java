package com.farmer.ai;

import com.farmer.game.GameState;
import com.farmer.game.PublicView;
import com.farmer.model.Move;
import com.farmer.rules.MoveGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 斗地主 PIMC AI 引擎
 */
public class PimcAiPlayer {
    private final int numDeterminizations;
    private final int mctsIterationsPerWorld;
    private final Random random;
    private final MctsSearcher searcher;

    public PimcAiPlayer(int numDeterminizations, int mctsIterationsPerWorld, Random random) {
        this.numDeterminizations = numDeterminizations;
        this.mctsIterationsPerWorld = mctsIterationsPerWorld;
        this.random = random;
        this.searcher = new MctsSearcher(Math.sqrt(2.0), random);
    }

    public PimcAiPlayer(int numDeterminizations, int mctsIterationsPerWorld) {
        this(numDeterminizations, mctsIterationsPerWorld, new Random());
    }

    public PimcAiPlayer() {
        this(20, 150);
    }

    public static class MoveEvaluation {
        private final Move move;
        private int totalVisits = 0;
        private double sumWinRate = 0.0;
        private int sampleCount = 0;

        public MoveEvaluation(Move move) {
            this.move = move;
        }

        public void record(int visits, double winRate) {
            this.totalVisits += visits;
            this.sumWinRate += winRate;
            this.sampleCount++;
        }

        public Move getMove() {
            return move;
        }

        public int getTotalVisits() {
            return totalVisits;
        }

        public double getAverageWinRate() {
            return sampleCount == 0 ? 0.0 : sumWinRate / sampleCount;
        }

        @Override
        public String toString() {
            return String.format("%s -> 总访问: %4d, 阵营胜率: %5.1f%% (采样: %2d次)",
                    move.toCardString(), totalVisits, getAverageWinRate() * 100.0, sampleCount);
        }
    }

    public static class DecisionResult {
        private final Move selectedMove;
        private final List<MoveEvaluation> evaluations;
        private final long durationMillis;

        public DecisionResult(Move selectedMove, List<MoveEvaluation> evaluations, long durationMillis) {
            this.selectedMove = selectedMove;
            this.evaluations = evaluations;
            this.durationMillis = durationMillis;
        }

        public Move getSelectedMove() {
            return selectedMove;
        }

        public List<MoveEvaluation> getEvaluations() {
            return evaluations;
        }

        public long getDurationMillis() {
            return durationMillis;
        }
    }

    public DecisionResult decide(PublicView publicView) {
        long startTime = System.currentTimeMillis();
        int myId = publicView.getViewingPlayerId();

        List<Move> legalMoves = MoveGenerator.generateLegalMoves(
                publicView.getMyHand(),
                publicView.getLastMove(),
                myId
        );

        if (legalMoves.isEmpty()) {
            return new DecisionResult(Move.pass(myId), List.of(), 0);
        }

        if (legalMoves.size() == 1) {
            Move singleOption = legalMoves.get(0);
            MoveEvaluation eval = new MoveEvaluation(singleOption);

            if (!singleOption.isPass() && singleOption.getCardCount() == publicView.getMyHand().getTotalCards()) {
                // 确实是直接出完手牌获得完全胜利的动作
                eval.record(1, 1.0);
            } else {
                // 被迫过牌或只有唯一应牌动作：通过极速模拟推演其真实的胜率预估，避免虚假报出 100% 胜率
                int wins = 0;
                int sampleK = 15;
                for (int i = 0; i < sampleK; i++) {
                    GameState world = Determinizer.determinize(publicView, random);
                    world.applyMove(singleOption);
                    FastRolloutPolicy.simulate(world, random);
                    if (world.isPlayerWinner(myId)) {
                        wins++;
                    }
                }
                eval.record(sampleK, (double) wins / sampleK);
            }
            return new DecisionResult(singleOption, List.of(eval), System.currentTimeMillis() - startTime);
        }

        Map<Move, MoveEvaluation> evalMap = new LinkedHashMap<>();
        for (Move m : legalMoves) {
            evalMap.put(m, new MoveEvaluation(m));
        }

        // 多可能世界采样
        for (int k = 0; k < numDeterminizations; k++) {
            GameState world = Determinizer.determinize(publicView, random);
            MctsNode root = searcher.search(world, mctsIterationsPerWorld);

            for (Map.Entry<Move, MctsNode> entry : root.getChildren().entrySet()) {
                Move move = entry.getKey();
                MctsNode child = entry.getValue();

                MoveEvaluation eval = evalMap.get(move);
                if (eval != null) {
                    eval.record(child.getVisits(), child.getWinRate(myId));
                }
            }
        }

        List<MoveEvaluation> evalList = new ArrayList<>(evalMap.values());
        evalList.sort(Comparator.comparingInt(MoveEvaluation::getTotalVisits).reversed()
                .thenComparing(Comparator.comparingDouble(MoveEvaluation::getAverageWinRate).reversed()));

        Move bestMove = evalList.get(0).getMove();
        long duration = System.currentTimeMillis() - startTime;

        return new DecisionResult(bestMove, evalList, duration);
    }
}

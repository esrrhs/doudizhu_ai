package com.doudizhu.ai;

import com.doudizhu.game.GameState;
import com.doudizhu.game.PublicView;
import com.doudizhu.model.Move;
import com.doudizhu.rules.MoveGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

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
        // 网页端默认：更多假想世界 + 更深 MCTS（2 核机器上单步会稍慢）
        this(240, 1600);
    }

    public static class MoveEvaluation {
        private final Move move;
        private int totalVisits = 0;
        private double sumWinRate = 0.0;
        private int sampleCount = 0;

        public MoveEvaluation(Move move) {
            this.move = move;
        }

        public synchronized void record(int visits, double winRate) {
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

        Move lastMoveEarly = publicView.getLastMove();
        // 农民跟牌：有过则绝不压队友（含炸/王炸）
        if (publicView.getViewingRole().isFarmer()
                && lastMoveEarly != null && !lastMoveEarly.isPass()
                && publicView.getLastMovePlayerId() != publicView.getLandlordId()) {
            for (Move m : legalMoves) {
                if (m.isPass()) {
                    MoveEvaluation eval = new MoveEvaluation(m);
                    eval.record(1, 0.5);
                    return new DecisionResult(m, List.of(eval), System.currentTimeMillis() - startTime);
                }
            }
        }

        // 0. 若手中存在可直接清空手牌获胜的合法走法，直接执行必胜斩杀
        for (Move m : legalMoves) {
            if (!m.isPass() && m.getCardCount() == publicView.getMyHand().getTotalCards()) {
                MoveEvaluation eval = new MoveEvaluation(m);
                eval.record(100, 1.0);
                return new DecisionResult(m, List.of(eval), System.currentTimeMillis() - startTime);
            }
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

        int worldCount = numDeterminizations;
        Determinizer.SampledWorld[] worlds = new Determinizer.SampledWorld[worldCount];
        IntStream.range(0, worldCount).parallel().forEach(k ->
                worlds[k] = Determinizer.sample(publicView, ThreadLocalRandom.current()));

        double[] likelihoods = new double[worldCount];
        for (int i = 0; i < worldCount; i++) {
            likelihoods[i] = worlds[i].likelihood();
        }
        int[] iterations = PassInference.allocateSearchIterations(likelihoods, mctsIterationsPerWorld);
        Integer[] order = new Integer[worldCount];
        for (int i = 0; i < worldCount; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(likelihoods[b], likelihoods[a]));

        // 高概率世界排前面、分到更多迭代；低概率世界少算，但不丢弃
        java.util.Arrays.stream(order).parallel().forEach(k -> {
            Random workerRandom = ThreadLocalRandom.current();
            MctsSearcher workerSearcher = new MctsSearcher(searcher.getExplorationParam(), workerRandom);
            MctsNode root = workerSearcher.search(worlds[k].state(), iterations[k]);

            for (Map.Entry<Move, MctsNode> entry : root.getChildren().entrySet()) {
                Move move = entry.getKey();
                MctsNode child = entry.getValue();
                MoveEvaluation eval = evalMap.get(move);
                if (eval != null) {
                    eval.record(child.getVisits(), child.getWinRate(myId));
                }
            }
        });

        List<MoveEvaluation> evalList = new ArrayList<>(evalMap.values());
        boolean urgent = BombPolicy.isBombUrgent(publicView)
                || HandShape.isDeadScattered(publicView.getMyHand());
        boolean hasSafeAlternative = BombPolicy.hasSafeAlternative(legalMoves);
        var myHand = publicView.getMyHand();
        Move lastMove = publicView.getLastMove();
        evalList.sort((a, b) -> {
            double scoreA = BombPolicy.adjustedScore(
                    a.getMove(), a.getTotalVisits(), a.getAverageWinRate(), urgent, hasSafeAlternative, myHand, lastMove);
            double scoreB = BombPolicy.adjustedScore(
                    b.getMove(), b.getTotalVisits(), b.getAverageWinRate(), urgent, hasSafeAlternative, myHand, lastMove);
            int cmp = Double.compare(scoreB, scoreA);
            if (cmp != 0) {
                return cmp;
            }
            return Double.compare(b.getAverageWinRate(), a.getAverageWinRate());
        });

        // urgent 只用于软先验（炸/拆对惩罚）；硬护栏不因「散牌/对方报双」整段关闭
        boolean bombUrgent = BombPolicy.isBombUrgent(publicView);
        boolean vsOpponent = isVsOpponent(publicView);
        Move bestMove = selectWithHardGuards(evalList, legalMoves, myHand, lastMove, bombUrgent, vsOpponent);
        long duration = System.currentTimeMillis() - startTime;

        return new DecisionResult(bestMove, evalList, duration);
    }

    private static boolean isVsOpponent(com.doudizhu.game.PublicView view) {
        Move last = view.getLastMove();
        if (last == null || last.isPass()) {
            return false;
        }
        int lastId = view.getLastMovePlayerId();
        boolean meFarmer = view.getViewingRole().isFarmer();
        boolean lastFarmer = lastId != view.getLandlordId();
        return meFarmer != lastFarmer;
    }

    /**
     * 硬护栏：有安全小牌可压时禁止超压/拆对；主动时禁止拆对出单、早出控场；
     * 非紧急禁止炸/王炸；禁止烧掉控场制造死散；死散握控场时对敌夺权。
     */
    static Move selectWithHardGuards(List<MoveEvaluation> evalList, List<Move> legalMoves,
                                     com.doudizhu.model.Hand myHand, Move lastMove,
                                     boolean bombUrgent, boolean vsOpponent) {
        Move top = evalList.get(0).getMove();
        if (myHand == null) {
            return top;
        }

        Move passMove = null;
        for (Move m : legalMoves) {
            if (m.isPass()) {
                passMove = m;
                break;
            }
        }

        // 非紧急：有过牌或其它出法时，绝不选炸/王炸
        if (!bombUrgent && BombPolicy.isBombOrRocket(top)) {
            for (MoveEvaluation e : evalList) {
                Move m = e.getMove();
                if (!m.isPass() && !BombPolicy.isBombOrRocket(m)) {
                    return m;
                }
            }
            if (passMove != null) {
                return passMove;
            }
        }

        boolean leading = lastMove == null || lastMove.isPass();

        // 跟牌超压/拆对：即使尾牌也优先最小安全牌（A/RJ 同能赢墩时出 A）
        if (!leading) {
            // 非紧急：禁止把 2/王当带牌烧掉，宁可过牌
            if (!bombUrgent && passMove != null
                    && (HandShape.burnsControlAsAccessory(top) || HandShape.createsDeadWithControl(myHand, top))) {
                return passMove;
            }

            Move cheap = HandShape.findCheapestSafeBeater(legalMoves, myHand, lastMove);
            if (cheap != null && !top.isPass() && !BombPolicy.isBombOrRocket(top)) {
                if (HandShape.breaksSet(myHand, top) && !HandShape.breaksSet(myHand, cheap)) {
                    return cheap;
                }
                if (HandShape.isSevereOvershoot(top, cheap)) {
                    return cheap;
                }
            }
            if (cheap == null && passMove != null && !top.isPass()
                    && HandShape.breaksSet(myHand, top)
                    && lastMove.getMainRank() <= 10
                    && myHand.getTotalCards() >= 4) {
                return passMove;
            }

            // 已死散仍握控场：对敌方应尝试夺权，避免控场闲置到终局
            if (vsOpponent && top.isPass()
                    && HandShape.countWeakSingles(myHand) >= 4
                    && HandShape.hasControl(myHand)
                    && myHand.getTotalCards() >= 5) {
                Move rescue = HandShape.findRescueBeater(legalMoves, myHand, lastMove);
                if (rescue != null) {
                    return rescue;
                }
            }
        }

        if (myHand.getTotalCards() <= 2) {
            return top;
        }

        if (leading) {
            if (HandShape.createsDeadWithControl(myHand, top) || HandShape.burnsControlAsAccessory(top)
                    || HandShape.breaksSet(myHand, top)) {
                Move safeLead = HandShape.findBestSafeLead(legalMoves, myHand);
                if (safeLead != null) {
                    return safeLead;
                }
            }
            // 弱单已多且握控场：优先能减少弱单的结构首出
            if (HandShape.hasControl(myHand) && HandShape.countWeakSingles(myHand) >= 3
                    && HandShape.weakSinglesDelta(myHand, top) >= 0) {
                Move safeLead = HandShape.findBestSafeLead(legalMoves, myHand);
                if (safeLead != null && HandShape.weakSinglesDelta(myHand, safeLead) < 0) {
                    return safeLead;
                }
            }
            if (top.getType() == com.doudizhu.model.CardType.SINGLE
                    && top.getMainRank() >= com.doudizhu.model.Rank.TWO.getValue()
                    && myHand.getTotalCards() > 3) {
                Move safeLead = HandShape.findBestSafeLead(legalMoves, myHand);
                if (safeLead != null && safeLead.getMainRank() < com.doudizhu.model.Rank.TWO.getValue()) {
                    return safeLead;
                }
                for (MoveEvaluation e : evalList) {
                    Move m = e.getMove();
                    if (m.isPass() || BombPolicy.isBombOrRocket(m)) {
                        continue;
                    }
                    if (m.getMainRank() < com.doudizhu.model.Rank.TWO.getValue()) {
                        return m;
                    }
                }
            }
            return top;
        }

        return top;
    }
}

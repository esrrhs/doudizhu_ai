package com.farmer.ai;

import com.farmer.game.GameState;
import com.farmer.model.Hand;
import com.farmer.model.Rank;
import com.farmer.rules.Deck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 斗地主叫地主决策评估器 (基于思路二：PIMC 蒙特卡洛底牌与对手手牌采样推演)
 */
public class BidEvaluator {

    public record BidResult(
            boolean shouldCall,
            double winRate,
            int wins,
            int totalSimulations,
            long durationMs,
            int controlScore,
            String analysisSummary
    ) {
        public BidResult(boolean shouldCall, double winRate, int wins, int totalSimulations, long durationMs) {
            this(shouldCall, winRate, wins, totalSimulations, durationMs, 0, "");
        }
    }

    /**
     * 计算手牌硬牌力控制分 (Control Score)
     * 基于经典斗地主牌力体系：王炸/大王/小王、2 的数量、炸弹加成，并扣除多余散单牌
     */
    public static int computeControlScore(Hand hand) {
        int score = 0;

        // 1. 王牌
        if (hand.hasRocket()) {
            score += 8;
        } else {
            if (hand.getCount(Rank.RED_JOKER.getValue()) > 0) score += 4;
            if (hand.getCount(Rank.BLACK_JOKER.getValue()) > 0) score += 3;
        }

        // 2. 2 的数量 (2张以上质变)
        int twos = hand.getCount(Rank.TWO.getValue());
        if (twos == 1) score += 2;
        else if (twos == 2) score += 5;
        else if (twos == 3) score += 8;
        else if (twos == 4) score += 12;

        // 3. 炸弹 (3..A)
        for (int v = 3; v <= 14; v++) {
            if (hand.getCount(v) == 4) {
                score += 6;
            }
        }

        // 4. 散小单牌惩罚 (<= 9 的单张牌需要消耗回手牌权)
        for (int v = 3; v <= 9; v++) {
            if (hand.getCount(v) == 1) {
                score -= 1;
            }
        }

        return score;
    }

    /**
     * 根据当前 17 张手牌，结合【硬牌力先验】与【多世界并行 MCTS 深度推演】评估地主胜率
     *
     * @param myHand 当前玩家的 17 张手牌
     * @param sampleCount 采样世界次数 (建议 50 ~ 80 次)
     * @param winRateThreshold 决定叫地主的胜率基准阈值 (通常设为 0.50 ~ 0.52)
     * @param random 随机数生成器
     * @return 深度评估结果
     */
    public static BidResult evaluate(Hand myHand, int sampleCount, double winRateThreshold, Random random) {
        return evaluate(myHand, sampleCount, 80, winRateThreshold, random);
    }

    /**
     * 全参数深度评估：支持指定采样世界数与每个世界的 MCTS 搜索迭代量
     */
    public static BidResult evaluate(Hand myHand, int sampleCount, int mctsIterationsPerWorld, double winRateThreshold, Random random) {
        long startTime = System.currentTimeMillis();

        int controlScore = computeControlScore(myHand);

        // 1. 守门规则：无王无2无炸 (控制分 < 3) 属于极端死局手牌，坚决不叫，无需空耗深层 MCTS
        if (controlScore < 3) {
            long cost = System.currentTimeMillis() - startTime;
            double estimatedRate = Math.max(0.12, 0.22 + controlScore * 0.04);
            return new BidResult(false, estimatedRate, 0, sampleCount, cost, controlScore,
                    String.format("无王牌且控场 2 匮乏 (控制分 %d)，散单过多，坚决不叫", controlScore));
        }

        // 2. 超强天牌：双王+多张2或炸弹 (控制分 >= 14)，必叫无疑
        if (controlScore >= 14) {
            long cost = System.currentTimeMillis() - startTime;
            return new BidResult(true, 0.92, sampleCount, sampleCount, cost, controlScore,
                    String.format("手握绝对牌权与天牌组合 (控制分 %d)，果断叫地主", controlScore));
        }

        // 3. 边界与优质手牌：进入【多世界底牌采样 + MCTS 深度博弈推演】
        List<Rank> fullDeck = Deck.createStandard54Cards();
        Hand tempHand = myHand.copy();
        List<Rank> unknownPool = new ArrayList<>(37);

        for (Rank r : fullDeck) {
            if (tempHand.getCount(r) > 0) {
                tempHand.remove(r);
            } else {
                unknownPool.add(r);
            }
        }

        java.util.concurrent.atomic.DoubleAdder totalBestWinRate = new java.util.concurrent.atomic.DoubleAdder();
        java.util.concurrent.atomic.AtomicInteger favorableWorlds = new java.util.concurrent.atomic.AtomicInteger();

        // 多核心并行 MCTS 推演
        java.util.stream.IntStream.range(0, sampleCount).parallel().forEach(s -> {
            java.util.concurrent.ThreadLocalRandom workerRandom = java.util.concurrent.ThreadLocalRandom.current();
            List<Rank> shuffled = new ArrayList<>(unknownPool);
            Collections.shuffle(shuffled, workerRandom);

            // 采样 3 张底牌
            List<Rank> sampledBottom = new ArrayList<>(3);
            for (int i = 0; i < 3; i++) {
                sampledBottom.add(shuffled.get(i));
            }

            // 组成地主 20 张新手牌
            Hand landlordHand = myHand.copy();
            for (Rank r : sampledBottom) {
                landlordHand.add(r);
            }

            // 分发两家农民各 17 张手牌
            Hand farmer1 = new Hand();
            Hand farmer2 = new Hand();
            for (int i = 3; i < 20; i++) {
                farmer1.add(shuffled.get(i));
            }
            for (int i = 20; i < 37; i++) {
                farmer2.add(shuffled.get(i));
            }

            GameState simState = new GameState(
                    List.of(landlordHand, farmer1, farmer2),
                    0,
                    sampledBottom
            );

            // 启动该世界的 MCTS 树搜索，推演最佳首出走法的真实胜率
            MctsSearcher searcher = new MctsSearcher(Math.sqrt(2.0), workerRandom);
            MctsNode root = searcher.search(simState, mctsIterationsPerWorld);

            double bestChildWinRate = root.getChildren().values().stream()
                    .mapToDouble(c -> c.getWinRate(0))
                    .max()
                    .orElse(0.0);

            totalBestWinRate.add(bestChildWinRate);
            if (bestChildWinRate >= 0.50) {
                favorableWorlds.incrementAndGet();
            }
        });

        double avgWinRate = totalBestWinRate.sum() / sampleCount;
        double dynamicThreshold = (controlScore >= 7) ? (winRateThreshold - 0.03) : winRateThreshold;
        boolean shouldCall = (avgWinRate >= dynamicThreshold);
        long durationMs = System.currentTimeMillis() - startTime;

        String summary = String.format("控制分 %d，底牌采样 MCTS 综合胜率 %.1f%% (%d/%d 世界占优)",
                controlScore, avgWinRate * 100, favorableWorlds.get(), sampleCount);

        return new BidResult(shouldCall, avgWinRate, favorableWorlds.get(), sampleCount, durationMs, controlScore, summary);
    }
}

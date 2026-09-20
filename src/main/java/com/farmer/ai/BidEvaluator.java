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

    public record BidResult(boolean shouldCall, double winRate, int wins, int totalSimulations, long durationMs) {}

    /**
     * 根据当前 17 张手牌，采样假设底牌与对手手牌，通过 FastRollout 模拟推演地主胜率
     *
     * @param myHand 当前玩家的 17 张手牌
     * @param sampleCount 采样世界次数 (建议 25 ~ 40 次)
     * @param winRateThreshold 决定叫地主的胜率阈值 (通常设为 0.50 ~ 0.53)
     * @param random 随机数生成器
     * @return 评估结果 (包含是否叫地主、预估胜率、推演耗时)
     */
    public static BidResult evaluate(Hand myHand, int sampleCount, double winRateThreshold, Random random) {
        long startTime = System.currentTimeMillis();

        // 1. 获取全场未知牌池 (54 张 - 自己已知的 17 张 = 37 张)
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

        int wins = 0;

        // 2. 蒙特卡洛采样推演
        for (int s = 0; s < sampleCount; s++) {
            Collections.shuffle(unknownPool, random);

            // 采样 3 张作为底牌
            List<Rank> sampledBottom = new ArrayList<>(3);
            for (int i = 0; i < 3; i++) {
                sampledBottom.add(unknownPool.get(i));
            }

            // 自己作为地主的手牌 (17 + 3 = 20 张)
            Hand landlordHand = myHand.copy();
            for (Rank r : sampledBottom) {
                landlordHand.add(r);
            }

            // 剩下 34 张牌随机分给两名农民对手 (各 17 张)
            Hand farmer1 = new Hand();
            Hand farmer2 = new Hand();
            for (int i = 3; i < 20; i++) {
                farmer1.add(unknownPool.get(i));
            }
            for (int i = 20; i < 37; i++) {
                farmer2.add(unknownPool.get(i));
            }

            // 构建假想对局 (设置玩家 0 为地主，率先出牌)
            GameState simState = new GameState(
                    List.of(landlordHand, farmer1, farmer2),
                    0,
                    sampledBottom
            );

            // 使用 FastRolloutPolicy 快速推演至终局
            FastRolloutPolicy.simulate(simState, random);

            // 判断地主 (玩家 0) 是否获胜
            if (simState.isPlayerWinner(0)) {
                wins++;
            }
        }

        double winRate = (double) wins / sampleCount;
        boolean shouldCall = winRate >= winRateThreshold;
        long durationMs = System.currentTimeMillis() - startTime;

        return new BidResult(shouldCall, winRate, wins, sampleCount, durationMs);
    }
}

package com.doudizhu.ai;

import java.util.Random;

/**
 * 随机叫分规则：轮到表态的玩家独立地以 {@link #CALL_PROBABILITY} 概率叫地主，
 * 最先叫的一家当地主；三家都不叫则流局重新发牌。
 *
 * <p>不再用牌力评估或蒙特卡洛推演决定叫分，叫地主与出牌强度解耦，地主可能拿到很弱的牌。
 */
public final class BidRule {

    public static final double CALL_PROBABILITY = 1.0 / 3.0;

    private BidRule() {
    }

    public static boolean shouldCall(Random random) {
        return random.nextDouble() < CALL_PROBABILITY;
    }
}

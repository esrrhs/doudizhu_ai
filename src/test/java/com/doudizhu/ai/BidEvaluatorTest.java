package com.doudizhu.ai;

import com.doudizhu.model.Hand;
import com.doudizhu.rules.Deck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class BidEvaluatorTest {

    @Test
    @DisplayName("测试思路二叫牌评估：极强手牌应该果断叫地主")
    void testStrongHandShouldCall() {
        // 双王 + 三张 2 + 双 A + 顺子，天牌
        Hand strongHand = Deck.fromCardString("RJ,BJ,2,2,2,A,A,K,K,Q,Q,J,10,9,8,7,6");
        Random random = new Random(42);

        BidEvaluator.BidResult result = BidEvaluator.evaluate(strongHand, 30, 0.50, random);

        // 胜率应远高于 50% 且决定叫地主
        assertThat(result.winRate()).isGreaterThan(0.60);
        assertThat(result.shouldCall()).isTrue();
    }

    @Test
    @DisplayName("测试思路二叫牌评估：极弱无控牌手牌应该果断不叫")
    void testWeakHandShouldPass() {
        // 全是单散小杂牌，无王无 2
        Hand weakHand = Deck.fromCardString("3,4,5,6,7,8,9,10,J,Q,3,4,5,6,7,8,9");
        Random random = new Random(42);

        BidEvaluator.BidResult result = BidEvaluator.evaluate(weakHand, 30, 0.50, random);

        // 胜率应显著低于 50% 且决定不叫
        assertThat(result.winRate()).isLessThan(0.40);
        assertThat(result.shouldCall()).isFalse();
    }
}

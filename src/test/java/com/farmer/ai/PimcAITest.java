package com.farmer.ai;

import com.farmer.game.GameState;
import com.farmer.game.PublicView;
import com.farmer.model.Hand;
import com.farmer.model.Rank;
import com.farmer.rules.Deck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class PimcAITest {

    @Test
    @DisplayName("测试确定化采样器 (Determinizer) 的未知牌守恒性与手牌分配")
    void testDeterminizerConservation() {
        Random random = new Random(42);
        int landlordId = 0;
        Deck.DealResult dealResult = Deck.deal(random, landlordId);

        GameState state = new GameState(dealResult.playerHands(), landlordId, dealResult.bottomCards());
        PublicView view = state.getPublicView(0);
        List<Rank> unseen = view.computeUnseenCards();

        // 另外两家各有 17 张手牌，所以未知牌数必须等于 34
        assertThat(unseen).hasSize(34);

        // 确定化生成假想状态
        GameState sampledWorld = Determinizer.determinize(view, random);

        // 验证玩家 0 (地主) 的手牌与真实一致 (20张)
        assertThat(sampledWorld.getPlayer(0).getHand().getTotalCards()).isEqualTo(20);
        assertThat(sampledWorld.getPlayer(0).getHand().toCardString()).isEqualTo(dealResult.playerHands().get(0).toCardString());

        // 验证两位农民分配到的张数完全符合公开信息 (各17张)
        assertThat(sampledWorld.getPlayer(1).getHand().getTotalCards()).isEqualTo(17);
        assertThat(sampledWorld.getPlayer(2).getHand().getTotalCards()).isEqualTo(17);
    }

    @Test
    @DisplayName("测试 AI 在仅剩必胜手牌时的决策准确度")
    void testWinningMoveSelection() {
        Hand myHand = Deck.fromCardString("RJ"); // 我只剩一张大王
        Hand opp1 = Deck.fromCardString("3,4");
        Hand opp2 = Deck.fromCardString("5,6");

        GameState state = new GameState(List.of(myHand, opp1, opp2), 0, List.of(Rank.THREE, Rank.FOUR, Rank.FIVE));
        PublicView view = state.getPublicView(0);

        PimcAiPlayer ai = new PimcAiPlayer(5, 50);
        PimcAiPlayer.DecisionResult result = ai.decide(view);

        // 必须直接出 RJ 获胜
        assertThat(result.getSelectedMove().toCardString()).isEqualTo("RJ");
    }
}

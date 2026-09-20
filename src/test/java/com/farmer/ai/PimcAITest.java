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

    @Test
    @DisplayName("测试被迫出牌或过牌时不虚假汇报 100% 胜率")
    void testHonestWinRateEstimation() {
        // 我方处于劣势只剩 3, 对手剩对 2，我方出 3 不能保证必胜，绝不能谎报 100%
        Hand myHand = Deck.fromCardString("3");
        Hand opp1 = Deck.fromCardString("5,2,2");
        Hand opp2 = Deck.fromCardString("A,A");

        GameState state = new GameState(List.of(myHand, opp1, opp2), 1, List.of(Rank.THREE, Rank.FOUR, Rank.FIVE));
        // 玩家 1 出 5
        state.applyMove(com.farmer.model.Move.of(com.farmer.model.CardType.SINGLE, Rank.FIVE.getValue(), List.of(Rank.FIVE), 1));
        // 玩家 2 出 A
        state.applyMove(com.farmer.model.Move.of(com.farmer.model.CardType.SINGLE, Rank.ACE.getValue(), List.of(Rank.ACE), 2));
        // 此时轮到玩家 0 (我方)，只能 PASS
        PublicView view = state.getPublicView(0);

        PimcAiPlayer ai = new PimcAiPlayer(5, 50);
        PimcAiPlayer.DecisionResult result = ai.decide(view);

        // 只能 PASS，且胜率不能是 100%
        assertThat(result.getSelectedMove().isPass()).isTrue();
        assertThat(result.getEvaluations().get(0).getAverageWinRate()).isLessThan(1.0);
    }

    @Test
    @DisplayName("测试首出牌时优先出小散牌，保留控制大牌 (2/王) 用于回手")
    void testConserveControlCardsOnLead() {
        // 我方持牌: 3, 4, 2，轮到我方自由领出 (Lead)
        // 关键博弈：如果先出 2，手中剩下 3, 4 两张死单，再出一张 3 后因无大牌回手而必输；
        // 必须先出 3 引出对手牌，再用 2 夺回牌权打出 4 获胜！
        Hand myHand = Deck.fromCardString("3,4,2");
        Hand opp1 = Deck.fromCardString("5,6,7");
        Hand opp2 = Deck.fromCardString("8,9,10");

        GameState state = new GameState(List.of(myHand, opp1, opp2), 0, List.of(Rank.THREE, Rank.FOUR, Rank.FIVE));
        PublicView view = state.getPublicView(0);

        PimcAiPlayer ai = new PimcAiPlayer(15, 100);
        PimcAiPlayer.DecisionResult result = ai.decide(view);

        // 首出必须是小牌 3 或 4，绝不能把大牌 2 率先浪费
        assertThat(result.getSelectedMove().toCardString()).isIn("3", "4");
    }
}

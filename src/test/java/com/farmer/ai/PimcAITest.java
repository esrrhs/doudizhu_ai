package com.farmer.ai;

import com.farmer.game.GameState;
import com.farmer.game.PublicView;
import com.farmer.model.Hand;
import com.farmer.model.Move;
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
    @DisplayName("过牌推断是优先级：有落单小牌可压的世界似然更低，但仍会分到搜索预算")
    void testPassInferenceIsPriorityNotHardReject() {
        Hand p0 = Deck.fromCardString("5,6,7,8,9,10,J,Q,K,A,2,BJ,RJ,3,4,8,9,10,J,Q");
        Hand p1 = Deck.fromCardString("3,4,6,7,8,9,10,J,Q,K,A,2,3,4,5,6,7");
        Hand p2 = Deck.fromCardString("3,4,5,6,7,8,9,10,J,Q,K,A,3,4,5,6,7");
        GameState state = new GameState(List.of(p0, p1, p2), 0, List.of(Rank.THREE, Rank.FOUR, Rank.FIVE));
        state.applyMove(com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.FIVE.getValue(), List.of(Rank.FIVE), 0));
        state.applyMove(com.farmer.model.Move.pass(1));
        PublicView view = state.getPublicView(0);

        Hand p1Cheap = Deck.fromCardString("6,9,9,10,10,J,J,Q,Q,K,K,A,A,2,3,3,4");
        Hand p1Clean = Deck.fromCardString("9,9,10,10,J,J,Q,Q,K,K,A,A,2,3,3,4,4");
        List<Hand> low = List.of(state.getPlayer(0).getHand(), p1Cheap, state.getPlayer(2).getHand());
        List<Hand> high = List.of(state.getPlayer(0).getHand(), p1Clean, state.getPlayer(2).getHand());

        double lowP = PassInference.likelihood(view, low);
        double highP = PassInference.likelihood(view, high);
        assertThat(highP).isGreaterThan(lowP);
        assertThat(lowP).isGreaterThan(0.0);

        int[] iters = PassInference.allocateSearchIterations(new double[]{highP, lowP}, 1600);
        assertThat(iters[0]).isGreaterThan(iters[1]);
        assertThat(iters[1]).isGreaterThan(200);
    }

    @Test
    @DisplayName("农民视角确定化：未打出的已知底牌必须落入地主手牌")
    void testDeterminizerPinsBottomCardsToLandlord() {
        Random random = new Random(7);
        int landlordId = 0;
        Deck.DealResult dealResult = Deck.deal(random, landlordId);
        GameState state = new GameState(dealResult.playerHands(), landlordId, dealResult.bottomCards());

        // 以农民 P1 视角采样多个世界
        PublicView farmerView = state.getPublicView(1);
        for (int i = 0; i < 20; i++) {
            GameState world = Determinizer.determinize(farmerView, random);
            Hand landlordHand = world.getPlayer(landlordId).getHand().copy();
            for (Rank bottom : dealResult.bottomCards()) {
                assertThat(landlordHand.getCount(bottom.getValue()))
                        .as("底牌 %s 必须在地主手中", bottom.getSymbol())
                        .isGreaterThan(0);
                landlordHand.remove(bottom);
            }
        }
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

    @Test
    @DisplayName("硬护栏：有 10 可压时禁止用 2 超压（即使搜索更看好 2）")
    void testHardGuardBlocksControlOvershoot() {
        Hand hand = Deck.fromCardString("7,8,10,J,K,A,2");
        Move last = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.NINE.getValue(), List.of(Rank.NINE), 1);
        Move two = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.TWO.getValue(), List.of(Rank.TWO), 0);
        Move ten = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.TEN.getValue(), List.of(Rank.TEN), 0);
        PimcAiPlayer.MoveEvaluation preferTwo = new PimcAiPlayer.MoveEvaluation(two);
        preferTwo.record(200, 0.95);
        PimcAiPlayer.MoveEvaluation preferTen = new PimcAiPlayer.MoveEvaluation(ten);
        preferTen.record(200, 0.40);
        Move chosen = PimcAiPlayer.selectWithHardGuards(
                java.util.List.of(preferTwo, preferTen),
                java.util.List.of(two, ten, com.farmer.model.Move.pass(0)),
                hand, last, false, false);
        assertThat(chosen.toCardString()).isEqualTo("10");
    }

    @Test
    @DisplayName("硬护栏：主动禁止拆三出单，优先出不拆结构的牌")
    void testHardGuardBlocksLeadBreakSet() {
        Hand hand = Deck.fromCardString("3,3,3,4,9,K,A,2");
        Move break3 = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.THREE.getValue(), List.of(Rank.THREE), 0);
        Move lead4 = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.FOUR.getValue(), List.of(Rank.FOUR), 0);
        PimcAiPlayer.MoveEvaluation preferBreak = new PimcAiPlayer.MoveEvaluation(break3);
        preferBreak.record(100, 0.8);
        PimcAiPlayer.MoveEvaluation preferSafe = new PimcAiPlayer.MoveEvaluation(lead4);
        preferSafe.record(100, 0.3);
        Move chosen = PimcAiPlayer.selectWithHardGuards(
                java.util.List.of(preferBreak, preferSafe),
                java.util.List.of(break3, lead4),
                hand, null, false, false);
        assertThat(chosen.toCardString()).isEqualTo("4");
    }

    @Test
    @DisplayName("硬护栏：有对 5 可压时禁止用对 10 超压")
    void testHardGuardBlocksPairOvershoot() {
        Hand hand = Deck.fromCardString("5,5,10,10,2,2,2");
        Move last = com.farmer.model.Move.of(
                com.farmer.model.CardType.PAIR, Rank.THREE.getValue(),
                java.util.List.of(Rank.THREE, Rank.THREE), 1);
        Move tens = com.farmer.model.Move.of(
                com.farmer.model.CardType.PAIR, Rank.TEN.getValue(),
                java.util.List.of(Rank.TEN, Rank.TEN), 0);
        Move fives = com.farmer.model.Move.of(
                com.farmer.model.CardType.PAIR, Rank.FIVE.getValue(),
                java.util.List.of(Rank.FIVE, Rank.FIVE), 0);
        PimcAiPlayer.MoveEvaluation preferTens = new PimcAiPlayer.MoveEvaluation(tens);
        preferTens.record(80, 0.9);
        PimcAiPlayer.MoveEvaluation preferFives = new PimcAiPlayer.MoveEvaluation(fives);
        preferFives.record(80, 0.5);
        Move chosen = PimcAiPlayer.selectWithHardGuards(
                java.util.List.of(preferTens, preferFives),
                java.util.List.of(tens, fives, com.farmer.model.Move.pass(0)),
                hand, last, false, false);
        assertThat(chosen.toCardString()).isEqualTo("5,5");
    }

    @Test
    @DisplayName("硬护栏：全对子只能拆对压中小牌时优先过牌")
    void testHardGuardPassInsteadOfBreakPair() {
        Hand hand = Deck.fromCardString("5,5,K,K,A,A,2,2");
        Move last = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.SEVEN.getValue(), List.of(Rank.SEVEN), 1);
        Move king = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.KING.getValue(), List.of(Rank.KING), 0);
        Move pass = com.farmer.model.Move.pass(0);
        PimcAiPlayer.MoveEvaluation preferKing = new PimcAiPlayer.MoveEvaluation(king);
        preferKing.record(100, 0.85);
        PimcAiPlayer.MoveEvaluation preferPass = new PimcAiPlayer.MoveEvaluation(pass);
        preferPass.record(100, 0.40);
        Move chosen = PimcAiPlayer.selectWithHardGuards(
                java.util.List.of(preferKing, preferPass),
                java.util.List.of(king, pass),
                hand, last, false, false);
        assertThat(chosen.isPass()).isTrue();
    }

    @Test
    @DisplayName("农民绝不压队友（含王炸）")
    void testFarmerNeverBeatsTeammate() {
        // 座位顺序 0→1→2：地主 P0 出牌后，农民 P1 出 K，轮到农民 P2（有王炸）应过牌
        Hand p0 = Deck.fromCardString("3,4,5,6,7,8,9,10,J,Q,A,2,3,4,5,6,7");
        Hand p1 = Deck.fromCardString("4,5,6,6,7,8,9,10,K,K,A,2,3,4,5,6,7");
        Hand p2 = Deck.fromCardString("3,3,4,J,J,8,9,10,J,Q,A,2,BJ,RJ,5,6,7");
        GameState state = new GameState(List.of(p0, p1, p2), 0, List.of(Rank.THREE, Rank.FOUR, Rank.FIVE));
        state.applyMove(com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.THREE.getValue(), List.of(Rank.THREE), 0));
        state.applyMove(com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.KING.getValue(), List.of(Rank.KING), 1));
        PublicView view = state.getPublicView(2);
        PimcAiPlayer ai = new PimcAiPlayer(8, 40);
        PimcAiPlayer.DecisionResult result = ai.decide(view);
        assertThat(result.getSelectedMove().isPass()).isTrue();
    }

    @Test
    @DisplayName("硬护栏：主动有落单时禁止拆对出单")
    void testHardGuardBlocksLeadBreakWhenSinglesExist() {
        Hand hand = Deck.fromCardString("3,6,7,8,8,10,10,Q,K,2");
        Move break8 = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.EIGHT.getValue(), List.of(Rank.EIGHT), 0);
        Move lead3 = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.THREE.getValue(), List.of(Rank.THREE), 0);
        Move pair10 = com.farmer.model.Move.of(
                com.farmer.model.CardType.PAIR, Rank.TEN.getValue(),
                java.util.List.of(Rank.TEN, Rank.TEN), 0);
        PimcAiPlayer.MoveEvaluation preferBreak = new PimcAiPlayer.MoveEvaluation(break8);
        preferBreak.record(100, 0.9);
        PimcAiPlayer.MoveEvaluation e3 = new PimcAiPlayer.MoveEvaluation(lead3);
        e3.record(10, 0.2);
        PimcAiPlayer.MoveEvaluation e10 = new PimcAiPlayer.MoveEvaluation(pair10);
        e10.record(10, 0.3);
        Move chosen = PimcAiPlayer.selectWithHardGuards(
                java.util.List.of(preferBreak, e3, e10),
                java.util.List.of(break8, lead3, pair10),
                hand, null, false, false);
        assertThat(HandShape.breaksSet(hand, chosen)).isFalse();
        assertThat(chosen.toCardString()).isNotEqualTo("8");
    }

    @Test
    @DisplayName("硬护栏：尾牌两张时有 A 可压则不用大王超压")
    void testHardGuardEndgamePreferAceOverRocket() {
        Hand hand = Deck.fromCardString("A,RJ");
        Move last = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.FOUR.getValue(), List.of(Rank.FOUR), 1);
        Move rj = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.RED_JOKER.getValue(), List.of(Rank.RED_JOKER), 0);
        Move ace = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.ACE.getValue(), List.of(Rank.ACE), 0);
        PimcAiPlayer.MoveEvaluation preferRj = new PimcAiPlayer.MoveEvaluation(rj);
        preferRj.record(50, 0.99);
        PimcAiPlayer.MoveEvaluation preferAce = new PimcAiPlayer.MoveEvaluation(ace);
        preferAce.record(50, 0.5);
        Move chosen = PimcAiPlayer.selectWithHardGuards(
                java.util.List.of(preferRj, preferAce),
                java.util.List.of(rj, ace, com.farmer.model.Move.pass(0)),
                hand, last, false, false);
        assertThat(chosen.toCardString()).isEqualTo("A");
    }

    @Test
    @DisplayName("硬护栏：非紧急禁止用三带二把 2 当带牌烧掉造死散")
    void testHardGuardRejectsBurningControlAsAccessory() {
        Hand hand = Deck.fromCardString("3,4,5,6,6,7,8,9,10,10,J,Q,K,2,2,2,BJ");
        Move last = com.farmer.model.Move.of(
                com.farmer.model.CardType.TRIPLE_PLUS_PAIR, Rank.NINE.getValue(),
                java.util.List.of(Rank.NINE, Rank.NINE, Rank.NINE, Rank.FIVE, Rank.FIVE), 0);
        Move burn = com.farmer.model.Move.of(
                com.farmer.model.CardType.TRIPLE_PLUS_PAIR, Rank.TWO.getValue(),
                java.util.List.of(Rank.TWO, Rank.TWO, Rank.TWO, Rank.TEN, Rank.TEN), 1);
        Move pass = com.farmer.model.Move.pass(1);
        PimcAiPlayer.MoveEvaluation preferBurn = new PimcAiPlayer.MoveEvaluation(burn);
        preferBurn.record(100, 0.9);
        PimcAiPlayer.MoveEvaluation preferPass = new PimcAiPlayer.MoveEvaluation(pass);
        preferPass.record(100, 0.4);
        Move chosen = PimcAiPlayer.selectWithHardGuards(
                java.util.List.of(preferBurn, preferPass),
                java.util.List.of(burn, pass),
                hand, last, false, true);
        assertThat(chosen.isPass()).isTrue();
        assertThat(HandShape.createsDeadWithControl(hand, burn)).isTrue();
    }

    @Test
    @DisplayName("HandShape: 弱单多且握控场时对敌应能找到夺权着法")
    void testRescueBeaterWhenDeadWithControl() {
        Hand hand = Deck.fromCardString("3,4,6,8,K,A,2,RJ");
        Move last = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.JACK.getValue(), List.of(Rank.JACK), 0);
        Move two = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.TWO.getValue(), List.of(Rank.TWO), 1);
        Move rj = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.RED_JOKER.getValue(), List.of(Rank.RED_JOKER), 1);
        Move ace = com.farmer.model.Move.of(
                com.farmer.model.CardType.SINGLE, Rank.ACE.getValue(), List.of(Rank.ACE), 1);
        Move pass = com.farmer.model.Move.pass(1);
        Move rescue = HandShape.findRescueBeater(java.util.List.of(two, rj, ace, pass), hand, last);
        assertThat(rescue).isNotNull();
        assertThat(rescue.toCardString()).isEqualTo("A");
    }

    @Test
    @DisplayName("测试大参数下完整 20 张手牌的 PIMC 决策耗时与深层展开")
    void testPimcScaleBenchmark() {
        Random random = new Random(42);
        Deck.DealResult deal = Deck.deal(random, 0);
        GameState state = new GameState(deal.playerHands(), 0, deal.bottomCards());
        PublicView view = state.getPublicView(0);

        // 采样 80 个世界，每个世界 500 次 MCTS 迭代 (共 40,000 次深度推演模拟)
        PimcAiPlayer ai = new PimcAiPlayer(80, 500, random);
        long start = System.currentTimeMillis();
        PimcAiPlayer.DecisionResult result = ai.decide(view);
        long cost = System.currentTimeMillis() - start;

        System.out.printf("[BENCHMARK] 80 worlds * 500 iter: cost=%d ms, selectedMove=%s, evaluations=%d\n",
                cost, result.getSelectedMove().toCardString(), result.getEvaluations().size());
        assertThat(result.getSelectedMove()).isNotNull();
    }
}

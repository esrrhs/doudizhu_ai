package com.farmer.rules;

import com.farmer.model.CardType;
import com.farmer.model.Hand;
import com.farmer.model.Move;
import com.farmer.model.Rank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MoveGeneratorTest {

    @Test
    @DisplayName("主动出牌测试：包含王炸、炸弹、三带二、顺子")
    void testLeadMovesGeneration() {
        Hand hand = Deck.fromCardString("3,3,4,5,6,7,8,8,8,8,BJ,RJ");
        List<Move> moves = MoveGenerator.generateLegalMoves(hand, null, 0);

        assertThat(moves).isNotEmpty();

        // 包含火箭 (王炸)
        boolean hasRocket = moves.stream()
                .anyMatch(m -> m.getType() == CardType.ROCKET);
        assertThat(hasRocket).isTrue();

        // 包含炸弹 8
        boolean hasBomb8 = moves.stream()
                .anyMatch(m -> m.isBomb() && m.getMainRank() == Rank.EIGHT.getValue());
        assertThat(hasBomb8).isTrue();

        // 包含对子 3
        boolean hasPair3 = moves.stream()
                .anyMatch(m -> m.getType() == CardType.PAIR && m.getMainRank() == Rank.THREE.getValue());
        assertThat(hasPair3).isTrue();

        // 包含顺子 3,4,5,6,7
        boolean hasStraight = moves.stream()
                .anyMatch(m -> m.getType() == CardType.STRAIGHT && m.getMainRank() == Rank.SEVEN.getValue() && m.getCardCount() == 5);
        assertThat(hasStraight).isTrue();

        // 首出牌不应包含 PASS
        assertThat(moves).noneMatch(Move::isPass);
    }

    @Test
    @DisplayName("压牌测试：王炸克制普通炸弹，斗地主随时允许 PASS")
    void testRocketBeatsBombAndPassAllowed() {
        Hand hand = Deck.fromCardString("3,4,BJ,RJ");

        // 场上是 8 炸弹
        Move bomb8 = Move.of(CardType.BOMB, Rank.EIGHT.getValue(), List.of(Rank.EIGHT, Rank.EIGHT, Rank.EIGHT, Rank.EIGHT), 1);
        List<Move> beatMoves = MoveGenerator.generateLegalMoves(hand, bomb8, 0);

        // 可以出王炸
        assertThat(beatMoves).anyMatch(m -> m.getType() == CardType.ROCKET);

        // 斗地主自由出牌规则：随时允许 PASS
        assertThat(beatMoves).anyMatch(Move::isPass);
    }

    @Test
    @DisplayName("压牌测试：无法压制时包含且仅包含 PASS")
    void testPassWhenCannotBeat() {
        Hand hand = Deck.fromCardString("3,4,5");

        // 场上是对 K
        Move lastPairK = Move.of(CardType.PAIR, Rank.KING.getValue(), List.of(Rank.KING, Rank.KING), 1);
        List<Move> beatMoves = MoveGenerator.generateLegalMoves(hand, lastPairK, 0);

        assertThat(beatMoves).hasSize(1);
        assertThat(beatMoves.get(0).isPass()).isTrue();
    }

    @Test
    @DisplayName("标准连对测试：腾讯斗地主规则必须至少 3 对 (6张) 才能连，2 对不能连")
    void testConsecutivePairsRequiresAtLeastThreePairs() {
        // 只有 2 对：3344
        Hand twoPairsHand = Deck.fromCardString("3,3,4,4");
        List<Move> twoPairsMoves = MoveGenerator.generateLegalMoves(twoPairsHand, null, 0);
        assertThat(twoPairsMoves.stream().noneMatch(m -> m.getType() == CardType.CONSECUTIVE_PAIRS)).isTrue();

        // 拥有 3 对：334455
        Hand threePairsHand = Deck.fromCardString("3,3,4,4,5,5");
        List<Move> threePairsMoves = MoveGenerator.generateLegalMoves(threePairsHand, null, 0);
        assertThat(threePairsMoves.stream().anyMatch(m -> m.getType() == CardType.CONSECUTIVE_PAIRS && m.getCardCount() == 6)).isTrue();
    }
}

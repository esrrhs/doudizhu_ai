package com.farmer.rules;

import com.farmer.model.Hand;
import com.farmer.model.Rank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 斗地主 54 张标准牌库生成与发牌器
 * (3~2 各 4 张，大小王各 1 张，共 54 张；地主 20 张，两位农民各 17 张，底牌 3 张)
 */
public class Deck {
    public static final int TOTAL_CARDS = 54;
    public static final int BOTTOM_CARDS_COUNT = 3;
    public static final int FARMER_CARDS_COUNT = 17;
    public static final int LANDLORD_CARDS_COUNT = 20;

    public record DealResult(List<Hand> playerHands, List<Rank> bottomCards) {}

    public static List<Rank> createStandard54Cards() {
        List<Rank> deck = new ArrayList<>(TOTAL_CARDS);
        // 3 到 2，各 4 张 (13 * 4 = 52 张)
        for (int v = Rank.THREE.getValue(); v <= Rank.TWO.getValue(); v++) {
            Rank r = Rank.fromValue(v);
            for (int i = 0; i < 4; i++) {
                deck.add(r);
            }
        }
        // 小王、大王
        deck.add(Rank.BLACK_JOKER);
        deck.add(Rank.RED_JOKER);
        return deck;
    }

    /**
     * 洗牌并分发给 3 位玩家，默认 player 0 为地主 (获得 3 张底牌)
     */
    public static DealResult deal(Random random, int landlordPlayerId) {
        List<Rank> deck = createStandard54Cards();
        Collections.shuffle(deck, random);

        List<Hand> hands = new ArrayList<>(3);
        hands.add(new Hand());
        hands.add(new Hand());
        hands.add(new Hand());

        // 前 51 张牌按每人 17 张分发
        for (int i = 0; i < 3; i++) {
            Hand hand = hands.get(i);
            for (int j = 0; j < FARMER_CARDS_COUNT; j++) {
                hand.add(deck.get(i * FARMER_CARDS_COUNT + j));
            }
        }

        // 最后 3 张为底牌
        List<Rank> bottomCards = new ArrayList<>(3);
        for (int i = 51; i < 54; i++) {
            bottomCards.add(deck.get(i));
        }

        // 地主获得底牌
        for (Rank r : bottomCards) {
            hands.get(landlordPlayerId).add(r);
        }

        return new DealResult(hands, bottomCards);
    }

    public static Hand fromCardString(String str) {
        Hand hand = new Hand();
        if (str == null || str.isBlank()) {
            return hand;
        }
        String[] parts = str.split(",");
        for (String p : parts) {
            String s = p.trim();
            if (!s.isEmpty()) {
                hand.add(Rank.fromSymbol(s));
            }
        }
        return hand;
    }
}

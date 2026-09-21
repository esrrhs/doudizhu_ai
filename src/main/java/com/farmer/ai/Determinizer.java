package com.farmer.ai;

import com.farmer.game.GameState;
import com.farmer.game.PublicView;
import com.farmer.model.Hand;
import com.farmer.model.Rank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 斗地主确定化采样器 (Determinizer)
 * <p>
 * 未知牌池 = 54 张 − 自己手牌 − 场上历史已打出牌；
 * 再按公开剩余张数分给两名对手。若底牌已揭晓且观察者不是地主，
 * 尚未打出的底牌必须落入地主手牌。
 * <p>
 * 采样后用过牌历史做一致性剪枝：例如对方对单牌选择 PASS，
 * 则不应再给他「落单的小/中单」这类明显该管的牌。
 */
public class Determinizer {

    private static final int MAX_RESAMPLE_ATTEMPTS = 80;

    public static GameState determinize(PublicView publicView, Random random) {
        GameState best = null;
        for (int attempt = 0; attempt < MAX_RESAMPLE_ATTEMPTS; attempt++) {
            List<Hand> hands = sampleHands(publicView, random);
            if (PassInference.isWorldConsistent(publicView, hands)) {
                return buildState(publicView, hands);
            }
            best = buildState(publicView, hands);
        }
        // 约束过紧时退回最后一次采样，避免卡死
        return best;
    }

    private static List<Hand> sampleHands(PublicView publicView, Random random) {
        int myId = publicView.getViewingPlayerId();
        int landlordId = publicView.getLandlordId();
        List<Rank> unseen = new ArrayList<>(publicView.computeUnseenCards());

        List<Hand> hands = new ArrayList<>(3);
        hands.add(new Hand());
        hands.add(new Hand());
        hands.add(new Hand());

        hands.set(myId, publicView.getMyHand().copy());

        List<Rank> forcedToLandlord = new ArrayList<>();
        if (myId != landlordId) {
            for (Rank r : publicView.getBottomCards()) {
                if (unseen.remove(r)) {
                    forcedToLandlord.add(r);
                }
            }
            if (forcedToLandlord.size() > publicView.getCardCount(landlordId)) {
                unseen.addAll(forcedToLandlord);
                forcedToLandlord.clear();
            }
        }

        List<Integer> dealOrder = new ArrayList<>(2);
        if (myId != landlordId) {
            dealOrder.add(landlordId);
        }
        for (int i = 0; i < 3; i++) {
            if (i != myId && i != landlordId) {
                dealOrder.add(i);
            }
        }

        Collections.shuffle(unseen, random);
        int unseenIndex = 0;

        for (int playerId : dealOrder) {
            Hand oppHand = hands.get(playerId);
            int needed = publicView.getCardCount(playerId);

            if (playerId == landlordId && !forcedToLandlord.isEmpty()) {
                for (Rank r : forcedToLandlord) {
                    oppHand.add(r);
                }
                needed -= forcedToLandlord.size();
            }

            for (int j = 0; j < needed; j++) {
                if (unseenIndex >= unseen.size()) {
                    throw new IllegalStateException("Unseen card pool exhausted during determinization");
                }
                oppHand.add(unseen.get(unseenIndex++));
            }
        }
        return hands;
    }

    private static GameState buildState(PublicView publicView, List<Hand> hands) {
        GameState simulatedState = new GameState(hands, publicView.getLandlordId(), publicView.getBottomCards());
        try {
            var lastMoveField = GameState.class.getDeclaredField("lastMove");
            lastMoveField.setAccessible(true);
            lastMoveField.set(simulatedState, publicView.getLastMove());

            var lastMovePlayerField = GameState.class.getDeclaredField("lastMovePlayerId");
            lastMovePlayerField.setAccessible(true);
            lastMovePlayerField.set(simulatedState, publicView.getLastMovePlayerId());

            var passCountField = GameState.class.getDeclaredField("passCount");
            passCountField.setAccessible(true);
            passCountField.set(simulatedState, publicView.getPassCount());

            var activePlayerField = GameState.class.getDeclaredField("activePlayerIndex");
            activePlayerField.setAccessible(true);
            activePlayerField.set(simulatedState, publicView.getActivePlayerId());
        } catch (Exception e) {
            throw new RuntimeException("Failed to reconstruct trick state in determinization", e);
        }
        return simulatedState;
    }
}

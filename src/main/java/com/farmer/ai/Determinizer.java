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
 * 采样后给出过牌推断似然，供上层按概率分配搜索算力：
 * 符合推断的世界优先多算，低概率世界少算但仍会跑到。
 */
public class Determinizer {

    public static GameState determinize(PublicView publicView, Random random) {
        return sample(publicView, random).state();
    }

    public record SampledWorld(GameState state, double likelihood) {
    }

    public static SampledWorld sample(PublicView publicView, Random random) {
        List<Hand> hands = sampleHands(publicView, random);
        double likelihood = PassInference.likelihood(publicView, hands);
        return new SampledWorld(buildState(publicView, hands), likelihood);
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

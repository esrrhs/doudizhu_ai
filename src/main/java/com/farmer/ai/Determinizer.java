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
 */
public class Determinizer {

    public static GameState determinize(PublicView publicView, Random random) {
        int myId = publicView.getViewingPlayerId();
        int landlordId = publicView.getLandlordId();
        List<Rank> unseen = new ArrayList<>(publicView.computeUnseenCards());

        List<Hand> hands = new ArrayList<>(3);
        hands.add(new Hand());
        hands.add(new Hand());
        hands.add(new Hand());

        // 还原自己的真实手牌
        hands.set(myId, publicView.getMyHand().copy());

        // 已知底牌中尚未打出的，必须归地主（观察者是地主时已在自己手牌中）
        List<Rank> forcedToLandlord = new ArrayList<>();
        if (myId != landlordId) {
            for (Rank r : publicView.getBottomCards()) {
                if (unseen.remove(r)) {
                    forcedToLandlord.add(r);
                }
            }
            // 公开信息自洽时：未打出底牌数 ≤ 地主剩余张数。
            // 否则（如测试桩数据）放弃强制，退回自由采样，避免污染世界。
            if (forcedToLandlord.size() > publicView.getCardCount(landlordId)) {
                unseen.addAll(forcedToLandlord);
                forcedToLandlord.clear();
            }
        }

        // 先给地主发牌（含强制底牌），再给另一名对手，避免底牌被先分走
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

        GameState simulatedState = new GameState(hands, landlordId, publicView.getBottomCards());

        // 恢复桌面状态
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

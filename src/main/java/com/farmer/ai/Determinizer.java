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
 */
public class Determinizer {

    public static GameState determinize(PublicView publicView, Random random) {
        int myId = publicView.getViewingPlayerId();
        List<Rank> unseen = new ArrayList<>(publicView.computeUnseenCards());
        Collections.shuffle(unseen, random);

        List<Hand> hands = new ArrayList<>(3);
        hands.add(new Hand());
        hands.add(new Hand());
        hands.add(new Hand());

        // 还原自己的真实手牌
        hands.set(myId, publicView.getMyHand().copy());

        // 将未知牌池分配给其余两位对手
        int unseenIndex = 0;
        for (int i = 0; i < 3; i++) {
            if (i != myId) {
                int needed = publicView.getCardCount(i);
                Hand oppHand = hands.get(i);
                for (int j = 0; j < needed; j++) {
                    if (unseenIndex < unseen.size()) {
                        oppHand.add(unseen.get(unseenIndex++));
                    }
                }
            }
        }

        GameState simulatedState = new GameState(hands, publicView.getLandlordId(), publicView.getBottomCards());

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

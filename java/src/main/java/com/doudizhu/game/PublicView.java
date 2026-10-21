package com.doudizhu.game;

import com.doudizhu.model.Hand;
import com.doudizhu.model.Move;
import com.doudizhu.model.Rank;
import com.doudizhu.model.Role;
import com.doudizhu.rules.Deck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 斗地主玩家观察视角 (不完全信息，严格隔离对手私有手牌)
 */
public class PublicView {
    private final int viewingPlayerId;
    private final Role viewingRole;
    private final int landlordId;
    private final List<Rank> bottomCards;
    private final Hand myHand;
    private final int[] cardCounts;
    private final List<Move> moveHistory;
    private final Move lastMove;
    private final int lastMovePlayerId;
    private final int activePlayerId;
    private final int passCount;

    public PublicView(int viewingPlayerId, Role viewingRole, int landlordId, List<Rank> bottomCards,
                      Hand myHand, int[] cardCounts, List<Move> moveHistory,
                      Move lastMove, int lastMovePlayerId, int activePlayerId, int passCount) {
        this.viewingPlayerId = viewingPlayerId;
        this.viewingRole = viewingRole;
        this.landlordId = landlordId;
        this.bottomCards = Collections.unmodifiableList(new ArrayList<>(bottomCards));
        this.myHand = myHand;
        this.cardCounts = cardCounts.clone();
        this.moveHistory = Collections.unmodifiableList(moveHistory);
        this.lastMove = lastMove;
        this.lastMovePlayerId = lastMovePlayerId;
        this.activePlayerId = activePlayerId;
        this.passCount = passCount;
    }

    public int getViewingPlayerId() {
        return viewingPlayerId;
    }

    public Role getViewingRole() {
        return viewingRole;
    }

    public int getLandlordId() {
        return landlordId;
    }

    public List<Rank> getBottomCards() {
        return bottomCards;
    }

    public Hand getMyHand() {
        return myHand;
    }

    public int getCardCount(int playerId) {
        return cardCounts[playerId];
    }

    public int[] getCardCounts() {
        return cardCounts.clone();
    }

    public List<Move> getMoveHistory() {
        return moveHistory;
    }

    public Move getLastMove() {
        return lastMove;
    }

    public int getLastMovePlayerId() {
        return lastMovePlayerId;
    }

    public int getActivePlayerId() {
        return activePlayerId;
    }

    public int getPassCount() {
        return passCount;
    }

    /**
     * 计算所有对手当前可能持有的未知牌池
     * 未知牌池 = 54张初始牌库 - 我的私有手牌 - 场上已打出的所有历史牌
     */
    public List<Rank> computeUnseenCards() {
        List<Rank> deck = Deck.createStandard54Cards();

        // 移除自己手里的牌
        for (Rank r : myHand.getCards()) {
            deck.remove(r);
        }

        // 移除历史上已经打出的牌
        for (Move m : moveHistory) {
            if (!m.isPass()) {
                for (Rank r : m.getCards()) {
                    deck.remove(r);
                }
            }
        }

        return deck;
    }
}

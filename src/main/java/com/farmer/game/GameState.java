package com.farmer.game;

import com.farmer.model.Hand;
import com.farmer.model.Move;
import com.farmer.model.Rank;
import com.farmer.model.Role;
import com.farmer.rules.MoveGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 斗地主牌局状态机 (支持深拷贝与 2v1 阵营胜负结算)
 */
public class GameState {
    private final Player[] players = new Player[3];
    private final int landlordId;
    private final List<Rank> bottomCards;
    private int activePlayerIndex;
    private Move lastMove;
    private int lastMovePlayerId;
    private int passCount;
    private boolean isGameOver;
    private int winnerId = -1;
    private Role winningRole = null;
    private final List<Move> moveHistory;

    public GameState(List<Hand> hands, int landlordId, List<Rank> bottomCards) {
        if (hands.size() != 3) {
            throw new IllegalArgumentException("GameState requires exactly 3 hands");
        }
        this.landlordId = landlordId;
        this.bottomCards = Collections.unmodifiableList(new ArrayList<>(bottomCards));
        for (int i = 0; i < 3; i++) {
            Role role = (i == landlordId) ? Role.LANDLORD : Role.FARMER;
            String name = (i == landlordId) ? "地主" : ("农民-" + i);
            this.players[i] = new Player(i, name, role, hands.get(i).copy(), true);
        }
        this.activePlayerIndex = landlordId; // 斗地主由地主先手出牌
        this.lastMove = null;
        this.lastMovePlayerId = -1;
        this.passCount = 0;
        this.isGameOver = false;
        this.moveHistory = new ArrayList<>();
    }

    private GameState(GameState other) {
        for (int i = 0; i < 3; i++) {
            this.players[i] = other.players[i].copy();
        }
        this.landlordId = other.landlordId;
        this.bottomCards = other.bottomCards;
        this.activePlayerIndex = other.activePlayerIndex;
        this.lastMove = other.lastMove;
        this.lastMovePlayerId = other.lastMovePlayerId;
        this.passCount = other.passCount;
        this.isGameOver = other.isGameOver;
        this.winnerId = other.winnerId;
        this.winningRole = other.winningRole;
        this.moveHistory = new ArrayList<>(other.moveHistory);
    }

    public GameState copy() {
        return new GameState(this);
    }

    public List<Move> getLegalMoves() {
        if (isGameOver) {
            return List.of();
        }
        Player current = players[activePlayerIndex];
        return MoveGenerator.generateLegalMoves(current.getHand(), lastMove, activePlayerIndex);
    }

    /**
     * 执行一个动作并推进游戏状态
     */
    public void applyMove(Move move) {
        if (isGameOver) {
            throw new IllegalStateException("Game is already over");
        }

        Player current = players[activePlayerIndex];
        current.getHand().play(move);
        moveHistory.add(move);

        // 检查终局
        if (current.hasWon()) {
            isGameOver = true;
            winnerId = current.getId();
            winningRole = current.getRole();
            return;
        }

        if (move.isPass()) {
            passCount++;
            if (passCount == 2) {
                // 两家都过牌，本轮结束，由上一轮最大出牌者重新出牌 (Lead)
                activePlayerIndex = lastMovePlayerId;
                lastMove = null;
                lastMovePlayerId = -1;
                passCount = 0;
            } else {
                activePlayerIndex = (activePlayerIndex + 1) % 3;
            }
        } else {
            lastMove = move;
            lastMovePlayerId = current.getId();
            passCount = 0;
            activePlayerIndex = (activePlayerIndex + 1) % 3;
        }
    }

    /**
     * 判断指定玩家是否属于获胜方 (斗地主中任意农民出完手牌，两位农民均获胜)
     */
    public boolean isPlayerWinner(int playerId) {
        if (!isGameOver || winningRole == null) {
            return false;
        }
        if (winningRole.isLandlord()) {
            return playerId == landlordId;
        } else {
            return playerId != landlordId; // 两位农民同时算赢
        }
    }

    public Player[] getPlayers() {
        return players;
    }

    public Player getPlayer(int id) {
        return players[id];
    }

    public int getLandlordId() {
        return landlordId;
    }

    public List<Rank> getBottomCards() {
        return bottomCards;
    }

    public Player getActivePlayer() {
        return players[activePlayerIndex];
    }

    public int getActivePlayerIndex() {
        return activePlayerIndex;
    }

    public Move getLastMove() {
        return lastMove;
    }

    public int getLastMovePlayerId() {
        return lastMovePlayerId;
    }

    public int getPassCount() {
        return passCount;
    }

    public boolean isGameOver() {
        return isGameOver;
    }

    public int getWinnerId() {
        return winnerId;
    }

    public Role getWinningRole() {
        return winningRole;
    }

    public List<Move> getMoveHistory() {
        return moveHistory;
    }

    public PublicView getPublicView(int viewingPlayerId) {
        int[] counts = new int[3];
        for (int i = 0; i < 3; i++) {
            counts[i] = players[i].getCardCount();
        }
        return new PublicView(
                viewingPlayerId,
                players[viewingPlayerId].getRole(),
                landlordId,
                bottomCards,
                players[viewingPlayerId].getHand().copy(),
                counts,
                new ArrayList<>(moveHistory),
                lastMove,
                lastMovePlayerId,
                activePlayerIndex,
                passCount
        );
    }
}

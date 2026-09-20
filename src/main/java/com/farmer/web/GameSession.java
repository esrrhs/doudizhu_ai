package com.farmer.web;

import com.farmer.ai.PimcAiPlayer;
import com.farmer.game.GameState;
import com.farmer.game.PublicView;
import com.farmer.model.Hand;
import com.farmer.model.Move;
import com.farmer.model.Rank;
import com.farmer.model.Role;
import com.farmer.rules.Deck;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 斗地主网页端游戏会话 (支持真人与 AI 协同对抗)
 */
public class GameSession {
    private GameState gameState;
    private final PimcAiPlayer aiPlayer1;
    private final PimcAiPlayer aiPlayer2;
    private final PimcAiPlayer hintAi;
    private final Map<Integer, PimcAiPlayer.DecisionResult> lastAiThoughts = new HashMap<>();
    private final List<String> eventLogs = new ArrayList<>();
    private final Move[] playerLastActions = new Move[3];
    private final Random random = new Random();

    public GameSession() {
        this.aiPlayer1 = new PimcAiPlayer(20, 120, random);
        this.aiPlayer2 = new PimcAiPlayer(20, 120, random);
        this.hintAi = new PimcAiPlayer(15, 100, random);
        newGame(0); // 默认玩家 0 为地主
    }

    public synchronized void newGame(int landlordId) {
        Deck.DealResult dealResult = Deck.deal(random, landlordId);
        this.gameState = new GameState(dealResult.playerHands(), landlordId, dealResult.bottomCards());
        this.lastAiThoughts.clear();
        this.eventLogs.clear();
        Arrays.fill(this.playerLastActions, null);

        String landlordName = (landlordId == 0) ? "玩家 P0 (真人)" : ("AI 玩家 P" + landlordId);
        addLog(String.format("🎲 新局开始！%s 成为地主，获得 3 张底牌: %s",
                landlordName, formatCards(dealResult.bottomCards())));
    }

    public synchronized GameState getGameState() {
        return gameState;
    }

    public synchronized List<String> getEventLogs() {
        return Collections.unmodifiableList(eventLogs);
    }

    public synchronized Map<Integer, PimcAiPlayer.DecisionResult> getLastAiThoughts() {
        return Collections.unmodifiableMap(lastAiThoughts);
    }

    public synchronized Move[] getPlayerLastActions() {
        return playerLastActions.clone();
    }

    public synchronized void addLog(String log) {
        eventLogs.add(log);
        if (eventLogs.size() > 100) {
            eventLogs.remove(0);
        }
    }

    public synchronized String humanPlay(List<String> cardSymbols) {
        if (gameState.isGameOver()) {
            return "游戏已结束，请重新开局";
        }
        if (gameState.getActivePlayerIndex() != 0) {
            return "当前不是你的回合，请等待 AI 行动";
        }

        List<Move> legalMoves = gameState.getLegalMoves();
        List<Rank> targetCards = new ArrayList<>();
        for (String s : cardSymbols) {
            try {
                targetCards.add(Rank.fromSymbol(s));
            } catch (Exception e) {
                return "无效的卡牌点数: " + s;
            }
        }
        targetCards.sort((a, b) -> Integer.compare(a.getValue(), b.getValue()));

        Move matchedMove = null;
        for (Move m : legalMoves) {
            if (!m.isPass() && m.getCards().equals(targetCards)) {
                matchedMove = m;
                break;
            }
        }

        if (matchedMove == null) {
            return "不符合斗地主规则或压不过上家牌！请重新选牌或使用 AI 提示。";
        }

        if (gameState.getLastMove() == null || gameState.getLastMove().isPass()) {
            Arrays.fill(playerLastActions, null);
        }

        playerLastActions[0] = matchedMove;
        gameState.applyMove(matchedMove);
        addLog(String.format("👉 玩家 P0 (%s) 出牌: %s (%s)",
                gameState.getPlayer(0).getRole().getDescription(),
                matchedMove.toCardString(), matchedMove.getType().getDescription()));

        if (gameState.isGameOver()) {
            announceWinner();
        }
        return null;
    }

    public synchronized String humanPass() {
        if (gameState.isGameOver()) {
            return "游戏已结束，请重新开局";
        }
        if (gameState.getActivePlayerIndex() != 0) {
            return "当前不是你的回合";
        }

        List<Move> legalMoves = gameState.getLegalMoves();
        Move passMove = legalMoves.stream().filter(Move::isPass).findFirst().orElse(null);
        if (passMove == null) {
            return "当前无人出牌，您必须主动出牌，不能过牌！";
        }

        playerLastActions[0] = passMove;
        gameState.applyMove(passMove);
        addLog(String.format("👉 玩家 P0 (%s) 选择了【不出/过牌】。", gameState.getPlayer(0).getRole().getDescription()));
        return null;
    }

    public synchronized String aiStep() {
        if (gameState.isGameOver()) {
            return "游戏已结束";
        }
        int activeId = gameState.getActivePlayerIndex();
        if (activeId == 0) {
            return "轮到真人行动";
        }

        if (gameState.getLastMove() == null || gameState.getLastMove().isPass()) {
            Arrays.fill(playerLastActions, null);
        }

        PublicView view = gameState.getPublicView(activeId);
        PimcAiPlayer ai = (activeId == 1) ? aiPlayer1 : aiPlayer2;

        PimcAiPlayer.DecisionResult result = ai.decide(view);
        Move chosen = result.getSelectedMove();
        lastAiThoughts.put(activeId, result);

        playerLastActions[activeId] = chosen;
        gameState.applyMove(chosen);

        if (chosen.isPass()) {
            addLog(String.format("🤖 玩家 P%d (%s) 选择了【不出/过牌】",
                    activeId, gameState.getPlayer(activeId).getRole().getDescription()));
        } else {
            addLog(String.format("🤖 玩家 P%d (%s) 出牌: %s (%s)",
                    activeId, gameState.getPlayer(activeId).getRole().getDescription(),
                    chosen.toCardString(), chosen.getType().getDescription()));
        }

        if (gameState.isGameOver()) {
            announceWinner();
        }

        return chosen.toCardString();
    }

    public synchronized Move getHumanHint() {
        if (gameState.isGameOver() || gameState.getActivePlayerIndex() != 0) {
            return null;
        }
        PublicView view = gameState.getPublicView(0);
        PimcAiPlayer.DecisionResult result = hintAi.decide(view);
        return result.getSelectedMove();
    }

    private void announceWinner() {
        Role winRole = gameState.getWinningRole();
        if (winRole.isLandlord()) {
            addLog(String.format("🏆 地主 (玩家 P%d) 率先出完手牌，地主阵营获胜！", gameState.getLandlordId()));
        } else {
            addLog("🌾 农民阵营通力配合，率先走完手牌，农民阵营获胜！");
        }
    }

    private String formatCards(List<Rank> cards) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            sb.append(cards.get(i).getSymbol());
            if (i < cards.size() - 1) sb.append(",");
        }
        return sb.toString();
    }
}

package com.farmer.web;

import com.farmer.ai.BidEvaluator;
import com.farmer.ai.PimcAiPlayer;
import com.farmer.game.GameState;
import com.farmer.game.PublicView;
import com.farmer.model.CardType;
import com.farmer.model.Hand;
import com.farmer.model.Move;
import com.farmer.model.Rank;
import com.farmer.model.Role;
import com.farmer.rules.Deck;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 斗地主网页端游戏会话 (支持叫地主阶段与 PIMC 2v1 实战对弈)
 */
public class GameSession {

    public enum Stage {
        BIDDING,
        PLAYING,
        GAME_OVER
    }

    private Stage stage;
    private int currentBidder;
    private int bidStartPlayer;
    private int bidsCount;
    private final String[] bidActions = new String[3];
    private final Map<Integer, BidEvaluator.BidResult> lastBidThoughts = new HashMap<>();

    private final Hand[] initialHands = new Hand[3];
    private List<Rank> bottomCards = new ArrayList<>(3);
    private boolean bottomCardsRevealed = false;
    private int landlordId = -1;

    private GameState gameState;
    private final PimcAiPlayer aiPlayer1;
    private final PimcAiPlayer aiPlayer2;
    private final Map<Integer, PimcAiPlayer.DecisionResult> lastAiThoughts = new HashMap<>();
    private final List<String> eventLogs = new ArrayList<>();
    private final Move[] playerLastActions = new Move[3];
    private final Random random = new Random();

    public GameSession() {
        this.aiPlayer1 = new PimcAiPlayer(200, 1500, random);
        this.aiPlayer2 = new PimcAiPlayer(200, 1500, random);
        // 默认进入互动式叫地主流程 (随机首叫玩家)
        startBiddingGame(-1);
    }

    /**
     * 开启支持【叫地主】环节的标准对局
     */
    public synchronized void startBiddingGame(int startBidder) {
        this.stage = Stage.BIDDING;
        this.landlordId = -1;
        this.bottomCardsRevealed = false;
        this.bidsCount = 0;
        Arrays.fill(this.bidActions, null);
        Arrays.fill(this.playerLastActions, null);
        this.lastAiThoughts.clear();
        this.lastBidThoughts.clear();
        this.eventLogs.clear();

        // 54 张牌洗牌发牌
        List<Rank> deck = Deck.createStandard54Cards();
        Collections.shuffle(deck, random);

        for (int i = 0; i < 3; i++) {
            initialHands[i] = new Hand();
            for (int j = 0; j < Deck.FARMER_CARDS_COUNT; j++) {
                initialHands[i].add(deck.get(i * Deck.FARMER_CARDS_COUNT + j));
            }
        }

        bottomCards = new ArrayList<>(3);
        for (int i = 51; i < 54; i++) {
            bottomCards.add(deck.get(i));
        }

        this.currentBidder = (startBidder >= 0 && startBidder <= 2) ? startBidder : random.nextInt(3);
        this.bidStartPlayer = this.currentBidder;
        this.gameState = null;

        String starterName = (currentBidder == 0) ? "玩家 P0 (真人)" : ("AI 玩家 P" + currentBidder);
        addLog(String.format("🎲 新局发牌完毕（各 17 张），进入【叫地主】阶段！由 %s 首先表态。", starterName));
    }

    /**
     * 直接指定地主开局 (快捷模式)
     */
    public synchronized void newGame(int targetLandlordId) {
        Deck.DealResult dealResult = Deck.deal(random, targetLandlordId);
        for (int i = 0; i < 3; i++) {
            initialHands[i] = dealResult.playerHands().get(i);
        }
        this.bottomCards = dealResult.bottomCards();
        this.landlordId = targetLandlordId;
        this.bottomCardsRevealed = true;
        this.stage = Stage.PLAYING;
        this.gameState = new GameState(dealResult.playerHands(), targetLandlordId, dealResult.bottomCards());
        this.lastAiThoughts.clear();
        this.lastBidThoughts.clear();
        this.eventLogs.clear();
        Arrays.fill(this.bidActions, null);
        Arrays.fill(this.playerLastActions, null);

        String landlordName = (targetLandlordId == 0) ? "玩家 P0 (真人)" : ("AI 玩家 P" + targetLandlordId);
        addLog(String.format("🎲 快速开局！%s 成为地主，翻开 3 张底牌: %s",
                landlordName, formatCards(dealResult.bottomCards())));
    }

    /**
     * 真人表态叫地主 / 不叫
     */
    public synchronized String humanBid(boolean call) {
        if (stage != Stage.BIDDING) {
            return "当前不在叫地主阶段";
        }
        if (currentBidder != 0) {
            return "当前不是你的叫牌回合";
        }

        if (call) {
            bidActions[0] = "叫地主";
            addLog("👉 玩家 P0 (真人) 选择了【叫地主】！");
            finalizeLandlord(0);
        } else {
            bidActions[0] = "不叫";
            addLog("👉 玩家 P0 (真人) 选择了【不叫】。");
            advanceBidder();
        }
        return null;
    }

    /**
     * AI 玩家表态叫地主 / 不叫 (基于思路二：底牌蒙特卡洛采样推演胜率)
     */
    public synchronized String aiBidStep() {
        if (stage != Stage.BIDDING) {
            return "当前不在叫地主阶段";
        }
        if (currentBidder == 0) {
            return "当前轮到真人叫牌";
        }

        int activeId = currentBidder;
        Hand myHand = initialHands[activeId];

        // 采用思路二 + MCTS 深度推演：60 个底牌假想世界 x 80 次 MCTS 搜索迭代
        BidEvaluator.BidResult result = BidEvaluator.evaluate(myHand, 60, 80, 0.50, random);
        lastBidThoughts.put(activeId, result);

        if (result.shouldCall()) {
            bidActions[activeId] = "叫地主";
            addLog(String.format("🤖 玩家 P%d (AI) [MCTS推演胜率 %.1f%% | 硬牌力分 %d] 决定【叫地主】！",
                    activeId, result.winRate() * 100, result.controlScore()));
            finalizeLandlord(activeId);
            return "叫地主";
        } else {
            bidActions[activeId] = "不叫";
            addLog(String.format("🤖 玩家 P%d (AI) [MCTS推演胜率 %.1f%% | 硬牌力分 %d] 决定【不叫】。",
                    activeId, result.winRate() * 100, result.controlScore()));
            advanceBidder();
            return "不叫";
        }
    }

    private void advanceBidder() {
        bidsCount++;
        if (bidsCount >= 3) {
            // 三家都不叫，流局重新发牌
            addLog("⚠️ 三位玩家均选择【不叫】，流局！重新洗牌发牌...");
            startBiddingGame((bidStartPlayer + 1) % 3);
        } else {
            currentBidder = (currentBidder + 1) % 3;
        }
    }

    private void finalizeLandlord(int chosenLandlordId) {
        this.landlordId = chosenLandlordId;
        this.bottomCardsRevealed = true;

        // 底牌归地主所有 (手牌增至 20 张)
        for (Rank r : bottomCards) {
            initialHands[chosenLandlordId].add(r);
        }

        List<Hand> finalHands = List.of(
                initialHands[0].copy(),
                initialHands[1].copy(),
                initialHands[2].copy()
        );

        this.gameState = new GameState(finalHands, chosenLandlordId, bottomCards);
        this.stage = Stage.PLAYING;

        String landlordName = (chosenLandlordId == 0) ? "玩家 P0 (真人)" : ("AI 玩家 P" + chosenLandlordId);
        addLog(String.format("👑 %s 成为地主！获得 3 张底牌: %s，手牌增至 20 张并先手出牌！",
                landlordName, formatCards(bottomCards)));
    }

    public synchronized Stage getStage() {
        if (stage == Stage.PLAYING && gameState != null && gameState.isGameOver()) {
            return Stage.GAME_OVER;
        }
        return stage;
    }

    public synchronized int getCurrentBidder() {
        return currentBidder;
    }

    public synchronized String[] getBidActions() {
        return bidActions.clone();
    }

    public synchronized Map<Integer, BidEvaluator.BidResult> getLastBidThoughts() {
        return Collections.unmodifiableMap(lastBidThoughts);
    }

    public synchronized boolean isBottomCardsRevealed() {
        return bottomCardsRevealed;
    }

    public synchronized List<Rank> getBottomCards() {
        return Collections.unmodifiableList(bottomCards);
    }

    public synchronized Hand getPlayerHand(int playerId) {
        if (stage == Stage.PLAYING && gameState != null) {
            return gameState.getPlayer(playerId).getHand();
        }
        return initialHands[playerId];
    }

    public synchronized int getLandlordId() {
        return landlordId;
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
        if (stage == Stage.BIDDING) {
            return "当前仍在叫地主阶段，尚未确定地主";
        }
        if (gameState == null || gameState.isGameOver()) {
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
        if (stage == Stage.BIDDING) {
            return "当前仍在叫地主阶段";
        }
        if (gameState == null || gameState.isGameOver()) {
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
        if (stage == Stage.BIDDING) {
            return aiBidStep();
        }
        if (gameState == null || gameState.isGameOver()) {
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
        if (stage != Stage.PLAYING || gameState == null || gameState.isGameOver() || gameState.getActivePlayerIndex() != 0) {
            return null;
        }
        List<Move> legalMoves = gameState.getLegalMoves();
        if (legalMoves.isEmpty()) {
            return null;
        }

        Move lastMove = gameState.getLastMove();
        boolean isLead = (lastMove == null || lastMove.isPass());

        if (isLead) {
            // 主动出牌：快速找出合适的起手牌型（长结构牌型或散牌）
            List<Move> nonBombs = new ArrayList<>();
            List<Move> bombs = new ArrayList<>();
            for (Move m : legalMoves) {
                if (m.isBomb() || m.isRocket()) {
                    bombs.add(m);
                } else if (!m.isPass()) {
                    nonBombs.add(m);
                }
            }

            if (!nonBombs.isEmpty()) {
                // 优先长结构牌（顺子、连对、飞机、三带一/二）
                List<Move> structures = nonBombs.stream()
                        .filter(m -> m.getType() == CardType.STRAIGHT
                                || m.getType() == CardType.CONSECUTIVE_PAIRS
                                || m.getType() == CardType.AIRPLANE
                                || m.getType() == CardType.AIRPLANE_PLUS_SINGLES
                                || m.getType() == CardType.AIRPLANE_PLUS_PAIRS
                                || m.getType() == CardType.TRIPLE_PLUS_ONE
                                || m.getType() == CardType.TRIPLE_PLUS_PAIR)
                        .sorted(Comparator.comparingInt(Move::getMainRank))
                        .toList();
                if (!structures.isEmpty()) {
                    return structures.get(0);
                }

                // 三张
                List<Move> trios = nonBombs.stream()
                        .filter(m -> m.getType() == CardType.TRIPLE)
                        .sorted(Comparator.comparingInt(Move::getMainRank))
                        .toList();
                if (!trios.isEmpty()) {
                    return trios.get(0);
                }

                // 低点数散牌 (保留 2 和王)
                List<Move> smallMoves = nonBombs.stream()
                        .filter(m -> m.getMainRank() < Rank.TWO.getValue())
                        .sorted(Comparator.comparingInt(Move::getMainRank))
                        .toList();
                if (!smallMoves.isEmpty()) {
                    return smallMoves.get(0);
                }

                nonBombs.sort(Comparator.comparingInt(Move::getMainRank));
                return nonBombs.get(0);
            } else if (!bombs.isEmpty()) {
                bombs.sort(Comparator.comparingInt(Move::getMainRank));
                return bombs.get(0);
            }
            return legalMoves.get(0);
        } else {
            // 被动接牌：快速寻找能接得上的最小合法牌
            List<Move> beaters = new ArrayList<>();
            List<Move> bombs = new ArrayList<>();
            Move passMove = null;

            for (Move m : legalMoves) {
                if (m.isPass()) {
                    passMove = m;
                } else if (m.isBomb() || m.isRocket()) {
                    bombs.add(m);
                } else {
                    beaters.add(m);
                }
            }

            if (!beaters.isEmpty()) {
                beaters.sort(Comparator.comparingInt(Move::getMainRank));
                return beaters.get(0);
            }

            // 无普通牌可压，若有炸弹
            if (!bombs.isEmpty()) {
                bombs.sort(Comparator.comparingInt(Move::getMainRank));
                return bombs.get(0);
            }

            return (passMove != null) ? passMove : legalMoves.get(0);
        }
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

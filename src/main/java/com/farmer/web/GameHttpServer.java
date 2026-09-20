package com.farmer.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.farmer.ai.BidEvaluator;
import com.farmer.ai.PimcAiPlayer;
import com.farmer.game.GameState;
import com.farmer.model.Move;
import com.farmer.model.Rank;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 嵌入式 HTTP 服务器 (支持多 Session 并发隔离、叫地主交互、REST API 与 Web 静态页面)
 */
public class GameHttpServer {
    private final int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    private static class SessionContainer {
        final GameSession session;
        volatile long lastAccessTime;

        SessionContainer(GameSession session) {
            this.session = session;
            this.lastAccessTime = System.currentTimeMillis();
        }

        void touch() {
            this.lastAccessTime = System.currentTimeMillis();
        }
    }

    private final ConcurrentHashMap<String, SessionContainer> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sessionCleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Session-Cleaner");
        t.setDaemon(true);
        return t;
    });

    public GameHttpServer(int port) {
        this.port = port;
        this.sessionCleaner.scheduleAtFixedRate(this::cleanExpiredSessions, 10, 10, TimeUnit.MINUTES);
    }

    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        long expireTime = 3600_000;
        sessions.entrySet().removeIf(entry -> (now - entry.getValue().lastAccessTime) > expireTime);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/game/state", this::handleState);
        server.createContext("/api/game/new", this::handleNewGame);
        server.createContext("/api/game/bid", this::handleBid);
        server.createContext("/api/game/play", this::handlePlay);
        server.createContext("/api/game/pass", this::handlePass);
        server.createContext("/api/game/ai-step", this::handleAiStep);
        server.createContext("/api/game/hint", this::handleHint);

        server.createContext("/", this::handleStatic);

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("===============================================================");
        System.out.println(" 🌐 斗地主 AI 网页客户端已启动！(支持叫地主与多用户并发对局)");
        System.out.printf(" 🎮 请在浏览器中打开: http://localhost:%d\n", port);
        System.out.println("===============================================================");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        sessionCleaner.shutdown();
    }

    private String getOrInitSessionId(HttpExchange exchange) {
        String sessionId = exchange.getRequestHeaders().getFirst("X-Session-Id");
        if (sessionId == null || sessionId.isBlank()) {
            URI uri = exchange.getRequestURI();
            String query = uri.getQuery();
            if (query != null && query.contains("sessionId=")) {
                for (String param : query.split("&")) {
                    if (param.startsWith("sessionId=")) {
                        sessionId = param.substring("sessionId=".length());
                        break;
                    }
                }
            }
        }
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "");
        }
        exchange.getResponseHeaders().set("X-Session-Id", sessionId);
        return sessionId;
    }

    private GameSession getSession(HttpExchange exchange) {
        String sessionId = getOrInitSessionId(exchange);
        SessionContainer container = sessions.computeIfAbsent(sessionId, id -> new SessionContainer(new GameSession()));
        container.touch();
        return container.session;
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.isBlank()) {
            path = "/index.html";
        }

        String resourcePath = "static" + path;
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            sendResponse(exchange, 404, "Not Found", "text/plain");
            return;
        }

        byte[] bytes = is.readAllBytes();
        String contentType = "text/html; charset=utf-8";
        if (path.endsWith(".css")) {
            contentType = "text/css; charset=utf-8";
        } else if (path.endsWith(".js")) {
            contentType = "application/javascript; charset=utf-8";
        }
        sendResponse(exchange, 200, bytes, contentType);
    }

    private void handleState(HttpExchange exchange) throws IOException {
        GameSession session = getSession(exchange);
        sendJson(exchange, 200, buildStateDto(session, null));
    }

    private void handleNewGame(HttpExchange exchange) throws IOException {
        GameSession session = getSession(exchange);
        String query = exchange.getRequestURI().getQuery();
        boolean isDirect = false;
        int targetLandlord = -1;

        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("mode=direct")) {
                    isDirect = true;
                } else if (param.startsWith("landlordId=")) {
                    try {
                        targetLandlord = Integer.parseInt(param.substring("landlordId=".length()));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (isDirect && targetLandlord >= 0 && targetLandlord <= 2) {
            session.newGame(targetLandlord);
            sendJson(exchange, 200, buildStateDto(session, "快捷新对局已就绪！"));
        } else {
            session.startBiddingGame(-1);
            sendJson(exchange, 200, buildStateDto(session, "新对局已发牌，进入叫地主阶段！"));
        }
    }

    private void handleBid(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }

        GameSession session = getSession(exchange);
        InputStream is = exchange.getRequestBody();
        Map<?, ?> body = mapper.readValue(is, Map.class);
        boolean call = Boolean.TRUE.equals(body.get("call"));

        String error = session.humanBid(call);
        if (error != null) {
            Map<String, Object> resp = buildStateDto(session, null);
            resp.put("error", error);
            sendJson(exchange, 400, resp);
        } else {
            sendJson(exchange, 200, buildStateDto(session, call ? "你叫了地主！" : "你选择不叫"));
        }
    }

    private void handlePlay(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed", "text/plain");
            return;
        }

        GameSession session = getSession(exchange);
        InputStream is = exchange.getRequestBody();
        Map<?, ?> body = mapper.readValue(is, Map.class);
        List<?> cardsRaw = (List<?>) body.get("cards");
        List<String> cards = new ArrayList<>();
        if (cardsRaw != null) {
            for (Object o : cardsRaw) {
                cards.add(o.toString());
            }
        }

        String error = session.humanPlay(cards);
        if (error != null) {
            Map<String, Object> resp = buildStateDto(session, null);
            resp.put("error", error);
            sendJson(exchange, 400, resp);
        } else {
            sendJson(exchange, 200, buildStateDto(session, "出牌成功！"));
        }
    }

    private void handlePass(HttpExchange exchange) throws IOException {
        GameSession session = getSession(exchange);
        String error = session.humanPass();
        if (error != null) {
            Map<String, Object> resp = buildStateDto(session, null);
            resp.put("error", error);
            sendJson(exchange, 400, resp);
        } else {
            sendJson(exchange, 200, buildStateDto(session, "已过牌"));
        }
    }

    private void handleAiStep(HttpExchange exchange) throws IOException {
        GameSession session = getSession(exchange);
        String actionStr = session.aiStep();
        sendJson(exchange, 200, buildStateDto(session, "AI 决策: " + actionStr));
    }

    private void handleHint(HttpExchange exchange) throws IOException {
        GameSession session = getSession(exchange);
        Move hint = session.getHumanHint();
        Map<String, Object> resp = new HashMap<>();
        if (hint == null) {
            resp.put("hint", null);
        } else {
            List<String> cards = new ArrayList<>();
            for (Rank r : hint.getCards()) {
                cards.add(r.getSymbol());
            }
            resp.put("hint", cards);
            resp.put("isPass", hint.isPass());
            resp.put("type", hint.getType().getDescription());
        }
        sendJson(exchange, 200, resp);
    }

    private Map<String, Object> buildStateDto(GameSession session, String message) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("message", message);
        dto.put("stage", session.getStage().name());
        dto.put("currentBidder", session.getCurrentBidder());
        List<String> bidActionList = new ArrayList<>();
        for (String a : session.getBidActions()) {
            bidActionList.add(a);
        }
        dto.put("bidActions", bidActionList);
        dto.put("landlordId", session.getLandlordId());
        dto.put("bottomCardsRevealed", session.isBottomCardsRevealed());

        // 3张底牌
        List<String> bottomCards = new ArrayList<>();
        if (session.isBottomCardsRevealed()) {
            for (Rank r : session.getBottomCards()) {
                bottomCards.add(r.getSymbol());
            }
        }
        dto.put("bottomCards", bottomCards);

        // 各家剩余牌数
        int[] counts = new int[3];
        for (int i = 0; i < 3; i++) {
            counts[i] = session.getPlayerHand(i).getTotalCards();
        }
        dto.put("cardCounts", counts);

        // 玩家 0 (真人) 的手牌
        List<String> humanHand = new ArrayList<>();
        for (Rank r : session.getPlayerHand(0).getCards()) {
            humanHand.add(r.getSymbol());
        }
        dto.put("humanHand", humanHand);

        // 各家身份角色
        String[] roles = new String[3];
        if (session.getStage() == GameSession.Stage.BIDDING) {
            for (int i = 0; i < 3; i++) {
                roles[i] = "待定";
            }
        } else if (session.getGameState() != null) {
            for (int i = 0; i < 3; i++) {
                roles[i] = session.getGameState().getPlayer(i).getRole().getDescription();
            }
        }
        dto.put("roles", roles);

        // 出牌对战阶段的信息
        GameState state = session.getGameState();
        if (state != null) {
            dto.put("activePlayer", state.getActivePlayerIndex());
            dto.put("isGameOver", state.isGameOver());
            dto.put("winner", state.getWinnerId());
            dto.put("winningRole", state.getWinningRole() != null ? state.getWinningRole().getDescription() : null);
            dto.put("isHumanWinner", state.isPlayerWinner(0));

            // 桌面最新出牌
            Move lastMove = state.getLastMove();
            if (lastMove != null && !lastMove.isPass()) {
                Map<String, Object> lastMoveMap = new HashMap<>();
                lastMoveMap.put("playerId", state.getLastMovePlayerId());
                lastMoveMap.put("type", lastMove.getType().getDescription());
                List<String> cards = new ArrayList<>();
                for (Rank r : lastMove.getCards()) {
                    cards.add(r.getSymbol());
                }
                lastMoveMap.put("cards", cards);
                dto.put("lastMove", lastMoveMap);
            } else {
                dto.put("lastMove", null);
            }

            // 真人是否可以不出
            List<Move> legalMoves = state.getLegalMoves();
            boolean canPass = legalMoves.stream().anyMatch(Move::isPass);
            dto.put("canPass", canPass);

            // 记牌器 (3..17，包括大王小王)
            Map<String, Integer> playedStats = new LinkedHashMap<>();
            for (int v = 3; v <= 17; v++) {
                playedStats.put(Rank.fromValue(v).getSymbol(), 0);
            }
            for (Move m : state.getMoveHistory()) {
                if (!m.isPass()) {
                    for (Rank r : m.getCards()) {
                        playedStats.put(r.getSymbol(), playedStats.get(r.getSymbol()) + 1);
                    }
                }
            }
            dto.put("playedStats", playedStats);
        } else {
            dto.put("activePlayer", session.getCurrentBidder());
            dto.put("isGameOver", false);
            dto.put("winner", -1);
            dto.put("winningRole", null);
            dto.put("isHumanWinner", false);
            dto.put("lastMove", null);
            dto.put("canPass", false);

            Map<String, Integer> playedStats = new LinkedHashMap<>();
            for (int v = 3; v <= 17; v++) {
                playedStats.put(Rank.fromValue(v).getSymbol(), 0);
            }
            dto.put("playedStats", playedStats);
        }

        // 3位玩家各自面前展示的最新动作 (出牌或不出/PASS)
        List<Map<String, Object>> playerActions = new ArrayList<>();
        Move[] actions = session.getPlayerLastActions();
        for (int i = 0; i < 3; i++) {
            Move m = actions[i];
            if (m == null) {
                playerActions.add(null);
            } else {
                Map<String, Object> act = new HashMap<>();
                act.put("playerId", i);
                act.put("isPass", m.isPass());
                act.put("type", m.getType().getDescription());
                List<String> cards = new ArrayList<>();
                for (Rank r : m.getCards()) {
                    cards.add(r.getSymbol());
                }
                act.put("cards", cards);
                playerActions.add(act);
            }
        }
        dto.put("playerActions", playerActions);

        // AI 思考推演信息
        Map<String, Object> aiThoughtsDto = new HashMap<>();

        // 叫牌思考雷达
        if (session.getStage() == GameSession.Stage.BIDDING) {
            for (Map.Entry<Integer, BidEvaluator.BidResult> e : session.getLastBidThoughts().entrySet()) {
                BidEvaluator.BidResult res = e.getValue();
                Map<String, Object> bidMap = new HashMap<>();
                bidMap.put("type", "bid");
                bidMap.put("shouldCall", res.shouldCall());
                bidMap.put("winRate", Math.round(res.winRate() * 1000.0) / 10.0);
                bidMap.put("timeMs", res.durationMs());
                bidMap.put("sims", res.totalSimulations());
                bidMap.put("controlScore", res.controlScore());
                bidMap.put("summary", res.analysisSummary() != null ? res.analysisSummary() : "");
                aiThoughtsDto.put("p" + e.getKey(), bidMap);
            }
        } else {
            // 出牌思考雷达
            for (Map.Entry<Integer, PimcAiPlayer.DecisionResult> e : session.getLastAiThoughts().entrySet()) {
                PimcAiPlayer.DecisionResult res = e.getValue();
                List<Map<String, Object>> evals = new ArrayList<>();
                int limit = Math.min(4, res.getEvaluations().size());
                for (int i = 0; i < limit; i++) {
                    PimcAiPlayer.MoveEvaluation me = res.getEvaluations().get(i);
                    evals.add(Map.of(
                            "cards", me.getMove().toCardString(),
                            "visits", me.getTotalVisits(),
                            "winRate", Math.round(me.getAverageWinRate() * 1000.0) / 10.0
                    ));
                }
                aiThoughtsDto.put("p" + e.getKey(), Map.of(
                        "type", "play",
                        "move", res.getSelectedMove().toCardString(),
                        "timeMs", res.getDurationMillis(),
                        "evals", evals
                ));
            }
        }
        dto.put("aiThoughts", aiThoughtsDto);

        // 事件日志
        dto.put("logs", session.getEventLogs());

        return dto;
    }

    private void sendJson(HttpExchange exchange, int statusCode, Object data) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(data);
        sendResponse(exchange, statusCode, bytes, "application/json; charset=utf-8");
    }

    private void sendResponse(HttpExchange exchange, int statusCode, Object body, String contentType) throws IOException {
        byte[] bytes;
        if (body instanceof byte[] b) {
            bytes = b;
        } else {
            bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}

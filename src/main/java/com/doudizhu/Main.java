package com.doudizhu;

import com.doudizhu.ai.PimcAiPlayer;
import com.doudizhu.ai.SelfPlayRunner;
import com.doudizhu.game.GameState;
import com.doudizhu.game.PublicView;
import com.doudizhu.model.Hand;
import com.doudizhu.model.Move;
import com.doudizhu.model.Rank;
import com.doudizhu.rules.Deck;
import com.doudizhu.web.GameHttpServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 斗地主现代 AI 运行入口
 * 默认启动本地 Web 网页对战客户端 (http://localhost:8080)
 * 也可通过参数 --cli 运行纯命令行测试演示
 */
public class Main {

    public static void main(String[] args) throws Exception {
        boolean runCli = false;
        boolean runSelfPlay = false;
        int port = 8080;
        java.util.List<String> selfPlayArgs = new java.util.ArrayList<>();

        for (String arg : args) {
            if ("--cli".equalsIgnoreCase(arg)) {
                runCli = true;
            } else if ("--selfplay".equalsIgnoreCase(arg)) {
                runSelfPlay = true;
            } else if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            } else if (arg.startsWith("--games=") || arg.startsWith("--worlds=")
                    || arg.startsWith("--iters=") || arg.startsWith("--seed=")
                    || arg.startsWith("--out=")) {
                selfPlayArgs.add(arg);
            }
        }

        if (runCli) {
            runCliSimulation();
        } else if (runSelfPlay) {
            SelfPlayRunner.main(selfPlayArgs.toArray(new String[0]));
        } else {
            startWebServer(port);
        }
    }

    private static void startWebServer(int port) {
        try {
            GameHttpServer server = new GameHttpServer(port);
            server.start();

            System.out.println("👉 服务已就绪。如需在命令行查看纯 AI 对局，可使用参数: mvn exec:java -Dexec.args=\"--cli\"");
            System.out.println("按 Ctrl+C 可停止网页端服务。");

            // 保持主线程存活
            Thread.currentThread().join();
        } catch (Exception e) {
            System.err.println("启动 Web 服务失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void runCliSimulation() {
        System.out.println("===============================================================");
        System.out.println("   斗地主 AI 引擎 - PIMC (Perfect Information Monte Carlo)");
        System.out.println("   采用 Java 17 + PIMC 确定化采样 + 2v1 合作对抗 MCTS 搜索");
        System.out.println("===============================================================\n");

        Random random = new Random();
        System.out.println("[阶段 1] 随机洗牌发牌（各 17 张，底牌 3 张盖伏）");
        List<Rank> deck = Deck.createStandard54Cards();
        Collections.shuffle(deck, random);

        List<Hand> hands = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            Hand h = new Hand();
            for (int j = 0; j < 17; j++) {
                h.add(deck.get(i * 17 + j));
            }
            hands.add(h);
            System.out.printf("  玩家 P%d 初始 17 张手牌: %s\n", i, h.toCardString());
        }

        List<Rank> bottomCards = new ArrayList<>(3);
        for (int i = 51; i < 54; i++) {
            bottomCards.add(deck.get(i));
        }

        System.out.println("\n[阶段 2] 叫地主环节 (采用思路二：PIMC 底牌蒙特卡洛采样推演)");
        int landlordId = -1;
        for (int i = 0; i < 3; i++) {
            com.doudizhu.ai.BidEvaluator.BidResult bidRes = com.doudizhu.ai.BidEvaluator.evaluate(hands.get(i), 25, 0.50, random);
            System.out.printf("  🤖 玩家 P%d 评估 17 张手牌 -> 采样 25 次底牌推演地主胜率: %4.1f%% (%d ms) -> %s\n",
                    i, bidRes.winRate() * 100, bidRes.durationMs(),
                    bidRes.shouldCall() ? "【👑 叫地主】" : "【🙅 不叫】");
            if (bidRes.shouldCall()) {
                landlordId = i;
                break;
            }
        }

        if (landlordId == -1) {
            System.out.println("  ⚠️ 三位玩家均不叫地主，系统随机指派玩家 P0 为地主。");
            landlordId = 0;
        }

        System.out.printf("\n>>> 👑 玩家 P%d 成功叫得地主！翻开底牌: %s\n", landlordId, bottomCards);
        for (Rank r : bottomCards) {
            hands.get(landlordId).add(r);
        }
        System.out.printf("  地主 P%d 获得底牌后手牌 (20张): %s\n\n", landlordId, hands.get(landlordId).toCardString());

        System.out.println("[阶段 3] 正式对局出牌阶段");
        PimcAiPlayer[] aiPlayers = new PimcAiPlayer[]{
                new PimcAiPlayer(25, 120),
                new PimcAiPlayer(25, 120),
                new PimcAiPlayer(25, 120)
        };

        GameState state = new GameState(hands, landlordId, bottomCards);
        int round = 1;
        long totalStartTime = System.currentTimeMillis();

        while (!state.isGameOver()) {
            int currentId = state.getActivePlayerIndex();
            Hand currentHand = state.getPlayer(currentId).getHand();
            PublicView view = state.getPublicView(currentId);

            String roleStr = (currentId == landlordId) ? "地主" : "农民";
            System.out.printf("----------- [第 %2d 步] 轮到玩家 P%d (%s) 出牌 (手牌剩 %d 张) -----------\n",
                    round++, currentId, roleStr, currentHand.getTotalCards());
            System.out.printf("当前手牌: %s\n", currentHand.toCardString());
            if (state.getLastMove() != null && !state.getLastMove().isPass()) {
                System.out.printf("上家出牌: %s (出牌者: P%d)\n", state.getLastMove().toCardString(), state.getLastMovePlayerId());
            } else {
                System.out.println("桌面状态: [自由主动出牌]");
            }

            PimcAiPlayer.DecisionResult result = aiPlayers[currentId].decide(view);
            Move chosen = result.getSelectedMove();

            List<PimcAiPlayer.MoveEvaluation> evals = result.getEvaluations();
            if (evals.size() > 1) {
                System.out.printf("  AI 评估候选动作数: %d，耗时: %d ms\n", evals.size(), result.getDurationMillis());
                int showTop = Math.min(5, evals.size());
                for (int i = 0; i < showTop; i++) {
                    System.out.println("    " + (i == 0 ? "★ " : "  ") + evals.get(i));
                }
            }

            System.out.printf(">>> 玩家 P%d (%s) 决定: %s\n\n", currentId, roleStr, chosen.toCardString());
            state.applyMove(chosen);
        }

        long totalDuration = System.currentTimeMillis() - totalStartTime;
        System.out.println("===============================================================");
        System.out.printf("  对局结束！获胜方: %s (玩家 P%d 率先出完手牌)\n",
                state.getWinningRole().getDescription(), state.getWinnerId());
        System.out.printf("  总步数: %d 步，总耗时: %d ms\n", round - 1, totalDuration);
        System.out.println("===============================================================");
        for (int i = 0; i < 3; i++) {
            boolean isWinner = state.isPlayerWinner(i);
            System.out.printf("  玩家 P%d (%s) 最终剩余牌数: %2d 张  %s\n",
                    i, state.getPlayer(i).getRole().getDescription(),
                    state.getPlayer(i).getCardCount(),
                    isWinner ? "【获胜方 🏆】" : "【失败方】");
        }
        System.out.println("===============================================================");
    }
}

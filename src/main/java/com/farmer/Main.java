package com.farmer;

import com.farmer.ai.PimcAiPlayer;
import com.farmer.game.GameState;
import com.farmer.game.PublicView;
import com.farmer.model.Hand;
import com.farmer.model.Move;
import com.farmer.model.Rank;
import com.farmer.rules.Deck;
import com.farmer.web.GameHttpServer;

import java.util.List;
import java.util.Random;

/**
 * 斗地主现代 AI 运行入口
 * 默认启动本地 Web 网页对战客户端 (http://localhost:8080)
 * 也可通过参数 --cli 运行纯命令行测试演示
 */
public class Main {

    public static void main(String[] args) {
        boolean runCli = false;
        int port = 8080;

        for (String arg : args) {
            if ("--cli".equalsIgnoreCase(arg)) {
                runCli = true;
            } else if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            }
        }

        if (runCli) {
            runCliSimulation();
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
        int landlordId = 0; // 地主
        Deck.DealResult dealResult = Deck.deal(random, landlordId);

        List<Hand> hands = dealResult.playerHands();
        List<Rank> bottomCards = dealResult.bottomCards();

        System.out.printf("【底牌 3 张】: %s\n", bottomCards);
        for (int i = 0; i < 3; i++) {
            String roleStr = (i == landlordId) ? "【地主 👑】" : "【农民 🌾】";
            System.out.printf("  玩家 P%d %s 初始手牌 (%2d张): %s\n",
                    i, roleStr, hands.get(i).getTotalCards(), hands.get(i).toCardString());
        }
        System.out.println();

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

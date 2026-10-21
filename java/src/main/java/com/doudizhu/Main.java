package com.doudizhu;

import com.doudizhu.ai.AiDecision;
import com.doudizhu.ai.AiPlayer;
import com.doudizhu.ai.BidRule;
import com.doudizhu.ai.NeuralAiPlayer;
import com.doudizhu.game.GameState;
import com.doudizhu.game.PublicView;
import com.doudizhu.model.Hand;
import com.doudizhu.model.Move;
import com.doudizhu.model.Rank;
import com.doudizhu.nn.ActorNetwork;
import com.doudizhu.rules.Deck;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 斗地主 AI 运行入口: 三个 AI 自对弈一局的命令行演示。
 *
 * <p>网页对战平台不在这里 —— 它与 Go/C++ 三套实现共用的前端由 {@code server/} (fakelua 宿主) 提供。
 *
 * <p>参数: {@code --model-dir=data/models/ts}
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Path modelDir = NeuralAiPlayer.defaultModelDir();

        for (String arg : args) {
            if (arg.startsWith("--model-dir=")) {
                modelDir = Path.of(arg.substring("--model-dir=".length()));
            }
        }

        runCliSimulation(modelDir);
    }

    private static void runCliSimulation(Path modelDir) throws Exception {
        System.out.println("===============================================================");
        System.out.println("   斗地主 AI 引擎 - 强化学习策略网络 (PerfectDou)");
        System.out.printf("   权重: %s   叫分: 每家 %d%% 概率随机叫地主%n",
                modelDir, Math.round(BidRule.CALL_PROBABILITY * 100));
        System.out.println("===============================================================\n");

        Random random = new Random();
        try (ActorNetwork network = ActorNetwork.load(modelDir)) {
            AiPlayer neural = new NeuralAiPlayer(network);

            // 叫分阶段: 三家随机表态, 都不叫则流局重发
            int landlordId = -1;
            List<Hand> hands = new ArrayList<>();
            List<Rank> bottomCards = new ArrayList<>();
            for (int attempt = 1; landlordId < 0; attempt++) {
                List<Rank> deck = Deck.createStandard54Cards();
                Collections.shuffle(deck, random);
                hands = new ArrayList<>(3);
                for (int i = 0; i < 3; i++) {
                    Hand h = new Hand();
                    for (int j = 0; j < Deck.FARMER_CARDS_COUNT; j++) {
                        h.add(deck.get(i * Deck.FARMER_CARDS_COUNT + j));
                    }
                    hands.add(h);
                }
                bottomCards = new ArrayList<>(deck.subList(51, 54));

                System.out.printf("[阶段 1] 第 %d 轮发牌%n", attempt);
                for (int i = 0; i < 3; i++) {
                    System.out.printf("  玩家 P%d 手牌: %s%n", i, hands.get(i).toCardString());
                }
                System.out.println("\n[阶段 2] 随机叫地主");
                int first = random.nextInt(3);
                for (int k = 0; k < 3 && landlordId < 0; k++) {
                    int id = (first + k) % 3;
                    if (BidRule.shouldCall(random)) {
                        landlordId = id;
                        System.out.printf("  🤖 P%d 【叫地主】%n", id);
                    } else {
                        System.out.printf("  🤖 P%d 不叫%n", id);
                    }
                }
                if (landlordId < 0) {
                    System.out.println("  ⚠️ 三家都不叫，流局重发。");
                }
            }

            for (Rank r : bottomCards) {
                hands.get(landlordId).add(r);
            }
            System.out.printf("%n>>> 👑 玩家 P%d 成为地主，底牌: %s，手牌 (20 张): %s%n%n",
                    landlordId, bottomCards, hands.get(landlordId).toCardString());

            System.out.println("[阶段 3] 出牌阶段");
            GameState state = new GameState(hands, landlordId, bottomCards);
            int round = 1;
            long totalStartTime = System.currentTimeMillis();

            while (!state.isGameOver()) {
                int currentId = state.getActivePlayerIndex();
                PublicView view = state.getPublicView(currentId);
                String roleStr = (currentId == landlordId) ? "地主" : "农民";
                System.out.printf("----------- [第 %2d 步] 轮到玩家 P%d (%s) 出牌 (手牌剩 %d 张) -----------%n",
                        round++, currentId, roleStr, state.getPlayer(currentId).getCardCount());

                AiDecision decision = neural.decide(view);
                Move chosen = decision.getSelectedMove();
                if (decision.getScores().size() > 1) {
                    System.out.printf("  候选动作 %d 个，耗时 %d ms%n",
                            decision.getScores().size(), decision.getDurationMillis());
                    for (int i = 0; i < Math.min(5, decision.getScores().size()); i++) {
                        System.out.println("    " + (i == 0 ? "★ " : "  ") + decision.getScores().get(i));
                    }
                }
                System.out.printf(">>> 玩家 P%d (%s) 决定: %s%n%n", currentId, roleStr, chosen.toCardString());
                state.applyMove(chosen);
            }

            System.out.println("===============================================================");
            System.out.printf("  对局结束！获胜方: %s (玩家 P%d 率先出完手牌)%n",
                    state.getWinningRole().getDescription(), state.getWinnerId());
            System.out.printf("  总步数: %d 步，总耗时: %d ms%n", round - 1,
                    System.currentTimeMillis() - totalStartTime);
            for (int i = 0; i < 3; i++) {
                System.out.printf("  玩家 P%d (%s) 剩余 %2d 张  %s%n",
                        i, state.getPlayer(i).getRole().getDescription(), state.getPlayer(i).getCardCount(),
                        state.isPlayerWinner(i) ? "【获胜方 🏆】" : "【失败方】");
            }
            System.out.println("===============================================================");
        }
    }
}

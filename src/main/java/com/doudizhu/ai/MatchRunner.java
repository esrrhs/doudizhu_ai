package com.doudizhu.ai;

import com.doudizhu.game.GameState;
import com.doudizhu.game.PublicView;
import com.doudizhu.model.Hand;
import com.doudizhu.model.Move;
import com.doudizhu.model.Rank;
import com.doudizhu.nn.ActorNetwork;
import com.doudizhu.rules.Deck;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 强度对抗评估：神经网络玩家与 PIMC 搜索玩家轮流坐地主与农民，统计胜率与 ADP 得分。
 *
 * <p>同一个发牌跑两局 (NN 坐地主 / NN 坐农民)，两侧角色互换，抵消牌力差异。
 * 得分用与训练侧一致的 ADP：炸弹数加倍，地主输赢计 2 倍分，农民各计 1 倍分。
 */
public final class MatchRunner {

    /** 单局最多步数, 防止异常局面死循环 (正常对局远小于此值) */
    private static final int STEP_LIMIT = 400;

    private MatchRunner() {
    }

    public static void main(String[] args) throws Exception {
        int games = 20;
        int worlds = 25;
        int iters = 120;
        int minutes = 0;
        long baseSeed = 20261020L;
        Path modelDir = Path.of("models/ts");
        Path outFile = null;

        for (String arg : args) {
            if (arg.startsWith("--games=")) {
                games = Integer.parseInt(arg.substring("--games=".length()));
            } else if (arg.startsWith("--worlds=")) {
                worlds = Integer.parseInt(arg.substring("--worlds=".length()));
            } else if (arg.startsWith("--iters=")) {
                iters = Integer.parseInt(arg.substring("--iters=".length()));
            } else if (arg.startsWith("--minutes=")) {
                minutes = Integer.parseInt(arg.substring("--minutes=".length()));
            } else if (arg.startsWith("--seed=")) {
                baseSeed = Long.parseLong(arg.substring("--seed=".length()));
            } else if (arg.startsWith("--model-dir=")) {
                modelDir = Path.of(arg.substring("--model-dir=".length()));
            } else if (arg.startsWith("--out=")) {
                outFile = Path.of(arg.substring("--out=".length()));
            }
        }

        System.out.printf("=== NN vs PIMC: deals=%d worlds=%d iters=%d seed=%d model=%s ===%n",
                games, worlds, iters, baseSeed, modelDir);

        Tally nn = new Tally();
        Tally pimc = new Tally();
        long startedAt = System.currentTimeMillis();
        long deadline = minutes > 0 ? startedAt + minutes * 60_000L : Long.MAX_VALUE;
        try (ActorNetwork network = ActorNetwork.load(modelDir)) {
            AiPlayer neural = new NeuralAiPlayer(network);
        
            for (int done = 0; done < games; done++) {
                if (minutes > 0 && System.currentTimeMillis() >= deadline) {
                    break;
                }
                Deal deal = deal(baseSeed + done);
                play(deal, neural, worlds, iters, true, nn, pimc);
                play(deal, neural, worlds, iters, false, nn, pimc);
                int pairs = done + 1;
                if (pairs % 5 == 0 || pairs == games) {
                    System.out.printf("%d 组已完事 (用时 %ds): NN 胜率 %.1f%%, PIMC 胜率 %.1f%%%n",
                            pairs, (System.currentTimeMillis() - startedAt) / 1000,
                            nn.winRate() * 100, pimc.winRate() * 100);
                }
            }
        }

        String report = format(nn, pimc, (System.currentTimeMillis() - startedAt) / 1000);
        System.out.println(report);
        if (outFile != null) {
            Files.createDirectories(outFile.toAbsolutePath().getParent());
            Files.writeString(outFile, report, StandardCharsets.UTF_8);
            System.out.println("报告写入 " + outFile.toAbsolutePath());
        }
    }

    /** 一方在各角色下的统计 */
    private static final class Tally {
        int nnLandlordGames;
        int nnLandlordWins;
        double nnLandlordScore;
        int nnFarmerGames;
        int nnFarmerWins;
        double nnFarmerScore;

        void recordLandlord(boolean nnWon, double nnScore) {
            nnLandlordGames++;
            if (nnWon) {
                nnLandlordWins++;
            }
            nnLandlordScore += nnScore;
        }

        void recordFarmer(boolean nnWon, double nnScore) {
            nnFarmerGames++;
            if (nnWon) {
                nnFarmerWins++;
            }
            nnFarmerScore += nnScore;
        }

        int games() {
            return nnLandlordGames + nnFarmerGames;
        }

        int wins() {
            return nnLandlordWins + nnFarmerWins;
        }

        double score() {
            return nnLandlordScore + nnFarmerScore;
        }

        double winRate() {
            return games() == 0 ? 0 : (double) wins() / games();
        }
    }

    private record Deal(long seed, List<Hand> hands, List<Rank> bottom, int landlordId) {
    }

    /** 与自对弈一致的开局: 随机发牌, BidEvaluator 决定地主 */
    private static Deal deal(long seed) {
        Random random = new Random(seed);
        List<Rank> deck = Deck.createStandard54Cards();
        Collections.shuffle(deck, random);
        List<Hand> hands = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            Hand h = new Hand();
            for (int j = 0; j < 17; j++) {
                h.add(deck.get(i * 17 + j));
            }
            hands.add(h);
        }
        List<Rank> bottom = List.of(deck.get(51), deck.get(52), deck.get(53));
        int landlordId = 0;
        for (int i = 0; i < 3; i++) {
            if (BidEvaluator.evaluate(hands.get(i), 12, 80, 0.50, random).shouldCall()) {
                landlordId = i;
                break;
            }
        }
        for (Rank r : bottom) {
            hands.get(landlordId).add(r);
        }
        return new Deal(seed, hands, bottom, landlordId);
    }

    private static void play(Deal deal, AiPlayer neural, int worlds, int iters,
                             boolean neuralIsLandlord, Tally nn, Tally pimc) {
        Random random = new Random(deal.seed() * 31 + (neuralIsLandlord ? 1 : 2));
        List<Hand> hands = new ArrayList<>(3);
        for (Hand h : deal.hands()) {
            hands.add(h.copy());
        }
        GameState state = new GameState(hands, deal.landlordId(), deal.bottom());

        AiPlayer[] seats = new AiPlayer[3];
        for (int i = 0; i < 3; i++) {
            boolean landlordSeat = i == deal.landlordId();
            seats[i] = (landlordSeat == neuralIsLandlord) ? neural
                    : new PimcAiPlayer(worlds, iters, new Random(random.nextLong()));
        }

        int steps = 0;
        while (!state.isGameOver() && steps++ < STEP_LIMIT) {
            int active = state.getActivePlayerIndex();
            PublicView view = state.getPublicView(active);
            Move chosen = seats[active].decide(view).getSelectedMove();
            state.applyMove(chosen);
        }
        if (!state.isGameOver()) {
            return;
        }

        int bombs = 0;
        for (Move m : state.getMoveHistory()) {
            if (m.isBomb() || m.isRocket()) {
                bombs++;
            }
        }
        double mult = Math.pow(2.0, Math.min(bombs, 20));
        boolean landlordWon = state.getWinningRole() != null && state.getWinningRole().isLandlord();
        double landlordScore = (landlordWon ? 2.0 : -2.0) * mult;
        double farmerScore = -landlordScore / 2.0;
        double nnScore = neuralIsLandlord ? landlordScore : 2 * farmerScore;
        boolean nnWon = nnScore > 0;
        if (neuralIsLandlord) {
            nn.recordLandlord(nnWon, nnScore);
            pimc.recordFarmer(!nnWon, -nnScore);
        } else {
            nn.recordFarmer(nnWon, nnScore);
            pimc.recordLandlord(!nnWon, -nnScore);
        }
    }

    private static String format(Tally nn, Tally pimc, long elapsedSec) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-26s %6s %6s %9s %9s%n",
                "配置", "局数", "胜局", "胜率", "平均分"));
        sb.append(String.format("%-26s %6d %6d %8.1f%% %9.3f%n",
                "NN 坐地主", nn.nnLandlordGames, nn.nnLandlordWins,
                nn.nnLandlordGames == 0 ? 0 : 100.0 * nn.nnLandlordWins / nn.nnLandlordGames,
                nn.nnLandlordGames == 0 ? 0 : nn.nnLandlordScore / nn.nnLandlordGames));
        sb.append(String.format("%-26s %6d %6d %8.1f%% %9.3f%n",
                "NN 坐农民", nn.nnFarmerGames, nn.nnFarmerWins,
                nn.nnFarmerGames == 0 ? 0 : 100.0 * nn.nnFarmerWins / nn.nnFarmerGames,
                nn.nnFarmerGames == 0 ? 0 : nn.nnFarmerScore / nn.nnFarmerGames));
        sb.append(String.format("%-26s %6d %6d %8.1f%% %9.3f%n",
                "NN 合计", nn.games(), nn.wins(), nn.winRate() * 100,
                nn.games() == 0 ? 0 : nn.score() / nn.games()));
        sb.append(String.format("%-26s %6d %6d %8.1f%% %9.3f%n",
                "PIMC 合计", pimc.games(), pimc.wins(), pimc.winRate() * 100,
                pimc.games() == 0 ? 0 : pimc.score() / pimc.games()));
        sb.append(String.format("elapsedSec=%d%n", elapsedSec));
        return sb.toString();
    }
}

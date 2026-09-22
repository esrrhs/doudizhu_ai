package com.doudizhu.ai;

import com.doudizhu.game.GameState;
import com.doudizhu.game.PublicView;
import com.doudizhu.model.CardType;
import com.doudizhu.model.Hand;
import com.doudizhu.model.Move;
import com.doudizhu.model.Rank;
import com.doudizhu.rules.Deck;
import com.doudizhu.rules.MoveGenerator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 三人 AI 自对弈：记录初始手牌与每步出牌，并做启发式合理性审查。
 */
public final class SelfPlayRunner {

    public record StepRecord(
            int step,
            int playerId,
            String role,
            String handBefore,
            String lastMove,
            String chosen,
            long thinkMs,
            List<String> issues
    ) {
    }

    public record GameRecord(
            int seed,
            int landlordId,
            String[] initialHands,
            String bottom,
            List<StepRecord> steps,
            int winnerId,
            String winningRole,
            String[] finalHands,
            List<String> gameIssues
    ) {
    }

    public static void main(String[] args) throws Exception {
        int games = 5;
        int worlds = 40;
        int iters = 300;
        int minutes = 0;
        long baseSeed = System.currentTimeMillis();
        Path outDir = Path.of("target/selfplay");

        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--games=")) {
                games = Integer.parseInt(args[i].substring("--games=".length()));
            } else if (args[i].startsWith("--worlds=")) {
                worlds = Integer.parseInt(args[i].substring("--worlds=".length()));
            } else if (args[i].startsWith("--iters=")) {
                iters = Integer.parseInt(args[i].substring("--iters=".length()));
            } else if (args[i].startsWith("--minutes=")) {
                minutes = Integer.parseInt(args[i].substring("--minutes=".length()));
            } else if (args[i].startsWith("--seed=")) {
                baseSeed = Long.parseLong(args[i].substring("--seed=".length()));
            } else if (args[i].startsWith("--out=")) {
                outDir = Path.of(args[i].substring("--out=".length()));
            }
        }

        Files.createDirectories(outDir);
        List<GameRecord> all = new ArrayList<>();
        int totalIssues = 0;
        long startedAt = System.currentTimeMillis();
        long deadline = minutes > 0 ? startedAt + minutes * 60_000L : Long.MAX_VALUE;

        if (minutes > 0) {
            System.out.printf("=== Self-play: minutes=%d worlds=%d iters=%d seed=%d ===%n",
                    minutes, worlds, iters, baseSeed);
        } else {
            System.out.printf("=== Self-play: games=%d worlds=%d iters=%d seed=%d ===%n",
                    games, worlds, iters, baseSeed);
        }

        for (int g = 0; (minutes > 0) ? (System.currentTimeMillis() < deadline) : (g < games); g++) {
            long seed = baseSeed + g;
            GameRecord rec = playOne(seed, worlds, iters);
            all.add(rec);
            totalIssues += rec.gameIssues().size();
            for (StepRecord s : rec.steps()) {
                totalIssues += s.issues().size();
            }
            Path file = outDir.resolve(String.format("game-%d-seed%d.txt", g, seed));
            Files.writeString(file, formatGame(rec), StandardCharsets.UTF_8);
            long elapsedSec = (System.currentTimeMillis() - startedAt) / 1000;
            System.out.printf("Game %d seed=%d landlord=P%d winner=P%d(%s) steps=%d issues=%d elapsed=%ds -> %s%n",
                    g, seed, rec.landlordId(), rec.winnerId(), rec.winningRole(),
                    rec.steps().size(),
                    rec.gameIssues().size() + rec.steps().stream().mapToInt(s -> s.issues().size()).sum(),
                    elapsedSec, file);
        }

        Path summary = outDir.resolve("summary.txt");
        String summaryText = formatSummary(all, totalIssues)
                + String.format("elapsedSec=%d%n", (System.currentTimeMillis() - startedAt) / 1000);
        Files.writeString(summary, summaryText, StandardCharsets.UTF_8);
        System.out.println(summaryText);
        System.out.println("Summary written to " + summary.toAbsolutePath());
    }

    public static GameRecord playOne(long seed, int worlds, int iters) {
        Random random = new Random(seed);
        List<Rank> deck = Deck.createStandard54Cards();
        java.util.Collections.shuffle(deck, random);
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
            var bid = BidEvaluator.evaluate(hands.get(i), 12, 80, 0.50, random);
            if (bid.shouldCall()) {
                landlordId = i;
                break;
            }
        }
        for (Rank r : bottom) {
            hands.get(landlordId).add(r);
        }

        String[] initial = new String[3];
        for (int i = 0; i < 3; i++) {
            initial[i] = hands.get(i).toCardString();
        }

        PimcAiPlayer[] ais = new PimcAiPlayer[]{
                new PimcAiPlayer(worlds, iters, new Random(seed + 11)),
                new PimcAiPlayer(worlds, iters, new Random(seed + 22)),
                new PimcAiPlayer(worlds, iters, new Random(seed + 33))
        };

        GameState state = new GameState(hands, landlordId, bottom);
        List<StepRecord> steps = new ArrayList<>();
        int step = 0;

        while (!state.isGameOver() && step < 200) {
            step++;
            int pid = state.getActivePlayerIndex();
            Hand handBefore = state.getPlayer(pid).getHand().copy();
            String handStr = handBefore.toCardString();
            String lastStr = (state.getLastMove() == null || state.getLastMove().isPass())
                    ? "-" : state.getLastMove().toCardString() + "(P" + state.getLastMovePlayerId() + ")";
            String role = state.getPlayer(pid).getRole().getDescription();

            PublicView view = state.getPublicView(pid);
            long t0 = System.currentTimeMillis();
            PimcAiPlayer.DecisionResult result = ais[pid].decide(view);
            long ms = System.currentTimeMillis() - t0;
            Move chosen = result.getSelectedMove();

            List<String> issues = reviewMove(state, handBefore, chosen);
            steps.add(new StepRecord(step, pid, role, handStr, lastStr, chosen.toCardString(), ms, issues));
            state.applyMove(chosen);
        }

        String[] finals = new String[3];
        for (int i = 0; i < 3; i++) {
            finals[i] = state.getPlayer(i).getHand().toCardString();
        }
        List<String> gameIssues = reviewEndgame(state);

        return new GameRecord(
                (int) seed, landlordId, initial, formatRanks(bottom),
                steps, state.getWinnerId(),
                state.getWinningRole() != null ? state.getWinningRole().getDescription() : "?",
                finals, gameIssues
        );
    }

    static List<String> reviewMove(GameState state, Hand handBefore, Move chosen) {
        List<String> issues = new ArrayList<>();
        Move last = state.getLastMove();
        boolean leading = last == null || last.isPass();
        int pid = state.getActivePlayerIndex();
        List<Move> legal = MoveGenerator.generateLegalMoves(handBefore, last, pid);
        boolean canPass = legal.stream().anyMatch(Move::isPass);

        if (!leading && chosen.isRocket()) {
            int oppCards = state.getPlayer(state.getLastMovePlayerId()).getCardCount();
            if (oppCards > 3 && handBefore.getTotalCards() > 6 && canPass) {
                issues.add("EARLY_ROCKET: 非紧急出王炸 (对手剩" + oppCards + " 自己剩" + handBefore.getTotalCards() + ")");
            }
        }

        if (!leading && chosen.isBomb() && !chosen.isRocket() && canPass) {
            int oppCards = state.getPlayer(state.getLastMovePlayerId()).getCardCount();
            if (oppCards > 3 && handBefore.getTotalCards() > 6 && !last.isBomb()) {
                issues.add("EARLY_BOMB: 非紧急普通炸 (对手剩" + oppCards + ")");
            }
        }

        if (HandShape.breaksSet(handBefore, chosen)) {
            if (leading) {
                issues.add("LEAD_BREAK_SET: 主动拆对/拆三出单 " + chosen.toCardString());
            } else if (canPass && handBefore.getTotalCards() > 2) {
                int oppCards = state.getPlayer(state.getLastMovePlayerId()).getCardCount();
                if (oppCards > 3 && last.getMainRank() <= 10) {
                    issues.add("FOLLOW_BREAK_PAIR: 非紧急拆对压中小牌 " + chosen.toCardString()
                            + " 压 " + last.toCardString());
                }
            }
        }

        if (!leading && !chosen.isPass() && !chosen.isBomb() && !chosen.isRocket()
                && chosen.getType() == last.getType()) {
            Move cheaper = null;
            for (Move m : legal) {
                if (m.isPass() || m.isBomb() || m.isRocket()) continue;
                if (m.getType() != chosen.getType()) continue;
                if (HandShape.breaksSet(handBefore, m)) continue;
                if (m.getMainRank() < chosen.getMainRank() && m.canBeat(last)) {
                    if (cheaper == null || m.getMainRank() < cheaper.getMainRank()) {
                        cheaper = m;
                    }
                }
            }
            if (cheaper != null && chosen.getMainRank() - cheaper.getMainRank() >= 3) {
                issues.add("FOLLOW_OVERSHOOT: 有更小同型可压却出大牌 "
                        + chosen.toCardString() + " (本可用 " + cheaper.toCardString() + ")");
            }
        }

        if (leading && chosen.getType() == CardType.SINGLE
                && chosen.getMainRank() >= Rank.TWO.getValue()
                && handBefore.getTotalCards() > 4) {
            boolean hasSmall = false;
            for (int v = 3; v < Rank.TWO.getValue(); v++) {
                if (handBefore.getCount(v) > 0) {
                    hasSmall = true;
                    break;
                }
            }
            if (hasSmall) {
                issues.add("LEAD_CONTROL_EARLY: 主动早出控场牌 " + chosen.toCardString());
            }
        }

        return issues;
    }

    static List<String> reviewEndgame(GameState state) {
        List<String> issues = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            if (state.isPlayerWinner(i)) {
                continue;
            }
            Hand h = state.getPlayer(i).getHand();
            int weak = HandShape.countWeakSingles(h);
            int structured = 0;
            for (int v = 3; v <= 15; v++) {
                if (h.getCount(v) >= 2) {
                    structured++;
                }
            }
            boolean hasJoker = h.getCount(Rank.BLACK_JOKER.getValue()) > 0
                    || h.getCount(Rank.RED_JOKER.getValue()) > 0
                    || h.getCount(Rank.TWO.getValue()) > 0;
            // 真正散死：弱单多、几乎没有对子/三张，还握着控场
            if (weak >= 4 && structured == 0 && hasJoker && h.getTotalCards() >= 5) {
                issues.add(String.format(
                        "DEAD_HAND_P%d: 失败方纯散弱单%d + 控场未用完 [%s]",
                        i, weak, h.toCardString()));
            } else if (HandShape.isDeadScattered(h) && structured == 0 && h.getTotalCards() >= 5) {
                issues.add(String.format(
                        "SCATTERED_P%d: 失败方散牌死形 [%s]", i, h.toCardString()));
            }
        }
        return issues;
    }

    private static String formatRanks(List<Rank> ranks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ranks.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ranks.get(i).getSymbol());
        }
        return sb.toString();
    }

    public static String formatGame(GameRecord rec) {
        StringBuilder sb = new StringBuilder();
        sb.append("seed=").append(rec.seed())
                .append(" landlord=P").append(rec.landlordId())
                .append(" bottom=").append(rec.bottom()).append('\n');
        for (int i = 0; i < 3; i++) {
            sb.append("初始 P").append(i).append(": ").append(rec.initialHands()[i]).append('\n');
        }
        sb.append('\n');
        for (StepRecord s : rec.steps()) {
            sb.append(String.format("#%02d P%d(%s) hand=[%s] last=%s -> %s (%dms)",
                    s.step(), s.playerId(), s.role(), s.handBefore(), s.lastMove(), s.chosen(), s.thinkMs()));
            if (!s.issues().isEmpty()) {
                sb.append(" !! ").append(String.join("; ", s.issues()));
            }
            sb.append('\n');
        }
        sb.append('\n');
        sb.append("winner=P").append(rec.winnerId()).append('(').append(rec.winningRole()).append(")\n");
        for (int i = 0; i < 3; i++) {
            sb.append("最终 P").append(i).append(": ").append(rec.finalHands()[i]).append('\n');
        }
        if (!rec.gameIssues().isEmpty()) {
            sb.append("终局问题:\n");
            for (String g : rec.gameIssues()) {
                sb.append("  - ").append(g).append('\n');
            }
        }
        return sb.toString();
    }

    public static String formatSummary(List<GameRecord> all, int totalIssues) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SUMMARY ===\n");
        sb.append("games=").append(all.size()).append(" totalIssueFlags=").append(totalIssues).append('\n');
        int dead = 0, breakPair = 0, earlyRocket = 0, earlyBomb = 0, leadBreak = 0, leadCtrl = 0, scattered = 0, overshoot = 0;
        for (GameRecord g : all) {
            for (String x : g.gameIssues()) {
                if (x.startsWith("DEAD_HAND")) dead++;
                if (x.startsWith("SCATTERED")) scattered++;
            }
            for (StepRecord s : g.steps()) {
                for (String x : s.issues()) {
                    if (x.startsWith("FOLLOW_BREAK_PAIR")) breakPair++;
                    if (x.startsWith("FOLLOW_OVERSHOOT")) overshoot++;
                    if (x.startsWith("EARLY_ROCKET")) earlyRocket++;
                    if (x.startsWith("EARLY_BOMB")) earlyBomb++;
                    if (x.startsWith("LEAD_BREAK_SET")) leadBreak++;
                    if (x.startsWith("LEAD_CONTROL_EARLY")) leadCtrl++;
                }
            }
        }
        sb.append(String.format(
                "DEAD_HAND=%d SCATTERED=%d FOLLOW_BREAK_PAIR=%d FOLLOW_OVERSHOOT=%d EARLY_ROCKET=%d EARLY_BOMB=%d LEAD_BREAK_SET=%d LEAD_CONTROL_EARLY=%d%n",
                dead, scattered, breakPair, overshoot, earlyRocket, earlyBomb, leadBreak, leadCtrl));
        return sb.toString();
    }
}

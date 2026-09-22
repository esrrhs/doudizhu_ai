package com.doudizhu.ai;

import com.doudizhu.game.PublicView;
import com.doudizhu.model.CardType;
import com.doudizhu.model.Hand;
import com.doudizhu.model.Move;
import com.doudizhu.model.Rank;
import com.doudizhu.model.Role;
import com.doudizhu.rules.MoveGenerator;

import java.util.ArrayList;
import java.util.List;

/**
 * 由公开出牌历史做「过牌推断」，剪掉明显不合理的假想世界。
 * <p>
 * 例：上家出单 5，某玩家过牌 → 他大概率没有 6/7/8 这类「紧挨着的落单小牌」可压；
 * 2/王 等大牌可以舍不得打，不因此否定。对子、顺子、飞机等同理。
 * 炸弹/王炸默认视为可留着。农民放过队友时不做强约束。
 */
public final class PassInference {

    private PassInference() {
    }

    public static final class Constraint {
        public final int passerId;
        public final Move challenge;
        public final int historyIndex;

        Constraint(int passerId, Move challenge, int historyIndex) {
            this.passerId = passerId;
            this.challenge = challenge;
            this.historyIndex = historyIndex;
        }
    }

    public static List<Constraint> extractConstraints(List<Move> history) {
        List<Constraint> result = new ArrayList<>();
        Move currentChallenge = null;
        for (int i = 0; i < history.size(); i++) {
            Move m = history.get(i);
            if (m.isPass()) {
                if (currentChallenge != null && !currentChallenge.isPass()) {
                    result.add(new Constraint(m.getPlayerId(), currentChallenge, i));
                }
            } else {
                currentChallenge = m;
            }
        }
        return result;
    }

    /**
     * 只保留「当前轮」相关过牌：挑战牌等于当前 lastMove，
     * 或历史末尾连续过牌所对应的那手挑战（信息最新、最可靠）。
     */
    public static List<Constraint> extractActiveConstraints(PublicView view) {
        List<Move> history = view.getMoveHistory();
        List<Constraint> all = extractConstraints(history);
        if (all.isEmpty()) {
            return all;
        }
        Move lastMove = view.getLastMove();
        List<Constraint> active = new ArrayList<>();
        for (Constraint c : all) {
            if (lastMove != null && sameChallenge(c.challenge, lastMove)) {
                active.add(c);
            }
        }
        // 若桌面已清空，取每一段 trick 里最近一次过牌约束（末尾若干条）
        if (active.isEmpty()) {
            int from = Math.max(0, all.size() - 4);
            active.addAll(all.subList(from, all.size()));
        }
        return active;
    }

    private static boolean sameChallenge(Move a, Move b) {
        return a.getType() == b.getType()
                && a.getMainRank() == b.getMainRank()
                && a.getPlayerId() == b.getPlayerId()
                && a.getCardCount() == b.getCardCount();
    }

    public static Hand reconstructHandAtPass(Hand currentHand, int passerId,
                                             List<Move> history, int passIndex) {
        Hand hand = currentHand.copy();
        for (int i = passIndex + 1; i < history.size(); i++) {
            Move m = history.get(i);
            if (m.getPlayerId() == passerId && !m.isPass()) {
                for (Rank r : m.getCards()) {
                    hand.add(r);
                }
            }
        }
        return hand;
    }

    public static double likelihood(PublicView view, List<Hand> hands) {
        int violations = countViolations(view, hands);
        if (violations <= 0) {
            return 1.0;
        }
        return Math.max(0.2, Math.pow(0.45, violations));
    }

    /**
     * 按推断概率分配每个假想世界的搜索迭代。高概率多算，低概率仍保留约 1/4 份额。
     */
    public static int[] allocateSearchIterations(double[] likelihoods, int fullIterations) {
        int n = likelihoods.length;
        double[] weight = new double[n];
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            weight[i] = Math.max(0.25, likelihoods[i]);
            sum += weight[i];
        }
        int[] iterations = new int[n];
        for (int i = 0; i < n; i++) {
            iterations[i] = Math.max(1, (int) Math.round(fullIterations * (n * weight[i] / sum)));
        }
        return iterations;
    }

    public static boolean isWorldConsistent(PublicView view, List<Hand> hands) {
        return countViolations(view, hands) == 0;
    }

    private static int countViolations(PublicView view, List<Hand> hands) {
        int myId = view.getViewingPlayerId();
        List<Move> history = view.getMoveHistory();
        List<Constraint> constraints = extractActiveConstraints(view);
        if (constraints.isEmpty()) {
            return 0;
        }

        Role[] roles = new Role[3];
        for (int i = 0; i < 3; i++) {
            roles[i] = (i == view.getLandlordId()) ? Role.LANDLORD : Role.FARMER;
        }

        int violations = 0;
        for (Constraint c : constraints) {
            if (c.passerId == myId) {
                continue;
            }
            if (roles[c.passerId].isTeammateWith(roles[c.challenge.getPlayerId()])) {
                continue;
            }
            Hand handAtPass = reconstructHandAtPass(
                    hands.get(c.passerId), c.passerId, history, c.historyIndex);
            if (hasObviousCheapBeater(handAtPass, c.challenge, c.passerId)) {
                violations++;
            }
        }
        return violations;
    }

    static boolean hasObviousCheapBeater(Hand hand, Move challenge, int playerId) {
        if (challenge == null || challenge.isPass()) {
            return false;
        }
        List<Move> legal = MoveGenerator.generateLegalMoves(hand, challenge, playerId);
        for (Move m : legal) {
            if (m.isPass() || m.isBomb() || m.isRocket()) {
                continue;
            }
            if (!m.canBeat(challenge) || m.getType() != challenge.getType()) {
                continue;
            }
            if (isCheapBeater(m, challenge, hand)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 「明明该管」的便宜着法：紧挨挑战牌的小牌 / 结构牌，而非 2、王等控场牌。
     */
    static boolean isCheapBeater(Move beater, Move challenge, Hand hand) {
        CardType type = challenge.getType();
        return switch (type) {
            case SINGLE -> {
                int r = beater.getMainRank();
                // 只否定「紧挨的落单小/中单」，例如压 5 时的落单 6/7/8
                int maxCheap = Math.min(Rank.TEN.getValue(), challenge.getMainRank() + 3);
                yield r <= maxCheap && r > challenge.getMainRank() && hand.getCount(r) == 1;
            }
            case PAIR -> {
                int r = beater.getMainRank();
                int maxCheap = Math.min(Rank.QUEEN.getValue(), challenge.getMainRank() + 3);
                yield r <= maxCheap && r > challenge.getMainRank();
            }
            case TRIPLE, TRIPLE_PLUS_ONE, TRIPLE_PLUS_PAIR -> {
                int r = beater.getMainRank();
                int maxCheap = Math.min(Rank.JACK.getValue(), challenge.getMainRank() + 2);
                yield r <= maxCheap && r > challenge.getMainRank();
            }
            case STRAIGHT, CONSECUTIVE_PAIRS, AIRPLANE, AIRPLANE_PLUS_SINGLES, AIRPLANE_PLUS_PAIRS,
                 FOUR_PLUS_TWO, FOUR_PLUS_TWO_PAIRS ->
                    beater.getMainRank() <= challenge.getMainRank() + 2;
            default -> false;
        };
    }
}

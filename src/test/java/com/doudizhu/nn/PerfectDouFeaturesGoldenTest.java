package com.doudizhu.nn;

import com.doudizhu.game.GameState;
import com.doudizhu.game.PublicView;
import com.doudizhu.model.Hand;
import com.doudizhu.model.Move;
import com.doudizhu.model.Rank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用 PerfectDouCPP 侧导出的 golden 记录逐位校验 Java 移植。
 *
 * <p>golden 由 PerfectDouCPP 的 tools/pddump.cpp 生成: 每局给出发牌与底牌, 每个决策给出 C++ 的
 * xImp / seq / 全部合法动作及其 actionFeat / actor 的 logits / 实际所选动作。
 * Java 侧从同一副牌起用自己的引擎回放, 因此这里同时校验候选动作枚举、观察构造、牌矩阵
 * 与手数 oracle 是否与训练侧完全一致。
 */
class PerfectDouFeaturesGoldenTest {

    private static final String GOLDEN = "/golden/golden24.txt.gz";

    @Test
    @DisplayName("golden 对齐：候选动作、xImp、seq、actionFeat 与 C++ 完全一致")
    void javaFeaturesMatchCppGolden() {
        GoldenReader reader = GoldenReader.open(GOLDEN);
        Diff diff = new Diff();
        int decisions = 0;
        try {
            while (reader.hasNext()) {
                String key = reader.nextToken();
                if ("GAME".equals(key)) {
                    decisions += replayGame(reader, diff);
                } else if (!"DDZGOLDEN".equals(key)) {
                    throw new IllegalStateException("意外的字段 " + key);
                } else {
                    reader.skipLine();
                }
            }
        } finally {
            reader.close();
        }

        assertThat(decisions).as("回放的决策数").isGreaterThan(700);
        assertThat(diff.report()).isEmpty();
    }

    /** 回放一局, 返回该局完成的决策数 */
    private int replayGame(GoldenReader reader, Diff diff) {
        reader.nextToken();   // 局号
        List<Hand> hands = new ArrayList<>();
        List<Rank> bottom = new ArrayList<>();
        for (int s = 0; s < 3; ++s) {
            assertThat(reader.nextToken()).isEqualTo("DEAL");
            reader.nextToken();   // 座位号
            hands.add(parseHand(reader.nextToken()));
        }
        assertThat(reader.nextToken()).isEqualTo("BOTTOM");
        for (char ch : reader.nextToken().toCharArray()) {
            bottom.add(Rank.fromSymbol(String.valueOf(ch)));
        }
        assertThat(reader.nextToken()).isEqualTo("LANDLORD");
        int landlordId = Integer.parseInt(reader.nextToken());

        GameState state = new GameState(hands, landlordId, bottom);
        int decisions = 0;
        while (true) {
            String marker = reader.nextToken();
            if ("ENDGAME".equals(marker)) {
                return decisions;
            }
            assertThat(marker).isEqualTo("DEC");
            reader.nextToken();   // 决策序号
            assertThat(reader.nextToken()).isEqualTo("TURN");
            int turn = Integer.parseInt(reader.nextToken());
            assertThat(reader.nextToken()).isEqualTo("HIST");
            reader.nextToken();   // 该局已发生的动作数
            assertThat(state.getActivePlayerIndex())
                    .as("第 %d 个决策的行动者", decisions)
                    .isEqualTo(turn);

            PublicView view = state.getPublicView(turn);
            PerfectDouFeatures.View observed = PerfectDouFeatures.observe(view);
            float[] xImp = new float[PerfectDouFeatures.NODE_SIZE];
            float[] seq = new float[PerfectDouFeatures.SEQ_SIZE];
            PerfectDouFeatures.encode(observed, xImp, seq);

            assertThat(reader.nextToken()).isEqualTo("XIMP");
            int n = Integer.parseInt(reader.nextToken());
            assertThat(n).isEqualTo(PerfectDouFeatures.NODE_SIZE);
            diff.compareBlock("xImp", decisions, xImp, reader.readFloats(n));

            assertThat(reader.nextToken()).isEqualTo("SEQ");
            n = Integer.parseInt(reader.nextToken());
            assertThat(n).isEqualTo(PerfectDouFeatures.SEQ_SIZE);
            diff.compareBlock("seq", decisions, seq, reader.readFloats(n));

            assertThat(reader.nextToken()).isEqualTo("OPT");
            int options = Integer.parseInt(reader.nextToken());
            List<int[]> cppOptions = new ArrayList<>(options);
            List<float[]> cppFeat = new ArrayList<>(options);
            for (int i = 0; i < options; ++i) {
                assertThat(reader.nextToken()).isEqualTo("OPTLINE");
                cppOptions.add(GoldenReader.parseCounts(reader.nextToken()));
                assertThat(reader.nextToken()).isEqualTo("AFEAT");
                int m = Integer.parseInt(reader.nextToken());
                assertThat(m).isEqualTo(PerfectDouFeatures.ACTION_SIZE);
                cppFeat.add(reader.readFloats(m));
            }

            int[] toBeat = state.getLastMove() == null
                    ? new int[PerfectDouFeatures.RANKS]
                    : PerfectDouFeatures.countsOf(state.getLastMove());
            List<int[]> mine = PerfectDouMoves.legal(observed.myHand, toBeat);
            diff.compareOptionSet(decisions, mine, cppOptions);

            for (int i = 0; i < options; ++i) {
                Move match = PerfectDouMoves.toMove(cppOptions.get(i), turn);
                float[] feat = new float[PerfectDouFeatures.ACTION_SIZE];
                PerfectDouFeatures.actionFeature(observed, match, feat, 0);
                diff.compareBlock("actionFeat[" + GoldenReader.cardsOf(cppOptions.get(i)) + "]",
                        decisions, feat, cppFeat.get(i));
            }

            assertThat(reader.nextToken()).isEqualTo("LOGIT");
            int logitCount = Integer.parseInt(reader.nextToken());
            reader.readFloats(logitCount);   // logits 交给推理侧的对齐测试比对

            assertThat(reader.nextToken()).isEqualTo("CHOSEN");
            int chosenSlot = Integer.parseInt(reader.nextToken());
            String chosenCards = reader.nextToken();

            assertThat(reader.nextToken()).isEqualTo("ACT");
            assertThat(Integer.parseInt(reader.nextToken())).isEqualTo(turn);
            int[] playedCounts = GoldenReader.parseCounts(reader.nextToken());
            assertThat(GoldenReader.cardsOf(playedCounts)).isEqualTo(chosenCards);
            assertThat(chosenSlot).as("槽位必须落在候选范围内").isBetween(0, logitCount - 1);

            diff.noteChosen(decisions, mine, playedCounts, chosenSlot);
            state.applyMove(PerfectDouMoves.toMove(playedCounts, turn));

            assertThat(reader.nextToken()).isEqualTo("ENDDEC");
            decisions++;
        }
    }

    private static Hand parseHand(String cards) {
        Hand hand = new Hand();
        for (char ch : cards.toCharArray()) {
            hand.add(Rank.fromSymbol(String.valueOf(ch)));
        }
        return hand;
    }

    /** 收集全部差异后一次性报告, 免得第一处失败就丢掉上下文 */
    private static final class Diff {
        private final List<String> problems = new ArrayList<>();
        private int featureMismatches;
        private int setMismatches;

        void compareBlock(String name, int decision, float[] mine, float[] golden) {
            for (int i = 0; i < golden.length; ++i) {
                if (mine[i] != golden[i]) {
                    ++featureMismatches;
                    if (problems.size() < 20) {
                        problems.add(String.format("%s 第%d个决策 [%d]: java=%s cpp=%s",
                                name, decision, i, mine[i], golden[i]));
                    }
                    return;
                }
            }
        }

        void compareOptionSet(int decision, List<int[]> mine, List<int[]> cpp) {
            Set<Long> myKeys = keys(mine);
            Set<Long> cppKeys = keys(cpp);
            if (myKeys.equals(cppKeys)) {
                return;
            }
            ++setMismatches;
            if (problems.size() >= 20) {
                return;
            }
            List<String> onlyCpp = new ArrayList<>();
            for (int[] c : cpp) {
                if (!myKeys.contains(PerfectDouMoves.key(c))) {
                    onlyCpp.add(PerfectDouFeatures.debugCards(c));
                }
            }
            List<String> onlyMine = new ArrayList<>();
            for (int[] c : mine) {
                if (!cppKeys.contains(PerfectDouMoves.key(c))) {
                    onlyMine.add(PerfectDouFeatures.debugCards(c));
                }
            }
            problems.add(String.format("第%d个决策候选集合不同 cpp=%d java=%d 仅C++有%s 仅Java有%s",
                    decision, cpp.size(), mine.size(), onlyCpp, onlyMine));
        }

        /** C++ 认为最优的那手必须确实在候选集中 */
        void noteChosen(int decision, List<int[]> mine, int[] chosen, int slot) {
            if (!keys(mine).contains(PerfectDouMoves.key(chosen))) {
                ++setMismatches;
                problems.add(String.format("第%d个决策 C++ 选择 %s (槽位%d) 不在 Java 候选集中",
                        decision, PerfectDouFeatures.debugCards(chosen), slot));
            }
        }

        String report() {
            if (problems.isEmpty()) {
                return "";
            }
            return String.join("\n", problems)
                    + String.format("%n特征差异 %d 处, 候选集合差异 %d 处",
                    featureMismatches, setMismatches);
        }
    }

    private static Set<Long> keys(List<int[]> options) {
        Set<Long> out = new HashSet<>(options.size() * 2);
        for (int[] c : options) {
            out.add(PerfectDouMoves.key(c));
        }
        return out;
    }
}

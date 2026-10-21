package com.doudizhu.nn;

import com.doudizhu.game.GameState;
import com.doudizhu.game.PublicView;
import com.doudizhu.model.Hand;
import com.doudizhu.model.Move;
import com.doudizhu.model.Rank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端校验原生推理: Java + DJL 打出的 logits 必须与训练侧 C++ (libtorch) 一致。
 *
 * <p>两侧 libtorch 版本不同 (导出用 2.7.1, golden 由训练机的 2.14 生成), 允许 1e-3 量级的
 * 浮点噪声, 但 argmax 必须完全相同。
 */
class ActorNetworkGoldenTest {

    private static final String GOLDEN = "/golden/golden24.txt.gz";
    private static final Path MODEL_DIR = Path.of("models/ts");

    @Test
    @DisplayName("原生推理对齐：DJL 打出的 logits 与 C++ 一致且 argmax 完全相同")
    void logitsMatchCppReference() throws Exception {
        assertThat(Path.of("src/test/resources" + GOLDEN)).as("golden 文件").exists();
        try (ActorNetwork network = ActorNetwork.load(MODEL_DIR)) {
            GoldenReader reader = GoldenReader.open(GOLDEN);
            int decisions = 0;
            int argmaxMismatch = 0;
            double worst = 0;
            int worstDecision = -1;
            long nanos = 0;
            try {
                while (reader.hasNext()) {
                    String key = reader.nextToken();
                    if ("DDZGOLDEN".equals(key)) {
                        reader.skipLine();
                        continue;
                    }
                    if (!"GAME".equals(key)) {
                        throw new IllegalStateException("意外的字段 " + key);
                    }
                    reader.nextToken();   // 局号
                    List<Hand> hands = new ArrayList<>();
                    List<Rank> bottom = new ArrayList<>();
                    for (int s = 0; s < 3; ++s) {
                        assertThat(reader.nextToken()).isEqualTo("DEAL");
                        reader.nextToken();
                        hands.add(parseHand(reader.nextToken()));
                    }
                    assertThat(reader.nextToken()).isEqualTo("BOTTOM");
                    for (char ch : reader.nextToken().toCharArray()) {
                        bottom.add(Rank.fromSymbol(String.valueOf(ch)));
                    }
                    assertThat(reader.nextToken()).isEqualTo("LANDLORD");
                    int landlordId = Integer.parseInt(reader.nextToken());

                    GameState state = new GameState(hands, landlordId, bottom);
                    while (true) {
                        String marker = reader.nextToken();
                        if ("ENDGAME".equals(marker)) {
                            break;
                        }
                        assertThat(marker).isEqualTo("DEC");
                        reader.nextToken();
                        assertThat(reader.nextToken()).isEqualTo("TURN");
                        int turn = Integer.parseInt(reader.nextToken());
                        assertThat(reader.nextToken()).isEqualTo("HIST");
                        reader.nextToken();
                        assertThat(state.getActivePlayerIndex()).isEqualTo(turn);

                        PublicView view = state.getPublicView(turn);
                        PerfectDouFeatures.View observed = PerfectDouFeatures.observe(view);
                        float[] xImp = new float[PerfectDouFeatures.NODE_SIZE];
                        float[] seq = new float[PerfectDouFeatures.SEQ_SIZE];
                        PerfectDouFeatures.encode(observed, xImp, seq);

                        assertThat(reader.nextToken()).isEqualTo("XIMP");
                        reader.readFloats(Integer.parseInt(reader.nextToken()));
                        assertThat(reader.nextToken()).isEqualTo("SEQ");
                        reader.readFloats(Integer.parseInt(reader.nextToken()));

                        assertThat(reader.nextToken()).isEqualTo("OPT");
                        int options = Integer.parseInt(reader.nextToken());
                        float[] feats = new float[options * PerfectDouFeatures.ACTION_SIZE];
                        for (int i = 0; i < options; ++i) {
                            assertThat(reader.nextToken()).isEqualTo("OPTLINE");
                            reader.nextToken();   // 牌面, 已由特征测试校验
                            assertThat(reader.nextToken()).isEqualTo("AFEAT");
                            System.arraycopy(reader.readFloats(
                                    Integer.parseInt(reader.nextToken())), 0, feats,
                                    i * PerfectDouFeatures.ACTION_SIZE, PerfectDouFeatures.ACTION_SIZE);
                        }

                        assertThat(reader.nextToken()).isEqualTo("LOGIT");
                        float[] cppLogits = reader.readFloats(Integer.parseInt(reader.nextToken()));

                        long start = System.nanoTime();
                        float[] mine = network.score(turn, xImp, seq, feats, options);
                        nanos += System.nanoTime() - start;

                        for (int i = 0; i < options; ++i) {
                            double d = Math.abs(mine[i] - cppLogits[i]);
                            if (d > worst) {
                                worst = d;
                                worstDecision = decisions;
                            }
                        }
                        if (argmax(mine) != argmax(cppLogits)) {
                            argmaxMismatch++;
                        }

                        assertThat(reader.nextToken()).isEqualTo("CHOSEN");
                        reader.nextToken();
                        reader.nextToken();
                        assertThat(reader.nextToken()).isEqualTo("ACT");
                        assertThat(Integer.parseInt(reader.nextToken())).isEqualTo(turn);
                        Move played = PerfectDouMoves.toMove(
                                GoldenReader.parseCounts(reader.nextToken()), turn);
                        assertThat(reader.nextToken()).isEqualTo("ENDDEC");
                        state.applyMove(played);
                        decisions++;
                    }
                }
            } finally {
                reader.close();
            }

            System.out.printf("对齐 %d 个决策: 最大 logit 偏差 %.3e (第%d个决策), argmax 不一致 %d 个, "
                            + "平均单次推理 %.2f ms%n",
                    decisions, worst, worstDecision, argmaxMismatch,
                    decisions == 0 ? 0 : nanos / 1e6 / decisions);
            assertThat(decisions).isGreaterThan(700);
            assertThat(worst).as("最大 logit 偏差").isLessThan(1e-3);
            assertThat(argmaxMismatch).as("argmax 不一致数").isZero();
        }
    }

    private static int argmax(float[] values) {
        int best = 0;
        for (int i = 1; i < values.length; ++i) {
            if (values[i] > values[best]) {
                best = i;
            }
        }
        return best;
    }

    private static Hand parseHand(String cards) {
        Hand hand = new Hand();
        for (char ch : cards.toCharArray()) {
            hand.add(Rank.fromSymbol(String.valueOf(ch)));
        }
        return hand;
    }
}

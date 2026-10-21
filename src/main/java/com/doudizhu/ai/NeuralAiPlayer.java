package com.doudizhu.ai;

import com.doudizhu.game.PublicView;
import com.doudizhu.model.Move;
import com.doudizhu.nn.ActorNetwork;
import com.doudizhu.nn.PerfectDouFeatures;
import com.doudizhu.nn.PerfectDouMoves;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 强化学习策略网络玩家：完整枚举合法动作，逐手打分后直接取最高分出牌。
 *
 * <p>权重由 PerfectDou 式自对弈强化学习训练 (见 PerfectDouCPP)，输入编码与动作空间在
 * {@link PerfectDouFeatures} / {@link PerfectDouMoves} 中与训练侧逐位对齐，观察只使用公开信息。
 * 推理走 DJL 的原生 libtorch，{@code DecisionResult} 里的概率是 softmax 后的策略偏好，
 * 不是搜索胜率。
 */
public class NeuralAiPlayer implements AiPlayer {

    /** 仓库内自带的权重目录 */
    public static final Path DEFAULT_MODEL_DIR = Path.of("models/ts");

    private final ActorNetwork network;

    public NeuralAiPlayer(ActorNetwork network) {
        this.network = network;
    }

    /** 加载仓库默认目录 {@code models/ts} 下的权重 */
    public static NeuralAiPlayer create() throws IOException {
        return fromDirectory(DEFAULT_MODEL_DIR);
    }

    /** 从权重目录 (含 actor0_ts.pt .. actor2_ts.pt) 创建；可多座位共享同一实例 */
    public static NeuralAiPlayer fromDirectory(Path dir) throws IOException {
        return new NeuralAiPlayer(ActorNetwork.load(dir));
    }

    @Override
    public AiDecision decide(PublicView publicView) {
        long startTime = System.currentTimeMillis();
        int myId = publicView.getViewingPlayerId();
        if (publicView.getMyHand().isEmpty()) {
            return AiDecision.of(Move.pass(myId), 0);
        }

        PerfectDouFeatures.View observed = PerfectDouFeatures.observe(publicView);
        float[] xImp = new float[PerfectDouFeatures.NODE_SIZE];
        float[] seq = new float[PerfectDouFeatures.SEQ_SIZE];
        PerfectDouFeatures.encode(observed, xImp, seq);

        int[] toBeat = publicView.getLastMove() == null
                ? new int[PerfectDouFeatures.RANKS]
                : PerfectDouFeatures.countsOf(publicView.getLastMove());
        List<int[]> options = PerfectDouMoves.legal(observed.myHandCounts(), toBeat);
        int n = options.size();

        Move[] moves = new Move[n];
        float[] actionFeat = new float[n * PerfectDouFeatures.ACTION_SIZE];
        for (int i = 0; i < n; ++i) {
            moves[i] = PerfectDouMoves.toMove(options.get(i), myId);
            PerfectDouFeatures.actionFeature(observed, moves[i], actionFeat,
                    i * PerfectDouFeatures.ACTION_SIZE);
        }

        float[] scores = network.score(observed.actorIndex(), xImp, seq, actionFeat, n);
        int best = 0;
        for (int i = 1; i < n; ++i) {
            if (scores[i] > scores[best]) {
                best = i;
            }
        }

        return new AiDecision(moves[best], probabilityTable(moves, scores),
                System.currentTimeMillis() - startTime);
    }

    /** 候选动作按策略概率从高到低排列，供前端展示思路 */
    private static List<MoveScore> probabilityTable(Move[] moves, float[] scores) {
        double max = scores[0];
        for (float s : scores) {
            max = Math.max(max, s);
        }
        double[] exp = new double[scores.length];
        double sum = 0;
        for (int i = 0; i < scores.length; ++i) {
            exp[i] = Math.exp(scores[i] - max);
            sum += exp[i];
        }
        List<MoveScore> out = new ArrayList<>(moves.length);
        for (int i = 0; i < moves.length; ++i) {
            out.add(new MoveScore(moves[i], sum == 0 ? 0 : exp[i] / sum));
        }
        out.sort(Comparator.comparingDouble(MoveScore::probability).reversed());
        return out;
    }
}

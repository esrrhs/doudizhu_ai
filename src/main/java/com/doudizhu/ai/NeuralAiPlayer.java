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
 * 强化学习策略网络玩家：对当前所有合法动作打分，直接取最高分出牌。
 *
 * <p>与 {@link PimcAiPlayer} 的采样搜索是两条独立路线：这里的权重由 PerfectDou 式自对弈 RL
 * 训练得到，动作空间用 {@link PerfectDouMoves} 完整枚举 (与训练侧一致)，观察特征只用公开信息。
 * 推理走 DJL 的原生 libtorch，因此依赖是 optional 的；不加载权重时核心引擎不受影响。
 *
 * <p>{@code DecisionResult} 里的 winRate 字段在本网压下填的是 softmax 后的**策略概率**，
 * 不是搜索胜率。
 */
public class NeuralAiPlayer implements AiPlayer {

    private final ActorNetwork network;

    public NeuralAiPlayer(ActorNetwork network) {
        this.network = network;
    }

    /** 从权重目录 (含 actor0_ts.pt .. actor2_ts.pt) 创建，权重常驻复用，可多座位共享同一实例 */
    public static NeuralAiPlayer fromDirectory(Path dir) throws IOException {
        return new NeuralAiPlayer(ActorNetwork.load(dir));
    }

    @Override
    public PimcAiPlayer.DecisionResult decide(PublicView publicView) {
        long startTime = System.currentTimeMillis();
        int myId = publicView.getViewingPlayerId();
        if (publicView.getMyHand().isEmpty()) {
            return new PimcAiPlayer.DecisionResult(Move.pass(myId), List.of(), 0);
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

        List<PimcAiPlayer.MoveEvaluation> evaluations = probabilityTable(moves, scores);
        return new PimcAiPlayer.DecisionResult(moves[best], evaluations,
                System.currentTimeMillis() - startTime);
    }

    /** 候选动作按策略概率从高到低排列，供前端展示思路 */
    private static List<PimcAiPlayer.MoveEvaluation> probabilityTable(Move[] moves, float[] scores) {
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
        List<PimcAiPlayer.MoveEvaluation> out = new ArrayList<>(moves.length);
        for (int i = 0; i < moves.length; ++i) {
            PimcAiPlayer.MoveEvaluation eval = new PimcAiPlayer.MoveEvaluation(moves[i]);
            eval.record(1, sum == 0 ? 0 : exp[i] / sum);
            out.add(eval);
        }
        out.sort(Comparator.comparingDouble(PimcAiPlayer.MoveEvaluation::getAverageWinRate).reversed());
        return out;
    }
}

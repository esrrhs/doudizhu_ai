package com.doudizhu.ai;

import com.doudizhu.game.PublicView;

/**
 * 出牌决策器的统一入口
 *
 * <p>{@link PimcAiPlayer} (采样 + MCTS 搜索) 与 {@link NeuralAiPlayer} (强化学习策略网络)
 * 都实现本接口，调用方按接口持有即可在两种引擎之间切换。
 */
public interface AiPlayer {

    PimcAiPlayer.DecisionResult decide(PublicView publicView);
}

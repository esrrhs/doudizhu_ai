package com.doudizhu.ai;

import com.doudizhu.game.PublicView;

/**
 * 出牌决策器的统一入口。
 *
 * <p>目前只有 {@link NeuralAiPlayer}（强化学习策略网络）实现该接口，保留接口是为了
 * 让牌局状态机、网页端与命令行不必绑定具体决策实现。
 */
public interface AiPlayer {

    AiDecision decide(PublicView publicView);
}

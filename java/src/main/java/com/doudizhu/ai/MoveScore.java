package com.doudizhu.ai;

import com.doudizhu.model.Move;

/**
 * 一个候选动作及其被选中的概率。
 *
 * <p>概率由策略网络对各候选动作的打分做 softmax 归一化得到，表示模型的**策略偏好**，
 * 不是搜索统计出来的胜率。
 */
public record MoveScore(Move move, double probability) {

    @Override
    public String toString() {
        return String.format("%s -> 策略概率: %5.1f%%", move.toCardString(), probability * 100.0);
    }
}

// 手数下界 oracle: 一手牌最少几步出完 (PerfectDou 论文附录 E.1 算法 1)。
// 移植自 Java nn/Oracle.java (上游 PerfectDouCPP src/ddz/oracle.cpp)。
//
// 该值直接参与神经网络输入特征 (每手候选牌减去后的剩余手数), 因此必须与训练侧逐位一致。
#pragma once

#include "doudizhu/model.h"

namespace doudizhu
{

// 一手牌的最少出牌步数
int MinSteps(const CardSet &counts);

} // namespace doudizhu

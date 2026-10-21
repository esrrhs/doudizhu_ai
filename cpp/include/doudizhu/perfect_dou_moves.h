// 与训练侧一致的动作枚举与牌型判定 (移植自 Java nn/PerfectDouMoves.java, 上游为
// PerfectDouCPP src/ddz/moves.cpp)。
//
// 引擎自带的 rules/MoveGenerator 对「飞机带翅」只生成一种带牌组合, 四带二也不允许用一对
// 当两单; 而策略网络是在完整枚举所有具体带牌组合的动作空间上训练的。为了让网络拿到与训练
// 时一致的动作集合, 这里独立实现完整枚举。
#pragma once

#include <vector>

#include "doudizhu/model.h"

namespace doudizhu
{

// 牌型信息: type + 主牌点数索引 (链牌为链的最小点数) + 链长
struct Info
{
    int type = MT_WRONG;
    int rank = -1;
    int len = 1;
};

// 判定一手工牌; 无法归类时 type 为 MT_WRONG
Info Detect(const CardSet &cards);

// 当前手的合法动作 (多重集)。应牌时最后一项恒为过牌; 自由出牌不含过牌。
// toBeat 全零表示自由出牌 (Java PerfectDouMoves.legal)
std::vector<CardSet> Legal(const CardSet &hand, const CardSet &toBeat);

// 把多重集转成引擎的 Move; 无法归类时抛 std::invalid_argument
Move ToMove(const CardSet &counts, int playerId, const Info &info);
Move ToMove(const CardSet &counts, int playerId);

} // namespace doudizhu

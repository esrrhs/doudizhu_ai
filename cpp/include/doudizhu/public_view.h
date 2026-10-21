// 玩家观察视角 (不完全信息, 严格隔离对手私有手牌)。对应 Java game/PublicView.java。
#pragma once

#include <array>
#include <optional>
#include <vector>

#include "doudizhu/model.h"

namespace doudizhu
{

struct PublicView
{
    int viewingPlayerId = 0;
    int landlordId = 0;
    CardSet bottom;                        // 底牌 (地主已并入手牌, 这里只记牌面)
    CardSet myHand;
    std::array<int, 3> cardCounts{};       // 各家剩余张数, 下标 = playerId
    std::vector<Move> moveHistory;         // 该局已发生的全部动作, 含过牌
    std::optional<Move> lastMove;          // 需要压过的牌, 空表示自由出牌
    int lastMovePlayerId = -1;
    int activePlayerId = 0;
    int passCount = 0;

    int GetCardCount(int playerId) const { return cardCounts[static_cast<std::size_t>(playerId)]; }

    // 所有对手当前可能持有的未知牌池 = 54 张初始牌库 - 我的私有手牌 - 场上已打出的历史牌
    CardSet ComputeUnseenCards() const;
};

} // namespace doudizhu

// 玩家观察视角实现。
#include "doudizhu/public_view.h"

namespace doudizhu
{

CardSet PublicView::ComputeUnseenCards() const
{
    // 54 张初始牌库
    CardSet deck;
    for (int r = 0; r < kNormalRanks; ++r)
    {
        deck[r] = 4;
    }
    deck[13] = 1;
    deck[14] = 1;

    // 移除自己手里的牌
    for (int r = 0; r < kRanks; ++r)
    {
        deck[r] -= myHand[r];
    }
    // 移除历史上已经打出的牌
    for (const Move &m : moveHistory)
    {
        if (m.IsPass())
        {
            continue;
        }
        for (int r = 0; r < kRanks; ++r)
        {
            deck[r] -= m.cards[r];
        }
    }
    return deck;
}

} // namespace doudizhu

// 领域类型实现: CardSet 指纹与训练侧牌面读写, Move 构造。
#include "doudizhu/model.h"

#include <stdexcept>

namespace doudizhu
{

int CardSet::Total() const
{
    int s = 0;
    for (int c : cnt)
    {
        s += c;
    }
    return s;
}

long long Key(const CardSet &counts)
{
    long long k = 0;
    for (int r = 0; r < kRanks; ++r)
    {
        k |= static_cast<long long>(counts[r]) << (3 * r);
    }
    return k;
}

Move MakePass(int playerId)
{
    Move m;
    m.type = CardType::Pass;
    m.mainRank = 0;
    m.playerId = playerId;
    return m;
}

Move MakeMove(CardType type, int mainRank, const CardSet &cards, int playerId)
{
    Move m;
    m.type = type;
    m.mainRank = mainRank;
    m.cards = cards;
    m.playerId = playerId;
    return m;
}

bool Move::CanBeat(const Move *other) const
{
    if (other == nullptr || other->IsPass())
    {
        return !IsPass();
    }
    if (IsPass())
    {
        return false;
    }
    if (IsRocket())
    {
        return true; // 王炸 (火箭) 压制一切牌型
    }
    if (other->IsRocket())
    {
        return false;
    }
    if (IsBomb() && !other->IsBomb())
    {
        return true; // 炸弹压制普通牌型
    }
    if (IsBomb() && other->IsBomb())
    {
        return mainRank > other->mainRank; // 炸弹对比炸弹
    }
    if (type == other->type && CardCount() == other->CardCount())
    {
        return mainRank > other->mainRank; // 牌型一致且张数相同才可比
    }
    return false;
}

std::string DebugCards(const CardSet &counts)
{
    static constexpr char chars[kRanks] = {'3', '4', '5', '6', '7', '8', '9',
                                           'T', 'J', 'Q', 'K', 'A', '2', 'B', 'R'};
    std::string out;
    for (int r = 0; r < kRanks; ++r)
    {
        out.append(static_cast<std::size_t>(counts[r]), chars[r]);
    }
    if (out.empty())
    {
        out = "-";
    }
    return out;
}

CardSet ParseCards(std::string_view cards)
{
    static constexpr char chars[kRanks] = {'3', '4', '5', '6', '7', '8', '9',
                                           'T', 'J', 'Q', 'K', 'A', '2', 'B', 'R'};
    CardSet out;
    if (cards.empty() || (cards.size() == 1 && cards[0] == '-'))
    {
        return out;
    }
    for (char ch : cards)
    {
        int rank = -1;
        for (int r = 0; r < kRanks; ++r)
        {
            if (chars[r] == ch)
            {
                rank = r;
                break;
            }
        }
        if (rank < 0)
        {
            throw std::invalid_argument(std::string("无法识别的点数字符: ") + ch);
        }
        out[rank]++;
    }
    return out;
}

} // namespace doudizhu

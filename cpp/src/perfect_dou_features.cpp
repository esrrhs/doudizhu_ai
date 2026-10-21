// PerfectDou 网络输入编码实现, 逐分支移植自 Java nn/PerfectDouFeatures.java。
#include "doudizhu/perfect_dou_features.h"

#include <algorithm>
#include <stdexcept>

#include "doudizhu/oracle.h"

namespace doudizhu
{
namespace
{

// 矩阵内的行偏移
constexpr int kRowSolo = 4;
constexpr int kRowPair = 5;
constexpr int kRowTrio = 6;
constexpr int kRowBomb = 7;
constexpr int kRowRocket = 8;
constexpr int kRowSoloChain = 9;
constexpr int kRowPairChain = 10;
constexpr int kRowTrioChain = 11;

// 该点数是否落在一条长度足够的连续链里 (只有 3..A 能成链, 2 与王被点数差自动切断)
bool InChain(const CardSet &cnt, int r, int minLen, int need)
{
    int lo = r;
    int hi = r;
    while (lo - 1 >= 0 && cnt[lo - 1] >= need && RankValue(lo) - RankValue(lo - 1) == 1)
    {
        --lo;
    }
    while (hi + 1 < kNormalRanks && cnt[hi + 1] >= need && RankValue(hi + 1) - RankValue(hi) == 1)
    {
        ++hi;
    }
    return hi - lo + 1 >= minLen;
}

void OneHot(float *dst, int off, int n, int value)
{
    if (value >= 0 && value < n)
    {
        dst[off + value] = 1.0f;
    }
}

// 同类型中的最大一手 (再没有能压它的同型牌)
bool IsLargest(const Move &move)
{
    const int rankIdx = move.mainRank - 3; // 链牌的 mainRank 已是链的最大点数
    switch (move.type)
    {
    case CardType::Rocket:
        return true;
    case CardType::Single:
        return rankIdx == 14; // 大王
    case CardType::Bomb:
    case CardType::Pair:
    case CardType::Triple:
    case CardType::TriplePlusOne:
    case CardType::TriplePlusPair:
    case CardType::FourPlusTwo:
    case CardType::FourPlusTwoPairs:
        return rankIdx == 12; // 2
    case CardType::Straight:
    case CardType::ConsecutivePairs:
    case CardType::Airplane:
    case CardType::AirplanePlusSingles:
    case CardType::AirplanePlusPairs:
        return rankIdx == 11; // 链尾到 A
    default:
        return false;
    }
}

} // namespace

CardSet CountsOf(const Move &move)
{
    return move.cards; // 过牌的 cards 恒为全零
}

void CardMatrix(const CardSet &cnt, float *dst, int off)
{
    std::fill(dst + off, dst + off + kCardMatrix, 0.0f);
    for (int r = 0; r < kRanks; ++r)
    {
        for (int k = 0; k < cnt[r]; ++k)
        {
            dst[off + k * kRanks + r] = 1.0f; // 第 k 行: 该点数至少出现 k+1 次
        }
        if (cnt[r] >= 1)
        {
            dst[off + kRowSolo * kRanks + r] = 1.0f;
        }
        if (cnt[r] >= 2)
        {
            dst[off + kRowPair * kRanks + r] = 1.0f;
        }
        if (cnt[r] >= 3)
        {
            dst[off + kRowTrio * kRanks + r] = 1.0f;
        }
        if (r < kNormalRanks && cnt[r] == 4)
        {
            dst[off + kRowBomb * kRanks + r] = 1.0f;
        }
    }
    if (cnt[13] != 0 && cnt[14] != 0)
    {
        dst[off + kRowRocket * kRanks + 13] = 1.0f; // 王炸同时点亮两张
        dst[off + kRowRocket * kRanks + 14] = 1.0f;
    }
    for (int r = 0; r < kNormalRanks; ++r)
    {
        if (InChain(cnt, r, kMinStraight, 1))
        {
            dst[off + kRowSoloChain * kRanks + r] = 1.0f;
        }
        if (InChain(cnt, r, kMinPairChain, 2))
        {
            dst[off + kRowPairChain * kRanks + r] = 1.0f;
        }
        if (InChain(cnt, r, kMinPlane, 3))
        {
            dst[off + kRowTrioChain * kRanks + r] = 1.0f;
        }
    }
}

View Observe(const PublicView &view)
{
    const int seat = view.viewingPlayerId;
    if (seat != view.activePlayerId)
    {
        throw std::invalid_argument("Observe() 需要行动者本人的视角");
    }
    View v;
    v.seat = seat;
    v.prev = (seat + 2) % 3;
    v.nxt = (seat + 1) % 3;
    v.landlordId = view.landlordId;
    v.actorIndex = ActorIndex(seat, view.landlordId);
    v.lastPlayer = view.landlordId;

    for (int value = 3; value <= 17; ++value)
    {
        v.myHand[value - 3] = view.myHand[value - 3];
    }
    v.bottom = view.bottom;
    for (int id = 0; id < 3; ++id)
    {
        v.handCount[id] = view.GetCardCount(id);
    }

    // 逐手回放公开历史: 各家已出牌、各家最近一手动作、炸弹数、最后实际出牌者
    std::array<CardSet, 3> lastOf{};
    for (const Move &m : view.moveHistory)
    {
        const int id = m.playerId;
        const CardSet c = CountsOf(m);
        lastOf[static_cast<std::size_t>(id)] = c;
        if (!m.IsPass())
        {
            v.lastPlayer = id;
            for (int r = 0; r < kRanks; ++r)
            {
                v.played[static_cast<std::size_t>(id)][r] += c[r];
            }
            if (m.IsBomb() || m.IsRocket())
            {
                v.bombCount++;
            }
        }
    }
    for (int id = 0; id < 3; ++id)
    {
        v.lastAction[static_cast<std::size_t>(id)] = lastOf[static_cast<std::size_t>(id)];
    }

    // 历史矩阵: 取最近 30 手 (含过牌), 按时间顺序放在尾部, 前部补空矩阵
    const int hlen = std::min(kHistoryLen, static_cast<int>(view.moveHistory.size()));
    const int total = static_cast<int>(view.moveHistory.size());
    for (int i = 0; i < hlen; ++i)
    {
        const CardSet c = CountsOf(view.moveHistory[static_cast<std::size_t>(total - hlen + i)]);
        const int slot = (kHistoryLen - hlen + i) * kRanks;
        for (int r = 0; r < kRanks; ++r)
        {
            v.history[static_cast<std::size_t>(slot + r)] = c[r];
        }
    }

    // 底牌要扣掉地主已经打出的那部分 (按点数取交集)
    for (int r = 0; r < kRanks; ++r)
    {
        v.bottom[r] -= std::min(v.bottom[r], v.played[static_cast<std::size_t>(v.landlordId)][r]);
    }

    // 两家合计的未知牌池 = 整副牌 - 我的手牌 - 场上已打出的牌
    for (int r = 0; r < kRanks; ++r)
    {
        const int deck = r < kNormalRanks ? 4 : 1;
        const int seen = v.myHand[r] + v.played[0][r] + v.played[1][r] + v.played[2][r];
        v.others[r] = deck - seen;
    }
    return v;
}

void Encode(const View &v, float *xImp, float *seq)
{
    CardMatrix(v.myHand, xImp, 0 * kCardMatrix);
    CardMatrix(v.others, xImp, 1 * kCardMatrix);
    CardMatrix(v.played[static_cast<std::size_t>(v.seat)], xImp, 2 * kCardMatrix);
    CardMatrix(v.played[static_cast<std::size_t>(v.prev)], xImp, 3 * kCardMatrix);
    CardMatrix(v.played[static_cast<std::size_t>(v.nxt)], xImp, 4 * kCardMatrix);
    CardMatrix(v.bottom, xImp, 5 * kCardMatrix);
    CardMatrix(v.lastAction[static_cast<std::size_t>(v.seat)], xImp, 6 * kCardMatrix);
    CardMatrix(v.lastAction[static_cast<std::size_t>(v.prev)], xImp, 7 * kCardMatrix);
    CardMatrix(v.lastAction[static_cast<std::size_t>(v.nxt)], xImp, 8 * kCardMatrix);

    // 56 个标量 (复用缓冲区时须先清零)
    const int s = kNodeBin;
    std::fill(xImp + s, xImp + s + kImpScalars, 0.0f);
    OneHot(xImp, s, 20, v.handCount[static_cast<std::size_t>(v.prev)] - 1);
    OneHot(xImp, s + 20, 20, v.handCount[static_cast<std::size_t>(v.nxt)] - 1);
    OneHot(xImp, s + 40, 15, std::min(v.bombCount, 14));
    xImp[s + 55] = v.lastPlayer == v.seat ? 1.0f : 0.0f;

    if (seq != nullptr)
    {
        // 30 手历史矩阵顺序铺平, 每 3 手构成一个 LSTM 时间步
        CardSet cnt;
        for (int k = 0; k < kHistoryLen; ++k)
        {
            for (int r = 0; r < kRanks; ++r)
            {
                cnt[r] = v.history[static_cast<std::size_t>(k * kRanks + r)];
            }
            CardMatrix(cnt, seq, k * kCardMatrix);
        }
    }
}

void ActionFeature(const View &v, const Move &move, float *out, int off)
{
    const CardSet chosen = CountsOf(move);
    CardMatrix(chosen, out, off);
    const int size = move.IsPass() ? 0 : move.CardCount();

    const int e = off + kCardMatrix;
    out[e] = (move.IsBomb() || move.IsRocket()) ? 1.0f : 0.0f;
    out[e + 1] = IsLargest(move) ? 1.0f : 0.0f;
    out[e + 2] = (size > 0 && size == v.handCount[static_cast<std::size_t>(v.nxt)]) ? 1.0f : 0.0f;
    out[e + 3] = (size > 0 && size == v.handCount[static_cast<std::size_t>(v.prev)]) ? 1.0f : 0.0f;

    CardSet after;
    for (int r = 0; r < kRanks; ++r)
    {
        after[r] = v.myHand[r] - chosen[r];
    }
    out[e + 4] = static_cast<float>(MinSteps(after)) / 20.0f;
    out[e + 5] = 1.0f; // 有效动作标记, 用于区分全零矩阵的过牌
}

int ActorIndex(int playerId, int landlordId)
{
    return (playerId - landlordId + 3) % 3;
}

} // namespace doudizhu

// 与训练侧一致的动作枚举与牌型判定, 逐分支移植自 Java nn/PerfectDouMoves.java
// (上游为 PerfectDouCPP src/ddz/moves.cpp)。枚举顺序与去重方式都保持与 Java 一致,
// 以便读代码时可以两份并排对照。
#include "doudizhu/perfect_dou_moves.h"

#include <functional>
#include <stdexcept>
#include <unordered_set>
#include <utility>

namespace doudizhu
{
namespace
{

// ---------------------------------------------------------------------------
// 牌型判定
// ---------------------------------------------------------------------------

std::vector<int> ValuesOf(const CardSet &m)
{
    std::vector<int> v;
    for (int r = 0; r < kRanks; ++r)
    {
        for (int k = 0; k < m[r]; ++k)
        {
            v.push_back(RankValue(r));
        }
    }
    return v;
}

// 点数索引列表 -> 训练侧数值列表
std::vector<int> RanksToValues(const std::vector<int> &ranks)
{
    std::vector<int> v;
    v.reserve(ranks.size());
    for (int r : ranks)
    {
        v.push_back(RankValue(r));
    }
    return v;
}

bool Continuous(const std::vector<int> &v)
{
    for (std::size_t i = 1; i < v.size(); ++i)
    {
        if (v[i] - v[i - 1] != 1)
        {
            return false;
        }
    }
    return true;
}

bool ContinuousSub(const std::vector<int> &ranks, int from, int len)
{
    std::vector<int> v;
    v.reserve(static_cast<std::size_t>(len));
    for (int i = 0; i < len; ++i)
    {
        v.push_back(RankValue(ranks[static_cast<std::size_t>(from + i)]));
    }
    return Continuous(v);
}

CardSet FromRanks(const std::vector<int> &ranks, int begin, int len, int repeat)
{
    CardSet m;
    for (int i = begin; i < begin + len; ++i)
    {
        m[ranks[static_cast<std::size_t>(i)]] += repeat;
    }
    return m;
}

} // namespace

Info Detect(const CardSet &m)
{
    const int size = m.Total();
    if (size == 0)
    {
        return Info{MT_PASS, -1, 1};
    }
    if (size == 1)
    {
        for (int r = 0; r < kRanks; ++r)
        {
            if (m[r] != 0)
            {
                return Info{MT_SINGLE, r, 1};
            }
        }
    }
    if (size == 2)
    {
        for (int r = 0; r < kNormalRanks; ++r)
        {
            if (m[r] == 2)
            {
                return Info{MT_PAIR, r, 1};
            }
        }
        if (m[13] == 1 && m[14] == 1)
        {
            return Info{MT_ROCKET, -1, 1};
        }
        return Info{MT_WRONG, -1, 1};
    }
    if (size == 3)
    {
        for (int r = 0; r < kRanks; ++r)
        {
            if (m[r] == 3)
            {
                return Info{MT_TRIPLE, r, 1};
            }
        }
        return Info{MT_WRONG, -1, 1};
    }
    if (size == 4)
    {
        for (int r = 0; r < kNormalRanks; ++r)
        {
            if (m[r] == 4)
            {
                return Info{MT_BOMB, r, 1};
            }
        }
        int distinct = 0;
        int triRank = -1;
        for (int r = 0; r < kRanks; ++r)
        {
            if (m[r] == 3)
            {
                triRank = r;
                ++distinct;
            }
            else if (m[r] == 1)
            {
                ++distinct;
            }
            else if (m[r] != 0)
            {
                return Info{MT_WRONG, -1, 1};
            }
        }
        if (distinct == 2 && triRank >= 0)
        {
            return Info{MT_TRIPLE_ONE, triRank, 1};
        }
        return Info{MT_WRONG, -1, 1};
    }

    std::vector<int> vals = ValuesOf(m);
    if (Continuous(vals))
    {
        return Info{MT_STRAIGHT, ValueRank(vals[0]), size};
    }

    if (size == 5)
    {
        int distinct = 0;
        int triRank = -1;
        for (int r = 0; r < kRanks; ++r)
        {
            if (m[r] == 3)
            {
                triRank = r;
                ++distinct;
            }
            else if (m[r] == 2)
            {
                ++distinct;
            }
            else if (m[r] != 0)
            {
                return Info{MT_WRONG, -1, 1};
            }
        }
        if (distinct == 2 && triRank >= 0)
        {
            return Info{MT_TRIPLE_TWO, triRank, 1};
        }
        return Info{MT_WRONG, -1, 1};
    }

    int byCount[5] = {0, 0, 0, 0, 0};
    int distinct = 0;
    for (int r = 0; r < kRanks; ++r)
    {
        if (m[r] > 0)
        {
            ++byCount[m[r]];
            ++distinct;
        }
    }

    if (size == 6)
    {
        bool shape = (distinct == 2 || distinct == 3) && byCount[4] == 1 && (byCount[2] == 1 || byCount[1] == 2);
        if (shape)
        {
            int rank = -1;
            for (int r = 0; r < kRanks; ++r)
            {
                if (m[r] == 4)
                {
                    rank = r;
                    break;
                }
            }
            return Info{MT_FOUR_TWO, rank, 1};
        }
    }
    if (size == 8)
    {
        bool shape = ((distinct == 3 || distinct == 2) && byCount[4] == 1 && byCount[2] == 2) || byCount[4] == 2;
        if (shape)
        {
            int rank = -1;
            for (int r = kRanks - 1; r >= 0; --r)
            {
                if (m[r] == 4)
                {
                    rank = r;
                    break;
                }
            }
            return Info{MT_FOUR_TWO_PAIR, rank, 1};
        }
    }

    std::vector<int> present;
    present.reserve(kRanks);
    for (int r = 0; r < kRanks; ++r)
    {
        if (m[r] > 0)
        {
            present.push_back(r);
        }
    }
    const int np = static_cast<int>(present.size());

    if (distinct == byCount[2])
    {
        std::vector<int> pairVals;
        pairVals.reserve(np);
        for (int i = 0; i < np; ++i)
        {
            pairVals.push_back(RankValue(present[static_cast<std::size_t>(i)]));
        }
        if (Continuous(pairVals))
        {
            return Info{MT_PAIR_STRAIGHT, present[0], np};
        }
    }
    if (distinct == byCount[3])
    {
        std::vector<int> trioVals;
        trioVals.reserve(np);
        for (int i = 0; i < np; ++i)
        {
            trioVals.push_back(RankValue(present[static_cast<std::size_t>(i)]));
        }
        if (Continuous(trioVals))
        {
            return Info{MT_PLANE, present[0], np};
        }
    }

    if (byCount[3] >= kMinPlane)
    {
        std::vector<int> triRanks;
        std::vector<int> soloRanks;
        std::vector<int> pairRanks;
        bool bad = false;
        for (int r = 0; r < kRanks; ++r)
        {
            if (m[r] == 3)
            {
                triRanks.push_back(r);
            }
            else if (m[r] == 1)
            {
                soloRanks.push_back(r);
            }
            else if (m[r] == 2)
            {
                pairRanks.push_back(r);
            }
            else if (m[r] != 0)
            {
                bad = true;
                break;
            }
        }
        if (!bad)
        {
            if (Continuous(RanksToValues(triRanks)))
            {
                const int nTri = static_cast<int>(triRanks.size());
                if (nTri == static_cast<int>(soloRanks.size()) + 2 * static_cast<int>(pairRanks.size()))
                {
                    return Info{MT_PLANE_SOLO, triRanks[0], nTri};
                }
                if (nTri == static_cast<int>(pairRanks.size()) && distinct == 2 * nTri)
                {
                    return Info{MT_PLANE_PAIR, triRanks[0], nTri};
                }
            }
            // DouZero 边界: 四个连续三张可以当作「三张链 + 第四个三张拆成三张单翅」
            if (triRanks.size() == 4)
            {
                if (ContinuousSub(triRanks, 1, 3))
                {
                    return Info{MT_PLANE_SOLO, triRanks[1], 3};
                }
                if (ContinuousSub(triRanks, 0, 3))
                {
                    return Info{MT_PLANE_SOLO, triRanks[0], 3};
                }
            }
        }
    }
    return Info{MT_WRONG, -1, 1};
}

namespace
{

// ---------------------------------------------------------------------------
// 生成器 (与训练侧 MoveGenerator 的成员函数逐一对应)
// ---------------------------------------------------------------------------

// 在 items 上枚举取 num 个位置的所有组合 (同值的不同位置会产生重复多重集, 由 Dedupe 去除)
using ComboSink = std::function<void(const std::vector<int> &idx)>;

void ForEachCombination(const std::vector<int> &items, int num, const ComboSink &fn)
{
    const int n = static_cast<int>(items.size());
    if (num > n || num <= 0)
    {
        return;
    }
    std::vector<int> idx(static_cast<std::size_t>(num));
    for (int i = 0; i < num; ++i)
    {
        idx[static_cast<std::size_t>(i)] = i;
    }
    for (;;)
    {
        fn(idx);
        int i = num - 1;
        while (i >= 0 && idx[static_cast<std::size_t>(i)] == i + n - num)
        {
            --i;
        }
        if (i < 0)
        {
            return;
        }
        ++idx[static_cast<std::size_t>(i)];
        for (int j = i + 1; j < num; ++j)
        {
            idx[static_cast<std::size_t>(j)] = idx[static_cast<std::size_t>(j - 1)] + 1;
        }
    }
}

std::vector<CardSet> Dedupe(const std::vector<CardSet> &moves)
{
    std::unordered_set<long long> seen;
    seen.reserve(moves.size() * 2);
    std::vector<CardSet> out;
    out.reserve(moves.size());
    for (const CardSet &m : moves)
    {
        if (seen.insert(Key(m)).second)
        {
            out.push_back(m);
        }
    }
    return out;
}

class Generator
{
public:
    explicit Generator(const CardSet &hand) : hand_(hand)
    {
        for (int r = 0; r < kRanks; ++r)
        {
            if (hand_[r] > 0)
            {
                presentRanks_.push_back(r);
            }
        }
    }

    std::vector<CardSet> GenAll() const
    {
        std::vector<CardSet> out;
        Append(&out, GenSingles());
        Append(&out, GenPairs());
        Append(&out, GenTriples());
        Append(&out, GenBombs());
        Append(&out, GenRocket());
        Append(&out, GenTripleSolo());
        Append(&out, GenTriplePair());
        Append(&out, GenSerials(1, kMinStraight, 0));
        Append(&out, GenSerials(2, kMinPairChain, 0));
        Append(&out, GenSerials(3, kMinPlane, 0));
        Append(&out, GenPlaneSolo(0));
        Append(&out, GenPlanePair(0));
        Append(&out, GenFourTwo());
        Append(&out, GenFourTwoPair());
        return Dedupe(out);
    }

    std::vector<CardSet> GenByType(int type, int len) const
    {
        std::vector<CardSet> out;
        switch (type)
        {
        case MT_SINGLE:
            out = GenSingles();
            break;
        case MT_PAIR:
            out = GenPairs();
            break;
        case MT_TRIPLE:
            out = GenTriples();
            break;
        case MT_BOMB:
            out = GenBombs();
            Append(&out, GenRocket());
            break;
        case MT_TRIPLE_ONE:
            out = GenTripleSolo();
            break;
        case MT_TRIPLE_TWO:
            out = GenTriplePair();
            break;
        case MT_STRAIGHT:
            out = GenSerials(1, kMinStraight, len);
            break;
        case MT_PAIR_STRAIGHT:
            out = GenSerials(2, kMinPairChain, len);
            break;
        case MT_PLANE:
            out = GenSerials(3, kMinPlane, len);
            break;
        case MT_PLANE_SOLO:
            out = GenPlaneSolo(len);
            break;
        case MT_PLANE_PAIR:
            out = GenPlanePair(len);
            break;
        case MT_FOUR_TWO:
            out = GenFourTwo();
            break;
        case MT_FOUR_TWO_PAIR:
            out = GenFourTwoPair();
            break;
        default:
            break;
        }
        return Dedupe(out);
    }

private:
    static void Append(std::vector<CardSet> *dst, const std::vector<CardSet> &src)
    {
        dst->insert(dst->end(), src.begin(), src.end());
    }

    std::vector<CardSet> GenSingles() const
    {
        std::vector<CardSet> r;
        for (int rank : presentRanks_)
        {
            CardSet m;
            m[rank] = 1;
            r.push_back(m);
        }
        return r;
    }

    std::vector<CardSet> GenPairs() const
    {
        std::vector<CardSet> r;
        for (int rank = 0; rank < kRanks; ++rank)
        {
            if (hand_[rank] >= 2)
            {
                CardSet m;
                m[rank] = 2;
                r.push_back(m);
            }
        }
        return r;
    }

    std::vector<CardSet> GenTriples() const
    {
        std::vector<CardSet> r;
        for (int rank = 0; rank < kRanks; ++rank)
        {
            if (hand_[rank] >= 3)
            {
                CardSet m;
                m[rank] = 3;
                r.push_back(m);
            }
        }
        return r;
    }

    std::vector<CardSet> GenBombs() const
    {
        std::vector<CardSet> r;
        for (int rank = 0; rank < kNormalRanks; ++rank)
        {
            if (hand_[rank] == 4)
            {
                CardSet m;
                m[rank] = 4;
                r.push_back(m);
            }
        }
        return r;
    }

    std::vector<CardSet> GenRocket() const
    {
        std::vector<CardSet> r;
        if (hand_[13] != 0 && hand_[14] != 0)
        {
            CardSet m;
            m[13] = 1;
            m[14] = 1;
            r.push_back(m);
        }
        return r;
    }

    std::vector<CardSet> GenTripleSolo() const
    {
        std::vector<CardSet> r;
        for (int t = 0; t < kRanks; ++t)
        {
            if (hand_[t] < 3)
            {
                continue;
            }
            for (int k : presentRanks_)
            {
                if (k == t)
                {
                    continue;
                }
                CardSet m;
                m[t] = 3;
                m[k] += 1;
                r.push_back(m);
            }
        }
        return r;
    }

    std::vector<CardSet> GenTriplePair() const
    {
        std::vector<CardSet> r;
        for (int t = 0; t < kRanks; ++t)
        {
            if (hand_[t] < 3)
            {
                continue;
            }
            for (int p = 0; p < kNormalRanks; ++p)
            {
                if (p == t || hand_[p] < 2)
                {
                    continue;
                }
                CardSet m;
                m[t] = 3;
                m[p] = 2;
                r.push_back(m);
            }
        }
        return r;
    }

    // repeat = 1 顺子 / 2 连对 / 3 飞机; fixedLen 为 0 时枚举所有长度
    std::vector<CardSet> GenSerials(int repeat, int minLen, int fixedLen) const
    {
        std::vector<int> ranks;
        ranks.reserve(kRanks);
        for (int r = 0; r < kRanks; ++r)
        {
            if (hand_[r] >= repeat)
            {
                ranks.push_back(r);
            }
        }
        std::vector<std::pair<int, int>> runs; // 每项 {起始下标, 段长}
        int i = 0;
        const int n = static_cast<int>(ranks.size());
        while (i < n)
        {
            int j = i;
            while (j + 1 < n && RankValue(ranks[static_cast<std::size_t>(j + 1)]) -
                                          RankValue(ranks[static_cast<std::size_t>(j)]) ==
                                  1)
            {
                ++j;
            }
            runs.emplace_back(i, j - i + 1);
            i = j + 1;
        }
        std::vector<CardSet> out;
        for (const auto &run : runs)
        {
            const int start = run.first;
            const int longest = run.second;
            if (longest < minLen)
            {
                continue;
            }
            if (fixedLen > 0)
            {
                if (longest < fixedLen)
                {
                    continue;
                }
                for (int idx = 0; idx + fixedLen <= longest; ++idx)
                {
                    out.push_back(FromRanks(ranks, start + idx, fixedLen, repeat));
                }
            }
            else
            {
                for (int len = minLen; len <= longest; ++len)
                {
                    for (int idx = 0; idx + len <= longest; ++idx)
                    {
                        out.push_back(FromRanks(ranks, start + idx, len, repeat));
                    }
                }
            }
        }
        return out;
    }

    std::vector<CardSet> GenPlaneSolo(int fixedLen) const
    {
        std::vector<CardSet> planes = GenSerials(3, kMinPlane, fixedLen);
        std::vector<CardSet> out;
        for (const CardSet &pl : planes)
        {
            std::vector<int> rest; // 拆掉整条三张后的实际牌
            int chainLen = 0;
            for (int r = 0; r < kRanks; ++r)
            {
                if (pl[r] == 3)
                {
                    ++chainLen;
                    continue;
                }
                for (int k = 0; k < hand_[r]; ++k)
                {
                    rest.push_back(r);
                }
            }
            ForEachCombination(rest, chainLen, [&](const std::vector<int> &idx) {
                CardSet m = pl;
                for (int i : idx)
                {
                    m[rest[static_cast<std::size_t>(i)]] += 1;
                }
                out.push_back(m);
            });
        }
        return out;
    }

    std::vector<CardSet> GenPlanePair(int fixedLen) const
    {
        std::vector<CardSet> planes = GenSerials(3, kMinPlane, fixedLen);
        std::vector<CardSet> out;
        for (const CardSet &pl : planes)
        {
            std::vector<int> candidates;
            int chainLen = 0;
            for (int r = 0; r < kRanks; ++r)
            {
                if (pl[r] == 3)
                {
                    ++chainLen;
                    continue;
                }
                if (hand_[r] >= 2 && r < kNormalRanks)
                {
                    candidates.push_back(r);
                }
            }
            ForEachCombination(candidates, chainLen, [&](const std::vector<int> &idx) {
                CardSet m = pl;
                for (int i : idx)
                {
                    m[candidates[static_cast<std::size_t>(i)]] += 2;
                }
                out.push_back(m);
            });
        }
        return out;
    }

    std::vector<CardSet> GenFourTwo() const
    {
        std::vector<CardSet> out;
        for (int f = 0; f < kNormalRanks; ++f)
        {
            if (hand_[f] != 4)
            {
                continue;
            }
            std::vector<int> rest;
            for (int r = 0; r < kRanks; ++r)
            {
                const int n = hand_[r] - (r == f ? 4 : 0);
                for (int k = 0; k < n; ++k)
                {
                    rest.push_back(r);
                }
            }
            ForEachCombination(rest, 2, [&](const std::vector<int> &idx) {
                CardSet m;
                m[f] = 4;
                m[rest[static_cast<std::size_t>(idx[0])]] += 1;
                m[rest[static_cast<std::size_t>(idx[1])]] += 1;
                out.push_back(m);
            });
        }
        return out;
    }

    std::vector<CardSet> GenFourTwoPair() const
    {
        std::vector<CardSet> out;
        for (int f = 0; f < kNormalRanks; ++f)
        {
            if (hand_[f] != 4)
            {
                continue;
            }
            std::vector<int> pairRanks;
            for (int r = 0; r < kNormalRanks; ++r)
            {
                if (r != f && hand_[r] >= 2)
                {
                    pairRanks.push_back(r);
                }
            }
            ForEachCombination(pairRanks, 2, [&](const std::vector<int> &idx) {
                CardSet m;
                m[f] = 4;
                m[pairRanks[static_cast<std::size_t>(idx[0])]] += 2;
                m[pairRanks[static_cast<std::size_t>(idx[1])]] += 2;
                out.push_back(m);
            });
        }
        return out;
    }

    CardSet hand_;
    std::vector<int> presentRanks_;
};

// ---------------------------------------------------------------------------
// 压牌过滤
// ---------------------------------------------------------------------------

bool Beats(const CardSet &move, const CardSet &rival, int type)
{
    Info a = Detect(move);
    Info b = Detect(rival);
    if (a.type == MT_ROCKET)
    {
        return true;
    }
    if (a.type == MT_BOMB && type != MT_BOMB)
    {
        return true;
    }
    switch (type)
    {
    case MT_SINGLE:
    case MT_PAIR:
    case MT_TRIPLE:
    case MT_BOMB:
    case MT_STRAIGHT:
    case MT_PAIR_STRAIGHT:
    case MT_PLANE:
        return a.type == type && a.len == b.len && a.rank > b.rank;
    case MT_TRIPLE_ONE:
    case MT_TRIPLE_TWO:
    case MT_FOUR_TWO:
    case MT_FOUR_TWO_PAIR:
        return a.type == type && a.rank > b.rank;
    case MT_PLANE_SOLO:
    case MT_PLANE_PAIR:
    {
        if (a.type != type || a.len != b.len)
        {
            return false;
        }
        int ra = -1;
        int rb = -1;
        for (int r = 0; r < kRanks; ++r)
        {
            if (move[r] == 3)
            {
                ra = r; // 最高的三张点数
            }
            if (rival[r] == 3)
            {
                rb = r;
            }
        }
        return ra > rb;
    }
    default:
        return false;
    }
}

} // namespace

// ---------------------------------------------------------------------------
// 与引擎 Move 的互转
// ---------------------------------------------------------------------------

namespace
{
CardType CardTypeOf(int mtType)
{
    switch (mtType)
    {
    case MT_PASS:
        return CardType::Pass;
    case MT_SINGLE:
        return CardType::Single;
    case MT_PAIR:
        return CardType::Pair;
    case MT_TRIPLE:
        return CardType::Triple;
    case MT_BOMB:
        return CardType::Bomb;
    case MT_ROCKET:
        return CardType::Rocket;
    case MT_TRIPLE_ONE:
        return CardType::TriplePlusOne;
    case MT_TRIPLE_TWO:
        return CardType::TriplePlusPair;
    case MT_STRAIGHT:
        return CardType::Straight;
    case MT_PAIR_STRAIGHT:
        return CardType::ConsecutivePairs;
    case MT_PLANE:
        return CardType::Airplane;
    case MT_PLANE_SOLO:
        return CardType::AirplanePlusSingles;
    case MT_PLANE_PAIR:
        return CardType::AirplanePlusPairs;
    case MT_FOUR_TWO:
        return CardType::FourPlusTwo;
    case MT_FOUR_TWO_PAIR:
        return CardType::FourPlusTwoPairs;
    default:
        break;
    }
    // Java 侧返回 null, 由 ToMove 抛出; 这里用 MT_WRONG 表示无法归类
    return CardType::Pass;
}
} // namespace

Move ToMove(const CardSet &counts, int playerId, const Info &info)
{
    if (info.type == MT_PASS)
    {
        return MakePass(playerId);
    }
    if (info.type == MT_WRONG || info.type > MT_FOUR_TWO_PAIR)
    {
        throw std::invalid_argument("无法归类的牌: " + DebugCards(counts));
    }
    // 链牌的 mainRank 用链的最大点数, 与 Java / 引擎 MoveGenerator 的约定一致
    const int topIndex = info.type == MT_ROCKET ? 14 : info.rank + (info.len > 1 ? info.len - 1 : 0);
    return MakeMove(CardTypeOf(info.type), topIndex + 3, counts, playerId);
}

Move ToMove(const CardSet &counts, int playerId)
{
    return ToMove(counts, playerId, Detect(counts));
}

std::vector<CardSet> Legal(const CardSet &hand, const CardSet &toBeat)
{
    if (toBeat.Empty())
    {
        return Generator(hand).GenAll();
    }
    Info rival = Detect(toBeat);
    if (rival.type == MT_ROCKET)
    {
        return std::vector<CardSet>{CardSet{}}; // 只有过牌
    }
    Generator gen(hand);
    std::vector<CardSet> moves;
    if (rival.type == MT_BOMB)
    {
        for (const CardSet &m : gen.GenByType(MT_BOMB, 0))
        {
            if (Beats(m, toBeat, MT_BOMB))
            {
                moves.push_back(m);
            }
        }
    }
    else
    {
        for (const CardSet &m : gen.GenByType(rival.type, rival.len))
        {
            if (Beats(m, toBeat, rival.type))
            {
                moves.push_back(m);
            }
        }
        for (const CardSet &m : gen.GenByType(MT_BOMB, 0))
        {
            if (Beats(m, toBeat, rival.type))
            {
                moves.push_back(m);
            }
        }
    }
    moves.emplace_back(); // 过牌
    return moves;
}

} // namespace doudizhu

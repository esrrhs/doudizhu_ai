// 手数下界 oracle 实现, 逐分支移植自 Java nn/Oracle.java。
//
// 无王牌手牌按「组计数」(a 单张 / b 对子 / c 三张 / d 炸弹) 做 DP 查表,
// 顺子/连对/飞机由外层记忆化 DFS 枚举。
#include "doudizhu/oracle.h"

#include <array>
#include <cstdint>
#include <functional>

namespace doudizhu
{
namespace
{

constexpr int kMaxA = 22;
constexpr int kMaxB = 15;
constexpr int kMaxC = 9;
constexpr int kMaxD = 6;
constexpr int kTableSize = kMaxA * kMaxB * kMaxC * kMaxD;

constexpr int kImpossible = 100;

// F(a,b,c,d) 打平成一维, -1 表示尚未计算; 首次使用时建表,
// 与 Java 的类初始化、C++ 训练侧的 call_once 语义等价
class GroupTable
{
public:
    GroupTable()
    {
        f_.fill(static_cast<std::int8_t>(-1));
        Set(0, 0, 0, 0, 0);
        for (int total = 1; total <= 20; ++total)
        {
            for (int a = 0; a < kMaxA; ++a)
            {
                for (int b = 0; b < kMaxB; ++b)
                {
                    for (int c = 0; c < kMaxC; ++c)
                    {
                        for (int d = 0; d < kMaxD; ++d)
                        {
                            if (a + 2 * b + 3 * c + 4 * d != total)
                            {
                                continue;
                            }
                            BuildOne(a, b, c, d);
                        }
                    }
                }
            }
        }
    }

    // 组计数表的读取入口 (Java fDP/fGet)
    int Get(int a, int b, int c, int d) const
    {
        if (a < 0 || b < 0 || c < 0 || d < 0)
        {
            return kImpossible;
        }
        if (a >= kMaxA || b >= kMaxB || c >= kMaxC || d >= kMaxD)
        {
            return a + b + c + d;
        }
        const std::int8_t v = f_[static_cast<std::size_t>(Index(a, b, c, d))];
        return v < 0 ? a + b + c + d : v;
    }

private:
    static int Index(int a, int b, int c, int d)
    {
        return ((a * kMaxB + b) * kMaxC + c) * kMaxD + d;
    }

    void Set(int a, int b, int c, int d, int value)
    {
        f_[static_cast<std::size_t>(Index(a, b, c, d))] = static_cast<std::int8_t>(value);
    }

    void BuildOne(int a, int b, int c, int d)
    {
        int best = a + b + c + d;
        if (a >= 1)
        {
            best = Smaller(best, 1 + Get(a - 1, b, c, d));
        }
        if (b >= 1)
        {
            best = Smaller(best, 1 + Get(a, b - 1, c, d));
        }
        if (c >= 1)
        {
            best = Smaller(best, 1 + Get(a, b, c - 1, d));
        }
        if (d >= 1)
        {
            best = Smaller(best, 1 + Get(a, b, c, d - 1));
        }
        if (c >= 1)
        {
            EnumLooseKickers(a, b, c - 1, d, 1, [&](int na, int nb, int nc, int nd) {
                best = Smaller(best, 1 + Get(na, nb, nc, nd));
            });
        }
        if (c >= 1)
        {
            if (b >= 1)
            {
                best = Smaller(best, 1 + Get(a, b - 1, c - 1, d));
            }
            if (c >= 2)
            {
                best = Smaller(best, 1 + Get(a + 1, b, c - 2, d));
            }
            if (d >= 1)
            {
                best = Smaller(best, 1 + Get(a, b, c - 1, d - 1));
            }
        }
        if (d >= 1)
        {
            EnumLooseKickers(a, b, c, d - 1, 2, [&](int na, int nb, int nc, int nd) {
                best = Smaller(best, 1 + Get(na, nb, nc, nd));
            });
        }
        if (d >= 1)
        {
            if (b >= 2)
            {
                best = Smaller(best, 1 + Get(a, b - 2, c, d - 1));
            }
            if (b >= 1 && c >= 1)
            {
                best = Smaller(best, 1 + Get(a + 1, b - 1, c - 1, d - 1));
            }
            if (c >= 2)
            {
                best = Smaller(best, 1 + Get(a + 2, b, c - 2, d - 1));
            }
        }
        Set(a, b, c, d, best);
    }

    static int Smaller(int x, int y) { return y < x ? y : x; }

    // 枚举「拿 need 张散牌」的所有拆组方式, 拆剩的组自动降档
    static void EnumLooseKickers(int a, int b, int c, int d, int need,
                                const std::function<void(int, int, int, int)> &fn)
    {
        for (int s = 0; s <= Min(need, a); ++s)
        {
            const int r1 = need - s;
            if (r1 == 0)
            {
                fn(a - s, b, c, d);
                continue;
            }
            for (int bp = 0; bp <= Min(b, r1); ++bp)
            {
                for (int pu = Max(bp, 0); pu <= Min(2 * bp, r1); ++pu)
                {
                    const int r2 = r1 - pu;
                    const int pairLeftSingles = 2 * bp - pu;
                    if (r2 == 0)
                    {
                        fn(a - s + pairLeftSingles, b - bp, c, d);
                        continue;
                    }
                    for (int bt = 0; bt <= Min(c, r2); ++bt)
                    {
                        for (int tu = bt; tu <= Min(3 * bt, r2); ++tu)
                        {
                            const int r3 = r2 - tu;
                            for (int t2 = 0; t2 <= bt; ++t2)
                            {
                                for (int t1 = 0; t1 + t2 <= bt; ++t1)
                                {
                                    const int t3 = bt - t1 - t2;
                                    if (t1 + 2 * t2 + 3 * t3 != tu)
                                    {
                                        continue;
                                    }
                                    const int r4 = r3;
                                    if (r4 == 0)
                                    {
                                        fn(a - s + pairLeftSingles + t2, b - bp + t1, c - bt, d);
                                        continue;
                                    }
                                    for (int bd = 0; bd <= Min(d, r4); ++bd)
                                    {
                                        for (int bu = bd; bu <= Min(4 * bd, r4); ++bu)
                                        {
                                            if (bu != r4)
                                            {
                                                continue;
                                            }
                                            for (int u3 = 0; u3 <= bd; ++u3)
                                            {
                                                for (int u2 = 0; u2 + u3 <= bd; ++u2)
                                                {
                                                    for (int u1 = 0; u1 + u2 + u3 <= bd; ++u1)
                                                    {
                                                        const int u4 = bd - u1 - u2 - u3;
                                                        if (u1 + 2 * u2 + 3 * u3 + 4 * u4 != bu)
                                                        {
                                                            continue;
                                                        }
                                                        fn(a - s + pairLeftSingles + t2 + u3,
                                                           b - bp + t1 + u2,
                                                           c - bt + u1, d - bd);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    static int Min(int x, int y) { return x < y ? x : y; }
    static int Max(int x, int y) { return x > y ? x : y; }

    std::array<std::int8_t, kTableSize> f_;
};

const GroupTable &Table()
{
    // 函数内静态: 首次调用时构造, 之后只读
    static const GroupTable table;
    return table;
}

// 直接映射缓存, 与训练侧同为 65536 槽; 按线程隔离, 对应 Java 的 ThreadLocal
constexpr int kCacheSlots = 65536;
constexpr int kCacheMask = kCacheSlots - 1;

struct Memo
{
    std::array<std::int64_t, kCacheSlots> keys{};
    std::array<std::int8_t, kCacheSlots> vals{};
};

Memo &ThreadMemo()
{
    static thread_local Memo memo;
    return memo;
}

std::int64_t PackKey(const CardSet &cnt)
{
    return static_cast<std::int64_t>(Key(cnt));
}

// MurmurHash3 64 位混淆
int HashKey(std::int64_t k)
{
    std::uint64_t x = static_cast<std::uint64_t>(k);
    x ^= x >> 33;
    x *= 0xff51afd7ed558ccdULL;
    x ^= x >> 33;
    x *= 0xc4ceb9fe1a85ec53ULL;
    x ^= x >> 33;
    return static_cast<int>(x & static_cast<std::uint64_t>(kCacheMask));
}

// 是否存在可能的顺子/连对/飞机链, 用于走无链快路径
bool HasPotentialChain(const CardSet &cnt)
{
    int maxConsecutive = 0;
    int curConsecutive = 0;
    int maxPairChain = 0;
    int curPairChain = 0;
    int maxPlane = 0;
    int curPlane = 0;
    for (int r = 0; r < kNormalRanks; ++r)
    {
        const int c = cnt[r];
        if (c >= 1)
        {
            ++curConsecutive;
            maxConsecutive = maxConsecutive > curConsecutive ? maxConsecutive : curConsecutive;
        }
        else
        {
            curConsecutive = 0;
        }
        if (c >= 2)
        {
            ++curPairChain;
            maxPairChain = maxPairChain > curPairChain ? maxPairChain : curPairChain;
        }
        else
        {
            curPairChain = 0;
        }
        if (c >= 3)
        {
            ++curPlane;
            maxPlane = maxPlane > curPlane ? maxPlane : curPlane;
        }
        else
        {
            curPlane = 0;
        }
    }
    return maxConsecutive >= kMinStraight || maxPairChain >= kMinPairChain || maxPlane >= kMinPlane;
}

// 忽略链牌的组数下界
int LowerBound(const CardSet &cnt)
{
    int a = 0;
    int b = 0;
    int c = 0;
    int d = 0;
    for (int r = 0; r < kNormalRanks; ++r)
    {
        if (cnt[r] == 1)
        {
            ++a;
        }
        else if (cnt[r] == 2)
        {
            ++b;
        }
        else if (cnt[r] == 3)
        {
            ++c;
        }
        else if (cnt[r] == 4)
        {
            ++d;
        }
    }
    int jokers = (cnt[13] != 0 ? 1 : 0) + (cnt[14] != 0 ? 1 : 0);
    if (cnt[13] != 0 && cnt[14] != 0)
    {
        jokers = 1; // 王炸算一步
    }
    return Table().Get(a, b, c, d) + jokers;
}

using WingSink = std::function<void(const int *pick)>;

// 逐点枚举 need 张带的全部多重集合
void EnumWingMultisets(const CardSet &cnt, int rank, int need, CardSet &pick, const WingSink &fn)
{
    if (need == 0)
    {
        fn(pick.cnt.data());
        return;
    }
    if (rank == kRanks)
    {
        return;
    }
    const int mx = cnt[rank] < need ? cnt[rank] : need;
    for (int take = 0; take <= mx; ++take)
    {
        pick[rank] = take;
        EnumWingMultisets(cnt, rank + 1, need - take, pick, fn);
    }
    pick[rank] = 0;
}

int Solve(const CardSet &cnt);

void EnumerateChains(const CardSet &cnt, int repeat, int minLen, bool trios, int *best);

// 飞机带对: 从可对的点数里选 len 个不同点数的组合
void ChoosePairCombos(const CardSet &rem, const std::vector<int> &pairRanks, int len,
                      int idx, int start, std::vector<int> &sel, int *best)
{
    if (idx == len)
    {
        CardSet after = rem;
        for (int r : sel)
        {
            after[r] -= 2;
        }
        if (1 + LowerBound(after) < *best)
        {
            const int solved = Solve(after);
            if (1 + solved < *best)
            {
                *best = 1 + solved;
            }
        }
        return;
    }
    const int np = static_cast<int>(pairRanks.size());
    for (int x = start; x + (len - idx - 1) < np; ++x)
    {
        sel[static_cast<std::size_t>(idx)] = pairRanks[static_cast<std::size_t>(x)];
        ChoosePairCombos(rem, pairRanks, len, idx + 1, x + 1, sel, best);
    }
}

// 在连续可连的区段上枚举所有链长与起点, 必要时再枚举带牌
void EnumerateChains(const CardSet &cnt, int repeat, int minLen, bool trios, int *best)
{
    int i = 0;
    while (i < kNormalRanks)
    {
        if (cnt[i] < repeat)
        {
            ++i;
            continue;
        }
        int j = i;
        while (j + 1 < kNormalRanks && cnt[j + 1] >= repeat && RankValue(j + 1) - RankValue(j) == 1)
        {
            ++j;
        }
        const int runLen = j - i + 1;
        for (int len = minLen; len <= runLen; ++len)
        {
            for (int s = i; s + len <= j + 1; ++s)
            {
                CardSet rem = cnt;
                for (int x = 0; x < len; ++x)
                {
                    rem[s + x] -= repeat;
                }
                if (1 + LowerBound(rem) < *best)
                {
                    const int solved = Solve(rem);
                    if (1 + solved < *best)
                    {
                        *best = 1 + solved;
                    }
                }
                if (trios)
                {
                    if (4 * len <= 20)
                    {
                        CardSet pick;
                        EnumWingMultisets(rem, 0, len, pick, [&](const int *w) {
                            CardSet after = rem;
                            for (int r = 0; r < kRanks; ++r)
                            {
                                after[r] -= w[r];
                            }
                            if (1 + LowerBound(after) < *best)
                            {
                                const int solved = Solve(after);
                                if (1 + solved < *best)
                                {
                                    *best = 1 + solved;
                                }
                            }
                        });
                    }
                    if (5 * len <= 20)
                    {
                        std::vector<int> pairRanks;
                        for (int r = 0; r < kNormalRanks; ++r)
                        {
                            if (rem[r] >= 2)
                            {
                                pairRanks.push_back(r);
                            }
                        }
                        if (static_cast<int>(pairRanks.size()) >= len)
                        {
                            std::vector<int> sel(static_cast<std::size_t>(len), 0);
                            ChoosePairCombos(rem, pairRanks, len, 0, 0, sel, best);
                        }
                    }
                }
            }
        }
        i = j + 1;
    }
}

int Solve(const CardSet &cnt)
{
    const std::int64_t key = PackKey(cnt);
    if (key == 0)
    {
        return 0;
    }
    Memo &memo = ThreadMemo();
    const int h = HashKey(key);
    if (memo.keys[static_cast<std::size_t>(h)] == key && memo.vals[static_cast<std::size_t>(h)] > 0)
    {
        return memo.vals[static_cast<std::size_t>(h)];
    }

    // 无链可能时组数下界即为精确解
    if (!HasPotentialChain(cnt))
    {
        const int ans = LowerBound(cnt);
        memo.keys[static_cast<std::size_t>(h)] = key;
        memo.vals[static_cast<std::size_t>(h)] = static_cast<std::int8_t>(ans);
        return ans;
    }

    int best = kImpossible;
    if (cnt[13] != 0 || cnt[14] != 0)
    {
        CardSet rem = cnt;
        rem[13] = 0;
        rem[14] = 0;
        const int solved = 1 + Solve(rem);
        if (solved < best)
        {
            best = solved;
        }
    }

    int a = 0;
    int b = 0;
    int c = 0;
    int d = 0;
    for (int r = 0; r < kNormalRanks; ++r)
    {
        if (cnt[r] == 1)
        {
            ++a;
        }
        else if (cnt[r] == 2)
        {
            ++b;
        }
        else if (cnt[r] == 3)
        {
            ++c;
        }
        else if (cnt[r] == 4)
        {
            ++d;
        }
    }
    int jokerGroups = (cnt[13] != 0 ? 1 : 0) + (cnt[14] != 0 ? 1 : 0);
    if (cnt[13] != 0 && cnt[14] != 0)
    {
        jokerGroups = 1;
    }
    const int grouped = Table().Get(a, b, c, d) + jokerGroups;
    if (grouped < best)
    {
        best = grouped;
    }

    if (best > 1)
    {
        EnumerateChains(cnt, 1, kMinStraight, false, &best);
        EnumerateChains(cnt, 2, kMinPairChain, false, &best);
        EnumerateChains(cnt, 3, kMinPlane, true, &best);
    }

    memo.keys[static_cast<std::size_t>(h)] = key;
    memo.vals[static_cast<std::size_t>(h)] = static_cast<std::int8_t>(best);
    return best;
}

} // namespace

int MinSteps(const CardSet &counts)
{
    return Solve(counts);
}

} // namespace doudizhu

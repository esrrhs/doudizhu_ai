// 领域类型: 点数序、牌型编码、15 维张数集合 CardSet 与一手牌 Move。
// 对应 Java model/Rank.java、model/CardType.java、model/Move.java。
#pragma once

#include <array>
#include <string>
#include <string_view>

namespace doudizhu
{

// 牌索引 0..14 = 3 4 5 6 7 8 9 T J Q K A 2 小王 大王, 即 Java 侧 Rank.getValue() - 3。
inline constexpr int kRanks = 15;
inline constexpr int kNormalRanks = 13; // 3..A: 只有这些点数能成炸弹与链
inline constexpr int kMinStraight = 5;
inline constexpr int kMinPairChain = 3;
inline constexpr int kMinPlane = 2;

// 花色无关的点数序, 相邻差值为 1 才能连成链; A(14) 到 2(17) 的断档即由此而来,
// 因此 2 与两张王天然被切断。与训练侧 PerfectDouCPP 完全一致。
inline constexpr int kRankValues[kRanks] = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30};

// 点数索引 -> 训练侧数值
inline constexpr int RankValue(int rankIndex)
{
    return kRankValues[rankIndex];
}

// 训练侧数值 -> 点数索引
inline constexpr int ValueRank(int value)
{
    switch (value)
    {
    case 17:
        return 12;
    case 20:
        return 13;
    case 30:
        return 14;
    default:
        return value - 3;
    }
}

// 牌型编码, 与训练侧 C++ MoveType 一一对应 (Java nn/PerfectDouMoves.java 的同名常量)
enum MoveType : int
{
    MT_PASS = 0,
    MT_SINGLE = 1,
    MT_PAIR = 2,
    MT_TRIPLE = 3,
    MT_BOMB = 4,
    MT_ROCKET = 5,
    MT_TRIPLE_ONE = 6,
    MT_TRIPLE_TWO = 7,
    MT_STRAIGHT = 8,
    MT_PAIR_STRAIGHT = 9,
    MT_PLANE = 10,
    MT_PLANE_SOLO = 11,
    MT_PLANE_PAIR = 12,
    MT_FOUR_TWO = 13,
    MT_FOUR_TWO_PAIR = 14,
    MT_WRONG = 15
};

// 引擎侧牌型, 枚举顺序与 Java CardType 一致
enum class CardType : int
{
    Pass = 0,         // 过牌
    Single,           // 单张
    Pair,             // 对子
    Triple,           // 三张
    TriplePlusOne,    // 三带一
    TriplePlusPair,   // 三带二
    Straight,         // 顺子
    ConsecutivePairs, // 连对
    Airplane,         // 飞机不带
    AirplanePlusSingles, // 飞机带单
    AirplanePlusPairs,   // 飞机带对
    FourPlusTwo,         // 四带两单
    FourPlusTwoPairs,    // 四带两对
    Bomb,                // 炸弹
    Rocket               // 火箭(王炸)
};

// 一手牌在 15 个点数上的张数 (多重集); 过牌为全零
struct CardSet
{
    std::array<int, kRanks> cnt{};

    int Total() const;
    bool Empty() const { return Total() == 0; }
    int &operator[](int r) { return cnt[static_cast<std::size_t>(r)]; }
    const int &operator[](int r) const { return cnt[static_cast<std::size_t>(r)]; }
    bool operator==(const CardSet &o) const { return cnt == o.cnt; }
};

// 点数多重集的稳定 45 位指纹, 每点数占 3 位 (Java PerfectDouMoves.key)
long long Key(const CardSet &counts);

// 一手牌 (引擎动作): 牌型 + 主权值 + 具体张数 + 出牌者
struct Move
{
    CardType type = CardType::Pass;
    int mainRank = 0; // 同类型比大小的主权值, 数值 3..17; 链牌为链的最大点数
    CardSet cards;
    int playerId = 0;

    bool IsPass() const { return type == CardType::Pass; }
    bool IsBomb() const { return type == CardType::Bomb; }
    bool IsRocket() const { return type == CardType::Rocket; }
    int CardCount() const { return cards.Total(); }
    // 判断当前出牌能否压制 other (Java Move.canBeat; other 为空指针表示无人压过)
    bool CanBeat(const Move *other) const;
};

// 过牌
Move MakePass(int playerId);
// 按牌面构一手牌: mainRank 为数值 3..17
Move MakeMove(CardType type, int mainRank, const CardSet &cards, int playerId);

// 训练侧的牌面写法, 用于日志与基准记录比对; 空集为 "-"
std::string DebugCards(const CardSet &counts);
// 牌面字符串 -> 张数集合; "-" 或空串为全零, 无法识别的字符抛 std::invalid_argument
CardSet ParseCards(std::string_view cards);

} // namespace doudizhu

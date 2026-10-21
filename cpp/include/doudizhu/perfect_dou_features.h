// PerfectDou 网络输入编码 (移植自 Java nn/PerfectDouFeatures.java, 上游为
// PerfectDouCPP src/ddz/features.cpp)。
//
// 牌索引 0..14 = 3 4 5 6 7 8 9 T J Q K A 2 小王 大王, 即 Java 侧 Rank.getValue() - 3。
// 每份牌用一个 12x15 的 0/1 矩阵描述, 展平为 180 个 float, 行主序 row * 15 + rank。
//
// 一次决策的输入:
//   xImp         1676 = 9 个当前状态矩阵 (1620) + 56 个标量
//   seq          5400 = 最近 30 手动作矩阵, 每 3 手拼成 LSTM 的一个时间步, 共 10 步, 前部补零
//   actionFeat   186  = 该手牌的矩阵 (180) + 6 个动态标量 (每个候选动作一份)
// 所有输入均可由公开信息推出, 不读取任何玩家私有手牌。
#pragma once

#include <array>

#include "doudizhu/model.h"
#include "doudizhu/public_view.h"

namespace doudizhu
{

inline constexpr int kCardRows = 12;
inline constexpr int kCardMatrix = kCardRows * kRanks;  // 180
inline constexpr int kStaticMatrices = 9;
inline constexpr int kHistoryLen = 30;
inline constexpr int kLstmSteps = 10;
inline constexpr int kLstmIn = 3 * kCardMatrix;         // 540
inline constexpr int kNodeBin = kStaticMatrices * kCardMatrix;  // 1620
inline constexpr int kImpScalars = 20 + 20 + 15 + 1;    // 56
inline constexpr int kNodeSize = kNodeBin + kImpScalars; // 1676
inline constexpr int kSeqSize = kHistoryLen * kCardMatrix; // 5400
inline constexpr int kActionSize = kCardMatrix + 6;     // 186

// 一次决策的公开观察, 座位号沿用引擎的 playerId 体系。
// 训练侧用 seat 0/1/2 = 地主/地主下家/地主上家, 以 (playerId - landlordId + 3) % 3
// 还原为对应的 actor 编号。
struct View
{
    int seat = 0;
    int prev = 0;
    int nxt = 0;
    int landlordId = 0;
    CardSet myHand;
    CardSet others;                            // 两家合计的未知牌池
    CardSet bottom;                            // 已扣掉地主打出的部分
    std::array<CardSet, 3> played{};           // 各家累计已出牌
    std::array<CardSet, 3> lastAction{};       // 各家最近一手动作 (过牌为全零)
    std::array<int, 3> handCount{};            // 各家剩余张数
    std::array<int, kHistoryLen * kRanks> history{}; // 最近 30 手的张数, 时间顺序放在尾部
    int lastPlayer = 0;                        // 最后一次实际出牌者 (非过牌)
    int bombCount = 0;                         // 场上已出现的炸弹 + 王炸数
    int actorIndex = 0;                        // 该座位应使用的 actor 编号

    // 行动者自己的手牌张数 (15 维)
    const CardSet &MyHandCounts() const { return myHand; }
    // 应使用的 actor 编号: 0=地主, 1=地主下家, 2=地主上家
    int GetActorIndex() const { return actorIndex; }
};

// 由玩家视角构造观察; 调用时该玩家必须正好是行动者, 否则抛 std::invalid_argument
View Observe(const PublicView &view);

// 写入 1676 维节点向量 (xImp) 与 5400 维历史序列 (seq); seq 传 nullptr 则跳过
void Encode(const View &v, float *xImp, float *seq);

// 候选动作特征: 180 维牌矩阵 + 6 个动态标量, 写入 out 的 [off, off+186)
void ActionFeature(const View &v, const Move &move, float *out, int off);

// 一手牌在 15 个点数上的张数; 过牌为全零
CardSet CountsOf(const Move &move);

// 12x15 牌矩阵, 写入 dst 的 [off, off+180)
void CardMatrix(const CardSet &cnt, float *dst, int off);

// 该玩家应使用的 actor 编号: 0=地主, 1=地主下家, 2=地主上家
int ActorIndex(int playerId, int landlordId);

} // namespace doudizhu

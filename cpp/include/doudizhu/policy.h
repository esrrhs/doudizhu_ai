// 手写 actor 推理 (无 libtorch、无 zlib)。对应 Java nn/ActorNetwork.java 与
// ai/NeuralAiPlayer.java 的调用形状。
//
// 权重是 TorchScript 档 data/models/ts/actor{0,1,2}_ts.pt: 张量数据以 STORED(不压缩)
// 形式写成 <root>/data/<i>, 条目序号 -> 层名 -> 形状 记在同目录 actor<N>_manifest.json,
// 因此本类自己解析 zip 中央目录即可, 不引入任何第三方依赖。
//
// 网络结构 (单状态, B=1):
//   10 步 x 540 维输入过 1 层 LSTM (batch_first, 门序按 PyTorch 为 i, f, g, o),
//   只取最后一个时间步的 h;
//   node = concat(xImp[1676], h_last[128]) = 1804;
//   每个候选动作 joint = concat(node, actionFeat[186]) = 1990
//     -> relu(l1) -> relu(l2) -> relu(l3) -> relu(l4) -> head -> 一个标量。
#pragma once

#include <array>
#include <memory>
#include <string>
#include <vector>

#include "doudizhu/model.h"
#include "doudizhu/perfect_dou_features.h"
#include "doudizhu/public_view.h"

namespace doudizhu
{

// 三个座位各一份策略: 0 地主, 1 地主下家, 2 地主上家
inline constexpr int kPolicySeats = 3;
inline constexpr int kLstmHidden = 128;
inline constexpr int kNodeDim = kNodeSize + kLstmHidden;        // 1804
inline constexpr int kJointSize = kNodeDim + kActionSize;       // 1990

class Policy
{
public:
    // 仓库内自带权重目录相对仓库根的位置 (Java NeuralAiPlayer.DEFAULT_MODEL_REL)
    static const char *DefaultModelRel();
    // 按当前工作目录解析出 data/models/ts 的真实位置
    static std::string DefaultModelDir();

    Policy();
    ~Policy();

    Policy(const Policy &) = delete;
    Policy &operator=(const Policy &) = delete;

    // 从目录加载 actor0_ts.pt .. actor2_ts.pt; 失败返回 false, 原因见 Error()
    bool Load(const std::string &dir);
    const std::string &Error() const { return error_; }

    // 给单个决策的全部候选动作打分 (Java ActorNetwork.score)。
    // seat 为 actor 编号, actionFeat 为 options * 186 维按槽位紧密排列。
    std::vector<float> Score(int seat, const float *xImp, const float *seq,
                             const float *actionFeat, int options) const;

    // 一次完整决策: 观察 -> 枚举候选 -> 编码 -> 打分 -> 取最高分 (Java NeuralAiPlayer.decide)
    struct Decision
    {
        Move move;                              // 选出的动作 (手牌为空时为过牌)
        int slot = -1;                          // 选出的候选槽位
        std::vector<CardSet> options;           // 候选动作 (多重集)
        std::vector<Move> moves;                // 与候选一一对应的引擎 Move
        std::vector<float> logits;              // 各候选得分
        std::vector<float> probs;               // logits softmax 后的策略偏好
    };
    Decision Decide(const PublicView &view) const;

private:
    struct Actor;
    const Actor &ActorAt(int seat) const;

    std::array<std::unique_ptr<Actor>, kPolicySeats> actors_;
    std::string error_;
};

} // namespace doudizhu

# doudizhu_cpp 实现 (C++)

*英文版: [README.md](README.md)*

Java 版 [doudizhu_ai/java](../java) 与 Go 版 [doudizhu_ai/go](../go) 的 C++ 移植:斗地主引擎状态机、
与训练侧完全一致的动作枚举、PerfectDou 输入编码,以及**手写 float32 的策略网络推理**
(不用 libtorch、不用 ONNX runtime、不用 cgo)。库目标零第三方依赖,只有测试用系统 zlib 读 `.gz` 基准。
权重与对齐基准和 Java/Go 共用仓库根目录 [data/](../data),行为逐位对齐。

## 使用

```cpp
#include <cstdio>
#include <doudizhu/api.h>

using namespace doudizhu;

int main()
{
    const CardSet plane = ParseCards("333444");

    std::printf("%d %d %d\n", Detect(plane).type, Detect(plane).rank, Detect(plane).len);
    // 10 0 2 -> MTPlane 飞机不带

    std::printf("%d\n", MinSteps(plane)); // 1 手出完

    // 自由出牌的完整候选(飞机带翅、四带二会枚举每一种具体带牌组合)
    const std::vector<CardSet> options = Legal(ParseCards("333444567"), CardSet{});
    std::printf("%zu %s\n", options.size(), DebugCards(options[0]).c_str()); // 24 3

    // 神经网络玩家: 权重在 data/models/ts 下,座位按 (playerId - landlordId + 3) % 3 选 actor
    Policy policy;
    if (!policy.Load(Policy::DefaultModelDir()))
    {
        std::printf("加载失败: %s\n", policy.Error().c_str());
        return 1;
    }

    std::array<CardSet, 3> hands{ParseCards("334445566689TTTJKAAR"),
                                 ParseCards("4577899JQQKKAA222"),
                                 ParseCards("335677889TJJQQK2B")};
    // 地主那一家 DEAL 的 20 张已含底牌,GameState 不会再叠加底牌
    GameState state(hands, 0, ParseCards("4TK"));
    const Policy::Decision d =
        policy.Decide(state.GetPublicView(state.ActivePlayerIndex()));
    std::printf("%s slot=%d options=%zu\n", DebugCards(CountsOf(d.move)).c_str(), d.slot,
                d.options.size()); // 334455 slot=65 options=68
}
```

链接:

```bash
g++ -std=c++20 -Icpp/include main.cpp cpp/build/libdoudizhu.a
```

## 公开 API

头文件都在 `include/doudizhu/` 下,`doudizhu/api.h` 一次全部包含。
点数索引 `0..14 = 3 4 5 6 7 8 9 T J Q K A 2 小王 大王`(即 Java `Rank.getValue() - 3`),
牌集一律用 15 维张数向量 `CardSet` 表示,全零即过牌。

| C++ | 等价的 Java 调用 |
| --- | --- |
| `Key(const CardSet&) -> long long` | `PerfectDouMoves.key`(45 位多重集指纹) |
| `DebugCards(const CardSet&)` / `ParseCards(std::string_view)` | 牌面文本互转 |
| `Move::CanBeat(const Move*)` | `Move.canBeat` |
| `Detect(const CardSet&) -> Info{type, rank, len}` | `PerfectDouMoves.detect` |
| `Legal(const CardSet &hand, const CardSet &toBeat)` | `PerfectDouMoves.legal` |
| `ToMove(const CardSet&, int playerId)` | `PerfectDouMoves.toMove` |
| `MinSteps(const CardSet&)` | `Oracle.minSteps` |
| `Observe(const PublicView&) -> View` | `PerfectDouFeatures.observe` |
| `Encode(const View&, float *xImp, float *seq)` | `PerfectDouFeatures.encode`(1676 / 5400) |
| `ActionFeature(const View&, const Move&, float *out, int off)` | `PerfectDouFeatures.actionFeature`(186) |
| `CountsOf(const Move&)` | `PerfectDouFeatures.countsOf` |
| `Policy::Load(dir)` / `Policy::DefaultModelDir()` | `ActorNetwork.load` |
| `Policy::Score(seat, xImp, seq, actionFeat, options)` | `ActorNetwork.score` |
| `Policy::Decide(const PublicView&) -> Decision` | `NeuralAiPlayer.decide` |
| `GameState` / `PublicView` | `game/GameState`、`game/PublicView` |
| `FindSharedData` / `ResolveSharedData` | `SharedData` |

维度常量(`include/doudizhu/perfect_dou_features.h`、`policy.h`):
`kCardMatrix=180`、`kNodeSize=1676`、`kSeqSize=5400`、`kActionSize=186`、`kLstmSteps=10`、
`kLstmIn=540`、`kLstmHidden=128`、`kNodeDim=1804`、`kJointSize=1990`、`kPolicySeats=3`。
牌型编码 `MT_PASS..MT_WRONG` 与 Java `PerfectDouMoves.MT_*`、Go `MT*` 数值一致。

`Policy::Decision` 一次决策的全部产物:`move`(选出的动作)、`slot`(候选槽位)、`options`(候选多重集)、
`moves`(与候选一一对应的引擎 `Move`)、`logits`(各候选得分)、`probs`(logits 的 softmax)。
`Observe` / `ToMove` / `GameState` 的脏输入抛 `std::invalid_argument` 或 `std::logic_error`。

## 构建与测试

```bash
cd cpp
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
ctest --test-dir build --output-on-failure
```

C++20,非 MSVC 下带 `-Wall -Wextra -ffp-contract=off`。
`-ffp-contract=off` 关掉 FMA 合并,实测让 logits 与 libtorch 基准的最大偏差从 5.8e-4 降到 5.2e-4。

只有一个测试目标 `parity`(`test/parity_test.cpp`),它是**跨语言对齐契约**:
回放 [../data/golden/golden24.txt.gz](../data/golden)(训练侧状态由
[tools/pddump.cpp](../tools/pddump.cpp) 导出,24 局 712 个决策),逐决策断言

1. 候选动作集合与记录作为 45 位多重集**双向完全相等**(实测连枚举顺序也一致);
2. `xImp`、`seq`、每个候选的 `actionFeat` **逐位相等**(按 IEEE-754 位模式比较);
3. 手写前向的 logits 偏差 **< 1e-3**,且 argmax 恒等于记录的 `CHOSEN` 槽位;

外加端到端断言 `Policy::Decide`(走本工程自己的枚举顺序与编码)选出的牌面等于记录,
并校验读满表头声明的 24 局 / 712 个决策。当前结果:

```text
回放 24 局 712 个决策 (候选总数 6460): 最大 |logit 偏差| 5.188e-04 (第585个决策), argmax 不一致 0 个,
logits 超容差 0 个, Decide 端到端不符 0 个, 特征逐位差异 0 处, 候选集合差异 0 处, 记录格式错误 0 处
PARITY OK
```

对照:Java(libtorch)2.7e-4、Go(手写 float32)4.883e-4、C++(手写 float32)5.188e-4,三者 argmax
不一致都是 0。基准记录格式见 [../tools/README.md](../tools/README.md)。

## 权重读取

`data/models/ts/actor{0,1,2}_ts.pt` 是 TorchScript 归档,张量以 **STORED(不压缩)** 的 zip 条目
`<root>/data/<i>` 存放,是小端 float32 行主序裸字节;`actor{N}_manifest.json`
(由 [tools/export_actor.py](../tools/export_actor.py) 生成)记录了 条目序号 → 层名 → 形状。
C++ 侧自己解析 zip 中央目录(`src/pt_archive.cpp`)+ 一个够用的 JSON 子集,不引入 zlib 或 libtorch;
形状、条目长度、层名齐全性都按清单校验,不硬编码权重。

前向:`node = concat(xImp[1676], LSTM 末步隐状态[128])`,每个候选
`joint = concat(node, actionFeat[186])` → `relu(l1)` → `relu(l2)` → `relu(l3)` → `relu(l4)` → `head`。
LSTM 取门顺序 **i(输入门)、f(遗忘门)、g(候选, tanh)、o(输出门)**,即 PyTorch `weight_ih_l0`
的实际拼接顺序;`i/f/o` 走 sigmoid,只有 `g` 走 tanh。

> 门顺序是这个移植最容易踩的坑:按 PyTorch 旧的 `_lstm_gate` 命名 `i, h, f, c` 去切片,
> logits 与基准会差到 1~1900、argmax 错约 89 处。`tools/export_actor.py` 写出的 `gateOrder`
> 已改成正确描述,`Policy::Load` 会拒绝与本实现不符的 `gateOrder`(与 Go 侧同一条检查)。

## 与 Java 的差异

- 推理是手写 float32 前向(Java 走 DJL/libtorch),logits 偏差约 5e-4 而不是 2.7e-4;
  仍远小于 1e-3 门限,argmax 完全一致。`l1` 拆成"状态部分先算一次 + 每个候选只算 186 那一截",
  与 TorchScript 的 `concat` 数学一致,只差浮点求和顺序;动作特征里为 0 的维度直接跳过累加。
- `Move` 用 `CardSet` 张数向量存牌(Java 用 `List<Rank>`),信息等价。
- `Oracle` 的备忘录用 `thread_local`(Java 是 `ThreadLocal`,Go 是共享缓存 + 互斥锁),
  纯函数,缓存只影响速度不影响结果。
- 未提供 Java 的显式 `GameState.copy()`:所有成员都是值语义,默认拷贝构造就是深拷贝。
  也未移植 `rules/MoveGenerator`(引擎侧简化枚举的生成器):
  策略网络必须吃完整枚举的动作空间,统一用 `Legal`。`game/Player` 折进 `GameState` 的张数数组,
  `rules/Deck` 的 54 张牌库常量内联在 `PublicView::ComputeUnseenCards` 里;没有叫分阶段,
  地主席位与发牌像基准记录那样由调用方给定。
- `Decision.probs` 是按候选槽位下标排列的 `vector<float>`,Java 的 `probabilityTable` 是
  按概率降序排列的 `List<MoveScore>`;数值等价,只是容器形状不同。

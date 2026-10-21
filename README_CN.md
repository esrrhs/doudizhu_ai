# 斗地主 AI (doudizhu_ai)

[![License](https://img.shields.io/github/license/esrrhs/doudizhu_ai)](https://github.com/esrrhs/doudizhu_ai)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.esrrhs/doudizhu_ai)](https://central.sonatype.com/artifact/com.github.esrrhs/doudizhu_ai)
[![Java CI](https://github.com/esrrhs/doudizhu_ai/actions/workflows/maven.yml/badge.svg?branch=master)](https://github.com/esrrhs/doudizhu_ai/actions/workflows/maven.yml)
[![Go CI](https://github.com/esrrhs/doudizhu_ai/actions/workflows/go.yml/badge.svg?branch=master)](https://github.com/esrrhs/doudizhu_ai/actions/workflows/go.yml)
[![C++ CI](https://github.com/esrrhs/doudizhu_ai/actions/workflows/cpp.yml/badge.svg?branch=master)](https://github.com/esrrhs/doudizhu_ai/actions/workflows/cpp.yml)
[![Server CI](https://github.com/esrrhs/doudizhu_ai/actions/workflows/server.yml/badge.svg?branch=master)](https://github.com/esrrhs/doudizhu_ai/actions/workflows/server.yml)

*读本文的英文版: [README.md](README.md)*

斗地主 AI 引擎的**三份独立实现**（Java 17 / Go 1.21 / C++20）加一个**网页对战平台**
（[FakeLua](https://github.com/esrrhs/fakelua) 服务）。出牌由强化学习策略网络驱动
（[PerfectDouCPP](https://github.com/esrrhs/PerfectDouCPP) 式自对弈训练，权重在仓库里），
叫地主是随机叫分。三份实现共用同一份权重与同一份基准记录，
在 712 个真实决策上 logits 与所选着法完全一致。

---

## 📂 目录结构

```text
doudizhu_ai/
├── java/            # Java 17 实现, Maven 坐标 com.github.esrrhs:doudizhu_ai
│   └── src/main/java/com/doudizhu/{model,rules,game,ai,nn}/  + Main.java (三 AI 自对弈演示)
├── go/              # Go 1.21 实现, 零第三方依赖 (前向纯手写, 不用 cgo/ONNX)
├── cpp/             # C++20 实现, 零第三方依赖 (自带 zip 解析与手写前向)
├── server/          # 网页对战平台: Lua 业务 + C++ 宿主 + 前端
│   ├── lua/  host/  static/  tests/  API.md  README.md
├── data/
│   ├── models/latest/   # 训练机直出的权重 (只有参数, 没有 forward)
│   ├── models/ts/       # 可推理的 TorchScript 权重 + 逐层清单 json (三语共用, 13 MB)
│   └── golden/          # 训练侧导出的逐决策基准记录 (三语共用, 304 KB)
└── tools/           # 权重再导出与基准记录生成脚本 (不参与运行时)
```

规则引擎、牌局状态机与特征编码在三种语言里各自独立实现；只有**权重**和**基准记录**是共享的。

---

## 🎯 三语一致是怎么保证的

对齐不是「差不多对」，而是有一条写死在测试里的契约（`data/golden/golden24.txt.gz`：
24 局、712 个决策、6460 个候选动作，由 `tools/pddump.cpp` 从训练侧导出）：

| 校验项 | 要求 |
|---|---|
| 候选动作集合 | 与训练侧完全一致（按 45 位多重集指纹比较，含飞机/四带二的全部具体带牌组合） |
| `xImp` / `seq` / `actionFeat` 输入特征 | **逐位相同**，不容差 |
| logits | 最大绝对偏差 < 1e-3 |
| argmax 选出的着法 | 必须完全相等 |

实测（各自 `mvn test` / `go test` / `ctest` 打印）：

| 实现 | 最大 logit 偏差 | argmax 不一致 | 平均单次推理 |
|---|---|---|---|
| Java (libtorch via DJL) | 2.747e-4 | 0 | 0.84 ms |
| Go (手写前向) | 4.883e-4 | 0 | 7.80 ms |
| C++ (手写前向) | 5.188e-4 | 0 | ~1 ms |

偏差只来自浮点求和顺序，所以 C++ 侧强制 `-ffp-contract=off`，容差也**从不放宽**。
`server/` 复用 C++ 那份引擎，因此服务端的着法与 `cpp/` 逐位一致。

---

## 🚀 各语言怎么用

### Java

```bash
cd java
./mvnw clean test                    # 9 个测试, 含与训练侧的逐位对齐
./mvnw exec:java                     # 三个 AI 自对弈一局的命令行演示
./mvnw exec:java -Dexec.args="--model-dir=/path/to/weights"
```

```xml
<dependency>
    <groupId>com.github.esrrhs</groupId>
    <artifactId>doudizhu_ai</artifactId>
    <version>1.1.0</version>
</dependency>
```

规则引擎（`model` / `rules` / `game`）零依赖。出牌 AI 需要原生 libtorch，而 DJL 相关依赖在 pom 里
标记为 `optional`、**不会传递给下游**，所以要用 `NeuralAiPlayer` 得自行补齐引擎与本机平台的原生库：

```xml
<dependency>
    <groupId>ai.djl.pytorch</groupId>
    <artifactId>pytorch-engine</artifactId>
    <version>0.39.0</version>
</dependency>
<!-- 显式绑定平台原生库, 否则 DJL 会在首次运行时下载到 ~/.djl.ai -->
<dependency>
    <groupId>ai.djl.pytorch</groupId>
    <artifactId>pytorch-native-cpu</artifactId>
    <classifier>osx-aarch64</classifier>
    <version>2.7.1</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>ai.djl.pytorch</groupId>
    <artifactId>pytorch-jni</artifactId>
    <version>2.7.1-0.39.0</version>
    <scope>runtime</scope>
</dependency>
```

权重必须与加载它的 libtorch 同主版本导出（本工程固定 **2.7.1**，DJL 0.39 的最新原生库）。

### Go

```bash
cd go && go test ./...      # 21 个测试, 零第三方依赖
```

```go
package main

import (
	"fmt"

	ddz "github.com/esrrhs/doudizhu_ai/go"
)

func main() {
	policy, err := ddz.Load() // 权重在 data/models/ts
	if err != nil {
		panic(err)
	}
	hands := make([][15]int32, 3)
	for i, cards := range []string{"334445566689TTTJKAAR", "4577899JQQKKAA222", "335677889TJJQQK2B"} {
		hands[i], _ = ddz.ParseCards(cards)
	}
	bottom, _ := ddz.ParseCards("4TK")
	// 地主那 20 张已含底牌, NewState 不会再叠加
	state, err := ddz.NewState(hands, 0, bottom)
	if err != nil {
		panic(err)
	}
	move, logits, err := policy.Decide(state.PublicViewOf(state.ActivePlayerID()))
	if err != nil {
		panic(err)
	}
	fmt.Println(move, len(logits))
}
```

### C++

```bash
cd cpp
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
ctest --test-dir build --output-on-failure
```

```cpp
#include <array>
#include <cstdio>

#include "doudizhu/api.h"

int main()
{
    doudizhu::Policy policy;
    if (!policy.Load(doudizhu::Policy::DefaultModelDir()))
    {
        std::fprintf(stderr, "%s\n", policy.Error().c_str());
        return 1;
    }
    const std::array<doudizhu::CardSet, 3> hands{
        doudizhu::ParseCards("334445566689TTTJKAAR"),
        doudizhu::ParseCards("4577899JQQKKAA222"),
        doudizhu::ParseCards("335677889TJJQQK2B")};
    const doudizhu::CardSet bottom = doudizhu::ParseCards("4TK");
    doudizhu::GameState state(hands, 0, bottom);
    const auto d = policy.Decide(state.GetPublicView(state.ActivePlayerIndex()));
    std::printf("%s (%d candidates)\n", doudizhu::DebugCards(d.move.cards).c_str(),
                static_cast<int>(d.options.size()));
    return 0;
}
```

上面两段都是先编译、先跑通才写进文档的：同一个局面两边都答 **`334455`，候选 68 个**，
与 Java 引擎对这副牌的选择一致。

---

## 🌐 网页对战平台 (FakeLua)

线上：<https://doudizhu.esrrhs.xyz>（部署与发布步骤见 [server/README_CN.md](server/README_CN.md)）。

1 真人（0 号位）对 2 个策略网络 AI：随机叫分、底牌翻开、AI 提示、AI 思路面板、
记牌器与战报日志、多 Session 隔离。

```bash
cd server
cmake -S host -B host/build -DCMAKE_BUILD_TYPE=Release   # 需要先装 fakelua, 见 server/README_CN.md
cmake --build host/build -j
DOUDIZHU_PORT=8080 ./host/build/doudizhu_server lua      # http://localhost:8080
python3 tests/api_test.py                               # 12 个接口用例
```

牌局规则与推理都在 C++ 里，Lua 只做 HTTP、会话与流程编排；接口契约见 [server/API.md](server/API.md)。
常驻内存约 35 MB，loopback 请求延迟 p50 约 4 ms，单次决策 p50 约 2 ms。

---

## 🧠 权重来源与再导出

`data/models/latest` 是训练机（libtorch `OutputArchive`）直出的权重，**只有参数、没有 forward**，
任何 TorchScript 运行时都无法直接调用，因此要重建导出一次：

```bash
# 1) 用训练侧的 PyTorch 版本抽出参数
python3 tools/export_actor.py extract --in data/models/latest --out /tmp/params
# 2) 用与原生库一致的版本 (本工程 2.7.1) 重建并脚本化
python3 tools/export_actor.py script --in /tmp/params --out data/models/ts
```

`export_actor.py` 同时会写 `actor<N>_manifest.json`：TorchScript zip 把张量以 **STORED（不压缩）**
形式存成 `actor<N>_data/<i>`，清单记录「条目序号 → 层 → 形状」，Go/C++ 因此只用标准库解析 zip 就能加载。
基准记录由 `tools/pddump.cpp` 生成。步骤、格式与踩坑（LSTM 门块顺序是 **i, f, g, o**，写错会有
上千量级的偏差）见 [tools/README_CN.md](tools/README_CN.md)。

---

## 🃏 规则与叫分

54 张牌，地主 20 张、农民各 17 张、底牌 3 张；支持单张、对子、三张、三带一、三带二、
单顺（5 张起）、双顺（3 对起）、飞机（2 个连续三张起，可带单/带对）、四带二、炸弹、王炸；
农民阵营任意一家先出完则两位农民同胜。

叫分阶段每家（含两个 AI）**独立以 1/3 概率**表态叫地主，最先叫的一家当地主并拿走 3 张底牌；
三家都不叫则流局重发、先叫位顺延。叫分与牌力无关。

策略网络只用公开信息（自己的手牌、两家合计的未知牌、各家已出与最近一手、底牌剩余、
最近 30 手历史、剩余手数），**不读取任何玩家的私有手牌**。面板上的「概率」是 softmax 后的
**策略概率**（模型偏好），不是搜索胜率。

---

## 📝 版本说明

早期版本的出牌引擎是 **PIMC 采样 + 2v1 协同 MCTS 搜索**，并配有手牌形态启发与硬护栏。
策略网络在配对对局（同一副牌跑两局、双方角色互换）中对抗它的胜率：

| 旧 PIMC 档位（假想世界 × 迭代） | 对局数 | 策略网络胜率 |
|---|---|---|
| 25 × 120 | 48 | 64.6% |
| 120 × 600 | 24 | 58.3% |
| 240 × 1600 | 80 | **68.8%** |

基于该结果，搜索链路（`PimcAiPlayer`、`MctsSearcher`、`Determinizer`、`FastRolloutPolicy` 等）、
基于采样胜率的叫地主评估 `BidEvaluator` 以及依赖它们的 `SelfPlayRunner`、`MatchRunner` 已整体移除，
叫地主改为随机，网页端也从 Java 内嵌服务迁到了 `server/`。
如需取回，历史提交 `f8c9680` 及之前完整保留了那套实现。

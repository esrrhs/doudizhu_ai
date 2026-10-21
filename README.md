# 斗地主 AI (doudizhu_ai)

[![License](https://img.shields.io/github/license/esrrhs/doudizhu_ai)](https://github.com/esrrhs/doudizhu_ai)
[![Language](https://img.shields.io/github/languages/top/esrrhs/doudizhu_ai)](https://github.com/esrrhs/doudizhu_ai)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.esrrhs/doudizhu_ai)](https://central.sonatype.com/artifact/com.github.esrrhs/doudizhu_ai)
[![Build Status](https://github.com/esrrhs/doudizhu_ai/actions/workflows/maven.yml/badge.svg?branch=master)](https://github.com/esrrhs/doudizhu_ai/actions)

**Java 17** 实现的斗地主 AI 引擎与本地网页对战平台。出牌由**强化学习策略网络**驱动（PerfectDou 式自对弈训练），
叫地主采用随机叫分，规则引擎与牌局状态机保持纯 Java、无第三方依赖。

---

## 📦 依赖引入

```xml
<dependency>
    <groupId>com.github.esrrhs</groupId>
    <artifactId>doudizhu_ai</artifactId>
    <version>1.1.0</version>
</dependency>
```

```groovy
implementation 'com.github.esrrhs:doudizhu_ai:1.1.0'
```

规则引擎（`rules` / `game` / `model`）零依赖，可直接引用。
出牌 AI 需要原生 libtorch，而 DJL 相关依赖在本项目 pom 里标记为 `optional`、**不会传递给下游**，
因此使用 `NeuralAiPlayer` 时须自行补齐引擎与本机平台的原生库：

```xml
<dependency>
    <groupId>ai.djl.pytorch</groupId>
    <artifactId>pytorch-engine</artifactId>
    <version>0.39.0</version>
</dependency>
<!-- 显式绑定平台原生库，否则 DJL 会在首次运行时下载到 ~/.djl.ai -->
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

> 权重必须用与加载方一致的主版本导出（本工程固定 libtorch **2.7.1**，DJL 0.39 的最新原生库），
> 详见「权重的来源与再导出」。

---

## 🌟 核心特性

### 1. 强化学习策略网络出牌
- **权重来源**：由 [PerfectDouCPP](https://github.com/esrrhs/PerfectDouCPP) 自对弈强化学习训练，按座位（地主 / 地主下家 / 地主上家）各一个网络，仓库 `models/` 已附带；
- **决策方式**：完整枚举当前所有合法走法（含飞机、四带二的**全部具体带牌组合**），逐手打分后直接取最高分，单次推理毫秒级；
- **只用公开信息**：观察特征为我的手牌、两家合计的未知牌、各家已出与最近一手、底牌剩余、最近 30 手历史、剩余手数等，**不读取任何玩家的私有手牌**；
- **移植校验**：测试内与 C++ 训练侧逐位对齐——候选动作集合、全部输入特征、logits（最大偏差 2.7e-4）与 argmax（712 个决策零不一致）。

### 2. 随机叫地主
每家独立以 **1/3** 概率表态叫地主，最先叫的一家当地主并拿走 3 张底牌；三家都不叫则流局重发。
叫分与牌力无关，地主也可能拿到很弱的牌。真人仍可自己选择叫或不叫。

### 3. 网页端交互对战平台（1 真人 vs 2 AI）
- 发牌、随机叫分、底牌翻开、专属出牌区与「不出/PASS」区；
- **💡 智能提示**：一键获取神经网络推荐的着法并高亮手牌；
- **🔍 AI 思路面板**：展示候选动作及其 **softmax 策略概率**（模型偏好，不是搜索胜率）；
- **📊 记牌器与对局日志**、多 Session 并发隔离。

### 4. 规范的斗地主规则引擎
54 张牌，地主 20 张、农民各 17 张、底牌 3 张；支持单张、对子、三张、三带一、三带二、
单顺（5 张起）、双顺（3 对起）、飞机（2 个连续三张起，可带单/带对）、四带二、炸弹、王炸；
农民阵营任意一家先出完则两位农民同胜。

---

## 🚀 快速开始

### 环境依赖
JDK 17+，Apache Maven 3.5+

### 1. 启动网页端对战平台
```bash
mvn exec:java                        # http://localhost:8080
mvn exec:java -Dexec.args="--port=8088"
mvn exec:java -Dexec.args="--model-dir=/path/to/weights"
```

### 2. 命令行 AI 演示（三个 AI 自动打一局）
```bash
mvn exec:java -Dexec.args="--cli"
```

### 3. 运行测试
```bash
mvn clean test
```
其中 `PerfectDouFeaturesGoldenTest` / `ActorNetworkGoldenTest` 会读取 `src/test/resources/golden/`
里由 C++ 导出的基准记录做逐位对齐校验，需要原生库可用。

### 4. 权重的来源与再导出
`models/latest` 是训练机直接产出的权重，`models/ts` 是 Java 侧可推理的 TorchScript 权重。
训练侧用 libtorch `OutputArchive` 保存，**只有参数、没有 forward**，任何 TorchScript 运行时都无法直接调用，
因此需要一次重建导出：

```bash
# 1) 用训练侧的 PyTorch 版本抽出参数
python3 tools/export_actor.py extract --in models/latest --out /tmp/params
# 2) 用与原生库一致的版本 (2.7.1) 重建并脚本化
python3 tools/export_actor.py script --in /tmp/params --out models/ts
```

`export_actor.py`（重建导出）与 `pddump.cpp`（逐决策基准数据导出）位于
[PerfectDouCPP](https://github.com/esrrhs/PerfectDouCPP) 的 `tools/` 目录。

---

## 📂 项目结构

```text
doudizhu_ai/
├── pom.xml                                   # Maven 配置 (Java 17, Central 发布, GPG 签名)
├── models/
│   ├── latest/                               # 训练机导出的原始权重 (仅参数, 无 forward)
│   └── ts/                                   # 可推理的 TorchScript 权重 (DJL 加载)
├── src/
│   ├── main/
│   │   ├── java/com/doudizhu/
│   │   │   ├── model/                        # 领域模型 (Rank, Role, CardType, Move, Hand)
│   │   │   ├── rules/                        # 规则与牌库 (Deck, MoveGenerator)
│   │   │   ├── game/                         # 牌局状态机与信息视角 (GameState, Player, PublicView)
│   │   │   ├── ai/                           # AiPlayer / AiDecision / NeuralAiPlayer / BidRule
│   │   │   ├── nn/                           # 特征编码、完整动作枚举、手数 oracle、DJL 推理封装
│   │   │   ├── web/                          # Web 会话与 HTTP 服务 (GameSession, GameHttpServer)
│   │   │   └── Main.java                     # 启动入口
│   │   └── resources/
│   │       ├── logback.xml
│   │       └── static/                       # 前端 (index.html, style.css, app.js)
│   └── test/
│       ├── java/com/doudizhu/nn/             # 与 C++ 训练侧的逐位对齐测试
│       └── resources/golden/                 # 基准决策记录 (24 局 712 决策)
```

---

## 📝 版本说明

早期版本的出牌引擎是 **PIMC 采样 + 2v1 协同 MCTS 搜索**，并配有手牌形态启发与硬护栏；
策略网络在配对对局（同牌两局、角色互换）中对其胜率为：

| 旧 PIMC 档位（假想世界 × 迭代） | 对局数 | 策略网络胜率 |
|---|---|---|
| 25 × 120 | 48 | 64.6% |
| 120 × 600 | 24 | 58.3% |
| 240 × 1600 | 80 | **68.8%** |

基于该结果，搜索链路（`PimcAiPlayer`、`MctsSearcher`、`Determinizer`、`PassInference`、
`FastRolloutPolicy`、`BombPolicy`、`HandShape`）、基于采样胜率的叫地主评估 `BidEvaluator`
以及依赖它们的 `SelfPlayRunner`、`MatchRunner` 已整体移除，叫地主改为随机。
如需取回，历史提交 `f8c9680` 及其之前的版本完整保留了这套实现。

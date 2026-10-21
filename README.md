# 斗地主 AI (doudizhu_ai)

[![License](https://img.shields.io/github/license/esrrhs/doudizhu_ai)](https://github.com/esrrhs/doudizhu_ai)
[![Language](https://img.shields.io/github/languages/top/esrrhs/doudizhu_ai)](https://github.com/esrrhs/doudizhu_ai)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.esrrhs/doudizhu_ai)](https://central.sonatype.com/artifact/com.github.esrrhs/doudizhu_ai)
[![Build Status](https://github.com/esrrhs/doudizhu_ai/actions/workflows/maven.yml/badge.svg?branch=master)](https://github.com/esrrhs/doudizhu_ai/actions)

基于 **Java 17** 实现的高性能扑克牌游戏“**斗地主**”人工智能引擎与本地网页端对战平台。

核心算法采用**完美信息蒙特卡洛 (PIMC, Perfect Information Monte Carlo)** 结合 **2v1 协同对抗蒙特卡洛树搜索 (Cooperative MCTS)**，针对斗地主这一典型不完全信息、2 对 1 非对称团队零和博弈实现严谨且强力的出牌决策。

---

## 📦 依赖引入 (Installation)

### Maven
在项目的 `pom.xml` 中添加依赖：
```xml
<dependency>
    <groupId>com.github.esrrhs</groupId>
    <artifactId>doudizhu_ai</artifactId>
    <version>1.1.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'com.github.esrrhs:doudizhu_ai:1.1.0'
```

### 使用神经网络玩家时的额外依赖
`ai.djl:api`、`ai.djl.pytorch:pytorch-engine` 与 libtorch 原生库在 pom 中都标记为 `optional`，**不会传递给下游**。
只使用 PIMC 搜索引擎时无需任何原生库；要用 `NeuralAiPlayer` 需自行补上引擎与本机平台的原生库：

```xml
<dependency>
    <groupId>ai.djl.pytorch</groupId>
    <artifactId>pytorch-engine</artifactId>
    <version>0.39.0</version>
</dependency>
<!-- 显式绑定平台原生库，否则 DJL 会在首次使用时自动下载到 ~/.djl.ai -->
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

权重必须与加载它的 libtorch 版本同主版本导出（本工程固定 2.7.1），详见下文“权重的来源与再导出”。
网页端与 CLI 中神经网络候选动作显示的“阵营胜率”，是 softmax 归一化后的**策略概率**，不是搜索胜率。

---

## 🌟 核心特性

### 1. PIMC + 2v1 协同对抗 MCTS 决策算法
- **不完全信息隐藏与确定化采样 (Determinization)**：玩家视角严格受限，仅感知自身手牌、对手剩余牌数、公开的 3 张底牌与出牌历史。AI 决策时通过全场未知牌池采样生成多个完全信息假想世界分别推演。
- **2 对 1 团队博弈回报机制**：
  - 农民阵营共享胜负目标：只要任意一位农民走完手牌，两位农民同时获得完全胜利（收益 1.0）；
  - 模拟推演中内置农民配合启发（互不炸弹相残、强力牌不强压队友、主动垫牌协助）；
  - 地主面对双人封锁，运用 MCTS 探索突破口。
- **标准的 MCTS 四阶段**：Selection（选择）、Expansion（扩展）、Simulation / Rollout（快速推演）、Backpropagation（反向传播）。
- **多世界聚合评估**：跨采样世界汇总各合法出牌动作的访问频次与胜率期望，选取最优稳健着法。

### 2. PIMC 底牌采样叫地主算法
- **发牌与盖伏底牌**：每人先发 17 张手牌，中央 3 张底牌保持盖伏；
- **底牌蒙特卡洛采样推演**：轮到 AI 表态是否叫地主时，从 37 张全场未知牌中采样 3 张作为假设底牌并入己手，将其余 34 张分配给两名假想农民，通过极速推演策略模拟数十局终局；
- **期望胜率决策**：当综合预估胜率 $\ge 50\%$ 时果断【叫地主】，否则【不叫】。

### 3. 网页端交互对战平台 (1 真人 vs 2 AI)
- **交互式叫地主**：支持开局进入发牌与顺序叫地主流程（叫地主/不叫），AI 实时展现底牌采样推演胜率；同时提供快速直选身份模式。
- **底牌遮盖与翻开**：叫地主阶段 3 张底牌背面展示，地主确定后翻开亮牌并归地主所有；每位玩家拥有专属出牌与“不出/PASS”展示区。
- **自由过牌**：符合正宗斗地主规则，只要非首轮出牌，随时可以自由选择不出（PASS）控盘。
- **💡 AI 智能提示**：遇到疑难牌局时，一键获取 PIMC AI 的推荐着法并自动高亮手牌。
- **🔍 实时 AI 思考雷达**：可视化展示 AI 在叫牌阶段的底牌采样胜率柱状图，以及出牌阶段各候选动作的访问量与预估胜率。
- **📊 记牌器与对局日志**：实时统计全场 54 张牌剩余分布（含大王、小王）并记录完整出牌历史。
- **多 Session 并发隔离**：支持多个玩家或多个浏览器窗口同时独立开启对局，互不干扰。

### 4. 规范的斗地主规则引擎
- 54 张标准扑克牌（含大王、小王），地主 20 张牌，农民各 17 张牌，底牌 3 张。
- 完整支持所有标准牌型：
  - **王炸（火箭）**：大王 + 小王，克制所有牌型；
  - **炸弹**：4 张同点数，克制所有非炸弹牌型；
  - **普通牌型**：单张、对子、三张、三带一、三带二、单顺（5张起）、双顺（3对起）、飞机（2个连续三张起）、四带二（带两单或两对）。

### 5. 防散牌死手启发与硬护栏策略
- **手牌形态分析 (HandShape)**：深度解析手牌散牌数、控场大牌（2与双王）及牌型结构，精确评估出牌前后弱单增减量与死散手牌风险；
- **决策硬护栏**：
  - **防拆对与大牌超压**：有更小同型安全牌时不拆对压小牌、不用大牌超压；
  - **控场牌保护**：禁止将 2 或王作为三带或顺子的带牌过早烧掉制造死散；
  - **队友协同绝对保障**：农民跟牌阶段绝不压队友合法走法，自动过牌控盘保送队友；
  - **非紧急勿炸**：严格限制非关键时刻交出炸弹与王炸。
- **三人自对弈审局工具 (SelfPlayRunner)**：内置全自动对战推演与违规启发审查工具，保障策略迭代的高质量。

### 6. 强化学习策略网络玩家 (可选原生推理)
- **权重来源**：由 [PerfectDouCPP](https://github.com/esrrhs/PerfectDouCPP) 用 PerfectDou 式自对弈强化学习训练，仓库内 `models/` 直接附带；
- **决策方式**：对完整动作空间（含飞机带翅的全部具体带牌组合）逐手打分，直接取最高分出牌，单次推理毫秒级；
- **观察特征**：仅使用公开信息（我的手牌、两家合计未知牌、各家已出与最近一手、底牌剩余、30 手历史、剩余手数等），绝不读取对手私有手牌；
- **原生推理**：通过 DJL + libtorch 加载 TorchScript 权重，依赖声明为 `optional`，不使用该玩家时核心引擎依旧零原生依赖；
- **移植校验**：内置与 C++ 训练侧的逐位对齐测试（候选动作集合、全部输入特征、logits 与 argmax 一致）。

---

## 🚀 快速开始

### 环境依赖
- JDK 17+
- Apache Maven 3.5+

### 1. 启动网页端对战平台 (推荐)
执行以下命令启动本地 Web 服务：
```bash
mvn exec:java
```
服务启动后，在浏览器中访问：
👉 **http://localhost:8080**

*(如需指定端口，可执行：`mvn exec:java -Dexec.args="--port=8088"`)*

### 2. 运行命令行 AI 对局模拟 (Benchmark)
若需在终端中查看 3 个 AI 纯自动对局的深度思考过程与结算：
```bash
mvn exec:java -Dexec.args="--cli"
```
改用强化学习策略网络出牌（权重在 `models/ts`）：
```bash
mvn exec:java -Dexec.args="--cli --nn"
```

### 3. 神经网络玩家与 PIMC 的强度对抗评估
同一副牌跑两局、双方角色互换（NN 坐地主 / NN 坐农民），统计胜率与 ADP 得分：
```bash
mvn exec:java -Dexec.args="--match --games=40 --worlds=240 --iters=1600"
```
实测结果（NN 始终对抗同配置的 PIMC，胜率含双方角色互换的两组对局）：

| PIMC 档位 (假想世界 × 迭代) | 对局数 | NN 胜率 | NN 坐地主 | NN 坐农民 |
|---|---|---|---|---|
| 25 × 120 | 48 | 64.6% | 70.8% | 58.3% |
| 120 × 600 | 24 | 58.3% | 58.3% | 58.3% |
| 240 × 1600 (网页端默认) | 80 | **68.8%** | 82.5% | 55.0% |

让网页端 AI 也用策略网络：
```bash
mvn exec:java -Dexec.args="--nn"
```

### 4. 运行自动化单元测试
```bash
mvn clean test
```

### 5. 权重的来源与再导出
仓库内的 `models/latest` 是训练机直接产出的原始权重，`models/ts` 是 Java 侧可直接调用的 TorchScript 版本。
原始权重由 libtorch 的 `OutputArchive` 保存，**只有参数、没有 forward**，任何 TorchScript 运行时都无法直接调用，
因此需要经过一次重建导出：

```bash
# 1) 用与训练侧相同的 PyTorch 版本抽出参数
python3 tools/export_actor.py extract --in models/latest --out /tmp/params
# 2) 用与 DJL 原生库相同的版本 (本工程为 2.7.1) 重建并脚本化
python3 tools/export_actor.py script --in /tmp/params --out models/ts
```

导出脚本与逐决策基准数据的生成脚本都在 [PerfectDouCPP](https://github.com/esrrhs/PerfectDouCPP) 的 `tools/` 下
(`export_actor.py` 与 `pddump.cpp`)。

---

## 📂 项目结构

```text
doudizhu_ai/
├── pom.xml                                   # Maven 配置 (Java 17, Central Publishing, GPG Signing)
├── models/
│   ├── latest/                               # 训练机直接导出的 actor0..2.pt (TorchScript 参数档)
│   └── ts/                                   # 带 forward 的可推理权重 (供 DJL 加载)
├── src/
│   ├── main/
│   │   ├── java/com/doudizhu/
│   │   │   ├── model/                        # 领域模型 (Rank, Role, CardType, Move, Hand)
│   │   │   ├── rules/                        # 规则与牌库 (Deck, MoveGenerator)
│   │   │   ├── game/                         # 牌局状态机与信息视角 (GameState, Player, PublicView)
│   │   │   ├── ai/                           # AiPlayer 接口 + PIMC/MCTS + 神经网络玩家 + 评估工具
│   │   │   ├── nn/                           # 特征编码、完整动作枚举、手数 oracle、DJL 推理封装
│   │   │   ├── web/                          # Web 会话与 HTTP 服务 (GameSession, GameHttpServer)
│   │   │   └── Main.java                     # 统一启动入口
│   │   └── resources/
│   │       ├── logback.xml                   # 日志配置
│   │       └── static/                       # Web 前端资源 (index.html, style.css, app.js)
│   └── test/
│       ├── java/com/doudizhu/nn/             # 与 C++ 训练侧的逐位对齐测试
│       └── resources/golden/                 # PerfectDouCPP 导出的基准决策记录 (24 局 712 决策)
```

# 斗地主 AI (Farmer AI / DouDiZhu AI)

[![License](https://img.shields.io/github/license/esrrhs/farmer_ai)](https://github.com/esrrhs/farmer_ai)
[![Language](https://img.shields.io/github/languages/top/esrrhs/farmer_ai)](https://github.com/esrrhs/farmer_ai)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.esrrhs/farmer-ai)](https://central.sonatype.com/artifact/com.github.esrrhs/farmer-ai)
[![Build Status](https://github.com/esrrhs/farmer_ai/actions/workflows/maven.yml/badge.svg?branch=master)](https://github.com/esrrhs/farmer_ai/actions)

基于 **Java 17** 实现的高性能扑克牌游戏“**斗地主**”人工智能引擎与本地网页端对战平台。

核心算法采用**完美信息蒙特卡洛 (PIMC, Perfect Information Monte Carlo)** 结合 **2v1 协同对抗蒙特卡洛树搜索 (Cooperative MCTS)**，针对斗地主这一典型不完全信息、2 对 1 非对称团队零和博弈实现严谨且强力的出牌决策。

---

## 📦 依赖引入 (Installation)

### Maven
在项目的 `pom.xml` 中添加依赖：
```xml
<dependency>
    <groupId>com.github.esrrhs</groupId>
    <artifactId>farmer-ai</artifactId>
    <version>1.1.0</version>
</dependency>
```

### Gradle
```groovy
implementation 'com.github.esrrhs:farmer-ai:1.1.0'
```

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

### 3. 运行自动化单元测试
```bash
mvn clean test
```

---

## 📂 项目结构

```text
farmer_ai/
├── pom.xml                                   # Maven 配置 (Java 17, Central Publishing, GPG Signing)
├── src/
│   ├── main/
│   │   ├── java/com/farmer/
│   │   │   ├── model/                        # 领域模型 (Rank, Role, CardType, Move, Hand)
│   │   │   ├── rules/                        # 规则与牌库 (Deck, MoveGenerator)
│   │   │   ├── game/                         # 牌局状态机与信息视角 (GameState, Player, PublicView)
│   │   │   ├── ai/                           # PIMC 与 2v1 MCTS 核心 (Determinizer, MctsSearcher, PimcAiPlayer)
│   │   │   ├── web/                          # Web 会话与 HTTP 服务 (GameSession, GameHttpServer)
│   │   │   └── Main.java                     # 统一启动入口
│   │   └── resources/
│   │       ├── logback.xml                   # 日志配置
│   │       └── static/                       # Web 前端资源 (index.html, style.css, app.js)
│   └── test/                                 # 单元测试 (MoveGeneratorTest, PimcAITest)
```

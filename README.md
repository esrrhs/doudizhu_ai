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
    <version>1.0.0</version>
</dependency>
```

### Gradle
```groovy
implementation 'com.github.esrrhs:farmer-ai:1.0.0'
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

### 2. 网页端交互对战平台 (1 真人 vs 2 AI)
- **多角色自选**：支持在对局中自由选择扮演“地主”或“农民”，体验单挑双 AI 或与 AI 队友协同对敌。
- **底牌展示与独立出牌区**：桌面中央公开展示 3 张底牌；每位玩家面前拥有独立的出牌与“不出/PASS”状态展示区。
- **自由过牌**：符合正宗斗地主规则，只要非首轮出牌，随时可以自由选择不出（PASS）控盘。
- **💡 AI 智能提示**：遇到疑难牌局时，一键获取 PIMC AI 的推荐着法并自动高亮手牌。
- **🔍 实时 AI 思考雷达**：可视化展示 AI 在各可能世界采样中的候选动作分布、访问量与预估胜率柱状图。
- **📊 记牌器与对局日志**：实时统计全场 54 张牌剩余分布（含大王、小王）并记录完整出牌历史。
- **多 Session 并发隔离**：支持多个玩家或多个浏览器窗口同时独立开启对局，互不干扰。

### 3. 规范的斗地主规则引擎
- 54 张标准扑克牌（含大王、小王），地主 20 张牌，农民各 17 张牌，底牌 3 张。
- 完整支持所有标准牌型：
  - **王炸（火箭）**：大王 + 小王，克制所有牌型；
  - **炸弹**：4 张同点数，克制所有非炸弹牌型；
  - **普通牌型**：单张、对子、三张、三带一、三带二、单顺（5张起）、双顺（3对起）、飞机（2个连续三张起）、四带二（带两单或两对）。

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

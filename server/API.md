# 服务接口契约

`server/` 的对外协议。HTTP 部分是**前端与旧 Java 版共用的同一套契约**（字段名、状态码、
错误语义都对齐 `GameHttpServer`，所以 `static/` 一行没改就搬过来了）；
原生函数部分是 **Lua 与 C++ 宿主之间**的内部契约，改任何一侧都要同时改另一侧。

- 实现：`server/lua/`（路由 `http_server.lua`、流程 `game.lua`、编码 `def.lua`、生命周期 `main.lua`）
- 宿主：`server/host/main.cpp`（把 `cpp/` 的引擎注册成 `doudizhu.*` 原生函数）
- 用例：`server/tests/api_test.py`（12 个用例，含一整局的守恒检查）
- 会话：`X-Session-Id` 头，缺失或非法时服务端现造一个；同一浏览器固定一个 id
- 真人固定 **0 号座位**，1/2 两个座位由策略网络驱动

---

## 1. 牌面与牌型编码

| 表示 | 用途 | 形式 |
|---|---|---|
| 紧凑牌面 | 宿主的一切输入输出 | 一个字符一张牌 `3456789TJQKA2BR`（`T`=10，`B`=小王，`R`=大王），过牌是 `-` |
| 符号数组 | 前端与 HTTP | `["3","10","J","2","BJ","RJ"]` |
| 一手牌 | 宿主的线格式 | `type:mainRank:cards`，如 `6:7:34567` |
| 动作列表 | 宿主的线格式 | 上面那种用 `|` 连接 |

`mainRank` 是比大小用的点数值 3..17（`2`=15、小王=16、大王=17），链牌取链的最大点数。

`type` 是引擎 `CardType` 的序号，与 Java 枚举顺序一致：

| 值 | 牌型 | 值 | 牌型 |
|---|---|---|---|
| 0 | 过牌 | 8 | 飞机不带 |
| 1 | 单张 | 9 | 飞机带单 |
| 2 | 对子 | 10 | 飞机带对 |
| 3 | 三张 | 11 | 四带两单 |
| 4 | 三带一 | 12 | 四带两对 |
| 5 | 三带二 | 13 | 炸弹 |
| 6 | 顺子 | 14 | 火箭(王炸) |
| 7 | 连对 | | |

两套牌面写法只在 `lua/def.lua` 里转换，其余各层只认其中一种。

---

## 2. HTTP 接口

请求体一律 JSON；成功 `200`，业务失败 `400` 且响应体仍是**完整状态 DTO 再加一个 `error` 字段**
（前端据此弹 toast，不需要额外分支）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 存活探针：`{status, backend, sessions, games}` |
| GET | `/api/game/state` | 当前视角的完整状态 DTO |
| POST | `/api/game/new` | 重新发牌并进入叫分；带 `?mode=direct&landlordId=n` 时跳过叫分直接定地主 |
| POST | `/api/game/bid` | `{"call":true}` 表态；只在真人回合有效 |
| POST | `/api/game/play` | `{"cards":["3","10","10","10"]}` 出牌 |
| POST | `/api/game/pass` | 过牌（自由出牌时会 400） |
| POST | `/api/game/ai-step` | 让当前 AI 行动一步：叫分阶段随机表态，出牌阶段走神经网络 |
| GET | `/api/game/hint` | 给真人的建议，见下 |
| GET | `/*` | `static/` 下的前端资源，`/` 即 `index.html` |
| OPTIONS | 任意 | `204` + CORS |

`/api/game/hint` 返回 `{"hint":["3","10","10","10"],"isPass":false,"type":"三带一"}`。
要不起时 `hint` 为空且 `isPass:true`，此时只能调 `/api/game/pass`。

### 状态 DTO

必发字段：

```
stage                "BIDDING" | "PLAYING" | "GAME_OVER"
currentBidder        叫分阶段当前表态的座位 (= activePlayer)
landlordId           地主座位, 未定地主时 -1
activePlayer         轮到谁出牌
isGameOver / winner / isHumanWinner
cardCounts           [P0, P1, P2] 剩余张数
humanHand            真人(0 号位)手牌, 符号数组
bottomCards / bottomCardsRevealed   底牌, 未定地主时是空数组且 revealed=false
roles                ["地主","农民","农民"] 或 ["待定","待定","待定"]
canPass              当前行动者能否过牌
playedStats          记牌器: 15 个点数键全在, 值是已打出张数
playerActions        长度 3 的数组, 每个座位面前的最近一手; 没有时是空对象 {}
aiThoughts           {"p1":{...},"p2":{...}} AI 的思路
bidActions           长度 3 的数组, ""/「叫地主」/「不叫」
logs                 战报, 最多 100 条
```

可选字段（没有值时**直接缺席**，前端的 `if (!x)` 判空与 `null` 等价）：
`message`、`lastMove{playerId,type,cards}`、`winningRole`、`revealedHands`。

`aiThoughts` 的每一项：

```json
{"type":"play","move":"3,10,10,10","timeMs":2,
 "evals":[{"cards":"3,5,5,5","probability":37.9}]}
```

`probability` 是 softmax 归一化后的**策略概率**（百分数、一位小数），不是搜索胜率；
`evals` 最多 6 项，按概率降序。

---

## 3. 宿主注册的原生函数

Lua 侧只拿到整数会话 id，牌局对象始终留在 C++ 里。

| 函数 | 返回 |
|---|---|
| `doudizhu.version()` | `"engine=cpp policy=loaded"` 或权重加载失败的原因 |
| `doudizhu.newgame(seed, firstBidder)` | 会话 id；`seed<=0` 时随机洗牌 |
| `doudizhu.bid(id, call)` | JSON：`{outcome:"landlord",bidder}` / `{outcome:"pass",bidder}` / `{outcome:"draw",next}` |
| `doudizhu.redeal(id, firstBidder)` | JSON：流局后重发牌，先叫位由调用方给 |
| `doudizhu.setlandlord(id, pid)` | JSON：跳过叫分直接定地主 |
| `doudizhu.legal(id)` | `"type:rank:cards|..."`，当前行动者的全部合法动作 |
| `doudizhu.decide(id)` | `"chosen|seat|p:cards,p:cards,..."`（最多前 6 个候选） |
| `doudizhu.apply(id, cards)` | `"type:rank"`；不合法或不在轮次时是空串 |
| `doudizhu.state(id, viewer)` | 该视角可见的公开状态 JSON |
| `doudizhu.minsteps(cards)` | 手数下界（oracle） |
| `doudizhu.sessions()` / `doudizhu.free(id)` | 存活牌局数 / 显式释放 |
| `doudizhu.exit(code)` | 本次 `update` 返回后优雅退出 |

三条不变量：

1. **叫分状态机在引擎里**。`GameState` 在叫分阶段用 `firstBidder` 当占位地主来获得轮转顺序，
   定下地主后用「三家手牌 + 底牌并给地主」重建，所以叫分阶段的过牌不会污染正式对局的历史。
2. **`doudizhu.state` 严格按 viewer 过滤**：只有 viewer 自己的手牌是明文，其余家只给张数，
   终局时才在 `revealed` 里公开三家剩余手牌。
3. **会话必须显式释放**。fakelua 没有 GC，宿主也不会自动回收：`lua/game.lua` 在会话重开时
   释放上一局的牌局，闲置超过 1 小时的会话由 `Sweep` 回收（`tests/api_test.py` 有防泄漏用例）。

引擎异常一律在原生边界转成回退值（JSON 错误对象或空串）并写 stderr —— 在 fakelua 里
错误逃出 `update` 等于整个服务退出，一个坏请求不该把进程带走。

---

## 4. 生命周期与节拍

宿主固定调用 `package "main"` 的 `init` / `update` / `fini`，每次调用都是独立的顶层 Call
（帧 arena 在 Call 入口重置，请求级临时内存随之回收）。因此 Lua 侧绝不写 `while true`
把请求包进单个 Call。

`update` 只做 `runtime.tick()` 加一次闲置扫描，然后自己 `os.sleep(ms)` 节拍：空闲退避 20ms，
收到请求后的 16 拍内回到 1ms。一次 HTTP 请求要跨三拍（收 → 派发 → 回包），
所以 loopback 上的请求延迟约 4ms，主要由节拍决定。

# doudizhu_ai Go 实现

Java 版 [doudizhu_ai/java](../java) 的 Go 移植:斗地主引擎状态机、与训练侧完全一致的动作枚举、
PerfectDou 输入编码,以及**纯 Go 的策略网络推理**(不用 cgo、不用 libtorch、不用 ONNX runtime)。
权重与对齐基准和 Java/C++ 共用仓库根目录 [data/](../data),行为逐位对齐。

## 使用

```bash
go get github.com/esrrhs/doudizhu_ai/go
```

```go
package main

import (
	"fmt"

	doudizhu "github.com/esrrhs/doudizhu_ai/go"
)

func main() {
	counts, _ := doudizhu.ParseCards("333444")

	// 牌型判定: (type, 主牌点数索引, 链长)
	fmt.Println(doudizhu.Detect(counts)) // 10 0 2 -> MTPlane 飞机不带

	// 一手牌最少几步出完(与训练侧 oracle 逐位一致)
	fmt.Println(doudizhu.MinSteps(counts)) // 1

	// 自由出牌的完整候选(飞机带翅、四带二会枚举每一种具体带牌组合)
	lead, _ := doudizhu.ParseCards("333444567")
	options := doudizhu.Legal(lead, [15]int32{})
	fmt.Println(len(options), doudizhu.DebugCards(options[0])) // 24 3

	// 神经网络玩家: 权重在 data/models/ts 下,座位按 (playerId - landlordId + 3) % 3 选择
	policy, err := doudizhu.Load()
	if err != nil {
		panic(err)
	}
	// 地主那一家 DEAL 的 20 张已含底牌,NewState 不会再叠加底牌
	hands := make([][15]int32, 3)
	for i, s := range []string{
		"334445566689TTTJKAAR",
		"4577899JQQKKAA222",
		"335677889TJJQQK2B"} {
		hands[i], _ = doudizhu.ParseCards(s)
	}
	bottom, _ := doudizhu.ParseCards("4TK")
	state, _ := doudizhu.NewState(hands, 0, bottom)
	move, scores, _ := policy.Decide(state.PublicViewOf(state.ActivePlayerID()))
	fmt.Println(move, len(scores)) // [P0: 334455 (连对, main=5)] 68
}
```

## 公开 API

点数索引 `0..14 = 3 4 5 6 7 8 9 T J Q K A 2 小王 大王`(即 Java `Rank.getValue() - 3`),
牌集一律用 `[15]int32` 张数向量表示,全零即过牌。

| Go | 等价的 Java 调用 |
| --- | --- |
| `Legal(hand, toBeat [15]int32) [][15]int32` | `PerfectDouMoves.legal` |
| `Detect(cs [15]int32) (mtType, rank, length int)` | `PerfectDouMoves.detect` |
| `ToMove(cs [15]int32, playerID int) (*Move, error)` | `PerfectDouMoves.toMove` |
| `Key(cs [15]int32) int64` | `PerfectDouMoves.key`(45 位多重集指纹) |
| `MinSteps(cs [15]int32) int` | `Oracle.minSteps` |
| `Observe(view *PublicView) (*View, error)` | `PerfectDouFeatures.observe` |
| `Encode(v *View, xImp, seq []float32)` | `PerfectDouFeatures.encode`(1676 / 5400) |
| `ActionFeature(v *View, m *Move, out []float32, off int)` | `PerfectDouFeatures.actionFeature`(186) |
| `Load() / LoadPolicy(dir) *Policy` | `ActorNetwork.load` |
| `(*Policy).Score(seat, xImp, seq, feat, options)` | `ActorNetwork.score` |
| `(*Policy).Decide(view)` | `NeuralAiPlayer.decide` |
| `NewState / (*State).ApplyMove / (*State).PublicViewOf` | `GameState` |
| `FindSharedData / ResolveSharedData` | `SharedData` |

维度常量:`NodeSize=1676`、`SeqSize=5400`、`ActionSize=186`、`CardMatrix=180`、`LSTMSteps=10`、
`LSTMIn=540`;牌型编码为 `MTPass..MTWrong`,与 C++ `MoveType` 一一对应。

## 构建与测试

```bash
cd go
gofmt -l .
go vet ./...
go build ./...
go test ./... -v
```

CI 在 [.github/workflows/go.yml](../.github/workflows/go.yml) 里按 vet → build → test 执行。

- `parity_test.go`:**跨语言对齐契约**。回放 `../data/golden/golden24.txt.gz`
  (PerfectDouCPP `tools/pddump.cpp` 导出,24 局 712 个决策),逐决策断言
  候选动作集合与 C++ 作为 45 位多重集完全相等、`xImp`/`seq`/每个 `actionFeat` **逐位相等**、
  手写前向的 logits 偏差 < 1e-3 且 argmax 恒等于 golden 的 `CHOSEN` 槽位。
  当前结果:712 个决策,最大 logit 偏差 4.9e-4,argmax 不一致 0 个。
- `moves_test.go` / `oracle_test.go` / `features_test.go` / `def_test.go`:与 Java JUnit 用例
  一一对应的移植,外加与 oracle 无关的暴力最少步数对照。
- `policy_test.go`:推理入口的冒烟测试与输入校验。

## 权重读取

`data/models/ts/actor{0,1,2}_ts.pt` 是 TorchScript 归档,张量以 **STORED(不压缩)** 的 zip 条目
`<root>/data/<i>` 存放,是小端 float32 行主序裸字节;`actor{N}_manifest.json`
(由 [tools/export_actor.py](../tools/export_actor.py) 生成)记录了 条目序号 → 层名 → 形状。
Go 侧只用标准库 `archive/zip` + `encoding/json` 读取,并按形状校验字节数,不硬编码形状。

前向:`node = concat(xImp[1676], LSTM 末步隐状态[128])`,每个候选
`joint = concat(node, actionFeat[186])` → `relu(l1)` → `relu(l2)` → `relu(l3)` → `relu(l4)` → `head`。
LSTM 取门顺序 **i, f, g, o**(即 PyTorch `weight_ih_l0` 的实际拼接顺序)。

> 注意:`actor{N}_manifest.json` 里的 `gateOrder` 文字写的是 `"i, h, f, c"`,那是 PyTorch 内部
> `_lstm_gate` 的旧命名,**与实际不符**:按该顺序解码时 logits 与 golden 差到 1~1900,按
> `i, f, g, o` 解码时逐位吻合。以 golden 基准为准,Go 与后续 C++ 移植都应按 `i, f, g, o` 实现。

## 与 Java 的差异

- 推理是手写 float32 前向(Java 走 DJL/libtorch),因此 logits 偏差约为 5e-4 而不是 2.4e-4;
  仍远小于 1e-3 的门限,argmax 完全一致。`l1` 拆成"状态部分先算一次 + 每个候选只算 186 那一截",
  与 TorchScript 的 `concat` 数学一致,只差浮点求和顺序。
- `Move` 用 `[15]int32` 张数向量存牌(Java 用 `List<Rank>`),信息等价。
- `Oracle` 的备忘录在 Java 里是 `ThreadLocal`,Go 侧改为进程内共享缓存 + 互斥锁
  (纯函数,缓存只影响速度不影响结果)。
- 未移植 `GameState.copy()` 深拷贝与 `rules/MoveGenerator`(引擎侧简化枚举的生成器):
  策略网络必须吃完整枚举的动作空间,Go 侧统一用 `Legal`。

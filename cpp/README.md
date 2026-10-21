# doudizhu_cpp implementation (C++)

A C++ port of the Java version [doudizhu_ai/java](../java) and the Go version [doudizhu_ai/go](../go): the Dou Dizhu
engine state machine, move enumeration exactly identical to the training side, PerfectDou input encoding, and a
**hand-written float32 policy-network inference** (no libtorch, no ONNX runtime, no cgo). The library target has zero
third-party dependencies; only the tests use the system zlib to read the `.gz` baseline.
The weights and the alignment baseline are shared with Java/Go through the repository root [data/](../data);
behaviour is aligned bit for bit.

## Usage

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

Linking:

```bash
g++ -std=c++20 -Icpp/include main.cpp cpp/build/libdoudizhu.a
```

## Public API

All headers live under `include/doudizhu/`; `doudizhu/api.h` includes them all at once.
Rank indices `0..14 = 3 4 5 6 7 8 9 T J Q K A 2 小王 大王` (i.e. Java `Rank.getValue() - 3`; 小王/大王 are the
small and the big joker), and every card set is the 15-dimensional count vector `CardSet` — all zeros means pass.

| C++ | Equivalent Java call |
| --- | --- |
| `Key(const CardSet&) -> long long` | `PerfectDouMoves.key` (45-bit multiset fingerprint) |
| `DebugCards(const CardSet&)` / `ParseCards(std::string_view)` | card-text conversions |
| `Move::CanBeat(const Move*)` | `Move.canBeat` |
| `Detect(const CardSet&) -> Info{type, rank, len}` | `PerfectDouMoves.detect` |
| `Legal(const CardSet &hand, const CardSet &toBeat)` | `PerfectDouMoves.legal` |
| `ToMove(const CardSet&, int playerId)` | `PerfectDouMoves.toMove` |
| `MinSteps(const CardSet&)` | `Oracle.minSteps` |
| `Observe(const PublicView&) -> View` | `PerfectDouFeatures.observe` |
| `Encode(const View&, float *xImp, float *seq)` | `PerfectDouFeatures.encode` (1676 / 5400) |
| `ActionFeature(const View&, const Move&, float *out, int off)` | `PerfectDouFeatures.actionFeature` (186) |
| `CountsOf(const Move&)` | `PerfectDouFeatures.countsOf` |
| `Policy::Load(dir)` / `Policy::DefaultModelDir()` | `ActorNetwork.load` |
| `Policy::Score(seat, xImp, seq, actionFeat, options)` | `ActorNetwork.score` |
| `Policy::Decide(const PublicView&) -> Decision` | `NeuralAiPlayer.decide` |
| `GameState` / `PublicView` | `game/GameState`, `game/PublicView` |
| `FindSharedData` / `ResolveSharedData` | `SharedData` |

Dimension constants (`include/doudizhu/perfect_dou_features.h`, `policy.h`):
`kCardMatrix=180`, `kNodeSize=1676`, `kSeqSize=5400`, `kActionSize=186`, `kLstmSteps=10`,
`kLstmIn=540`, `kLstmHidden=128`, `kNodeDim=1804`, `kJointSize=1990`, `kPolicySeats=3`.
The move-type encoding `MT_PASS..MT_WRONG` matches the values of Java `PerfectDouMoves.MT_*` and Go `MT*`.

`Policy::Decision` holds everything a single decision produces: `move` (the chosen action), `slot` (the candidate
slot), `options` (the candidate multisets), `moves` (the engine `Move`s in one-to-one correspondence with the
candidates), `logits` (the score of each candidate), `probs` (the softmax of the logits).
Dirty input to `Observe` / `ToMove` / `GameState` throws `std::invalid_argument` or `std::logic_error`.

## Build and test

```bash
cd cpp
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
ctest --test-dir build --output-on-failure
```

C++20; on non-MSVC the build adds `-Wall -Wextra -ffp-contract=off`.
`-ffp-contract=off` disables FMA contraction, which measurably lowered the maximum logit deviation from the libtorch
baseline from 5.8e-4 to 5.2e-4.

There is a single test target, `parity` (`test/parity_test.cpp`), and it is the **cross-language alignment contract**:
it replays [../data/golden/golden24.txt.gz](../data/golden) (training-side states exported by
[tools/pddump.cpp](../tools/pddump.cpp), 24 games / 712 decisions) and asserts, per decision, that

1. the candidate action set and the record are **exactly equal in both directions** as 45-bit multisets (measurements
   show even the enumeration order matches);
2. `xImp`, `seq` and every candidate's `actionFeat` are **bit-identical** (compared by IEEE-754 bit pattern);
3. the hand-written forward pass has a logit deviation **< 1e-3**, and its argmax always equals the recorded
   `CHOSEN` slot;

plus an end-to-end assertion that `Policy::Decide` (using this project's own enumeration order and encoding) picks the
same cards as the record, and a check that the full 24 games / 712 decisions declared in the header were read.
Current result:

```text
回放 24 局 712 个决策 (候选总数 6460): 最大 |logit 偏差| 5.188e-04 (第585个决策), argmax 不一致 0 个,
logits 超容差 0 个, Decide 端到端不符 0 个, 特征逐位差异 0 处, 候选集合差异 0 处, 记录格式错误 0 处
PARITY OK
```

For comparison: Java (libtorch) 2.7e-4, Go (hand-written float32) 4.883e-4, C++ (hand-written float32) 5.188e-4, and
all three have 0 argmax mismatches. The baseline record format is documented in
[../tools/README.md](../tools/README.md).

## Weight loading

`data/models/ts/actor{0,1,2}_ts.pt` are TorchScript archives; tensors are stored as **STORED (uncompressed)** zip
entries `<root>/data/<i>`, which are raw little-endian float32 row-major bytes; `actor{N}_manifest.json`
(generated by [tools/export_actor.py](../tools/export_actor.py)) records entry index → layer name → shape.
The C++ side parses the zip central directory itself (`src/pt_archive.cpp`) plus a JSON subset that is good enough
here, without pulling in zlib or libtorch; shapes, entry lengths and the completeness of layer names are all validated
against the manifest, so no weights are hardcoded.

Forward pass: `node = concat(xImp[1676], LSTM last-step hidden state[128])`, then per candidate
`joint = concat(node, actionFeat[186])` → `relu(l1)` → `relu(l2)` → `relu(l3)` → `relu(l4)` → `head`.
The LSTM takes the gate order **i (input gate), f (forget gate), g (candidate, tanh), o (output gate)**, which is the
actual concatenation order of PyTorch `weight_ih_l0`; `i/f/o` go through sigmoid and only `g` goes through tanh.

> The gate order is the easiest trap in this port: slicing by PyTorch's old `_lstm_gate` naming `i, h, f, c` puts the
> logits 1~1900 away from the baseline and breaks about 89 argmaxes. The `gateOrder` written by
> `tools/export_actor.py` has been corrected, and `Policy::Load` rejects a `gateOrder` that disagrees with this
> implementation (the same check as on the Go side).

## Differences from Java

- Inference is a hand-written float32 forward pass (Java goes through DJL/libtorch), so the logit deviation is about
  5e-4 rather than 2.7e-4; still far below the 1e-3 threshold, and the argmax matches exactly. `l1` is split into
  "compute the state part once + only the 186 slice per candidate", which is mathematically the same as TorchScript's
  `concat` and differs only in floating-point summation order; dimensions whose action feature is 0 are skipped
  entirely in the accumulation.
- `Move` stores cards as a `CardSet` count vector (Java uses `List<Rank>`); the information is equivalent.
- The `Oracle` memo table uses `thread_local` (Java uses `ThreadLocal`, Go uses a shared cache plus a mutex); it is a
  pure function, so the cache affects speed only, never the result.
- There is no explicit Java-style `GameState.copy()`: every member is value-semantic, so the default copy constructor
  is already a deep copy. `rules/MoveGenerator` (the engine-side generator with simplified enumeration) was not ported
  either: the policy network must be fed the fully enumerated action space, so `Legal` is used throughout.
  `game/Player` is folded into the count arrays of `GameState`, and `rules/Deck`'s 54-card constant is inlined in
  `PublicView::ComputeUnseenCards`; there is no bidding phase — the landlord seat and the deal are given by the caller,
  exactly as in the baseline records.
- `Decision.probs` is a `vector<float>` indexed by candidate slot, while Java's `probabilityTable` is a
  `List<MoveScore>` sorted by descending probability; the values are equivalent, only the container shape differs.

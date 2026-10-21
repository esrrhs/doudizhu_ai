# Dou Dizhu AI (doudizhu_ai)

[![License](https://img.shields.io/github/license/esrrhs/doudizhu_ai)](https://github.com/esrrhs/doudizhu_ai)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.esrrhs/doudizhu_ai)](https://central.sonatype.com/artifact/com.github.esrrhs/doudizhu_ai)
[![Java CI](https://github.com/esrrhs/doudizhu_ai/actions/workflows/maven.yml/badge.svg?branch=master)](https://github.com/esrrhs/doudizhu_ai/actions/workflows/maven.yml)
[![Go CI](https://github.com/esrrhs/doudizhu_ai/actions/workflows/go.yml/badge.svg?branch=master)](https://github.com/esrrhs/doudizhu_ai/actions/workflows/go.yml)
[![C++ CI](https://github.com/esrrhs/doudizhu_ai/actions/workflows/cpp.yml/badge.svg?branch=master)](https://github.com/esrrhs/doudizhu_ai/actions/workflows/cpp.yml)
[![Server CI](https://github.com/esrrhs/doudizhu_ai/actions/workflows/server.yml/badge.svg?branch=master)](https://github.com/esrrhs/doudizhu_ai/actions/workflows/server.yml)

*中文版: [README_CN.md](README_CN.md)*

Three independent implementations of a Dou Dizhu (Fight the Landlord) AI engine
(Java 17 / Go 1.21 / C++20) plus a **playable web client** built on
[FakeLua](https://github.com/esrrhs/fakelua). Card play is driven by a reinforcement-learning
policy network (PerfectDou-style self-play, weights checked into this repo); the landlord bid is random.
All three languages share one set of weights and one golden fixture and agree on the logits and the chosen
move across 712 real decisions.

---

## 📂 Layout

```text
doudizhu_ai/
├── java/            # Java 17, Maven coordinate com.github.esrrhs:doudizhu_ai
│   └── src/main/java/com/doudizhu/{model,rules,game,ai,nn}/  + Main.java (3-AI self-play demo)
├── go/              # Go 1.21, zero third-party deps (hand-written forward pass, no cgo/ONNX)
├── cpp/             # C++20, zero third-party deps (own zip parsing + hand-written forward pass)
├── server/          # web platform: Lua business logic + C++ host + frontend
│   ├── lua/  host/  static/  tests/  API.md  README.md
├── data/
│   ├── models/latest/   # raw training output (parameters only, no forward)
│   ├── models/ts/       # runnable TorchScript weights + per-layer manifests (shared, 13 MB)
│   └── golden/          # per-decision fixture exported from the training side (shared, 304 KB)
└── tools/           # weight re-export and fixture generation (not part of runtime)
```

Rules, game state machine and feature encoding are implemented separately in each language;
only the **weights** and the **golden fixture** are shared.

---

## 🎯 What "parity" means here

Parity is a contract written into the tests, not a vibe. The fixture
`data/golden/golden24.txt.gz` holds 24 games, 712 decisions and 6,460 candidate moves,
dumped from the training side by `tools/pddump.cpp`:

| Checked | Requirement |
|---|---|
| Candidate set | identical to the training side (compared as 45-bit multisets, including every concrete airplane / four-plus-two kicker combination) |
| `xImp` / `seq` / `actionFeat` | **bit-identical**, no tolerance |
| logits | max absolute deviation < 1e-3 |
| argmax move | must match exactly |

Measured by each language's own test run:

| Implementation | Max logit deviation | argmax mismatches | Avg decision |
|---|---|---|---|
| Java (libtorch via DJL) | 2.747e-4 | 0 | 0.84 ms |
| Go (hand-written forward) | 4.883e-4 | 0 | 7.80 ms |
| C++ (hand-written forward) | 5.188e-4 | 0 | ~1 ms |

The residual comes only from floating-point summation order, so the C++ build forces
`-ffp-contract=off` and the tolerance has never been loosened. `server/` reuses the C++ engine,
so the served moves are bit-identical to `cpp/`.

---

## 🚀 Using each implementation

### Java

```bash
cd java
./mvnw clean test                    # 9 tests, including bit-level parity with the training side
./mvnw exec:java                     # command-line demo: three AIs play one game
./mvnw exec:java -Dexec.args="--model-dir=/path/to/weights"
```

```xml
<dependency>
    <groupId>com.github.esrrhs</groupId>
    <artifactId>doudizhu_ai</artifactId>
    <version>1.1.0</version>
</dependency>
```

The rules engine (`model` / `rules` / `game`) has no dependencies. The play AI needs native libtorch,
and the DJL dependencies are marked `optional` in the pom — they do **not** propagate to consumers — so
using `NeuralAiPlayer` means adding the engine and your platform's native library yourself:

```xml
<dependency>
    <groupId>ai.djl.pytorch</groupId>
    <artifactId>pytorch-engine</artifactId>
    <version>0.39.0</version>
</dependency>
<!-- pin the native library explicitly, otherwise DJL downloads it into ~/.djl.ai on first use -->
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

Weights must be exported with the same libtorch major version that loads them (this project is pinned
to **2.7.1**, the newest native library in DJL 0.39).

### Go

```bash
cd go && go test ./...      # 21 tests, zero third-party dependencies
```

```go
import ddz "github.com/esrrhs/doudizhu_ai/go"

policy, err := ddz.Load()                        // weights directory is located from the repo layout
move, logits, err := policy.Decide(view)         // enumerate all candidates, take the highest score
```

### C++

```bash
cd cpp
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j
ctest --test-dir build --output-on-failure
```

```cpp
#include "doudizhu/api.h"

doudizhu::Policy policy;
policy.Load(doudizhu::Policy::DefaultModelDir());
auto d = policy.Decide(state.GetPublicView(active));
```

---

## 🌐 Web platform (FakeLua)

One human (seat 0) versus two policy-network AIs: random landlord bidding, bottom cards reveal,
move hints, an AI reasoning panel, a card counter, a game log, and per-session isolation.

```bash
cd server
cmake -S host -B host/build -DCMAKE_BUILD_TYPE=Release   # needs fakelua installed, see server/README.md
cmake --build host/build -j
DOUDIZHU_PORT=8080 ./host/build/doudizhu_server lua      # http://localhost:8080
python3 tests/api_test.py                               # 12 HTTP cases
```

Rules and inference stay in C++; Lua only handles HTTP, sessions and flow.
The contract is documented in [server/API.md](server/API.md).
Measured on macOS arm64: ~35 MB resident, loopback request p50 ~4 ms, decision p50 ~2 ms.

---

## 🧠 Weights and re-export

`data/models/latest` is what the training machine writes through a libtorch `OutputArchive`:
**parameters only, no forward**, so no TorchScript runtime can call it directly. It has to be rebuilt:

```bash
# 1) extract the parameters with the same PyTorch version the training side used
python3 tools/export_actor.py extract --in data/models/latest --out /tmp/params
# 2) rebuild and script with the version that matches the native library (2.7.1 here)
python3 tools/export_actor.py script --in /tmp/params --out data/models/ts
```

`export_actor.py` also writes `actor<N>_manifest.json`. A TorchScript zip stores tensors as **STORED
(uncompressed)** entries, so the manifest maps "entry index → layer → shape" and Go/C++ can load the
weights with nothing but a standard-library zip reader. The fixture comes from `tools/pddump.cpp`.
Steps, record format and the traps (the LSTM gate chunks are ordered **i, f, g, o**; getting it wrong
produces deviations in the thousands) are in [tools/README.md](tools/README.md).

---

## 🃏 Rules and bidding

54 cards; the landlord holds 20, each farmer 17, with 3 bottom cards. Supported shapes: single, pair,
triple, triple-plus-one, triple-plus-pair, straight (5+), pair chain (3+), airplane (2+ consecutive
triples, with or without kickers), four-plus-two, bomb and rocket. If either farmer empties their hand
first, both farmers win.

During bidding each player (including both AIs) calls landlord **independently with probability 1/3**;
the first caller becomes the landlord and takes the 3 bottom cards. If nobody calls, the deal is void and
the first caller position rotates. Bidding ignores hand strength.

The policy network sees public information only (own hand, the combined unknown cards of the other two,
what each player has played and the last move, remaining bottom cards, the last 30 moves, remaining
trick counts) and **never reads another player's private hand**. The numbers in the UI are softmax
**policy probabilities** (model preference), not search win rates.

---

## 📝 Notes on the previous engine

The original play engine was **PIMC sampling plus a 2v1 cooperative MCTS search**, with hand-shape
heuristics and hard guardrails. In paired matches (same deal played twice with the roles swapped),
the policy network beat it:

| Old PIMC setting (worlds × iterations) | Games | Policy network win rate |
|---|---|---|
| 25 × 120 | 48 | 64.6% |
| 120 × 600 | 24 | 58.3% |
| 240 × 1600 | 80 | **68.8%** |

On the strength of that result the whole search chain (`PimcAiPlayer`, `MctsSearcher`, `Determinizer`,
`FastRolloutPolicy`, …), the sampling-based `BidEvaluator` and the tools that depended on them
(`SelfPlayRunner`, `MatchRunner`) were removed, the bid became random, and the web client moved from an
embedded Java server to `server/`. Everything before commit `f8c9680` still contains that implementation.

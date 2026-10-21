# Weight tooling (tools/)

*读中文版: [README_CN.md](README_CN.md)*

These two scripts serve one pipeline: "weights produced by training → weights Java can run
inference on → a verifiable port". They are **not part of the runtime and Maven does not package
them**; the project itself only depends on the outputs `data/models/ts` and
`data/golden/golden24.txt.gz`.

| File | What it does | Where it runs |
|---|---|---|
| `export_actor.py` | Rebuilds the training weights into a TorchScript module with a forward, for DJL to load | Locally or on the training box, needs Python + PyTorch |
| `pddump.cpp` | Generates the per-decision baseline records (features + logits + the move actually chosen), the reference the Java port is checked against | The training box, needs the PerfectDouCPP build environment |

---

## 1. Re-exporting the weights

The training side's `ActorImpl::save()` writes through libtorch's `torch::serialize::OutputArchive`,
so the resulting `.pt` files carry **parameters only, no forward**: `torch.jit.load` can open them,
but the first call fails with `RuntimeError: Method 'forward' is not defined`. They therefore have
to be rebuilt once.

And because PyTorch does not guarantee that a newer serial archive can be read by an older runtime,
while **the newest native library published for DJL 0.39.0 is libtorch 2.7.1** (the training box runs
torch 2.14), the export must happen in two stages with two different torch versions:

```bash
# Stage 1: extract the parameters from the raw archive with the torch the training side used (2.14)
python3 tools/export_actor.py extract --in data/models/latest --out /tmp/params

# Stage 2: rebuild and script with the torch that matches the native library (2.7.1)
/tmp/ts27/bin/python tools/export_actor.py script --in /tmp/params --out data/models/ts
```

The `script` stage also writes `data/models/ts/actor<N>_manifest.json` for every actor, recording
`entry` index → layer name → shape one by one. TorchScript stores the tensor data as **STORED
(uncompressed)** entries named `<root>/data/<i>`, so Go / C++ only have to read the raw float32 bytes
following the manifest: no libtorch, no zlib, and no extra `.bin` copy (which would double the
weights). The manifest was verified on the training box with `torch.equal`, comparing `entry i`
against `named_parameters()[i]` layer by layer.

> Trap: the four LSTM chunks are ordered **i, f, g, o** as in the PyTorch documentation (`g` is the
> candidate cell value), not the i,h,f,c order cuDNN is usually written in. Hand-build the forward in
> the wrong order and the logits drift into the 1~1900 range, with about 89 wrong argmaxes.

With `--golden`, the `script` stage also replays the baseline records and reports the deviation from
C++:

```bash
/tmp/ts27/bin/python tools/export_actor.py script --in /tmp/params --out data/models/ts \
    --golden /tmp/golden.txt
# Expected output: max |logit diff| in the 1e-4 range, argmax mismatches = 0
```

After an export, run the alignment tests once to confirm:

```bash
cd java && ./mvnw test -Dtest='PerfectDouFeaturesGoldenTest,ActorNetworkGoldenTest'
```

> How `data/models/latest` and `data/models/ts` relate: the former is the raw output of the training
> box, the latter is the files this project actually loads. When you change the DJL version, redo
> both (the native library version decides which torch stage 2 uses).

---

## 2. Regenerating the alignment baseline (pddump)

The two Java tests consume the "ground-truth records" exported by C++. **They only need regenerating
when the training-side feature encoding or the network structure changes
(`src/ddz/features.cpp`, `src/ddz/oracle.cpp`, `src/torch_nn/models.h`).**

Compile and run it on the training box (it only links PerfectDouCPP's existing static library; that
project is left untouched):

```bash
TP=/usr/local/lib/python3.10/site-packages/torch       # the same-version libtorch installed via pip
export LD_LIBRARY_PATH=$TP/lib:/root/GCC-16/lib64       # without the latter: CXXABI_1.3.15 not found

g++ -std=c++20 -O2 -o pddump pddump.cpp \
    -I<PerfectDouCPP>/src -I$TP/include -I$TP/include/torch/csrc/api/include \
    -D_GLIBCXX_USE_CXX11_ABI=1 \
    <PerfectDouCPP>/build/libperfectdou.a \
    -L$TP/lib -ltorch -ltorch_cpu -lc10 -lpthread -ldl

./pddump 24 777 <PerfectDouCPP>/models/latest > golden.txt      # 24 games, start seed 777
gzip -c golden.txt > golden24.txt.gz                            # about 300KB
```

Put `golden24.txt.gz` in `data/golden/` over the old file (the text is about 13MB and gzips down to
almost nothing, because the card matrices are nearly all 0/1).

### Record format

Read token by token, line by line: every numeric block starts with one `<KEY> <count>` header line,
followed by at most 20 numbers per line:

```text
DDZGOLDEN <version> <games> <start seed>
GAME <index>
DEAL <seat> <cards>          # three lines; seat 0 is always the landlord
BOTTOM <cards>               # the 3 bottom cards (the landlord's hand is sorted, so they cannot be inferred and must be given)
LANDLORD 0
DEC <index> TURN <seat> HIST <actions so far>
XIMP 1676                    # node features: 9 card matrices 1620 + 56 scalars
SEQ 5400                     # the last 30 moves' card matrices, every 3 moves packed into one LSTM timestep
OPT <candidate count>
OPTLINE <cards>              # one candidate move
AFEAT 186                    # that candidate's action features
LOGIT <candidate count>      # the actor's scores for the candidates
CHOSEN <slot> <cards>
ACT <seat> <cards>           # the cards actually played for this decision, so a reader can replay them in order
ENDDEC
```

Cards use the training-side notation: `3..9 T J Q K A 2 B` (小王, the joker) `R` (大王, the big joker),
and a pass (过牌) is `-`.
The Java reader lives in `src/test/java/com/doudizhu/nn/GoldenReader.java`.

---

## 3. Expected alignment results

Current baseline (24 games, 712 decisions):

- Candidate action sets: **exactly equal** to C++ (compared as a 45-slot multiset, independent of index order; in practice the order matched too)
- `xImp` / `seq` / every `actionFeat`: **bit-identical**
- logits: maximum deviation **2.7e-4** (pure floating-point noise across libtorch versions)
- argmax: **0 mismatches**
- one inference: about 0.8ms

Any regression in one of these means the port is no longer equivalent to the training side: check the
features first, and never widen the tolerance to make the test "green".

---

## 4. Traps already hit

- **Loading the raw archive directly** → `Method 'forward' is not defined`; you must go through the export procedure in this file.
- **An export torch whose version differs from the native library** → cross-version reads are not guaranteed to work; stay pinned to 2.7.1.
- **`CXXABI_1.3.15 not found`** → the training box compiles with GCC-16, so the runtime `LD_LIBRARY_PATH` must include
  `/root/GCC-16/lib64`.
- **Running the venv's python with `LD_LIBRARY_PATH` set** → it loads the native libraries of the pip-installed system torch and fails with
  `No module named 'torch._custom_class_base'`; `unset LD_LIBRARY_PATH` before running python.
- **Truncated downloads of the large files** (the 41MB native-library jar often breaks mid-transfer) → loop
  `curl -sS -C -` until the file is complete, then place it under `~/.m2/repository/...`.

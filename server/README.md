# Dou Dizhu web battle platform (FakeLua service)

*读中文版: [README_CN.md](README_CN.md)*

The fourth thing besides the three algorithms (`java/` `go/` `cpp/`): wrap the engine into a service
you can actually play. **The card rules and the neural-network inference both live in C++; Lua only
does HTTP, sessions and flow orchestration.**

- Engine: the `cpp/` static library `doudizhu` (the build that is bit-aligned with Java/Go, no third-party dependencies)
- Host: `host/main.cpp`, registers the engine as `doudizhu.*` native functions and runs fakelua's `init/update/fini`
- Business logic: `lua/` (`main.lua` lifecycle, `http_server.lua` routing, `game.lua` session flow, `def.lua` encoding conversion)
- Frontend: `static/` (character-for-character identical to the old Java version — the HTTP contract did not change, so not one line was edited)
- Contract: [API.md](API.md); test cases: [tests/api_test.py](tests/api_test.py)

## Why not just use `flua`

The `majiang_algorithm` service has no host: that whole algorithm was ported into Lua. Dou Dizhu
cannot do that — a single decision runs a 1990-dimensional × 5-layer forward once per candidate, and
there are hundreds of candidates; pure Lua is an order of magnitude slower, and `cpp/` already has an
implementation bit-aligned with Java/Go, so copying it into Lua again would only add another source
of truth that can drift. This is therefore a thin host: rules and inference stay in C++, and every
number Lua receives comes from that same engine.

## Build

Requires [fakelua](https://github.com/esrrhs/fakelua) (provides `libfakelua` and the headers).

```bash
# 1) install fakelua (built from source, its dependencies are fetched by CPM)
git clone https://github.com/esrrhs/fakelua && cd fakelua
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
      -DFAKELUA_BUILD_TESTS=OFF -DFAKELUA_BUILD_BENCHMARKS=OFF
cmake --build build --target flua -j
sudo cmake --install build --prefix /usr/local       # or ~/.local, /opt/homebrew

# 2) build the host (looks for fakelua under /opt/homebrew by default; if it is not there, point -DFAKELUA_PREFIX at it)
cd ../doudizhu_ai/server
cmake -S host -B host/build -DCMAKE_BUILD_TYPE=Release -DFAKELUA_PREFIX=/usr/local
cmake --build host/build -j
```

The host itself reads only three switches (`--once` / `--jit_type=n` / `--model_dir=...`) and does not
depend on gflags; the weights are located through `cpp/`'s `Policy::DefaultModelDir()`, which searches
upwards from the current directory for `data/models/ts`, so **start it from the `server/` directory**.

## Running

```bash
cd server
DOUDIZHU_PORT=8080 ./host/build/doudizhu_server lua
# open http://127.0.0.1:8080 in a browser
```

Self-check (starts no HTTP server, exits as soon as init is done, non-zero exit code means failure):

```bash
DOUDIZHU_SMOKE=1 ./host/build/doudizhu_server lua --once
```

What the self-check covers: the weights are loaded, the deal is 17/17/17 with the bottom cards face
down, after the landlord is set that hand has 20 cards and leads, all three seats play to the end of
the game through the policy network, a game object can be released explicitly, the shape of the
session DTO, a non-empty hint, the AI one-step move plus its reasoning data, JSON string keys encoded
as objects, and both card notations round-tripping identically.

API regression:

```bash
python3 tests/api_test.py                                  # starts its own server on a random port
python3 tests/api_test.py --base-url http://127.0.0.1:8080 # hits an already running server
```

## Gameplay

A human sits in seat 0. During the bidding phase every seat (both AIs included) **declares at random,
each with probability 1/3**; if all three decline, the deal is void, the cards are reshuffled and the
first-caller position rotates. Once the landlord is settled, the 3 bottom cards are revealed and merged
into the landlord's hand, and the landlord leads. In the playing phase the two AIs take the
highest-scoring move directly from the PerfectDou-style policy network; the human can click 【AI 提示】
(AI hint) to ask for a suggested move.

The 「阵营胜率」 (camp win rate) shown on the web page is the **softmax policy probability**, not a
search win rate.

## Resource usage

macOS arm64, Release build, loopback:

| Metric | Value |
|---|---|
| Resident memory RSS | about 35 MB (three weight sets included, single session) |
| Request latency p50 | about 4 ms (one request spans three ticks, the tick pace is the bulk of it) |
| Per-decision time | p50 2 ms, p90 5 ms, max 9 ms |
| Concurrency model | single-threaded event loop, at most 1 core saturated |
| Idle CPU | about 0.1% (with no requests the tick backs off to 20 ms) |

fakelua has no GC, but the frame arena is reset at the entry of every top-level Call, so the
per-request temporary tables and strings are reclaimed when the call returns. Only two things
**stay resident long term**: the serialized session tables in `container.map`, and the game objects in
the host registry — the latter must be released explicitly with `doudizhu.free`, so restarting a game
frees the previous one first, and sessions idle for over an hour are reclaimed by `Sweep`.

## Production deployment

<https://doudizhu.esrrhs.xyz> —— nginx already reverse-proxies that domain to `127.0.0.1:8889`, the
process is managed by systemd (`doudizhu.service`, with `User=ubuntu`,
`WorkingDirectory=<repo>/server`, `Environment=DOUDIZHU_PORT=8889`, `Restart=always`), the same pattern
as `majiang.service` / `fakefish.service`.
The code lives at `ubuntu@gq.esrrhs.xyz:/home/ubuntu/project/doudizhu_ai`.

**That machine cannot reach GitHub**, so publishing goes through rsync instead of `git pull`:

```bash
# run locally: sync the code (excluding .git and all build directories)
cd .. && rsync -az --delete --exclude='.git/' --exclude='**/target/' \
    --exclude='server/host/build/' --exclude='cpp/build/' --exclude='go/bin/' \
    doudizhu_ai/ ubuntu@gq.esrrhs.xyz:/home/ubuntu/project/doudizhu_ai/

# run remotely: rebuild + self-check + restart
ssh ubuntu@gq.esrrhs.xyz 'cd /home/ubuntu/project/doudizhu_ai/server && \
    cmake -S host -B host/build -DCMAKE_BUILD_TYPE=Release -DFAKELUA_PREFIX=/usr/local && \
    cmake --build host/build -j2 && \
    DOUDIZHU_SMOKE=1 ./host/build/doudizhu_server lua --once && \
    sudo systemctl restart doudizhu'

# run the API contract against production (12 cases)
python3 server/tests/api_test.py --base-url https://doudizhu.esrrhs.xyz
```

The deploy box already has the `/usr/local` fakelua installed (`flua` + `libfakelua` +
`fakeluaConfig.cmake`, the same copy `majiang`/`fakefish` use), so only this repository's host needs
building and boost never gets rebuilt; on a different machine install fakelua first, see **Build**
above.

gq is a shared 2-core 2G machine: measured on it this service sits at about 34 MB RSS with 0.4% idle
CPU, and the time from restart until the socket listens again is about 5 s (the JIT compilation of the
4 Lua files). It
coexists with majiang (:8888) and fakefish (:8080), so keep the ports from colliding.

## Known limits

- Single-threaded event loop: one request spans three ticks (receive → dispatch → respond), so the
  tail latency is decided by the tick pace rather than by the algorithm.
- `lua/` uses no coroutines, no metatables and no `require` (fakelua does not support them); file-level
  `local` values are constants, so mutable state always goes through `container.map`, and a table must
  first be turned into a string with `serialize.encode` before it can be stored there.
- The card rules exist only in the one C++ copy; `java/` and `go/` take no part in the server: the
  three-language consistency is guaranteed by the baseline records in `data/golden/` inside each
  implementation's own tests.

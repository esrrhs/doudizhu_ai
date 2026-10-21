# 斗地主网页对战平台 (FakeLua 服务)

*英文版: [README.md](README.md)*

三套算法（`java/` `go/` `cpp/`）之外的第四件事：把引擎包成一个能玩的服务。
**牌局规则与神经网络推理都在 C++ 里，Lua 只做 HTTP、会话与流程编排。**

- 引擎：`cpp/` 的静态库 `doudizhu`（与 Java/Go 逐位对齐的那一份，无第三方依赖）
- 宿主：`host/main.cpp`，把引擎注册成 `doudizhu.*` 原生函数，跑 fakelua 的 `init/update/fini`
- 业务：`lua/`（`main.lua` 生命周期、`http_server.lua` 路由、`game.lua` 会话流程、`def.lua` 编码转换）
- 前端：`static/`（与旧 Java 版逐字相同 —— HTTP 契约没变，所以一行没改）
- 契约：[API.md](API.md)；用例：[tests/api_test.py](tests/api_test.py)

## 为什么不是直接用 `flua`

`majiang_algorithm` 的服务没有宿主：那套算法整体被移植成了 Lua。斗地主不行 ——
一次决策要给上百个候选动作各跑一遍 1990 维 × 5 层的前向，纯 Lua 慢一个数量级，
而且 `cpp/` 已经有一份和 Java/Go 逐位对齐的实现，再抄一遍 Lua 版本只会多一个会走偏的真相源。
所以这里是一层薄宿主：规则与推理留在 C++，Lua 拿到的每个数都出自同一份引擎。

## 构建

需要 [fakelua](https://github.com/esrrhs/fakelua)（提供 `libfakelua` 与头文件）。

```bash
# 1) 装 fakelua (源码里编, 依赖由 CPM 拉)
git clone https://github.com/esrrhs/fakelua && cd fakelua
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
      -DFAKELUA_BUILD_TESTS=OFF -DFAKELUA_BUILD_BENCHMARKS=OFF
cmake --build build --target flua -j
sudo cmake --install build --prefix /usr/local       # 或 ~/.local、/opt/homebrew

# 2) 装宿主 (默认在 /opt/homebrew 找 fakelua, 找不到就用 -DFAKELUA_PREFIX 指过去)
cd ../doudizhu_ai/server
cmake -S host -B host/build -DCMAKE_BUILD_TYPE=Release -DFAKELUA_PREFIX=/usr/local
cmake --build host/build -j
```

宿主自己只读三个开关（`--once` / `--jit_type=n` / `--model_dir=...`），不依赖 gflags；
权重走 `cpp/` 的 `Policy::DefaultModelDir()`，即在当前目录往上找 `data/models/ts`，
所以**要在 `server/` 目录下启动**。

## 运行

```bash
cd server
DOUDIZHU_PORT=8080 ./host/build/doudizhu_server lua
# 浏览器打开 http://127.0.0.1:8080
```

自检（不建 HTTP 服务，跑完 init 就退出，非 0 即失败）：

```bash
DOUDIZHU_SMOKE=1 ./host/build/doudizhu_server lua --once
```

自检覆盖：权重已加载、发牌 17/17/17 与底牌盖着、定地主后 20 张并先手、三家全用策略网络
跑到终局、牌局对象能显式释放、会话 DTO 的形状、提示非空、AI 一步与思路数据、
JSON 字符串键编码成对象、两套牌面写法往返一致。

接口回归：

```bash
python3 tests/api_test.py                                  # 自己拉起服务 (随机端口)
python3 tests/api_test.py --base-url http://127.0.0.1:8080 # 打已运行的服务
```

## 玩法

真人坐 0 号位。叫地主阶段每家（含两个 AI）**各以 1/3 概率随机表态**，三家都不叫就流局重发、
先叫位顺延；地主确定后翻开 3 张底牌并入地主手牌，先手归地主。出牌阶段两个 AI 由
PerfectDou 式策略网络直接取最高分着法，真人可以点【AI 提示】要一手建议。

网页端的「阵营胜率」是 softmax 后的**策略概率**，不是搜索胜率。

## 资源占用

macOS arm64、Release 构建、loopback：

| 指标 | 数值 |
|---|---|
| 常驻内存 RSS | 约 35 MB（含三份权重，单会话） |
| 请求延迟 p50 | 约 4 ms（三拍一个请求，节拍占大头） |
| 单次决策耗时 | p50 2 ms，p90 5 ms，最大 9 ms |
| 并发模型 | 单线程事件循环，最多吃满 1 核 |
| 空闲 CPU | 约 0.1%（无请求时节拍退避到 20 ms） |

fakelua 没有 GC，但帧 arena 在每次顶层 Call 入口重置，请求级临时表与字符串随调用结束回收。
会**长期驻留**的只有两处：`container.map` 里序列化后的会话表，以及宿主 registry 里的牌局对象 ——
后者必须显式 `doudizhu.free`，所以重开一局会先释放上一局，闲置一小时以上的会话由 `Sweep` 回收。

## 线上部署

<https://doudizhu.esrrhs.xyz> —— nginx 反代到 `127.0.0.1:8889`，进程由 systemd 管
（`doudizhu.service`，`User=ubuntu`、`WorkingDirectory=<repo>/server`、`Environment=DOUDIZHU_PORT=8889`、
`Restart=always`），与 `majiang.service` / `fakefish.service` 同一套模式。
代码在 `ubuntu@gq.esrrhs.xyz:/home/ubuntu/project/doudizhu_ai`。

**那台机器访问不到 GitHub**，所以发布走 rsync 而不是 `git pull`：

```bash
# 本地执行: 同步代码 (不含 .git 与各类构建目录)
cd .. && rsync -az --delete --exclude='.git/' --exclude='**/target/' \
    --exclude='server/host/build/' --exclude='cpp/build/' --exclude='go/bin/' \
    doudizhu_ai/ ubuntu@gq.esrrhs.xyz:/home/ubuntu/project/doudizhu_ai/

# 远端执行: 重新编译 + 自检 + 重启
ssh ubuntu@gq.esrrhs.xyz 'cd /home/ubuntu/project/doudizhu_ai/server && \
    cmake -S host -B host/build -DCMAKE_BUILD_TYPE=Release -DFAKELUA_PREFIX=/usr/local && \
    cmake --build host/build -j2 && \
    DOUDIZHU_SMOKE=1 ./host/build/doudizhu_server lua --once && \
    sudo systemctl restart doudizhu'

# 打线上跑一遍接口契约 (12 个用例)
python3 server/tests/api_test.py --base-url https://doudizhu.esrrhs.xyz
```

宿主机上已装 `/usr/local` 版 fakelua（`flua` + `libfakelua` + `fakeluaConfig.cmake`，
`majiang`/`fakefish` 用的就是它），所以只需要编本仓库的宿主，不用重编 boost；
换机器时要先装 fakelua，见上面的「构建」。

gq 是 2 核 2G 的共享机器，实测这个服务 RSS 约 34 MB、空闲 CPU 0.4%，重启到重新监听约 5 s
（4 个 Lua 文件的 JIT 编译）。它与 majiang(:8888)、fakefish(:8080) 并存，端口别撞。

## 已知边界

- 单线程事件循环，一次请求要跨三拍（收 → 派发 → 回包），尾延迟由节拍决定而不是算法决定。
- `lua/` 不用协程、元表与 `require`（fakelua 不支持）；文件级 `local` 是常量，可变状态一律走
  `container.map`，表要先 `serialize.encode` 成字符串才能存进去。
- 牌局规则只有 C++ 一份，`java/` 与 `go/` 不参与服务端：三语一致性由 `data/golden/` 的
  基准记录在各自测试里保证。

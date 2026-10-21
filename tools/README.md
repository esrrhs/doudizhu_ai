# 权重工具 (tools/)

这两个脚本服务于「训练产出的权重 → Java 可推理的权重 → 可验证的移植」这条链路。
它们**不参与运行时、不被 Maven 打包**，工程本身只依赖产出的 `data/models/ts` 与
`data/golden/golden24.txt.gz`。

| 文件 | 干什么 | 在哪跑 |
|---|---|---|
| `export_actor.py` | 把训练权重重建成带 forward 的 TorchScript，供 DJL 加载 | 本机或训练机，需要 Python + PyTorch |
| `pddump.cpp` | 生成逐决策基准记录（特征 + logits + 实际选择），作为 Java 移植的对照组 | 训练机，需要 PerfectDouCPP 的编译环境 |

---

## 一、重新导出权重

训练侧 `ActorImpl::save()` 用的是 libtorch `torch::serialize::OutputArchive`，产出的 `.pt`
**只有参数、没有 forward**：`torch.jit.load` 能打开，但一调用就报
`RuntimeError: Method 'forward' is not defined`。所以必须重建一次。

又因为 PyTorch 不保证新序列档被旧运行时读取，而 **DJL 0.39.0 发布的最新原生库是 libtorch 2.7.1**
（训练机跑的是 torch 2.14），导出必须分两阶段、用两个不同版本的 torch：

```bash
# 阶段 1：用与训练侧一致的 torch (2.14) 从原始档里抽出参数
python3 tools/export_actor.py extract --in data/models/latest --out /tmp/params

# 阶段 2：用与原生库一致的 torch (2.7.1) 重建并脚本化
/tmp/ts27/bin/python tools/export_actor.py script --in /tmp/params --out models/ts
```

`script` 阶段还会为每个 actor 生成 `data/models/ts/actor<N>_manifest.json`：
逐条记录 `entry` 序号 → 层名 → 形状。TorchScript 把张量数据以 **STORED（不压缩）** 形式写成
`<root>/data/<i>`，所以 Go / C++ 只要按清单读原始 float32 字节即可，不需要 libtorch、不需要 zlib、
也不需要额外再存一份 `.bin`（避免权重翻倍）。清单的正确性已在训练机上用 `torch.equal`
逐层比对 `entry i` 与 `named_parameters()[i]` 验证过。

> 坑：LSTM 四段的顺序是 PyTorch 文档口径的 **i, f, g, o**（`g` 是候选细胞值），
> 不是 cuDNN 习惯写的 i,h,f,c。按错顺序手搭前向，logit 会偏到 1~1900 的量级、argmax 错约 89 处。

`script` 阶段带 `--golden` 时会顺手回放基准记录，报告与 C++ 的偏差：

```bash
/tmp/ts27/bin/python tools/export_actor.py script --in /tmp/params --out models/ts \
    --golden /tmp/golden.txt
# 期望输出：max |logit diff| 在 1e-4 量级、argmax mismatches = 0
```

导出完成后必须跑一次对齐测试确认：

```bash
cd java && ./mvnw test -Dtest='PerfectDouFeaturesGoldenTest,ActorNetworkGoldenTest'
```

> data/models/latest` 与 `data/models/ts` 的对应关系：前者是训练机的原始产出，后者是本工程真正加载的文件。
> 换 DJL 版本时，两处都要重做一遍（原生库版本决定阶段 2 用哪个 torch）。

---

## 二、重新生成对齐基准 (pddump)

Java 侧那两个测试吃的是 C++ 导出的「真相记录」。**只有当训练侧的特征编码或网络结构变了
（`src/ddz/features.cpp`、`src/ddz/oracle.cpp`、`src/torch_nn/models.h`）才需要重新生成。**

在训练机上编译并运行（只链接 PerfectDouCPP 现成的静态库，不改动那个工程）：

```bash
TP=/usr/local/lib/python3.10/site-packages/torch       # pip 安装的同版本 libtorch
export LD_LIBRARY_PATH=$TP/lib:/root/GCC-16/lib64       # 缺后者会报 CXXABI_1.3.15 not found

g++ -std=c++20 -O2 -o pddump pddump.cpp \
    -I<PerfectDouCPP>/src -I$TP/include -I$TP/include/torch/csrc/api/include \
    -D_GLIBCXX_USE_CXX11_ABI=1 \
    <PerfectDouCPP>/build/libperfectdou.a \
    -L$TP/lib -ltorch -ltorch_cpu -lc10 -lpthread -ldl

./pddump 24 777 <PerfectDouCPP>/models/latest > golden.txt      # 24 局, 起始种子 777
gzip -c golden.txt > golden24.txt.gz                            # 约 300KB
```

把 `golden24.txt.gz` 放到 `data/golden/` 覆盖旧文件即可（文本约 13MB，gzip 后很小，
因为牌矩阵几乎全是 0/1）。

### 记录格式

按 token 逐行读取，每个数值块都由一行 `<KEY> <个数>` 表头打头，其后每行最多 20 个数：

```text
DDZGOLDEN <版本> <局数> <起始种子>
GAME <序号>
DEAL <座位> <牌面>          # 三行；座位 0 恒为地主
BOTTOM <牌面>               # 3 张底牌（地主手牌已排序，无法反推，必须单独给）
LANDLORD 0
DEC <序号> TURN <座位> HIST <已动作数>
XIMP 1676                   # 节点特征：9 个牌矩阵 1620 + 56 个标量
SEQ 5400                    # 最近 30 手牌矩阵，每 3 手拼成一个 LSTM 时间步
OPT <候选数>
OPTLINE <牌面>              # 一个候选动作
AFEAT 186                   # 该候选的动作特征
LOGIT <候选数>              # actor 给各候选的分数
CHOSEN <槽位> <牌面>
ACT <座位> <牌面>           # 本决策实际打出的牌，供阅读方按序回放
ENDDEC
```

牌面用训练侧写法：`3..9 T J Q K A 2 B`(小王) `R`(大王)，过牌是 `-`。
Java 侧的读取器在 `src/test/java/com/doudizhu/nn/GoldenReader.java`。

---

## 三、期望的对齐结果

当前基线（24 局 712 个决策）：

- 候选动作集合：与 C++ **完全相等**（按 45 位多重集比较，不依赖下标顺序；实测顺序也一致）
- `xImp` / `seq` / 每个 `actionFeat`：**逐位相等**
- logits：最大偏差 **2.7e-4**（纯跨 libtorch 版本的浮点噪声）
- argmax：**0 处不一致**
- 单次推理：约 0.8ms

任何一项劣化都说明移植与训练侧不再等价，应该先查特征，不要靠放大容差「跑绿」。

---

## 四、踩过的坑

- **拿原始档直接加载** → `Method 'forward' is not defined`；必须走本文件的导出流程。
- **导出 torch 与原生库版本不一致** → 跨版本读取不保证可用，固定在 2.7.1。
- **`CXXABI_1.3.15 not found`** → 训练机用 GCC-16 编译，运行时要 `LD_LIBRARY_PATH` 带上
  `/root/GCC-16/lib64`。
- **带着 `LD_LIBRARY_PATH` 跑 venv 里的 python** → 会加载 pip 系统版 torch 的原生库，报
  `No module named 'torch._custom_class_base'`；跑 python 前先 `unset LD_LIBRARY_PATH`。
- **大文件下载被截断**（41MB 的原生库 jar 常在中途断）→ 用 `curl -sS -C -` 续传循环拿全后
  再放进 `~/.m2/repository/...`。

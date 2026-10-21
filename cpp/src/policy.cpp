// 手写 actor 推理实现: zip 权重加载 + LSTM/MLP 前向。
#include "doudizhu/policy.h"

#include <array>
#include <bit>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <stdexcept>

#include "doudizhu/perfect_dou_moves.h"
#include "doudizhu/shared_data.h"
#include "pt_archive.h"

namespace doudizhu
{
namespace
{

constexpr int kLstmGates = 4 * kLstmHidden; // 512
constexpr int kLstmInput = kLstmIn;         // 540
constexpr int kMlpHidden = 256;
constexpr int kMlpWide = 512;

// 档内是小端 float32, 主机同为小端时直接按位取
std::vector<float> LoadFloats(const std::uint8_t *p, std::size_t bytes)
{
    std::vector<float> out(bytes / 4);
    if constexpr (std::endian::native == std::endian::little)
    {
        std::memcpy(out.data(), p, out.size() * sizeof(float));
    }
    else
    {
        for (std::size_t i = 0; i < out.size(); ++i)
        {
            const std::uint32_t u = static_cast<std::uint32_t>(p[i * 4]) |
                                    (static_cast<std::uint32_t>(p[i * 4 + 1]) << 8) |
                                    (static_cast<std::uint32_t>(p[i * 4 + 2]) << 16) |
                                    (static_cast<std::uint32_t>(p[i * 4 + 3]) << 24);
            std::memcpy(&out[i], &u, sizeof(float));
        }
    }
    return out;
}

float Sigmoid(float x)
{
    return 1.0f / (1.0f + std::exp(-x));
}

// y = relu(W x + b) (relu 为 false 时不加 relu), W 为 [out][in] 行主序。
// 累加顺序与 Go 侧 weights.go 的 dot(weight, x) + bias 保持一致: 先求点积再加偏置,
// 这样两份手写 float32 前向逐位相同, 与 libtorch 的偏差也同为 ~5e-4 量级。
void Linear(const float *w, const float *b, int out, int in, const float *x, float *y, bool relu)
{
    for (int o = 0; o < out; ++o)
    {
        const float *row = w + static_cast<std::size_t>(o) * static_cast<std::size_t>(in);
        float acc = 0.0f;
        for (int i = 0; i < in; ++i)
        {
            acc += row[i] * x[i];
        }
        acc += b[o];
        y[o] = relu && acc < 0.0f ? 0.0f : acc;
    }
}

} // namespace

struct Policy::Actor
{
    // 与 manifest 的 14 个条目一一对应 (行主序 float32, 与档内字节序一致)
    std::vector<float> wIh; // [512][540]
    std::vector<float> wHh; // [512][128]
    std::vector<float> bIh; // [512]
    std::vector<float> bHh; // [512]
    std::vector<float> l1w; // [256][1990]
    std::vector<float> l1b; // [256]
    std::vector<float> l2w, l2b, l3w, l3b, l4w, l4b, headw, headb;
    bool ready = false;

    // 按 manifest 层名取权重容器; 未知层名返回 nullptr
    std::vector<float> *Field(const std::string &name);

    // 1 层 LSTM, batch_first, 门序按 PyTorch: i(输入门) f(遗忘门) g(候选,tanh) o(输出门);
    // 只取最后一个时间步的 h (128)
    void LstmLastHidden(const float *seq, float *outH) const;
};

std::vector<float> *Policy::Actor::Field(const std::string &name)
{
    if (name == "lstm.weight_ih_l0")
    {
        return &wIh;
    }
    if (name == "lstm.weight_hh_l0")
    {
        return &wHh;
    }
    if (name == "lstm.bias_ih_l0")
    {
        return &bIh;
    }
    if (name == "lstm.bias_hh_l0")
    {
        return &bHh;
    }
    if (name == "l1.weight")
    {
        return &l1w;
    }
    if (name == "l1.bias")
    {
        return &l1b;
    }
    if (name == "l2.weight")
    {
        return &l2w;
    }
    if (name == "l2.bias")
    {
        return &l2b;
    }
    if (name == "l3.weight")
    {
        return &l3w;
    }
    if (name == "l3.bias")
    {
        return &l3b;
    }
    if (name == "l4.weight")
    {
        return &l4w;
    }
    if (name == "l4.bias")
    {
        return &l4b;
    }
    if (name == "head.weight")
    {
        return &headw;
    }
    if (name == "head.bias")
    {
        return &headb;
    }
    return nullptr;
}

void Policy::Actor::LstmLastHidden(const float *seq, float *outH) const
{
    std::array<float, kLstmGates> gates{};
    std::array<float, kLstmHidden> cell{};
    std::array<float, kLstmHidden> hidden{};

    for (int t = 0; t < kLstmSteps; ++t)
    {
        const float *x = seq + static_cast<std::size_t>(t) * kLstmInput;
        // 历史序列前部补零, 整步全零时下面可以跳过一次 540 维点积
        bool xNonZero = false;
        for (int i = 0; i < kLstmInput; ++i)
        {
            if (x[i] != 0.0f)
            {
                xNonZero = true;
                break;
            }
        }
        for (int g = 0; g < kLstmGates; ++g)
        {
            const float *rowI = wIh.data() + static_cast<std::size_t>(g) * kLstmInput;
            const float *rowH = wHh.data() + static_cast<std::size_t>(g) * kLstmHidden;
            // 累加顺序同 Go weights.go: (dot(wIh,x) + bIh) + (dot(wHh,h)) + bHh
            float accI = 0.0f;
            // 输入全零 (开局常见) 时点积恒为 +0.0, 省掉这 540 次乘法不改变任何一位
            if (xNonZero)
            {
                for (int i = 0; i < kLstmInput; ++i)
                {
                    accI += rowI[i] * x[i];
                }
            }
            float acc = accI + bIh[g];
            float accH = 0.0f;
            for (int h = 0; h < kLstmHidden; ++h)
            {
                accH += rowH[h] * hidden[static_cast<std::size_t>(h)];
            }
            acc = acc + accH + bHh[g];
            gates[static_cast<std::size_t>(g)] = acc;
        }
        for (int k = 0; k < kLstmHidden; ++k)
        {
            // weight_ih / weight_hh 的 4 个 128 行块按 PyTorch 约定依次为 i, f, g, o;
            // i/f/o 走 sigmoid, g 是 tanh 的候选值。
            const float iGate = Sigmoid(gates[static_cast<std::size_t>(k)]);
            const float fGate = Sigmoid(gates[static_cast<std::size_t>(kLstmHidden + k)]);
            const float gGate = std::tanh(gates[static_cast<std::size_t>(2 * kLstmHidden + k)]);
            const float oGate = Sigmoid(gates[static_cast<std::size_t>(3 * kLstmHidden + k)]);
            cell[static_cast<std::size_t>(k)] =
                fGate * cell[static_cast<std::size_t>(k)] + iGate * gGate;
            hidden[static_cast<std::size_t>(k)] =
                oGate * std::tanh(cell[static_cast<std::size_t>(k)]);
        }
    }
    std::memcpy(outH, hidden.data(), sizeof(float) * kLstmHidden);
}

namespace
{

// manifest 层名 -> 期望形状。形状不符说明权重与本工程的网络结构不一致。
struct LayerSpec
{
    const char *name;
    int d0;
    int d1; // -1 表示一维
};

constexpr LayerSpec kLayers[] = {
    {"lstm.weight_ih_l0", kLstmGates, kLstmInput},
    {"lstm.weight_hh_l0", kLstmGates, kLstmHidden},
    {"lstm.bias_ih_l0", kLstmGates, -1},
    {"lstm.bias_hh_l0", kLstmGates, -1},
    {"l1.weight", kMlpHidden, kJointSize},
    {"l1.bias", kMlpHidden, -1},
    {"l2.weight", kMlpHidden, kMlpHidden},
    {"l2.bias", kMlpHidden, -1},
    {"l3.weight", kMlpHidden, kMlpHidden},
    {"l3.bias", kMlpHidden, -1},
    {"l4.weight", kMlpWide, kMlpHidden},
    {"l4.bias", kMlpWide, -1},
    {"head.weight", 1, kMlpWide},
    {"head.bias", 1, -1},
};
constexpr int kLayerCount = static_cast<int>(sizeof(kLayers) / sizeof(kLayers[0]));

const LayerSpec *FindLayer(const std::string &name)
{
    for (const LayerSpec &l : kLayers)
    {
        if (name == l.name)
        {
            return &l;
        }
    }
    return nullptr;
}

bool ShapeMatches(const std::vector<int> &shape, const LayerSpec &l)
{
    if (l.d1 < 0)
    {
        return shape.size() == 1 && shape[0] == l.d0;
    }
    return shape.size() == 2 && shape[0] == l.d0 && shape[1] == l.d1;
}

} // namespace

const char *Policy::DefaultModelRel()
{
    return "data/models/ts";
}

std::string Policy::DefaultModelDir()
{
    return ResolveSharedData(DefaultModelRel());
}

Policy::Policy() = default;
Policy::~Policy() = default;

const Policy::Actor &Policy::ActorAt(int seat) const
{
    if (seat < 0 || seat >= kPolicySeats)
    {
        throw std::out_of_range("actor 编号越界");
    }
    const Actor *a = actors_[static_cast<std::size_t>(seat)].get();
    if (a == nullptr || !a->ready)
    {
        throw std::runtime_error("actor " + std::to_string(seat) + " 未加载");
    }
    return *a;
}

bool Policy::Load(const std::string &dir)
{
    error_.clear();
    for (int seat = 0; seat < kPolicySeats; ++seat)
    {
        const std::string ptPath = dir + "/actor" + std::to_string(seat) + "_ts.pt";
        const std::string jsonPath = dir + "/actor" + std::to_string(seat) + "_manifest.json";

        std::string manifest;
        {
            std::FILE *fp = std::fopen(jsonPath.c_str(), "rb");
            if (fp == nullptr)
            {
                error_ = "缺少权重清单 " + jsonPath +
                         " (需先用 PerfectDouCPP 的 tools/export_actor.py 导出 TorchScript)";
                return false;
            }
            std::fseek(fp, 0, SEEK_END);
            const long sz = std::ftell(fp);
            std::fseek(fp, 0, SEEK_SET);
            const std::size_t want = static_cast<std::size_t>(sz > 0 ? sz : 0);
            manifest.resize(want);
            if (want > 0 && std::fread(manifest.data(), 1, want, fp) != want)
            {
                std::fclose(fp);
                error_ = "读取权重清单不完整 " + jsonPath;
                return false;
            }
            std::fclose(fp);
        }

        std::string root;
        std::vector<detail::ParamSpec> specs;
        if (!detail::ParseManifest(manifest, &root, &specs, &error_))
        {
            error_ = jsonPath + ": " + error_;
            return false;
        }

        detail::ZipReader zip;
        std::string zipErr;
        if (!zip.Open(ptPath, &zipErr))
        {
            error_ = "加载权重失败: " + zipErr;
            return false;
        }

        // 层数与层名都必须与本工程的内建结构一一对应 (重复层名同样视为清单损坏)
        if (specs.size() != static_cast<std::size_t>(kLayerCount))
        {
            error_ = jsonPath + ": 参数个数 " + std::to_string(specs.size()) + ", 期望 " +
                     std::to_string(kLayerCount) + " (网络结构与本工程不一致)";
            return false;
        }
        for (std::size_t i = 0; i < specs.size(); ++i)
        {
            for (std::size_t j = i + 1; j < specs.size(); ++j)
            {
                if (specs[i].name == specs[j].name)
                {
                    error_ = jsonPath + ": 清单层名重复 " + specs[i].name;
                    return false;
                }
            }
        }

        auto actor = std::make_unique<Actor>();
        std::array<bool, static_cast<std::size_t>(kLayerCount)> got{};
        got.fill(false);
        for (const detail::ParamSpec &spec : specs)
        {
            const LayerSpec *layer = FindLayer(spec.name);
            if (layer == nullptr)
            {
                error_ = "权重清单出现未知层名 " + spec.name + " (网络结构与本工程不一致)";
                return false;
            }
            if (!ShapeMatches(spec.shape, *layer))
            {
                error_ = "层 " + spec.name + " 的形状与本工程期望的网络结构不一致";
                return false;
            }
            const std::string entryName = root + "/data/" + std::to_string(spec.entry);
            const detail::ZipEntry *entry = zip.Find(entryName);
            if (entry == nullptr)
            {
                error_ = "权重档缺少条目 " + entryName + " (" + ptPath + ")";
                return false;
            }
            std::size_t numel = 1;
            for (int dim : spec.shape)
            {
                numel *= static_cast<std::size_t>(dim);
            }
            std::uint64_t size = 0;
            std::string zipErr;
            const std::uint8_t *raw = zip.StoredData(*entry, &size, &zipErr);
            if (raw == nullptr)
            {
                error_ = "权重档 " + ptPath + ": " + zipErr;
                return false;
            }
            if (size != numel * sizeof(float))
            {
                error_ = "条目 " + entryName + " (" + spec.name + ") 长度 " + std::to_string(size) +
                         " 与形状不符, 期望 " + std::to_string(numel * sizeof(float));
                return false;
            }
            std::vector<float> *dst = actor->Field(spec.name);
            if (dst == nullptr)
            {
                error_ = "权重清单出现未知层名 " + spec.name;
                return false;
            }
            *dst = LoadFloats(raw, static_cast<std::size_t>(size));
            got[static_cast<std::size_t>(layer - kLayers)] = true;
        }
        for (int i = 0; i < kLayerCount; ++i)
        {
            if (!got[static_cast<std::size_t>(i)])
            {
                error_ = std::string("权重清单缺少层 ") + kLayers[static_cast<std::size_t>(i)].name;
                return false;
            }
        }
        actor->ready = true;
        actors_[static_cast<std::size_t>(seat)] = std::move(actor);
    }
    return true;
}

std::vector<float> Policy::Score(int seat, const float *xImp, const float *seq,
                                 const float *actionFeat, int options) const
{
    if (options <= 0)
    {
        return {};
    }
    const Actor &a = ActorAt(seat);

    std::vector<float> h(kLstmHidden);
    a.LstmLastHidden(seq, h.data());

    // node = concat(xImp, h_last)
    std::vector<float> node(static_cast<std::size_t>(kNodeDim));
    std::memcpy(node.data(), xImp, sizeof(float) * kNodeSize);
    std::memcpy(node.data() + kNodeSize, h.data(), sizeof(float) * kLstmHidden);

    // l1 的 node 部分对同一状态的所有候选相同, 先算一次; 动作部分只累加非零维
    std::vector<float> pre1(a.l1b.begin(), a.l1b.end());
    for (int o = 0; o < kMlpHidden; ++o)
    {
        const float *row = a.l1w.data() + static_cast<std::size_t>(o) * kJointSize;
        float acc = pre1[static_cast<std::size_t>(o)];
        for (int j = 0; j < kNodeDim; ++j)
        {
            acc += row[j] * node[static_cast<std::size_t>(j)];
        }
        pre1[static_cast<std::size_t>(o)] = acc;
    }

    std::vector<int> nzIdx;
    nzIdx.reserve(kActionSize);
    std::vector<float> buf1(kMlpHidden);
    std::vector<float> buf2(kMlpHidden);
    std::vector<float> buf3(kMlpWide);
    std::vector<float> logits(static_cast<std::size_t>(options));

    for (int i = 0; i < options; ++i)
    {
        const float *feat = actionFeat + static_cast<std::size_t>(i) * kActionSize;
        nzIdx.clear();
        for (int j = 0; j < kActionSize; ++j)
        {
            if (feat[j] != 0.0f)
            {
                nzIdx.push_back(j);
            }
        }
        for (int o = 0; o < kMlpHidden; ++o)
        {
            const float *row = a.l1w.data() + static_cast<std::size_t>(o) * kJointSize + kNodeDim;
            float acc = pre1[static_cast<std::size_t>(o)];
            for (int j : nzIdx)
            {
                acc += row[j] * feat[j];
            }
            buf1[static_cast<std::size_t>(o)] = acc < 0.0f ? 0.0f : acc;
        }
        Linear(a.l2w.data(), a.l2b.data(), kMlpHidden, kMlpHidden, buf1.data(), buf2.data(), true);
        Linear(a.l3w.data(), a.l3b.data(), kMlpHidden, kMlpHidden, buf2.data(), buf1.data(), true);
        Linear(a.l4w.data(), a.l4b.data(), kMlpWide, kMlpHidden, buf1.data(), buf3.data(), true);
        float acc = a.headb[0];
        for (int j = 0; j < kMlpWide; ++j)
        {
            acc += a.headw[j] * buf3[static_cast<std::size_t>(j)];
        }
        logits[static_cast<std::size_t>(i)] = acc;
    }
    return logits;
}

Policy::Decision Policy::Decide(const PublicView &view) const
{
    Decision d;
    const int myId = view.viewingPlayerId;
    if (view.myHand.Empty())
    {
        d.move = MakePass(myId);
        return d;
    }

    const View observed = Observe(view);
    std::vector<float> xImp(kNodeSize);
    std::vector<float> seq(kSeqSize);
    Encode(observed, xImp.data(), seq.data());

    CardSet toBeat;
    if (view.lastMove.has_value())
    {
        toBeat = CountsOf(*view.lastMove);
    }
    d.options = Legal(observed.MyHandCounts(), toBeat);
    const int n = static_cast<int>(d.options.size());
    if (n == 0)
    {
        // 完整枚举下自由出牌至少有一张单牌, 应牌时末项恒为过牌, 因此正常牌局不会到这里;
        // 手牌张数与牌面自相矛盾等脏输入会返回空集, 退化为过牌以免越界读。
        d.move = MakePass(myId);
        return d;
    }

    d.moves.reserve(static_cast<std::size_t>(n));
    std::vector<float> actionFeat(static_cast<std::size_t>(n) * kActionSize);
    for (int i = 0; i < n; ++i)
    {
        Move m = ToMove(d.options[static_cast<std::size_t>(i)], myId);
        ActionFeature(observed, m, actionFeat.data(), i * kActionSize);
        d.moves.push_back(m);
    }

    d.logits = Score(observed.GetActorIndex(), xImp.data(), seq.data(), actionFeat.data(), n);

    int best = 0;
    for (int i = 1; i < n; ++i)
    {
        if (d.logits[static_cast<std::size_t>(i)] > d.logits[static_cast<std::size_t>(best)])
        {
            best = i;
        }
    }
    d.slot = best;
    d.move = d.moves[static_cast<std::size_t>(best)];

    // 策略概率表 (Java NeuralAiPlayer.probabilityTable)
    double max = d.logits.empty() ? 0.0 : static_cast<double>(d.logits[0]);
    for (float s : d.logits)
    {
        max = max < static_cast<double>(s) ? static_cast<double>(s) : max;
    }
    d.probs.resize(d.logits.size());
    double sum = 0.0;
    for (std::size_t i = 0; i < d.logits.size(); ++i)
    {
        const double e = std::exp(static_cast<double>(d.logits[i]) - max);
        d.probs[i] = static_cast<float>(e);
        sum += e;
    }
    for (float &p : d.probs)
    {
        p = sum > 0.0 ? static_cast<float>(static_cast<double>(p) / sum) : 0.0f;
    }
    return d;
}

} // namespace doudizhu

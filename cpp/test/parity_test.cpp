// 回放 data/golden/golden24.txt.gz: 由 PerfectDouCPP tools/pddump.cpp 导出的逐决策基准记录,
// C++ 移植必须逐字段复现 (与 Java PerfectDouFeaturesGoldenTest / ActorNetworkGoldenTest、
// Go parity_test.go 同一契约):
//   1. 候选动作集合与记录完全相等 (按 45 位多重集指纹双向比较);
//   2. xImp / seq / 每个 actionFeat 与记录逐位相等;
//   3. 手写前向的 logits 与记录偏差 < 1e-3, 且 argmax 恒等于记录的 CHOSEN 槽位。
#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_set>
#include <vector>

#include "golden_reader.h"

#include "doudizhu/game_state.h"
#include "doudizhu/model.h"
#include "doudizhu/perfect_dou_features.h"
#include "doudizhu/perfect_dou_moves.h"
#include "doudizhu/policy.h"

namespace
{

using namespace doudizhu;

struct Stats
{
    int decisions = 0;
    int games = 0;
    int featureMismatch = 0;  // xImp/seq/afeat 逐位不一致的块数
    int setMismatch = 0;      // 候选集合不一致的决策数
    int argmaxMismatch = 0;   // 我们的 argmax 与 CHOSEN 槽位不一致的决策数
    int logitOverTol = 0;     // logits 偏差 >= 1e-3 的决策数
    int goldenArgmaxDiff = 0; // 记录里 CHOSEN 槽位 != 记录 logits 的 argmax
    int decideMismatch = 0;   // Policy::Decide 端到端选出的牌面与记录不符
    int formatError = 0;      // 记录格式与约定不符
    double worst = 0.0;       // 最大 |logit 偏差|
    int worstDecision = -1;
    long long optionCount = 0;
    std::vector<std::string> problems;
};

// 基准记录 (data/golden/golden24.txt.gz) 由 24 局自对弈导出, 共 712 个决策。
// 这里断言实际读到的数量与之相等, 以免漏读或提前退出被当成通过。
constexpr int kExpectedDecisions = 712;
constexpr int kExpectedGames = 24;

void Problem(Stats &st, const std::string &msg)
{
    if (st.problems.size() < 20)
    {
        st.problems.push_back(msg);
    }
}

// 逐位比较两个 float 数组 (位模式相同才算相等)
bool BitEqual(const float *a, const float *b, int n, int *firstDiff)
{
    for (int i = 0; i < n; ++i)
    {
        const std::uint32_t ua = std::bit_cast<std::uint32_t>(a[i]);
        const std::uint32_t ub = std::bit_cast<std::uint32_t>(b[i]);
        if (ua != ub)
        {
            *firstDiff = i;
            return false;
        }
    }
    return true;
}

void CompareBlock(Stats &st, const char *name, const std::vector<float> &mine,
                  const std::vector<float> &golden)
{
    if (mine.size() != golden.size())
    {
        ++st.featureMismatch;
        Problem(st, std::string(name) + " 长度不同: 我方 " + std::to_string(mine.size()) +
                        " 记录 " + std::to_string(golden.size()));
        return;
    }
    int first = -1;
    if (BitEqual(mine.data(), golden.data(), static_cast<int>(mine.size()), &first))
    {
        return;
    }
    ++st.featureMismatch;
    Problem(st, std::string(name) + " 第" + std::to_string(st.decisions) + "个决策 [" +
                    std::to_string(first) + "]: 我方 " + std::to_string(mine[static_cast<std::size_t>(first)]) +
                    " 记录 " + std::to_string(golden[static_cast<std::size_t>(first)]));
}

std::unordered_set<long long> KeysOf(const std::vector<CardSet> &options)
{
    std::unordered_set<long long> out;
    out.reserve(options.size() * 2);
    for (const CardSet &c : options)
    {
        out.insert(Key(c));
    }
    return out;
}

bool ReadKey(dtest::GoldenReader &reader, const char *key, Stats &st)
{
    const std::string got = reader.NextToken();
    if (!dtest::ExpectKey(got, key, st.decisions, &st.problems))
    {
        ++st.formatError;
        return false;
    }
    return true;
}

int ReadInt(dtest::GoldenReader &reader)
{
    return std::atoi(reader.NextToken().c_str());
}

int Argmax(const std::vector<float> &v)
{
    int best = 0;
    for (std::size_t i = 1; i < v.size(); ++i)
    {
        if (v[i] > v[static_cast<std::size_t>(best)])
        {
            best = static_cast<int>(i);
        }
    }
    return best;
}

// 回放一局; 返回 false 表示记录格式已经读不下去, 应中止
bool ReplayGame(dtest::GoldenReader &reader, Stats &st, const Policy &policy)
{
    reader.NextToken(); // 局号
    ++st.games;

    std::array<CardSet, 3> hands{};
    CardSet bottom;
    for (int s = 0; s < 3; ++s)
    {
        if (!ReadKey(reader, "DEAL", st))
        {
            return false;
        }
        const int seat = ReadInt(reader);
        if (seat != s)
        {
            ++st.formatError;
            Problem(st, "DEAL 座位顺序不符: 期望 " + std::to_string(s) + " 实际 " + std::to_string(seat));
            return false;
        }
        hands[static_cast<std::size_t>(s)] = dtest::ParseCards(reader.NextToken());
    }
    if (!ReadKey(reader, "BOTTOM", st))
    {
        return false;
    }
    bottom = dtest::ParseCards(reader.NextToken());
    if (!ReadKey(reader, "LANDLORD", st))
    {
        return false;
    }
    const int landlordId = ReadInt(reader);

    GameState state(hands, landlordId, bottom);

    std::vector<float> xImp(static_cast<std::size_t>(kNodeSize));
    std::vector<float> seq(static_cast<std::size_t>(kSeqSize));

    for (;;)
    {
        const std::string marker = reader.NextToken();
        if (marker == "ENDGAME")
        {
            return true;
        }
        if (marker != "DEC")
        {
            ++st.formatError;
            Problem(st, "第" + std::to_string(st.decisions) + "个决策: 期望 DEC/ENDGAME, 实际读到 " + marker);
            return false;
        }
        reader.NextToken(); // 决策序号
        if (!ReadKey(reader, "TURN", st))
        {
            return false;
        }
        const int turn = ReadInt(reader);
        if (!ReadKey(reader, "HIST", st))
        {
            return false;
        }
        reader.NextToken(); // 该局已发生的动作数

        if (state.ActivePlayerIndex() != turn)
        {
            ++st.setMismatch;
            Problem(st, "第" + std::to_string(st.decisions) + "个决策行动者不符: 我方 " +
                            std::to_string(state.ActivePlayerIndex()) + " 记录 " + std::to_string(turn));
            return false;
        }

        const PublicView view = state.GetPublicView(turn);
        const View observed = Observe(view);
        Encode(observed, xImp.data(), seq.data());

        if (!ReadKey(reader, "XIMP", st))
        {
            return false;
        }
        const int xn = ReadInt(reader);
        if (xn != kNodeSize)
        {
            ++st.formatError;
            Problem(st, "XIMP 数量 " + std::to_string(xn) + " != 1676");
            return false;
        }
        CompareBlock(st, "xImp", xImp, reader.ReadFloats(xn));

        if (!ReadKey(reader, "SEQ", st))
        {
            return false;
        }
        const int sn = ReadInt(reader);
        if (sn != kSeqSize)
        {
            ++st.formatError;
            Problem(st, "SEQ 数量 " + std::to_string(sn) + " != 5400");
            return false;
        }
        CompareBlock(st, "seq", seq, reader.ReadFloats(sn));

        if (!ReadKey(reader, "OPT", st))
        {
            return false;
        }
        const int options = ReadInt(reader);
        std::vector<CardSet> golden;
        golden.reserve(static_cast<std::size_t>(options));
        std::vector<float> goldenFeat(static_cast<std::size_t>(options) * kActionSize);
        for (int i = 0; i < options; ++i)
        {
            if (!ReadKey(reader, "OPTLINE", st))
            {
                return false;
            }
            golden.push_back(dtest::ParseCards(reader.NextToken()));
            if (!ReadKey(reader, "AFEAT", st))
            {
                return false;
            }
            const int an = ReadInt(reader);
            if (an != kActionSize)
            {
                ++st.formatError;
                Problem(st, "AFEAT 数量 " + std::to_string(an) + " != 186");
                return false;
            }
            std::vector<float> block = reader.ReadFloats(an);
            std::memcpy(goldenFeat.data() + static_cast<std::size_t>(i) * kActionSize, block.data(),
                        sizeof(float) * static_cast<std::size_t>(an));
        }

        // 1) 候选动作集合双向相等
        CardSet toBeat;
        if (state.LastMove().has_value())
        {
            toBeat = CountsOf(*state.LastMove());
        }
        const std::vector<CardSet> mine = Legal(observed.MyHandCounts(), toBeat);
        const std::unordered_set<long long> mineKeys = KeysOf(mine);
        const std::unordered_set<long long> goldenKeys = KeysOf(golden);
        std::string onlyGolden;
        std::string onlyMine;
        for (const CardSet &c : golden)
        {
            if (mineKeys.find(Key(c)) == mineKeys.end())
            {
                onlyGolden += (onlyGolden.empty() ? "" : " ") + DebugCards(c);
            }
        }
        for (const CardSet &c : mine)
        {
            if (goldenKeys.find(Key(c)) == goldenKeys.end())
            {
                onlyMine += (onlyMine.empty() ? "" : " ") + DebugCards(c);
            }
        }
        if (!onlyGolden.empty() || !onlyMine.empty() || mine.size() != golden.size())
        {
            ++st.setMismatch;
            Problem(st, "第" + std::to_string(st.decisions) + "个决策候选集合不同 记录=" +
                            std::to_string(golden.size()) + " 我方=" + std::to_string(mine.size()) +
                            " 仅记录有[" + onlyGolden + "] 仅我方有[" + onlyMine + "]");
        }
        st.optionCount += golden.size();

        // 2) 逐个候选的 actionFeat 逐位相等 (按记录顺序取记录给出的牌面)
        std::vector<float> feat(static_cast<std::size_t>(kActionSize));
        for (int i = 0; i < options; ++i)
        {
            const Move m = ToMove(golden[static_cast<std::size_t>(i)], turn);
            std::fill(feat.begin(), feat.end(), 0.0f);
            ActionFeature(observed, m, feat.data(), 0);
            std::vector<float> expect(goldenFeat.begin() + static_cast<std::size_t>(i) * kActionSize,
                                      goldenFeat.begin() + static_cast<std::size_t>(i + 1) * kActionSize);
            CompareBlock(st, ("actionFeat[" + DebugCards(golden[static_cast<std::size_t>(i)]) + "]").c_str(),
                         feat, expect);
        }

        if (!ReadKey(reader, "LOGIT", st))
        {
            return false;
        }
        const int logitCount = ReadInt(reader);
        if (logitCount != options)
        {
            ++st.formatError;
            Problem(st, "LOGIT 数量 " + std::to_string(logitCount) + " != OPT " + std::to_string(options));
            return false;
        }
        const std::vector<float> goldenLogits = reader.ReadFloats(logitCount);

        // 3) 手写前向: logits 偏差 < 1e-3, argmax 与 CHOSEN 槽位一致
        const std::vector<float> ours =
            policy.Score(observed.GetActorIndex(), xImp.data(), seq.data(), goldenFeat.data(), options);
        for (int i = 0; i < options; ++i)
        {
            const double d = std::fabs(static_cast<double>(ours[static_cast<std::size_t>(i)]) -
                                       static_cast<double>(goldenLogits[static_cast<std::size_t>(i)]));
            if (d > st.worst)
            {
                st.worst = d;
                st.worstDecision = st.decisions;
            }
            if (!(d < 1e-3))
            {
                ++st.logitOverTol;
                Problem(st, "第" + std::to_string(st.decisions) + "个决策候选 " + std::to_string(i) +
                                " logits 偏差 " + std::to_string(d) + " >= 1e-3");
                break;
            }
        }

        if (!ReadKey(reader, "CHOSEN", st))
        {
            return false;
        }
        const int chosenSlot = ReadInt(reader);
        const std::string chosenCards = reader.NextToken();
        if (chosenSlot < 0 || chosenSlot >= logitCount)
        {
            ++st.formatError;
            Problem(st, "CHOSEN 槽位越界 " + std::to_string(chosenSlot));
            return false;
        }
        if (Argmax(ours) != chosenSlot)
        {
            ++st.argmaxMismatch;
            Problem(st, "第" + std::to_string(st.decisions) + "个决策 argmax 不一致: 我方 " +
                            std::to_string(Argmax(ours)) + " 记录 CHOSEN " + std::to_string(chosenSlot) +
                            " (" + chosenCards + ")");
        }
        if (Argmax(goldenLogits) != chosenSlot)
        {
            ++st.goldenArgmaxDiff;
        }

        // 端到端: Policy::Decide 走本工程自己的候选枚举顺序, 选出的牌面应与记录一致
        const Policy::Decision decided = policy.Decide(view);
        if (DebugCards(CountsOf(decided.move)) != chosenCards)
        {
            ++st.decideMismatch;
            Problem(st, "第" + std::to_string(st.decisions) + "个决策 Decide 选出 " +
                            DebugCards(CountsOf(decided.move)) + " 记录 CHOSEN " + chosenCards);
        }

        if (!ReadKey(reader, "ACT", st))
        {
            return false;
        }
        const int actSeat = ReadInt(reader);
        const CardSet played = dtest::ParseCards(reader.NextToken());
        if (actSeat != turn)
        {
            ++st.formatError;
            Problem(st, "ACT 座位 " + std::to_string(actSeat) + " != TURN " + std::to_string(turn));
            return false;
        }
        if (DebugCards(played) != chosenCards)
        {
            ++st.formatError;
            Problem(st, "ACT 与 CHOSEN 牌面不同: " + DebugCards(played) + " vs " + chosenCards);
            return false;
        }

        state.ApplyMove(ToMove(played, turn));

        if (!ReadKey(reader, "ENDDEC", st))
        {
            return false;
        }
        ++st.decisions;
    }
}

} // namespace

int main()
{
    std::string err;
    dtest::GoldenReader reader;
    if (!reader.Open("data/golden/golden24.txt.gz", &err))
    {
        std::fprintf(stderr, "读取基准记录失败: %s\n", err.c_str());
        return 1;
    }
    std::printf("基准记录: %s\n", reader.Path().c_str());

    Policy policy;
    const std::string modelDir = Policy::DefaultModelDir();
    if (!policy.Load(modelDir))
    {
        std::fprintf(stderr, "加载权重失败: %s\n", policy.Error().c_str());
        return 1;
    }
    std::printf("权重目录: %s\n", modelDir.c_str());

    Stats st;
    bool broken = false;
    try
    {
        while (reader.HasNext())
        {
            const std::string key = reader.NextToken();
            if (key == "DDZGOLDEN")
            {
                // DDZGOLDEN <版本> <局数> <起始种子>
                const int version = ReadInt(reader);
                const int declaredGames = ReadInt(reader);
                const int seed = ReadInt(reader);
                reader.SkipLine();
                if (version != 1 || declaredGames != kExpectedGames)
                {
                    ++st.formatError;
                    Problem(st, "记录头 DDZGOLDEN 版本/局数 = " + std::to_string(version) + " " +
                                    std::to_string(declaredGames) + ", 期望 1 " +
                                    std::to_string(kExpectedGames) + " (种子 " + std::to_string(seed) + ")");
                }
                continue;
            }
            if (key != "GAME")
            {
                ++st.formatError;
                Problem(st, "意外的字段 " + key);
                broken = true;
                break;
            }
            if (!ReplayGame(reader, st, policy))
            {
                broken = true;
                break;
            }
        }
    }
    catch (const std::exception &e)
    {
        ++st.formatError;
        Problem(st, std::string("回放中断: ") + e.what());
        broken = true;
    }
    reader.Close();

    std::printf("回放 %d 局 %d 个决策 (候选总数 %lld): 最大 |logit 偏差| %.3e (第%d个决策), "
                "argmax 不一致 %d 个, logits 超容差 %d 个, Decide 端到端不符 %d 个, "
                "特征逐位差异 %d 处, 候选集合差异 %d 处, 记录格式错误 %d 处\n",
                st.games, st.decisions, st.optionCount, st.worst, st.worstDecision, st.argmaxMismatch,
                st.logitOverTol, st.decideMismatch, st.featureMismatch, st.setMismatch, st.formatError);
    if (st.goldenArgmaxDiff > 0)
    {
        std::printf("  (记录内部: CHOSEN 槽位与记录 logits 的 argmax 不符 %d 处)\n", st.goldenArgmaxDiff);
    }
    for (const std::string &p : st.problems)
    {
        std::printf("  [差异] %s\n", p.c_str());
    }

    bool ok = !broken;
    ok = ok && st.games == kExpectedGames;
    ok = ok && st.decisions == kExpectedDecisions;
    ok = ok && st.featureMismatch == 0;
    ok = ok && st.setMismatch == 0;
    ok = ok && st.formatError == 0;
    ok = ok && st.logitOverTol == 0;
    ok = ok && st.worst < 1e-3;
    ok = ok && st.argmaxMismatch == 0;
    ok = ok && st.decideMismatch == 0;
    ok = ok && st.goldenArgmaxDiff == 0;
    std::printf("%s\n", ok ? "PARITY OK" : "PARITY FAILED");
    return ok ? 0 : 1;
}

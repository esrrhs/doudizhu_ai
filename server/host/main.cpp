// doudizhu_server -- 斗地主网页端的 fakelua 宿主。
//
// 分工只有一条界线: 规则与推理留在已经过三语逐位对齐验证的 C++ 引擎里, Lua 负责
// HTTP 路由、会话隔离、叫分流程编排、日志与前端数据组装。因此这里注册的 doudizhu.*
// 全是「一手牌进、字符串出」的薄函数, 不做任何展示层决策。
//
// 字符串契约见 server/API.md 的「原生函数」一节:
//   牌面   用引擎的训练侧写法, 3456789TJQKA2 + B(小王) R(大王), 过牌是 "-"
//   一手   "type:mainRank:cards", type 是引擎 CardType 序号 (0..14), mainRank 3..17
//   列表   用 "|" 分隔, Lua 侧 string.split 即可
//
// 生命周期沿用 flua 的约定 (fakelua/cmd/main.cpp): init 一次, update 每帧一次, fini
// 一次。每次调用都是独立的顶层 Call, 帧 arena 在 Call 入口重置 —— 所以绝不能在 Lua
// 里写 while true 把请求包进单个 Call, 否则每请求的临时表与字符串永不回收。
//
// 会话表由本宿主持有 (Lua 只拿到整数 id), fakelua 没有 GC, 宿主也不会自动回收:
// Lua 必须在会话结束或闲置超时后调用 doudizhu.free(id)。
#include <algorithm>
#include <array>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <memory>
#include <random>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "fakelua.h"

#include "doudizhu/api.h"

using namespace fakelua;

namespace {

// 命令行只有三个开关, 手写的: 宿主依赖因此只剩 fakelua + 引擎, 不需要 gflags。
struct Options
{
    bool once = false;
    int jitType = 0;
    std::string modelDir;
    std::string script;

    bool Parse(int argc, char **argv, std::string *error)
    {
        for (int i = 1; i < argc; ++i) {
            const std::string a = argv[i];
            if (a == "--once") {
                once = true;
            } else if (a.rfind("--jit_type=", 0) == 0) {
                jitType = std::atoi(a.c_str() + 11);
                if (jitType < 0 || jitType > 2) {
                    *error = "--jit_type 只能是 0(tcc) 1(gcc) 2(interp)";
                    return false;
                }
            } else if (a.rfind("--model_dir=", 0) == 0) {
                modelDir = a.substr(12);
            } else if (a == "--help" || a == "-h") {
                *error = "help";
                return false;
            } else if (!a.empty() && a[0] == '-') {
                *error = "未知选项: " + a;
                return false;
            } else if (script.empty()) {
                script = a;
            } else {
                *error = "只接受一个 lua 路径, 多出来的是: " + a;
                return false;
            }
        }
        if (script.empty()) {
            *error = "缺少 lua 目录或文件";
        }
        return error->empty();
    }
};

constexpr const char *kUsage =
    "用法: ./doudizhu_server <lua 目录或文件> [--model_dir=...] [--once] [--jit_type=n]\n"
    "\n"
    "入口固定为 package \"main\" 的 init / update / fini:\n"
    "  function init() ... end      启动一次, 建 HTTP 服务\n"
    "  function update() ... end    每帧一次, 泵 IO 并分发请求\n"
    "  function fini() ... end      退出前一次\n"
    "\n"
    "斗地主引擎以 doudizhu.* 原生函数暴露 (newgame/bid/legal/decide/apply/state/...),\n"
    "规则只有一份, Lua 只做 HTTP 与会话编排。监听端口取环境变量 DOUDIZHU_PORT。\n"
    "自检: DOUDIZHU_SMOKE=1 ./doudizhu_server ../server/lua --once\n";

constexpr const char *kInitEntry = "main.init";
constexpr const char *kUpdateEntry = "main.update";
constexpr const char *kFiniEntry = "main.fini";

std::atomic<bool> g_exit_requested{false};
std::atomic<int> g_exit_code{0};

// 一局牌的全部私有状态。叫分阶段也放在这里, 于是 Lua 拿到的每个数都出自同一份规则。
struct Session
{
    // 三家各 17 张 + 3 张底牌; 定下地主后地主手牌并成 20 张
    std::array<doudizhu::CardSet, 3> dealt{};
    doudizhu::CardSet bottom;
    int firstBidder = 0;
    int bids = 0;
    bool bidding = true;
    bool hasLandlord = false;
    int landlordId = -1;
    doudizhu::GameState state{dealt, 0, bottom};
};

class Registry
{
public:
    int64_t Insert(std::unique_ptr<Session> s)
    {
        const int64_t id = ++nextId_;
        items_.emplace(id, std::move(s));
        return id;
    }

    // id 在会话存续期内稳定, Lua 把它存在会话表里。fakelua 的 HTTP 分发全部在 update
    // 帧内的主线程上串行执行 (与 majiang 把对局状态放 container.map 的假设一致),
    // 所以这里返回裸指针不需要覆盖并发访问。
    Session *Get(int64_t id)
    {
        const auto it = items_.find(id);
        return it == items_.end() ? nullptr : it->second.get();
    }

    bool Erase(int64_t id)
    {
        return items_.erase(id) > 0;
    }

    size_t Size() const { return items_.size(); }

private:
    std::unordered_map<int64_t, std::unique_ptr<Session>> items_;
    int64_t nextId_ = 0;
};

Registry g_registry;
std::unique_ptr<doudizhu::Policy> g_policy;
std::string g_policyError;

// 一手牌 -> "type:mainRank:cards"
std::string FormatMove(const doudizhu::Move &m)
{
    return std::to_string(static_cast<int>(m.type)) + ":" + std::to_string(m.mainRank) + ":"
           + doudizhu::DebugCards(m.cards);
}

// CardSet 没有加法运算符, 并底牌这种合并就地做
doudizhu::CardSet Merge(const doudizhu::CardSet &a, const doudizhu::CardSet &b)
{
    doudizhu::CardSet out = a;
    for (int r = 0; r < doudizhu::kRanks; ++r) {
        out[r] += b[r];
    }
    return out;
}

// 54 张牌洗成三家 17 张 + 3 张底牌
void ShuffleDeal(std::array<doudizhu::CardSet, 3> &hands, doudizhu::CardSet &bottom, std::mt19937_64 &rng)
{
    std::vector<int> deck(54);
    for (int r = 0; r < doudizhu::kNormalRanks; ++r) {
        for (int k = 0; k < 4; ++k) {
            deck[static_cast<size_t>(r * 4 + k)] = r;
        }
    }
    deck[52] = 13;  // 小王
    deck[53] = 14;  // 大王
    std::shuffle(deck.begin(), deck.end(), rng);
    auto take = [&](int lo, int hi) {
        doudizhu::CardSet cs;
        for (int i = lo; i < hi; ++i) {
            cs[deck[static_cast<size_t>(i)]] += 1;
        }
        return cs;
    };
    hands = {take(0, 17), take(17, 34), take(34, 51)};
    bottom = take(51, 54);
}

// 定地主: 底牌并入手牌成 20 张, 状态重建后先手归地主
void MakeLandlord(Session &sess, int pid)
{
    std::array<doudizhu::CardSet, 3> hands = sess.dealt;
    hands[static_cast<size_t>(pid)] = Merge(hands[static_cast<size_t>(pid)], sess.bottom);
    sess.state = doudizhu::GameState(hands, pid, sess.bottom);
    sess.landlordId = pid;
    sess.hasLandlord = true;
    sess.bidding = false;
}

// 一个坏请求不该让整个进程退出: fakelua 里错误逃出 update 等于服务终止, 所以引擎
// 异常一律在原生边界转成回退值, 原因写 stderr。
template<typename Fn>
std::string Guard(const std::string &fallback, Fn &&fn)
{
    try {
        return fn();
    } catch (const std::exception &e) {
        std::fprintf(stderr, "doudizhu_server: 原生调用失败: %s\n", e.what());
        return fallback;
    }
}

std::string FormatMoves(const std::vector<doudizhu::Move> &moves)
{
    std::string out;
    for (const doudizhu::Move &m : moves) {
        if (!out.empty()) {
            out += "|";
        }
        out += FormatMove(m);
    }
    return out;
}

// 合法动作 -> Move 列表。引擎给的是多重集 (应牌时末项恒为过牌), 牌型现算。
std::vector<doudizhu::Move> LegalMoves(const Session &s)
{
    const int active = s.state.ActivePlayerIndex();
    std::vector<doudizhu::Move> out;
    for (const doudizhu::CardSet &cs : s.state.LegalMoves()) {
        out.push_back(doudizhu::ToMove(cs, active));
    }
    return out;
}

// JSON 里「没有值」一律写成空串而不是 null: Lua 侧的数组一旦出现 nil 空洞, 长度就不可靠
std::string Quote(const std::string &v)
{
    return "\"" + v + "\"";
}

// 公开状态: 严格按 viewer 过滤, 只有 viewer 自己的手牌是明文, 其余家只给张数。
// 终局时三家手牌在 revealed 里公开。
std::string StateJson(const Session &s, int viewer)
{
    const doudizhu::PublicView view = s.state.GetPublicView(viewer);
    std::ostringstream j;
    j << "{\"over\":" << (s.state.IsGameOver() ? "true" : "false")
      << ",\"winner\":" << s.state.WinnerId()
      << ",\"winnerIsLandlord\":" << (s.state.IsLandlord(s.state.WinnerId()) ? "true" : "false")
      << ",\"winnerSeat0\":" << (s.state.IsPlayerWinner(0) ? "true" : "false")
      << ",\"active\":" << s.state.ActivePlayerIndex()
      << ",\"landlord\":" << (s.hasLandlord ? s.landlordId : -1)
      << ",\"bidding\":" << (s.bidding ? "true" : "false")
      << ",\"firstBidder\":" << s.firstBidder
      << ",\"passCount\":" << s.state.PassCount()
      << ",\"viewer\":" << viewer
      << ",\"myHand\":\"" << doudizhu::DebugCards(view.myHand) << "\""
      << ",\"bottom\":" << Quote(s.hasLandlord ? doudizhu::DebugCards(s.bottom) : std::string())
      << ",\"counts\":[" << view.GetCardCount(0) << "," << view.GetCardCount(1) << ","
      << view.GetCardCount(2) << "]"
      << ",\"roles\":[";
    for (int i = 0; i < 3; ++i) {
        const std::string role = s.hasLandlord ? std::string(s.state.IsLandlord(i) ? "landlord" : "farmer")
                                                : std::string();
        j << (i ? "," : "") << Quote(role);
    }
    j << "],\"lastMove\":" << Quote(view.lastMove.has_value() ? FormatMove(*view.lastMove) : std::string());
    j << ",\"lastMovePlayer\":" << view.lastMovePlayerId << ",\"history\":[";
    bool first = true;
    for (const doudizhu::Move &m : s.state.MoveHistory()) {
        j << (first ? "" : ",") << "\"" << m.playerId << ":" << FormatMove(m) << "\"";
        first = false;
    }
    j << "]";
    if (s.state.IsGameOver()) {
        j << ",\"revealed\":[";
        for (int i = 0; i < 3; ++i) {
            j << (i ? "," : "") << Quote(doudizhu::DebugCards(s.state.Hand(i)));
        }
        j << "]";
    }
    j << "}";
    return j.str();
}

// ---- 原生回调 ---------------------------------------------------------------
// 回调都写成不带 State*/CVar 的普通函数: 参数只有 int64 和牌面字符串, 返回值只有
// 字符串和 int64, 于是取参/转值的样板全收在下面那三个模板里。

std::string NoSession()
{
    return "{\"error\":\"no session\"}";
}

std::string NativeVersion()
{
    return g_policy != nullptr ? "engine=cpp policy=loaded" : "engine=cpp policy=" + g_policyError;
}

int64_t NativeNewGame(int64_t seed, int64_t firstBidder)
{
    auto sess = std::make_unique<Session>();
    std::mt19937_64 rng(seed > 0 ? static_cast<uint64_t>(seed) : std::random_device{}());
    ShuffleDeal(sess->dealt, sess->bottom, rng);
    sess->firstBidder = static_cast<int>(firstBidder >= 0 && firstBidder <= 2 ? firstBidder : 0);
    sess->state = doudizhu::GameState(sess->dealt, sess->firstBidder, sess->bottom);
    return g_registry.Insert(std::move(sess));
}

// 叫分: 叫 -> 定地主; 不叫 -> 轮转; 三家都不叫 -> 由 Lua 拿 draw 后调 redeal 重发
std::string NativeBid(int64_t id, int64_t call)
{
    return Guard(NoSession(), [id, call] {
        Session *sess = g_registry.Get(id);
        if (sess == nullptr) {
            return NoSession();
        }
        if (!sess->bidding) {
            return std::string("{\"error\":\"not bidding\"}");
        }
        const int bidder = sess->state.ActivePlayerIndex();
        sess->bids += 1;
        if (call != 0) {
            MakeLandlord(*sess, bidder);
            return "{\"outcome\":\"landlord\",\"bidder\":" + std::to_string(bidder) + "}";
        }
        if (sess->bids >= 3) {
            const int next = (sess->firstBidder + 1) % 3;
            return "{\"outcome\":\"draw\",\"next\":" + std::to_string(next) + "}";
        }
        sess->state.ApplyMove(doudizhu::MakePass(bidder));
        return "{\"outcome\":\"pass\",\"bidder\":" + std::to_string(bidder) + "}";
    });
}

std::string NativeRedeal(int64_t id, int64_t firstBidder)
{
    return Guard(NoSession(), [id, firstBidder] {
        Session *sess = g_registry.Get(id);
        if (sess == nullptr) {
            return NoSession();
        }
        std::mt19937_64 rng(std::random_device{}());
        ShuffleDeal(sess->dealt, sess->bottom, rng);
        sess->firstBidder = static_cast<int>(firstBidder >= 0 && firstBidder <= 2 ? firstBidder : 0);
        sess->bids = 0;
        sess->bidding = true;
        sess->hasLandlord = false;
        sess->landlordId = -1;
        sess->state = doudizhu::GameState(sess->dealt, sess->firstBidder, sess->bottom);
        return "{\"outcome\":\"redealt\",\"first\":" + std::to_string(sess->firstBidder) + "}";
    });
}

// 跳过叫分直接定地主 (快捷开局)
std::string NativeSetLandlord(int64_t id, int64_t pid)
{
    return Guard(NoSession(), [id, pid] {
        Session *sess = g_registry.Get(id);
        if (sess == nullptr) {
            return NoSession();
        }
        if (pid < 0 || pid > 2) {
            return std::string("{\"error\":\"bad landlord\"}");
        }
        MakeLandlord(*sess, static_cast<int>(pid));
        return "{\"outcome\":\"landlord\",\"bidder\":" + std::to_string(pid) + "}";
    });
}

std::string NativeLegal(int64_t id)
{
    return Guard(std::string(), [id] {
        Session *sess = g_registry.Get(id);
        if (sess == nullptr || sess->bidding || sess->state.IsGameOver()) {
            return std::string();
        }
        return FormatMoves(LegalMoves(*sess));
    });
}

// 策略网络出一手: "type:mainRank:cards|座位|概率:牌面,..." (最多前 6 个候选)
// 中文名与排版全在 Lua 侧, 这里只出数字与牌面。
std::string NativeDecide(int64_t id)
{
    return Guard(std::string(), [id] {
        Session *sess = g_registry.Get(id);
        if (sess == nullptr || sess->bidding || sess->state.IsGameOver() || g_policy == nullptr) {
            return std::string();
        }
        const int active = sess->state.ActivePlayerIndex();
        const doudizhu::Policy::Decision d = g_policy->Decide(sess->state.GetPublicView(active));
        std::ostringstream out;
        out << FormatMove(d.move) << "|" << active;
        std::vector<int> order(d.options.size());
        for (int i = 0; i < static_cast<int>(order.size()); ++i) {
            order[static_cast<size_t>(i)] = i;
        }
        std::sort(order.begin(), order.end(), [&](int a, int b) {
            return d.probs[static_cast<size_t>(a)] > d.probs[static_cast<size_t>(b)];
        });
        const int top = static_cast<int>(std::min<size_t>(6, d.options.size()));
        for (int i = 0; i < top; ++i) {
            const int k = order[static_cast<size_t>(i)];
            char buf[32];
            std::snprintf(buf, sizeof buf, "%.5f", d.probs[static_cast<size_t>(k)]);
            out << (i == 0 ? "|" : ",") << buf << ":" << doudizhu::DebugCards(d.options[static_cast<size_t>(k)]);
        }
        return out.str();
    });
}

std::string NativeApply(int64_t id, std::string cards)
{
    return Guard(std::string(), [id, &cards] {
        Session *sess = g_registry.Get(id);
        if (sess == nullptr || sess->bidding || sess->state.IsGameOver()) {
            return std::string();
        }
        doudizhu::CardSet chosen;
        try {
            chosen = doudizhu::ParseCards(cards);
        } catch (const std::exception &) {
            return std::string();
        }
        for (const doudizhu::Move &m : LegalMoves(*sess)) {
            if (m.cards == chosen) {
                const std::string out = std::to_string(static_cast<int>(m.type)) + ":" + std::to_string(m.mainRank);
                sess->state.ApplyMove(m);
                return out;
            }
        }
        return std::string();
    });
}

std::string NativeState(int64_t id, int64_t viewer)
{
    return Guard(NoSession(), [id, viewer] {
        Session *sess = g_registry.Get(id);
        if (sess == nullptr) {
            return NoSession();
        }
        return StateJson(*sess, static_cast<int>(viewer >= 0 && viewer <= 2 ? viewer : 0));
    });
}

int64_t NativeMinSteps(std::string cards)
{
    try {
        return doudizhu::MinSteps(doudizhu::ParseCards(cards));
    } catch (const std::exception &) {
        return -1;
    }
}

int64_t NativeSessions()
{
    return static_cast<int64_t>(g_registry.Size());
}

std::string NativeFree(int64_t id)
{
    return g_registry.Erase(id) ? "1" : "0";
}

int64_t ArgInt(State *s, CVar *args, int n, int idx)
{
    return GetNativeIntArg(s, args, n, idx, 0);
}

std::string ArgStr(State *s, CVar *args, int n, int idx)
{
    if (idx >= n) {
        return std::string();
    }
    return inter::FakeluaToNativeString(s, inter::GetNativeArg(s, args, n, idx));
}

template<typename Arg>
Arg OneArg(State *s, CVar *args, int n, int idx)
{
    if constexpr (std::is_same_v<Arg, std::string>) {
        return ArgStr(s, args, n, idx);
    } else {
        return ArgInt(s, args, n, idx);
    }
}

template<typename Ret, typename... Args, std::size_t... I>
Ret CallPack(State *s, CVar *args, int n, Ret (*fn)(Args...), std::index_sequence<I...>)
{
    return fn(OneArg<Args>(s, args, n, static_cast<int>(I))...);
}

template<typename Ret, typename... Args>
void Native(State *s, const std::string &name, Ret (*fn)(Args...))
{
    RegisterNativeFunction(s, name, static_cast<int>(sizeof...(Args)), false, [fn](State *state, CVar *args, int n) -> CVar {
        using Seq = std::index_sequence_for<Args...>;
        if constexpr (std::is_same_v<Ret, std::string>) {
            return inter::NativeToFakeluaString(state, CallPack(state, args, n, fn, Seq{}));
        } else {
            return inter::NativeToFakeluaLonglong(state, static_cast<int64_t>(CallPack(state, args, n, fn, Seq{})));
        }
    });
}

void RegisterEngineApi(State *s)
{
    Native(s, "doudizhu.version", NativeVersion);
    Native(s, "doudizhu.newgame", NativeNewGame);
    Native(s, "doudizhu.bid", NativeBid);
    Native(s, "doudizhu.redeal", NativeRedeal);
    Native(s, "doudizhu.setlandlord", NativeSetLandlord);
    Native(s, "doudizhu.legal", NativeLegal);
    Native(s, "doudizhu.decide", NativeDecide);
    Native(s, "doudizhu.apply", NativeApply);
    Native(s, "doudizhu.state", NativeState);
    Native(s, "doudizhu.minsteps", NativeMinSteps);
    Native(s, "doudizhu.sessions", NativeSessions);
    Native(s, "doudizhu.free", NativeFree);

    // 只有这个回调要碰 CVar: 它把退出请求记到宿主的全局标志上
    RegisterNativeFunction(s, "doudizhu.exit", 1, true, [](State *state, CVar *args, int n) -> CVar {
        g_exit_code.store(static_cast<int>(GetNativeIntArg(state, args, n, 0, 0)), std::memory_order_relaxed);
        g_exit_requested.store(true, std::memory_order_relaxed);
        return NativeNil(state);
    });
}
std::vector<std::string> CollectLuaFiles(const std::string &path)
{
    std::error_code ec;
    if (std::filesystem::is_directory(path, ec)) {
        std::vector<std::string> files;
        for (const auto &entry : std::filesystem::directory_iterator(path, ec)) {
            if (entry.is_regular_file() && entry.path().extension() == ".lua") {
                files.push_back(entry.path().string());
            }
        }
        std::sort(files.begin(), files.end());
        if (files.empty()) {
            std::cerr << "doudizhu_server: " << path << " 下没有 .lua 文件\n";
        }
        return files;
    }
    return {path};
}

[[noreturn]] void ReportMissingEntry(const char *name)
{
    std::cerr << "doudizhu_server: 找不到入口 '" << name << "'\n"
              << "        脚本必须提供 package \"main\" 的 init / update / fini\n";
    std::exit(1);
}

// init -> update* -> fini。init 失败(返回非 0 或抛错)时不跑 fini。
int RunLifecycle(State *s, const Options &opts)
{
    const JITType jit = static_cast<JITType>(opts.jitType);
    if (!HasEntryFunction(s, jit, kInitEntry)) {
        ReportMissingEntry(kInitEntry);
    }
    if (!opts.once && !HasEntryFunction(s, jit, kUpdateEntry)) {
        ReportMissingEntry(kUpdateEntry);
    }
    if (!HasEntryFunction(s, jit, kFiniEntry)) {
        ReportMissingEntry(kFiniEntry);
    }

    bool initOk = false;
    int exitCode = 0;
    try {
        int code = 0;
        Call(s, jit, kInitEntry, code);
        if (code != 0) {
            exitCode = code;
            std::fprintf(stderr, "doudizhu_server: init 返回 %d\n", code);
        } else {
            initOk = true;
        }
    } catch (const std::exception &e) {
        std::fprintf(stderr, "doudizhu_server: init 失败: %s\n", e.what());
        return 1;
    }

    if (initOk && !opts.once) {
        while (!g_exit_requested.load(std::memory_order_relaxed)) {
            int code = 0;
            try {
                Call(s, jit, kUpdateEntry, code);
            } catch (const std::exception &e) {
                std::fprintf(stderr, "doudizhu_server: update 失败: %s\n", e.what());
                exitCode = 1;
                break;
            }
            if (code != 0) {
                g_exit_code.store(code, std::memory_order_relaxed);
                break;
            }
        }
    }

    if (initOk) {
        try {
            int code = 0;
            Call(s, jit, kFiniEntry, code);
        } catch (const std::exception &e) {
            std::fprintf(stderr, "doudizhu_server: fini 失败: %s\n", e.what());
            exitCode = 1;
        }
    }
    return exitCode != 0 ? exitCode : g_exit_code.load(std::memory_order_relaxed);
}

}  // namespace

int main(int argc, char **argv)
{
    Options opts;
    std::string error;
    if (!opts.Parse(argc, argv, &error)) {
        if (error != "help") {
            std::fprintf(stderr, "doudizhu_server: %s\n\n", error.c_str());
        }
        std::cout << kUsage;
        return error == "help" ? 0 : 1;
    }

    // 权重加载一次, 全部会话共用 (与 Java 的 ActorNetwork 共享方式一致)
    g_policy = std::make_unique<doudizhu::Policy>();
    const std::string dir = opts.modelDir.empty() ? doudizhu::Policy::DefaultModelDir() : opts.modelDir;
    if (!g_policy->Load(dir)) {
        g_policyError = g_policy->Error();
        std::fprintf(stderr, "doudizhu_server: 加载权重失败 (%s): %s\n", dir.c_str(), g_policyError.c_str());
        return 1;
    }

    const FakeluaStateGuard guard;
    State *s = guard.GetState();
    // 3 = Warn: 每次 CompileFile 都会打一串 JIT/INFO 日志, 常驻服务只需要看得见错误
    SetDebugLogLevel(s, 3);
    // 未捕获的 Lua 运行时错误按 Lua 惯例打印并以 1 退出, 不让 C++ 异常逃逸到 libc++abi
    try {
        RegisterEngineApi(s);

        CompileConfig cfg;
        for (const auto &file : CollectLuaFiles(opts.script)) {
            CompileFile(s, file, cfg);
        }
        return RunLifecycle(s, opts);
    } catch (const std::exception &e) {
        std::fprintf(stderr, "doudizhu_server: %s\n", e.what());
        return 1;
    }
}

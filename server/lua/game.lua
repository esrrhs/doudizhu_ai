package "DoudizhuGame"

-- 会话流程编排。牌局本体 (手牌、合法动作、轮转、胜负) 全在宿主的 C++ 引擎里,
-- 这里只存「谁该表态、日志、每个座位最近一手、AI 思路」这类流程与展示状态。
--
-- fakelua 的文件级 table 是常量, container.map 又只能存标量: 会话整块 serialize
-- 成字符串挂在 map 上, 每次改动读一遍写一遍。

local store = container.map()

local MAX_LOGS = 100
-- 闲置超过这个秒数的会话连同宿主的牌局一起回收 (宿主没有 GC, 不回收就一直占着)
local EXPIRE_S = 3600
-- 随机叫地主: AI 每次表态叫土地主的概率 (与 Java BidRule.CALL_PROBABILITY 一致)
local CALL_PROBABILITY = 1.0 / 3.0
-- 真人固定坐 0 号位, 1/2 交给策略网络
local HUMAN = 0

local function load_session(sid)
    local bin = store:get(sid)
    if bin == nil then
        return nil
    end
    return serialize.decode(bin)
end

local function save_session(sid, s)
    s.seen = os.time()
    store:set(sid, serialize.encode(s))
end

local function add_log(s, text)
    local logs = s.logs
    logs[#logs + 1] = text
    if #logs > MAX_LOGS then
        local kept = {}
        local i = #logs - MAX_LOGS + 1
        while i <= #logs do
            kept[#kept + 1] = logs[i]
            i = i + 1
        end
        s.logs = kept
    end
end

local function seat_name(seat)
    if seat == HUMAN then
        return "玩家 P0 (真人)"
    end
    return "AI 玩家 P" .. tostring(seat)
end

local function blank(game_id, first)
    return {
        gameId = game_id,
        stage = "BIDDING",
        bidder = first,
        bid = { "", "", "" },
        acts = { "", "", "" },
        thoughts = {},
        logs = {},
        seen = 0
    }
end

local function engine_of(s)
    return json.decode(doudizhu.state(s.gameId, HUMAN))
end

local function role_of(e, seat)
    return DoudizhuDef.RoleName(e.roles[seat + 1])
end

-- 桌面是否已清空 (无人出牌或上家过牌): 清台后各座位面前的展示归零
local function table_cleared(e)
    local m = DoudizhuDef.ParseMove(e.lastMove)
    return m == nil or m.type == 0
end

-- 记下一手的展示, 并在终局时把阶段推进到 GAME_OVER
local function record_action(s, e, seat, move_text)
    if table_cleared(e) then
        s.acts = { "", "", "" }
    end
    s.acts[seat + 1] = move_text
    local after = engine_of(s)
    if after ~= nil and after.over == true then
        s.stage = "GAME_OVER"
    end
end

-- direct 为真时跳过叫分直接指定地主 (网页端的快捷开局)
function New(sid, direct, landlord_id)
    -- 重开一局要先释放上一局的牌局对象: 宿主没有 GC, 不换掉就会一直堆
    local old = load_session(sid)
    if old ~= nil then
        doudizhu.free(old.gameId)
    end
    local first = math.random(0, 2)
    local gid = doudizhu.newgame(0, first)
    local s = blank(gid, first)
    if direct == true and landlord_id >= 0 and landlord_id <= 2 then
        doudizhu.setlandlord(gid, landlord_id)
        s.stage = "PLAYING"
        add_log(s, "🎲 快速开局！" .. seat_name(landlord_id)
            .. " 成为地主，翻开 3 张底牌，手牌增至 20 张并先手出牌！")
    else
        add_log(s, "🎲 新局发牌完毕（各 17 张），进入【叫地主】阶段！由 "
            .. seat_name(first) .. " 首先表态。")
    end
    save_session(sid, s)
    return s
end

function Ensure(sid)
    local s = load_session(sid)
    if s == nil then
        return New(sid, false, -1)
    end
    s.seen = os.time()
    return s
end

-- 一次表态: 叫分状态机全在引擎里, 这里只落展示与日志
local function apply_bid(s, seat, call)
    s.bidder = seat
    s.bid[seat + 1] = call and "叫地主" or "不叫"
    if call then
        add_log(s, "👉 " .. seat_name(seat) .. " 选择了【叫地主】！")
    else
        add_log(s, "👉 " .. seat_name(seat) .. " 选择了【不叫】。")
    end

    local out = json.decode(doudizhu.bid(s.gameId, call and 1 or 0))
    if out == nil then
        return "引擎状态异常"
    end
    if out.outcome == "landlord" then
        s.stage = "PLAYING"
        s.acts = { "", "", "" }
        add_log(s, "👑 " .. seat_name(out.bidder)
            .. " 成为地主！获得 3 张底牌，手牌增至 20 张并先手出牌！")
    elseif out.outcome == "draw" then
        -- 三家都不叫: 重发牌, 先叫位顺延 (与 Java 的流局处理一致)
        add_log(s, "⚠️ 三位玩家均选择【不叫】，流局！重新洗牌发牌...")
        doudizhu.redeal(s.gameId, out.next)
        s.bidder = out.next
        s.bid = { "", "", "" }
        s.acts = { "", "", "" }
        s.thoughts = {}
    else
        s.bidder = out.bidder
    end
    return nil
end

function Bid(sid, call)
    local s = Ensure(sid)
    if s.stage ~= "BIDDING" then
        return "当前不在叫地主阶段"
    end
    local e = engine_of(s)
    if e.active ~= HUMAN then
        return "当前不是你的叫牌回合"
    end
    local err = apply_bid(s, HUMAN, call == true)
    save_session(sid, s)
    return err
end

-- 轮到 AI 表态: 按概率随机叫/不叫, 与手牌强弱无关
function AiBid(sid)
    local s = Ensure(sid)
    if s.stage ~= "BIDDING" then
        return "当前不在叫地主阶段"
    end
    local e = engine_of(s)
    if e.active == HUMAN then
        save_session(sid, s)
        return "当前轮到真人叫牌"
    end
    local call = math.random() < CALL_PROBABILITY
    apply_bid(s, e.active, call)
    save_session(sid, s)
    if call then
        return "叫地主"
    end
    return "不叫"
end

function HumanPlay(sid, symbols)
    local s = Ensure(sid)
    if s.stage == "BIDDING" then
        save_session(sid, s)
        return "当前仍在叫地主阶段，尚未确定地主"
    end
    local e = engine_of(s)
    if e.over == true then
        save_session(sid, s)
        return "游戏已结束，请重新开局"
    end
    if e.active ~= HUMAN then
        save_session(sid, s)
        return "当前不是你的回合，请等待 AI 行动"
    end

    local cards = DoudizhuDef.SymbolsToCards(symbols)
    if cards == "" or #cards ~= #symbols then
        save_session(sid, s)
        return "无效的卡牌点数"
    end
    local applied = doudizhu.apply(s.gameId, cards)
    if applied == "" then
        save_session(sid, s)
        return "不符合斗地主规则或压不过上家牌！请重新选牌或使用 AI 提示。"
    end
    record_action(s, e, HUMAN, applied .. ":" .. cards)
    add_log(s, "👉 " .. seat_name(HUMAN) .. " (" .. role_of(e, HUMAN) .. ") 出牌: "
        .. DoudizhuDef.CardsToText(cards) .. " (" .. DoudizhuDef.TypeName(DoudizhuDef.MoveType(applied)) .. ")")
    save_session(sid, s)
    return nil
end

function HumanPass(sid)
    local s = Ensure(sid)
    if s.stage == "BIDDING" then
        save_session(sid, s)
        return "当前仍在叫地主阶段"
    end
    local e = engine_of(s)
    if e.over == true then
        save_session(sid, s)
        return "游戏已结束，请重新开局"
    end
    if e.active ~= HUMAN then
        save_session(sid, s)
        return "当前不是你的回合"
    end
    if not DoudizhuDef.HasPass(doudizhu.legal(s.gameId)) then
        save_session(sid, s)
        return "当前无人出牌，您必须主动出牌，不能过牌！"
    end
    local applied = doudizhu.apply(s.gameId, "-")
    if applied == "" then
        save_session(sid, s)
        return "当前不能过牌"
    end
    record_action(s, e, HUMAN, applied .. ":-")
    add_log(s, "👉 " .. seat_name(HUMAN) .. " (" .. role_of(e, HUMAN) .. ") 选择了【不出/过牌】。")
    save_session(sid, s)
    return nil
end

-- 轮到 AI 行动一步: 叫分阶段随机表态, 出牌阶段交给策略网络
function AiStep(sid)
    local s = Ensure(sid)
    if s.stage == "BIDDING" then
        return AiBid(sid)
    end
    local e = engine_of(s)
    if e.over == true then
        save_session(sid, s)
        return "游戏已结束"
    end
    if e.active == HUMAN then
        save_session(sid, s)
        return "轮到真人行动"
    end

    local t0 = os.clock()
    local decided = DoudizhuDef.ParseDecision(doudizhu.decide(s.gameId))
    local cost = math.floor((os.clock() - t0) * 1000)
    if decided == nil then
        save_session(sid, s)
        return "AI 决策失败"
    end
    local chosen = decided.chosen
    local applied = doudizhu.apply(s.gameId, chosen.cards)
    if applied == "" then
        save_session(sid, s)
        return "AI 选出的动作不合法"
    end
    local seat = decided.seat
    record_action(s, e, seat,
        tostring(chosen.type) .. ":" .. tostring(chosen.rank) .. ":" .. chosen.cards)
    s.thoughts["p" .. tostring(seat)] = {
        type = "play",
        move = DoudizhuDef.CardsToText(chosen.cards),
        timeMs = cost,
        evals = decided.evals
    }
    if chosen.type == 0 then
        add_log(s, "🤖 " .. seat_name(seat) .. " (" .. role_of(e, seat) .. ") 选择了【不出/过牌】")
    else
        add_log(s, "🤖 " .. seat_name(seat) .. " (" .. role_of(e, seat) .. ") 出牌: "
            .. DoudizhuDef.CardsToText(chosen.cards) .. " (" .. DoudizhuDef.TypeName(chosen.type) .. ")")
    end
    save_session(sid, s)
    return DoudizhuDef.CardsToText(chosen.cards)
end

-- 引擎 CardType 序号: 0 过牌 1 单张 2 对子 3 三张 4 三带一 5 三带二 6 顺子 7 连对
-- 8/9/10 飞机 11/12 四带二 13 炸弹 14 火箭
local function is_structure(t)
    return t >= 4 and t <= 12
end

local function is_heavy(t)
    return t == 13 or t == 14
end

local function pick_lowest(list)
    local best = nil
    local i = 1
    while i <= #list do
        if best == nil or list[i].rank < best.rank then
            best = list[i]
        end
        i = i + 1
    end
    return best
end

local function pick_highest(list)
    local best = nil
    local i = 1
    while i <= #list do
        if best == nil or list[i].rank > best.rank then
            best = list[i]
        end
        i = i + 1
    end
    return best
end

-- AI 提示: 与 Java GameSession.getHumanHint 同一条启发规则 —— 主动出牌时先走结构牌、
-- 再三张、再小点数散牌 (留 2 和王), 被动应牌时挑最小的能压过的, 都没有才考虑炸弹。
local function pick_hint(moves, lead)
    local structures = {}
    local trios = {}
    local smalls = {}
    local others = {}
    local bombs = {}
    local beaters = {}
    local pass = nil

    local i = 1
    while i <= #moves do
        local m = moves[i]
        if m.type == 0 then
            pass = m
        elseif is_heavy(m.type) then
            bombs[#bombs + 1] = m
        elseif lead then
            if is_structure(m.type) then
                structures[#structures + 1] = m
            elseif m.type == 3 then
                trios[#trios + 1] = m
            elseif m.rank < 15 then
                smalls[#smalls + 1] = m
            else
                others[#others + 1] = m
            end
        else
            beaters[#beaters + 1] = m
        end
        i = i + 1
    end

    if lead then
        local best = pick_lowest(structures)
        if best == nil then
            best = pick_lowest(trios)
        end
        if best == nil then
            best = pick_lowest(smalls)
        end
        if best == nil then
            best = pick_lowest(others)
        end
        if best == nil then
            best = pick_lowest(bombs)
        end
        if best == nil then
            best = moves[1]
        end
        return best
    end

    local best = pick_lowest(beaters)
    if best == nil then
        best = pick_highest(bombs)
    end
    if best == nil then
        best = pass
    end
    if best == nil then
        best = moves[1]
    end
    return best
end

function Hint(sid)
    local s = load_session(sid)
    if s == nil or s.stage ~= "PLAYING" then
        return { hint = nil }
    end
    local e = engine_of(s)
    if e.over == true or e.active ~= HUMAN then
        return { hint = nil }
    end
    local chosen = nil
    -- 优先走策略网络：真人要提示时用模型评估出最优着法
    local decided = DoudizhuDef.ParseDecision(doudizhu.decide(s.gameId))
    if decided ~= nil and decided.chosen ~= nil then
        chosen = decided.chosen
    else
        local moves = DoudizhuDef.ParseMoves(doudizhu.legal(s.gameId))
        if #moves == 0 then
            return { hint = nil }
        end
        chosen = pick_hint(moves, table_cleared(e))
    end
    if chosen == nil then
        return { hint = nil }
    end
    local res = {
        hint = DoudizhuDef.CardsToSymbols(chosen.cards),
        isPass = chosen.type == 0,
        type = DoudizhuDef.TypeName(chosen.type)
    }
    if decided ~= nil and decided.evals ~= nil and #decided.evals > 0 then
        res.evals = decided.evals
        res.probability = decided.evals[1].probability
    end
    return res
end

-- 前端要的完整状态 DTO, 字段与 Java 版 GameHttpServer.buildStateDto 一一对应
function View(sid, message)
    local s = Ensure(sid)
    local e = engine_of(s)
    if e == nil then
        return nil
    end
    if e.over == true then
        s.stage = "GAME_OVER"
        save_session(sid, s)
    end

    local dto = {}
    dto.message = message
    dto.stage = s.stage
    dto.currentBidder = e.active
    dto.bidActions = s.bid
    dto.landlordId = e.landlord
    dto.bottomCardsRevealed = e.bottom ~= ""
    dto.bottomCards = DoudizhuDef.CardsToSymbols(e.bottom)
    dto.cardCounts = e.counts
    dto.humanHand = DoudizhuDef.CardsToSymbols(e.myHand)

    local roles = {}
    local i = 1
    while i <= #e.roles do
        roles[i] = DoudizhuDef.RoleName(e.roles[i])
        i = i + 1
    end
    dto.roles = roles

    dto.activePlayer = e.active
    dto.isGameOver = e.over == true
    dto.winner = e.winner
    dto.isHumanWinner = e.winnerSeat0 == true
    if e.over == true then
        dto.winningRole = e.winnerIsLandlord == true and "地主" or "农民"
        local hands = {}
        local k = 1
        while k <= #e.revealed do
            hands[k] = DoudizhuDef.CardsToSymbols(e.revealed[k])
            k = k + 1
        end
        dto.revealedHands = hands
    else
        dto.winningRole = nil
        dto.revealedHands = nil
    end

    local lm = DoudizhuDef.ParseMove(e.lastMove)
    if lm == nil or lm.type == 0 then
        dto.lastMove = nil
    else
        dto.lastMove = {
            playerId = e.lastMovePlayer,
            type = DoudizhuDef.TypeName(lm.type),
            cards = DoudizhuDef.CardsToSymbols(lm.cards)
        }
    end

    dto.canPass = DoudizhuDef.HasPass(doudizhu.legal(s.gameId))
    dto.playedStats = DoudizhuDef.PlayedStats(e.history)

    local acts = {}
    local a = 1
    while a <= 3 do
        local m = DoudizhuDef.ParseMove(s.acts[a])
        if m == nil then
            acts[a] = {}
        else
            acts[a] = {
                playerId = a - 1,
                isPass = m.type == 0,
                type = DoudizhuDef.TypeName(m.type),
                cards = DoudizhuDef.CardsToSymbols(m.cards)
            }
        end
        a = a + 1
    end
    dto.playerActions = acts

    dto.aiThoughts = s.thoughts
    dto.logs = s.logs
    return dto
end

-- 回收闲置会话: 会话本体是序列化字符串, 只按 seen 比较
function Sweep(now)
    local keys = store:keys()
    local i = 1
    while i <= #keys do
        local sid = keys[i]
        local s = load_session(sid)
        if s ~= nil and now - (s.seen or 0) > EXPIRE_S then
            doudizhu.free(s.gameId)
            store:erase(sid)
        end
        i = i + 1
    end
end

function Count()
    return #store:keys()
end

package "DoudizhuDef"

-- 牌面与牌型的两套写法只在这里转换:
--   引擎侧 = 训练侧的紧凑写法, 一个字符一张牌 (3456789 T J Q K A 2 B小王 R大王), 过牌写 "-"
--   前端   = Java 版沿用的符号数组 ("3".."9" "10" "J" "Q" "K" "A" "2" "BJ" "RJ")
-- 其余各层只认其中一种, 转换全部收在这个文件里。

local CHARS = { "3", "4", "5", "6", "7", "8", "9", "T", "J", "Q", "K", "A", "2", "B", "R" }
local SYMBOLS = { "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2", "BJ", "RJ" }

-- 引擎 CardType 序号 0..14 -> 中文名, 与 Java CardType.getDescription 对齐
local TYPE_NAMES = {
    "过牌", "单张", "对子", "三张", "三带一", "三带二", "顺子", "连对",
    "飞机不带", "飞机带单", "飞机带对", "四带两单", "四带两对", "炸弹", "火箭(王炸)"
}

local ROLE_NAMES = { landlord = "地主", farmer = "农民" }

function RankCount()
    return 15
end

function TypeName(t)
    if t == nil or t < 0 or t > 14 then
        return "?"
    end
    return TYPE_NAMES[t + 1]
end

function RoleName(r)
    if r == nil then
        return "待定"
    end
    local n = ROLE_NAMES[r]
    if n == nil then
        return "待定"
    end
    return n
end

function SymbolOfChar(ch)
    local i = 1
    while i <= 15 do
        if CHARS[i] == ch then
            return SYMBOLS[i]
        end
        i = i + 1
    end
    return nil
end

-- "33T" -> {"3","3","10"}; 认不出的字符丢掉
function CardsToSymbols(cards)
    local out = {}
    if cards == nil then
        return out
    end
    local i = 1
    while i <= string.len(cards) do
        local sym = SymbolOfChar(string.sub(cards, i, i))
        if sym ~= nil then
            out[#out + 1] = sym
        end
        i = i + 1
    end
    return out
end

-- {"3","3","10"} -> "33T"; 按引擎的点数序回拼, 与 DebugCards 的写法逐字符一致。
-- 认不出的符号直接丢掉: 脏输入必须在这一层挡掉, 不能让宿主抛错把服务带崩。
function SymbolsToCards(list)
    local idxs = {}
    local i = 1
    while list ~= nil and i <= #list do
        local sym = string.upper(string.trim(tostring(list[i])))
        local j = 1
        while j <= 15 do
            if SYMBOLS[j] == sym then
                idxs[#idxs + 1] = j
                break
            end
            j = j + 1
        end
        i = i + 1
    end
    table.sort(idxs)
    local out = ""
    local k = 1
    while k <= #idxs do
        out = out .. CHARS[idxs[k]]
        k = k + 1
    end
    return out
end

function CardsToText(cards)
    local list = CardsToSymbols(cards)
    if #list == 0 then
        return "不出"
    end
    return table.concat(list, ",")
end

-- 一手牌的线格式 "type:mainRank:cards" -> {type=, rank=, cards=}
function ParseMove(text)
    if text == nil or text == "" then
        return nil
    end
    local p = string.split(text, ":")
    if #p < 3 then
        return nil
    end
    return { type = math.floor(tonumber(p[1]) or -1), rank = math.floor(tonumber(p[2]) or 0), cards = p[3] }
end

-- 宿主的 decide 回包 "chosen|seat|p:cards,p:cards,..."
function ParseDecision(text)
    if text == nil or text == "" then
        return nil
    end
    local parts = string.split(text, "|")
    if #parts < 3 then
        return nil
    end
    local chosen = ParseMove(parts[1])
    if chosen == nil then
        return nil
    end
    local ret = { chosen = chosen, seat = math.floor(tonumber(parts[2]) or 0), evals = {} }
    local i = 3
    while i <= #parts do
        local ev = string.split(parts[i], ":")
        if #ev >= 2 then
            local p = tonumber(ev[1])
            if p ~= nil then
                -- 前端显示的是百分数, 保留一位小数 (与 Java 版的 round(p*1000)/10 一致)
                ret.evals[#ret.evals + 1] = {
                    cards = CardsToText(ev[2]),
                    probability = math.floor(p * 1000 + 0.5) / 10
                }
            end
        end
        i = i + 1
    end
    return ret
end

-- 引擎的 "seat:type:rank:cards" (出牌历史里的一项)
function ParseHistory(text)
    local p = string.split(text, ":")
    if #p < 4 then
        return nil
    end
    return {
        seat = math.floor(tonumber(p[1]) or -1),
        type = math.floor(tonumber(p[2]) or -1),
        rank = math.floor(tonumber(p[3]) or 0),
        cards = p[4]
    }
end

-- 宿主 apply 的回包只有 "type:mainRank", 记日志时要拿牌型序号
function MoveType(text)
    if text == nil or text == "" then
        return -1
    end
    local p = string.split(text, ":")
    return math.floor(tonumber(p[1]) or -1)
end

-- doudizhu.legal 的 "type:rank:cards|..." -> 一手牌列表
function ParseMoves(text)
    local out = {}
    if text == nil or text == "" then
        return out
    end
    local list = string.split(text, "|")
    local i = 1
    while i <= #list do
        local m = ParseMove(list[i])
        if m ~= nil then
            out[#out + 1] = m
        end
        i = i + 1
    end
    return out
end

function HasPass(text)
    local moves = ParseMoves(text)
    local i = 1
    while i <= #moves do
        if moves[i].type == 0 then
            return true
        end
        i = i + 1
    end
    return false
end

function Symbols()
    return SYMBOLS
end

-- 记牌器: 15 个点数各占一格, 值 = 已经打出的张数。键全部预置成 0, 空场时也是 15 格。
function PlayedStats(history)
    local stats = {}
    local i = 1
    while i <= 15 do
        stats[SYMBOLS[i]] = 0
        i = i + 1
    end
    local h = 1
    while history ~= nil and h <= #history do
        local m = ParseHistory(history[h])
        if m ~= nil and m.type ~= 0 then
            local cs = CardsToSymbols(m.cards)
            local c = 1
            while c <= #cs do
                stats[cs[c]] = (stats[cs[c]] or 0) + 1
                c = c + 1
            end
        end
        h = h + 1
    end
    return stats
end

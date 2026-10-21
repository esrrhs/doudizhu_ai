package "main"

-- 标准生命周期。宿主固定调用 package "main" 下的这三个函数, 每次调用都是独立的顶层
-- Call (帧 arena 在入口 Reset, 请求级临时内存随之回收):
--   init   : 启动一次, 建 HTTP 服务
--   update : 每帧一次, 泵 IO/定时器并分发 HTTP 请求
--   fini   : 退出前一次 (doudizhu.exit / update 返回非 0 都会跑到)
-- 切勿在 Lua 侧写 while true 把三者套进一个 Call (arena 永不 Reset 即内存泄露)。
--
-- 节奏由业务自己控制: 宿主不加节流, update 返回后立刻再调一次, 所以这里自己
-- os.sleep 一拍 (单位是毫秒)。HTTP 请求在 runtime.tick() 里被分发。
--
-- 节拍直接决定 HTTP 延迟: 一个请求要跨三拍才走完 (收请求 -> 派发 -> 把响应泵出去),
-- 1000ms 一拍就是 3s 一个请求。空闲时退避到 IDLE_TICK_INTERVAL_MS 不空转 CPU,
-- 有请求来的那几拍再回到 1ms —— 由 DoudizhuHttp 的活跃计数器驱动。
local BUSY_TICK_INTERVAL_MS = 1
local IDLE_TICK_INTERVAL_MS = 20
-- 收到请求后保持多少拍的"忙碌"节拍 (够把一次请求的三拍走完)
local BUSY_TICKS = 16
-- 每隔多少拍扫一次闲置会话 (会话回收要序列化整表, 不必每拍都做)
local SWEEP_TICKS = 512

-- 可变状态一律落 C++ 堆: 文件级 local 落在常量区不可写
local pace = container.map()

-- 自检: 引擎与权重可用 + 一条完整牌局跑得通 + JSON 编码契约。
-- 用 DOUDIZHU_SMOKE=1 <宿主> lua --once 触发, init 返回非 0 即失败。
local function self_check()
    local ver = doudizhu.version()
    print("smoke version=", ver)
    if not string.contains(ver, "policy=loaded") then
        return 1
    end

    -- 1) 发牌: 三家 17 张, 底牌在定地主后才公开
    local gid = doudizhu.newgame(20261021, 1)
    local st = json.decode(doudizhu.state(gid, 0))
    if st == nil or #st.myHand ~= 17 then
        print("smoke deal broken: ", tostring(st == nil and "nil" or st.myHand))
        return 1
    end
    if st.bottom ~= "" then
        print("smoke bottom should stay hidden while bidding")
        return 1
    end

    -- 2) 叫分: 直接定地主后地主 20 张且先手
    doudizhu.setlandlord(gid, 2)
    st = json.decode(doudizhu.state(gid, 2))
    if #st.myHand ~= 20 or st.active ~= 2 or st.landlord ~= 2 then
        print("smoke landlord wiring broken")
        return 1
    end
    if st.bottom == "" or #DoudizhuDef.CardsToSymbols(st.bottom) ~= 3 then
        print("smoke bottom cards broken")
        return 1
    end

    -- 3) 三家全用策略网络跑完一局: 必须终局, 且不超步数上限
    local steps = 0
    while st.over ~= true and steps < 400 do
        local decided = DoudizhuDef.ParseDecision(doudizhu.decide(gid))
        if decided == nil then
            print("smoke decide returned nothing at step=", tostring(steps))
            return 1
        end
        if doudizhu.apply(gid, decided.chosen.cards) == "" then
            print("smoke chosen move rejected: ", decided.chosen.cards)
            return 1
        end
        steps = steps + 1
        st = json.decode(doudizhu.state(gid, 0))
    end
    print("smoke selfplay steps=", tostring(steps), " over=", tostring(st.over), " winner=", tostring(st.winner))
    if st.over ~= true then
        return 1
    end
    if st.revealed == nil or #st.revealed ~= 3 then
        print("smoke hands not revealed at game over")
        return 1
    end

    -- 4) 会话必须显式释放, 否则宿主里的牌局一直占着内存
    if doudizhu.free(gid) ~= "1" then
        print("smoke free failed")
        return 1
    end
    if doudizhu.sessions() ~= 0 then
        print("smoke sessions not zero after free: ", tostring(doudizhu.sessions()))
        return 1
    end

    -- 5) 会话流程层: 建会话、状态 DTO、提示、闲置回收
    local dto = DoudizhuGame.View("smoke_session", nil)
    if dto == nil or dto.stage ~= "BIDDING" or #dto.humanHand ~= 17 then
        print("smoke dto broken")
        return 1
    end
    if #dto.bidActions ~= 3 or #dto.roles ~= 3 or #dto.cardCounts ~= 3 then
        print("smoke dto shape broken")
        return 1
    end
    DoudizhuGame.New("smoke_session", true, 0)
    dto = DoudizhuGame.View("smoke_session", nil)
    if dto.stage ~= "PLAYING" or dto.landlordId ~= 0 then
        print("smoke direct start broken")
        return 1
    end
    local hint = DoudizhuGame.Hint("smoke_session")
    if hint.hint == nil or #hint.hint == 0 then
        print("smoke hint empty")
        return 1
    end
    if DoudizhuGame.Count() ~= 1 then
        print("smoke session count broken: ", tostring(DoudizhuGame.Count()))
        return 1
    end

    -- 会话层的 AI 一步: 地主是 1 号位, 所以轮到 AI 出牌
    DoudizhuGame.New("smoke_ai", true, 1)
    local acted = DoudizhuGame.AiStep("smoke_ai")
    local dto2 = DoudizhuGame.View("smoke_ai", nil)
    print("smoke ai step=", tostring(acted), " active=", tostring(dto2.activePlayer))
    if acted == nil or acted == "" then
        print("smoke ai step produced nothing")
        return 1
    end
    if type(dto2.aiThoughts) ~= "table" or dto2.aiThoughts["p1"] == nil then
        print("smoke ai thoughts missing")
        return 1
    end

    -- 6) JSON 编码契约: 字符串键的表必须是对象 (前端的记牌器按键取值)
    local as_obj = json.encode({ ["10"] = 4, ["RJ"] = 0 })
    if not string.contains(as_obj, "\"10\"") then
        print("smoke json object encode broken: ", as_obj)
        return 1
    end

    -- 7) 牌面两套写法必须能原样往返: 前端提交的符号列表要能拼回引擎的紧凑写法
    local roundtrip = DoudizhuDef.SymbolsToCards({ "10", "10", "J", "BJ", "3", "RJ" })
    if roundtrip ~= "3TTJBR" then
        print("smoke cards roundtrip broken: ", roundtrip)
        return 1
    end
    if table.concat(DoudizhuDef.CardsToSymbols(roundtrip), ",") ~= "3,10,10,J,BJ,RJ" then
        print("smoke cards decode broken")
        return 1
    end

    return 0
end

function init()
    local smoke = os.getenv("DOUDIZHU_SMOKE")
    if smoke ~= nil and #smoke > 0 then
        return self_check()
    end

    print("=================================================")
    print("  斗地主 AI 网页对战平台 (FakeLua + C++ 引擎版)")
    print("=================================================")
    print("推理后端: " .. doudizhu.version())

    local port = 8080
    local env_port = os.getenv("DOUDIZHU_PORT")
    if env_port ~= nil and #env_port > 0 then
        port = math.floor(tonumber(env_port) or 8080)
    end

    local cfg = {}
    cfg["ip"] = "0.0.0.0"
    cfg["port"] = port
    local srv = http.server(cfg)
    srv:dispatch("DoudizhuHttp.OnRequest")
    print("HTTP 监听 :" .. tostring(port))
    print("浏览器打开 http://127.0.0.1:" .. tostring(port))
    print("-------------------------------------------------")
    return 0
end

-- 自适应节拍: 请求活跃期 1ms 保证响应及时, 空闲期退避到 20ms 省 CPU。
function update()
    runtime.tick()

    local seen = DoudizhuHttp.ServedCount()
    if seen ~= pace:get("last_seen") then
        pace:set("last_seen", seen)
        pace:set("busy_left", BUSY_TICKS)
        pace:set("ticks", 0)
    end

    local ticks = (pace:get("ticks") or 0) + 1
    pace:set("ticks", ticks)
    if math.floor(ticks / SWEEP_TICKS) * SWEEP_TICKS == ticks then
        DoudizhuGame.Sweep(os.time())
    end

    local ms = IDLE_TICK_INTERVAL_MS
    local busy_left = pace:get("busy_left")
    if busy_left ~= nil and busy_left > 0 then
        ms = BUSY_TICK_INTERVAL_MS
        pace:set("busy_left", busy_left - 1)
    end
    os.sleep(ms)
    return 0
end

function fini()
    print("DoudizhuServer: shutdown")
    return 0
end

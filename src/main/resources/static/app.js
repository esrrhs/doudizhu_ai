// 斗地主网页端游戏逻辑与交互控制 (PIMC 2v1 非对称博弈、底牌采样叫地主与多会话支持)
document.addEventListener("DOMContentLoaded", () => {
    let currentState = null;
    let selectedCards = [];
    let isAiStepInProgress = false;

    // 多用户独立 Session ID
    let sessionId = localStorage.getItem("farmer_session_id");
    if (!sessionId) {
        sessionId = "sess_" + Date.now() + "_" + Math.random().toString(36).substring(2, 9);
        localStorage.setItem("farmer_session_id", sessionId);
    }

    // 封装带 Session 头的 fetch 请求
    async function sessionFetch(url, options = {}) {
        options.headers = options.headers || {};
        options.headers["X-Session-Id"] = sessionId;
        return fetch(url, options);
    }

    // DOM 元素引用
    const p1CardCount = document.getElementById("p1-card-count");
    const p2CardCount = document.getElementById("p2-card-count");
    const p0CardCount = document.getElementById("p0-card-count");
    const p1Status = document.getElementById("p1-status");
    const p2Status = document.getElementById("p2-status");

    const player1Box = document.getElementById("player-1");
    const player2Box = document.getElementById("player-2");

    const p0RoleBadge = document.getElementById("p0-role-badge");
    const p1RoleBadge = document.getElementById("p1-role-badge");
    const p2RoleBadge = document.getElementById("p2-role-badge");

    const bottomCardsLabel = document.getElementById("bottom-cards-label");
    const bottomCardsContainer = document.getElementById("bottom-cards-container");

    // 各玩家专属出牌/过牌/叫牌展示区
    const p0ActionArea = document.getElementById("p0-action-area");
    const p1ActionArea = document.getElementById("p1-action-area");
    const p2ActionArea = document.getElementById("p2-action-area");

    const trickStatusText = document.getElementById("trick-status-text");
    const turnIndicator = document.getElementById("turn-indicator");

    // 控制区
    const biddingControls = document.getElementById("bidding-controls");
    const btnCallLandlord = document.getElementById("btn-call-landlord");
    const btnPassLandlord = document.getElementById("btn-pass-landlord");

    const playingControls = document.getElementById("playing-controls");
    const humanHand = document.getElementById("human-hand");
    const btnPlay = document.getElementById("btn-play");
    const btnHint = document.getElementById("btn-hint");
    const btnPass = document.getElementById("btn-pass");

    const btnNewBidding = document.getElementById("btn-new-bidding");
    const btnQuickLandlord = document.getElementById("btn-quick-landlord");
    const btnQuickFarmer = document.getElementById("btn-quick-farmer");
    const btnRules = document.getElementById("btn-rules");

    const p0Avatar = document.getElementById("p0-avatar");
    const p1Avatar = document.getElementById("p1-avatar");
    const p2Avatar = document.getElementById("p2-avatar");

    const p1ThoughtPopover = document.getElementById("p1-thought-popover");
    const p1PopoverTime = document.getElementById("p1-popover-time");
    const p1PopoverBody = document.getElementById("p1-popover-body");

    const p2ThoughtPopover = document.getElementById("p2-thought-popover");
    const p2PopoverTime = document.getElementById("p2-popover-time");
    const p2PopoverBody = document.getElementById("p2-popover-body");

    const toastContainer = document.getElementById("toast-container");
    const cardCounter = document.getElementById("card-counter");
    const eventLogs = document.getElementById("event-logs");

    const modalOverlay = document.getElementById("modal-overlay");
    const modalTitle = document.getElementById("modal-title");
    const modalMessage = document.getElementById("modal-message");
    const modalBtnRestart = document.getElementById("modal-btn-restart");

    // 规则弹窗
    const rulesModal = document.getElementById("rules-modal");
    const rulesCloseIcon = document.getElementById("rules-close-icon");
    const btnRulesConfirm = document.getElementById("btn-rules-confirm");

    // 初始化加载
    fetchState();

    // 绑定交互事件
    btnCallLandlord.addEventListener("click", () => handleBid(true));
    btnPassLandlord.addEventListener("click", () => handleBid(false));

    btnPlay.addEventListener("click", handlePlay);
    btnPass.addEventListener("click", handlePass);
    btnHint.addEventListener("click", handleHint);

    btnNewBidding.addEventListener("click", () => handleNewGame("bidding"));
    btnQuickLandlord.addEventListener("click", () => handleNewGame("direct", 0));
    btnQuickFarmer.addEventListener("click", () => handleNewGame("direct", 1));

    modalBtnRestart.addEventListener("click", () => handleNewGame("bidding"));

    // 规则弹窗事件
    btnRules.addEventListener("click", () => rulesModal.classList.remove("hidden"));
    rulesCloseIcon.addEventListener("click", () => rulesModal.classList.add("hidden"));
    btnRulesConfirm.addEventListener("click", () => rulesModal.classList.add("hidden"));

    async function fetchState() {
        try {
            const res = await sessionFetch("/api/game/state");
            const data = await res.json();
            updateUI(data);
        } catch (err) {
            console.error("Failed to fetch game state:", err);
        }
    }

    async function handleNewGame(mode = "bidding", landlordId = 0) {
        try {
            modalOverlay.classList.add("hidden");
            selectedCards = [];
            let url = "/api/game/new";
            if (mode === "direct") {
                url += `?mode=direct&landlordId=${landlordId}`;
            }
            const res = await sessionFetch(url, { method: "POST" });
            const data = await res.json();
            updateUI(data);
        } catch (err) {
            console.error("Failed to start new game:", err);
        }
    }

    async function handleBid(call) {
        try {
            const res = await sessionFetch("/api/game/bid", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ call: call })
            });
            const data = await res.json();
            if (data.error) {
                alert("⚠️ " + data.error);
            } else {
                updateUI(data);
            }
        } catch (err) {
            console.error("Bid error:", err);
        }
    }

    async function handlePlay() {
        if (selectedCards.length === 0) return;
        try {
            const res = await sessionFetch("/api/game/play", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ cards: selectedCards })
            });
            const data = await res.json();
            if (data.error) {
                showToast("⚠️ " + data.error, "warning");
            } else {
                selectedCards = [];
                updateUI(data);
            }
        } catch (err) {
            console.error("Play card error:", err);
            showToast("⚠️ 网络请求异常", "warning");
        }
    }

    async function handlePass() {
        try {
            const res = await sessionFetch("/api/game/pass", { method: "POST" });
            const data = await res.json();
            if (data.error) {
                showToast("⚠️ " + data.error, "warning");
            } else {
                selectedCards = [];
                updateUI(data);
            }
        } catch (err) {
            console.error("Pass error:", err);
            showToast("⚠️ 网络请求异常", "warning");
        }
    }

    // 轻量级 Tips 提示 (替代浏览器 alert 弹窗)
    function showToast(message, type = "info") {
        if (!toastContainer) return;
        const toast = document.createElement("div");
        toast.className = `toast-item ${type}`;
        toast.innerHTML = message;
        toastContainer.appendChild(toast);
        setTimeout(() => {
            if (toast.parentNode) {
                toast.parentNode.removeChild(toast);
            }
        }, 2600);
    }

    async function handleHint() {
        try {
            const res = await sessionFetch("/api/game/hint");
            const data = await res.json();
            if (!data.hint || data.hint.length === 0) {
                if (data.isPass) {
                    showToast("💡 AI 建议：当前上家牌力压制，要不起，建议【过牌 (PASS)】", "warning");
                } else {
                    showToast("💡 暂无可用出牌建议", "info");
                }
                return;
            }

            selectedCards = [...data.hint];
            renderHand(currentState.humanHand);
            btnPlay.disabled = false;
            showToast(`💡 AI 走法推荐: <strong>${data.hint.join(", ")}</strong>`, "info");
        } catch (err) {
            console.error("Hint error:", err);
            showToast("⚠️ 获取提示失败", "warning");
        }
    }

    // 核心 UI 更新与轮次调度
    function updateUI(state) {
        currentState = state;

        // 更新各玩家剩余牌数与角色
        p0CardCount.textContent = state.cardCounts[0];
        p1CardCount.textContent = state.cardCounts[1];
        p2CardCount.textContent = state.cardCounts[2];

        updateRoleBadges(state.roles, state.landlordId, state.stage);

        // 渲染 3 张底牌 (盖牌或亮牌)
        renderBottomCards(state.bottomCards, state.bottomCardsRevealed);

        // 更新人类手牌
        renderHand(state.humanHand);

        // 根据所处阶段调度
        if (state.stage === "BIDDING") {
            handleBiddingStageUI(state);
        } else {
            handlePlayingStageUI(state);
        }

        // 渲染侧边栏数据
        renderCardCounter(state.playedStats);
        renderAiThoughts(state.aiThoughts, state.roles, state.stage);
        renderLogs(state.logs);
    }

    function handleBiddingStageUI(state) {
        biddingControls.classList.remove("hidden");
        playingControls.classList.add("hidden");
        bottomCardsLabel.textContent = "三张底牌 (待揭晓)";
        trickStatusText.textContent = "叫地主阶段";
        thoughtPanelTitle.textContent = "🔍 AI 叫牌思考 (底牌蒙特卡洛采样)";

        const isHumanBidTurn = (state.currentBidder === 0);
        btnCallLandlord.disabled = !isHumanBidTurn;
        btnPassLandlord.disabled = !isHumanBidTurn;

        // 高亮当前叫牌玩家
        player1Box.classList.toggle("active-turn", state.currentBidder === 1);
        player2Box.classList.toggle("active-turn", state.currentBidder === 2);

        p1Status.textContent = (state.currentBidder === 1) ? "思考中..." : "等待中";
        p1Status.classList.toggle("thinking", state.currentBidder === 1);
        p2Status.textContent = (state.currentBidder === 2) ? "思考中..." : "等待中";
        p2Status.classList.toggle("thinking", state.currentBidder === 2);

        // 面前展示叫牌决定
        renderBiddingActions(state.bidActions);

        if (isHumanBidTurn) {
            turnIndicator.textContent = "👉 轮到你表态：手牌是否符合心意？请选择【叫地主】或【不叫】";
        } else {
            turnIndicator.textContent = `⏳ 玩家 P${state.currentBidder} (AI) 正在采样未知底牌推演地主胜率...`;
            if (!isAiStepInProgress) {
                triggerAiTurn();
            }
        }
    }

    function handlePlayingStageUI(state) {
        biddingControls.classList.add("hidden");
        playingControls.classList.remove("hidden");
        bottomCardsLabel.textContent = "三张底牌 (地主所得)";
        thoughtPanelTitle.textContent = "🔍 AI 出牌雷达 (PIMC 2v1 推演)";

        const isHumanTurn = (state.activePlayer === 0) && !state.isGameOver;
        btnPlay.disabled = !isHumanTurn || selectedCards.length === 0;
        btnHint.disabled = !isHumanTurn;
        btnPass.disabled = !isHumanTurn || !state.canPass;

        player1Box.classList.toggle("active-turn", state.activePlayer === 1);
        player2Box.classList.toggle("active-turn", state.activePlayer === 2);

        p1Status.textContent = (state.activePlayer === 1) ? "思考中..." : "等待中";
        p1Status.classList.toggle("thinking", state.activePlayer === 1);
        p2Status.textContent = (state.activePlayer === 2) ? "思考中..." : "等待中";
        p2Status.classList.toggle("thinking", state.activePlayer === 2);

        // 渲染出牌展示
        renderPlayerActions(state.playerActions, state.lastMove);

        if (state.isGameOver) {
            turnIndicator.textContent = "对局结束";
            showGameOverModal(state);
        } else if (isHumanTurn) {
            turnIndicator.textContent = "👉 轮到你出牌！请选择手牌打出或选择不出";
        } else {
            const activeRole = (state.roles && state.roles[state.activePlayer]) ? state.roles[state.activePlayer] : "";
            turnIndicator.textContent = `⏳ 玩家 P${state.activePlayer} (${activeRole}) 正在推演最佳出牌...`;
            if (!isAiStepInProgress) {
                triggerAiTurn();
            }
        }
    }

    function updateRoleBadges(roles, landlordId, stage) {
        const badges = [p0RoleBadge, p1RoleBadge, p2RoleBadge];
        const boxes = [null, player1Box, player2Box];
        const avatars = [p0Avatar, p1Avatar, p2Avatar];

        for (let i = 0; i < 3; i++) {
            const badge = badges[i];
            const avatar = avatars[i];
            if (stage === "BIDDING" || landlordId < 0) {
                badge.textContent = "待定";
                badge.className = "role-badge";
                if (boxes[i]) boxes[i].classList.remove("is-landlord");
                if (avatar) avatar.textContent = (i === 0) ? "👤" : "🤔";
            } else {
                const isLandlord = (i === landlordId);
                badge.textContent = isLandlord ? "👑 地主" : "🌾 农民";
                badge.className = "role-badge " + (isLandlord ? "role-landlord" : "role-farmer");
                if (boxes[i]) boxes[i].classList.toggle("is-landlord", isLandlord);
                if (avatar) avatar.textContent = isLandlord ? "🤠" : "🧑‍🌾";
            }
        }
    }

    function renderBottomCards(bottomCards, isRevealed) {
        bottomCardsContainer.innerHTML = "";
        if (!isRevealed || !bottomCards || bottomCards.length === 0) {
            // 显示 3 张盖住的底牌
            for (let i = 0; i < 3; i++) {
                const el = document.createElement("div");
                el.className = "card small covered";
                el.textContent = "🂠";
                bottomCardsContainer.appendChild(el);
            }
        } else {
            // 翻开亮牌
            bottomCards.forEach(cardSymbol => {
                const el = createCardElement(cardSymbol, true);
                bottomCardsContainer.appendChild(el);
            });
        }
    }

    function renderBiddingActions(bidActions) {
        const areaMap = [p0ActionArea, p1ActionArea, p2ActionArea];
        areaMap.forEach(area => area.innerHTML = "");

        if (!bidActions) return;
        bidActions.forEach((act, idx) => {
            if (!act) return;
            const container = areaMap[idx];
            const badge = document.createElement("div");
            if (act === "叫地主") {
                badge.className = "bid-badge call";
                badge.textContent = "👑 叫地主";
            } else {
                badge.className = "bid-badge pass";
                badge.textContent = "🙅 不叫";
            }
            container.appendChild(badge);
        });
    }

    async function triggerAiTurn() {
        isAiStepInProgress = true;
        setTimeout(async () => {
            try {
                const res = await sessionFetch("/api/game/ai-step", { method: "POST" });
                const data = await res.json();
                isAiStepInProgress = false;
                updateUI(data);
            } catch (err) {
                console.error("AI step error:", err);
                isAiStepInProgress = false;
            }
        }, 500);
    }

    function renderPlayerActions(actions, lastMove) {
        const areaMap = [p0ActionArea, p1ActionArea, p2ActionArea];
        areaMap.forEach(area => area.innerHTML = "");

        if (!lastMove || !lastMove.cards || lastMove.cards.length === 0) {
            trickStatusText.textContent = "桌面清空 / 自由出牌";
        } else {
            const leadName = (lastMove.playerId === 0) ? "你 (P0)" : `AI (P${lastMove.playerId})`;
            trickStatusText.textContent = `当前需压制: ${leadName} 的 [${lastMove.type}] (${lastMove.cards.length}张)`;
        }

        if (!actions) return;

        actions.forEach((act, idx) => {
            if (!act) return;
            const container = areaMap[idx];

            // 检查胜率特效 class
            let winRateAnimClass = "";
            if (currentState && currentState.aiThoughts && idx > 0) {
                const thoughtKey = (idx === 1) ? "p1" : "p2";
                const thought = currentState.aiThoughts[thoughtKey];
                if (thought && thought.type === "play" && thought.evals && thought.evals.length > 0) {
                    const topWinRate = thought.evals[0].winRate;
                    if (topWinRate >= 75.0) {
                        winRateAnimClass = "anim-win-high";
                    } else if (topWinRate <= 30.0) {
                        winRateAnimClass = "anim-win-low";
                    }
                }
            }

            if (act.isPass) {
                const badge = document.createElement("div");
                badge.className = `pass-badge ${winRateAnimClass}`;
                badge.textContent = "不出 / PASS";
                container.appendChild(badge);
            } else if (act.cards && act.cards.length > 0) {
                act.cards.forEach(cardSymbol => {
                    const el = createCardElement(cardSymbol, true);
                    if (winRateAnimClass) {
                        el.classList.add(winRateAnimClass);
                    }
                    container.appendChild(el);
                });

                if (act.type) {
                    const tag = document.createElement("span");
                    tag.className = "action-type-tag";
                    tag.textContent = act.type;
                    container.appendChild(tag);
                }
            }
        });
    }

    function renderHand(cards) {
        humanHand.innerHTML = "";
        const selectedMap = {};
        selectedCards.forEach(c => {
            selectedMap[c] = (selectedMap[c] || 0) + 1;
        });

        const activeSelectedMap = {};

        cards.forEach((cardSymbol) => {
            const el = createCardElement(cardSymbol, false);

            const needed = selectedMap[cardSymbol] || 0;
            const currentCount = activeSelectedMap[cardSymbol] || 0;
            if (currentCount < needed) {
                el.classList.add("selected");
                activeSelectedMap[cardSymbol] = currentCount + 1;
            }

            el.addEventListener("click", () => {
                if (currentState.stage !== "PLAYING" || currentState.activePlayer !== 0 || currentState.isGameOver) return;
                toggleCardSelection(cardSymbol, el);
            });

            humanHand.appendChild(el);
        });
    }

    function createCardElement(cardSymbol, isSmall = false) {
        const el = document.createElement("div");
        el.className = "card" + (isSmall ? " small" : "");

        let displayRank = cardSymbol;
        let suit = "♠️";

        if (cardSymbol === "RJ") {
            displayRank = "大王";
            suit = "👑";
            el.classList.add("joker-red");
        } else if (cardSymbol === "BJ") {
            displayRank = "小王";
            suit = "🃏";
            el.classList.add("joker-black");
        } else if (cardSymbol === "2" || cardSymbol === "A") {
            el.classList.add("red");
            suit = "♥️";
        }

        el.innerHTML = `
            <div class="card-corner">${displayRank}</div>
            <div class="card-center">${suit}</div>
            <div class="card-corner bottom">${displayRank}</div>
        `;
        return el;
    }

    function toggleCardSelection(cardSymbol, el) {
        const idx = selectedCards.indexOf(cardSymbol);
        if (idx >= 0 && el.classList.contains("selected")) {
            selectedCards.splice(idx, 1);
            el.classList.remove("selected");
        } else {
            selectedCards.push(cardSymbol);
            el.classList.add("selected");
        }
        btnPlay.disabled = selectedCards.length === 0;
    }

    function renderCardCounter(stats) {
        cardCounter.innerHTML = "";
        if (!stats) return;

        const totalStock = {
            "3": 4, "4": 4, "5": 4, "6": 4, "7": 4, "8": 4, "9": 4, "10": 4,
            "J": 4, "Q": 4, "K": 4, "A": 4, "2": 4, "BJ": 1, "RJ": 1
        };

        for (const [rank, playedCount] of Object.entries(stats)) {
            const max = totalStock[rank] || 4;
            const remaining = Math.max(0, max - playedCount);

            let label = rank;
            if (rank === "BJ") label = "小王";
            if (rank === "RJ") label = "大王";

            const el = document.createElement("div");
            el.className = "counter-item";
            el.innerHTML = `
                <span class="counter-rank">${label}</span>
                <span class="counter-value">${remaining}</span>
            `;
            cardCounter.appendChild(el);
        }
    }

    function renderAiThoughts(aiThoughts, roles, stage) {
        const p1Popover = p1ThoughtPopover;
        const p2Popover = p2ThoughtPopover;

        if (!aiThoughts || Object.keys(aiThoughts).length === 0) {
            if (p1Popover) p1Popover.classList.add("hidden");
            if (p2Popover) p2Popover.classList.add("hidden");
            return;
        }

        // 分别为 P1 和 P2 渲染桌面外侧的专属思考面板
        [ { id: 1, popover: p1Popover, timeEl: p1PopoverTime, bodyEl: p1PopoverBody, key: "p1" },
          { id: 2, popover: p2Popover, timeEl: p2PopoverTime, bodyEl: p2PopoverBody, key: "p2" }
        ].forEach(({ id, popover, timeEl, bodyEl, key }) => {
            if (!popover || !bodyEl) return;
            const thought = aiThoughts[key];
            if (!thought) {
                popover.classList.add("hidden");
                return;
            }

            popover.classList.remove("hidden");
            if (timeEl) timeEl.textContent = `${thought.timeMs || 0} ms`;

            const roleStr = (roles && roles[id] && roles[id] !== "待定") ? ` (${roles[id]})` : "";
            let html = "";

            if (thought.type === "bid") {
                const decisionText = thought.shouldCall ? "👑 叫地主" : "🙅 不叫";
                const decisionColor = thought.shouldCall ? "#f59e0b" : "#94a3b8";
                const widthPercent = Math.min(100, Math.max(5, thought.winRate));
                const controlScoreText = (thought.controlScore !== undefined) ? `<div>硬牌力分: <strong>${thought.controlScore}</strong></div>` : "";
                const summaryHtml = thought.summary ? `<div style="font-size: 11px; color: #94a3b8; margin-top: 4px;">📝 ${thought.summary}</div>` : "";

                html = `
                    <div style="font-weight: 600; color: #38bdf8; margin-bottom: 2px;">底牌采样推演</div>
                    <div>预估胜率: <strong style="color: #f59e0b;">${thought.winRate}%</strong> (${thought.sims} 世界)</div>
                    ${controlScoreText}
                    <div class="progress-bar-bg" style="margin: 4px 0;">
                        <div class="progress-bar-fill" style="width: ${widthPercent}%;"></div>
                    </div>
                    <div>表态: <strong style="color: ${decisionColor};">${decisionText}</strong></div>
                    ${summaryHtml}
                `;
            } else {
                let winRateAnimIcon = "";
                if (thought.evals && thought.evals.length > 0) {
                    const topRate = thought.evals[0].winRate;
                    if (topRate >= 75.0) winRateAnimIcon = "🔥";
                    else if (topRate <= 30.0) winRateAnimIcon = "💧";
                }

                html = `
                    <div style="font-weight: 600; color: #f59e0b; margin-bottom: 4px;">
                        ${winRateAnimIcon} 决策出牌: <strong>${thought.move}</strong>
                    </div>
                `;

                if (thought.evals && thought.evals.length > 0) {
                    thought.evals.forEach(ev => {
                        const widthPercent = Math.min(100, Math.max(5, ev.winRate));
                        html += `
                            <div style="font-size: 11px; margin-top: 4px; display: flex; justify-content: space-between;">
                                <span>${ev.cards}</span>
                                <span>${ev.winRate}% (${ev.visits})</span>
                            </div>
                            <div class="progress-bar-bg">
                                <div class="progress-bar-fill" style="width: ${widthPercent}%;"></div>
                            </div>
                        `;
                    });
                }
            }

            bodyEl.innerHTML = html;
        });
    }

    function renderLogs(logs) {
        if (!logs) return;
        eventLogs.innerHTML = "";
        logs.forEach(log => {
            const el = document.createElement("div");
            el.className = "log-entry";
            el.textContent = log;
            eventLogs.appendChild(el);
        });
        eventLogs.scrollTop = eventLogs.scrollHeight;
    }

    function showGameOverModal(state) {
        const isHumanWinner = state.isHumanWinner;
        const winnerId = state.winner;

        if (isHumanWinner) {
            modalTitle.textContent = "🎉 恭喜你获得胜利！";
            if (state.landlordId === 0) {
                modalMessage.textContent = "你作为地主率先出完手牌，成功抵挡住两名 AI 农民的联手封锁！";
            } else {
                modalMessage.textContent = `农民阵营大获全胜！你与队友 (玩家 P${3 - state.landlordId}) 默契配合，成功击败了地主！`;
            }
        } else {
            modalTitle.textContent = "💔 遗憾惜败";
            if (state.landlordId === 0) {
                modalMessage.textContent = "两名 AI 农民通力合作率先走完手牌，地主未能成功防守。";
            } else {
                modalMessage.textContent = `地主 (玩家 P${winnerId}) 率先走完全部手牌，农民阵营失守。再接再厉！`;
            }
        }
        modalOverlay.classList.remove("hidden");
    }
});

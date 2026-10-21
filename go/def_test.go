package doudizhu

import "testing"

// 状态机的定点检查,分支对应 Java GameStateTest 的场景:
// 地主先手、两家过牌后由上一轮最大出牌者重新获得出牌权、农民阵营同胜同负。

func mustMove(t *testing.T, cards string, playerID int) *Move {
	t.Helper()
	counts, err := ParseCards(cards)
	if err != nil {
		t.Fatalf("%s: %v", cards, err)
	}
	mv, err := ToMove(counts, playerID)
	if err != nil {
		t.Fatalf("%s: %v", cards, err)
	}
	return mv
}

func newTestState(t *testing.T, landlordID int) *State {
	t.Helper()
	hands := make([][RankSize]int32, 3)
	for i, s := range []string{"34", "55", "66"} {
		c, err := ParseCards(s)
		if err != nil {
			t.Fatal(err)
		}
		hands[i] = c
	}
	bottom, err := ParseCards("7")
	if err != nil {
		t.Fatal(err)
	}
	st, err := NewState(hands, landlordID, bottom)
	if err != nil {
		t.Fatal(err)
	}
	return st
}

func TestStateLandlordLeadsAndTrickResets(t *testing.T) {
	st := newTestState(t, 0)
	if st.ActivePlayerID() != 0 {
		t.Fatalf("应由地主先手, 实际 %d", st.ActivePlayerID())
	}
	if st.LastMove() != nil || st.PassCount() != 0 {
		t.Errorf("开局不应有待压的牌")
	}
	if got := st.Player(0).Hand().TotalCards(); got != 2 {
		t.Errorf("地主手牌应为 2 张, 实际 %d", got)
	}
	if st.PublicViewOf(0).CardCount(0) != 2 {
		t.Errorf("公开视角的张数应与手牌一致")
	}

	if err := st.ApplyMove(mustMove(t, "3", 0)); err != nil {
		t.Fatal(err)
	}
	if st.ActivePlayerID() != 1 || st.LastMove().ToCardString() != "3" {
		t.Fatalf("出牌后应由下家应牌, 实际 %d", st.ActivePlayerID())
	}
	if st.PublicViewOf(1).LastMove().PlayerID() != 0 {
		t.Errorf("下家看到的待压牌应来自地主")
	}

	_ = st.ApplyMove(mustMove(t, "-", 1))
	if st.PassCount() != 1 || st.ActivePlayerID() != 2 {
		t.Errorf("一家过牌后应轮到第三家")
	}
	_ = st.ApplyMove(mustMove(t, "-", 2))
	if st.PassCount() != 0 || st.LastMove() != nil {
		t.Errorf("两家过牌后本轮应结束")
	}
	if st.ActivePlayerID() != 0 {
		t.Errorf("应由上一轮最大出牌者重新出牌, 实际 %d", st.ActivePlayerID())
	}

	_ = st.ApplyMove(mustMove(t, "4", 0)) // 地主出完
	if !st.IsGameOver() || st.WinnerID() != 0 {
		t.Fatalf("地主出完手牌应立即结束, 实际 gameOver=%v winner=%d", st.IsGameOver(), st.WinnerID())
	}
	if !st.IsPlayerWinner(0) || st.IsPlayerWinner(1) || st.IsPlayerWinner(2) {
		t.Errorf("地主获胜时只有地主是赢家")
	}
	if err := st.ApplyMove(mustMove(t, "-", 1)); err == nil {
		t.Errorf("结束后不应还能出牌")
	}
}

func TestStateFarmerWinCountsForBothFarmers(t *testing.T) {
	st := newTestState(t, 0)
	_ = st.ApplyMove(mustMove(t, "3", 0))
	_ = st.ApplyMove(mustMove(t, "55", 1)) // 对子压单张不合法,但状态机不校验牌型
	if st.Player(1).Hand().TotalCards() != 0 {
		t.Fatalf("农民应出完手牌")
	}
	_ = st.ApplyMove(mustMove(t, "6", 2))
	if !st.IsGameOver() {
		t.Fatalf("农民出完手牌后应结束")
	}
	if st.IsPlayerWinner(1) != true || st.IsPlayerWinner(2) != true {
		t.Errorf("两名农民应同时算赢")
	}
	if st.IsPlayerWinner(0) {
		t.Errorf("地主应判负")
	}
}

func TestStateRejectsIllegalHand(t *testing.T) {
	st := newTestState(t, 1)
	if st.ActivePlayerID() != 1 {
		t.Fatalf("地主 1 应先手")
	}
	if err := st.ApplyMove(mustMove(t, "3", 1)); err == nil {
		t.Errorf("下家没有 3, 应拒绝这手牌")
	}
	if _, err := NewState([][RankSize]int32{{}, {}, {}, {}}, 0, blank()); err == nil {
		t.Errorf("四家手牌应报错")
	}
	if _, err := NewState([][RankSize]int32{{}, {}, {}}, 3, blank()); err == nil {
		t.Errorf("非法地主座位应报错")
	}
}

// 玩家视角必须隔离私有手牌。
func TestPublicViewIsolation(t *testing.T) {
	st := newTestState(t, 0)
	view := st.PublicViewOf(1)
	if view.MyHand().ToCardString() != "55" {
		t.Errorf("1 号玩家看到的应是自己的牌, 实际 %s", view.MyHand().ToCardString())
	}
	if view.ViewingRole() != RoleFarmer || st.PublicViewOf(0).ViewingRole() != RoleLandlord {
		t.Errorf("阵营应正确")
	}
	if _, err := Observe(view); err == nil {
		t.Errorf("非行动者的视角不应能构造观察")
	}
	if got := st.PublicViewOf(2).ActivePlayerID(); got != 0 {
		t.Errorf("行动者应为地主, 实际 %d", got)
	}
}

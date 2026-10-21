package doudizhu

import "testing"

// 牌型判定的定点检查;用例对应 Java PerfectDouMoves.detect 的各分支。

func TestDetect(t *testing.T) {
	cases := []struct {
		cards  string
		mtType int
		rank   int
		length int
	}{
		{"-", MTPass, -1, 1},
		{"3", MTSingle, 0, 1},
		{"R", MTSingle, 14, 1},
		{"33", MTPair, 0, 1},
		{"22", MTPair, 12, 1},
		{"BR", MTRocket, -1, 1},
		{"3B", MTWrong, -1, 1}, // 两张不同的牌既不成对也不是火箭
		{"333", MTTriple, 0, 1},
		{"3333", MTBomb, 0, 1},
		{"3334", MTTripleOne, 0, 1},
		{"33344", MTTripleTwo, 0, 1},
		{"3456", MTWrong, -1, 1},       // 只有 4 张,连不成顺
		{"34567", MTStraight, 0, 5},    // 顺子
		{"TJQKA", MTStraight, 7, 5},    // T..A 连续
		{"JQKA2", MTWrong, -1, 1},      // A 到 2 被点数差切断
		{"3456789T", MTStraight, 0, 8}, // 先判连续,8 张也不按四带两对归类
		{"334455", MTPairStraight, 0, 3},
		{"333444", MTPlane, 0, 2},
		{"333444555", MTPlane, 0, 3},
		{"33344456", MTPlaneSolo, 0, 2},
		{"3334445566", MTPlanePair, 0, 2},
		{"444433", MTFourTwo, 1, 1}, // 四带两单: 允许用一对当两张单
		{"44445566", MTFourTwoPair, 1, 1},
		{"33334444", MTFourTwoPair, 1, 1}, // 两个炸弹也按四带两对归类
		{"33334", MTWrong, -1, 1},         // 炸弹带一张不成立
	}
	for _, c := range cases {
		counts, err := ParseCards(c.cards)
		if err != nil {
			t.Fatalf("%s: %v", c.cards, err)
		}
		mtType, rank, length := Detect(counts)
		if mtType != c.mtType || rank != c.rank || length != c.length {
			t.Errorf("%s: 期望 (%d,%d,%d), 实际 (%d,%d,%d)", c.cards,
				c.mtType, c.rank, c.length, mtType, rank, length)
		}
	}
}

// 45 位指纹要能唯一表示一个多重集。
func TestKeyIsFingerprint(t *testing.T) {
	a, _ := ParseCards("34567")
	b, _ := ParseCards("34567")
	c, _ := ParseCards("34568")
	if Key(a) != Key(b) {
		t.Errorf("同一手牌的指纹应相同")
	}
	if Key(a) == Key(c) {
		t.Errorf("不同手牌的指纹不应相同")
	}
	if uint64(Key(a)) >= 1<<45 {
		t.Errorf("指纹超出 45 位: %d", Key(a))
	}
}

// 自由出牌与应牌的动作集合形态。
func TestLegalShape(t *testing.T) {
	hand, _ := ParseCards("3445566789TJQKA2BR")

	lead := Legal(hand, blank())
	if len(lead) == 0 {
		t.Fatalf("自由出牌不应没有动作")
	}
	for _, m := range lead {
		if m == blank() {
			t.Errorf("自由出牌不应包含过牌")
		}
		if mtType, _, _ := Detect(m); mtType == MTWrong {
			t.Errorf("候选 %s 无法归类", DebugCards(m))
		}
		for r := 0; r < RankSize; r++ {
			if m[r] > hand[r] {
				t.Errorf("候选 %s 超出了手牌", DebugCards(m))
			}
		}
	}
	if got := len(keySetOf(lead)); got != len(lead) {
		t.Errorf("候选动作有重复: %d 个候选, %d 个指纹", len(lead), got)
	}

	toBeat, _ := ParseCards("3")
	follow := Legal(hand, toBeat)
	if last := follow[len(follow)-1]; last != blank() {
		t.Errorf("应牌时最后一项必须是过牌, 实际 %s", DebugCards(last))
	}
	for _, m := range follow[:len(follow)-1] {
		mtType, _, _ := Detect(m)
		if mtType != MTSingle && mtType != MTBomb && mtType != MTRocket {
			t.Errorf("压单张只应出现单张/炸弹/火箭, 实际 %s(%d)", DebugCards(m), mtType)
		}
	}

	// 对手出火箭: 唯一动作是过牌
	if got := Legal(hand, [RankSize]int32{13: 1, 14: 1}); len(got) != 1 || got[0] != blank() {
		t.Errorf("面对火箭只能过牌, 实际 %d 个动作", len(got))
	}
}

// 压牌过滤与带牌枚举的具体结果。
func TestLegalEnumeration(t *testing.T) {
	cases := []struct {
		hand   string
		toBeat string
		want   []string
	}{
		{"4455999", "3", []string{"4", "5", "9", "-"}},
		{"4455999", "4", []string{"5", "9", "-"}},
		{"4455999", "9", []string{"-"}},
		{"4455999", "33", []string{"44", "55", "99", "-"}},
		{"4455999", "55", []string{"99", "-"}},
		{"4455999", "333", []string{"999", "-"}},
		{"456789", "34567", []string{"45678", "56789", "-"}},
		{"456789", "345678", []string{"456789", "-"}},
		{"345567", "34567", []string{"-"}},                  // 唯一的 34567 压不过同点数的对手
		{"33445", "-", []string{"3", "4", "5", "33", "44"}}, // 3 张的连对/3 张的顺子都不够长
	}
	for _, c := range cases {
		h, err := ParseCards(c.hand)
		if err != nil {
			t.Fatalf("%s: %v", c.hand, err)
		}
		toBeat, err := ParseCards(c.toBeat)
		if err != nil {
			t.Fatalf("%s: %v", c.toBeat, err)
		}
		got := Legal(h, toBeat)
		if len(got) != len(c.want) {
			t.Errorf("%s 应 %s: 期望 %d 个动作 %v, 实际 %d 个",
				c.hand, c.toBeat, len(c.want), c.want, len(got))
			continue
		}
		want := keySetOfStr(c.want)
		for _, m := range got {
			if !want[Key(m)] {
				t.Errorf("%s 应 %s: 多出了 %s", c.hand, c.toBeat, DebugCards(m))
			}
		}
	}

	// 飞机带翅要枚举每一种具体带牌组合(引擎自带的 MoveGenerator 只给一种)
	h, _ := ParseCards("333444567")
	found := keySetOf(Legal(h, blank()))
	for _, want := range []string{"33344456", "33344457", "33344467"} {
		c, err := ParseCards(want)
		if err != nil {
			t.Fatalf("%s: %v", want, err)
		}
		if !found[Key(c)] {
			t.Errorf("候选里应有 %s", want)
		}
	}
	for _, absent := range []string{"33344455", "3334445566", "3334445678"} {
		c, _ := ParseCards(absent)
		if found[Key(c)] {
			t.Errorf("手上没有这些牌, 候选里不应有 %s", absent)
		}
	}

	// 四带两单要枚举所有带牌组合(引擎自带的 MoveGenerator 不允许用一对当两单)
	h2, _ := ParseCards("44445678")
	found2 := keySetOf(Legal(h2, blank()))
	for _, want := range []string{"444456", "444457", "444458", "444467", "444468", "444478"} {
		c, err := ParseCards(want)
		if err != nil {
			t.Fatalf("%s: %v", want, err)
		}
		if !found2[Key(c)] {
			t.Errorf("候选里应有 %s", want)
		}
	}
	// 一对当两单: 4444 + 55 也在候选里(引擎侧的 MoveGenerator 不会给出)
	h3, _ := ParseCards("4444556")
	found3 := keySetOf(Legal(h3, blank()))
	if c, err := ParseCards("444455"); err != nil || !found3[Key(c)] {
		t.Errorf("候选里应有 444455(四带两单允许带一对)")
	}
	if c, err := ParseCards("444456"); err != nil || !found3[Key(c)] {
		t.Errorf("候选里应有 444456")
	}
}

func keySetOf(options [][RankSize]int32) map[int64]bool {
	out := make(map[int64]bool, len(options))
	for _, m := range options {
		out[Key(m)] = true
	}
	return out
}

func keySetOfStr(cards []string) map[int64]bool {
	out := make(map[int64]bool, len(cards))
	for _, s := range cards {
		c, err := ParseCards(s)
		if err != nil {
			panic(err)
		}
		out[Key(c)] = true
	}
	return out
}

// 多重集 -> 引擎 Move 的互转,链牌的 mainRank 用链的最大点数。
func TestToMove(t *testing.T) {
	cases := []struct {
		cards    string
		cardType CardType
		mainRank int
	}{
		{"-", CardPass, 0},
		{"3", CardSingle, 3},
		{"R", CardSingle, 17},
		{"BR", CardRocket, 17},
		{"3333", CardBomb, 3},
		{"34567", CardStraight, 7},
		{"TJQKA", CardStraight, 14},
		{"334455", CardConsecutivePairs, 5},
		{"333444", CardAirplane, 4},
		{"33344456", CardAirplanePlusSingles, 4},
		{"3334445566", CardAirplanePlusPairs, 4},
		{"3334", CardTriplePlusOne, 3},
	}
	for _, c := range cases {
		counts, err := ParseCards(c.cards)
		if err != nil {
			t.Fatalf("%s: %v", c.cards, err)
		}
		mv, err := ToMove(counts, 1)
		if err != nil {
			t.Fatalf("%s: %v", c.cards, err)
		}
		if mv.Type() != c.cardType {
			t.Errorf("%s: 牌型应为 %v, 实际 %v", c.cards, c.cardType, mv.Type())
		}
		if mv.MainRank() != c.mainRank {
			t.Errorf("%s: mainRank 应为 %d, 实际 %d", c.cards, c.mainRank, mv.MainRank())
		}
		if mv.PlayerID() != 1 {
			t.Errorf("%s: 出牌者应为 1", c.cards)
		}
		if !mv.IsPass() && mv.CardCount() != Total(counts) {
			t.Errorf("%s: 张数应为 %d, 实际 %d", c.cards, Total(counts), mv.CardCount())
		}
	}
	if _, err := ToMove([RankSize]int32{0: 1, 1: 1}, 0); err == nil {
		t.Errorf("无法归类的牌应报错")
	}
}

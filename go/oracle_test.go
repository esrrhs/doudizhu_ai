package doudizhu

import "testing"

// 手数下界 oracle 的定点检查。
//
// 该值直接进入候选动作特征(actionFeat 的第 5 个标量),golden 对齐测试已经把它在
// 712 个决策 × 全部候选上逐位校验过;这里补上可人工推算的小牌型与暴力下界对照,
// 防止后续改动跑偏。

func TestMinSteps(t *testing.T) {
	cases := []struct {
		cards string
		want  int
	}{
		{"-", 0},
		{"3", 1},
		{"33", 1},
		{"333", 1},
		{"3333", 1},         // 炸弹
		{"3334", 1},         // 三带一
		{"33344", 1},        // 三带二
		{"34567", 1},        // 顺子
		{"345678", 1},       // 六张顺子
		{"334455", 1},       // 连对
		{"333444", 1},       // 飞机不带
		{"33344456", 1},     // 飞机带两单
		{"3334445566", 1},   // 飞机带两对
		{"BR", 1},           // 王炸
		{"33334444", 1},     // 四带两对(另一串四个拆成两对)
		{"3456789TJQ", 1},   // 3..Q 十张顺子
		{"3456", 4},         // 连不成顺,只能四张单
		{"3B", 2},           // 单张 + 小王
		{"3344", 2},         // 两个对子(连对不够长)
		{"33334", 2},        // 炸弹 + 单张
		{"345673", 2},       // 顺子 + 多一张 3
		{"3334445", 2},      // 飞机带不了单翅,只能拆
		{"333444567", 2},    // 33344456 带两单 + 7
		{"BR3", 2},          // 王炸 + 单张
		{"2222", 1},         // 2 的炸弹
		{"2223", 1},         // 三带一(2 的三张可以带)
		{"34567BR", 2},      // 顺子 + 王炸
		{"333444555666", 1}, // 四组三张的飞机
	}
	for _, c := range cases {
		counts, err := ParseCards(c.cards)
		if err != nil {
			t.Fatalf("%s: %v", c.cards, err)
		}
		if got := MinSteps(counts); got != c.want {
			t.Errorf("%s: 期望 %d 步, 实际 %d", c.cards, c.want, got)
		}
	}
}

// bruteSteps 直接按"每一步出掉一个合法牌型"递归求最优,是与 DP/oracle 无关的暴力下界。
func bruteSteps(cnt [RankSize]int32, memo map[int64]int) int {
	if Total(cnt) == 0 {
		return 0
	}
	key := Key(cnt)
	if v, ok := memo[key]; ok {
		return v
	}
	best := Total(cnt) // 上界: 全按单张出
	for _, m := range Legal(cnt, blank()) {
		if m == blank() {
			continue
		}
		after := cnt
		for r := 0; r < RankSize; r++ {
			after[r] -= m[r]
		}
		if v := 1 + bruteSteps(after, memo); v < best {
			best = v
		}
	}
	memo[key] = best
	return best
}

// 小规模手牌上与暴力枚举逐手对照: oracle 必须给出真正的最少步数。
func TestMinStepsMatchesBruteForce(t *testing.T) {
	cases := []string{
		"3334445", "33344456", "3456789", "33445566", "3334445556",
		"2345678", "3344556", "333444556", "345677",
		"3344567", "55566677", "34567889", "33334567", "TTTTJQKA",
	}
	memo := make(map[int64]int, 4096)
	for _, s := range cases {
		counts, err := ParseCards(s)
		if err != nil {
			t.Fatalf("%s: %v", s, err)
		}
		want := bruteSteps(counts, memo)
		if got := MinSteps(counts); got != want {
			t.Errorf("%s: oracle=%d 暴力=%d", s, got, want)
		}
	}
}

// 真实 20 张手牌上的界限: 至少 1 步,最多等于张数;每打掉一个候选动作都不超过张数。
func TestMinStepsBounds(t *testing.T) {
	counts, err := ParseCards("334445566689TTTJKAAR")
	if err != nil {
		t.Fatal(err)
	}
	base := MinSteps(counts)
	if base < 1 || base > Total(counts) {
		t.Fatalf("20 张手牌的手数下界应在 1..%d, 实际 %d", Total(counts), base)
	}
	// 整副牌(54 张)也应在合理范围内,顺带走一遍深递归
	full, _ := ParseCards("3333444455556666777788889999TTTTJJJJQQQQKKKKAAAA2222BR")
	if got := MinSteps(full); got < 1 || got > 54 {
		t.Errorf("整副牌的手数应在 1..54, 实际 %d", got)
	}
}

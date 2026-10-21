package doudizhu

import "testing"

// 座位映射与牌矩阵的定点检查,用例移植自 Java PerfectDouFeaturesTest。
//
// golden 对齐测试里地主恒为 seat 0, 覆盖不到「地主不是 0 号玩家」时的 actor 选择, 这里补上。

func TestActorIndexFollowsLandlordSeat(t *testing.T) {
	// 出牌顺序恒为 0 -> 1 -> 2 -> 0, 训练侧 seat: 0=地主, 1=地主下家, 2=地主上家
	for landlordID := 0; landlordID < 3; landlordID++ {
		if got := ActorIndex(landlordID, landlordID); got != 0 {
			t.Errorf("地主 %d 的 actor 应为 0, 实际 %d", landlordID, got)
		}
		if got := ActorIndex((landlordID+1)%3, landlordID); got != 1 {
			t.Errorf("地主 %d 的下家 actor 应为 1, 实际 %d", landlordID, got)
		}
		if got := ActorIndex((landlordID+2)%3, landlordID); got != 2 {
			t.Errorf("地主 %d 的上家 actor 应为 2, 实际 %d", landlordID, got)
		}
	}
}

func TestCardMatrixMarksExpectedRows(t *testing.T) {
	out := make([]float32, CardMatrix)

	// KKKK: 张数 4 -> 行 0..3 点亮, 行 7(炸弹) 点亮; K 的索引是 10
	cardMatrix(counts(10, 4), out, 0)
	for row := 0; row < 4; row++ {
		if out[row*RankSize+10] != 1 {
			t.Errorf("第%d行应点亮", row)
		}
	}
	for _, row := range []int{rowSolo, rowPair, rowTrio, rowBomb} {
		if out[row*RankSize+10] != 1 {
			t.Errorf("行 %d 应点亮", row)
		}
	}

	// 王炸点亮行 8 的两个王牌列
	cardMatrix(counts(13, 1, 14, 1), out, 0)
	if out[rowRocket*RankSize+13] != 1 || out[rowRocket*RankSize+14] != 1 {
		t.Errorf("王炸应点亮两张王牌")
	}

	// 34567 顺子: 行 9(单张链) 对这五个点数都点亮
	cardMatrix(counts(0, 1, 1, 1, 2, 1, 3, 1, 4, 1), out, 0)
	for r := 0; r <= 4; r++ {
		if out[rowSoloChain*RankSize+r] != 1 {
			t.Errorf("链上的点数 %d 应点亮", r)
		}
	}
	if out[rowPairChain*RankSize+2] != 0 {
		t.Errorf("连对不成立")
	}
	// 矩阵外的部分必须保持为 0
	if out[rowTrioChain*RankSize] != 0 {
		t.Errorf("不该有飞机链")
	}
}

// 点数索引即 Java Rank.getValue() - 3。
func TestRankIndexMatchesTrainingSide(t *testing.T) {
	cases := []struct {
		value int
		index int
	}{
		{3, 0}, {14, 11}, {15, 12}, {16, 13}, {17, 14},
	}
	for _, c := range cases {
		if got := c.value - 3; got != c.index {
			t.Errorf("点数数值 %d 的索引应为 %d, 实际 %d", c.value, c.index, got)
		}
	}
	if JavaRankValues[0] != 3 || JavaRankValues[14] != 17 {
		t.Errorf("点数数值表应为 3..17")
	}
	if RankValues[12] != 17 || RankValues[13] != 20 || RankValues[14] != 30 {
		t.Errorf("训练侧点数值应为 2=17, 小王=20, 大王=30")
	}
}

// counts(10, 4, 13, 1, ...) 这种成对写法,等价 Java 测试里的 counts(int...)。
func counts(pairs ...int) [RankSize]int32 {
	var c [RankSize]int32
	for i := 0; i < len(pairs); i += 2 {
		c[pairs[i]] = int32(pairs[i+1])
	}
	return c
}

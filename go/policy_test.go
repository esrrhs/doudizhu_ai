package doudizhu

import (
	"path/filepath"
	"strings"
	"testing"
)

// 推理入口的冒烟测试: 端到端跑一个真实决策,并检查动作确实来自完整枚举的候选集。

func TestPolicyDecideOnFirstTurn(t *testing.T) {
	reader := openGolden(t)
	defer reader.close()
	for reader.hasNext() {
		if reader.nextToken() == "DDZGOLDEN" {
			reader.skipLine()
			continue
		}
		break
	}
	game := reader.readGoldenGame()
	st, err := NewState(game.hands, game.landlordID, game.bottom)
	if err != nil {
		t.Fatal(err)
	}

	view := st.PublicViewOf(st.ActivePlayerID())
	mv, scores, err := testPolicy.Decide(view)
	if err != nil {
		t.Fatalf("决策失败: %v", err)
	}
	observed, err := Observe(view)
	if err != nil {
		t.Fatal(err)
	}
	options := Legal(observed.MyHandCounts(), blank())
	if len(scores) != len(options) {
		t.Errorf("得分个数 %d 应等于候选个数 %d", len(scores), len(options))
	}
	chosen := countsOf(mv)
	found := false
	for _, m := range options {
		if m == chosen {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("选出的 %s 不在候选集中", DebugCards(chosen))
	}
	if observed.ActorIndex() != ActorIndex(view.ViewingPlayerID(), game.landlordID) {
		t.Errorf("actor 编号应为 (playerId-landlordId+3)%%3")
	}
}

// 行动者手牌为空时应直接过牌,而不是送进网络。
func TestPolicyDecideWithEmptyHand(t *testing.T) {
	st := newTestState(t, 0)
	_ = st.ApplyMove(mustMove(t, "3", 0))
	_ = st.ApplyMove(mustMove(t, "-", 1))
	_ = st.ApplyMove(mustMove(t, "-", 2)) // 两家过牌,出牌权回到地主
	_ = st.ApplyMove(mustMove(t, "4", 0)) // 地主出完
	view := st.PublicViewOf(0)
	mv, _, err := testPolicy.Decide(view)
	if err != nil {
		t.Fatalf("决策失败: %v", err)
	}
	if !mv.IsPass() {
		t.Errorf("空手牌应过牌, 实际 %s", mv)
	}
}

func TestPolicyScoreValidatesInput(t *testing.T) {
	xImp := make([]float32, NodeSize)
	seq := make([]float32, SeqSize)
	feats := make([]float32, ActionSize)
	if _, err := testPolicy.Score(3, xImp, seq, feats, 1); err == nil {
		t.Errorf("越界的 actor 编号应报错")
	}
	if _, err := testPolicy.Score(0, xImp[:10], seq, feats, 1); err == nil {
		t.Errorf("xImp 太短应报错")
	}
	if _, err := testPolicy.Score(0, xImp, seq[:10], feats, 1); err == nil {
		t.Errorf("seq 太短应报错")
	}
	if _, err := testPolicy.Score(0, xImp, seq, feats[:10], 1); err == nil {
		t.Errorf("actionFeat 不够长应报错")
	}
	if got, err := testPolicy.Score(0, xImp, seq, feats, 0); err != nil || len(got) != 0 {
		t.Errorf("0 个候选应返回空得分, 实际 %v %v", got, err)
	}
}

// 权重缺失时要给出可操作的报错,而不是静默跳过。
func TestLoadPolicyMissingDirectory(t *testing.T) {
	_, err := LoadPolicy(filepath.Join("this", "directory", "does", "not", "exist"))
	if err == nil || !strings.Contains(err.Error(), "manifest") {
		t.Errorf("应报缺少清单, 实际 %v", err)
	}
}

package doudizhu

import (
	"fmt"
	"math"
	"os"
	"strings"
	"testing"
	"time"
)

// 用 PerfectDouCPP 侧导出的 golden 记录逐位校验 Go 移植(跨语言功能对齐契约)。
//
// golden 由 tools/pddump.cpp 生成: 每局给出发牌与底牌,每个决策给出 C++ 的 xImp / seq /
// 全部合法动作及其 actionFeat / actor 的 logits / 实际所选动作。
// Go 侧从同一副牌起用自己的状态机回放,因此这里同时校验候选动作枚举、观察构造、牌矩阵、
// 手数 oracle 与手写前向。
//
// 判定标准(与 Java 侧的两个 golden 测试一致,不许放宽):
//  1. 候选动作集合与 golden 作为 45 位多重集完全相等(双向);
//  2. xImp / seq / 每个 actionFeat 逐位相等;
//  3. logits 绝对偏差 < 1e-3 且 argmax 恒等于 golden 的 CHOSEN 槽位。

// testPolicy 由 TestMain 加载一次,所有测试共享(权重只读,可并发)。
var testPolicy *Policy

func TestMain(m *testing.M) {
	p, err := Load()
	if err != nil {
		fmt.Fprintln(os.Stderr, "加载权重失败:", err)
		os.Exit(1)
	}
	testPolicy = p
	os.Exit(m.Run())
}

// parityRun 汇总整轮回放的差异与统计。
type parityRun struct {
	problems             []string
	featureMismatches    int
	setMismatches        int
	decisions            int
	games                int
	argmaxMismatch       int
	goldenArgmaxMismatch int
	worst                float64
	worstDecision        int
	nanos                int64
}

// compareBlock 逐位比较一段特征;等价 Java 测试里的 Diff.compareBlock。
func (r *parityRun) compareBlock(name string, decision int, mine, golden []float32) {
	if len(mine) != len(golden) {
		r.record(fmt.Sprintf("%s 第%d个决策: 长度 %d != %d", name, decision, len(mine), len(golden)))
		r.featureMismatches++
		return
	}
	for i := range golden {
		if mine[i] != golden[i] {
			r.featureMismatches++
			r.record(fmt.Sprintf("%s 第%d个决策 [%d]: go=%v cpp=%v", name, decision, i, mine[i], golden[i]))
			return
		}
	}
}

// compareOptionSet 以 45 位多重集双向比较候选动作集合;等价 Java 测试里的 Diff.compareOptionSet。
func (r *parityRun) compareOptionSet(decision int, mine, golden [][RankSize]int32) {
	mineKeys := keySet(mine)
	goldenKeys := keySet(golden)
	if len(mineKeys) == len(goldenKeys) && sameKeySet(mineKeys, goldenKeys) {
		return
	}
	r.setMismatches++
	var onlyGolden, onlyMine []string
	for _, c := range golden {
		if _, ok := mineKeys[Key(c)]; !ok {
			onlyGolden = append(onlyGolden, DebugCards(c))
		}
	}
	for _, c := range mine {
		if _, ok := goldenKeys[Key(c)]; !ok {
			onlyMine = append(onlyMine, DebugCards(c))
		}
	}
	r.record(fmt.Sprintf("第%d个决策候选集合不同 cpp=%d go=%d 仅C++有%v 仅Go有%v",
		decision, len(golden), len(mine), onlyGolden, onlyMine))
}

// noteChosen C++ 认为最优的那手必须确实在候选集中。
func (r *parityRun) noteChosen(decision int, mine [][RankSize]int32, chosen [RankSize]int32, slot int) {
	if _, ok := keySet(mine)[Key(chosen)]; !ok {
		r.setMismatches++
		r.record(fmt.Sprintf("第%d个决策 C++ 选择 %s (槽位%d) 不在 Go 候选集中",
			decision, DebugCards(chosen), slot))
	}
}

func (r *parityRun) record(msg string) {
	if len(r.problems) < 20 { // 收集全部差异后一次性报告,免得第一处失败就丢掉上下文
		r.problems = append(r.problems, msg)
	}
}

func (r *parityRun) report() string {
	if len(r.problems) == 0 {
		return ""
	}
	return fmt.Sprintf("%s\n特征差异 %d 处, 候选集合差异 %d 处",
		strings.Join(r.problems, "\n"), r.featureMismatches, r.setMismatches)
}

func keySet(options [][RankSize]int32) map[int64]int {
	out := make(map[int64]int, len(options)*2)
	for _, c := range options {
		out[Key(c)]++
	}
	return out
}

func sameKeySet(a, b map[int64]int) bool {
	for k, n := range a {
		if b[k] != n {
			return false
		}
	}
	return true
}

func argmax(values []float32) int {
	best := 0
	for i := 1; i < len(values); i++ {
		if values[i] > values[best] {
			best = i
		}
	}
	return best
}

// TestGoldenParity 回放全部局面,校验候选集合、特征逐位一致与 logits 对齐。
func TestGoldenParity(t *testing.T) {
	reader := openGolden(t)
	defer reader.close()

	run := &parityRun{worstDecision: -1}
	for reader.hasNext() {
		key := reader.nextToken()
		switch key {
		case "DDZGOLDEN":
			reader.skipLine()
		case "GAME":
			game := reader.readGoldenGame()
			run.games++
			run.replayGame(t, reader, game)
		default:
			t.Fatalf("意外的字段 %s", key)
		}
	}

	if got := run.report(); got != "" {
		t.Errorf("与 golden 不一致:\n%s", got)
	}
	if run.decisions <= 700 {
		t.Errorf("回放的决策数 %d, 应多于 700", run.decisions)
	}
	if run.worst >= 1e-3 {
		t.Errorf("最大 logit 偏差 %e, 应小于 1e-3(第%d个决策)", run.worst, run.worstDecision)
	}
	if run.argmaxMismatch != 0 {
		t.Errorf("argmax 与 golden CHOSEN 不一致 %d 个", run.argmaxMismatch)
	}
	if run.goldenArgmaxMismatch != 0 {
		t.Errorf("golden 自身 argmax 与 CHOSEN 不一致 %d 个(基准可疑,请重新导出)", run.goldenArgmaxMismatch)
	}

	avgMs := 0.0
	if run.decisions > 0 {
		avgMs = float64(run.nanos) / float64(time.Millisecond) / float64(run.decisions)
	}
	summary := fmt.Sprintf("对齐 %d 个决策(%d 局): 最大 logit 偏差 %.3e (第%d个决策), "+
		"argmax 不一致 %d 个, 平均单次推理 %.2f ms",
		run.decisions, run.games, run.worst, run.worstDecision, run.argmaxMismatch, avgMs)
	fmt.Println(summary)
	t.Log(summary)
}

// replayGame 回放一局;遇到结构性偏差直接失败,其余差异累积到 run 里统一报告。
func (r *parityRun) replayGame(t *testing.T, g *goldenReader, game *goldenGame) {
	t.Helper()
	state, err := NewState(game.hands, game.landlordID, game.bottom)
	if err != nil {
		t.Fatalf("开局失败: %v", err)
	}
	for {
		marker := g.nextToken()
		if marker == "ENDGAME" {
			return
		}
		if marker != "DEC" {
			t.Fatalf("第%d个决策: 期望 DEC, 实际 %s", r.decisions, marker)
		}
		g.nextToken() // 决策序号
		d := g.readGoldenDecision()
		turn := d.turn
		if d.historyLen != len(state.MoveHistory()) {
			t.Fatalf("第%d个决策: HIST %d 与本地历史 %d 不符", r.decisions, d.historyLen, len(state.MoveHistory()))
		}
		if state.ActivePlayerID() != turn {
			t.Fatalf("第%d个决策的行动者应为 %d, 实际 %d", r.decisions, turn, state.ActivePlayerID())
		}
		if d.actSeat != turn {
			t.Fatalf("第%d个决策: ACT 座位 %d 与 TURN 不符", r.decisions, d.actSeat)
		}
		if DebugCards(d.played) != d.chosen {
			t.Fatalf("第%d个决策: CHOSEN 与 ACT 牌面不一致", r.decisions)
		}
		if d.chosenSlot < 0 || d.chosenSlot >= len(d.logits) {
			t.Fatalf("第%d个决策: 槽位 %d 超出候选范围", r.decisions, d.chosenSlot)
		}

		view := state.PublicViewOf(turn)
		observed, err := Observe(view)
		if err != nil {
			t.Fatalf("第%d个决策: 构造观察失败: %v", r.decisions, err)
		}
		xImp := make([]float32, NodeSize)
		seq := make([]float32, SeqSize)
		Encode(observed, xImp, seq)
		r.compareBlock("xImp", r.decisions, xImp, d.xImp)
		r.compareBlock("seq", r.decisions, seq, d.seq)

		toBeat := blank()
		if state.LastMove() != nil {
			toBeat = countsOf(state.LastMove())
		}
		mine := Legal(observed.MyHandCounts(), toBeat)
		r.compareOptionSet(r.decisions, mine, d.options)

		// 特征按 golden 的候选顺序逐位校验,并用校验过的自有特征喂给手写前向
		feats := make([]float32, len(d.options)*ActionSize)
		for i, cs := range d.options {
			mv, err := ToMove(cs, turn)
			if err != nil {
				t.Fatalf("第%d个决策: 候选 %s 无法归类: %v", r.decisions, DebugCards(cs), err)
			}
			off := i * ActionSize
			ActionFeature(observed, mv, feats, off)
			r.compareBlock("actionFeat["+DebugCards(cs)+"]", r.decisions,
				feats[off:off+ActionSize], d.feats[i])
		}

		start := time.Now()
		scores, err := testPolicy.Score(observed.ActorIndex(), xImp, seq, feats, len(d.options))
		r.nanos += int64(time.Since(start))
		if err != nil {
			t.Fatalf("第%d个决策: 推理失败: %v", r.decisions, err)
		}
		for i := range d.logits {
			diff := math.Abs(float64(scores[i]) - float64(d.logits[i]))
			if diff > r.worst {
				r.worst = diff
				r.worstDecision = r.decisions
			}
		}
		if argmax(scores) != d.chosenSlot {
			r.argmaxMismatch++
			r.record(fmt.Sprintf("第%d个决策: Go argmax=%d golden CHOSEN=%d(%s)",
				r.decisions, argmax(scores), d.chosenSlot, d.chosen))
		}
		if argmax(d.logits) != d.chosenSlot {
			r.goldenArgmaxMismatch++
		}

		r.noteChosen(r.decisions, mine, d.played, d.chosenSlot)
		played, err := ToMove(d.played, turn)
		if err != nil {
			t.Fatalf("第%d个决策: 实际出牌 %s 无法归类: %v", r.decisions, DebugCards(d.played), err)
		}
		if err := state.ApplyMove(played); err != nil {
			t.Fatalf("第%d个决策: 回放失败: %v", r.decisions, err)
		}
		r.decisions++
	}
}

// TestPolicyForwardIsDeterministic 同一决策重复推理结果必须完全一致。
func TestForwardIsDeterministic(t *testing.T) {
	xImp := make([]float32, NodeSize)
	seq := make([]float32, SeqSize)
	for i := range xImp {
		xImp[i] = float32(i%3) * 0.25
	}
	for i := range seq {
		seq[i] = float32(i%5) * 0.1
	}
	feats := make([]float32, 2*ActionSize)
	for i := range feats {
		feats[i] = float32(i%7) * 0.5
	}
	a, err := testPolicy.Score(0, xImp, seq, feats, 2)
	if err != nil {
		t.Fatalf("推理失败: %v", err)
	}
	b, err := testPolicy.Score(0, xImp, seq, feats, 2)
	if err != nil {
		t.Fatalf("推理失败: %v", err)
	}
	if a[0] != b[0] || a[1] != b[1] {
		t.Errorf("同样输入两次结果不同: %v vs %v", a, b)
	}
}

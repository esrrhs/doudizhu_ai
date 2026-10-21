package doudizhu

// 对外 API: 名字与 Java 侧调用形状一一对应,便于逐行对照移植。
//
//	Java                                          Go
//	PerfectDouMoves.legal(hand, toBeat)           Legal(hand, toBeat)
//	PerfectDouMoves.detect(m) -> Info             Detect(cs) -> (mtType, rank, length)
//	PerfectDouMoves.toMove(cs, playerId)          ToMove(cs, playerID)
//	PerfectDouMoves.key(cs)                       Key(cs)
//	Oracle.minSteps(cs)                           MinSteps(cs)
//	PerfectDouFeatures.observe(view)              Observe(view)
//	PerfectDouFeatures.encode(v, xImp, seq)       Encode(v, xImp, seq)
//	PerfectDouFeatures.actionFeature(v, m, o, i)  ActionFeature(v, m, out, off)
//	ActorNetwork.load(dir) / score(...)           LoadPolicy(dir) / (*Policy).Score(...)
//	NeuralAiPlayer.decide(publicView)             (*Policy).Decide(view)

// Legal 当前手的合法动作(多重集表示);应牌时最后一项恒为过牌,自由出牌不含过牌。
// 等价 Java PerfectDouMoves.legal。
func Legal(hand, toBeat [RankSize]int32) [][RankSize]int32 {
	return legal(hand, toBeat)
}

// Detect 牌型判定,返回(牌型 MT*, 主牌点数索引, 链长);无法归类时牌型为 MTWrong。
// 等价 Java PerfectDouMoves.detect。
func Detect(counts [RankSize]int32) (mtType, rank, length int) {
	info := detect(counts)
	return info.MTType, info.Rank, info.Length
}

// ToMove 把多重集转成引擎的 Move,链牌的 mainRank 用链的最大点数;
// 等价 Java PerfectDouMoves.toMove。
func ToMove(counts [RankSize]int32, playerID int) (*Move, error) {
	return toMove(counts, playerID)
}

// MinSteps 一手牌的最少出牌步数(与训练侧 oracle 逐位一致)。
// 等价 Java Oracle.minSteps。
func MinSteps(counts [RankSize]int32) int {
	return minSteps(counts)
}

// Observe 由玩家视角构造一次决策的公开观察;调用时该玩家必须正好是行动者。
// 等价 Java PerfectDouFeatures.observe。
func Observe(view *PublicView) (*View, error) {
	return observe(view)
}

// Encode 写入 1676 维节点向量;seq 传 nil 则跳过 5400 维历史序列。
// 等价 Java PerfectDouFeatures.encode。
func Encode(v *View, xImp []float32, seq []float32) {
	encode(v, xImp, seq)
}

// ActionFeature 候选动作特征: 180 维牌矩阵 + 6 个动态标量,写入 out 的 [off, off+186)。
// 等价 Java PerfectDouFeatures.actionFeature。
func ActionFeature(v *View, m *Move, out []float32, off int) {
	actionFeature(v, m, out, off)
}

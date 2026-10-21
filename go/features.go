package doudizhu

import "fmt"

// PerfectDou 网络输入编码(移植自 PerfectDouCPP src/ddz/features.cpp,
// 对应 Java com.doudizhu.nn.PerfectDouFeatures)。
//
// 牌索引 0..14 = 3 4 5 6 7 8 9 T J Q K A 2 小王 大王,即 Java Rank.getValue() - 3。
// 每份牌用一个 12x15 的 0/1 矩阵描述,展平为 180 个 float,行主序 row*15+rank。
//
// 一次决策的输入:
//   - xImp 1676 = 9 个当前状态矩阵(1620)+ 56 个标量
//   - seq 5400 = 最近 30 手动作矩阵,每 3 手拼成 LSTM 的一个时间步,共 10 步,前部补零
//   - 每个候选动作 actionFeat 186 = 该手牌的矩阵(180)+ 6 个动态标量
//
// 所有输入均可由公开信息推出,不读取任何玩家私有手牌。

const (
	// CardMatrix 一手牌的 12x15 牌矩阵展平后的长度
	CardMatrix = 12 * RankSize
	// StaticMatrices 节点向量里的牌矩阵个数
	StaticMatrices = 9
	// HistoryLen 历史序列保留的手数
	HistoryLen = 30
	// LSTMSteps LSTM 时间步数
	LSTMSteps = 10
	// LSTMIn 单个时间步的输入维度
	LSTMIn = 3 * CardMatrix
	// NodeBin 节点向量里牌矩阵部分的维度
	NodeBin = StaticMatrices * CardMatrix
	// ImpScalars 节点向量里的标量个数
	ImpScalars = 20 + 20 + 15 + 1
	// NodeSize 节点向量总维度 (1676)
	NodeSize = NodeBin + ImpScalars
	// SeqSize 历史序列总维度 (5400)
	SeqSize = HistoryLen * CardMatrix
	// ActionSize 单个候选动作特征维度 (186)
	ActionSize = CardMatrix + 6
)

// 矩阵内的行偏移,等价 Java PerfectDouFeatures.ROW_*。
const (
	rowSolo      = 4
	rowPair      = 5
	rowTrio      = 6
	rowBomb      = 7
	rowRocket    = 8
	rowSoloChain = 9
	rowPairChain = 10
	rowTrioChain = 11
)

// countsOf 一手牌在 15 个点数上的张数,过牌为全零;等价 Java PerfectDouFeatures.countsOf。
func countsOf(m *Move) [RankSize]int32 {
	if m == nil || m.IsPass() {
		return blank()
	}
	return m.Counts()
}

// cardMatrix 12x15 牌矩阵,写入 dst 的 [off, off+180);等价 Java PerfectDouFeatures.cardMatrix。
func cardMatrix(cnt [RankSize]int32, dst []float32, off int) {
	for i := off; i < off+CardMatrix; i++ {
		dst[i] = 0
	}
	for r := 0; r < RankSize; r++ {
		for k := 0; k < int(cnt[r]); k++ {
			dst[off+k*RankSize+r] = 1 // 第 k 行: 该点数至少出现 k+1 次
		}
		if cnt[r] >= 1 {
			dst[off+rowSolo*RankSize+r] = 1
		}
		if cnt[r] >= 2 {
			dst[off+rowPair*RankSize+r] = 1
		}
		if cnt[r] >= 3 {
			dst[off+rowTrio*RankSize+r] = 1
		}
		if r < NormalRankSize && cnt[r] == 4 {
			dst[off+rowBomb*RankSize+r] = 1
		}
	}
	if cnt[13] != 0 && cnt[14] != 0 { // 王炸同时点亮两张
		dst[off+rowRocket*RankSize+13] = 1
		dst[off+rowRocket*RankSize+14] = 1
	}
	for r := 0; r < NormalRankSize; r++ {
		if inChain(cnt, r, MinStraight, 1) {
			dst[off+rowSoloChain*RankSize+r] = 1
		}
		if inChain(cnt, r, MinPairChain, 2) {
			dst[off+rowPairChain*RankSize+r] = 1
		}
		if inChain(cnt, r, MinPlane, 3) {
			dst[off+rowTrioChain*RankSize+r] = 1
		}
	}
}

// inChain 该点数是否落在一条长度足够的连续链里(只有 3..A 能成链,2 与王被点数差自动切断);
// 等价 Java PerfectDouFeatures.inChain。
func inChain(cnt [RankSize]int32, r, minLen, need int) bool {
	lo, hi := r, r
	for lo-1 >= 0 && cnt[lo-1] >= int32(need) && RankValues[lo]-RankValues[lo-1] == 1 {
		lo--
	}
	for hi+1 < NormalRankSize && cnt[hi+1] >= int32(need) &&
		RankValues[hi+1]-RankValues[hi] == 1 {
		hi++
	}
	return hi-lo+1 >= minLen
}

// View 一次决策的公开观察,座位号沿用引擎的 playerId 体系;
// 等价 Java PerfectDouFeatures.View。
//
// 训练侧用 seat 0/1/2 = 地主/地主下家/地主上家,这里以 (playerId - landlordId + 3) % 3
// 还原为对应的 actor 编号。
type View struct {
	seat       int
	prev       int
	nxt        int
	landlordID int
	myHand     [RankSize]int32
	others     [RankSize]int32
	bottom     [RankSize]int32
	played     [3][RankSize]int32
	lastAction [3][RankSize]int32
	handCount  [3]int
	history    [HistoryLen * RankSize]int32
	lastPlayer int
	bombCount  int
	actorIndex int
}

// MyHandCounts 行动者自己的手牌张数(15 维,索引 = Rank.getValue() - 3);
// 等价 Java PerfectDouFeatures.View.myHandCounts。
func (v *View) MyHandCounts() [RankSize]int32 { return v.myHand }

// ActorIndex 应使用的 actor 编号: 0=地主, 1=地主下家, 2=地主上家;
// 等价 Java PerfectDouFeatures.View.actorIndex。
func (v *View) ActorIndex() int { return v.actorIndex }

// Seat 当前行动者的 playerId。
func (v *View) Seat() int { return v.seat }

// observe 由玩家视角构造观察,调用时该玩家必须正好是行动者;
// 等价 Java PerfectDouFeatures.observe。
func observe(view *PublicView) (*View, error) {
	seat := view.ViewingPlayerID()
	if seat != view.ActivePlayerID() {
		return nil, fmt.Errorf("observe() needs the acting player's view")
	}
	v := &View{}
	v.seat = seat
	v.prev = (seat + 2) % 3
	v.nxt = (seat + 1) % 3
	v.landlordID = view.LandlordID()
	v.actorIndex = (seat - view.LandlordID() + 3) % 3
	v.lastPlayer = view.LandlordID()

	v.myHand = view.MyHand().Counts()
	v.bottom = view.BottomCounts()
	for id := 0; id < 3; id++ {
		v.handCount[id] = view.CardCount(id)
	}

	// 逐手回放公开历史: 各家已出牌、各家最近一手动作、炸弹数、最后实际出牌者
	moves := view.MoveHistory()
	var lastOf [3 * RankSize]int32
	for _, m := range moves {
		id := m.PlayerID()
		c := countsOf(m)
		copy(lastOf[id*RankSize:], c[:])
		if !m.IsPass() {
			v.lastPlayer = id
			for r := 0; r < RankSize; r++ {
				v.played[id][r] += c[r]
			}
			if m.IsBomb() || m.IsRocket() {
				v.bombCount++
			}
		}
	}
	for id := 0; id < 3; id++ {
		copy(v.lastAction[id][:], lastOf[id*RankSize:])
	}

	// 历史矩阵: 取最近 30 手(含过牌),按时间顺序放在尾部,前部补空矩阵
	hlen := min(HistoryLen, len(moves))
	for i := 0; i < hlen; i++ {
		m := moves[len(moves)-hlen+i]
		slot := (HistoryLen - hlen + i) * RankSize
		c := countsOf(m)
		copy(v.history[slot:], c[:])
	}

	// 底牌要扣掉地主已经打出的那部分(按点数取交集)
	for r := 0; r < RankSize; r++ {
		v.bottom[r] -= min(v.bottom[r], v.played[v.landlordID][r])
	}

	// 两家合计的未知牌池 = 整副牌 - 我的手牌 - 场上已打出的牌
	for r := 0; r < RankSize; r++ {
		deckCount := int32(4)
		if r >= NormalRankSize {
			deckCount = 1
		}
		seen := v.myHand[r] + v.played[0][r] + v.played[1][r] + v.played[2][r]
		v.others[r] = deckCount - seen
	}
	return v, nil
}

// encode 写入 1676 维节点向量;seq 传 nil 则跳过 5400 维历史序列;
// 等价 Java PerfectDouFeatures.encode。
func encode(v *View, xImp []float32, seq []float32) {
	cardMatrix(v.myHand, xImp, 0*CardMatrix)
	cardMatrix(v.others, xImp, 1*CardMatrix)
	cardMatrix(v.played[v.seat], xImp, 2*CardMatrix)
	cardMatrix(v.played[v.prev], xImp, 3*CardMatrix)
	cardMatrix(v.played[v.nxt], xImp, 4*CardMatrix)
	cardMatrix(v.bottom, xImp, 5*CardMatrix)
	cardMatrix(v.lastAction[v.seat], xImp, 6*CardMatrix)
	cardMatrix(v.lastAction[v.prev], xImp, 7*CardMatrix)
	cardMatrix(v.lastAction[v.nxt], xImp, 8*CardMatrix)

	// 56 个标量(复用缓冲区时须先清零)
	s := NodeBin
	for i := s; i < s+ImpScalars; i++ {
		xImp[i] = 0
	}
	oneHot(xImp, s, 20, v.handCount[v.prev]-1)
	oneHot(xImp, s+20, 20, v.handCount[v.nxt]-1)
	oneHot(xImp, s+40, 15, min(v.bombCount, 14))
	if v.lastPlayer == v.seat {
		xImp[s+55] = 1
	} else {
		xImp[s+55] = 0
	}

	if seq != nil {
		// 30 手历史矩阵顺序铺平,每 3 手构成一个 LSTM 时间步
		var cnt [RankSize]int32
		for k := 0; k < HistoryLen; k++ {
			copy(cnt[:], v.history[k*RankSize:])
			cardMatrix(cnt, seq, k*CardMatrix)
		}
	}
}

// oneHot 等价 Java PerfectDouFeatures.oneHot。
func oneHot(dst []float32, off, n, value int) {
	if value >= 0 && value < n {
		dst[off+value] = 1
	}
}

// actionFeature 候选动作特征: 180 维牌矩阵 + 6 个动态标量,写入 out 的 [off, off+186);
// 等价 Java PerfectDouFeatures.actionFeature。
func actionFeature(v *View, m *Move, out []float32, off int) {
	chosen := countsOf(m)
	cardMatrix(chosen, out, off)
	size := 0
	if !m.IsPass() {
		size = m.CardCount()
	}

	e := off + CardMatrix
	if m.IsBomb() || m.IsRocket() {
		out[e] = 1
	} else {
		out[e] = 0
	}
	if isLargest(m) {
		out[e+1] = 1
	} else {
		out[e+1] = 0
	}
	if size > 0 && size == v.handCount[v.nxt] {
		out[e+2] = 1
	} else {
		out[e+2] = 0
	}
	if size > 0 && size == v.handCount[v.prev] {
		out[e+3] = 1
	} else {
		out[e+3] = 0
	}

	var after [RankSize]int32
	for r := 0; r < RankSize; r++ {
		after[r] = v.myHand[r] - chosen[r]
	}
	out[e+4] = float32(minSteps(after)) / 20
	out[e+5] = 1 // 有效动作标记,用于区分全零矩阵的过牌
}

// isLargest 同类型中的最大一手(再没有能压它的同型牌);等价 Java PerfectDouFeatures.isLargest。
func isLargest(m *Move) bool {
	rankIdx := m.MainRank() - 3 // 链牌的 MainRank 已是链的最大点数
	switch m.Type() {
	case CardRocket:
		return true
	case CardSingle:
		return rankIdx == 14 // 大王
	case CardBomb, CardPair, CardTriple, CardTriplePlusOne, CardTriplePlusPair,
		CardFourPlusTwo, CardFourPlusTwoPairs:
		return rankIdx == 12 // 2
	case CardStraight, CardConsecutivePairs, CardAirplane, CardAirplanePlusSingles,
		CardAirplanePlusPairs:
		return rankIdx == 11 // 链尾到 A
	default:
		return false
	}
}

// ActorIndex 该玩家应使用的 actor 编号: 0=地主, 1=地主下家, 2=地主上家;
// 等价 Java PerfectDouFeatures.actorIndex。
func ActorIndex(playerID, landlordID int) int {
	return (playerID - landlordID + 3) % 3
}

package doudizhu

import "fmt"

// 与训练侧一致的动作枚举与牌型判定(移植自 PerfectDouCPP src/ddz/moves.cpp,
// 逐分支对应 Java com.doudizhu.nn.PerfectDouMoves)。
//
// 引擎自带的 rules/MoveGenerator 对"飞机带翅"只生成一种带牌组合、四带二也不允许用一对当两单,
// 而策略网络是在完整枚举所有具体带牌组合的动作空间上训练的;为了让网络拿到与训练时一致的动作集合,
// 这里独立实现完整枚举。
//
// 牌索引 0..14 = 3 4 5 6 7 8 9 T J Q K A 2 小王 大王,判定链牌时用的是 RankValues
// (3..14,17,20,30),相邻数值差 1 才能连成链,因此 2 与王天然被切断。

// 牌型编码,与 C++ MoveType 一一对应。
const (
	MTPass         = 0  // 过牌
	MTSingle       = 1  // 单张
	MTPair         = 2  // 对子
	MTTriple       = 3  // 三张
	MTBomb         = 4  // 炸弹
	MTRocket       = 5  // 火箭(王炸)
	MTTripleOne    = 6  // 三带一
	MTTripleTwo    = 7  // 三带二
	MTStraight     = 8  // 顺子
	MTPairStraight = 9  // 连对
	MTPlane        = 10 // 飞机不带
	MTPlaneSolo    = 11 // 飞机带单
	MTPlanePair    = 12 // 飞机带对
	MTFourTwo      = 13 // 四带两单
	MTFourTwoPair  = 14 // 四带两对
	MTWrong        = 15 // 无法归类
)

// Info 牌型信息: type + 主牌点数索引(链牌为链的最小点数) + 链长;
// 等价 Java PerfectDouMoves.Info。
type Info struct {
	MTType int
	Rank   int
	Length int
}

// blank 全零牌集,等价 Java PerfectDouMoves.blank。
func blank() [RankSize]int32 {
	return [RankSize]int32{}
}

// valueRank 训练侧点数值 -> 点数索引;等价 Java PerfectDouMoves.valueRank。
func valueRank(value int) int {
	switch value {
	case 17:
		return 12
	case 20:
		return 13
	case 30:
		return 14
	default:
		return value - 3
	}
}

// valuesOf 展开成训练侧点数值序列(升序);等价 Java PerfectDouMoves.valuesOf。
func valuesOf(m [RankSize]int32) []int {
	v := make([]int, 0, Total(m))
	for r := 0; r < RankSize; r++ {
		for k := 0; k < int(m[r]); k++ {
			v = append(v, RankValues[r])
		}
	}
	return v
}

// continuous 是否逐张差 1 连续;等价 Java PerfectDouMoves.continuous。
func continuous(v []int) bool {
	for i := 1; i < len(v); i++ {
		if v[i]-v[i-1] != 1 {
			return false
		}
	}
	return true
}

// continuousSub ranks[from:from+len] 是否连续;等价 Java PerfectDouMoves.continuousSub。
func continuousSub(ranks []int, from, length int) bool {
	v := make([]int, length)
	for i := 0; i < length; i++ {
		v[i] = RankValues[ranks[from+i]]
	}
	return continuous(v)
}

// detect 判定一手工牌,无法归类时 Type 为 MTWrong;等价 Java PerfectDouMoves.detect。
func detect(m [RankSize]int32) Info {
	size := Total(m)
	if size == 0 {
		return Info{MTPass, -1, 1}
	}
	if size == 1 {
		for r := 0; r < RankSize; r++ {
			if m[r] != 0 {
				return Info{MTSingle, r, 1}
			}
		}
	}
	if size == 2 {
		for r := 0; r < NormalRankSize; r++ {
			if m[r] == 2 {
				return Info{MTPair, r, 1}
			}
		}
		if m[13] == 1 && m[14] == 1 {
			return Info{MTRocket, -1, 1}
		}
		return Info{MTWrong, -1, 1}
	}
	if size == 3 {
		for r := 0; r < RankSize; r++ {
			if m[r] == 3 {
				return Info{MTTriple, r, 1}
			}
		}
		return Info{MTWrong, -1, 1}
	}
	if size == 4 {
		for r := 0; r < NormalRankSize; r++ {
			if m[r] == 4 {
				return Info{MTBomb, r, 1}
			}
		}
		distinct := 0
		triRank := -1
		for r := 0; r < RankSize; r++ {
			if m[r] == 3 {
				triRank = r
				distinct++
			} else if m[r] == 1 {
				distinct++
			} else if m[r] != 0 {
				return Info{MTWrong, -1, 1}
			}
		}
		if distinct == 2 && triRank >= 0 {
			return Info{MTTripleOne, triRank, 1}
		}
		return Info{MTWrong, -1, 1}
	}

	vals := valuesOf(m)
	if continuous(vals) {
		return Info{MTStraight, valueRank(vals[0]), size}
	}

	if size == 5 {
		distinct := 0
		triRank := -1
		for r := 0; r < RankSize; r++ {
			if m[r] == 3 {
				triRank = r
				distinct++
			} else if m[r] == 2 {
				distinct++
			} else if m[r] != 0 {
				return Info{MTWrong, -1, 1}
			}
		}
		if distinct == 2 && triRank >= 0 {
			return Info{MTTripleTwo, triRank, 1}
		}
		return Info{MTWrong, -1, 1}
	}

	var byCount [5]int
	distinct := 0
	for r := 0; r < RankSize; r++ {
		if m[r] > 0 {
			byCount[m[r]]++
			distinct++
		}
	}

	if size == 6 {
		shape := (distinct == 2 || distinct == 3) && byCount[4] == 1 &&
			(byCount[2] == 1 || byCount[1] == 2)
		if shape {
			rank := -1
			for r := 0; r < RankSize; r++ {
				if m[r] == 4 {
					rank = r
					break
				}
			}
			return Info{MTFourTwo, rank, 1}
		}
	}
	if size == 8 {
		shape := ((distinct == 3 || distinct == 2) && byCount[4] == 1 && byCount[2] == 2) ||
			byCount[4] == 2
		if shape {
			rank := -1
			for r := RankSize - 1; r >= 0; r-- {
				if m[r] == 4 {
					rank = r
					break
				}
			}
			return Info{MTFourTwoPair, rank, 1}
		}
	}

	var present [RankSize]int
	np := 0
	for r := 0; r < RankSize; r++ {
		if m[r] > 0 {
			present[np] = r
			np++
		}
	}

	if distinct == byCount[2] {
		pairVals := make([]int, np)
		for i := 0; i < np; i++ {
			pairVals[i] = RankValues[present[i]]
		}
		if continuous(pairVals) {
			return Info{MTPairStraight, present[0], np}
		}
	}
	if distinct == byCount[3] {
		trioVals := make([]int, np)
		for i := 0; i < np; i++ {
			trioVals[i] = RankValues[present[i]]
		}
		if continuous(trioVals) {
			return Info{MTPlane, present[0], np}
		}
	}

	if byCount[3] >= MinPlane {
		var triRanks, soloRanks, pairRanks []int
		bad := false
		for r := 0; r < RankSize; r++ {
			switch m[r] {
			case 3:
				triRanks = append(triRanks, r)
			case 1:
				soloRanks = append(soloRanks, r)
			case 2:
				pairRanks = append(pairRanks, r)
			case 0:
			default:
				bad = true
			}
		}
		if !bad {
			triVals := make([]int, len(triRanks))
			for i, r := range triRanks {
				triVals[i] = RankValues[r]
			}
			if continuous(triVals) {
				nTri := len(triRanks)
				if nTri == len(soloRanks)+2*len(pairRanks) {
					return Info{MTPlaneSolo, triRanks[0], nTri}
				}
				if nTri == len(pairRanks) && distinct == 2*nTri {
					return Info{MTPlanePair, triRanks[0], nTri}
				}
			}
			// DouZero 边界: 四个连续三张可以当作"三张链 + 第四个三张拆成三张单翅"
			if len(triRanks) == 4 {
				if continuousSub(triRanks, 1, 3) {
					return Info{MTPlaneSolo, triRanks[1], 3}
				}
				if continuousSub(triRanks, 0, 3) {
					return Info{MTPlaneSolo, triRanks[0], 3}
				}
			}
		}
	}
	return Info{MTWrong, -1, 1}
}

// fromRanks 把给定各点数重复 repeat 次;等价 Java PerfectDouMoves.fromRanks。
func fromRanks(ranks []int, repeat int) [RankSize]int32 {
	var m [RankSize]int32
	for _, r := range ranks {
		m[r] += int32(repeat)
	}
	return m
}

// forEachCombination 在 items 上枚举取 num 个位置的所有组合(同值的不同位置会产生重复多重集,
// 由 dedupe 去除);等价 Java PerfectDouMoves.forEachCombination。
func forEachCombination(items []int, num int, fn func(idx []int)) {
	n := len(items)
	if num > n || num <= 0 {
		return
	}
	idx := make([]int, num)
	for i := 0; i < num; i++ {
		idx[i] = i
	}
	for {
		fn(idx)
		i := num - 1
		for i >= 0 && idx[i] == i+n-num {
			i--
		}
		if i < 0 {
			return
		}
		idx[i]++
		for j := i + 1; j < num; j++ {
			idx[j] = idx[j-1] + 1
		}
	}
}

// generator 与训练侧一致的枚举器,逐类型对应 C++ MoveGenerator 的成员函数;
// 等价 Java PerfectDouMoves.Generator。
type generator struct {
	hand         [RankSize]int32
	presentRanks []int
}

func newGenerator(hand [RankSize]int32) *generator {
	g := &generator{hand: hand}
	n := 0
	for r := 0; r < RankSize; r++ {
		if hand[r] > 0 {
			n++
		}
	}
	g.presentRanks = make([]int, 0, n)
	for r := 0; r < RankSize; r++ {
		if hand[r] > 0 {
			g.presentRanks = append(g.presentRanks, r)
		}
	}
	return g
}

func (g *generator) genAll() [][RankSize]int32 {
	out := make([][RankSize]int32, 0, 64)
	out = append(out, g.genSingles()...)
	out = append(out, g.genPairs()...)
	out = append(out, g.genTriples()...)
	out = append(out, g.genBombs()...)
	out = append(out, g.genRocket()...)
	out = append(out, g.genTripleSolo()...)
	out = append(out, g.genTriplePair()...)
	out = append(out, g.genSerials(1, MinStraight, 0)...)
	out = append(out, g.genSerials(2, MinPairChain, 0)...)
	out = append(out, g.genSerials(3, MinPlane, 0)...)
	out = append(out, g.genPlaneSolo(0)...)
	out = append(out, g.genPlanePair(0)...)
	out = append(out, g.genFourTwo()...)
	out = append(out, g.genFourTwoPair()...)
	return dedupe(out)
}

func (g *generator) genByType(mtType int, length int) [][RankSize]int32 {
	var out [][RankSize]int32
	switch mtType {
	case MTSingle:
		out = g.genSingles()
	case MTPair:
		out = g.genPairs()
	case MTTriple:
		out = g.genTriples()
	case MTBomb:
		out = append(g.genBombs(), g.genRocket()...)
	case MTTripleOne:
		out = g.genTripleSolo()
	case MTTripleTwo:
		out = g.genTriplePair()
	case MTStraight:
		out = g.genSerials(1, MinStraight, length)
	case MTPairStraight:
		out = g.genSerials(2, MinPairChain, length)
	case MTPlane:
		out = g.genSerials(3, MinPlane, length)
	case MTPlaneSolo:
		out = g.genPlaneSolo(length)
	case MTPlanePair:
		out = g.genPlanePair(length)
	case MTFourTwo:
		out = g.genFourTwo()
	case MTFourTwoPair:
		out = g.genFourTwoPair()
	default:
	}
	return dedupe(out)
}

func (g *generator) genSingles() [][RankSize]int32 {
	r := make([][RankSize]int32, 0, len(g.presentRanks))
	for _, rank := range g.presentRanks {
		var m [RankSize]int32
		m[rank] = 1
		r = append(r, m)
	}
	return r
}

func (g *generator) genPairs() [][RankSize]int32 {
	r := make([][RankSize]int32, 0, RankSize)
	for rank := 0; rank < RankSize; rank++ {
		if g.hand[rank] >= 2 {
			var m [RankSize]int32
			m[rank] = 2
			r = append(r, m)
		}
	}
	return r
}

func (g *generator) genTriples() [][RankSize]int32 {
	r := make([][RankSize]int32, 0, RankSize)
	for rank := 0; rank < RankSize; rank++ {
		if g.hand[rank] >= 3 {
			var m [RankSize]int32
			m[rank] = 3
			r = append(r, m)
		}
	}
	return r
}

func (g *generator) genBombs() [][RankSize]int32 {
	r := make([][RankSize]int32, 0, NormalRankSize)
	for rank := 0; rank < NormalRankSize; rank++ {
		if g.hand[rank] == 4 {
			var m [RankSize]int32
			m[rank] = 4
			r = append(r, m)
		}
	}
	return r
}

func (g *generator) genRocket() [][RankSize]int32 {
	var r [][RankSize]int32
	if g.hand[13] != 0 && g.hand[14] != 0 {
		var m [RankSize]int32
		m[13] = 1
		m[14] = 1
		r = append(r, m)
	}
	return r
}

func (g *generator) genTripleSolo() [][RankSize]int32 {
	var r [][RankSize]int32
	for t := 0; t < RankSize; t++ {
		if g.hand[t] < 3 {
			continue
		}
		for _, k := range g.presentRanks {
			if k == t {
				continue
			}
			var m [RankSize]int32
			m[t] = 3
			m[k] += 1
			r = append(r, m)
		}
	}
	return r
}

func (g *generator) genTriplePair() [][RankSize]int32 {
	var r [][RankSize]int32
	for t := 0; t < RankSize; t++ {
		if g.hand[t] < 3 {
			continue
		}
		for p := 0; p < NormalRankSize; p++ {
			if p == t || g.hand[p] < 2 {
				continue
			}
			var m [RankSize]int32
			m[t] = 3
			m[p] = 2
			r = append(r, m)
		}
	}
	return r
}

// genSerials repeat 为每点张数(1 顺子 / 2 连对 / 3 飞机), minLen 为下限,
// fixedLen 为 0 时枚举所有长度;等价 Java PerfectDouMoves.genSerials。
func (g *generator) genSerials(repeat, minLen, fixedLen int) [][RankSize]int32 {
	var ranks []int
	for r := 0; r < RankSize; r++ {
		if g.hand[r] >= int32(repeat) {
			ranks = append(ranks, r)
		}
	}
	// runs 每项为 {起始下标, 段长}
	var runs [][2]int
	i := 0
	for i < len(ranks) {
		j := i
		for j+1 < len(ranks) && RankValues[ranks[j+1]]-RankValues[ranks[j]] == 1 {
			j++
		}
		runs = append(runs, [2]int{i, j - i + 1})
		i = j + 1
	}
	var out [][RankSize]int32
	for _, run := range runs {
		start, longest := run[0], run[1]
		if longest < minLen {
			continue
		}
		if fixedLen > 0 {
			if longest < fixedLen {
				continue
			}
			for idx := 0; idx+fixedLen <= longest; idx++ {
				out = append(out, fromRanks(ranks[start+idx:start+idx+fixedLen], repeat))
			}
		} else {
			for length := minLen; length <= longest; length++ {
				for idx := 0; idx+length <= longest; idx++ {
					out = append(out, fromRanks(ranks[start+idx:start+idx+length], repeat))
				}
			}
		}
	}
	return out
}

func (g *generator) genPlaneSolo(fixedLen int) [][RankSize]int32 {
	planes := g.genSerials(3, MinPlane, fixedLen)
	var out [][RankSize]int32
	for _, pl := range planes {
		var rest []int // 拆掉整条三张后的实际牌
		chainLen := 0
		for r := 0; r < RankSize; r++ {
			if pl[r] == 3 {
				chainLen++
				continue
			}
			for k := 0; k < int(g.hand[r]); k++ {
				rest = append(rest, r)
			}
		}
		out = append(out, combosToMoves(rest, chainLen, pl, 1)...)
	}
	return out
}

func (g *generator) genPlanePair(fixedLen int) [][RankSize]int32 {
	planes := g.genSerials(3, MinPlane, fixedLen)
	var out [][RankSize]int32
	for _, pl := range planes {
		var candidates []int
		chainLen := 0
		for r := 0; r < RankSize; r++ {
			if pl[r] == 3 {
				chainLen++
				continue
			}
			if g.hand[r] >= 2 && r < NormalRankSize {
				candidates = append(candidates, r)
			}
		}
		out = append(out, combosToMoves(candidates, chainLen, pl, 2)...)
	}
	return out
}

// combosToMoves 从 items 里取 num 个位置作为带牌,每个位置加 add 张;
// 对应 Java genPlaneSolo/genPlanePair/genFourTwo 里的 forEachCombination 回调。
func combosToMoves(items []int, num int, base [RankSize]int32, add int32) [][RankSize]int32 {
	var out [][RankSize]int32
	forEachCombination(items, num, func(idx []int) {
		m := base
		for _, i := range idx {
			m[items[i]] += add
		}
		out = append(out, m)
	})
	return out
}

func (g *generator) genFourTwo() [][RankSize]int32 {
	var out [][RankSize]int32
	for f := 0; f < NormalRankSize; f++ {
		if g.hand[f] != 4 {
			continue
		}
		var rest []int
		for r := 0; r < RankSize; r++ {
			n := int(g.hand[r])
			if r == f {
				n -= 4
			}
			for k := 0; k < n; k++ {
				rest = append(rest, r)
			}
		}
		var quad [RankSize]int32
		quad[f] = 4
		out = append(out, combosToMoves(rest, 2, quad, 1)...)
	}
	return out
}

func (g *generator) genFourTwoPair() [][RankSize]int32 {
	var out [][RankSize]int32
	for f := 0; f < NormalRankSize; f++ {
		if g.hand[f] != 4 {
			continue
		}
		var pairRanks []int
		for r := 0; r < NormalRankSize; r++ {
			if r != f && g.hand[r] >= 2 {
				pairRanks = append(pairRanks, r)
			}
		}
		var quad [RankSize]int32
		quad[f] = 4
		out = append(out, combosToMoves(pairRanks, 2, quad, 2)...)
	}
	return out
}

// dedupe 按 45 位指纹去重,保留首次出现顺序;等价 Java PerfectDouMoves.dedupe。
func dedupe(moves [][RankSize]int32) [][RankSize]int32 {
	seen := make(map[int64]bool, len(moves)*2)
	out := make([][RankSize]int32, 0, len(moves))
	for _, m := range moves {
		if k := Key(m); !seen[k] {
			seen[k] = true
			out = append(out, m)
		}
	}
	return out
}

// beats 能否压过 rival(type 为被压方的牌型);等价 Java PerfectDouMoves.beats。
func beats(move, rival [RankSize]int32, mtType int) bool {
	a := detect(move)
	b := detect(rival)
	if a.MTType == MTRocket {
		return true
	}
	if a.MTType == MTBomb && mtType != MTBomb {
		return true
	}
	switch mtType {
	case MTSingle, MTPair, MTTriple, MTBomb, MTStraight, MTPairStraight, MTPlane:
		return a.MTType == mtType && a.Length == b.Length && a.Rank > b.Rank
	case MTTripleOne, MTTripleTwo, MTFourTwo, MTFourTwoPair:
		return a.MTType == mtType && a.Rank > b.Rank
	case MTPlaneSolo, MTPlanePair:
		if a.MTType != mtType || a.Length != b.Length {
			return false
		}
		ra := -1
		rb := -1
		for r := 0; r < RankSize; r++ {
			if move[r] == 3 {
				ra = r // 最高的三张点数
			}
			if rival[r] == 3 {
				rb = r
			}
		}
		return ra > rb
	default:
		return false
	}
}

// legal 当前手的合法动作(多重集);应牌时最后一项恒为过牌,自由出牌不含过牌。
// 等价 Java PerfectDouMoves.legal。
func legal(hand, toBeat [RankSize]int32) [][RankSize]int32 {
	if Total(toBeat) == 0 {
		return newGenerator(hand).genAll()
	}
	rival := detect(toBeat)
	if rival.MTType == MTRocket {
		return [][RankSize]int32{blank()}
	}
	gen := newGenerator(hand)
	var moves [][RankSize]int32
	if rival.MTType == MTBomb {
		for _, m := range gen.genByType(MTBomb, 0) {
			if beats(m, toBeat, MTBomb) {
				moves = append(moves, m)
			}
		}
	} else {
		for _, m := range gen.genByType(rival.MTType, rival.Length) {
			if beats(m, toBeat, rival.MTType) {
				moves = append(moves, m)
			}
		}
		for _, m := range gen.genByType(MTBomb, 0) {
			if beats(m, toBeat, rival.MTType) {
				moves = append(moves, m)
			}
		}
	}
	moves = append(moves, blank()) // 过牌
	return moves
}

// cardTypeOf 训练侧牌型 -> 引擎牌型;等价 Java PerfectDouMoves.cardTypeOf。
func cardTypeOf(mtType int) (CardType, bool) {
	switch mtType {
	case MTPass:
		return CardPass, true
	case MTSingle:
		return CardSingle, true
	case MTPair:
		return CardPair, true
	case MTTriple:
		return CardTriple, true
	case MTBomb:
		return CardBomb, true
	case MTRocket:
		return CardRocket, true
	case MTTripleOne:
		return CardTriplePlusOne, true
	case MTTripleTwo:
		return CardTriplePlusPair, true
	case MTStraight:
		return CardStraight, true
	case MTPairStraight:
		return CardConsecutivePairs, true
	case MTPlane:
		return CardAirplane, true
	case MTPlaneSolo:
		return CardAirplanePlusSingles, true
	case MTPlanePair:
		return CardAirplanePlusPairs, true
	case MTFourTwo:
		return CardFourPlusTwo, true
	case MTFourTwoPair:
		return CardFourPlusTwoPairs, true
	}
	return CardPass, false
}

// toMoveInfo 把多重集转成引擎的 Move,链牌的 mainRank 用链的最大点数,与 MoveGenerator 的约定一致;
// 等价 Java PerfectDouMoves.toMove(counts, playerId, info)。
func toMoveInfo(counts [RankSize]int32, playerID int, info Info) (*Move, error) {
	if info.MTType == MTPass {
		return PassMove(playerID), nil
	}
	cardType, ok := cardTypeOf(info.MTType)
	if !ok {
		return nil, fmt.Errorf("无法归类的牌: %s", DebugCards(counts))
	}
	topIndex := info.Rank
	if info.MTType != MTRocket && info.Length > 1 {
		topIndex = info.Rank + info.Length - 1
	}
	if info.MTType == MTRocket {
		topIndex = 14
	}
	return NewMove(cardType, topIndex+3, counts, playerID), nil
}

// toMove 等价 Java PerfectDouMoves.toMove(counts, playerId)。
func toMove(counts [RankSize]int32, playerID int) (*Move, error) {
	return toMoveInfo(counts, playerID, detect(counts))
}

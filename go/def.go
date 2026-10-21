// Package doudizhu 是 doudizhu_ai Java 版的 Go 移植:
// 斗地主引擎状态机、与训练侧一致的动作枚举、PerfectDou 输入编码,
// 以及纯 Go(不依赖 cgo/libtorch/ONNX)的策略网络推理。
//
// 权重与对齐基准与 Java/C++ 完全共用(仓库根目录 data/ 下),
// 候选动作集合、xImp/seq/actionFeat 逐位对齐,logits 差异只来自浮点求和顺序。
package doudizhu

import (
	"fmt"
	"os"
	"path/filepath"
)

// 点数索引 0..14 = 3 4 5 6 7 8 9 T J Q K A 2 小王 大王,即 Java Rank.getValue() - 3。
// 牌集一律用 [15]int32 张数向量表示,全零即"过牌"。
const (
	// RankSize 点数档数(13 个普通点数 + 小王 + 大王)
	RankSize = 15
	// NormalRankSize 能成炸弹/链牌的普通点数档数
	NormalRankSize = 13
	// MinStraight 顺子最少张数
	MinStraight = 5
	// MinPairChain 连对最少组数
	MinPairChain = 3
	// MinPlane 飞机最少三张组数
	MinPlane = 2
)

// CardChars 训练侧(pddump/golden)的牌面写法,小王=B 大王=R。
var CardChars = [RankSize]byte{'3', '4', '5', '6', '7', '8', '9', 'T', 'J', 'Q', 'K', 'A', '2', 'B', 'R'}

// JavaRankValues 每个点数索引对应的 Java 侧 Rank.getValue()(3..14, 15=2, 16=小王, 17=大王)。
var JavaRankValues = [RankSize]int{3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17}

// RankValues 训练侧点数权值:相邻差 1 才能连成链,因此 2 与王天然被切断。
// 等价 Java PerfectDouMoves.RANK_VALUES。
var RankValues = [RankSize]int{3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 30}

// rankSymbols 每个点数索引对应的 Java 侧 Rank.getSymbol()。
var rankSymbols = [RankSize]string{"3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A",
	"2", "BJ", "RJ"}

// RankSymbol 点数数值(Java 侧 Rank.getValue(),3..17)的符号;等价 Java Rank.fromValue(v).getSymbol()。
func RankSymbol(rankValue int) string {
	if rankValue < 3 || rankValue > 17 {
		return "?"
	}
	return rankSymbols[rankValue-3]
}

// Total 一手牌的总张数,等价 Java PerfectDouMoves.total。
func Total(counts [RankSize]int32) int {
	s := 0
	for _, c := range counts {
		s += int(c)
	}
	return s
}

// Key 点数多重集的稳定 45 位指纹,每点数占 3 位;等价 Java PerfectDouMoves.key。
func Key(counts [RankSize]int32) int64 {
	var k int64
	for r := 0; r < RankSize; r++ {
		k |= int64(counts[r]) << (3 * r)
	}
	return k
}

// DebugCards 训练侧的牌面写法,用于日志与测试比对;等价 Java PerfectDouFeatures.debugCards。
func DebugCards(counts [RankSize]int32) string {
	buf := make([]byte, 0, Total(counts))
	for r := 0; r < RankSize; r++ {
		for k := 0; k < int(counts[r]); k++ {
			buf = append(buf, CardChars[r])
		}
	}
	if len(buf) == 0 {
		return "-"
	}
	return string(buf)
}

// ParseCards 牌面字符串 -> 15 维张数;等价 Java GoldenReader.parseCounts。
func ParseCards(cards string) ([RankSize]int32, error) {
	var c [RankSize]int32
	if cards == "-" || cards == "" {
		return c, nil
	}
	for _, ch := range cards {
		i := RankIndexOfChar(byte(ch))
		if i < 0 {
			return c, fmt.Errorf("无法识别的点数字符 %q", string(ch))
		}
		c[i]++
	}
	return c, nil
}

// RankIndexOfChar 牌面字符 -> 点数索引,不认识返回 -1。
func RankIndexOfChar(ch byte) int {
	for r := 0; r < RankSize; r++ {
		if CardChars[r] == ch {
			return r
		}
	}
	return -1
}

// CardType 斗地主牌型(引擎侧),等价 Java com.doudizhu.model.CardType。
type CardType int

const (
	CardPass                CardType = iota // 过牌
	CardSingle                              // 单张
	CardPair                                // 对子
	CardTriple                              // 三张
	CardTriplePlusOne                       // 三带一
	CardTriplePlusPair                      // 三带二
	CardStraight                            // 顺子
	CardConsecutivePairs                    // 连对
	CardAirplane                            // 飞机不带
	CardAirplanePlusSingles                 // 飞机带单
	CardAirplanePlusPairs                   // 飞机带对
	CardFourPlusTwo                         // 四带两单
	CardFourPlusTwoPairs                    // 四带两对
	CardBomb                                // 炸弹
	CardRocket                              // 火箭(王炸)
)

var cardTypeDescriptions = [...]string{
	"过牌", "单张", "对子", "三张", "三带一", "三带二", "顺子", "连对",
	"飞机不带", "飞机带单", "飞机带对", "四带两单", "四带两对", "炸弹", "火箭(王炸)",
}

var cardTypeMinCards = [...]int{0, 1, 2, 3, 4, 5, 5, 6, 6, 8, 10, 6, 8, 4, 2}

// Description 中文牌型名,等价 Java CardType.getDescription()。
func (t CardType) Description() string {
	if int(t) < 0 || int(t) >= len(cardTypeDescriptions) {
		return "未知"
	}
	return cardTypeDescriptions[t]
}

// MinCards 该牌型最少张数,等价 Java CardType.getMinCards()。
func (t CardType) MinCards() int {
	if int(t) < 0 || int(t) >= len(cardTypeMinCards) {
		return 0
	}
	return cardTypeMinCards[t]
}

// Role 阵营,等价 Java com.doudizhu.model.Role。
type Role int

const (
	RoleLandlord Role = iota // 地主
	RoleFarmer               // 农民
)

// IsLandlord 等价 Java Role.isLandlord()。
func (r Role) IsLandlord() bool { return r == RoleLandlord }

// Description 等价 Java Role.getDescription()。
func (r Role) Description() string {
	if r == RoleLandlord {
		return "地主"
	}
	return "农民"
}

// Move 一次出牌;字段语义等价 Java com.doudizhu.model.Move
// (Java 用 List<Rank> 存牌,这里用 15 维张数,信息完全相同)。
type Move struct {
	cardType CardType
	mainRank int // 用于同类型比大小的主权值,存 Java 侧 Rank.getValue();链牌为链的最大点数
	counts   [RankSize]int32
	playerID int
}

// NewMove 构造一次出牌;等价 Java Move.of(type, mainRank, cards, playerId)。
func NewMove(cardType CardType, mainRank int, counts [RankSize]int32, playerID int) *Move {
	return &Move{cardType: cardType, mainRank: mainRank, counts: counts, playerID: playerID}
}

// PassMove 过牌;等价 Java Move.pass(playerId)。
func PassMove(playerID int) *Move {
	return &Move{cardType: CardPass, mainRank: 0, playerID: playerID}
}

// Type 等价 Java Move.getType()。
func (m *Move) Type() CardType { return m.cardType }

// MainRank 等价 Java Move.getMainRank()。
func (m *Move) MainRank() int { return m.mainRank }

// Counts 出牌包含的各点数张数;等价 Java Move.getCards() 的频次视图。
func (m *Move) Counts() [RankSize]int32 { return m.counts }

// PlayerID 等价 Java Move.getPlayerId()。
func (m *Move) PlayerID() int { return m.playerID }

// CardCount 等价 Java Move.getCardCount()。
func (m *Move) CardCount() int { return Total(m.counts) }

// IsPass 等价 Java Move.isPass()。
func (m *Move) IsPass() bool { return m.cardType == CardPass }

// IsBomb 等价 Java Move.isBomb()。
func (m *Move) IsBomb() bool { return m.cardType == CardBomb }

// IsRocket 等价 Java Move.isRocket()。
func (m *Move) IsRocket() bool { return m.cardType == CardRocket }

// CanBeat 判断当前出牌能否压制 other;等价 Java Move.canBeat(Move)。
func (m *Move) CanBeat(other *Move) bool {
	if other == nil || other.IsPass() {
		return !m.IsPass()
	}
	if m.IsPass() {
		return false
	}
	if m.IsRocket() {
		return true
	}
	if other.IsRocket() {
		return false
	}
	if m.IsBomb() && !other.IsBomb() {
		return true
	}
	if m.IsBomb() && other.IsBomb() {
		return m.mainRank > other.mainRank
	}
	if m.cardType == other.cardType && m.CardCount() == other.CardCount() {
		return m.mainRank > other.mainRank
	}
	return false
}

// ToCardString 出牌的训练侧写法;等价 Java Move.toCardString()。
func (m *Move) ToCardString() string {
	if m.IsPass() {
		return "PASS"
	}
	return DebugCards(m.counts)
}

// String 等价 Java Move.toString() 的可读输出(牌面用训练侧写法)。
func (m *Move) String() string {
	if m.IsPass() {
		return fmt.Sprintf("[P%d: PASS]", m.playerID)
	}
	return fmt.Sprintf("[P%d: %s (%s, main=%s)]", m.playerID, m.ToCardString(),
		m.cardType.Description(), RankSymbol(m.mainRank))
}

// Hand 一手牌(54 张的频次管理);等价 Java com.doudizhu.model.Hand。
type Hand struct {
	counts [RankSize]int32
}

// NewHand 由张数向量构造一份手牌;等价 Java Hand 的 add 序列。
func NewHand(counts [RankSize]int32) *Hand {
	return &Hand{counts: counts}
}

// ParseHand 由牌面字符串构造手牌,例如 "3344TKR";等价 Java 侧逐字符 add(Rank)。
func ParseHand(cards string) (*Hand, error) {
	c, err := ParseCards(cards)
	if err != nil {
		return nil, err
	}
	return &Hand{counts: c}, nil
}

// Copy 等价 Java Hand.copy()。
func (h *Hand) Copy() *Hand {
	return &Hand{counts: h.counts}
}

// Counts 15 维张数视图。
func (h *Hand) Counts() [RankSize]int32 { return h.counts }

// Add 等价 Java Hand.add(int rankValue)。
func (h *Hand) Add(rankValue int) {
	if rankValue < 3 || rankValue > 17 {
		return
	}
	h.counts[rankValue-3]++
}

// Remove 等价 Java Hand.remove(int rankValue),牌不存在时返回 false。
func (h *Hand) Remove(rankValue int) bool {
	if rankValue < 3 || rankValue > 17 {
		return false
	}
	i := rankValue - 3
	if h.counts[i] <= 0 {
		return false
	}
	h.counts[i]--
	return true
}

// Count 指定点数(Java Rank.getValue())的张数;等价 Java Hand.getCount(int)。
func (h *Hand) Count(rankValue int) int {
	if rankValue < 3 || rankValue > 17 {
		return 0
	}
	return int(h.counts[rankValue-3])
}

// TotalCards 等价 Java Hand.getTotalCards()。
func (h *Hand) TotalCards() int { return Total(h.counts) }

// IsEmpty 等价 Java Hand.isEmpty()。
func (h *Hand) IsEmpty() bool { return h.TotalCards() == 0 }

// HasRocket 等价 Java Hand.hasRocket()。
func (h *Hand) HasRocket() bool { return h.counts[13] > 0 && h.counts[14] > 0 }

// CanPlay 手牌是否够出这一手;等价 Java Hand.canPlay(Move)。
func (h *Hand) CanPlay(m *Move) bool {
	if m.IsPass() {
		return true
	}
	for r := 0; r < RankSize; r++ {
		if m.counts[r] > h.counts[r] {
			return false
		}
	}
	return true
}

// Play 从手牌中打掉这一手;等价 Java Hand.play(Move)。
func (h *Hand) Play(m *Move) {
	if m.IsPass() {
		return
	}
	for r := 0; r < RankSize; r++ {
		h.counts[r] -= m.counts[r]
	}
}

// Unplay 撤销一次出牌;等价 Java Hand.unplay(Move)。
func (h *Hand) Unplay(m *Move) {
	if m.IsPass() {
		return
	}
	for r := 0; r < RankSize; r++ {
		h.counts[r] += m.counts[r]
	}
}

// ToCardString 等价 Java Hand.toCardString()。
func (h *Hand) ToCardString() string { return DebugCards(h.counts) }

// String 等价 Java Hand.toString()。
func (h *Hand) String() string {
	return fmt.Sprintf("[%s] (%d cards)", h.ToCardString(), h.TotalCards())
}

// Player 玩家实体;等价 Java com.doudizhu.game.Player。
type Player struct {
	id   int
	name string
	role Role
	hand *Hand
}

// ID 等价 Java Player.getId()。
func (p *Player) ID() int { return p.id }

// Name 等价 Java Player.getName()。
func (p *Player) Name() string { return p.name }

// Role 等价 Java Player.getRole()。
func (p *Player) Role() Role { return p.role }

// Hand 等价 Java Player.getHand()。
func (p *Player) Hand() *Hand { return p.hand }

// CardCount 等价 Java Player.getCardCount()。
func (p *Player) CardCount() int { return p.hand.TotalCards() }

// HasWon 等价 Java Player.hasWon()。
func (p *Player) HasWon() bool { return p.hand.IsEmpty() }

// PublicView 玩家观察视角(不完全信息,严格隔离对手私有手牌);
// 等价 Java com.doudizhu.game.PublicView。
type PublicView struct {
	viewingPlayerID  int
	viewingRole      Role
	landlordID       int
	bottom           [RankSize]int32
	myHand           *Hand
	cardCounts       [3]int
	moveHistory      []*Move
	lastMove         *Move
	lastMovePlayerID int
	activePlayerID   int
	passCount        int
}

// ViewingPlayerID 等价 Java PublicView.getViewingPlayerId()。
func (v *PublicView) ViewingPlayerID() int { return v.viewingPlayerID }

// ViewingRole 等价 Java PublicView.getViewingRole()。
func (v *PublicView) ViewingRole() Role { return v.viewingRole }

// LandlordID 等价 Java PublicView.getLandlordId()。
func (v *PublicView) LandlordID() int { return v.landlordID }

// BottomCounts 底牌张数;等价 Java PublicView.getBottomCards()。
func (v *PublicView) BottomCounts() [RankSize]int32 { return v.bottom }

// MyHand 等价 Java PublicView.getMyHand()。
func (v *PublicView) MyHand() *Hand { return v.myHand }

// CardCount 等价 Java PublicView.getCardCount(int)。
func (v *PublicView) CardCount(playerID int) int { return v.cardCounts[playerID] }

// MoveHistory 等价 Java PublicView.getMoveHistory()。
func (v *PublicView) MoveHistory() []*Move { return v.moveHistory }

// LastMove 等价 Java PublicView.getLastMove()。
func (v *PublicView) LastMove() *Move { return v.lastMove }

// LastMovePlayerID 等价 Java PublicView.getLastMovePlayerId()。
func (v *PublicView) LastMovePlayerID() int { return v.lastMovePlayerID }

// ActivePlayerID 等价 Java PublicView.getActivePlayerId()。
func (v *PublicView) ActivePlayerID() int { return v.activePlayerID }

// PassCount 等价 Java PublicView.getPassCount()。
func (v *PublicView) PassCount() int { return v.passCount }

// State 斗地主牌局状态机;等价 Java com.doudizhu.game.GameState。
// 地主先手,两家连续过牌后由上一轮最大出牌者重新获得出牌权。
type State struct {
	players          [3]*Player
	landlordID       int
	bottom           [RankSize]int32
	activePlayerID   int
	lastMove         *Move
	lastMovePlayerID int
	passCount        int
	isGameOver       bool
	winnerID         int
	winningRole      Role
	moveHistory      []*Move
}

// NewState 由三家手牌、地主座位与底牌开局;等价 Java GameState(List<Hand>, int, List<Rank>)。
// 注意 golden 里地主那一家 DEAL 的 20 张已含底牌,这里不会重复叠加。
func NewState(hands [][RankSize]int32, landlordID int, bottom [RankSize]int32) (*State, error) {
	if len(hands) != 3 {
		return nil, fmt.Errorf("NewState 需要恰好 3 家手牌, 实际 %d", len(hands))
	}
	if landlordID < 0 || landlordID > 2 {
		return nil, fmt.Errorf("非法地主座位 %d", landlordID)
	}
	s := &State{landlordID: landlordID, bottom: bottom, lastMovePlayerID: -1, winnerID: -1}
	for i := 0; i < 3; i++ {
		role := RoleFarmer
		name := fmt.Sprintf("农民-%d", i)
		if i == landlordID {
			role = RoleLandlord
			name = "地主"
		}
		s.players[i] = &Player{id: i, name: name, role: role, hand: NewHand(hands[i])}
	}
	s.activePlayerID = landlordID // 斗地主由地主先手出牌
	return s, nil
}

// LandlordID 等价 Java GameState.getLandlordId()。
func (s *State) LandlordID() int { return s.landlordID }

// Player 等价 Java GameState.getPlayer(int)。
func (s *State) Player(id int) *Player { return s.players[id] }

// ActivePlayer 等价 Java GameState.getActivePlayer()。
func (s *State) ActivePlayer() *Player { return s.players[s.activePlayerID] }

// ActivePlayerID 等价 Java GameState.getActivePlayerIndex()。
func (s *State) ActivePlayerID() int { return s.activePlayerID }

// LastMove 等价 Java GameState.getLastMove()。
func (s *State) LastMove() *Move { return s.lastMove }

// PassCount 等价 Java GameState.getPassCount()。
func (s *State) PassCount() int { return s.passCount }

// IsGameOver 等价 Java GameState.isGameOver()。
func (s *State) IsGameOver() bool { return s.isGameOver }

// WinnerID 等价 Java GameState.getWinnerId()。
func (s *State) WinnerID() int { return s.winnerID }

// MoveHistory 等价 Java GameState.getMoveHistory()。
func (s *State) MoveHistory() []*Move { return s.moveHistory }

// ApplyMove 执行一个动作并推进状态;等价 Java GameState.applyMove(Move)。
func (s *State) ApplyMove(m *Move) error {
	if s.isGameOver {
		return fmt.Errorf("牌局已经结束")
	}
	current := s.players[s.activePlayerID]
	if !current.hand.CanPlay(m) {
		return fmt.Errorf("玩家 %d 手牌不足, 无法出 %s", current.id, m.ToCardString())
	}
	current.hand.Play(m)
	s.moveHistory = append(s.moveHistory, m)

	if current.HasWon() {
		s.isGameOver = true
		s.winnerID = current.id
		s.winningRole = current.role
		return nil
	}

	if m.IsPass() {
		s.passCount++
		if s.passCount == 2 {
			// 两家都过牌,本轮结束,由上一轮最大出牌者重新出牌
			s.activePlayerID = s.lastMovePlayerID
			s.lastMove = nil
			s.lastMovePlayerID = -1
			s.passCount = 0
		} else {
			s.activePlayerID = (s.activePlayerID + 1) % 3
		}
	} else {
		s.lastMove = m
		s.lastMovePlayerID = current.id
		s.passCount = 0
		s.activePlayerID = (s.activePlayerID + 1) % 3
	}
	return nil
}

// IsPlayerWinner 指定玩家是否属于获胜方(任一农民出完手牌两名农民都算赢);
// 等价 Java GameState.isPlayerWinner(int)。
func (s *State) IsPlayerWinner(playerID int) bool {
	if !s.isGameOver {
		return false
	}
	if s.winningRole.IsLandlord() {
		return playerID == s.landlordID
	}
	return playerID != s.landlordID
}

// PublicViewOf 构造某玩家的视角;等价 Java GameState.getPublicView(int)。
func (s *State) PublicViewOf(viewingPlayerID int) *PublicView {
	var counts [3]int
	for i := 0; i < 3; i++ {
		counts[i] = s.players[i].CardCount()
	}
	history := make([]*Move, len(s.moveHistory))
	copy(history, s.moveHistory)
	return &PublicView{
		viewingPlayerID:  viewingPlayerID,
		viewingRole:      s.players[viewingPlayerID].role,
		landlordID:       s.landlordID,
		bottom:           s.bottom,
		myHand:           s.players[viewingPlayerID].hand.Copy(),
		cardCounts:       counts,
		moveHistory:      history,
		lastMove:         s.lastMove,
		lastMovePlayerID: s.lastMovePlayerID,
		activePlayerID:   s.activePlayerID,
		passCount:        s.passCount,
	}
}

// 共享资产的定位: data/ 与 java/ go/ cpp/ server/ 平级,同一份文件在不同启动方式下的
// 相对路径不一样(从 go/ 跑测试、从仓库根跑 CI 都可能),这里按顺序试探当前目录及其上层。
// 等价 Java com.doudizhu.nn.SharedData。

// maxSharedParents 最多向上找几层目录。
const maxSharedParents = 3

// FindSharedData 返回仓库根 relativeToRepoRoot 的实际路径,找不到返回 "";
// 等价 Java SharedData.find。
func FindSharedData(relativeToRepoRoot string) string {
	base, err := os.Getwd()
	if err != nil {
		return ""
	}
	for up := 0; up <= maxSharedParents; up++ {
		p := base
		for i := 0; i < up; i++ {
			p = filepath.Join(p, "..")
		}
		candidate := filepath.Join(p, filepath.FromSlash(relativeToRepoRoot))
		if _, err := os.Stat(candidate); err == nil {
			return candidate
		}
	}
	return ""
}

// ResolveSharedData 同 FindSharedData,但找不到时返回按当前目录解释的路径,
// 让调用方抛出带有具体文件名的错误信息;等价 Java SharedData.resolve。
func ResolveSharedData(relativeToRepoRoot string) string {
	if found := FindSharedData(relativeToRepoRoot); found != "" {
		return found
	}
	abs, err := filepath.Abs(filepath.FromSlash(relativeToRepoRoot))
	if err != nil {
		return relativeToRepoRoot
	}
	return abs
}

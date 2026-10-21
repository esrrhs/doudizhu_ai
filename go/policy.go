package doudizhu

import "fmt"

// 策略网络玩家: 完整枚举合法动作,逐手打分后直接取最高分出牌。
// 对应 Java 侧 ActorNetwork(推理)与 NeuralAiPlayer(决策)。
//
// 三个座位各一份权重: actor 0=地主, 1=地主下家, 2=地主上家,
// 由 (playerId - landlordId + 3) % 3 选定;观察只用公开信息。

// Seats 座位(actor)个数。
const Seats = 3

// DefaultModelRel 仓库内自带权重目录相对仓库根的位置。
const DefaultModelRel = "data/models/ts"

// DefaultModelDir 按当前工作目录解析出 data/models/ts 的真实位置;
// 等价 Java NeuralAiPlayer.defaultModelDir。
func DefaultModelDir() string {
	return ResolveSharedData(DefaultModelRel)
}

// Policy 已加载的三个座位的 actor。权重只读,可在多个 goroutine 间并发使用。
type Policy struct {
	seats [Seats]*actorNet
}

// LoadPolicy 从目录加载 actor0_ts.pt .. actor2_ts.pt(连同各自的 _manifest.json);
// 等价 Java ActorNetwork.load(Path)。
func LoadPolicy(dir string) (*Policy, error) {
	p := &Policy{}
	for seat := 0; seat < Seats; seat++ {
		net, err := readActor(dir, seat)
		if err != nil {
			return nil, err
		}
		p.seats[seat] = net
	}
	return p, nil
}

// Load 从仓库默认目录 data/models/ts 加载权重;找不到权重时给出可操作的报错。
func Load() (*Policy, error) {
	dir := FindSharedData(DefaultModelRel)
	if dir == "" {
		return nil, fmt.Errorf("未找到 %s(该目录应与 go/ 平级放在仓库 data/ 下, "+
			"权重需先用 PerfectDouCPP 训练产出再由 tools/export_actor.py 导出 TorchScript)",
			DefaultModelRel)
	}
	return LoadPolicy(dir)
}

// Score 给单个决策的全部候选动作打分;等价 Java ActorNetwork.score。
//
// xImp 为 1676 维节点特征,seq 为 5400 维历史序列([10][540] 展平),
// actionFeat 为 options*186 维候选动作特征(按槽位顺序紧密排列)。
func (p *Policy) Score(seat int, xImp, seq, actionFeat []float32, options int) ([]float32, error) {
	if seat < 0 || seat >= Seats {
		return nil, fmt.Errorf("非法 actor 编号 %d", seat)
	}
	if len(xImp) < NodeSize {
		return nil, fmt.Errorf("xImp 长度 %d, 至少需要 %d", len(xImp), NodeSize)
	}
	if len(seq) < SeqSize {
		return nil, fmt.Errorf("seq 长度 %d, 至少需要 %d", len(seq), SeqSize)
	}
	if options < 0 || len(actionFeat) < options*ActionSize {
		return nil, fmt.Errorf("actionFeat 长度 %d 不足以放下 %d 个候选", len(actionFeat), options)
	}
	if options == 0 {
		return []float32{}, nil
	}
	return p.seats[seat].forward(xImp, seq, actionFeat, options), nil
}

// Decide 用策略网络为当前行动者选一手;等价 Java NeuralAiPlayer.decide。
// 返回最优动作与全部候选的得分(顺序与 Legal 一致)。
func (p *Policy) Decide(view *PublicView) (*Move, []float32, error) {
	myID := view.ViewingPlayerID()
	if view.MyHand().IsEmpty() {
		return PassMove(myID), nil, nil
	}
	observed, err := Observe(view)
	if err != nil {
		return nil, nil, err
	}
	xImp := make([]float32, NodeSize)
	seq := make([]float32, SeqSize)
	Encode(observed, xImp, seq)

	toBeat := blank()
	if view.LastMove() != nil {
		toBeat = countsOf(view.LastMove())
	}
	options := Legal(observed.MyHandCounts(), toBeat)
	n := len(options)

	moves := make([]*Move, n)
	actionFeat := make([]float32, n*ActionSize)
	for i, cs := range options {
		mv, err := toMove(cs, myID)
		if err != nil {
			return nil, nil, err
		}
		moves[i] = mv
		ActionFeature(observed, mv, actionFeat, i*ActionSize)
	}

	scores, err := p.Score(observed.ActorIndex(), xImp, seq, actionFeat, n)
	if err != nil {
		return nil, nil, err
	}
	best := 0
	for i := 1; i < n; i++ {
		if scores[i] > scores[best] {
			best = i
		}
	}
	return moves[best], scores, nil
}

package doudizhu

import (
	"bufio"
	"compress/gzip"
	"os"
	"strconv"
	"strings"
	"testing"
)

// golden 记录的读取器,对应 Java 测试侧 GoldenReader。
//
// 记录是行式的: 每个数值块以一行 "<KEY> <个数>" 表头开头,其后每行最多 20 个值,
// 所以按 token 逐个读取最稳妥。牌面用训练侧写法 3..9 T J Q K A 2 B(小王) R(大王),过牌为 "-"。

const goldenRelPath = "data/golden/golden24.txt.gz"

type goldenReader struct {
	file    *os.File
	gz      *gzip.Reader
	scanner *bufio.Scanner
	tokens  []string
	index   int
}

// openGolden 从仓库根的 data/ 目录打开 golden 记录(三种语言共用同一份基准)。
func openGolden(t *testing.T) *goldenReader {
	t.Helper()
	path := FindSharedData(goldenRelPath)
	if path == "" {
		t.Fatalf("未找到 %s(已尝试当前目录及其上三层,该文件应放在仓库 data/golden/ 下)", goldenRelPath)
	}
	f, err := os.Open(path)
	if err != nil {
		t.Fatalf("打开 %s 失败: %v", path, err)
	}
	gz, err := gzip.NewReader(bufio.NewReader(f))
	if err != nil {
		f.Close()
		t.Fatalf("解压 %s 失败: %v", path, err)
	}
	scanner := bufio.NewScanner(gz)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	return &goldenReader{file: f, gz: gz, scanner: scanner}
}

func (g *goldenReader) close() {
	g.gz.Close()
	g.file.Close()
}

// hasNext 是否还有未读的 token。
func (g *goldenReader) hasNext() bool {
	return g.index < len(g.tokens) || g.readLine()
}

func (g *goldenReader) nextToken() string {
	for g.index >= len(g.tokens) {
		if !g.readLine() {
			panic("golden 文件提前结束")
		}
	}
	tok := g.tokens[g.index]
	g.index++
	return tok
}

// readLine 读入下一非空行并填满缓冲区;文件结束返回 false。
func (g *goldenReader) readLine() bool {
	for g.scanner.Scan() {
		line := strings.TrimSpace(g.scanner.Text())
		if line == "" {
			continue
		}
		g.tokens = strings.Fields(line)
		g.index = 0
		return true
	}
	if err := g.scanner.Err(); err != nil {
		panic("读取 golden 失败: " + err.Error())
	}
	return false
}

// skipLine 丢弃当前行剩余内容。
func (g *goldenReader) skipLine() {
	g.tokens = nil
	g.index = 0
}

// readFloats 读取 n 个 float32;十进制文本到 float32 的转换在 Java/Go 两侧都是正确舍入的,
// 因此逐位可比。
func (g *goldenReader) readFloats(n int) []float32 {
	out := make([]float32, n)
	for i := 0; i < n; i++ {
		v, err := strconv.ParseFloat(g.nextToken(), 32)
		if err != nil {
			panic("golden 里的浮点数读不出来: " + err.Error())
		}
		out[i] = float32(v)
	}
	return out
}

// expect 消费一行 "<key> <个数>" 表头并返回个数。
func (g *goldenReader) expect(key string) int {
	if got := g.nextToken(); got != key {
		panic("golden 结构异常: 期望 " + key + ", 实际 " + got)
	}
	n, err := strconv.Atoi(g.nextToken())
	if err != nil {
		panic("golden 的个数读不出来: " + err.Error())
	}
	return n
}

// expectToken 消费一个应当等于 key 的 token。
func (g *goldenReader) expectToken(key string) {
	if got := g.nextToken(); got != key {
		panic("golden 结构异常: 期望 " + key + ", 实际 " + got)
	}
}

// goldenDecision golden 里一个决策的原始记录。
type goldenDecision struct {
	turn       int
	historyLen int
	xImp       []float32
	seq        []float32
	options    [][RankSize]int32
	feats      [][]float32
	logits     []float32
	chosenSlot int
	chosen     string
	actSeat    int
	played     [RankSize]int32
}

// readGoldenDecision 读完 DEC ... ENDDEC 之间的全部内容。
func (g *goldenReader) readGoldenDecision() *goldenDecision {
	d := &goldenDecision{}
	g.expectToken("TURN")
	d.turn, _ = strconv.Atoi(g.nextToken())
	g.expectToken("HIST")
	d.historyLen, _ = strconv.Atoi(g.nextToken())

	if n := g.expect("XIMP"); n != NodeSize {
		panic("XIMP 个数 " + strconv.Itoa(n) + " 与实现不符")
	}
	d.xImp = g.readFloats(NodeSize)

	if n := g.expect("SEQ"); n != SeqSize {
		panic("SEQ 个数 " + strconv.Itoa(n) + " 与实现不符")
	}
	d.seq = g.readFloats(SeqSize)

	options := g.expect("OPT")
	d.options = make([][RankSize]int32, 0, options)
	d.feats = make([][]float32, 0, options)
	for i := 0; i < options; i++ {
		g.expectToken("OPTLINE")
		cards := g.nextToken()
		cnt, err := ParseCards(cards)
		if err != nil {
			panic("候选动作牌面异常 " + cards + ": " + err.Error())
		}
		d.options = append(d.options, cnt)
		if n := g.expect("AFEAT"); n != ActionSize {
			panic("AFEAT 个数 " + strconv.Itoa(n) + " 与实现不符")
		}
		d.feats = append(d.feats, g.readFloats(ActionSize))
	}

	if n := g.expect("LOGIT"); n != options {
		panic("LOGIT 个数与候选数不符")
	}
	d.logits = g.readFloats(options)

	g.expectToken("CHOSEN")
	d.chosenSlot, _ = strconv.Atoi(g.nextToken())
	d.chosen = g.nextToken()

	g.expectToken("ACT")
	d.actSeat, _ = strconv.Atoi(g.nextToken())
	played, err := ParseCards(g.nextToken())
	if err != nil {
		panic("ACT 牌面异常: " + err.Error())
	}
	d.played = played
	g.expectToken("ENDDEC")
	return d
}

// goldenGame golden 里一局的发牌、底牌与地主。
type goldenGame struct {
	hands      [][RankSize]int32
	bottom     [RankSize]int32
	landlordID int
}

// readGoldenGame 读完 GAME ... 的发牌部分(不含决策)。
func (g *goldenReader) readGoldenGame() *goldenGame {
	game := &goldenGame{hands: make([][RankSize]int32, 3)}
	g.nextToken() // 局号
	for s := 0; s < 3; s++ {
		g.expectToken("DEAL")
		g.nextToken() // 座位号(golden 里 0 恒为地主)
		cards := g.nextToken()
		cnt, err := ParseCards(cards)
		if err != nil {
			panic("DEAL 牌面异常 " + cards + ": " + err.Error())
		}
		game.hands[s] = cnt
	}
	g.expectToken("BOTTOM")
	bottom, err := ParseCards(g.nextToken())
	if err != nil {
		panic("BOTTOM 牌面异常: " + err.Error())
	}
	game.bottom = bottom
	g.expectToken("LANDLORD")
	game.landlordID, _ = strconv.Atoi(g.nextToken())
	return game
}

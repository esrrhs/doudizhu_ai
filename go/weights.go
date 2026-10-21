package doudizhu

import (
	"archive/zip"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// 纯 Go 的 actor 权重加载与前向计算(不需要 cgo、libtorch 或 ONNX runtime)。
//
// data/models/ts/actor<N>_ts.pt 是 TorchScript 归档: 张量数据以 STORED(不压缩)的 zip 条目
// <root>/data/<i> 存放,是小端 float32 行主序的裸字节,所以标准库 archive/zip 就够了。
// 条目序号 -> 层名 -> 形状 的映射写在同目录的 actor<N>_manifest.json 里(由
// tools/export_actor.py 生成),这里按清单读取并校验字节数,不硬编码形状。

// 参数名与结构,与 tools/export_actor.py 的 PARAM_NAMES 一一对应。
const (
	paramLSTMWeightIH = "lstm.weight_ih_l0"
	paramLSTMWeightHH = "lstm.weight_hh_l0"
	paramLSTMBiasIH   = "lstm.bias_ih_l0"
	paramLSTMBiasHH   = "lstm.bias_hh_l0"
	paramL1Weight     = "l1.weight"
	paramL1Bias       = "l1.bias"
	paramL2Weight     = "l2.weight"
	paramL2Bias       = "l2.bias"
	paramL3Weight     = "l3.weight"
	paramL3Bias       = "l3.bias"
	paramL4Weight     = "l4.weight"
	paramL4Bias       = "l4.bias"
	paramHeadWeight   = "head.weight"
	paramHeadBias     = "head.bias"
)

// expectedParams 清单里必须出现且只出现这些参数。
var expectedParams = []string{
	paramLSTMWeightIH, paramLSTMWeightHH, paramLSTMBiasIH, paramLSTMBiasHH,
	paramL1Weight, paramL1Bias, paramL2Weight, paramL2Bias,
	paramL3Weight, paramL3Bias, paramL4Weight, paramL4Bias,
	paramHeadWeight, paramHeadBias,
}

// LSTMHidden LSTM 隐层宽度(与清单里 lstm.weight_hh_l0 的行列一致)。
const LSTMHidden = 128

// NodeDim 拼接进 l1 的状态向量维度: xImp(1676) + LSTM 末步隐状态(128) = 1804。
const NodeDim = NodeSize + LSTMHidden

// manifestParam 清单里的一条参数记录。
type manifestParam struct {
	Entry int    `json:"entry"`
	Name  string `json:"name"`
	Shape []int  `json:"shape"`
}

// actorManifest actor<N>_manifest.json 的内容。
//
// 清单同时给出条目顺序与门块顺序,加载时一并校验,防止导出侧悄悄改约定。
type actorManifest struct {
	Root      string          `json:"root"`
	Dtype     string          `json:"dtype"`
	ByteOrder string          `json:"byteOrder"`
	GateOrder string          `json:"gateOrder"`
	Params    []manifestParam `json:"parameters"`
}

// dense 一个全连接层,weight 为 [out][in] 行主序。
type dense struct {
	weight []float32
	bias   []float32
	outDim int
	inDim  int
}

// actorNet 单个座位的策略网络权重。
type actorNet struct {
	wIh  []float32 // [4*128][540]
	wHh  []float32 // [4*128][128]
	bIh  []float32 // [4*128]
	bHh  []float32 // [4*128]
	l1   dense
	l2   dense
	l3   dense
	l4   dense
	head dense
}

// readActor 从 dir 下的 actor<seat>_ts.pt 与 actor<seat>_manifest.json 读出一座权重。
func readActor(dir string, seat int) (*actorNet, error) {
	archivePath := filepath.Join(dir, fmt.Sprintf("actor%d_ts.pt", seat))
	manifestPath := filepath.Join(dir, fmt.Sprintf("actor%d_manifest.json", seat))

	raw, err := os.ReadFile(manifestPath)
	if err != nil {
		return nil, fmt.Errorf("读取清单 %s 失败: %w", manifestPath, err)
	}
	var mf actorManifest
	if err := json.Unmarshal(raw, &mf); err != nil {
		return nil, fmt.Errorf("解析清单 %s 失败: %w", manifestPath, err)
	}
	if mf.Dtype != "float32" {
		return nil, fmt.Errorf("%s: 不支持的权重类型 %q", manifestPath, mf.Dtype)
	}
	if mf.ByteOrder != "little" {
		return nil, fmt.Errorf("%s: 不支持的字节序 %q", manifestPath, mf.ByteOrder)
	}
	// 门块顺序写错会让 logits 偏到千分位以上，这里挡住清单侧的变更
	if mf.GateOrder != "" && !strings.Contains(mf.GateOrder, "i (input gate), f (forget gate)") {
		return nil, fmt.Errorf("%s: gateOrder 与本工程前向实现不符: %q", manifestPath, mf.GateOrder)
	}
	if mf.Root == "" {
		mf.Root = fmt.Sprintf("actor%d_ts", seat)
	}
	if len(mf.Params) != len(expectedParams) {
		return nil, fmt.Errorf("%s: 参数个数 %d, 期望 %d(网络结构与本工程不一致)",
			manifestPath, len(mf.Params), len(expectedParams))
	}

	zf, err := zip.OpenReader(archivePath)
	if err != nil {
		return nil, fmt.Errorf("打开权重 %s 失败: %w", archivePath, err)
	}
	defer zf.Close()

	byName := make(map[string][]float32, len(expectedParams))
	for _, p := range mf.Params {
		if err := validateShape(archivePath, p); err != nil {
			return nil, err
		}
		name := fmt.Sprintf("%s/data/%d", mf.Root, p.Entry)
		entry := findEntry(&zf.Reader, name, p.Entry)
		if entry == nil {
			return nil, fmt.Errorf("%s: 缺少条目 %s(清单与归档不匹配)", archivePath, name)
		}
		if int64(entry.UncompressedSize64) != elemCount(p.Shape)*4 {
			return nil, fmt.Errorf("%s: 条目 %s 大小 %d, 按形状 %v 期望 %d",
				archivePath, name, entry.UncompressedSize64, p.Shape, elemCount(p.Shape)*4)
		}
		if entry.Method != zip.Store {
			return nil, fmt.Errorf("%s: 条目 %s 不是 STORED(压缩权重无法用标准库裸读)",
				archivePath, name)
		}
		rc, err := entry.Open()
		if err != nil {
			return nil, fmt.Errorf("%s: 打开条目 %s 失败: %w", archivePath, name, err)
		}
		buf, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			return nil, fmt.Errorf("%s: 读取条目 %s 失败: %w", archivePath, name, err)
		}
		byName[p.Name] = floatsFromLE(buf)
	}
	for _, name := range expectedParams {
		if _, ok := byName[name]; !ok {
			return nil, fmt.Errorf("%s: 清单缺少参数 %s, 实际为 %v", manifestPath, name,
				sortedParamNames(mf.Params))
		}
	}
	if len(byName) != len(expectedParams) {
		return nil, fmt.Errorf("%s: 清单参数 %v 与本工程期望的 %v 不一致",
			manifestPath, sortedParamNames(mf.Params), expectedParams)
	}

	net := &actorNet{wIh: byName[paramLSTMWeightIH], wHh: byName[paramLSTMWeightHH],
		bIh: byName[paramLSTMBiasIH], bHh: byName[paramLSTMBiasHH]}
	dims := map[string][2]int{}
	for _, p := range mf.Params {
		if len(p.Shape) == 2 {
			dims[p.Name] = [2]int{p.Shape[0], p.Shape[1]}
		}
	}
	layer := func(wName, bName string) (dense, error) {
		d, ok := dims[wName]
		if !ok {
			return dense{}, fmt.Errorf("%s: %s 不是一维/二维之外的可用形状", manifestPath, wName)
		}
		if got := len(byName[wName]); got != d[0]*d[1] {
			return dense{}, fmt.Errorf("%s: %s 元素个数 %d 与形状 %v 不符", manifestPath, wName, got, d)
		}
		if got := len(byName[bName]); got != d[0] {
			return dense{}, fmt.Errorf("%s: %s 长度 %d 与形状 %v 不符", manifestPath, bName, got, d)
		}
		return dense{weight: byName[wName], bias: byName[bName], outDim: d[0], inDim: d[1]}, nil
	}
	var err2 error
	if net.l1, err2 = layer(paramL1Weight, paramL1Bias); err2 != nil {
		return nil, err2
	}
	if net.l2, err2 = layer(paramL2Weight, paramL2Bias); err2 != nil {
		return nil, err2
	}
	if net.l3, err2 = layer(paramL3Weight, paramL3Bias); err2 != nil {
		return nil, err2
	}
	if net.l4, err2 = layer(paramL4Weight, paramL4Bias); err2 != nil {
		return nil, err2
	}
	if net.head, err2 = layer(paramHeadWeight, paramHeadBias); err2 != nil {
		return nil, err2
	}
	// 结构必须与本工程写死的前向一致,否则宁可报错也不要算出悄悄偏掉的分数
	if net.l1.inDim != NodeDim+ActionSize || net.l1.outDim != 256 ||
		net.l2.inDim != 256 || net.l2.outDim != 256 ||
		net.l3.inDim != 256 || net.l3.outDim != 256 ||
		net.l4.inDim != 256 || net.l4.outDim != 512 ||
		net.head.inDim != 512 || net.head.outDim != 1 {
		return nil, fmt.Errorf("%s: 全连接层形状 %v 与本工程前向实现不符", manifestPath, dims)
	}
	if len(net.wIh) != 4*LSTMHidden*LSTMIn || len(net.wHh) != 4*LSTMHidden*LSTMHidden ||
		len(net.bIh) != 4*LSTMHidden || len(net.bHh) != 4*LSTMHidden {
		return nil, fmt.Errorf("%s: LSTM 形状与本工程前向实现不符", manifestPath)
	}
	return net, nil
}

// validateShape 只接受"若干维正整数"的形状,并且元素总数不超过 2^31。
func validateShape(where string, p manifestParam) error {
	if len(p.Shape) == 0 || len(p.Shape) > 2 {
		return fmt.Errorf("%s: 参数 %s 形状 %v 不受支持", where, p.Name, p.Shape)
	}
	n := elemCount(p.Shape)
	if n <= 0 || n > 1<<31 {
		return fmt.Errorf("%s: 参数 %s 形状 %v 元素个数异常", where, p.Name, p.Shape)
	}
	return nil
}

func elemCount(shape []int) int64 {
	n := int64(1)
	for _, d := range shape {
		n *= int64(d)
	}
	return n
}

// findEntry 按 <root>/data/<entry> 找条目;不同 torch 版本可能把根写成 actor<N> 或
// actor<N>_ts,因此根不匹配时再按 "唯一位于某个 data/ 目录下的该序号" 兜底。
func findEntry(z *zip.Reader, name string, entryIdx int) *zip.File {
	if f, ok := lookupEntry(z, name); ok {
		return f
	}
	suffix := fmt.Sprintf("/data/%d", entryIdx)
	var found *zip.File
	for _, f := range z.File {
		if filepath.Base(filepath.Dir(f.Name)) != "data" || !hasSuffix(f.Name, suffix) {
			continue
		}
		if found != nil {
			return nil // 归档里有多个根,无法判定
		}
		found = f
	}
	return found
}

func lookupEntry(z *zip.Reader, name string) (*zip.File, bool) {
	for _, cand := range z.File {
		if cand.Name == name {
			return cand, true
		}
	}
	return nil, false
}

func hasSuffix(s, suffix string) bool {
	return len(s) >= len(suffix) && s[len(s)-len(suffix):] == suffix
}

// floatsFromLE 小端 float32 裸字节 -> []float32。
func floatsFromLE(buf []byte) []float32 {
	out := make([]float32, len(buf)/4)
	for i := range out {
		out[i] = math.Float32frombits(binary.LittleEndian.Uint32(buf[i*4:]))
	}
	return out
}

// dot 点积,float32 累加以保持与 libtorch 相同的量级。
func dot(a, b []float32) float32 {
	var acc float32
	for i := range a {
		acc += a[i] * b[i]
	}
	return acc
}

// linear 全连接: dst = w @ src + bias, relu 为真时再过 relu(就地)。
func linear(dst, src []float32, l dense, relu bool) {
	for o := 0; o < l.outDim; o++ {
		acc := dot(l.weight[o*l.inDim:(o+1)*l.inDim], src) + l.bias[o]
		if relu && acc < 0 {
			acc = 0
		}
		dst[o] = acc
	}
}

func sigmoid(x float32) float32 {
	return float32(1 / (1 + math.Exp(float64(-x))))
}

func tanh32(x float32) float32 {
	return float32(math.Tanh(float64(x)))
}

// lstmHidden 一层 LSTM、10 个时间步,只取最后一步的隐状态 h(cell 丢弃);
// 等价 TorchScript 里 self.lstm(seq)[1][0]。
//
// 门块顺序为 i(输入门), f(遗忘门), g(候选/cell 输入, tanh), o(输出门),即 PyTorch
// weight_ih_l0 的实际拼接顺序 (manifest 的 gateOrder 也是这个口径)。别按 cuDNN 习惯
// 写成 i,h,f,c —— 那样 logits 会偏到 1~1900 量级、约 89 处 argmax 出错。
func (a *actorNet) lstmHidden(seq []float32, h [LSTMHidden]float32) [LSTMHidden]float32 {
	var c [LSTMHidden]float32
	var gates [4 * LSTMHidden]float32
	for t := 0; t < LSTMSteps; t++ {
		x := seq[t*LSTMIn : (t+1)*LSTMIn]
		for g := 0; g < 4*LSTMHidden; g++ {
			gates[g] = dot(a.wIh[g*LSTMIn:(g+1)*LSTMIn], x) + a.bIh[g] +
				dot(a.wHh[g*LSTMHidden:(g+1)*LSTMHidden], h[:]) + a.bHh[g]
		}
		for k := 0; k < LSTMHidden; k++ {
			inGate := sigmoid(gates[k])
			forget := sigmoid(gates[LSTMHidden+k])
			cand := tanh32(gates[2*LSTMHidden+k])
			outGate := sigmoid(gates[3*LSTMHidden+k])
			c[k] = forget*c[k] + inGate*cand
			h[k] = outGate * tanh32(c[k])
		}
	}
	return h
}

// forward 一次决策的前向: LSTM 末步隐状态与 xImp 拼成 node,每个候选再拼上自己的动作特征,
// 过 4 层 relu 全连接与 head,得到每个候选的得分。
//
// 与 TorchScript 的 concat(node, actionFeat) 数学上完全一致,这里把 l1 拆成
// "node 部分先算一次" + "每个候选只算 186 维那一截",差别只在浮点求和顺序。
func (a *actorNet) forward(xImp, seq, actionFeat []float32, options int) []float32 {
	h := a.lstmHidden(seq, [LSTMHidden]float32{})
	node := make([]float32, 0, NodeDim)
	node = append(node, xImp[:NodeSize]...)
	node = append(node, h[:]...)

	// 状态部分对 l1 的贡献(含 bias)
	nodePart := make([]float32, a.l1.outDim)
	for o := range nodePart {
		row := a.l1.weight[o*a.l1.inDim : o*a.l1.inDim+NodeDim]
		nodePart[o] = dot(row, node) + a.l1.bias[o]
	}

	hidden := a.l1.outDim
	joint := make([]float32, hidden)
	tmp := make([]float32, hidden)
	l4Buf := make([]float32, a.l4.outDim)
	scores := make([]float32, options)
	for i := 0; i < options; i++ {
		feat := actionFeat[i*ActionSize : (i+1)*ActionSize]
		for o := 0; o < hidden; o++ {
			row := a.l1.weight[o*a.l1.inDim+NodeDim : (o+1)*a.l1.inDim]
			acc := nodePart[o] + dot(row, feat)
			if acc < 0 {
				acc = 0
			}
			joint[o] = acc
		}
		linear(tmp, joint, a.l2, true)
		linear(joint, tmp, a.l3, true)
		linear(l4Buf, joint, a.l4, true)
		scores[i] = dot(a.head.weight[:a.head.inDim], l4Buf) + a.head.bias[0]
	}
	return scores
}

// sortedParamNames 便于报错时列出清单里的实际参数(稳定顺序)。
func sortedParamNames(p []manifestParam) []string {
	names := make([]string, 0, len(p))
	for _, x := range p {
		names = append(names, x.Name)
	}
	sort.Strings(names)
	return names
}

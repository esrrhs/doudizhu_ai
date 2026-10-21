package doudizhu

import (
	"sync"
)

// 手数下界 oracle: 一手牌最少几步出完(PerfectDou 论文附录 E.1 算法 1)。
// 逐分支移植自 PerfectDouCPP src/ddz/oracle.cpp,对应 Java com.doudizhu.nn.Oracle。
//
// 无王牌手牌按"组计数"(a 单张 / b 对子 / c 三张 / d 炸弹)做 DP 查表,
// 顺子/连对/飞机由外层记忆化 DFS 枚举。该值直接参与神经网络输入特征
// (每手候选牌减去后的剩余手数),因此必须与训练侧逐位一致。

const (
	maxA = 22
	maxB = 15
	maxC = 9
	maxD = 6

	impossible = 100
)

// fDPTable F(a,b,c,d) 打平成一维, -1 表示尚未计算;等价 Java Oracle.F。
var fDPTable [maxA * maxB * maxC * maxD]int8

var oracleOnce sync.Once

// initFDP 建组计数表;等价 Java Oracle 的 static 初始化块(类加载时执行一次)。
func initFDP() {
	for i := range fDPTable {
		fDPTable[i] = -1
	}
	fSet(0, 0, 0, 0, 0)
	for total := 1; total <= 20; total++ {
		for a := 0; a < maxA; a++ {
			for b := 0; b < maxB; b++ {
				for c := 0; c < maxC; c++ {
					for d := 0; d < maxD; d++ {
						if a+2*b+3*c+4*d != total {
							continue
						}
						best := a + b + c + d
						if a >= 1 {
							best = min(best, 1+fGet(a-1, b, c, d))
						}
						if b >= 1 {
							best = min(best, 1+fGet(a, b-1, c, d))
						}
						if c >= 1 {
							best = min(best, 1+fGet(a, b, c-1, d))
						}
						if d >= 1 {
							best = min(best, 1+fGet(a, b, c, d-1))
						}
						if c >= 1 {
							enumLooseKickers(a, b, c-1, d, 1, func(na, nb, nc, nd int) {
								best = min(best, 1+fGet(na, nb, nc, nd))
							})
						}
						if c >= 1 {
							if b >= 1 {
								best = min(best, 1+fGet(a, b-1, c-1, d))
							}
							if c >= 2 {
								best = min(best, 1+fGet(a+1, b, c-2, d))
							}
							if d >= 1 {
								best = min(best, 1+fGet(a, b, c-1, d-1))
							}
						}
						if d >= 1 {
							enumLooseKickers(a, b, c, d-1, 2, func(na, nb, nc, nd int) {
								best = min(best, 1+fGet(na, nb, nc, nd))
							})
						}
						if d >= 1 {
							if b >= 2 {
								best = min(best, 1+fGet(a, b-2, c, d-1))
							}
							if b >= 1 && c >= 1 {
								best = min(best, 1+fGet(a+1, b-1, c-1, d-1))
							}
							if c >= 2 {
								best = min(best, 1+fGet(a+2, b, c-2, d-1))
							}
						}
						fSet(a, b, c, d, best)
					}
				}
			}
		}
	}
}

func fIndex(a, b, c, d int) int {
	return ((a*maxB+b)*maxC+c)*maxD + d
}

func fSet(a, b, c, d, value int) {
	fDPTable[fIndex(a, b, c, d)] = int8(value)
}

func fGet(a, b, c, d int) int {
	if a < 0 || b < 0 || c < 0 || d < 0 {
		return impossible
	}
	if a >= maxA || b >= maxB || c >= maxC || d >= maxD {
		return a + b + c + d
	}
	v := int(fDPTable[fIndex(a, b, c, d)])
	if v < 0 {
		return a + b + c + d
	}
	return v
}

// fDP 组计数表的读取入口;等价 Java Oracle.fDP。
func fDP(a, b, c, d int) int {
	oracleOnce.Do(initFDP)
	return fGet(a, b, c, d)
}

// enumLooseKickers 枚举"拿 need 张散牌"的所有拆组方式,拆剩的组自动降档;
// 等价 Java Oracle.enumLooseKickers。
func enumLooseKickers(a, b, c, d, need int, fn func(a, b, c, d int)) {
	for s := 0; s <= min(need, a); s++ {
		r1 := need - s
		if r1 == 0 {
			fn(a-s, b, c, d)
			continue
		}
		for bp := 0; bp <= min(b, r1); bp++ {
			for pu := max(bp, 0); pu <= min(2*bp, r1); pu++ {
				r2 := r1 - pu
				pairLeftSingles := 2*bp - pu
				if r2 == 0 {
					fn(a-s+pairLeftSingles, b-bp, c, d)
					continue
				}
				for bt := 0; bt <= min(c, r2); bt++ {
					for tu := bt; tu <= min(3*bt, r2); tu++ {
						r3 := r2 - tu
						for t2 := 0; t2 <= bt; t2++ {
							for t1 := 0; t1+t2 <= bt; t1++ {
								t3 := bt - t1 - t2
								if t1+2*t2+3*t3 != tu {
									continue
								}
								r4 := r3
								if r4 == 0 {
									fn(a-s+pairLeftSingles+t2, b-bp+t1, c-bt, d)
									continue
								}
								for bd := 0; bd <= min(d, r4); bd++ {
									for bu := bd; bu <= min(4*bd, r4); bu++ {
										if bu != r4 {
											continue
										}
										for u3 := 0; u3 <= bd; u3++ {
											for u2 := 0; u2+u3 <= bd; u2++ {
												for u1 := 0; u1+u2+u3 <= bd; u1++ {
													u4 := bd - u1 - u2 - u3
													if u1+2*u2+3*u3+4*u4 != bu {
														continue
													}
													fn(a-s+pairLeftSingles+t2+u3,
														b-bp+t1+u2,
														c-bt+u1, d-bd)
												}
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}
}

// 直接映射缓存,与 C++/Java 同为 65536 槽。Java 用 ThreadLocal 隔离,
// Go 侧改用一把锁保护共享缓存(MinSteps 是纯函数,缓存只影响速度不影响结果)。
const cacheSlots = 65536

const cacheMask = cacheSlots - 1

type oracleMemo struct {
	keys [cacheSlots]int64
	vals [cacheSlots]int8
}

var (
	oracleMu        sync.Mutex
	oracleMemoTable = &oracleMemo{}
)

// hashKey MurmurHash3 64 位混淆;等价 Java Oracle.hashKey。
func hashKey(k int64) int {
	x := uint64(k)
	x ^= x >> 33
	x *= 0xff51afd7ed558ccd
	x ^= x >> 33
	x *= 0xc4ceb9fe1a85ec53
	x ^= x >> 33
	return int(x & cacheMask)
}

// hasPotentialChain 是否存在可能的顺子/连对/飞机链,用于走无链快路径;
// 等价 Java Oracle.hasPotentialChain。
func hasPotentialChain(cnt [RankSize]int32) bool {
	maxConsecutive, curConsecutive := 0, 0
	maxPairChain, curPairChain := 0, 0
	maxPlane, curPlane := 0, 0
	for r := 0; r < NormalRankSize; r++ {
		c := int(cnt[r])
		if c >= 1 {
			curConsecutive++
			maxConsecutive = max(maxConsecutive, curConsecutive)
		} else {
			curConsecutive = 0
		}
		if c >= 2 {
			curPairChain++
			maxPairChain = max(maxPairChain, curPairChain)
		} else {
			curPairChain = 0
		}
		if c >= 3 {
			curPlane++
			maxPlane = max(maxPlane, curPlane)
		} else {
			curPlane = 0
		}
	}
	return maxConsecutive >= MinStraight || maxPairChain >= MinPairChain || maxPlane >= MinPlane
}

// lowerBound 忽略链牌的组数下界;等价 Java Oracle.lowerBound。
func lowerBound(cnt [RankSize]int32) int {
	a, b, c, d := groupCounts(cnt)
	jokers := 0
	if cnt[13] != 0 {
		jokers++
	}
	if cnt[14] != 0 {
		jokers++
	}
	if cnt[13] != 0 && cnt[14] != 0 {
		jokers = 1 // 王炸算一步
	}
	return fDP(a, b, c, d) + jokers
}

// groupCounts 统计 3..A 上的单张/对子/三张/炸弹组数;等价 Java Oracle 里的 a/b/c/d 统计。
func groupCounts(cnt [RankSize]int32) (a, b, c, d int) {
	for r := 0; r < NormalRankSize; r++ {
		switch cnt[r] {
		case 1:
			a++
		case 2:
			b++
		case 3:
			c++
		case 4:
			d++
		}
	}
	return a, b, c, d
}

// jokerGroups 王牌的组数(两张都在只算王炸一步);等价 Java Oracle 里的 jokerGroups。
func jokerGroups(cnt [RankSize]int32) int {
	groups := 0
	if cnt[13] != 0 {
		groups++
	}
	if cnt[14] != 0 {
		groups++
	}
	if cnt[13] != 0 && cnt[14] != 0 {
		groups = 1
	}
	return groups
}

// enumWingMultisets 逐点枚举 need 张带的全部多重集合;等价 Java Oracle.enumWingMultisets。
func enumWingMultisets(cnt [RankSize]int32, rank, need int, pick *[RankSize]int32, fn func(pick *[RankSize]int32)) {
	if need == 0 {
		fn(pick)
		return
	}
	if rank == RankSize {
		return
	}
	mx := min(int(cnt[rank]), need)
	for take := 0; take <= mx; take++ {
		pick[rank] = int32(take)
		enumWingMultisets(cnt, rank+1, need-take, pick, fn)
	}
	pick[rank] = 0
}

// solve 记忆化的最少步数 DFS;等价 Java Oracle.solve。
func solve(cnt [RankSize]int32) int {
	key := Key(cnt)
	if key == 0 {
		return 0
	}
	h := hashKey(key)
	if oracleMemoTable.keys[h] == key && oracleMemoTable.vals[h] > 0 {
		return int(oracleMemoTable.vals[h])
	}

	// 无链可能时组数下界即为精确解
	if !hasPotentialChain(cnt) {
		ans := lowerBound(cnt)
		oracleMemoTable.keys[h] = key
		oracleMemoTable.vals[h] = int8(ans)
		return ans
	}

	best := impossible
	if cnt[13] != 0 || cnt[14] != 0 {
		rem := cnt
		rem[13] = 0
		rem[14] = 0
		best = min(best, 1+solve(rem))
	}

	a, b, c, d := groupCounts(cnt)
	best = min(best, fDP(a, b, c, d)+jokerGroups(cnt))

	if best > 1 {
		enumerateChains(cnt, 1, MinStraight, false, &best)
		enumerateChains(cnt, 2, MinPairChain, false, &best)
		enumerateChains(cnt, 3, MinPlane, true, &best)
	}

	oracleMemoTable.keys[h] = key
	oracleMemoTable.vals[h] = int8(best)
	return best
}

// enumerateChains 在连续可连的区段上枚举所有链长与起点,必要时再枚举带牌;
// 等价 Java Oracle.enumerateChains。
func enumerateChains(cnt [RankSize]int32, repeat, minLen int, trios bool, best *int) {
	i := 0
	for i < NormalRankSize {
		if cnt[i] < int32(repeat) {
			i++
			continue
		}
		j := i
		for j+1 < NormalRankSize && cnt[j+1] >= int32(repeat) &&
			RankValues[j+1]-RankValues[j] == 1 {
			j++
		}
		runLen := j - i + 1
		for length := minLen; length <= runLen; length++ {
			for s := i; s+length <= j+1; s++ {
				rem := cnt
				for x := 0; x < length; x++ {
					rem[s+x] -= int32(repeat)
				}
				if 1+lowerBound(rem) < *best {
					*best = min(*best, 1+solve(rem))
				}
				if trios {
					if 4*length <= 20 {
						var pick [RankSize]int32
						enumWingMultisets(rem, 0, length, &pick, func(w *[RankSize]int32) {
							after := rem
							for r := 0; r < RankSize; r++ {
								after[r] -= w[r]
							}
							if 1+lowerBound(after) < *best {
								*best = min(*best, 1+solve(after))
							}
						})
					}
					if 5*length <= 20 {
						var pairRanks [NormalRankSize]int
						np := 0
						for r := 0; r < NormalRankSize; r++ {
							if rem[r] >= 2 {
								pairRanks[np] = r
								np++
							}
						}
						if np >= length {
							choosePairCombos(rem, pairRanks[:np], length, 0, 0,
								make([]int, length), best)
						}
					}
				}
			}
		}
		i = j + 1
	}
}

// choosePairCombos 飞机带对: 从可对的点数里选 length 个不同点数的组合;
// 等价 Java Oracle.choosePairCombos。
func choosePairCombos(rem [RankSize]int32, pairRanks []int, length, idx, start int,
	sel []int, best *int) {
	if idx == length {
		after := rem
		for _, r := range sel {
			after[r] -= 2
		}
		if 1+lowerBound(after) < *best {
			*best = min(*best, 1+solve(after))
		}
		return
	}
	for x := start; x+(length-idx-1) < len(pairRanks); x++ {
		sel[idx] = pairRanks[x]
		choosePairCombos(rem, pairRanks, length, idx+1, x+1, sel, best)
	}
}

// minSteps 一手牌的最少出牌步数;等价 Java Oracle.minSteps。
func minSteps(cnt [RankSize]int32) int {
	oracleOnce.Do(initFDP)
	oracleMu.Lock()
	defer oracleMu.Unlock()
	return solve(cnt)
}

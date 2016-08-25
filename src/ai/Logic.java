package ai;

import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;

public class Logic
{
	public Table t;
	public Robot A;
	public Robot B;
	public Robot C;
	Random rand = new Random();

	public static int[] Card =
	{ 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 };

	public static int[] AllCard =
	{ 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 1, 2, 3, 4, 5, 6, 7, 8, 9,
			10, 11, 12, 13, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 };

	public static String[] CardName =
	{ "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2", "小王", "大王" };

	public static int N = 100;

	public Logic(Table t)
	{
		this.t = t;
		this.A = t.A;
		this.B = t.B;
		this.C = t.C;
	}

	public void Run()
	{
		// 发牌
		Dispatch();

		CardInfo lastbig = new CardInfo(CardType.ct_single, 0, A, new int[0], 1);

		// 开始打
		while (true)
		{
			lastbig = A.Go(lastbig);
			if (IsEnd())
			{
				A.Win();
				break;
			}

			lastbig = B.Go(lastbig);
			if (IsEnd())
			{
				B.Win();
				C.Win();
				break;
			}

			lastbig = C.Go(lastbig);
			if (IsEnd())
			{
				B.Win();
				C.Win();
				break;
			}
		}
	}

	public boolean IsEnd()
	{
		return A.IsEnd() || B.IsEnd() || C.IsEnd();
	}

	public int GetCardFromName(String s)
	{
		for (int i = 0; i < CardName.length; i++)
		{
			if (s.equals(CardName[i]))
			{
				return i + 1;
			}
		}
		return 0;
	}

	public void Dispatch()
	{
		boolean test = false;
		if (test)
		{
			String a = "3,4,小王,大王";
			String b = "3,Q";
			String c = "3";

			for (String s : a.split("\\,"))
			{
				A.AddCard(GetCardFromName(s));
			}

			for (String s : b.split("\\,"))
			{
				B.AddCard(GetCardFromName(s));
			}

			for (String s : c.split("\\,"))
			{
				C.AddCard(GetCardFromName(s));
			}
		}
		else
		{
			LinkedList<Integer> tmp = new LinkedList<Integer>();
			for (int i : AllCard)
			{
				tmp.add(i);
			}

			for (int i = 0; i < 20; i++)
			{
				int index = rand.nextInt(tmp.size());
				int card = tmp.get(index);
				tmp.remove(index);
				A.AddCard(card);
			}

			for (int i = 0; i < 17; i++)
			{
				int index = rand.nextInt(tmp.size());
				int card = tmp.get(index);
				tmp.remove(index);
				B.AddCard(card);
			}

			for (int i = 0; i < tmp.size(); i++)
			{
				int card = tmp.get(i);
				C.AddCard(card);
			}

			System.out.println(">>>[" + A.no + "] :" + A.CardState());
			System.out.println(">>>[" + B.no + "] :" + B.CardState());
			System.out.println(">>>[" + C.no + "] :" + C.CardState());
		}

		try
		{
			FileWriter fileWriter = new FileWriter("Result.txt");
			fileWriter.close();
		}
		catch (Exception e)
		{

		}
	}

	public CardInfo OutCard(Robot r, CardInfo lastbig)
	{
		MCTSNode root = new MCTSNode();
		int N = 10000;
		CardInfo ret = MCTS(N, r, root, lastbig);
		boolean isdump = true;
		if (isdump)
		{
			String dump = DumpMCTS(N, 0, root, 0, 2);
			try
			{
				FileWriter fileWriter = new FileWriter("Result.txt", true);
				fileWriter.write("[" + r.no + "] :(" + r.CardState() + ")" + ret.CardStr() + "\n");
				fileWriter.write(dump);
				fileWriter.flush();
				fileWriter.close();
			}
			catch (Exception e)
			{

			}
		}
		return ret;
	}

	public String DumpMCTS(int N, int no, MCTSNode node, int deps, int need)
	{
		if (deps >= need)
		{
			return "";
		}
		String str = "";
		for (int i = 0; i < deps; i++)
		{
			str += "\t";
		}

		if (node.cardInfo == null)
		{
			str += "(" + no + ")" + "[{" + "null" + "}," + node.Value + "," + node.N + "]\n";
		}
		else
		{
			str += "(" + no + ")" + "[{";

			str += node.cardInfo.CardStr();

			str += "}," + node.Value + "," + node.N + "]\n";
		}

		int i = 0;
		for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
		{
			MCTSNode s = e.getValue();
			str += DumpMCTS(node.N, i, s, deps + 1, need);
			i++;
		}
		return str;
	}

	public boolean MCTSCompare(CardInfo l, CardInfo r)
	{
		int lsum = 0;
		for (int c : l.cardstr)
		{
			lsum += c;
		}

		int rsum = 0;
		for (int c : r.cardstr)
		{
			rsum += c;
		}

		int lvalue = lsum + (20 - l.cardnum) * 8;
		int rvalue = rsum + (20 - r.cardnum) * 8;

		return lvalue < rvalue;
	}

	public CardInfo MCTS(int num, Robot r, MCTSNode node, CardInfo lastbig)
	{
		for (int i = 0; i < num; i++)
		{
			MCTSCal(r, node, lastbig);

			// 出现90%的胜率或者90%的选择，提前end
			if ((i + 1) % 1000 == 0)
			{
				int maxv = Integer.MIN_VALUE;
				int maxn = Integer.MIN_VALUE;
				for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
				{
					MCTSNode s = e.getValue();
					if (s.N > maxn)
					{
						maxn = s.N;
					}
					if (s.Value > maxv)
					{
						maxv = s.Value;
					}
				}
				if (maxn > i * 90 / 100 || maxv > i * 90 / 100)
				{
					break;
				}
			}
		}

		int totalvalue = 0;
		for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
		{
			MCTSNode s = e.getValue();
			totalvalue += s.Value;
		}

		if (totalvalue >= node.N)
		{
			// 必赢
			for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
			{
				MCTSNode s = e.getValue();
				if (s.cardInfo.cardnum >= r.cardnum && s.cardInfo.type != CardType.ct_four_plus_two && s.cardInfo.type != CardType.ct_four_plus_two_double)
				{
					return s.cardInfo;
				}
			}
			
			int minmax = 999999;
			CardInfo cardinfo = null;
			for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
			{
				MCTSNode s = e.getValue();
				if (s.cardInfo.max < minmax && r.cardmap[s.cardInfo.max] != 4 && !(have_double_king(r) && s.cardInfo.max == 14 || s.cardInfo.max == 15))
				{
					cardinfo = s.cardInfo;
					minmax = s.cardInfo.max;
				}
			}
			
			for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
			{
				MCTSNode s = e.getValue();

				if (cardinfo == null)
				{
					cardinfo = s.cardInfo;
				}
				else
				{
					if (cardinfo.type == CardType.ct_pass)
					{
						cardinfo = s.cardInfo;
					}
					else if (cardinfo.type == CardType.ct_double_king)
					{
						cardinfo = s.cardInfo;
					}
					else if (s.cardInfo.max == cardinfo.max && s.cardInfo.cardnum > cardinfo.cardnum  && s.cardInfo.type != CardType.ct_four_plus_two && s.cardInfo.type != CardType.ct_four_plus_two_double )
					{
						cardinfo = s.cardInfo;
					}
					else if (s.cardInfo.type == cardinfo.type && r.cardmap[s.cardInfo.max] != 4)
					{
						if (MCTSCompare(s.cardInfo, cardinfo))
						{
							cardinfo = s.cardInfo;
						}
					}
				}
			}

			return cardinfo;
		}
		else if (totalvalue == 0)
		{
			// 必输
			CardInfo cardinfo = null;
			int min = 9999999;
			for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
			{
				MCTSNode s = e.getValue();

				int sum = 0;
				for (int c : s.cardInfo.cardstr)
				{
					sum += c;
				}

				if (sum < min || (cardinfo != null && cardinfo.type == CardType.ct_pass))
				{
					min = sum;
					cardinfo = s.cardInfo;
				}
			}

			return cardinfo;
		}
		else
		{
			// 同value的权重计算，选个价值最低的打出去
			CardInfo cardinfo = null;
			int max = -9999999;
			for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
			{
				MCTSNode s = e.getValue();
				// value正负1/100认为是一样的
				if (s.Value >= max - max / 100 && s.Value <= max + max / 100)
				{
					max = Math.max(s.Value, max);

					// 选价值最低的打出去
					if (MCTSCompare(s.cardInfo, cardinfo))
					{
						cardinfo = s.cardInfo;
					}
				}
				else if (s.Value > max)
				{
					max = s.Value;
					cardinfo = s.cardInfo;
				}
			}

			return cardinfo;
		}
	}

	public int MCTSCal(Robot r, MCTSNode node, CardInfo lastbig)
	{
		if (IsEnd())
		{
			if (A.IsEnd())
			{
				return 1;
			}
			else
			{
				return -1;
			}
		}

		MCTSNode select = null;
		if (!node.sonall)
		{
			ArrayList<CardInfo> outlist;
			if (lastbig.r == r)
			{
				outlist = FindFirstOutCard(r);
			}
			else
			{
				outlist = FindBigger(r, lastbig);
			}

			for (CardInfo c : outlist)
			{
				MCTSNode s = node.son.get(c);
				if (s == null)
				{
					s = new MCTSNode();
					s.cardInfo = c;
					node.son.put(c, s);
					select = s;
					break;
				}
			}

			if (select == null)
			{
				select = MCTSChoose(r, node);
				node.sonall = true;
			}
		}
		else
		{
			select = MCTSChoose(r, node);
		}

		r.RemoveCard(select.cardInfo);

		CardInfo newlastbig;
		if (select.cardInfo.type == CardType.ct_pass)
		{
			newlastbig = (CardInfo) lastbig.clone();
		}
		else
		{
			newlastbig = (CardInfo) select.cardInfo.clone();
		}

		Robot next = r.next;
		int ret = MCTSCal(next, select, newlastbig);

		r.AddCard(select.cardInfo);

		if (r.no == 0)
		{
			if (ret > 0)
			{
				select.Value++;
			}
		}
		else
		{
			if (ret < 0)
			{
				select.Value++;
			}
		}

		node.N++;

		return ret;
	}

	public MCTSNode MCTSChoose(Robot r, MCTSNode node)
	{
		int c = 1;
		float max = -999999;
		MCTSNode ret = null;
		for (Map.Entry<CardInfo, MCTSNode> e : node.son.entrySet())
		{
			MCTSNode s = e.getValue();
			float value = 0;
			if (s.N == 0)
			{
				return s;
			}
			value += (float) s.Value / s.N + c * (float) (Math.sqrt(2 * Math.log(node.N) / s.N));
			if (value > max)
			{
				max = value;
				ret = s;
			}
		}
		return ret;
	}

	/*
		public int MinMaxCard(int deps, Robot r, CardInfo lastbig, CardInfo ret)
		{
			if (IsEnd())
			{
				if (A.IsEnd())
				{
					return 9999999;
				}
				else
				{
					return -9999999;
				}
			}
	
			if (deps == 0)
			{
				return Eveluation();
			}
	
			ArrayList<CardInfo> outlist;
			if (lastbig.r == r)
			{
				outlist = FindFirstOutCard(r);
			}
			else
			{
				outlist = FindBigger(r, lastbig);
			}
	
			int retvalue = 0;
			if (r.no == 0)
			{
				retvalue = Integer.MIN_VALUE;
			}
			else
			{
				retvalue = Integer.MAX_VALUE;
			}
	
			CardInfo last = null;
			for (CardInfo c : outlist)
			{
				if (last != null && last.cardstr.equals(c.cardstr))
				{
					continue;
				}
				last = c;
	
				int oldsize = r.card.size();
				r.RemoveCard(c);
	
				CardInfo newlastbig;
				if (c.type == CardType.ct_pass)
				{
					newlastbig = (CardInfo) lastbig.clone();
				}
				else
				{
					newlastbig = (CardInfo) c.clone();
				}
	
				Robot next = r.next;
	
				int value = MinMaxCard(deps - 1, next, newlastbig, null);
	
				r.AddCard(c);
	
				if (r.card.size() != oldsize)
				{
					System.out.println("aaa");
				}
	
				if (r.no == 0)
				{
					if (value > retvalue)
					{
						retvalue = value;
						if (ret != null)
						{
							ret.copyfrom(c);
						}
					}
				}
				else
				{
					if (value < retvalue)
					{
						retvalue = value;
						if (ret != null)
						{
							ret.copyfrom(c);
						}
					}
				}
			}
	
			if (ret != null && ret.cardstr == null)
			{
				ret.copyfrom(new CardInfo(CardType.ct_pass, 0, r, new int[0], 0));
			}
	
			return retvalue;
		}
	
		public int AlphaBeta(int deps, Robot r, CardInfo lastbig, CardInfo ret, int alpha, int beta)
		{
			if (IsEnd())
			{
				if (A.IsEnd())
				{
					return 9999999;
				}
				else
				{
					return -9999999;
				}
			}
	
			if (deps == 0)
			{
				return Eveluation();
			}
	
			ArrayList<CardInfo> outlist;
			if (lastbig.r == r)
			{
				outlist = FindFirstOutCard(r);
			}
			else
			{
				outlist = FindBigger(r, lastbig);
			}
	
			if (r.no == 0)
			{
				CardInfo last = null;
				for (CardInfo c : outlist)
				{
					if (last != null && last.cardstr.equals(c.cardstr))
					{
						continue;
					}
					last = c;
	
					int oldsize = r.card.size();
					r.RemoveCard(c);
	
					CardInfo newlastbig;
					if (c.type == CardType.ct_pass)
					{
						newlastbig = (CardInfo) lastbig.clone();
					}
					else
					{
						newlastbig = (CardInfo) c.clone();
					}
	
					Robot next = r.next;
	
					int value = AlphaBeta(deps - 1, next, newlastbig, null, alpha, beta);
	
					r.AddCard(c);
	
					if (r.card.size() != oldsize)
					{
						System.out.println("aaa");
					}
	
					if (value > alpha)
					{
						alpha = value;
						if (ret != null)
						{
							ret.copyfrom(c);
						}
					}
					else if (value == alpha)
					{
						if (ret != null && ret.type == CardType.ct_pass)
						{
							ret.copyfrom(c);
						}
					}
					if (value >= beta)
					{
						return beta;
					}
				}
	
				if (ret != null && ret.cardstr == null)
				{
					ret.copyfrom(new CardInfo(CardType.ct_pass, 0, r, new int[0], 0));
				}
	
				return alpha;
			}
			else
			{
				CardInfo last = null;
				for (CardInfo c : outlist)
				{
					if (last != null && last.cardstr.equals(c.cardstr))
					{
						continue;
					}
					last = c;
	
					int oldsize = r.card.size();
					r.RemoveCard(c);
	
					CardInfo newlastbig;
					if (c.type == CardType.ct_pass)
					{
						newlastbig = (CardInfo) lastbig.clone();
					}
					else
					{
						newlastbig = (CardInfo) c.clone();
					}
	
					Robot next = r.next;
	
					int value = AlphaBeta(deps - 1, next, newlastbig, null, alpha, beta);
	
					r.AddCard(c);
	
					if (r.card.size() != oldsize)
					{
						System.out.println("aaa");
					}
	
					//System.out.println("deps " + deps + " robot[" + r.no + "] try card " + c.cardstr + " value " + value);
	
					if (value < beta)
					{
						beta = value;
						if (ret != null)
						{
							ret.copyfrom(c);
						}
					}
					else if (value == beta)
					{
						if (ret != null && ret.type == CardType.ct_pass)
						{
							ret.copyfrom(c);
						}
					}
					if (alpha >= value)
					{
						return alpha;
					}
				}
	
				if (ret != null && ret.cardstr == null)
				{
					ret.copyfrom(new CardInfo(CardType.ct_pass, 0, r, new int[0], 0));
				}
	
				return beta;
			}
		}
	
		public int Eveluation()
		{
			int a = EveluationCard(A);
			int b = EveluationCard(B);
			int c = EveluationCard(C);
			return a - (b + c);
		}
	
		public int EveluationNum(int num)
		{
			return (10 - num) * EveluationOne(5);
		}
	
		public int EveluationOne(int card)
		{
			return card * card / 2;
		}
	
		public int EveluationTwo(int card)
		{
			return card * EveluationOne(3);
		}
	
		public int EveluationThree(int card)
		{
			return card * EveluationTwo(5);
		}
	
		public int EveluationFour(int card)
		{
			return card * EveluationThree(14);
		}
	
		public int EveluationContinue(int card, int num)
		{
			return num * EveluationOne(card);
		}
	
		public int EveluationCard(Robot r)
		{
			HashMap<Integer, Integer> tmp = new HashMap<Integer, Integer>();
			for (Integer c : r.card)
			{
				if (tmp.get(c) == null)
				{
					tmp.put(c, 1);
				}
				else
				{
					tmp.put(c, tmp.get(c) + 1);
				}
			}
	
			int ret = 0;
			for (Map.Entry<Integer, Integer> e : tmp.entrySet())
			{
				int card = e.getKey().intValue();
				int num = e.getValue().intValue();
				if (num == 1)
				{
					ret += EveluationOne(card);
				}
				if (num == 2)
				{
					ret += EveluationTwo(card);
				}
				if (num == 3)
				{
					ret += EveluationThree(card);
				}
				if (num == 4)
				{
					ret += EveluationFour(card);
				}
			}
	
			for (int i = 0; i < r.card.size() - 3; i++)
			{
				int cur = r.card.get(i);
				int next = r.card.get(i + 1);
	
				if (cur == 14 && next == 15)
				{
					ret += EveluationFour(15);
					break;
				}
			}
	
			int num = 0;
			for (int i = 0; i < r.card.size() - 1; i++)
			{
				int cur = r.card.get(i);
				int next = r.card.get(i + 1);
				if (cur + 1 == next)
				{
					num++;
				}
				else
				{
					if (num >= 5)
					{
						ret += EveluationContinue(cur, num);
					}
					num = 0;
				}
			}
			if (num >= 5)
			{
				ret += EveluationContinue(r.card.get(r.card.size() - 1), num);
				num = 0;
			}
	
			ret += EveluationNum(r.card.size());
	
			return ret;
		}
	*/
	public ArrayList<CardInfo> FindBigger(Robot r, CardInfo lastbig)
	{
		ArrayList<CardInfo> ret = new ArrayList<CardInfo>();

		if (lastbig.type == CardType.ct_single)
		{
			ret = FindBiggerSingle(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_double)
		{
			ret = FindBiggerDouble(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_three)
		{
			ret = FindBiggerThree(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_boom)
		{
			ret = FindBiggerBoom(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_three_plus_one)
		{
			ret = FindBiggerThreePlusOne(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_three_plus_two)
		{
			ret = FindBiggerThreePlusTwo(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_four_plus_two)
		{
			ret = FindBiggerFourPlusTwo(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_four_plus_two_double)
		{
			ret = FindBiggerFourPlusTwoDouble(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_continue)
		{
			ret = FindBiggerContinue(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_double_continue)
		{
			ret = FindBiggerDoubleContinue(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_double_three)
		{
			ret = FindBiggerDoubleThree(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_double_three_plus_one)
		{
			ret = FindBiggerDoubleThreePlusOne(ret, r, lastbig);
		}
		else if (lastbig.type == CardType.ct_double_three_plus_two)
		{
			ret = FindBiggerDoubleThreePlusTwo(ret, r, lastbig);
		}

		if (lastbig.type != CardType.ct_boom)
		{
			if (is_need_boom(r))
			{
				CardInfo tmpboom = (CardInfo) lastbig.clone();
				tmpboom.max = 0;
				tmpboom.cardnum = 4;

				ret = FindBiggerBoom(ret, r, tmpboom);
			}
		}

		if (have_double_king(r))
		{
			if (is_need_boom(r))
			{
				ret.add(new CardInfo(CardType.ct_double_king, 15, r, new int[]
				{ 14, 15 }, 2));
			}
		}

		ret.add(new CardInfo(CardType.ct_pass, 0, r, new int[0], 0));

		return ret;
	}

	private int[] AppendCard(int[] src, int start, int[] add)
	{
		for (int k = 0; k < add.length; k++)
		{
			src[start] = add[k];
			start++;
		}
		return src;
	}

	public ArrayList<CardInfo> FindBiggerDoubleThreePlusTwo(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		int num = lastbig.cardnum / 5;
		for (int i = lastbig.max + 1; i <= 12 - num; i++)
		{
			boolean find = true;
			for (int j = 0; j < num; j++)
			{
				if (r.cardmap[i + j] > 2)
				{
				}
				else
				{
					find = false;
					break;
				}
			}

			if (find)
			{
				for (int j = 0; j < num; j++)
				{
					r.cardmap[i + j] -= 3;
				}

				ret = ChooseDoubleThreePlusTwo(ret, r, lastbig, i, num, new int[0]);

				for (int j = 0; j < num; j++)
				{
					r.cardmap[i + j] += 3;
				}
			}
		}

		return ret;
	}

	private ArrayList<CardInfo> ChooseDoubleThreePlusTwo(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig, int starti,
			int deps, int[] card)
	{
		if (deps == 0)
		{
			int[] cardstr = new int[lastbig.cardnum];
			for (int k = 0; k < lastbig.cardnum / 5 * 3; k += 3)
			{
				cardstr[k] = starti + (k / 3);
				cardstr[k + 1] = starti + (k / 3);
				cardstr[k + 2] = starti + (k / 3);
			}
			cardstr = AppendCard(cardstr, lastbig.cardnum / 5 * 3, card);

			ret.add(new CardInfo(CardType.ct_double_three_plus_two, starti, r, cardstr, lastbig.cardnum));

			return ret;
		}

		for (int i = 1; i <= 12; i++)
		{
			if (r.cardmap[i] > 1)
			{
				int[] mycard = new int[card.length + 2];
				mycard = AppendCard(mycard, 0, card);
				mycard[card.length] = i;
				mycard[card.length + 1] = i;

				r.cardmap[i] -= 2;

				ret = ChooseDoubleThreePlusTwo(ret, r, lastbig, starti, deps - 1, mycard);

				r.cardmap[i] += 2;
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerDoubleThreePlusOne(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		int num = lastbig.cardnum / 4;
		for (int i = lastbig.max + 1; i <= 12 - num; i++)
		{
			boolean find = true;
			for (int j = 0; j < num; j++)
			{
				if (r.cardmap[i + j] > 2)
				{
				}
				else
				{
					find = false;
					break;
				}
			}

			if (find)
			{
				for (int j = 0; j < num; j++)
				{
					r.cardmap[i + j] -= 3;
				}

				ret = ChooseDoubleThreePlusOne(ret, r, lastbig, i, num, new int[0]);

				for (int j = 0; j < num; j++)
				{
					r.cardmap[i + j] += 3;
				}
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> ChooseDoubleThreePlusOne(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig, int starti,
			int deps, int[] card)
	{
		if (deps == 0)
		{
			int[] cardstr = new int[lastbig.cardnum];
			for (int k = 0; k < lastbig.cardnum / 4 * 3; k += 3)
			{
				cardstr[k] = starti + (k / 3);
				cardstr[k + 1] = starti + (k / 3);
				cardstr[k + 2] = starti + (k / 3);
			}
			cardstr = AppendCard(cardstr, lastbig.cardnum / 4 * 3, card);

			ret.add(new CardInfo(CardType.ct_double_three_plus_one, starti, r, cardstr, lastbig.cardnum));

			return ret;
		}

		for (int i = 1; i <= 15; i++)
		{
			if (r.cardmap[i] > 0)
			{
				int[] mycard = new int[card.length + 1];
				mycard = AppendCard(mycard, 0, card);
				mycard[card.length] = i;

				r.cardmap[i]--;

				ret = ChooseDoubleThreePlusOne(ret, r, lastbig, starti, deps - 1, mycard);

				r.cardmap[i]++;
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerDoubleThree(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		int num = lastbig.cardnum / 3;
		for (int i = lastbig.max + 1; i <= 12 - num; i++)
		{
			boolean find = true;
			for (int j = 0; j < num; j++)
			{
				if (r.cardmap[i + j] > 2)
				{
				}
				else
				{
					find = false;
					break;
				}
			}

			if (find)
			{
				int[] cardstr = new int[lastbig.cardnum];
				for (int j = 0; j < lastbig.cardnum; j += 3)
				{
					cardstr[j] = i + (j / 3);
					cardstr[j + 1] = i + (j / 3);
					cardstr[j + 2] = i + (j / 3);
				}

				ret.add(new CardInfo(CardType.ct_double_three, i, r, cardstr, lastbig.cardnum));
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerDoubleContinue(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		int num = lastbig.cardnum / 2;
		for (int i = lastbig.max + 1; i <= 12 - num; i++)
		{
			boolean find = true;
			for (int j = 0; j < num; j++)
			{
				if (r.cardmap[i + j] > 1)
				{
				}
				else
				{
					find = false;
					break;
				}
			}

			if (find)
			{
				int[] cardstr = new int[lastbig.cardnum];
				for (int j = 0; j < lastbig.cardnum; j += 2)
				{
					cardstr[j] = i + (j / 2);
					cardstr[j + 1] = i + (j / 2);
				}

				ret.add(new CardInfo(CardType.ct_double_continue, i, r, cardstr, lastbig.cardnum));
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerContinue(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = lastbig.max + 1; i <= 12 - lastbig.cardnum; i++)
		{
			boolean find = true;
			for (int j = 0; j < lastbig.cardnum; j++)
			{
				if (r.cardmap[i + j] > 0)
				{
				}
				else
				{
					find = false;
					break;
				}
			}

			if (find)
			{
				int[] cardstr = new int[lastbig.cardnum];
				for (int j = 0; j < lastbig.cardnum; j++)
				{
					cardstr[j] = i + j;
				}

				ret.add(new CardInfo(CardType.ct_continue, i, r, cardstr, lastbig.cardnum));
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerFourPlusTwoDouble(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = lastbig.max + 1; i <= 13; i++)
		{
			if (r.cardmap[i] > 3)
			{
				// 随机选2个
				for (int j = 1; j <= 13; j++)
				{
					if (i != j && r.cardmap[j] > 1)
					{
						for (int k = j + 1; k <= 13; k++)
						{
							if (i != k && r.cardmap[k] > 1)
							{
								int[] cardstr = new int[lastbig.cardnum];
								cardstr[0] = i;
								cardstr[1] = i;
								cardstr[2] = i;
								cardstr[3] = i;
								cardstr[4] = j;
								cardstr[5] = j;
								cardstr[6] = k;
								cardstr[7] = k;

								ret.add(new CardInfo(CardType.ct_four_plus_two_double, i, r, cardstr, lastbig.cardnum));
							}
						}
					}
				}
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerFourPlusTwo(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = lastbig.max + 1; i <= 13; i++)
		{
			if (r.cardmap[i] > 3)
			{
				// 随机选2个
				for (int j = 1; j <= 13; j++)
				{
					if (i != j && r.cardmap[j] > 1)
					{
						int[] cardstr = new int[lastbig.cardnum];
						cardstr[0] = i;
						cardstr[1] = i;
						cardstr[2] = i;
						cardstr[3] = i;
						cardstr[4] = j;
						cardstr[5] = j;

						ret.add(new CardInfo(CardType.ct_four_plus_two, i, r, cardstr, lastbig.cardnum));
					}
				}

				for (int j = 1; j <= 15; j++)
				{
					if (i != j && r.cardmap[j] > 0)
					{
						for (int k = j + 1; k <= 15; k++)
						{
							if (i != k && r.cardmap[k] > 0)
							{
								int[] cardstr = new int[lastbig.cardnum];
								cardstr[0] = i;
								cardstr[1] = i;
								cardstr[2] = i;
								cardstr[3] = i;
								cardstr[4] = j;
								cardstr[5] = k;

								ret.add(new CardInfo(CardType.ct_four_plus_two, i, r, cardstr, lastbig.cardnum));
							}
						}
					}
				}
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerThreePlusTwo(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = lastbig.max + 1; i <= 13; i++)
		{
			if (r.cardmap[i] > 2)
			{
				// 随机选个
				for (int j = 1; j <= 13; j++)
				{
					if (i != j && r.cardmap[j] > 1)
					{
						int[] cardstr = new int[lastbig.cardnum];
						cardstr[0] = i;
						cardstr[1] = i;
						cardstr[2] = i;
						cardstr[3] = j;
						cardstr[4] = j;

						ret.add(new CardInfo(CardType.ct_three_plus_two, i, r, cardstr, lastbig.cardnum));
					}
				}
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerThreePlusOne(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = lastbig.max + 1; i <= 13; i++)
		{
			if (r.cardmap[i] > 2)
			{
				// 随机选个
				for (int j = 1; j <= 15; j++)
				{
					if (i != j && r.cardmap[j] > 0)
					{
						int[] cardstr = new int[lastbig.cardnum];
						cardstr[0] = i;
						cardstr[1] = i;
						cardstr[2] = i;
						cardstr[3] = j;

						ret.add(new CardInfo(CardType.ct_three_plus_one, i, r, cardstr, lastbig.cardnum));
					}
				}
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerThree(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = lastbig.max + 1; i <= 13; i++)
		{
			if (r.cardmap[i] > 2)
			{
				int[] cardstr = new int[lastbig.cardnum];
				cardstr[0] = i;
				cardstr[1] = i;
				cardstr[2] = i;
				ret.add(new CardInfo(CardType.ct_three, i, r, cardstr, lastbig.cardnum));
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerDouble(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = lastbig.max + 1; i <= 13; i++)
		{
			if (r.cardmap[i] > 1)
			{
				int[] cardstr = new int[lastbig.cardnum];
				cardstr[0] = i;
				cardstr[1] = i;
				ret.add(new CardInfo(CardType.ct_double, i, r, cardstr, lastbig.cardnum));
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerSingle(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = lastbig.max + 1; i <= 15; i++)
		{
			if (r.cardmap[i] > 0)
			{
				int[] cardstr = new int[lastbig.cardnum];
				cardstr[0] = i;
				ret.add(new CardInfo(CardType.ct_single, i, r, cardstr, lastbig.cardnum));
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerBoom(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = lastbig.max + 1; i <= 12; i++)
		{
			if (r.cardmap[i] > 3)
			{
				int[] cardstr = new int[lastbig.cardnum];
				cardstr[0] = i;
				cardstr[1] = i;
				cardstr[2] = i;
				cardstr[3] = i;
				ret.add(new CardInfo(CardType.ct_boom, i, r, cardstr, lastbig.cardnum));
			}
		}
		return ret;
	}

	public boolean have_double_king(Robot r)
	{
		return r.cardmap[14] > 0 && r.cardmap[15] > 0;
	}

	public ArrayList<CardInfo> FindFirstOutCard(Robot r)
	{
		ArrayList<CardInfo> ret = new ArrayList<CardInfo>();

		CardInfo lastbig = new CardInfo(CardType.ct_single, 0, r, new int[0], 0);

		lastbig.type = CardType.ct_single;
		{
			lastbig.cardnum = 1;
			ret = FindBiggerSingle(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_double;
		{
			lastbig.cardnum = 2;
			ret = FindBiggerDouble(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_three;
		{
			lastbig.cardnum = 3;
			ret = FindBiggerThree(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_three_plus_one;
		{
			lastbig.cardnum = 4;
			ret = FindBiggerThreePlusOne(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_three_plus_two;
		{
			lastbig.cardnum = 5;
			ret = FindBiggerThreePlusTwo(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_four_plus_two;
		if (is_need_four_plus_two(r))
		{
			lastbig.cardnum = 6;
			ret = FindBiggerFourPlusTwo(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_four_plus_two_double;
		if (is_need_four_plus_two_double(r))
		{
			lastbig.cardnum = 8;
			ret = FindBiggerFourPlusTwoDouble(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_continue;
		for (int i = 5; i <= 13; i++)
		{
			lastbig.cardnum = i;
			ret = FindBiggerContinue(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_double_continue;
		for (int i = 6; i <= 12; i += 2)
		{
			lastbig.cardnum = i;
			ret = FindBiggerDoubleContinue(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_double_three;
		for (int i = 6; i <= 12; i += 3)
		{
			lastbig.cardnum = i;
			ret = FindBiggerDoubleThree(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_double_three_plus_one;
		for (int i = 8; i <= 16; i += 4)
		{
			lastbig.cardnum = i;
			ret = FindBiggerDoubleThreePlusOne(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_double_three_plus_two;
		for (int i = 10; i <= 15; i += 5)
		{
			lastbig.cardnum = i;
			ret = FindBiggerDoubleThreePlusTwo(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_boom;
		{
			lastbig.cardnum = 4;
			ret = FindBiggerBoom(ret, r, lastbig);
		}

		if (have_double_king(r))
		{
			ret.add(new CardInfo(CardType.ct_double_king, 15, r, new int[]
			{ 14, 15 }, 2));
		}

		return ret;
	}

	public boolean is_need_four_plus_two(Robot r)
	{
		if (r.cardnum <= 8)
		{
			return true;
		}
		return false;
	}

	public boolean is_need_four_plus_two_double(Robot r)
	{
		if (r.cardnum <= 10)
		{
			return true;
		}
		return false;
	}

	public boolean is_need_boom(Robot r)
	{
		if (A.cardnum <= 5 || B.cardnum <= 5 || C.cardnum <= 5)
		{
			return true;
		}
		return false;
	}
}

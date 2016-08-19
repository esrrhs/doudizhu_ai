package ai;

import java.util.ArrayList;
import java.util.HashMap;
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

		CardInfo lastbig = new CardInfo(CardType.ct_single, -1, A, "", 1);

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

	public void Dispatch()
	{
		boolean test = true;
		if (test)
		{
			String a = "2,4,6,8";
			String b = "1,3,5,7,8";
			String c = "1,2,4,4,5,5,7,7,9,10,12,13,15";

			for (String s : a.split("\\,"))
			{
				A.AddCard(Integer.parseInt(s));
			}

			for (String s : b.split("\\,"))
			{
				B.AddCard(Integer.parseInt(s));
			}

			for (String s : c.split("\\,"))
			{
				C.AddCard(Integer.parseInt(s));
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
			A.SortCard();

			for (int i = 0; i < 17; i++)
			{
				int index = rand.nextInt(tmp.size());
				int card = tmp.get(index);
				tmp.remove(index);
				B.AddCard(card);
			}
			B.SortCard();

			for (int i = 0; i < tmp.size(); i++)
			{
				int card = tmp.get(i);
				C.AddCard(card);
			}
			C.SortCard();
		}
	}

	public CardInfo OutCard(Robot r, CardInfo lastbig)
	{
		CardInfo ret = new CardInfo();
		//MinMaxCard(6, r, lastbig, ret);
		AlphaBeta(6, r, lastbig, ret, Integer.MIN_VALUE, Integer.MAX_VALUE);
		return ret;
	}

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

			//System.out.println("deps " + deps + " robot[" + r.no + "] try card " + c.cardstr + " value " + value);

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
			ret.copyfrom(new CardInfo(CardType.ct_pass, -1, r, "", 0));
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

				//System.out.println("deps " + deps + " robot[" + r.no + "] try card " + c.cardstr + " value " + value);

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
				ret.copyfrom(new CardInfo(CardType.ct_pass, -1, r, "", 0));
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
				ret.copyfrom(new CardInfo(CardType.ct_pass, -1, r, "", 0));
			}

			return beta;
		}
	}

	public int Eveluation()
	{
		int a = EveluationCard(A);
		int b = EveluationCard(B);
		int c = EveluationCard(C);
		return a - (b + c) + 50 * (B.card.size() - A.card.size()) + 50 * (C.card.size() - A.card.size());
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
				ret += card;
			}
			if (num == 2)
			{
				ret += card * 5;
			}
			if (num == 3)
			{
				ret += card * 60;
			}
			if (num == 4)
			{
				ret += card * 1000;
			}
		}

		for (int i = 0; i < r.card.size() - 3; i++)
		{
			int cur = r.card.get(i);
			int next = r.card.get(i + 1);

			if (cur == 14 && next == 15)
			{
				ret += 20000;
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
					ret += cur * 30 + num * 2;
				}
				num = 0;
			}
		}
		if (num >= 5)
		{
			ret += r.card.get(r.card.size() - 1) * 30 + num * 2;
			num = 0;
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBigger(Robot r, CardInfo lastbig)
	{
		ArrayList<CardInfo> ret = new ArrayList<CardInfo>();

		ret.add(new CardInfo(CardType.ct_pass, -1, r, "", 0));

		if (lastbig.type == CardType.ct_double_king)
		{
			return ret;
		}

		if (have_double_king(r))
		{
			ret.add(new CardInfo(CardType.ct_double_king, 15, r, "14,15", 2));
		}

		CardInfo tmpboom = (CardInfo) lastbig.clone();
		tmpboom.max = 0;
		tmpboom.cardnum = 4;

		if (lastbig.type == CardType.ct_single)
		{
			ret = FindBiggerSingle(ret, r, lastbig);
			ret = FindBiggerBoom(ret, r, tmpboom);
			return ret;
		}

		if (lastbig.type == CardType.ct_double)
		{
			ret = FindBiggerDouble(ret, r, lastbig);
			ret = FindBiggerBoom(ret, r, tmpboom);
			return ret;
		}

		if (lastbig.type == CardType.ct_three)
		{
			ret = FindBiggerThree(ret, r, lastbig);
			ret = FindBiggerBoom(ret, r, tmpboom);
			return ret;
		}

		if (lastbig.type == CardType.ct_boom)
		{
			ret = FindBiggerBoom(ret, r, lastbig);
			return ret;
		}

		if (lastbig.type == CardType.ct_three_plus_one)
		{
			ret = FindBiggerThreePlusOne(ret, r, lastbig);
			ret = FindBiggerBoom(ret, r, tmpboom);
			return ret;
		}

		if (lastbig.type == CardType.ct_three_plus_two)
		{
			ret = FindBiggerThreePlusTwo(ret, r, lastbig);
			ret = FindBiggerBoom(ret, r, tmpboom);
			return ret;
		}

		if (lastbig.type == CardType.ct_four_plus_two)
		{
			ret = FindBiggerFourPlusTwo(ret, r, lastbig);
			ret = FindBiggerBoom(ret, r, tmpboom);
			return ret;
		}

		if (lastbig.type == CardType.ct_four_plus_two_double)
		{
			ret = FindBiggerFourPlusTwoDouble(ret, r, lastbig);
			ret = FindBiggerBoom(ret, r, tmpboom);
			return ret;
		}

		if (lastbig.type == CardType.ct_continue)
		{
			ret = FindBiggerContinue(ret, r, lastbig);
			ret = FindBiggerBoom(ret, r, tmpboom);
			return ret;
		}

		if (lastbig.type == CardType.ct_double_continue)
		{
			ret = FindBiggerDoubleContinue(ret, r, lastbig);
			ret = FindBiggerBoom(ret, r, tmpboom);
			return ret;
		}

		if (lastbig.type == CardType.ct_double_three)
		{
			ret = FindBiggerDoubleThree(ret, r, lastbig);
			ret = FindBiggerBoom(ret, r, tmpboom);
			return ret;
		}

		if (lastbig.type == CardType.ct_double_three_plus_one)
		{
			ret = FindBiggerDoubleThreePlusOne(ret, r, lastbig);
			ret = FindBiggerBoom(ret, r, tmpboom);
			return ret;
		}

		if (lastbig.type == CardType.ct_double_three_plus_two)
		{
			ret = FindBiggerDoubleThreePlusTwo(ret, r, lastbig);
			ret = FindBiggerBoom(ret, r, tmpboom);
			return ret;
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerDoubleThreePlusTwo(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		int threenum = lastbig.cardnum / 5;
		for (int i = 0; i < r.card.size() - threenum * 3; i++)
		{
			if (r.card.get(i).intValue() > lastbig.max)
			{
				boolean find = true;
				for (int j = 0; j < threenum * 3 - 3; j += 3)
				{
					if (r.card.get(i + j).intValue() == r.card.get(i + j + 1).intValue()
							&& r.card.get(i + j).intValue() == r.card.get(i + j + 2).intValue()
							&& r.card.get(i + j + 2).intValue() + 1 == r.card.get(i + j + 3).intValue()
							&& r.card.get(i + j + 3).intValue() == r.card.get(i + j + 4).intValue()
							&& r.card.get(i + j + 3).intValue() == r.card.get(i + j + 5).intValue())
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
					ret = ChooseDoubleThreePlusTwo(ret, r, lastbig, 0, threenum, i, i + threenum * 3, "", threenum);
				}
			}
		}

		return ret;
	}

	private ArrayList<CardInfo> ChooseDoubleThreePlusTwo(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig, int start,
			int deps, int lowex, int highex, String card, int threenum)
	{
		if (deps == 0)
		{
			String cardstr = "";
			for (int k = 0; k < threenum * 3; k++)
			{
				if (k != 0)
				{
					cardstr += ",";
				}
				cardstr += r.card.get(lowex + k).intValue();
			}
			cardstr += card;

			ret.add(new CardInfo(CardType.ct_double_three_plus_two, r.card.get(lowex).intValue(), r, cardstr,
					lastbig.cardnum));

			return ret;
		}

		for (int i = start; i < r.card.size() - 1; i++)
		{
			if ((i < lowex || i >= highex) && (i + 1 < lowex || i + 1 >= highex))
			{
				if (r.card.get(i).intValue() == r.card.get(i + 1).intValue())
				{
					String mycard = card + "," + r.card.get(i).intValue() + "," + r.card.get(i + 1).intValue();
					ret = ChooseDoubleThreePlusTwo(ret, r, lastbig, i + 2, deps - 1, lowex, highex, mycard, threenum);
				}
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerDoubleThreePlusOne(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		int threenum = lastbig.cardnum / 4;
		for (int i = 0; i < r.card.size() - threenum * 3; i++)
		{
			if (r.card.get(i).intValue() > lastbig.max)
			{
				boolean find = true;
				for (int j = 0; j < threenum * 3 - 3; j += 3)
				{
					if (r.card.get(i + j).intValue() == r.card.get(i + j + 1).intValue()
							&& r.card.get(i + j).intValue() == r.card.get(i + j + 2).intValue()
							&& r.card.get(i + j + 2).intValue() + 1 == r.card.get(i + j + 3).intValue()
							&& r.card.get(i + j + 3).intValue() == r.card.get(i + j + 4).intValue()
							&& r.card.get(i + j + 3).intValue() == r.card.get(i + j + 5).intValue())
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
					ret = ChooseDoubleThreePlusOne(ret, r, lastbig, 0, threenum, i, i + threenum * 3, "", threenum);
				}
			}
		}

		return ret;
	}

	private ArrayList<CardInfo> ChooseDoubleThreePlusOne(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig, int start,
			int deps, int lowex, int highex, String card, int threenum)
	{
		if (deps == 0)
		{
			String cardstr = "";
			for (int k = 0; k < threenum * 3; k++)
			{
				if (k != 0)
				{
					cardstr += ",";
				}
				cardstr += r.card.get(lowex + k).intValue();
			}
			cardstr += card;

			ret.add(new CardInfo(CardType.ct_double_three_plus_one, r.card.get(lowex).intValue(), r, cardstr,
					lastbig.cardnum));

			return ret;
		}

		for (int i = start; i < r.card.size(); i++)
		{
			if (i < lowex || i >= highex)
			{
				String mycard = card + "," + r.card.get(i).intValue();
				ret = ChooseDoubleThreePlusOne(ret, r, lastbig, i + 1, deps - 1, lowex, highex, mycard, threenum);
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerDoubleThree(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = 0; i < r.card.size() - lastbig.cardnum; i++)
		{
			if (r.card.get(i).intValue() > lastbig.max)
			{
				boolean find = true;
				for (int j = 0; j < lastbig.cardnum - 3; j += 3)
				{
					if (r.card.get(i + j).intValue() == r.card.get(i + j + 1).intValue()
							&& r.card.get(i + j).intValue() == r.card.get(i + j + 2).intValue()
							&& r.card.get(i + j + 2).intValue() + 1 == r.card.get(i + j + 3).intValue()
							&& r.card.get(i + j + 3).intValue() == r.card.get(i + j + 4).intValue()
							&& r.card.get(i + j + 3).intValue() == r.card.get(i + j + 5).intValue())
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
					String cardstr = "";
					for (int j = 0; j < lastbig.cardnum; j++)
					{
						if (j != 0)
						{
							cardstr += ",";
						}
						cardstr += r.card.get(i + j).intValue();
					}

					ret.add(new CardInfo(CardType.ct_double_three, r.card.get(i).intValue(), r, cardstr,
							lastbig.cardnum));
				}
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerDoubleContinue(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = 0; i < r.card.size() - lastbig.cardnum; i++)
		{
			if (r.card.get(i).intValue() > lastbig.max)
			{
				boolean find = true;
				for (int j = 0; j < lastbig.cardnum - 2; j += 2)
				{
					if (r.card.get(i + j).intValue() == r.card.get(i + j + 1).intValue()
							&& r.card.get(i + j + 1).intValue() + 1 == r.card.get(i + j + 2).intValue()
							&& r.card.get(i + j + 2).intValue() == r.card.get(i + j + 3).intValue())
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
					String cardstr = "";
					for (int j = 0; j < lastbig.cardnum; j++)
					{
						if (j != 0)
						{
							cardstr += ",";
						}
						cardstr += r.card.get(i + j).intValue();
					}

					ret.add(new CardInfo(CardType.ct_double_continue, r.card.get(i).intValue(), r, cardstr,
							lastbig.cardnum));
				}
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerContinue(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = 0; i < r.card.size() - lastbig.cardnum; i++)
		{
			if (r.card.get(i).intValue() > lastbig.max)
			{
				boolean find = true;
				for (int j = 0; j < lastbig.cardnum - 1; j++)
				{
					if (r.card.get(i + j).intValue() + 1 == r.card.get(i + j + 1).intValue())
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
					String cardstr = "";
					for (int j = 0; j < lastbig.cardnum; j++)
					{
						if (j != 0)
						{
							cardstr += ",";
						}
						cardstr += r.card.get(i + j).intValue();
					}

					ret.add(new CardInfo(CardType.ct_continue, r.card.get(i).intValue(), r, cardstr, lastbig.cardnum));
				}
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerFourPlusTwoDouble(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = 0; i < r.card.size() - 3; i++)
		{
			if (r.card.get(i).intValue() > lastbig.max && r.card.get(i).intValue() == r.card.get(i + 1).intValue()
					&& r.card.get(i).intValue() == r.card.get(i + 2).intValue()
					&& r.card.get(i).intValue() == r.card.get(i + 3).intValue())
			{
				// 随机选2个
				for (int j = 0; j < r.card.size() - 1; j++)
				{
					if (r.card.get(i).intValue() != r.card.get(j).intValue()
							&& r.card.get(j).intValue() == r.card.get(j + 1).intValue())
					{

						for (int z = j + 2; z < r.card.size() - 1; z++)
						{
							if (r.card.get(i).intValue() != r.card.get(z).intValue()
									&& r.card.get(j).intValue() != r.card.get(z).intValue()
									&& r.card.get(z).intValue() == r.card.get(z + 1).intValue())
							{
								String cardstr = "";
								cardstr += r.card.get(i).intValue() + ",";
								cardstr += r.card.get(i + 1).intValue() + ",";
								cardstr += r.card.get(i + 2).intValue() + ",";
								cardstr += r.card.get(i + 3).intValue() + ",";
								cardstr += r.card.get(j).intValue() + ",";
								cardstr += r.card.get(j + 1).intValue() + ",";
								cardstr += r.card.get(z).intValue() + ",";
								cardstr += r.card.get(z + 1).intValue();

								ret.add(new CardInfo(CardType.ct_four_plus_two_double, r.card.get(i).intValue(), r,
										cardstr, lastbig.cardnum));
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
		for (int i = 0; i < r.card.size() - 3; i++)
		{
			if (r.card.get(i).intValue() > lastbig.max && r.card.get(i).intValue() == r.card.get(i + 1).intValue()
					&& r.card.get(i).intValue() == r.card.get(i + 2).intValue()
					&& r.card.get(i).intValue() == r.card.get(i + 3).intValue())
			{
				// 随机选2个
				for (int j = 0; j < r.card.size(); j++)
				{
					if (r.card.get(i).intValue() != r.card.get(j).intValue())
					{
						for (int z = j + 1; z < r.card.size(); z++)
						{
							if (r.card.get(i).intValue() != r.card.get(z).intValue())
							{
								String cardstr = "";
								cardstr += r.card.get(i).intValue() + ",";
								cardstr += r.card.get(i + 1).intValue() + ",";
								cardstr += r.card.get(i + 2).intValue() + ",";
								cardstr += r.card.get(i + 3).intValue() + ",";
								cardstr += r.card.get(j).intValue() + ",";
								cardstr += r.card.get(z).intValue();

								ret.add(new CardInfo(CardType.ct_four_plus_two, r.card.get(i).intValue(), r, cardstr,
										lastbig.cardnum));
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
		for (int i = 0; i < r.card.size() - 2; i++)
		{
			if (r.card.get(i).intValue() > lastbig.max && r.card.get(i).intValue() == r.card.get(i + 1).intValue()
					&& r.card.get(i).intValue() == r.card.get(i + 2).intValue())
			{
				// 随机选个
				for (int j = 0; j < r.card.size() - 1; j++)
				{
					if (r.card.get(i).intValue() != r.card.get(j).intValue()
							&& r.card.get(j).intValue() == r.card.get(j + 1).intValue())
					{
						String cardstr = "";
						cardstr += r.card.get(i).intValue() + ",";
						cardstr += r.card.get(i + 1).intValue() + ",";
						cardstr += r.card.get(i + 2).intValue() + ",";
						cardstr += r.card.get(j).intValue() + ",";
						cardstr += r.card.get(j + 1).intValue();

						ret.add(new CardInfo(CardType.ct_three_plus_two, r.card.get(i).intValue(), r, cardstr,
								lastbig.cardnum));
					}
				}
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerThreePlusOne(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = 0; i < r.card.size() - 2; i++)
		{
			if (r.card.get(i).intValue() > lastbig.max && r.card.get(i).intValue() == r.card.get(i + 1).intValue()
					&& r.card.get(i).intValue() == r.card.get(i + 2).intValue())
			{
				// 随机选个
				for (int j = 0; j < r.card.size(); j++)
				{
					if (r.card.get(i).intValue() != r.card.get(j).intValue())
					{
						String cardstr = "";
						cardstr += r.card.get(i).intValue() + ",";
						cardstr += r.card.get(i + 1).intValue() + ",";
						cardstr += r.card.get(i + 2).intValue() + ",";
						cardstr += r.card.get(j).intValue();

						ret.add(new CardInfo(CardType.ct_three_plus_one, r.card.get(i).intValue(), r, cardstr,
								lastbig.cardnum));
					}
				}
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerThree(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = 0; i < r.card.size() - 2; i++)
		{
			if (r.card.get(i).intValue() > lastbig.max && r.card.get(i).intValue() == r.card.get(i + 1).intValue()
					&& r.card.get(i).intValue() == r.card.get(i + 2).intValue())
			{
				String cardstr = "";
				cardstr += r.card.get(i).intValue() + ",";
				cardstr += r.card.get(i + 1).intValue() + ",";
				cardstr += r.card.get(i + 2).intValue();
				ret.add(new CardInfo(CardType.ct_three, r.card.get(i).intValue(), r, cardstr, lastbig.cardnum));
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerDouble(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = 0; i < r.card.size() - 1; i++)
		{
			if (r.card.get(i).intValue() > lastbig.max && r.card.get(i).intValue() == r.card.get(i + 1).intValue()
					&& r.card.get(i).intValue() != 14 && r.card.get(i).intValue() != 15)
			{
				String cardstr = "";
				cardstr += r.card.get(i).intValue() + ",";
				cardstr += r.card.get(i + 1).intValue();
				ret.add(new CardInfo(CardType.ct_double, r.card.get(i).intValue(), r, cardstr, lastbig.cardnum));
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerSingle(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = 0; i < r.card.size(); i++)
		{
			if (r.card.get(i).intValue() > lastbig.max)
			{
				String cardstr = "" + r.card.get(i).intValue();
				ret.add(new CardInfo(CardType.ct_single, r.card.get(i).intValue(), r, cardstr, lastbig.cardnum));
			}
		}
		return ret;
	}

	public ArrayList<CardInfo> FindBiggerBoom(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = 0; i < r.card.size() - 4; i++)
		{
			if (r.card.get(i).intValue() > lastbig.max && r.card.get(i).intValue() == r.card.get(i + 1).intValue()
					&& r.card.get(i).intValue() == r.card.get(i + 2).intValue()
					&& r.card.get(i).intValue() == r.card.get(i + 3).intValue())
			{
				String cardstr = "";
				cardstr += r.card.get(i).intValue() + ",";
				cardstr += r.card.get(i + 1).intValue() + ",";
				cardstr += r.card.get(i + 2).intValue() + ",";
				cardstr += r.card.get(i + 3).intValue();
				ret.add(new CardInfo(CardType.ct_boom, r.card.get(i).intValue(), r, cardstr, lastbig.cardnum));
			}
		}
		return ret;
	}

	public boolean have_double_king(Robot r)
	{
		if (r.card.size() >= 2)
		{
			return r.card.get(r.card.size() - 2) == 14 && r.card.get(r.card.size() - 1) == 15;
		}
		return false;
	}

	public ArrayList<CardInfo> FindFirstOutCard(Robot r)
	{
		ArrayList<CardInfo> ret = new ArrayList<CardInfo>();

		CardInfo lastbig = new CardInfo(CardType.ct_single, -1, r, "", 0);
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

		lastbig.type = CardType.ct_boom;
		{
			lastbig.cardnum = 4;
			ret = FindBiggerBoom(ret, r, lastbig);
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
		{
			lastbig.cardnum = 6;
			ret = FindBiggerFourPlusTwo(ret, r, lastbig);
		}

		lastbig.type = CardType.ct_four_plus_two_double;
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

		if (have_double_king(r))
		{
			ret.add(new CardInfo(CardType.ct_double_king, 15, r, "14,15", 2));
		}

		return ret;
	}
}

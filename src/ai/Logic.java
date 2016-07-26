package ai;

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

		// 一样的牌，打N次
		for (int i = 0; i < N; i++)
		{
			A.ClearStep();
			B.ClearStep();
			C.ClearStep();

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
	}

	public boolean IsEnd()
	{
		return A.IsEnd() || B.IsEnd() || C.IsEnd();
	}

	public void Dispatch()
	{
		LinkedList<Integer> tmp = new LinkedList<Integer>();
		for (int i : AllCard)
		{
			tmp.add(i);
		}

		for (int i = 0; i < 18; i++)
		{
			int index = rand.nextInt(tmp.size());
			int card = tmp.get(index);
			tmp.remove(index);
			A.AddCard(card);
		}
		A.SortCard();

		for (int i = 0; i < 16; i++)
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

	public CardInfo OutCard(Robot r, CardInfo lastbig)
	{
		ArrayList<CardInfo> outlist = FindBigger(r, lastbig);
		int index = rand.nextInt(outlist.size());
		return outlist.get(index);
	}

	public ArrayList<CardInfo> FindBigger(Robot r, CardInfo lastbig)
	{
		ArrayList<CardInfo> ret = new ArrayList<CardInfo>();

		// history
		String cardstate = t.GetCardState();
		Map<String, String> oldout = t.client.hgetAll(cardstate);
		for (Map.Entry<String, String> entry : oldout.entrySet())
		{
			String key = entry.getKey();
			String value = entry.getValue();

			String[] values = value.split("\\$");

			int num = Integer.valueOf(values[0]);
			int typeint = Integer.valueOf(values[1]);
			int max = Integer.valueOf(values[2]);
			int cardnum = Integer.valueOf(values[3]);

			for (int j = 0; j < num; j++)
			{
				CardInfo n = new CardInfo(CardType.values()[typeint], max, r, key, cardnum);
				ret.add(n);
			}
		}

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
		for (int i = 0; i < r.card.size() - lastbig.cardnum - 2; i++)
		{
			if (r.card.get(i).intValue() > lastbig.max)
			{
				boolean find = true;
				for (int j = 0; j < lastbig.cardnum - 2 - 3; j += 3)
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
					for (int j = 0; j < r.card.size() - 1; j++)
					{
						if (r.card.get(j).intValue() == r.card.get(j + 1).intValue())
						{
							for (int z = 0; z < lastbig.cardnum - 2; z++)
							{
								if (r.card.get(j).intValue() != r.card.get(z).intValue())
								{
									String cardstr = "";
									for (int k = 0; k < lastbig.cardnum - 1; k++)
									{
										if (k != 0)
										{
											cardstr += ",";
										}
										cardstr += r.card.get(i + k).intValue();
									}
									cardstr += "," + r.card.get(j).intValue();
									cardstr += "," + r.card.get(j + 1).intValue();

									ret.add(new CardInfo(CardType.ct_double_three_plus_two, r.card.get(i).intValue(), r,
											cardstr, lastbig.cardnum));
								}
							}
						}
					}

				}
			}
		}

		return ret;
	}

	public ArrayList<CardInfo> FindBiggerDoubleThreePlusOne(ArrayList<CardInfo> ret, Robot r, CardInfo lastbig)
	{
		for (int i = 0; i < r.card.size() - lastbig.cardnum - 1; i++)
		{
			if (r.card.get(i).intValue() > lastbig.max)
			{
				boolean find = true;
				for (int j = 0; j < lastbig.cardnum - 1 - 3; j += 3)
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
					for (int j = 0; j < r.card.size(); j++)
					{
						for (int z = 0; z < lastbig.cardnum - 1; z++)
						{
							if (r.card.get(j).intValue() != r.card.get(z).intValue())
							{
								String cardstr = "";
								for (int k = 0; k < lastbig.cardnum - 1; k++)
								{
									if (k != 0)
									{
										cardstr += ",";
									}
									cardstr += r.card.get(i + k).intValue();
								}
								cardstr += "," + r.card.get(j).intValue();

								ret.add(new CardInfo(CardType.ct_double_three_plus_one, r.card.get(i).intValue(), r,
										cardstr, lastbig.cardnum));
							}
						}
					}

				}
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

	public CardInfo FirstOutCard(Robot r)
	{
		CardInfo info;
		while (true)
		{
			int index = rand.nextInt(CardType.values().length);
			CardType type = CardType.values()[index];
			if (type != CardType.ct_pass)
			{
				ArrayList<CardInfo> ret = new ArrayList<CardInfo>();

				// history
				String cardstate = t.GetCardState();
				Map<String, String> oldout = t.client.hgetAll(cardstate);
				for (Map.Entry<String, String> entry : oldout.entrySet())
				{
					String key = entry.getKey();
					String value = entry.getValue();

					String[] values = value.split("\\$");

					int num = Integer.valueOf(values[0]);
					int typeint = Integer.valueOf(values[1]);
					int max = Integer.valueOf(values[2]);
					int cardnum = Integer.valueOf(values[3]);

					for (int j = 0; j < num; j++)
					{
						CardInfo n = new CardInfo(CardType.values()[typeint], max, r, key, cardnum);
						ret.add(n);
					}
				}

				CardInfo lastbig = new CardInfo(type, -1, r, "", 0);
				if (type == CardType.ct_single)
				{
					lastbig.cardnum = 1;
					ret = FindBiggerSingle(ret, r, lastbig);
				}

				if (type == CardType.ct_double)
				{
					lastbig.cardnum = 2;
					ret = FindBiggerDouble(ret, r, lastbig);
				}

				if (type == CardType.ct_three)
				{
					lastbig.cardnum = 3;
					ret = FindBiggerThree(ret, r, lastbig);
				}

				if (type == CardType.ct_boom)
				{
					lastbig.cardnum = 4;
					ret = FindBiggerBoom(ret, r, lastbig);
				}

				if (type == CardType.ct_three_plus_one)
				{
					lastbig.cardnum = 4;
					ret = FindBiggerThreePlusOne(ret, r, lastbig);
				}

				if (type == CardType.ct_three_plus_two)
				{
					lastbig.cardnum = 5;
					ret = FindBiggerThreePlusTwo(ret, r, lastbig);
				}

				if (type == CardType.ct_four_plus_two)
				{
					lastbig.cardnum = 6;
					ret = FindBiggerFourPlusTwo(ret, r, lastbig);
				}

				if (type == CardType.ct_four_plus_two_double)
				{
					lastbig.cardnum = 8;
					ret = FindBiggerFourPlusTwoDouble(ret, r, lastbig);
				}

				if (type == CardType.ct_continue)
				{
					lastbig.cardnum = 5 + rand.nextInt(5);
					ret = FindBiggerContinue(ret, r, lastbig);
				}

				if (type == CardType.ct_double_continue)
				{
					lastbig.cardnum = 6 + rand.nextInt(2) * 2;
					ret = FindBiggerDoubleContinue(ret, r, lastbig);
				}

				if (type == CardType.ct_double_three)
				{
					lastbig.cardnum = 6 + rand.nextInt(1) * 3;
					ret = FindBiggerDoubleThree(ret, r, lastbig);
				}

				if (type == CardType.ct_double_three_plus_one)
				{
					lastbig.cardnum = 6 + rand.nextInt(1) * 3 + 1;
					ret = FindBiggerDoubleThreePlusOne(ret, r, lastbig);
				}

				if (type == CardType.ct_double_three_plus_two)
				{
					lastbig.cardnum = 6 + rand.nextInt(1) * 3 + 2;
					ret = FindBiggerDoubleThreePlusTwo(ret, r, lastbig);
				}

				if (!ret.isEmpty())
				{
					int i = rand.nextInt(ret.size());
					info = ret.get(i);
					break;
				}
			}
		}
		return info;
	}
}

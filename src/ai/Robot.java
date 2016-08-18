package ai;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;

public class Robot
{
	public int no;
	public ArrayList<Integer> card = new ArrayList<Integer>();
	public Table t;
	public Robot next;

	public Robot(Table t, int no)
	{
		this.t = t;
		this.no = no;
	}

	public void Clear()
	{
		card.clear();
	}

	public void AddCard(int i)
	{
		card.add(i);
	}

	public void SortCard()
	{
		Collections.sort(card);
	}

	public String CardState()
	{
		String ret = "";
		for (int i : card)
		{
			ret += i + ",";
		}
		return ret;
	}

	public CardInfo Go(CardInfo lastbig)
	{
		CardInfo ret = null;
		CardInfo out = null;

		System.out.print("[" + no + "] :(" + CardState() + ")");

		if (no == 4)
		{
			System.out.println("type|max|cardstr|cardnum");
			String str;
			try
			{
				BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
				str = br.readLine();
				String[] tmp = str.split("\\|");

				CardType type = CardType.values()[Integer.parseInt(tmp[0])];
				int max = Integer.parseInt(tmp[1]);
				Robot r = this;
				String cardstr = tmp[2];
				int cardnum = Integer.parseInt(tmp[3]);

				out = new CardInfo(type, max, r, cardstr, cardnum);
			}
			catch (Exception e)
			{

			}
		}
		else
		{
			// 记录牌面
			out = OutCard(lastbig);
		}

		if (out.type == CardType.ct_pass)
		{
			ret = lastbig;
		}
		else
		{
			ret = out;
		}

		System.out.println((out.cardstr.isEmpty() ? "pass" : out.cardstr));

		RemoveCard(out);

		return ret;
	}

	public void AddCard(CardInfo out)
	{
		if (!out.cardstr.isEmpty())
		{
			String[] strcards = out.cardstr.split(",");
			for (String s : strcards)
			{
				Integer card = Integer.valueOf(s);
				this.card.add(card);
			}
		}
		Collections.sort(card);
	}

	public void RemoveCard(CardInfo out)
	{
		if (!out.cardstr.isEmpty())
		{
			String[] strcards = out.cardstr.split(",");
			for (String s : strcards)
			{
				Integer card = Integer.valueOf(s);
				this.card.remove(card);
			}
		}
	}

	public CardInfo OutCard(CardInfo lastbig)
	{
		return t.l.OutCard(this, lastbig);
	}

	public void Win()
	{
		// 存储此次的牌面和出牌
		System.out.println("[" + no + "] :Win");

	}

	public boolean IsEnd()
	{
		return card.isEmpty();
	}
}

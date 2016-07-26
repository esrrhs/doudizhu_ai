package ai;

import java.util.ArrayList;
import java.util.Collections;

public class Robot
{
	public int no;
	public ArrayList<Integer> oldcard = new ArrayList<Integer>();
	public ArrayList<Integer> card = new ArrayList<Integer>();
	public ArrayList<Step> steps = new ArrayList<Step>();
	public Table t;

	public Robot(Table t, int no)
	{
		this.t = t;
		this.no = no;
	}

	public void Clear()
	{
		oldcard.clear();
		card.clear();
		steps.clear();
	}

	public void ClearStep()
	{
		card.clear();
		card = (ArrayList<Integer>) oldcard.clone();
		steps.clear();
	}

	public void AddCard(int i)
	{
		oldcard.add(i);
	}

	public void SortCard()
	{
		Collections.sort(oldcard);
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
		if (lastbig.r == this)
		{
			// 记录牌面
			Step s = new Step();
			s.cardstate = t.GetCardState();

			CardInfo out = FirstOutCard();

			s.outcard = out;
			steps.add(s);

			//System.out.println("[" + no + "] first : (" + CardState() + ") " + out.cardstr);

			RemoveCard(out);

			return out;
		}
		else
		{
			CardInfo ret;

			// 记录牌面
			Step s = new Step();
			s.cardstate = t.GetCardState() + "|" + lastbig.cardstr;

			CardInfo out = OutCard(lastbig);

			s.outcard = out;
			steps.add(s);

			if (out.type == CardType.ct_pass)
			{
				ret = lastbig;
			}
			else
			{
				ret = out;
			}

			//System.out.println("[" + no + "] :(" + CardState() + ")" + (out.cardstr.isEmpty() ? "pass" : out.cardstr));

			RemoveCard(out);

			return ret;
		}
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

	public CardInfo FirstOutCard()
	{
		return t.l.FirstOutCard(this);
	}

	public void Win()
	{
		// 存储此次的牌面和出牌
		System.out.println("[" + no + "] :Win");

		for (Step s : steps)
		{
			String key = "" + no + "_" + s.cardstate;
			String field = s.outcard.cardstr;
			String value = t.client.hget(key, field);
			if (value == null || value.isEmpty())
			{
				value = "1";
			}
			else
			{
				String[] values = value.split("\\$");
				int num = Integer.valueOf(values[0]) + 1;

				value = "" + num;

				System.out.println("------" + num);
			}

			String realvalue = value + "$" + s.outcard.type.ordinal() + "$" + s.outcard.max + "$" + s.outcard.cardnum;

			t.client.hset(key, field, realvalue);

			//System.out.println("[ " + key + " ] : " + field + " " + value);
		}
	}

	public boolean IsEnd()
	{
		return card.isEmpty();
	}
}

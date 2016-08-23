package ai;

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
			ret += Logic.CardName[i - 1] + ",";
		}
		return ret;
	}

	public CardInfo Go(CardInfo lastbig)
	{
		CardInfo ret = null;
		CardInfo out = null;

		System.out.print("[" + no + "] :(" + CardState() + ")");

		out = OutCard(lastbig);

		if (out.type == CardType.ct_pass)
		{
			ret = lastbig;
		}
		else
		{
			ret = out;
		}

		System.out.println(out.CardStr());

		RemoveCard(out);

		return ret;
	}

	public void AddCard(CardInfo out)
	{
		if (out.cardstr.length > 0)
		{
			for (int c : out.cardstr)
			{
				int index = card.size();
				for (int i = 0; i < card.size(); i++)
				{
					if (c <= card.get(i))
					{
						index = i;
						break;
					}
				}
				card.add(index, c);
			}
		}
	}

	public void RemoveCard(CardInfo out)
	{
		if (out.cardstr.length > 0)
		{
			for (Integer c : out.cardstr)
			{
				this.card.remove(c);
			}
		}
	}

	public CardInfo OutCard(CardInfo lastbig)
	{
		return t.l.OutCard(this, lastbig);
	}

	public void Win()
	{
		System.out.println("[" + no + "] :Win");

	}

	public boolean IsEnd()
	{
		return card.isEmpty();
	}
}

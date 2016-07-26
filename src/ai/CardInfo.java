package ai;

public class CardInfo implements Cloneable
{
	public CardType type;
	public int max;
	public Robot r;
	public String cardstr;
	public int cardnum;

	public CardInfo(CardType type, int max, Robot r, String cardstr, int cardnum)
	{
		this.type = type;
		this.max = max;
		this.r = r;
		this.cardstr = cardstr;
		this.cardnum = cardnum;
	}

	public Object clone()
	{
		Object o = null;
		try
		{
			o = (CardInfo) super.clone();
		}
		catch (CloneNotSupportedException e)
		{
			System.out.println(e.toString());
		}
		return o;
	}
}

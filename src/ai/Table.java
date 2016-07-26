package ai;

import redis.clients.jedis.Jedis;

public class Table
{
	public Robot A;
	public Robot B;
	public Robot C;
	public Logic l;
	public Jedis client = new Jedis("127.0.0.1", 6379);

	public void Run()
	{
		A = new Robot(this, 0);
		B = new Robot(this, 1);
		C = new Robot(this, 2);
		l = new Logic(this);

		while (true)
		{
			A.Clear();
			B.Clear();
			C.Clear();
			l.Run();
		}
	}

	public String GetCardState()
	{
		return A.CardState() + "|" + B.CardState() + "|" + C.CardState();
	}
}

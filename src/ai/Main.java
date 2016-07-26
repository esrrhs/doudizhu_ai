package ai;

public class Main
{
	public static void main(String[] args)
	{
		for (int i = 0; i < 100; i++)
		{
			new Thread(new Runnable() {
				@Override
				public void run()
				{
					Table t = new Table();
					t.Run();
				}
			}).start();
		}
		try
		{
			while (true)
			{
				Thread.sleep(1);
			}
		}
		catch (Exception e)
		{

		}
	}
}

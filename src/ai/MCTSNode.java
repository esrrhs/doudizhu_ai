package ai;

import java.util.ArrayList;

public class MCTSNode
{
	public int N;
	public int Value;
	public ArrayList<MCTSNode> son = new ArrayList<MCTSNode>();
	public CardInfo cardInfo;
}

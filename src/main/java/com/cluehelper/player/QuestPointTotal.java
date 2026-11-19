package com.cluehelper.player;

import javax.inject.*;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;

/**
 * Client thread only. The quest points the game offers, summed from its Quest table the way Shortest Path's
 * ClientPlayerStateSource does (BSD-2, see NOTICE). The Quest point cape refuses to teleport while the player has
 * fewer, and a game update that adds a quest opens that gap again.
 */
@Singleton
public class QuestPointTotal
{
	private static final int NOT_RELEASED = 0;

	@Inject
	private Client client;

	int offered()
	{
		int total = 0;
		for (int quest : client.getDBTableRows(DBTableID.Quest.ID))
		{
			if (field(quest, DBTableID.Quest.COL_RELEASE_TYPE) != NOT_RELEASED)
			{
				total += field(quest, DBTableID.Quest.COL_QUESTPOINTS_OVERRIDE);
			}
		}
		return total;
	}

	private int field(int row, int column)
	{
		return (Integer) client.getDBTableField(row, column, 0)[0];
	}
}

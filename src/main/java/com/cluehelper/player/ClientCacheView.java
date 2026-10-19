package com.cluehelper.player;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.ItemComposition;

/** {@link CacheView} over the live client. Client thread only. */
final class ClientCacheView implements CacheView
{
	private static final int MULTIRUNES_TABLE = 91;
	private static final int REQUIRED_RUNE_COLUMN = 0;
	private static final int ALSO_COUNTS_COLUMN = 1;

	private final Client client;

	ClientCacheView(Client client)
	{
		this.client = client;
	}

	@Override
	public int[] enumKeys(int enumId)
	{
		EnumComposition e = client.getEnum(enumId);
		return e == null ? new int[0] : e.getKeys();
	}

	@Override
	public int[] enumIntValues(int enumId)
	{
		EnumComposition e = client.getEnum(enumId);
		return e == null ? new int[0] : e.getIntVals();
	}

	@Override
	public int itemIntParam(int itemId, int param, int absent)
	{
		ItemComposition item = client.getItemDefinition(itemId);
		return item.getParams() != null && item.getParams().get(param) != null ? item.getIntValue(param) : absent;
	}

	@Override
	public String itemStringParam(int itemId, int param)
	{
		ItemComposition item = client.getItemDefinition(itemId);
		return item.getParams() != null && item.getParams().get(param) != null ? item.getStringValue(param) : null;
	}

	@Override
	public String itemName(int itemId)
	{
		return client.getItemDefinition(itemId).getName();
	}

	@Override
	public List<int[]> multirunes()
	{
		List<int[]> rows = new ArrayList<>();
		for (int row : client.getDBTableRows(MULTIRUNES_TABLE))
		{
			Object[] required = client.getDBTableField(row, REQUIRED_RUNE_COLUMN, 0);
			Object[] alsoCounts = client.getDBTableField(row, ALSO_COUNTS_COLUMN, 0);
			if (required.length > 0 && alsoCounts.length > 0)
			{
				rows.add(new int[]{(Integer) required[0], (Integer) alsoCounts[0]});
			}
		}
		return rows;
	}
}

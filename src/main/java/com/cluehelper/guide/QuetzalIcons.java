package com.cluehelper.guide;

import java.util.*;
import javax.inject.*;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.widgets.Widget;

@Singleton
public class QuetzalIcons
{
	private static final int MOST_PIXELS_OFF = 24;

	@Inject
	private Client client;
	private Map<Integer, int[]> positions;

	/** The first call reads the cache, so it must be on the client thread. */
	public int[] position(int quetzalTableId)
	{
		if (positions == null)
		{
			positions = read();
		}
		return positions.get(quetzalTableId);
	}

	private Map<Integer, int[]> read()
	{
		Map<Integer, int[]> out = new HashMap<>();
		List<Integer> rows = client.getDBTableRows(DBTableID.Quetzal.ID);
		for (int row : rows == null ? List.<Integer>of() : rows)
		{
			Object[] id = client.getDBTableField(row, DBTableID.Quetzal.COL_ID, 0);
			Object[] x = client.getDBTableField(row, DBTableID.Quetzal.COL_IF_X_POS, 0);
			Object[] y = client.getDBTableField(row, DBTableID.Quetzal.COL_IF_Y_POS, 0);
			if (isInt(id) && isInt(x) && isInt(y))
			{
				out.put((Integer) id[0], new int[]{(Integer) x[0], (Integer) y[0]});
			}
		}
		return out;
	}

	static Widget nearest(Widget[] icons, int[] at)
	{
		Widget best = null;
		int bestOff = MOST_PIXELS_OFF + 1;
		for (Widget icon : icons == null ? new Widget[0] : icons)
		{
			if (icon == null || icon.isHidden())
			{
				continue;
			}
			int x = icon.getOriginalX();
			int y = icon.getOriginalY();
			int off = Math.min(off(x, y, at), off(x + icon.getWidth() / 2, y + icon.getHeight() / 2, at));
			if (off < bestOff)
			{
				best = icon;
				bestOff = off;
			}
		}
		return best;
	}

	private static int off(int x, int y, int[] at)
	{
		return Math.max(Math.abs(x - at[0]), Math.abs(y - at[1]));
	}

	private static boolean isInt(Object[] field)
	{
		return field != null && field.length > 0 && field[0] instanceof Integer;
	}
}

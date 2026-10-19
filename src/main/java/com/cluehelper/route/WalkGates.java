package com.cluehelper.route;

import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.transport.AvailabilityEngine;
import com.cluehelper.transport.CatalogRow;
import com.cluehelper.transport.Requirements;
import java.util.List;

/**
 * Which of the walk table's gates (quest, skill and var gated doors and shortcuts, B02) a snapshot passes.
 * An unread var fails its gate, so an unread account walks the always-open way.
 */
public final class WalkGates
{
	private final WalkTable table;
	private final boolean[] gatePass;
	private final boolean[] setPass;

	public WalkGates(WalkTable table, PlayerSnapshot player, AvailabilityEngine engine, long nowMinutes)
	{
		this.table = table;
		List<Requirements> gates = table.getGates();
		gatePass = new boolean[gates.size() + 1];
		gatePass[0] = true;
		for (int g = 0; g < gates.size(); g++)
		{
			CatalogRow asRow = CatalogRow.builder().name("gate " + (g + 1)).requirements(gates.get(g)).build();
			gatePass[g + 1] = engine.judge(asRow, player, nowMinutes).isAvailable();
		}
		setPass = new boolean[table.gateSetCount() + 1];
		setPass[0] = true;
		for (int s = 1; s <= table.gateSetCount(); s++)
		{
			boolean pass = true;
			for (int gate : table.gateSet(s))
			{
				pass &= gatePass[gate];
			}
			setPass[s] = pass;
		}
	}

	/** Tiles for this table entry: the full cost when every gate passes, else the open cost, else {@link WalkTable#NO_PATH}. */
	public int cost(int entry)
	{
		return setPass[table.entryGateSet(entry)] ? table.entryFull(entry) : table.entryOpen(entry);
	}

	/** Per walk row, whether the scene BFS may use it. */
	public boolean[] rowOpen()
	{
		boolean[] open = new boolean[table.rowCount()];
		for (int r = 0; r < open.length; r++)
		{
			open[r] = gatePass[table.rowGate(r)];
		}
		return open;
	}
}

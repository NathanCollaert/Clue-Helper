package com.cluehelper.route;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

/**
 * The walk search and the bundled walk table walk Shortest Path's map by the same rules, so a search from a node costs
 * each of its table walks exactly; a drifted map or a changed rule shows here.
 */
public class WalkCostsTest
{
	/** Every so many nodes, to keep the test quick. */
	private static final int NODE_STRIDE = 25;
	/** The walk generator's search limit. */
	private static final int WALK_LIMIT = 200;

	@Test
	public void startWalksMatchTheTable() throws IOException
	{
		WalkTable table = WalkTable.load();
		Ground ground = Ground.world(WorldMap.load(), null, table, row -> true);
		List<String> mismatches = new ArrayList<>();
		int compared = 0;
		int capped = 0;
		for (int from = 0; from < table.nodeCount(); from += NODE_STRIDE)
		{
			if (table.placed(from) == null)
			{
				continue;
			}
			WalkSearch walk = new WalkSearch(ground, WALK_LIMIT, false);
			walk.run(WalkTable.pack(table.placed(from)), (tile, cost) -> WalkSearch.UNREACHED);
			for (int e = table.entriesFrom(from); e < table.entriesEnd(from); e++)
			{
				WorldPoint to = table.placed(table.entryTo(e));
				if (to == null)
				{
					continue;
				}
				int walked = walk.cost(WalkTable.pack(to));
				if (walked == WalkSearch.UNREACHED && walk.reachedTiles() >= ground.tileLimit())
				{
					capped++;
					continue;
				}
				compared++;
				if (walked != table.entryFull(e))
				{
					mismatches.add("node " + from + " " + table.placed(from) + " -> " + to + ": table " + table.entryFull(e)
						+ ", walk " + (walked == WalkSearch.UNREACHED ? "unreached" : walked));
				}
			}
		}
		System.out.printf("walks against the table: %d pairs, %d differ, %d past the search's tile limit%n", compared,
			mismatches.size(), capped);
		assertTrue(compared > 0);
		assertTrue(String.join("\n", mismatches.subList(0, Math.min(20, mismatches.size()))), mismatches.isEmpty());
	}
}

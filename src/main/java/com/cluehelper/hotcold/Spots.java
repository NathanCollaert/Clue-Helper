package com.cluehelper.hotcold;

import com.cluehelper.step.HotColdTier;
import com.cluehelper.step.StepIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.Enemy;
import net.runelite.client.plugins.cluescrolls.clues.hotcold.HotColdLocation;

/** Core's spots, read from its enum, never from its solver. */
public final class Spots
{
	private static final int MIN_RADIUS = 1;

	private Spots()
	{
	}

	public static List<Spot> of(StepIndex index, HotColdTier tier)
	{
		Map<String, StepIndex.Spot> cache = index.getHotColdSpots();
		List<Spot> spots = new ArrayList<>();
		for (HotColdLocation location : HotColdLocation.values())
		{
			if (tierOf(location) == tier)
			{
				spots.add(spot(location, tier, cache.get(location.name())));
			}
		}
		return List.copyOf(spots);
	}

	private static HotColdTier tierOf(HotColdLocation location)
	{
		return location.isBeginnerClue() ? HotColdTier.BEGINNER : HotColdTier.MASTER;
	}

	private static Spot spot(HotColdLocation location, HotColdTier tier, StepIndex.Spot cached)
	{
		WorldPoint core = location.getWorldPoint();
		int[] tile = cached == null ? null : cached.getTile();
		int x = tile == null ? core.getX() : tile[0];
		int y = tile == null ? core.getY() : tile[1];
		int radius = Math.max(MIN_RADIUS, Math.max(Math.abs(core.getX() - x), Math.abs(core.getY() - y)));
		String description = location.getArea();
		if (description.endsWith("."))
		{
			description = description.substring(0, description.length() - 1);
		}
		return new Spot(location.name(), tier, x, y, radius, description,
			location.getHotColdArea().getName(), location.getEnemy() == Enemy.BRASSICAN_OR_WIZARDS);
	}
}

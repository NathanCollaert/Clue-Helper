package com.cluehelper.hotcold;

import com.cluehelper.step.*;
import java.util.*;
import java.util.stream.Collectors;
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
		return Arrays.stream(HotColdLocation.values()).filter(location -> tierOf(location) == tier)
			.map(location -> spot(location, tier, cache.get(location.name()))).collect(Collectors.toUnmodifiableList());
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
		int radius = Math.max(MIN_RADIUS, Hypotheses.distance(core, x, y));
		return new Spot(location.name(), tier, x, y, radius,
			location.getHotColdArea().getName(), location.getEnemy() == Enemy.BRASSICAN_OR_WIZARDS);
	}
}

package com.cluehelper.hotcold;

import com.cluehelper.step.*;
import java.util.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.Enemy;
import net.runelite.client.plugins.cluescrolls.clues.hotcold.HotColdLocation;
import static java.util.stream.Collectors.*;

/** Core's hot/cold spots, in its enum's order, centred where the cache's rows put them, as {@code StepLoader} bakes them. */
public final class SpotParts
{
	private static final int MIN_RADIUS = 1;

	private SpotParts()
	{
	}

	public static List<Spot> of(Map<String, StepLoader.CacheSpot> cache)
	{
		return Arrays.stream(HotColdLocation.values()).map(location -> spot(location, cache.get(location.name())))
			.collect(toUnmodifiableList());
	}

	private static Spot spot(HotColdLocation location, StepLoader.CacheSpot cached)
	{
		HotColdTier tier = location.isBeginnerClue() ? HotColdTier.BEGINNER : HotColdTier.MASTER;
		WorldPoint core = location.getWorldPoint();
		int[] tile = cached == null ? null : cached.getTile();
		int x = tile == null ? core.getX() : tile[0];
		int y = tile == null ? core.getY() : tile[1];
		int radius = Math.max(MIN_RADIUS, Hypotheses.distance(core, x, y));
		return new Spot(location.name(), tier, x, y, radius, location.getEnemy() == Enemy.BRASSICAN_OR_WIZARDS);
	}
}

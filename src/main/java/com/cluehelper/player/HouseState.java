package com.cluehelper.player;

import java.util.*;
import lombok.*;

@Value
@Builder(toBuilder = true)
public class HouseState
{
	public static final HouseState NONE = HouseState.builder().build();
	private static final int UNLEARNED_WALK_TICKS = 4;
	/** Base ids of the three nexus tiers and the Leagues one. */
	public static final List<Integer> NEXUS_OBJECTS = List.of(33408, 33409, 33410, 56075);

	public enum Source
	{
		/** Nothing scanned: only what the varbits say. */
		VARBITS,
		SCAN
	}

	/** Enum 1377 keys set in the nexus slots. */
	@Builder.Default
	Set<Integer> nexusKeys = Set.of();
	/**
	 * Each destination's hotkey line by struct id, read from script 2675 when the nexus opens. Not the slot order: the
	 * numbers skip.
	 */
	@Builder.Default
	Map<Integer, Integer> nexusLines = Map.of();
	/** Base object ids. */
	@Builder.Default
	Set<Integer> furniture = Set.of();
	/** By base object id, measured from the house entry. */
	@Builder.Default
	Map<Integer, Integer> walkTicks = Map.of();
	/** Left-click destinations are 1-based; 0 when unknown. */
	int xericLeftClick;
	int digsiteLeftClick;
	int boxLeftClick;
	@Builder.Default
	Source source = Source.VARBITS;
	/** Epoch millis; 0 when never scanned. */
	long scannedAt;

	public boolean hasAny(Iterable<Integer> objectIds)
	{
		for (int id : objectIds)
		{
			if (furniture.contains(id))
			{
				return true;
			}
		}
		return false;
	}

	public int walkTicks(Iterable<Integer> objectIds)
	{
		int best = Integer.MAX_VALUE;
		for (int id : objectIds)
		{
			best = Math.min(best, walkTicks.getOrDefault(id, Integer.MAX_VALUE));
		}
		return best == Integer.MAX_VALUE ? UNLEARNED_WALK_TICKS : best;
	}
}

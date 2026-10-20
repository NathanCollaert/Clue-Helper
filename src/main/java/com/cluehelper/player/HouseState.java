package com.cluehelper.player;

import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Value;

/**
 * What the player's own house holds, as POH rows are judged against it (PLAN §5.2 POH, B12, B16). Immutable.
 * The nexus destinations come from varbits; furniture from an own-house scan by base id, the mounted Xeric and
 * digsite settings before any scan, or the manual overrides.
 */
@Value
@Builder(toBuilder = true)
public class HouseState
{
	public static final HouseState NONE = HouseState.builder().build();
	/** Walking from where the house is entered to a piece, until a scan has measured it (PLAN §5.4 Cost). */
	public static final int UNLEARNED_WALK_TICKS = 4;
	/** The portal nexus's base ids: the three tiers and the League one (B12). */
	public static final List<Integer> NEXUS_OBJECTS = List.of(33408, 33409, 33410, 56075);

	public enum Source
	{
		/** Nothing learned: only what the varbits say. */
		VARBITS,
		/** An own-house scan; {@link #getScannedAt()} says when. */
		SCAN,
		/** The config's overrides ({@code pohSource} Manual). */
		MANUAL
	}

	/** Enum 1377 keys set in the 41 nexus slots. */
	@Builder.Default
	Set<Integer> nexusKeys = Set.of();
	/**
	 * Each nexus destination's line number by struct id, learned from script 2675 when the nexus opens: its hotkey. Not
	 * the slot order (B16: the numbers skip, and Camelot's slot index gave F where the game shows H).
	 */
	@Builder.Default
	Map<Integer, Integer> nexusLines = Map.of();
	/** Built pieces by base object id. */
	@Builder.Default
	Set<Integer> furniture = Set.of();
	/** Ticks from the house entry to each piece, by base id; missing ones count {@link #UNLEARNED_WALK_TICKS}. */
	@Builder.Default
	Map<Integer, Integer> walkTicks = Map.of();
	/** Left-click destinations, 1-based, 0 when unknown: 6617 (Xeric's talisman), 6651 (digsite), 2308 (box). */
	int xericLeftClick;
	int digsiteLeftClick;
	int boxLeftClick;
	@Builder.Default
	Source source = Source.VARBITS;
	/** Epoch millis of the last own-house scan; 0 when never. */
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

	/** The shortest learned walk to any of these pieces. */
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

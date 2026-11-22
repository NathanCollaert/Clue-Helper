package com.cluehelper.route;

import java.util.*;
import lombok.*;
import net.runelite.api.coords.WorldPoint;

/** Each route is null when no such route exists. */
@Value
@Builder(toBuilder = true)
public class Plan
{
	public static final Plan NONE = Plan.builder().note("no step").build();

	String stepKey;
	Route best;
	/** The followed route's way, planned again when the best starts another way; null when that way isn't within the margin. */
	Route followed;
	/** PROBES steps only. */
	@Builder.Default
	Map<WorldPoint, Integer> probeTicks = Map.of();
	/** PROBES steps only: each tile's ranking ticks, penalties included. */
	@Builder.Default
	Map<WorldPoint, Integer> probeRanks = Map.of();
	String note;
	@Builder.Default
	List<String> requirementNotes = List.of();
	long searchNanos;
	int labels;
}

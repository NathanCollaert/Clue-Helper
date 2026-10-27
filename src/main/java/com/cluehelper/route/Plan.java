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
	/** Best route without a bank stop; null when that is Best itself. */
	Route direct;
	/** Best route without a spellbook change; set only when Best uses one. */
	Route sameBook;
	/** Faster than Best, but it didn't beat its reference by the margin. */
	Route fasterUnqualified;
	/** Each with a first transport no earlier route has. */
	@Builder.Default
	List<Route> alternatives = List.of();
	/** The followed route's way, planned again when the best starts another way; null when that way isn't within the margin. */
	Route followed;
	@Builder.Default
	List<Candidate> candidates = List.of();
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

	@Value
	public static class Candidate
	{
		String category;
		Route route;
	}
}

package com.cluehelper.route;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** Each route is null when no such route exists. */
@Value
@Builder(toBuilder = true)
public class Plan
{
	public static final Plan NONE = Plan.builder().alternatives(List.of()).candidates(List.of()).probeTicks(Map.of())
		.note("no step").build();

	String stepKey;
	Route best;
	/** Best route without a bank stop; null when that is Best itself. */
	Route direct;
	/** Best route without a spellbook change; set only when Best uses one. */
	Route sameBook;
	/** Faster than Best, but it didn't beat its reference by the margin. */
	Route fasterUnqualified;
	/** Each with a first transport no earlier route has. */
	List<Route> alternatives;
	/** The followed route's way, planned again when the best starts another way; null when that way isn't within the margin. */
	Route followed;
	List<Candidate> candidates;
	/** PROBES steps only. */
	Map<WorldPoint, Integer> probeTicks;
	/** PROBES steps only: each tile's ranking ticks, penalties included. */
	@Builder.Default
	Map<WorldPoint, Integer> probeRanks = Map.of();
	String note;
	@Builder.Default
	List<String> requirementNotes = List.of();
	/** Includes {@link #contextNanos}. */
	long searchNanos;
	long contextNanos;
	int labels;
	int searches;

	@Value
	public static class Candidate
	{
		String category;
		Route route;
	}
}

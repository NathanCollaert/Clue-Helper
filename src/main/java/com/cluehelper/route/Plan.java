package com.cluehelper.route;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** What the panel shows for one step (PLAN §5.4 Route identity and output). Routes are null when none exists. */
@Value
@Builder
public class Plan
{
	public static final Plan NONE = Plan.builder().alternatives(List.of()).candidates(List.of()).probeTicks(Map.of())
		.note("no step").build();

	String stepKey;
	/** The bottom-up margin rule's pick. */
	Route best;
	/** The best route without a bank stop; null when it is Best. */
	Route direct;
	/** The best route without a spellbook change, shown when Best uses one. */
	Route sameBook;
	/** A faster route that didn't beat its reference by the margin, listed with its saving. */
	Route fasterUnqualified;
	/** Next best routes with new first transports, up to routeCount − 1. */
	List<Route> alternatives;
	/** Every category's best route from the first search, for {@code ::ch-routes}. */
	List<Candidate> candidates;
	/** PROBES only: per target tile, the Best rule's ticks. */
	Map<WorldPoint, Integer> probeTicks;
	/** Why there is no route, or a warning ("walk too long", "Wilderness target"). */
	String note;
	/** The whole plan: judging the catalog, then every search. */
	long searchNanos;
	/** Judging the catalog and gates for this plan, before any search. */
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

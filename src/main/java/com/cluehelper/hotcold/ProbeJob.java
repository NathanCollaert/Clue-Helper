package com.cluehelper.hotcold;

import com.cluehelper.route.*;
import com.cluehelper.step.HotColdTier;
import java.util.*;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import lombok.*;
import net.runelite.api.coords.WorldPoint;

/** One plan after a feel: filter the grid, gather probes, choose. Snapshot data only, so it runs on the executor. */
@Value
@Builder
public class ProbeJob
{
	String stepKey;
	HotColdTier tier;
	List<Spot> spots;
	List<Reading> readings;
	CoreSpots core;
	WorldPoint here;
	/** Scene tiles near the player with their walk in ticks. */
	List<Probe> local;
	/** A PROBES search over every landing tile; null when the route planner isn't ready. */
	PlanInput landingSearch;
	ProbePlanner.Costs costs;
	boolean avoidWilderness;
	/** No feel in the Wilderness, and no spot there is travelled to. */
	boolean stopWildernessClues;

	public HotColdPlan run()
	{
		HotColdPlan plan = decide(stepKey, spots, tier, readings, core, nearby(), this::landingsWithin, costs);
		return stopWildernessClues ? stoppedWhereOnlyWildernessIsLeft(plan) : plan;
	}

	private HotColdPlan stoppedWhereOnlyWildernessIsLeft(HotColdPlan plan)
	{
		List<WorldPoint> centres = spots.stream().filter(spot -> plan.getKept().containsKey(spot.getName()))
			.map(spot -> new WorldPoint(spot.getX(), spot.getY(), 0)).collect(Collectors.toList());
		if (centres.isEmpty() || !centres.stream().allMatch(this::wilderness))
		{
			return plan;
		}
		return plan.toBuilder().action(HotColdPlan.Action.ONLY_WILDERNESS).tile(centres.get(0)).choice(null).build();
	}

	/**
	 * The rules after the probes are in, shared with the simulator. Until core has seen a feel of this clue the log may
	 * be the last clue's, so it is never dug on: the next feel is planned as for a new clue instead. The landings are
	 * asked for only when a feel is planned.
	 *
	 * @param landingsWithin landing probes whose ranking ticks are at most the given number
	 */
	public static HotColdPlan decide(String stepKey, List<Spot> spots, HotColdTier tier, List<Reading> readings, CoreSpots core,
		List<Probe> nearby, IntFunction<List<Probe>> landingsWithin, ProbePlanner.Costs costs)
	{
		Hypotheses grid = Hypotheses.fitting(spots, tier, readings, core.getNames());
		Reading last = readings.isEmpty() ? null : readings.get(readings.size() - 1);
		boolean shook = last != null && last.getBand() == Band.SHAKING;
		boolean settled = shook || grid.keptSpots().size() == 1;
		if (settled && !core.isFelt())
		{
			grid = Hypotheses.filter(spots, tier, List.of(last.asAnchor()));
		}
		HotColdPlan.HotColdPlanBuilder plan = HotColdPlan.builder().stepKey(stepKey).tier(tier).kept(grid.spotShares());
		if (shook && core.isFelt())
		{
			return plan.action(HotColdPlan.Action.DIG_HERE).tile(new WorldPoint(last.getX(), last.getY(), last.getPlane())).build();
		}
		List<Spot> kept = grid.keptSpots();
		if (kept.size() == 1)
		{
			return plan.action(HotColdPlan.Action.DIG_SPOT).tile(grid.digTile(kept.get(0).getName())).build();
		}
		List<Probe> probes = ProbePlanner.worthTrying(grid, tier, last, nearby, landingsWithin, costs);
		ProbePlanner.Choice choice = ProbePlanner.choose(grid, tier, last, probes, costs);
		if (choice == null)
		{
			return plan.action(HotColdPlan.Action.NONE).build();
		}
		return plan.action(HotColdPlan.Action.FEEL).tile(choice.getProbe().getTile()).choice(choice).build();
	}

	List<Probe> nearby()
	{
		List<Probe> probes = new ArrayList<>();
		if (here != null && !(stopWildernessClues && wilderness(here)))
		{
			probes.add(new Probe(here, Probe.Kind.HERE, 0, 0, wilderness(here)));
		}
		for (Probe tile : local)
		{
			if (!avoidWilderness || !tile.isWilderness())
			{
				probes.add(tile);
			}
		}
		return probes;
	}

	private boolean wilderness(WorldPoint tile)
	{
		return landingSearch != null && OriginClass.of(tile, landingSearch.getNoTeleport()).isWilderness();
	}

	/** The PROBES search: landing probes whose ranking ticks are at most the limit. */
	List<Probe> landingsWithin(int rankTicksLimit)
	{
		if (landingSearch == null)
		{
			return List.of();
		}
		Plan landings = Planner.plan(landingSearch.toBuilder().probeRankLimitTicks(rankTicksLimit).build());
		Map<WorldPoint, Integer> ranks = landings.getProbeRanks();
		List<Probe> probes = new ArrayList<>();
		landings.getProbeTicks().forEach((tile, ticks) ->
		{
			int rank = ranks.getOrDefault(tile, ticks);
			if (rank <= rankTicksLimit && !(stopWildernessClues && wilderness(tile)))
			{
				probes.add(new Probe(tile, Probe.Kind.LANDING, ticks, rank, wilderness(tile)));
			}
		});
		return probes;
	}
}

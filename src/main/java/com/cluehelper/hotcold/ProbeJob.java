package com.cluehelper.hotcold;

import com.cluehelper.route.*;
import com.cluehelper.step.HotColdTier;
import java.util.*;
import java.util.function.IntFunction;
import lombok.*;
import net.runelite.api.coords.WorldPoint;

/** One plan after a feel: filter the grid, gather probes, choose. Snapshot data only, so it runs on the executor. */
@Value
@Builder
public class ProbeJob
{
	/** Kept tiles per spot that the scoring sees; a radius of at most 3 gives up to 49. */
	private static final int SAMPLES_PER_SPOT = 9;

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

	public HotColdPlan run()
	{
		long started = System.nanoTime();
		Landings landings = new Landings();
		HotColdPlan plan = decide(stepKey, spots, tier, readings, core, nearby(), landings, costs);
		return plan.toBuilder().landings(landings.count).landingNanos(landings.nanos).nanos(System.nanoTime() - started).build();
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
		HotColdPlan.HotColdPlanBuilder plan = HotColdPlan.builder().stepKey(stepKey).tier(tier).kept(grid.spotShares())
			.exact(grid.isExact()).readings(FeelLogs.feels(readings)).probes(nearby.size()).considered(List.copyOf(nearby));
		if (shook && core.isFelt())
		{
			return plan.action(HotColdPlan.Action.DIG_HERE).tile(new WorldPoint(last.getX(), last.getY(), last.getPlane())).build();
		}
		List<Spot> kept = grid.keptSpots();
		if (kept.size() == 1)
		{
			return plan.action(HotColdPlan.Action.DIG_SPOT).tile(grid.digTile(kept.get(0).getName())).build();
		}
		Hypotheses.Samples samples = grid.sample(SAMPLES_PER_SPOT);
		List<Probe> probes = ProbePlanner.worthTrying(samples, tier, last, nearby, landingsWithin, costs);
		plan.probes(probes.size()).considered(List.copyOf(probes));
		ProbePlanner.Choice choice = ProbePlanner.choose(samples, tier, last, probes, costs);
		if (choice == null)
		{
			return plan.action(HotColdPlan.Action.NONE).build();
		}
		return plan.action(HotColdPlan.Action.FEEL).tile(choice.getProbe().getTile()).choice(choice).build();
	}

	private List<Probe> nearby()
	{
		List<Probe> probes = new ArrayList<>();
		if (here != null)
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

	/** The PROBES search, with how many landings it gave and how long it took. */
	private final class Landings implements IntFunction<List<Probe>>
	{
		int count;
		long nanos;

		@Override
		public List<Probe> apply(int rankTicksLimit)
		{
			if (landingSearch == null)
			{
				return List.of();
			}
			long started = System.nanoTime();
			Plan landings = Planner.plan(landingSearch.toBuilder().probeRankLimitTicks(rankTicksLimit).build());
			Map<WorldPoint, Integer> ranks = landings.getProbeRanks();
			List<Probe> probes = new ArrayList<>();
			landings.getProbeTicks().forEach((tile, ticks) ->
			{
				int rank = ranks.getOrDefault(tile, ticks);
				if (rank <= rankTicksLimit)
				{
					probes.add(new Probe(tile, Probe.Kind.LANDING, ticks, rank, wilderness(tile)));
				}
			});
			count = probes.size();
			nanos += System.nanoTime() - started;
			return probes;
		}
	}
}

package com.cluehelper.hotcold;

import com.cluehelper.route.OriginClass;
import com.cluehelper.route.Plan;
import com.cluehelper.route.PlanInput;
import com.cluehelper.route.Planner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/** One plan after a feel: filter the grid, gather probes, choose. Snapshot data only, so it runs on the executor. */
@Value
@Builder
public class ProbeJob
{
	/** Kept tiles per spot that the scoring sees; B01's k ≤ 3 gives up to 49. */
	static final int SAMPLES_PER_SPOT = 9;

	String stepKey;
	boolean beginner;
	List<Spot> spots;
	List<Reading> readings;
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
		boolean needsProbes = readings.isEmpty() || readings.get(readings.size() - 1).getBand() != Band.SHAKING;
		int landings = needsProbes ? addLandings(probes) : 0;
		long landingNanos = System.nanoTime() - started;
		return decide(stepKey, spots, beginner, readings, probes, costs).toBuilder()
			.landings(landings).landingNanos(landingNanos).considered(List.copyOf(probes)).nanos(System.nanoTime() - started).build();
	}

	/** The rules after the probes are in, shared with the simulator. */
	public static HotColdPlan decide(String stepKey, List<Spot> spots, boolean beginner, List<Reading> readings, List<Probe> probes,
		ProbePlanner.Costs costs)
	{
		Hypotheses grid = Hypotheses.filter(spots, beginner, readings);
		HotColdPlan.HotColdPlanBuilder plan = HotColdPlan.builder().stepKey(stepKey).kept(grid.spotShares())
			.exact(grid.isExact()).readings(FeelLogs.feels(readings)).probes(probes.size());
		Reading last = readings.isEmpty() ? null : readings.get(readings.size() - 1);
		if (last != null && last.getBand() == Band.SHAKING)
		{
			return plan.action(HotColdPlan.Action.DIG_HERE).tile(new WorldPoint(last.getX(), last.getY(), last.getPlane())).build();
		}
		List<Spot> kept = grid.keptSpots();
		if (kept.size() == 1)
		{
			return plan.action(HotColdPlan.Action.DIG_SPOT).tile(grid.digTile(kept.get(0).getName())).build();
		}
		ProbePlanner.Choice choice = ProbePlanner.choose(grid.sample(SAMPLES_PER_SPOT), beginner, last, probes, costs);
		if (choice == null)
		{
			return plan.action(HotColdPlan.Action.NONE).build();
		}
		return plan.action(HotColdPlan.Action.FEEL).tile(choice.getProbe().getTile()).choice(choice).build();
	}

	private int addLandings(List<Probe> probes)
	{
		if (landingSearch == null)
		{
			return 0;
		}
		Plan landings = Planner.plan(landingSearch);
		Map<WorldPoint, Integer> ranks = landings.getProbeRanks();
		landings.getProbeTicks().forEach((tile, ticks) ->
			probes.add(new Probe(tile, Probe.Kind.LANDING, ticks, ranks.getOrDefault(tile, ticks), wilderness(tile))));
		return landings.getProbeTicks().size();
	}

	private boolean wilderness(WorldPoint tile)
	{
		return landingSearch != null && OriginClass.of(tile, landingSearch.getNoTeleport()).isWilderness();
	}
}

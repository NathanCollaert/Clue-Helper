package com.cluehelper.hotcold;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.cluehelper.route.WalkTable;
import com.cluehelper.step.StepIndex;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.hotcold.HotColdLocation;
import org.junit.Test;

/**
 * A simulation on the live planner's own rules: every spot solved from the tier's NPC, the truth on core's centre
 * (the disputed spots then sit k tiles off the cache's), with crude costs: any walk-table node 10 ticks away,
 * scene tiles at a run's pace. Counts feels until a dig and ticks until the dig tile; a dig on the wrong spot or no
 * decision is a failure.
 */
public class HotColdSimulationTest
{
	private static final WorldPoint RELDO = new WorldPoint(3211, 3494, 0);
	private static final WorldPoint JORRAL = new WorldPoint(2436, 3347, 0);
	private static final int LANDING_TICKS = 10;
	private static final int LOCAL_TILES = 32;
	private static final int LOCAL_STRIDE = 4;
	private static final int MOST_FEELS = 12;
	/** Regression bounds, a little above this planner's numbers on 2026-10-21: beginner 1.80, master 4.15. */
	private static final double BEGINNER_MEAN_FEELS = 2.0;
	private static final double MASTER_MEAN_FEELS = 4.3;
	private static final Path OUT = Paths.get("build/hotcold/simulation.tsv");

	@Test
	public void everySpotIsSolvedWithFewFeels() throws IOException
	{
		StepIndex index = StepIndex.read(new Gson());
		List<WorldPoint> landings = landings();
		List<String> rows = new ArrayList<>();
		rows.add("tier\tspot\tfeels\tticks\tresult");
		double beginner = meanFeels(true, RELDO, index, landings, rows);
		double master = meanFeels(false, JORRAL, index, landings, rows);
		Files.createDirectories(OUT.getParent());
		Files.write(OUT, rows, StandardCharsets.UTF_8);
		System.out.printf(Locale.ROOT, "hot/cold simulation: beginner %.2f feels, master %.2f feels (%s)%n", beginner, master, OUT);
		assertTrue("beginner mean feels " + beginner, beginner <= BEGINNER_MEAN_FEELS);
		assertTrue("master mean feels " + master, master <= MASTER_MEAN_FEELS);
	}

	private static double meanFeels(boolean beginner, WorldPoint start, StepIndex index, List<WorldPoint> landings, List<String> rows)
	{
		List<Spot> spots = Spots.of(index, beginner);
		ProbePlanner.Costs costs = ProbePlanner.Costs.builder()
			.wildernessPenaltyTicks(HotColdService.WILDERNESS_PENALTY_TICKS).build();
		int total = 0;
		List<String> failures = new ArrayList<>();
		for (HotColdLocation truth : HotColdLocation.values())
		{
			if (truth.isBeginnerClue() != beginner)
			{
				continue;
			}
			int[] result = solve(truth, beginner, start, spots, landings, costs);
			total += result[0];
			String outcome = result[2] == 1 ? "dug" : "FAILED";
			rows.add((beginner ? "beginner" : "master") + "\t" + truth.name() + "\t" + result[0] + "\t" + result[1] + "\t" + outcome);
			if (result[2] != 1)
			{
				failures.add(truth.name());
			}
		}
		assertEquals("spots not solved", List.of(), failures);
		return (double) total / spots.size();
	}

	/** @return feels, ticks, 1 when the dig found the spot */
	private static int[] solve(HotColdLocation truth, boolean beginner, WorldPoint start, List<Spot> spots, List<WorldPoint> landings,
		ProbePlanner.Costs costs)
	{
		WorldPoint centre = truth.getWorldPoint();
		List<Reading> readings = new ArrayList<>();
		WorldPoint at = start;
		int ticks = 0;
		for (int feels = 0; feels <= MOST_FEELS; feels++)
		{
			HotColdPlan plan = ProbeJob.decide("sim", spots, beginner, readings, probes(at, landings), costs);
			if (plan.getAction() == HotColdPlan.Action.DIG_HERE || plan.getAction() == HotColdPlan.Action.DIG_SPOT)
			{
				boolean found = plan.getTile().distanceTo2D(centre) <= Band.digRadius(beginner);
				int digTrip = Math.min((at.distanceTo2D(plan.getTile()) + 1) / 2, LANDING_TICKS);
				return new int[]{feels, ticks + digTrip, found ? 1 : 0};
			}
			if (plan.getAction() != HotColdPlan.Action.FEEL || feels == MOST_FEELS)
			{
				return new int[]{feels, ticks, 0};
			}
			Probe probe = plan.getChoice().getProbe();
			ticks += probe.getTicks() + ProbePlanner.FEEL_TICKS;
			at = probe.getTile();
			readings.add(feel(at, centre, beginner, readings));
		}
		return new int[]{MOST_FEELS, ticks, 0};
	}

	private static Reading feel(WorldPoint at, WorldPoint centre, boolean beginner, List<Reading> before)
	{
		int distance = at.distanceTo2D(centre);
		Band band = Band.of(distance, beginner);
		Reading previous = before.isEmpty() ? null : before.get(before.size() - 1);
		Comparison comparison = previous == null || band == Band.SHAKING ? null
			: Comparison.of(distance, new WorldPoint(previous.getX(), previous.getY(), 0).distanceTo2D(centre));
		return new Reading(at.getX(), at.getY(), at.getPlane(), band, comparison);
	}

	private static List<Probe> probes(WorldPoint at, List<WorldPoint> landings)
	{
		List<Probe> probes = new ArrayList<>();
		probes.add(new Probe(at, Probe.Kind.HERE, 0, 0, false));
		for (int dx = -LOCAL_TILES; dx <= LOCAL_TILES; dx += LOCAL_STRIDE)
		{
			for (int dy = -LOCAL_TILES; dy <= LOCAL_TILES; dy += LOCAL_STRIDE)
			{
				int ticks = (Math.max(Math.abs(dx), Math.abs(dy)) + 1) / 2;
				if (ticks > 0)
				{
					probes.add(new Probe(new WorldPoint(at.getX() + dx, at.getY() + dy, 0), Probe.Kind.LOCAL, ticks, ticks, false));
				}
			}
		}
		for (WorldPoint landing : landings)
		{
			probes.add(new Probe(landing, Probe.Kind.LANDING, LANDING_TICKS, LANDING_TICKS, false));
		}
		return probes;
	}

	private static List<WorldPoint> landings() throws IOException
	{
		WalkTable table = WalkTable.load();
		List<WorldPoint> tiles = new ArrayList<>();
		for (int node = 0; node < table.nodeCount(); node++)
		{
			WorldPoint tile = table.tile(node);
			if (tile != null && tile.getPlane() == 0 && tile.getY() < 4500)
			{
				tiles.add(tile);
			}
		}
		return tiles;
	}
}

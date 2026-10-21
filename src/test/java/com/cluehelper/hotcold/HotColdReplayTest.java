package com.cluehelper.hotcold;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.cluehelper.step.StepIndex;
import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.clues.hotcold.HotColdLocation;
import net.runelite.client.plugins.cluescrolls.clues.hotcold.HotColdSolver;
import net.runelite.client.plugins.cluescrolls.clues.hotcold.HotColdTemperature;
import net.runelite.client.plugins.cluescrolls.clues.hotcold.HotColdTemperatureChange;
import org.junit.Test;

/**
 * B18's recorded run, both tiers: a reading's tile from 3544, the logs across logins (each one a round trip through
 * the stored JSON) and a new step, a run and a walk across an edge. The true spot must stay in every grid, exactly.
 * Core's solver runs alongside on the same tiles, to count where our grid keeps a spot core has dropped.
 */
public class HotColdReplayTest
{
	private static final Map<String, String> TRUE_SPOT = Map.of("beginner", "NORTHEAST_OF_AL_KHARID_MINE", "master", "WILDERNESS_49");
	private static final Map<String, String> STEP_KEY = Map.of("beginner", "hotcold:beginner:reldo", "master", "hotcold:master:jorral");

	@Test
	public void recordedRunKeepsTheTrueSpotInEveryGrid() throws IOException
	{
		Gson gson = new Gson();
		StepIndex index = StepIndex.read(gson);
		FeelLogs logs = new FeelLogs();
		int readings = 0;
		int otherLines = 0;
		int coreDropped = 0;
		HotColdSolver core = null;
		List<String> firstReadings = new ArrayList<>();
		for (String[] row : rows())
		{
			String tier = row[2];
			if (row[1].equals("login"))
			{
				logs = gson.fromJson(gson.toJson(logs), FeelLogs.class);
				continue;
			}
			if (!row[1].equals("feel"))
			{
				continue;
			}
			DeviceLine line = DeviceLine.parse(row[6]);
			assertNotNull("every device line parses: " + row[6], line);
			if (line.getKind() != DeviceLine.Kind.READING)
			{
				otherLines++;
				continue;
			}
			readings++;
			int coord = Integer.parseInt(row[3]);
			String key = STEP_KEY.get(tier);
			logs = logs.with(key, new Reading(coord >> 14 & 0x3FFF, coord & 0x3FFF, coord >> 28 & 3, line.getBand(), line.getComparison()));
			List<Reading> log = logs.get(key);
			if (log.size() == 1)
			{
				firstReadings.add(row[0]);
				core = coreSolver(tier.equals("beginner"));
			}
			Reading last = log.get(log.size() - 1);
			core.signal(new WorldPoint(last.getX(), last.getY(), last.getPlane()), HotColdTemperature.getFromTemperatureSet(
				tier.equals("beginner") ? HotColdTemperature.BEGINNER_HOT_COLD_TEMPERATURES : HotColdTemperature.MASTER_HOT_COLD_TEMPERATURES,
				row[6]), HotColdTemperatureChange.of(row[6]));
			Hypotheses grid = Hypotheses.filter(Spots.of(index, tier.equals("beginner")), tier.equals("beginner"), log);
			assertTrue("tick " + row[0] + ": every reading fits the true spot", grid.isExact());
			assertTrue("tick " + row[0] + ": the true spot is kept", grid.spotShares().containsKey(TRUE_SPOT.get(tier)));
			Set<String> coreSpots = new HashSet<>();
			core.getPossibleLocations().forEach(location -> coreSpots.add(location.name()));
			if (!coreSpots.containsAll(grid.spotShares().keySet()))
			{
				coreDropped++;
			}
		}
		assertEquals(104, readings);
		assertEquals(17, otherLines);
		assertEquals("a log starts at the session's first beginner reading and at the master's first", List.of("19", "3259"), firstReadings);
		System.out.println("B18 replay: " + readings + " readings; our grid kept a spot core had dropped after " + coreDropped);
	}

	/** Core's own solver, fed the exact tile; it never resets on a re-read here. */
	private static HotColdSolver coreSolver(boolean beginner)
	{
		Set<HotColdLocation> spots = new HashSet<>();
		for (HotColdLocation location : HotColdLocation.values())
		{
			if (location.isBeginnerClue() == beginner)
			{
				spots.add(location);
			}
		}
		return new HotColdSolver(spots);
	}

	private static List<String[]> rows() throws IOException
	{
		List<String[]> rows = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			HotColdReplayTest.class.getResourceAsStream("/b18/feels.tsv"), StandardCharsets.UTF_8)))
		{
			for (String line = in.readLine(); line != null; line = in.readLine())
			{
				if (!line.startsWith("#"))
				{
					rows.add(Arrays.copyOf(line.split("\t", -1), 7));
				}
			}
		}
		return rows;
	}
}

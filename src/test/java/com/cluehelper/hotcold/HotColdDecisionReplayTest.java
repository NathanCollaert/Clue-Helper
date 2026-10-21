package com.cluehelper.hotcold;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.cluehelper.step.StepIndex;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

/**
 * The live run's plans (recorded by the dev client's HotColdTrace with every probe they weighed) planned again: the
 * same readings and probes must give the same action and tile.
 */
public class HotColdDecisionReplayTest
{
	@Test
	public void liveDecisionsReplay() throws IOException
	{
		Gson gson = new Gson();
		StepIndex index = StepIndex.read(gson);
		List<String> differences = new ArrayList<>();
		int decisions = 0;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(
			HotColdDecisionReplayTest.class.getResourceAsStream("/b19/decisions.jsonl.gz")), StandardCharsets.UTF_8)))
		{
			for (String line = in.readLine(); line != null; line = in.readLine())
			{
				JsonObject recorded = gson.fromJson(line, JsonObject.class);
				boolean beginner = recorded.get("beginner").getAsBoolean();
				List<Reading> readings = Arrays.asList(gson.fromJson(recorded.get("readings"), Reading[].class));
				ProbePlanner.Costs costs = ProbePlanner.Costs.builder()
					.wildernessPenaltyTicks(recorded.get("wildernessPenaltyTicks").getAsInt()).build();
				HotColdPlan plan = ProbeJob.decide(recorded.get("stepKey").getAsString(), Spots.of(index, beginner), beginner, readings,
					probes(recorded.getAsJsonArray("probes")), costs);
				String expected = recorded.get("action").getAsString() + " " + tile(recorded.getAsJsonArray("tile"));
				String replayed = plan.getAction() + " " + plan.getTile();
				if (!expected.equals(replayed))
				{
					differences.add(readings.size() + " readings: live " + expected + ", replay " + replayed);
				}
				decisions++;
			}
		}
		assertTrue("a live run's decisions are recorded", decisions > 0);
		assertEquals(List.of(), differences);
	}

	private static List<Probe> probes(JsonArray recorded)
	{
		List<Probe> probes = new ArrayList<>();
		for (int i = 0; i < recorded.size(); i++)
		{
			JsonObject probe = recorded.get(i).getAsJsonObject();
			probes.add(new Probe(tile(probe.getAsJsonObject("tile")), Probe.Kind.valueOf(probe.get("kind").getAsString()),
				probe.get("ticks").getAsInt(), probe.get("rankTicks").getAsInt(), probe.get("wilderness").getAsBoolean()));
		}
		return probes;
	}

	private static WorldPoint tile(JsonObject tile)
	{
		return new WorldPoint(tile.get("x").getAsInt(), tile.get("y").getAsInt(), tile.get("plane").getAsInt());
	}

	private static WorldPoint tile(JsonArray tile)
	{
		return tile == null ? null : new WorldPoint(tile.get(0).getAsInt(), tile.get(1).getAsInt(), tile.get(2).getAsInt());
	}
}

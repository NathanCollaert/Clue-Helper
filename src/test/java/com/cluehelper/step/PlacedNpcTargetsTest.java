package com.cluehelper.step;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The game's clue script 6657 picks Viggora's target by SECRET_GHOST_RANDOMISER 1, 2, 3 from the clue's target rows in
 * the cache's order (1026, 1027, 1028); ClueLink reads the targets in the step data's order, so the two must agree.
 */
public class PlacedNpcTargetsTest
{
	private static final List<List<Integer>> VIGGORA_ROW_NPCS = List.of(List.of(6117), List.of(6122), List.of(6123));

	@Test
	public void viggorasTargetsAreInTheOrderTheGamePicksThemBy() throws IOException
	{
		List<List<List<Integer>>> placed = new ArrayList<>();
		try (Reader reader = Files.newBufferedReader(StepLoader.JSON, StandardCharsets.UTF_8))
		{
			JsonObject steps = new JsonParser().parse(reader).getAsJsonObject().getAsJsonObject("steps");
			for (Map.Entry<String, JsonElement> step : steps.entrySet())
			{
				JsonObject entry = step.getValue().getAsJsonObject();
				if (entry.has("learnPlace"))
				{
					placed.add(targetNpcs(entry));
				}
			}
		}
		assertEquals(List.of(VIGGORA_ROW_NPCS), placed);
	}

	private static List<List<Integer>> targetNpcs(JsonObject entry)
	{
		List<List<Integer>> npcs = new ArrayList<>();
		for (JsonElement target : entry.getAsJsonArray("targets"))
		{
			List<Integer> ids = new ArrayList<>();
			target.getAsJsonObject().getAsJsonArray("npcs").forEach(id -> ids.add(id.getAsInt()));
			npcs.add(ids);
		}
		return npcs;
	}
}

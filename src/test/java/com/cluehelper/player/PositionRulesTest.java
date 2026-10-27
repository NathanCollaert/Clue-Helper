package com.cluehelper.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class PositionRulesTest
{
	private static final Pattern READING = Pattern.compile(
		"kind=(\\w+) raw=(\\S+) wv=(\\d+) topInstance=(\\w+) norm=(\\S+) boatRule=(\\S+) deck=\\S+ pohBox=(\\w+)");

	@Test
	public void loggedPositionsMapToTheirRoutingTiles() throws Exception
	{
		Map<Position.Kind, Integer> seen = new EnumMap<>(Position.Kind.class);
		List<String> wrong = new ArrayList<>();
		for (String file : new String[]{"/core-link/run1-clue-reads-and-toggles.jsonl", "/core-link/run2-start-with-core-off.jsonl"})
		{
			for (JsonObject event : events(file, "position"))
			{
				Matcher m = reading(event.get("reading").getAsString());
				boolean onBoat = !m.group(3).equals("0");
				WorldPoint norm = tile(m.group(5));
				Position p = onBoat ? PositionRules.onBoat(tile(m.group(6))) : PositionRules.onFoot(norm, Boolean.parseBoolean(m.group(4)));
				seen.merge(p.getKind(), 1, Integer::sum);
				Position.Kind expected = onBoat ? Position.Kind.BOAT : Boolean.parseBoolean(m.group(7)) ? Position.Kind.POH
					: m.group(1).equals("instance") ? Position.Kind.INSTANCE : Position.Kind.WORLD;
				WorldPoint expectedTile = expected == Position.Kind.POH ? PositionRules.POH_NODE : onBoat ? tile(m.group(6)) : norm;
				if (p.getKind() != expected || !p.getTile().equals(expectedTile))
				{
					wrong.add(event + " -> " + p);
				}
			}
		}
		assertEquals("", String.join("\n", wrong));
		for (Position.Kind kind : new Position.Kind[]{Position.Kind.WORLD, Position.Kind.BOAT, Position.Kind.INSTANCE, Position.Kind.POH})
		{
			assertTrue(kind + " seen in the logs: " + seen, seen.getOrDefault(kind, 0) > 0);
		}
	}

	@Test
	public void wildernessLevelsMatchTheGamesCounter() throws Exception
	{
		List<String> wrong = new ArrayList<>();
		int checked = 0;
		for (String file : new String[]{"/spellbook/run1-swap-and-occult.jsonl", "/spellbook/run2-cape-altars-tyss-wild.jsonl"})
		{
			for (JsonObject event : events(file, "wild"))
			{
				int game = Math.max(0, event.get("level").getAsInt());
				WorldPoint tileAtLevelChange = tile(reading(event.get("position").getAsString()).group(2));
				if (PositionRules.wildernessLevel(tileAtLevelChange) != game)
				{
					wrong.add(tileAtLevelChange + ": game " + game + ", ours " + PositionRules.wildernessLevel(tileAtLevelChange));
				}
				checked++;
			}
		}
		assertEquals("", String.join("\n", wrong));
		assertTrue(checked + " Wilderness readings", checked > 20);
		assertEquals("Ferox Enclave", 0, PositionRules.wildernessLevel(new WorldPoint(3150, 3635, 0)));
	}

	private static Matcher reading(String reading)
	{
		Matcher m = READING.matcher(reading);
		assertTrue(reading, m.find());
		return m;
	}

	private static WorldPoint tile(String text)
	{
		if (text.equals("null"))
		{
			return null;
		}
		String[] xyz = text.split(",");
		return new WorldPoint(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
	}

	private static List<JsonObject> events(String resource, String type) throws Exception
	{
		List<JsonObject> events = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			PositionRulesTest.class.getResourceAsStream(resource), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = in.readLine()) != null)
			{
				JsonObject event = new JsonParser().parse(line).getAsJsonObject();
				if (event.get("event").getAsString().equals(type))
				{
					events.add(event);
				}
			}
		}
		return events;
	}
}

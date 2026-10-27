package com.cluehelper.dev;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

public class SpellbookTimingParserTest
{
	private static final String RUN1 = "/spellbook/run1-swap-and-occult.jsonl";
	private static final String RUN2 = "/spellbook/run2-cape-altars-tyss-wild.jsonl";
	private static final Gson GSON = new Gson();
	/** First-click ticks in run 2 made from a distance (house entry or approach), so walking is in their time. */
	private static final Set<Integer> CLICKED_WHILE_STILL_WALKING_TO_IT = Set.of(1602, 2009);

	@Test
	public void spellbookSwapChangesTheBookFourTicksAfterTheCommittingClick() throws IOException
	{
		List<SpellbookTimingParser.Trial> swaps = changed(RUN1, "swap");
		swaps.addAll(changed(RUN2, "swap"));
		assertEquals(8, swaps.size());
		long fours = swaps.stream().filter(s -> s.commitToBook() == 4).count();
		assertEquals(7, fours);
		assertTrue(swaps.stream().allMatch(s -> s.commitToBook() >= 4 && s.commitToBook() <= 5));
	}

	@Test
	public void oneClickChangesLandOnTheNextTickWhenAlreadyAdjacent() throws IOException
	{
		for (String method : new String[]{"occultOp", "cape", "tyss", "pyramidAltar", "astralAltar"})
		{
			List<SpellbookTimingParser.Trial> trials = changed(RUN2, method).stream()
				.filter(t -> t.clicks == 1 && !CLICKED_WHILE_STILL_WALKING_TO_IT.contains(t.firstClickTick))
				.collect(Collectors.toList());
			assertFalse(method, trials.isEmpty());
			for (SpellbookTimingParser.Trial trial : trials)
			{
				assertTrue(method + " " + trial, trial.commitToBook() >= 1 && trial.commitToBook() <= 3);
			}
			long nextTick = trials.stream().filter(t -> t.commitToBook() == 1).count();
			assertTrue(method + " mostly next tick", nextTick * 2 > trials.size());
		}
	}

	@Test
	public void theFirstSpellClickedAfterAChangeIsAccepted() throws IOException
	{
		List<SpellbookTimingParser.Trial> withCast = new ArrayList<>();
		for (String run : new String[]{RUN1, RUN2})
		{
			parse(run).stream().filter(t -> t.changed() && t.castClickTick >= 0).forEach(withCast::add);
		}
		assertTrue(withCast.size() >= 25);
		for (SpellbookTimingParser.Trial trial : withCast)
		{
			assertEquals(trial.toString(), 0, trial.castClicksBeforeAccept);
			assertTrue(trial.toString(), trial.castClickToAccept() >= 1 && trial.castClickToAccept() <= 3);
		}
	}

	@Test
	public void theSixthCapeChangeIsRefusedWithTheDailyLimitLine() throws IOException
	{
		List<SpellbookTimingParser.Trial> refused = parse(RUN2).stream()
			.filter(t -> t.method.equals("cape") && !t.changed())
			.collect(Collectors.toList());
		assertEquals(1, refused.size());
		assertEquals("You may only switch spellbooks five times per day. Try again tomorrow.", refused.get(0).refusal);
	}

	@Test
	public void capeUsesCountUpOneSharedCounterInVarbit4816() throws IOException
	{
		List<Integer> counts = new ArrayList<>();
		for (JsonObject e : events(RUN2))
		{
			if ("vars".equals(e.get("event").getAsString()) && e.getAsJsonObject("from").has("capeUses4816"))
			{
				int from = e.getAsJsonObject("from").get("capeUses4816").getAsInt();
				int to = e.getAsJsonObject("to").get("capeUses4816").getAsInt();
				if (from != to)
				{
					counts.add(to);
				}
			}
		}
		assertEquals(List.of(1, 2, 3, 4, 5), counts);
	}

	@Test
	public void aSwapRevertsExactly200TicksAfterVarbit3617TurnsOn() throws IOException
	{
		List<Integer> durations = new ArrayList<>();
		for (String run : new String[]{RUN1, RUN2})
		{
			int on = -1;
			for (JsonObject e : events(run))
			{
				if ("vars".equals(e.get("event").getAsString()) && e.getAsJsonObject("from").has("swap3617"))
				{
					int from = e.getAsJsonObject("from").get("swap3617").getAsInt();
					int to = e.getAsJsonObject("to").get("swap3617").getAsInt();
					if (from == 0 && to == 1)
					{
						on = e.get("t").getAsInt();
					}
					else if (from == 1 && to == 0 && on >= 0)
					{
						durations.add(e.get("t").getAsInt() - on);
						on = -1;
					}
				}
			}
		}
		assertTrue(durations.contains(200));
		assertEquals(2, durations.stream().filter(d -> d == 200).count());
	}

	@Test
	public void swapBookVarbit4823NeverMoved() throws IOException
	{
		for (String run : new String[]{RUN1, RUN2})
		{
			for (JsonObject e : events(run))
			{
				if ("vars".equals(e.get("event").getAsString()) && e.getAsJsonObject("to").has("swapBook4823"))
				{
					assertEquals(run + " t" + e.get("t"), 0, e.getAsJsonObject("to").get("swapBook4823").getAsInt());
				}
			}
		}
	}

	private static List<SpellbookTimingParser.Trial> changed(String run, String method) throws IOException
	{
		return parse(run).stream().filter(t -> t.method.equals(method) && t.changed()).collect(Collectors.toList());
	}

	private static List<SpellbookTimingParser.Trial> parse(String run) throws IOException
	{
		return SpellbookTimingParser.parse(events(run));
	}

	private static List<JsonObject> events(String resource) throws IOException
	{
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			SpellbookTimingParserTest.class.getResourceAsStream(resource), StandardCharsets.UTF_8)))
		{
			return reader.lines().map(line -> GSON.fromJson(line, JsonObject.class)).collect(Collectors.toList());
		}
	}
}

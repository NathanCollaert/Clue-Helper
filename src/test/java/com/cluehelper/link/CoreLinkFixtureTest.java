package com.cluehelper.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * B03: pins the key rule and the observed core behaviour against the recorded live logs in
 * src/test/resources/b03/. B07 replays the same fixtures through ClueLink.
 */
public class CoreLinkFixtureTest
{
	private static final String RUN1 = "/b03/run1-clue-reads-and-toggles.jsonl";
	private static final String RUN2 = "/b03/run2-start-with-core-off.jsonl";
	private static final Gson GSON = new Gson();

	@Test
	public void keyRuleRebuildsEveryRecordedKey() throws IOException
	{
		List<JsonObject> clues = clueEvents(RUN1);
		int checked = 0;
		for (JsonObject e : clues)
		{
			if (!e.has("fields"))
			{
				continue;
			}
			ClueFields fields = GSON.fromJson(e.get("fields"), ClueFields.class);
			assertEquals("tick " + e.get("t"), e.get("key").getAsString(), ClueFields.key(fields));
			checked++;
		}
		assertEquals(15, checked);
	}

	@Test
	public void stepSequenceIsKeyChangesSeparatedByNullGaps() throws IOException
	{
		List<String> sequence = clueEvents(RUN1).stream()
			.map(e -> e.has("key") ? e.get("key").getAsString() : "-")
			.collect(Collectors.toList());
		List<String> expectedPrefixes = Arrays.asList(
			"threestep:the doorman of the warriors' gui",
			"coordinate:12101",
			"emote:panic at al kharid mine.",
			"-",
			"cryptic:talk to charlie the tramp in varro",
			"skill:CHARLIE:i need to give charlie som",
			"-",
			"cryptic:in the place duke horacio calls ho",
			"-",
			"coordinate:12101",
			"-",
			"anagram:an earl",
			"-",
			"cryptic:in a village of barbarians, i am t",
			"-",
			"anagram:carpet ahoy",
			"-",
			"map:w348",
			"-",
			"cryptic:talk to charlie the tramp in varro",
			"skill:CHARLIE:i need to give charlie a c",
			"-",
			"cryptic:near the open desert i reside, to ",
			"-",
			"coordinate:12101",
			"-");
		assertEquals(expectedPrefixes.size(), sequence.size());
		for (int i = 0; i < sequence.size(); i++)
		{
			assertTrue(i + ": " + sequence.get(i), sequence.get(i).startsWith(expectedPrefixes.get(i)));
		}
	}

	@Test
	public void coreHandsOutStaticEntriesSoIdentityRepeatsAcrossSteps() throws IOException
	{
		List<JsonObject> clues = clueEvents(RUN1);
		assertEquals("elite coordinate read at t83, re-read at t649 and t2240",
			identityAt(clues, 83), identityAt(clues, 649));
		assertEquals(identityAt(clues, 83), identityAt(clues, 2240));
		assertEquals("Charlie cryptic on two different trails",
			identityAt(clues, 325), identityAt(clues, 913));
		assertNotEquals("each Charlie challenge is a different entry", identityAt(clues, 408), identityAt(clues, 942));
	}

	@Test
	public void coreResetsInItsOwnTickOnStepCompletion() throws IOException
	{
		for (JsonObject e : clueEvents(RUN1))
		{
			int t = e.get("t").getAsInt();
			if (t == 294 || t == 495 || t == 773 || t == 841 || t == 909 || t == 1019)
			{
				assertFalse("a priority-0 read still saw the old clue at t" + t, e.get("seenAtPriority0").getAsBoolean());
			}
		}
	}

	@Test
	public void dialogueIdentifiedCluesChangeBetweenPriorityZeroAndMinusOne() throws IOException
	{
		List<JsonObject> changedBetween = events(RUN1, "chat").stream()
			.filter(e -> e.get("coreChangedBetween").getAsBoolean())
			.collect(Collectors.toList());
		assertEquals(2, changedBetween.size());
		for (JsonObject e : changedBetween)
		{
			assertEquals("DIALOG", e.get("type").getAsString());
			assertTrue(e.get("atPriority0").getAsString().startsWith("CrypticClue@"));
			assertTrue(e.get("atPriorityMinus1").getAsString().startsWith("SkillChallengeClue@"));
		}
	}

	@Test
	public void configKeyFlipsBeforeThePluginActuallyStopsOrStarts() throws IOException
	{
		List<JsonObject> toggles = new ArrayList<>();
		for (JsonObject e : allEvents(RUN1))
		{
			String kind = e.get("event").getAsString();
			if (kind.equals("configChanged") || (kind.equals("pluginChanged") && e.get("plugin").getAsString().equals("ClueScrollPlugin")))
			{
				toggles.add(e);
			}
		}
		assertEquals(6, toggles.size());
		for (int i = 0; i < toggles.size(); i += 2)
		{
			JsonObject config = toggles.get(i);
			JsonObject plugin = toggles.get(i + 1);
			assertEquals("configChanged", config.get("event").getAsString());
			assertEquals("pluginChanged", plugin.get("event").getAsString());
			boolean enabling = plugin.get("loaded").getAsBoolean();
			assertTrue("active still the old value when the config event arrives",
				config.get("coreState").getAsString().startsWith("active=" + !enabling));
			assertTrue(plugin.get("coreState").getAsString().startsWith("active=" + enabling));
			assertEquals("AWT-EventQueue-0", plugin.get("thread").getAsString());
		}
	}

	@Test
	public void startingWithCoreDisabledStillLoadsAndPostsNoCoreEvent() throws IOException
	{
		JsonObject start = events(RUN2, "start").get(0);
		assertTrue(start.get("coreFound").getAsBoolean());
		assertEquals("active=false enabled=false cfg=false", start.get("coreState").getAsString());
		assertEquals("none", start.get("serviceClue").getAsString());
		assertEquals("only the re-enable posts a core PluginChanged", 1, events(RUN2, "pluginChanged").stream()
			.filter(e -> e.get("plugin").getAsString().equals("ClueScrollPlugin")).count());
	}

	private static int identityAt(List<JsonObject> clues, int tick)
	{
		return clues.stream().filter(e -> e.get("t").getAsInt() == tick).findFirst()
			.orElseThrow(() -> new AssertionError("no clue event at t" + tick))
			.get("identity").getAsInt();
	}

	private static List<JsonObject> clueEvents(String resource) throws IOException
	{
		return events(resource, "clue");
	}

	private static List<JsonObject> events(String resource, String kind) throws IOException
	{
		return allEvents(resource).stream().filter(e -> e.get("event").getAsString().equals(kind)).collect(Collectors.toList());
	}

	private static List<JsonObject> allEvents(String resource) throws IOException
	{
		List<JsonObject> events = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			CoreLinkFixtureTest.class.getResourceAsStream(resource), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = reader.readLine()) != null)
			{
				events.add(GSON.fromJson(line, JsonObject.class));
			}
		}
		return events;
	}
}

package com.cluehelper.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.cluehelper.step.ClueFacts;
import com.cluehelper.step.StepKey;
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
 * Fixtures of more clue types can be cut from the dev log's "[CH-STEP] fixture" lines, which use the same "clue"
 * event format.
 */
public class CoreLinkFixtureTest
{
	private static final String RUN1 = "/core-link/run1-clue-reads-and-toggles.jsonl";
	private static final String RUN2 = "/core-link/run2-start-with-core-off.jsonl";
	private static final Gson GSON = new Gson();

	@Test
	public void keyRuleRebuildsEveryRecordedKey() throws IOException
	{
		int checked = 0;
		for (JsonObject e : events(RUN1, "clue"))
		{
			if (e.has("fields"))
			{
				ClueFacts facts = GSON.fromJson(e.get("fields"), ClueFacts.class);
				assertEquals("tick " + e.get("t"), e.get("key").getAsString(), StepKey.of(facts));
				checked++;
			}
		}
		assertEquals(15, checked);
	}

	@Test
	public void replayGivesOneChangePerRecordedTransition() throws IOException
	{
		List<String> changes = replay(RUN1).changes;
		List<String> expectedPrefixes = Arrays.asList(
			"NEW threestep:the doorman of the warriors' gui",
			"NEW coordinate:12101",
			"NEW emote:panic at al kharid mine.",
			"CLEARED",
			"NEW cryptic:talk to charlie the tramp in varro",
			"NEW skill:CHARLIE:i need to give charlie som",
			"CLEARED",
			"NEW cryptic:in the place duke horacio calls ho",
			"CLEARED",
			"NEW coordinate:12101",
			"CLEARED",
			"NEW anagram:an earl",
			"CLEARED",
			"NEW cryptic:in a village of barbarians, i am t",
			"CLEARED",
			"NEW anagram:carpet ahoy",
			"CLEARED",
			"NEW map:w348",
			"CLEARED",
			"NEW cryptic:talk to charlie the tramp in varro",
			"NEW skill:CHARLIE:i need to give charlie a c",
			"CLEARED",
			"NEW cryptic:near the open desert i reside, to ",
			"CLEARED",
			"NEW coordinate:12101",
			"CLEARED");
		assertEquals(changes.toString(), expectedPrefixes.size(), changes.size());
		for (int i = 0; i < changes.size(); i++)
		{
			assertTrue(i + ": " + changes.get(i), changes.get(i).startsWith(expectedPrefixes.get(i)));
		}
	}

	@Test
	public void replayKeepsTheActiveFlagOnCoresRealState() throws IOException
	{
		for (String run : List.of(RUN1, RUN2))
		{
			Replay replay = replay(run);
			assertEquals(run + ": active flag vs the recorded isPluginActive", List.of(), replay.activeMismatches);
		}
	}

	@Test
	public void replayAsksForAReadWheneverCoreIsOnWithoutAClueButOneIsHeld() throws IOException
	{
		assertEquals("run 1: client start; each beginner step done or casket opened while the unread elite is held;"
				+ " elite picked up; withdrawn; withdrawn again after core's re-enable",
			List.of(0, 294, 495, 566, 636, 672, 773, 841, 883, 909, 1019, 1037, 2234), replay(RUN1).readPromptStarts);
		assertEquals("run 2: only once core is turned on at t31, and again after the Gauntlet", List.of(31, 465), replay(RUN2).readPromptStarts);
	}

	@Test
	public void coreHandsOutStaticEntriesSoIdentityRepeatsAcrossSteps() throws IOException
	{
		List<JsonObject> clues = events(RUN1, "clue");
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
		for (JsonObject e : events(RUN1, "clue"))
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

	private static final class Replay
	{
		final List<String> changes = new ArrayList<>();
		final List<String> activeMismatches = new ArrayList<>();
		final List<Integer> readPromptStarts = new ArrayList<>();
	}

	/**
	 * Each tick's recorded events first, then one tick read: the probe logged clue reads at GameTick -1, and only on a
	 * change.
	 */
	private static Replay replay(String resource) throws IOException
	{
		List<JsonObject> all = allEvents(resource);
		Replay replay = new Replay();
		LinkState state = new LinkState();
		boolean coreActive = false;
		String key = null;
		Object targets = null;
		boolean holdsClue = false;
		boolean neededRead = false;
		int lastTick = all.get(all.size() - 1).get("t").getAsInt();
		int next = 0;
		for (int tick = 0; tick <= lastTick; tick++)
		{
			for (; next < all.size() && all.get(next).get("t").getAsInt() == tick; next++)
			{
				JsonObject e = all.get(next);
				switch (e.get("event").getAsString())
				{
					case "start":
						coreActive = recordedActive(e.get("coreState").getAsString());
						state.start(coreActive);
						break;
					case "pluginChanged":
						if (e.get("plugin").getAsString().equals("ClueScrollPlugin"))
						{
							state.onPluginChanged(e.get("loaded").getAsBoolean());
						}
						break;
					case "coreState":
						coreActive = recordedActive(e.get("to").getAsString());
						break;
					case "inventory":
						holdsClue = !e.get("clueItems").getAsString().isEmpty();
						break;
					case "clue":
						key = e.has("key") ? e.get("key").getAsString() : null;
						targets = e.has("fields") ? e.getAsJsonObject("fields").get("targets") : null;
						break;
					default:
						break;
				}
				if (e.has("coreState") && state.isActive() != recordedActive(e.get("coreState").getAsString()))
				{
					replay.activeMismatches.add("t" + tick + " " + e.get("event").getAsString() + ": " + state.isActive());
				}
			}
			StepChange change = state.tick(coreActive, key, targets, holdsClue);
			if (change == StepChange.NEW)
			{
				replay.changes.add("NEW " + key);
			}
			else if (change != StepChange.UNCHANGED)
			{
				replay.changes.add(change.name());
			}
			if (state.needsRead() && !neededRead)
			{
				replay.readPromptStarts.add(tick);
			}
			neededRead = state.needsRead();
		}
		return replay;
	}

	private static boolean recordedActive(String coreState)
	{
		return coreState.startsWith("active=true");
	}

	private static int identityAt(List<JsonObject> clues, int tick)
	{
		return clues.stream().filter(e -> e.get("t").getAsInt() == tick).findFirst()
			.orElseThrow(() -> new AssertionError("no clue event at t" + tick))
			.get("identity").getAsInt();
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

package com.cluehelper.step;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.CrypticClue;
import net.runelite.client.plugins.cluescrolls.clues.ThreeStepCrypticClue;
import net.runelite.client.plugins.cluescrolls.clues.hotcold.HotColdLocation;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * On a snapshot difference this writes build/drift/core-steps.tsv: review the diff, then copy it over
 * src/test/resources/drift/core-steps.tsv. A cache-side change shows up only once
 * tools/cache/c5_step_index.py regenerates step-index.json from a newer dump.
 */
public class CoreDriftTest
{
	private static final String GOLDEN = "/drift/core-steps.tsv";
	private static final Path ACTUAL = Paths.get("build/drift/core-steps.tsv");
	private static final Path VIEW_ROWS = Paths.get("build/drift/steps.json");
	private static final String SPOT_PREFIX = "spot:";
	private static final int MOST_DIFF_LINES = 40;

	private static final Set<String> EXPECTED_WITHOUT_ROW = new TreeSet<>(List.of(
		"cryptic:search a crate in the haymaker's arms.",
		"hotcold:beginner:leagues tutor",
		"hotcold:master:leagues tutor",
		SPOT_PREFIX + "SAIL_ISLE_OF_SERPENTS"));

	private static StepIndex index;
	private static Map<String, ClueScroll> steps;
	private static final Map<String, List<String>> SOURCES_BY_KEY = new TreeMap<>();
	/** Step key → snapshot columns → the var states that resolve to them. */
	private static final Map<String, Map<String, List<Integer>>> SNAPSHOT = new TreeMap<>();
	private static final List<String> UNSTABLE_KEYS = new ArrayList<>();
	private static final Map<String, Step> FIRST_BRANCH = new TreeMap<>();

	@BeforeClass
	public static void resolveEverything() throws ReflectiveOperationException
	{
		index = StepIndex.read(new Gson());
		steps = CoreClues.steps();
		ClueScrollPlugin plugin = CoreClues.plugin();
		StepResolver resolver = new StepResolver(() -> index);
		for (Map.Entry<String, ClueScroll> source : steps.entrySet())
		{
			String keyAtZero = null;
			for (int state : CoreClues.VAR_STATES)
			{
				CoreClues.setVarState(state);
				Step step = resolver.resolve(source.getValue(), plugin);
				if (keyAtZero == null)
				{
					keyAtZero = step.getKey();
					SOURCES_BY_KEY.computeIfAbsent(keyAtZero, k -> new ArrayList<>()).add(source.getKey());
					FIRST_BRANCH.putIfAbsent(keyAtZero, step);
				}
				else if (!keyAtZero.equals(step.getKey()))
				{
					UNSTABLE_KEYS.add(source.getKey() + ": " + keyAtZero + " / " + step.getKey() + " at var state " + state);
				}
				SNAPSHOT.computeIfAbsent(keyAtZero, k -> new LinkedHashMap<>())
					.computeIfAbsent(snapshotColumns(step), k -> new ArrayList<>()).add(state);
			}
		}
		CoreClues.setVarState(0);
	}

	@Test
	public void everyStepHasOneKeyOfItsOwnUnderEveryBranch()
	{
		assertEquals("keys that change with a varbit:\n" + String.join("\n", UNSTABLE_KEYS), List.of(), UNSTABLE_KEYS);
		List<String> shared = SOURCES_BY_KEY.entrySet().stream()
			.filter(e -> e.getValue().size() > 1)
			.map(e -> e.getKey() + " <- " + e.getValue())
			.collect(Collectors.toList());
		assertEquals("keys shared by two core entries:\n" + String.join("\n", shared), List.of(), shared);
		assertEquals(steps.size(), SOURCES_BY_KEY.size());
	}

	@Test
	public void everyKeyMapsToItsCacheRows()
	{
		Set<String> coreKeys = SOURCES_BY_KEY.keySet();
		Set<String> dataKeys = index.getSteps().keySet();
		assertEquals("core keys missing from step-index.json (regenerate it, tools/cache/c5_step_index.py)",
			Set.of(), difference(coreKeys, dataKeys));
		assertEquals("step-index.json keys core no longer has", Set.of(), difference(dataKeys, coreKeys));

		Set<String> spotNames = new TreeSet<>();
		for (HotColdLocation spot : HotColdLocation.values())
		{
			spotNames.add(spot.name());
		}
		assertEquals("hot/cold spots core no longer has", Set.of(), difference(index.getHotColdSpots().keySet(), spotNames));

		Set<String> withoutRow = new TreeSet<>();
		index.getSteps().forEach((key, entry) ->
		{
			if (entry.getRows().isEmpty())
			{
				withoutRow.add(key);
			}
		});
		for (String name : spotNames)
		{
			if (!index.getHotColdSpots().containsKey(name))
			{
				withoutRow.add(SPOT_PREFIX + name);
			}
		}
		assertEquals(EXPECTED_WITHOUT_ROW, withoutRow);

		StepIndex.Entry nieveOrSteve = index.entry("anagram:12059");
		assertEquals("I EVEN / VESTE owns one row per branch of varbit 5037", List.of(true, false),
			nieveOrSteve.getRows().stream().map(row -> row.getWhen().holdsFor(0)).collect(Collectors.toList()));
	}

	@Test
	public void resolvedStepsMatchTheGoldenSnapshot() throws IOException
	{
		List<String> actual = snapshotLines();
		Files.createDirectories(ACTUAL.getParent());
		Files.write(ACTUAL, actual, StandardCharsets.UTF_8);
		writeViewRows();

		List<String> golden = golden();
		if (!golden.equals(actual))
		{
			fail("core's resolved steps differ from " + GOLDEN + " (actual in " + ACTUAL + "):\n" + diff(golden, actual));
		}
	}

	@Test
	public void threeStepTakesItsPartsRowsAndShrinksAsPartsAreSolved() throws ReflectiveOperationException
	{
		CoreClues.setVarState(0);
		List<Map.Entry<CrypticClue, Boolean>> parts = new ArrayList<>();
		for (String start : List.of("the doorman of the warriors' guild", "she's small but can build", "my life was spared"))
		{
			CrypticClue part = (CrypticClue) CoreClues.entries("CrypticClue").stream()
				.filter(c -> ClueFacts.normalise(((CrypticClue) c).getText()).startsWith(start))
				.findFirst().orElseThrow(() -> new AssertionError("no cryptic starting " + start));
			parts.add(new AbstractMap.SimpleEntry<>(part, false));
		}
		ThreeStepCrypticClue clue = new ThreeStepCrypticClue(parts, "three parts");
		StepResolver resolver = new StepResolver(() -> index);

		Step whole = resolver.resolve(clue, CoreClues.plugin());
		assertEquals("master", whole.getTier());
		assertTrue(whole.isKnown());
		assertEquals(TargetKind.SEQUENCE, whole.getKind());
		assertEquals(3, whole.getCacheRows().size());
		assertEquals(3, whole.getTargets().size());

		parts.get(0).setValue(true);
		Step oneSolved = resolver.resolve(clue, CoreClues.plugin());
		assertEquals("the key stays, so ClueLink calls it an updated step", whole.getKey(), oneSolved.getKey());
		assertEquals(whole.getTargets().subList(1, 3), oneSolved.getTargets());
	}

	@Test
	public void unknownStepsStillGetCoresTargets() throws ReflectiveOperationException
	{
		CoreClues.setVarState(0);
		ClueScrollPlugin plugin = CoreClues.plugin();
		StepResolver withoutData = new StepResolver(StepIndex::empty);
		List<String> lost = new ArrayList<>();
		for (Map.Entry<String, ClueScroll> source : steps.entrySet())
		{
			Step unknown = withoutData.resolve(source.getValue(), plugin);
			assertFalse(unknown.isKnown());
			if (!unknown.getTargets().equals(CoreClueReader.locations(source.getValue(), plugin)))
			{
				lost.add(source.getKey() + " " + unknown.getKey() + " " + unknown.getTargets());
			}
		}
		assertEquals("unknown steps whose targets aren't core's", List.of(), lost);
	}

	private static String snapshotColumns(Step step)
	{
		return String.join("\t",
			step.getType(),
			step.getTier(),
			step.getKind().name(),
			tiles(step.getTargets()),
			String.join("|", step.getNpcs()),
			step.getRegions().stream().map(String::valueOf).collect(Collectors.joining("|")),
			step.getObjectIds().stream().map(String::valueOf).collect(Collectors.joining("|")),
			String.join("|", step.getObjectNames()),
			step.getRequirements().toString(),
			step.getCacheRows().stream().map(row -> String.valueOf(row.getId())).collect(Collectors.joining("|")),
			oneLine(step.getText()));
	}

	private static List<String> snapshotLines()
	{
		List<String> lines = new ArrayList<>();
		lines.add("key\tbranch\ttype\ttier\tkind\ttargets\tnpcs\tregions\tobjects\tobject names\tneeds\trows\ttext");
		SNAPSHOT.forEach((key, branches) -> branches.forEach((columns, states) ->
			lines.add(key + "\t" + (branches.size() == 1 ? "*" : states.stream().map(String::valueOf).collect(Collectors.joining(","))) + "\t" + columns)));
		for (HotColdLocation spot : HotColdLocation.values())
		{
			StepIndex.Spot row = index.getHotColdSpots().get(spot.name());
			lines.add(SPOT_PREFIX + spot.name() + "\t*\thotcold spot\t" + spot.getType().name().toLowerCase() + "\t\t"
				+ tiles(List.of(spot.getWorldPoint())) + "\t\t\t\t\t\t" + (row == null ? "" : row.getRow()) + "\t" + oneLine(spot.getArea()));
		}
		return lines;
	}

	private static void writeViewRows() throws IOException
	{
		List<Map<String, Object>> rows = new ArrayList<>();
		FIRST_BRANCH.forEach((key, step) ->
		{
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("key", key);
			row.put("type", step.getType());
			row.put("tier", step.getTier());
			row.put("kind", step.getKind().name());
			row.put("targets", tiles(step.getTargets()));
			row.put("targetCount", step.getTargets().size());
			row.put("npcs", String.join(", ", step.getNpcs()));
			row.put("objects", step.getObjectIds() + " " + step.getObjectNames());
			row.put("needs", step.getRequirements().toString());
			row.put("rows", step.getCacheRows().stream().map(CacheRow::getName).collect(Collectors.joining(", ")));
			row.put("branches", SNAPSHOT.get(key).size());
			row.put("text", oneLine(step.getText()));
			rows.add(row);
		});
		Files.write(VIEW_ROWS, new GsonBuilder().disableHtmlEscaping().create().toJson(rows).getBytes(StandardCharsets.UTF_8));
	}

	private static List<String> golden() throws IOException
	{
		try (InputStream in = CoreDriftTest.class.getResourceAsStream(GOLDEN))
		{
			if (in == null)
			{
				return List.of();
			}
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
			{
				return reader.lines().collect(Collectors.toList());
			}
		}
	}

	private static String diff(List<String> golden, List<String> actual)
	{
		Set<String> goldenSet = new TreeSet<>(golden);
		Set<String> actualSet = new TreeSet<>(actual);
		List<String> out = new ArrayList<>();
		for (String line : difference(goldenSet, actualSet))
		{
			out.add("- " + line);
		}
		for (String line : difference(actualSet, goldenSet))
		{
			out.add("+ " + line);
		}
		out.sort((a, b) -> a.substring(2).compareTo(b.substring(2)));
		int shown = Math.min(out.size(), MOST_DIFF_LINES);
		return String.join("\n", out.subList(0, shown)) + (out.size() > shown ? "\n… " + (out.size() - shown) + " more" : "");
	}

	private static String tiles(List<WorldPoint> points)
	{
		return points.stream().map(p -> p.getX() + "," + p.getY() + "," + p.getPlane()).collect(Collectors.joining("|"));
	}

	private static String oneLine(String text)
	{
		return text == null ? "" : text.replaceAll("\\s+", " ").trim();
	}

	private static Set<String> difference(Set<String> a, Set<String> b)
	{
		Set<String> d = new TreeSet<>(a);
		d.removeAll(b);
		return d;
	}
}

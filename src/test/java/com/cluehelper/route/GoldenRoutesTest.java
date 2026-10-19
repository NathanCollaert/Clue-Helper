package com.cluehelper.route;

import static org.junit.Assert.assertTrue;

import com.cluehelper.SpellbookChanges;
import com.cluehelper.player.Book;
import com.cluehelper.player.Charge;
import com.cluehelper.player.Loadouts;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.RuneModel;
import com.cluehelper.player.SpellbookState;
import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.Step;
import com.cluehelper.step.TargetKind;
import com.cluehelper.transport.AvailabilityEngine;
import com.cluehelper.transport.Catalog;
import com.cluehelper.transport.CatalogLoader;
import com.google.gson.JsonElement;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * PLAN §4 Testing: golden routes, one file per fixture loadout plus one for the special cases. A cost-model
 * change regenerates them: the test writes build/golden/*.txt and fails with the changed lines; after the
 * diff is read, copy them over src/test/resources/golden/. Every route also passes two invariants: it spends
 * no more than the player has, and an extra option (spellbook changes, a bank stop) never makes Best slower.
 */
public class GoldenRoutesTest
{
	private static final Path ACTUAL = Paths.get("build/golden");
	/** Where a route starts unless a case says otherwise: the Grand Exchange, with no walk leg. */
	private static final WorldPoint ANYWHERE = new WorldPoint(3164, 3486, 0);
	private static final int SAMPLE_PER_TYPE = 6;
	private static final long NOW_MINUTES = 29_000_000L;
	/** B02's ten largest heuristic regrets on the maxed main, and Draynor Manor's fenced grounds. */
	private static final List<String> REGRETS = List.of(
		"cryptic:search through chests found in the upstairs of houses in eastern falador.",
		"cryptic:you will need to under-cook to solve this one.",
		"cryptic:search through some drawers in the upstairs of a house in rimmington.",
		"anagram:12566",
		"cryptic:search the crates in a bank in varrock.",
		"emote:12021",
		"coordinate:2840,3423,0",
		"coordinate:2303,3328,0",
		"emote:19791");

	private static WalkTable table;
	private static Catalog catalog;
	private static RuneModel runes;
	private static Map<String, Step> steps;
	private static final List<String> timings = new ArrayList<>();

	@BeforeClass
	public static void load() throws IOException
	{
		table = WalkTable.load();
		catalog = CatalogLoader.load();
		runes = Loadouts.runeModel();
		steps = DriftSteps.read();
	}

	@Test
	public void fixtureRoutesMatchTheirGoldenFiles() throws IOException
	{
		List<String> failures = new ArrayList<>();
		for (String fixture : Loadouts.NAMES)
		{
			Loadouts.Loadout loadout = Loadouts.load(fixture);
			PlannerSettings settings = PlannerSettings.builder()
				.avoidWilderness(loadout.getFixture().get("avoidWilderness").getAsBoolean()).build();
			List<String> lines = new ArrayList<>();
			List<Long> nanos = new ArrayList<>();
			List<Integer> labelCounts = new ArrayList<>();
			List<Long> contexts = new ArrayList<>();
			List<Integer> searchCounts = new ArrayList<>();
			for (Step step : sample(loadout))
			{
				PlanInput input = input(ready(loadout.getCarried()), step, PlanStart.teleportOnly(ANYWHERE), settings).build();
				Plan plan = checked(input);
				nanos.add(plan.getSearchNanos());
				labelCounts.add(plan.getLabels());
				contexts.add(plan.getContextNanos());
				searchCounts.add(plan.getSearches());
				lines.add(line(step.getKey(), plan));
			}
			timings.add(timing(fixture, nanos, labelCounts) + String.format("; judging median %.1f ms; %d searches",
				contexts.stream().mapToLong(Long::longValue).sorted().toArray()[contexts.size() / 2] / 1e6,
				searchCounts.stream().mapToInt(Integer::intValue).sum()));
			compare(fixture, lines, failures);
		}
		writeTimings();
		assertTrue(String.join("\n", failures), failures.isEmpty());
	}

	@Test
	public void specialCasesMatchTheirGoldenFile() throws IOException
	{
		List<String> lines = new ArrayList<>();
		PlannerSettings plain = PlannerSettings.builder().build();
		PlannerSettings bankOn = plain.toBuilder().bankEdge(true).build();
		PlayerSnapshot maxed = ready(Loadouts.load("maxed-main").getCarried());
		PlayerSnapshot mid = ready(Loadouts.load("mid-ironman").getCarried());
		Step varrock = step("cryptic:search the crates in a bank in varrock.");
		Step draynor = step("map:w348");
		Step senntisten = step("map:w347");
		Step edgeville = step("cryptic:talk to the mother of a basement dwelling son.");

		PlayerSnapshot glory1 = mid.toBuilder().inventory(Map.of(itemId("AMULET_OF_GLORY_1"), 1)).charges(Map.of(
			itemId("AMULET_OF_GLORY_1"), new Charge(1, Charge.Source.ITEM_ID))).build();
		lines.add(line("consumption: glory(1) carried, bank on with glory(1), to Draynor",
			checked(input(glory1, draynor, PlanStart.teleportOnly(ANYWHERE), bankOn).banked(glory1).build())));
		PlayerSnapshot minigames = mid.toBuilder().inventory(Map.of()).build();
		lines.add(line("consumption: minigame teleports only, bank on, to Varrock bank crates",
			checked(input(minigames, varrock, PlanStart.teleportOnly(ANYWHERE), bankOn).banked(withBank(minigames, "Varrock tablet")).build())));

		PlayerSnapshot lunarMaxed = onBook(maxed, Book.LUNAR, false);
		lines.add(line("Lunar, swap runes and Max cape carried, to Varrock: Spellbook Swap, not the cape",
			checked(input(lunarMaxed, varrock, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("Lunar, swap runes and Max cape, Spellbook Swap only",
			checked(input(lunarMaxed, varrock, PlanStart.teleportOnly(ANYWHERE), plain.toBuilder().spellbookChanges(SpellbookChanges.SWAP_ONLY).build()).build())));
		PlayerSnapshot pending = onBook(maxed, Book.STANDARD, true);
		lines.add(line("pending revert to Lunar on Standard, 150 ticks left: Standard teleport at +0",
			checked(input(pending, varrock, PlanStart.teleportOnly(ANYWHERE), plain).revertTicksLeft(150).build())));
		lines.add(line("pending revert, window unknown: plans from Lunar",
			checked(input(pending, varrock, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("pending revert on Standard, Ancient target: the cape from a pending revert",
			checked(input(pending, senntisten, PlanStart.teleportOnly(ANYWHERE), plain).revertTicksLeft(150).build())));
		lines.add(line("99 Magic, no cape uses left today, Ancient target",
			checked(input(capeUsesLeft(maxed, 0), senntisten, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("Lunar, swap runes only in the bank (bank on)",
			checked(input(withoutSwapRunes(lunarMaxed), varrock, PlanStart.teleportOnly(ANYWHERE), bankOn)
				.banked(lunarMaxed).build())));

		lines.add(line("Wilderness start at level 26 (3100,3720), to Varrock",
			checked(input(maxed, varrock, PlanStart.teleportOnly(new WorldPoint(3100, 3720, 0)), plain).build())));
		lines.add(line("Wilderness start at level 36 (3100,3800), to Varrock",
			checked(input(maxed, varrock, PlanStart.teleportOnly(new WorldPoint(3100, 3800, 0)), plain).build())));
		PlayerSnapshot f2pAncient = onBook(maxed, Book.ANCIENT, false).toBuilder().membersWorld(false).build();
		lines.add(line("member on an F2P world on Ancient: Edgeville Home Teleport",
			checked(input(f2pAncient, edgeville, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		PlayerSnapshot hcim = ready(Loadouts.load("hcim").getCarried());
		Step wildTarget = step("coordinate:23168");
		lines.add(line("avoidWilderness with a Wilderness target",
			checked(input(hcim, wildTarget, PlanStart.teleportOnly(ANYWHERE), plain.toBuilder().avoidWilderness(true).build()).build())));
		lines.add(line("avoidWilderness, target outside: no Wilderness landing",
			checked(input(hcim, step("emote:12021"), PlanStart.teleportOnly(ANYWHERE), plain.toBuilder().avoidWilderness(true).build()).build())));

		lines.add(line("bank tipping point: mid-ironman, bank on, to Varrock bank crates",
			checked(input(mid, varrock, PlanStart.teleportOnly(ANYWHERE), bankOn).banked(withBank(mid, Loadouts.load("mid-ironman").getBanked())).build())));
		lines.add(line("start on a node: standing at the Varrock Teleport landing, to Varrock bank crates",
			checked(input(mid, varrock, PlanStart.atNode(table, table.node(new WorldPoint(3213, 3424, 0))), plain).build())));

		lines.add(line("AREA: " + firstOf(TargetKind.AREA).getKey(), checked(input(maxed, firstOf(TargetKind.AREA), PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("SEQUENCE skill: " + firstOf(TargetKind.SEQUENCE).getKey(), checked(input(maxed, firstOf(TargetKind.SEQUENCE), PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("SEQUENCE three-step (6 orders)", checked(input(maxed, threeStep(), PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(probeLine(checked(input(mid, probes(), PlanStart.teleportOnly(ANYWHERE), plain).build())));

		List<String> failures = new ArrayList<>();
		compare("special", lines, failures);
		assertTrue(String.join("\n", failures), failures.isEmpty());
	}

	// ---------------------------------------------------------------- invariants

	/** Plans, then checks both invariants; returns the plan. */
	private static Plan checked(PlanInput input)
	{
		Plan plan = Planner.plan(input);
		List<Route> routes = new ArrayList<>();
		routes.add(plan.getBest());
		routes.add(plan.getDirect());
		routes.add(plan.getSameBook());
		routes.addAll(plan.getAlternatives());
		plan.getCandidates().forEach(c -> routes.add(c.getRoute()));
		for (Route route : routes)
		{
			if (route != null)
			{
				SpendCheck.assertWithinMeans(input, route);
			}
		}
		if (plan.getBest() != null)
		{
			for (PlanInput fewer : fewerOptions(input))
			{
				Route reduced = Planner.plan(fewer).getBest();
				if (reduced != null)
				{
					assertTrue(input.getStep().getKey() + ": an extra option made Best slower: " + plan.getBest().describe() + " ("
							+ plan.getBest().getRankHalfTicks() + ") vs " + reduced.describe() + " (" + reduced.getRankHalfTicks() + ")",
						plan.getBest().getRankHalfTicks() <= reduced.getRankHalfTicks());
				}
			}
		}
		return plan;
	}

	private static List<PlanInput> fewerOptions(PlanInput input)
	{
		List<PlanInput> fewer = new ArrayList<>();
		if (input.getSettings().getSpellbookChanges() != SpellbookChanges.OFF)
		{
			fewer.add(input.toBuilder().settings(input.getSettings().toBuilder().spellbookChanges(SpellbookChanges.OFF).build()).build());
		}
		if (input.getSettings().isBankEdge())
		{
			fewer.add(input.toBuilder().settings(input.getSettings().toBuilder().bankEdge(false).build()).build());
		}
		return fewer;
	}

	// ---------------------------------------------------------------- output

	private static String line(String name, Plan plan)
	{
		StringBuilder out = new StringBuilder(name);
		out.append("\n  best      ").append(describe(plan.getBest()));
		if (plan.getDirect() != null)
		{
			out.append("\n  direct    ").append(describe(plan.getDirect()));
		}
		if (plan.getSameBook() != null)
		{
			out.append("\n  same book ").append(describe(plan.getSameBook()));
		}
		if (plan.getFasterUnqualified() != null)
		{
			out.append("\n  faster, under the margin ").append(describe(plan.getFasterUnqualified()));
		}
		for (Route alternative : plan.getAlternatives())
		{
			out.append("\n  alt       ").append(describe(alternative));
		}
		if (plan.getNote() != null)
		{
			out.append("\n  note      ").append(plan.getNote());
		}
		return out.toString();
	}

	private static String describe(Route route)
	{
		if (route == null)
		{
			return "none";
		}
		String extras = route.getRankHalfTicks() != route.getHalfTicks() ? " (rank " + (route.getRankHalfTicks() + 1) / 2 + ")" : "";
		String spent = route.getSpent().isEmpty() ? "" : " " + route.getSpent();
		String ends = route.getEndBook() != route.getStartBook() ? " leaves you on " + route.getEndBook() : "";
		return route.getTicks() + "t" + extras + ": " + route.describe() + spent + ends;
	}

	private static String probeLine(Plan plan)
	{
		return "PROBES (hot/cold spots): " + new TreeMap<>(plan.getProbeTicks().entrySet().stream().collect(Collectors.toMap(
			e -> e.getKey().getX() + "," + e.getKey().getY() + "," + e.getKey().getPlane(), Map.Entry::getValue)));
	}

	private static void compare(String name, List<String> lines, List<String> failures) throws IOException
	{
		Files.createDirectories(ACTUAL);
		Path actualFile = ACTUAL.resolve(name + ".txt");
		Files.write(actualFile, lines, StandardCharsets.UTF_8);
		List<String> golden = golden(name);
		List<String> actual = Files.readAllLines(actualFile, StandardCharsets.UTF_8);
		if (!actual.equals(golden))
		{
			failures.add(name + ": routes differ from /golden/" + name + ".txt (actual in " + actualFile + "), first difference: "
				+ firstDifference(golden, actual));
		}
	}

	private static String firstDifference(List<String> golden, List<String> actual)
	{
		for (int i = 0; i < Math.max(golden.size(), actual.size()); i++)
		{
			String g = i < golden.size() ? golden.get(i) : "<none>";
			String a = i < actual.size() ? actual.get(i) : "<none>";
			if (!g.equals(a))
			{
				return "line " + (i + 1) + "\n    golden: " + g + "\n    actual: " + a;
			}
		}
		return "none";
	}

	private static List<String> golden(String name) throws IOException
	{
		try (InputStream in = GoldenRoutesTest.class.getResourceAsStream("/golden/" + name + ".txt"))
		{
			if (in == null)
			{
				return List.of();
			}
			return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().collect(Collectors.toList());
		}
	}

	private static String timing(String fixture, List<Long> nanos, List<Integer> labelCounts)
	{
		long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
		int[] labels = labelCounts.stream().mapToInt(Integer::intValue).sorted().toArray();
		return String.format("%s: %d plans, median %.1f ms, p90 %.1f ms, max %.1f ms; labels median %d, max %d", fixture,
			sorted.length, sorted[sorted.length / 2] / 1e6, sorted[sorted.length * 9 / 10] / 1e6, sorted[sorted.length - 1] / 1e6,
			labels[labels.length / 2], labels[labels.length - 1]);
	}

	private static void writeTimings() throws IOException
	{
		Files.write(ACTUAL.resolve("timings.txt"), timings, StandardCharsets.UTF_8);
		timings.forEach(System.out::println);
	}

	// ---------------------------------------------------------------- inputs

	static PlanInput.PlanInputBuilder input(PlayerSnapshot player, Step step, PlanStart start, PlannerSettings settings)
	{
		return PlanInput.builder()
			.step(step)
			.player(player)
			.engine(new AvailabilityEngine(runes, id -> "item " + id))
			.nowMinutes(NOW_MINUTES)
			.catalog(catalog)
			.table(table)
			.start(start)
			.settings(settings);
	}

	/** Fixtures leave the teleport timers unread; they are ready here. */
	private static PlayerSnapshot ready(PlayerSnapshot player)
	{
		Map<Integer, Integer> varps = new HashMap<>(player.getVarps());
		varps.put(892, 0);
		varps.put(888, 0);
		return player.toBuilder().varps(varps).build();
	}

	private static PlayerSnapshot onBook(PlayerSnapshot player, Book book, boolean pending)
	{
		SpellbookState s = player.getSpellbook();
		Map<Integer, Integer> varbits = new HashMap<>(player.getVarbits());
		varbits.put(4070, book.ordinal());
		return player.toBuilder().varbits(varbits).spellbook(new SpellbookState(book, pending, s.getCapeChangesLeft(),
			s.isArceuusUnlocked(), s.isAncientAccess(), s.isLunarAccess(), s.isSwapAccess())).build();
	}

	private static PlayerSnapshot capeUsesLeft(PlayerSnapshot player, int left)
	{
		SpellbookState s = player.getSpellbook();
		Map<Integer, Integer> varbits = new HashMap<>(player.getVarbits());
		varbits.put(4816, SpellbookState.CAPE_CHANGES_PER_DAY - left);
		return player.toBuilder().varbits(varbits).spellbook(new SpellbookState(s.getBook(), s.isPendingRevert(), left,
			s.isArceuusUnlocked(), s.isAncientAccess(), s.isLunarAccess(), s.isSwapAccess())).build();
	}

	private static PlayerSnapshot withoutSwapRunes(PlayerSnapshot player)
	{
		Map<Integer, Integer> inventory = new HashMap<>(player.getInventory());
		inventory.remove(itemId("ASTRALRUNE"));
		inventory.remove(itemId("COSMICRUNE"));
		return player.toBuilder().inventory(inventory).build();
	}

	private static PlayerSnapshot withBank(PlayerSnapshot player, String... families)
	{
		return withBank(player, Arrays.stream(families).map(Loadouts::itemId).collect(Collectors.toList()));
	}

	/** A_banked stand-in until B15: the bank's items as if carried. */
	private static PlayerSnapshot withBank(PlayerSnapshot player, List<Integer> banked)
	{
		Map<Integer, Integer> inventory = new HashMap<>(player.getInventory());
		banked.forEach(id -> inventory.merge(id, 1, Integer::sum));
		return player.toBuilder().inventory(inventory).build();
	}

	private static int itemId(String gameval)
	{
		try
		{
			return ItemID.class.getField(gameval).getInt(null);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalArgumentException(gameval, e);
		}
	}

	// ---------------------------------------------------------------- steps

	/** Up to {@link #SAMPLE_PER_TYPE} steps per clue type, evenly spread over the sorted keys, plus B02's regrets. */
	private static List<Step> sample(Loadouts.Loadout loadout)
	{
		JsonElement tiers = loadout.getFixture().get("tiers");
		List<String> allowed = tiers.isJsonArray() ? new ArrayList<>() : null;
		if (allowed != null)
		{
			tiers.getAsJsonArray().forEach(t -> allowed.add(t.getAsString()));
		}
		Map<String, List<Step>> byType = new TreeMap<>();
		for (Step step : steps.values())
		{
			if ((allowed == null || allowed.contains(step.getTier())) && step.getKind() != TargetKind.PROBES)
			{
				byType.computeIfAbsent(step.getType(), t -> new ArrayList<>()).add(step);
			}
		}
		List<Step> sample = new ArrayList<>();
		byType.forEach((type, list) ->
		{
			int take = Math.min(SAMPLE_PER_TYPE, list.size());
			for (int i = 0; i < take; i++)
			{
				sample.add(list.get(i * list.size() / take));
			}
		});
		for (String key : REGRETS)
		{
			Step step = steps.get(key);
			if (step != null && !sample.contains(step) && (allowed == null || allowed.contains(step.getTier())))
			{
				sample.add(step);
			}
		}
		return sample;
	}

	private static Step step(String key)
	{
		return steps.get(key);
	}

	private static Step firstOf(TargetKind kind)
	{
		return steps.values().stream().filter(s -> s.getKind() == kind).findFirst().orElseThrow();
	}

	/** Three master cryptic parts, in the order the scroll lists them. */
	private static Step threeStep()
	{
		List<WorldPoint> parts = new ArrayList<>();
		for (String key : List.of("cryptic:search through chests found in the upstairs of houses in eastern falador.",
			"cryptic:you will need to under-cook to solve this one.", "cryptic:search the crates in a bank in varrock."))
		{
			parts.add(steps.get(key).getTargets().get(0));
		}
		return Step.builder().key("threestep:test").type(ClueTypes.THREE_STEP).tier("master").kind(TargetKind.SEQUENCE)
			.targets(parts).regions(List.of()).known(true).build();
	}

	/** Eight master hot/cold spots, as B19 will hand them over. */
	private static Step probes()
	{
		List<WorldPoint> spots = steps.values().stream().filter(s -> s.getType().equals("hotcold spot")).limit(8)
			.map(s -> s.getTargets().get(0)).collect(Collectors.toList());
		return Step.builder().key("hotcold:test").type("hotcold").tier("master").kind(TargetKind.PROBES).targets(spots)
			.regions(List.of()).known(true).build();
	}

}

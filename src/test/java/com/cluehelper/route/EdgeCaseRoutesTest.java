package com.cluehelper.route;

import com.cluehelper.memory.StashMemory;
import static org.junit.Assert.assertTrue;

import com.cluehelper.SpellbookChanges;
import com.cluehelper.player.Book;
import com.cluehelper.player.Charge;
import com.cluehelper.player.HouseState;
import com.cluehelper.player.Loadouts;
import com.cluehelper.player.PlayerSnapshot;
import com.cluehelper.player.PositionRules;
import com.cluehelper.player.RuneModel;
import com.cluehelper.player.SpellbookState;
import com.cluehelper.step.ClueTypes;
import com.cluehelper.step.Step;
import com.cluehelper.step.TargetKind;
import com.cluehelper.transport.AvailabilityEngine;
import com.cluehelper.transport.Catalog;
import com.cluehelper.transport.CatalogLoader;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirements;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Hand-built cases that fail quietly: consumption, spellbook changes, Wilderness starts, bank tipping points, the house,
 * rides and start legs, apart from the campaign's played steps. On a change the test writes build/edge-cases.txt and
 * fails with the first changed line; once the diff is read, copy it over src/test/resources/edge-cases.txt.
 */
public class EdgeCaseRoutesTest
{
	private static final Path ACTUAL = Paths.get("build/edge-cases.txt");
	private static final String GOLDEN = "/edge-cases.txt";
	private static final WorldPoint ANYWHERE = new WorldPoint(3164, 3486, 0);
	private static final long NOW_MINUTES = 29_000_000L;
	private static final int HOME_TELEPORT_TIMER_VARP = 892;
	private static final int MINIGAME_TELEPORT_TIMER_VARP = 888;
	private static final int TEAR_OF_THE_SOUL_DOCK = 45;
	/** The Kharazi ledge below the cliff and its vine up. */
	private static final int LEDGE_SOUTH_Y = 2940;
	private static final int VINE_X = 2899;
	private static final int GREATER_TELEPORT_FOCUS = 2;

	/** Mid-ironman steps where a bank stop wins or loses by a few ticks either side of the margin. */
	private static final List<String> TIPPING_POINTS = List.of("coordinate:3556", "map:3599", "spot:MISTHALIN_VARROCK_STONE_CIRCLE");


	private static WalkTable table;
	private static WorldMap map;
	private static Catalog catalog;
	private static RuneModel runes;
	private static Map<String, Step> steps;

	@BeforeClass
	public static void load() throws IOException
	{
		table = WalkTable.load();
		map = WorldMap.load();
		catalog = CatalogLoader.load();
		runes = Loadouts.runeModel();
		steps = DriftSteps.read();
	}

	/** Partway to the Gu'Tanoth cave its entrance lies past the loaded scene; going back to the landing must not win. */
	@Test
	public void startJoinsALongWalkPartway() throws IOException
	{
		LoadedScene scene = SceneFiles.resource("2504_2864_0").getScene();
		WorldPoint partway = new WorldPoint(2538, 2940, 0);
		PlanStart start = PlanStart.scene(partway, WalkCosts.from(Ground.world(map, scene, table, row -> true), partway));
		PlayerSnapshot maxed = ready(Loadouts.load("maxed-main").getCarried());
		Route best = checked(input(maxed, step("coordinate:3548"), start, PlannerSettings.builder().build()).build()).getBest();
		assertTrue("walks on: " + best.describe(), best.getLegs().stream().allMatch(leg -> leg.getKind() == Leg.Kind.WALK));
	}

	/**
	 * The path line leads all the way, past the loaded scene: on the ledge below the Kharazi cliff it never heads east
	 * into the ledge's dead end past the vine, it takes the hole down to Gu'Tanoth's cave rather than the glider
	 * landing's walk, and it leads on to Tyras Camp from a tile past the Isafdar landing.
	 */
	private static final WorldPoint KHARAZI_CLUE = new WorldPoint(2954, 2933, 0);
	/** Not a walk-table node, beside the clue. */
	private static final WorldPoint KHARAZI_STASH = new WorldPoint(2952, 2932, 0);
	private static final int AT_HOLE_TILES = 2;

	@Test
	public void lineLeadsTheWholeWay() throws IOException
	{
		WorldWalk walk = new WorldWalk();
		for (String name : List.of("2824_2896_0", "2848_2896_0", "2808_2904_0", "2848_2888_0"))
		{
			LoadedScene scene = scene(name);
			Ground ground = Ground.world(map, scene, table, row -> true);
			for (int x = 2880; x <= 2930; x += 2)
			{
				WorldPoint from = new WorldPoint(x, 2942, 0);
				for (WorldPoint goal : List.of(KHARAZI_CLUE, KHARAZI_STASH))
				{
					List<WorldPoint> line = scene.contains(from) ? walk.path(ground, from, WalkGoal.tile(goal)) : List.of();
					assertTrue(name + ": the line from " + from + " to " + goal + " heads into the ledge's dead end: " + line,
						line.stream().noneMatch(tile -> tile.getY() >= LEDGE_SOUTH_Y && tile.getX() > Math.max(VINE_X, from.getX())));
				}
			}
		}
		WorldPoint at = new WorldPoint(2509, 2954, 0);
		WorldPoint caveEntrance = new WorldPoint(2500, 2988, 0);
		List<WorldPoint> line = walk.path(Ground.world(map, scene("2488_2872_0"), table, row -> true), at, WalkGoal.tile(new WorldPoint(2581, 3030, 0)));
		assertTrue("through the hole from " + at + ": " + line,
			line.stream().anyMatch(tile -> tile.distanceTo2D(caveEntrance) <= AT_HOLE_TILES && tile.getPlane() == caveEntrance.getPlane()));

		WorldPoint walkedOn = new WorldPoint(2162, 3093, 0);
		WorldPoint tyrasClue = new WorldPoint(2209, 3161, 0);
		List<WorldPoint> onward = walk.path(Ground.world(map, scene("2096_3016_0"), table, row -> true), walkedOn, WalkGoal.tile(tyrasClue));
		assertTrue("a line on from " + walkedOn + ": " + onward, !onward.isEmpty() && onward.get(onward.size() - 1).distanceTo2D(tyrasClue) <= 2);
	}

	private static LoadedScene scene(String name) throws IOException
	{
		try (InputStream in = EdgeCaseRoutesTest.class.getResourceAsStream("/scenes/" + name + ".scene.gz"))
		{
			return SceneFiles.read(in).getScene();
		}
	}

	@Test
	public void edgeCasesMatchTheirGoldenFile() throws IOException
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
			checked(input(minigames, varrock, PlanStart.teleportOnly(ANYWHERE), bankOn).banked(withBank(minigames, Map.of(
				Loadouts.itemId("Varrock tablet"), 5))).build())));

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
		PlayerSnapshot noSwapRunes = withoutSwapRunes(lunarMaxed);
		lines.add(line("Lunar, swap runes only in the bank (bank on)",
			checked(input(noSwapRunes, varrock, PlanStart.teleportOnly(ANYWHERE), bankOn)
				.banked(withBank(noSwapRunes, Map.of(ItemID.ASTRALRUNE, 1000, ItemID.COSMICRUNE, 1000))).build())));
		lines.add(line("Lunar, swap runes only in the bank, Senntisten: bank + Spellbook Swap vs the next best",
			checked(input(capeUsesLeft(noSwapRunes, 0), senntisten, PlanStart.teleportOnly(ANYWHERE), bankOn)
				.banked(withBank(capeUsesLeft(noSwapRunes, 0), Map.of(ItemID.ASTRALRUNE, 1000, ItemID.COSMICRUNE, 1000))).build())));

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
		Step inAndOut = wildTarget.toBuilder().targets(List.of(wildTarget.getTargets().get(0), new WorldPoint(3213, 3424, 0))).build();
		lines.add(line("avoidWilderness, targets in and out of it: to the one outside",
			checked(input(hcim, inAndOut, PlanStart.teleportOnly(ANYWHERE), plain.toBuilder().avoidWilderness(true).build()).build())));
		lines.add(line("avoidWilderness, target outside: no Wilderness landing",
			checked(input(hcim, step("emote:12021"), PlanStart.teleportOnly(ANYWHERE), plain.toBuilder().avoidWilderness(true).build()).build())));
		lines.add(line("avoidWilderness, King Black Dragon's lair: outside it, but only reachable through it",
			checked(input(maxed, step("emote:dance in the king black dragon's lair. beware of double agents! equip a black dragonhide body, black dragonhide vambraces and a black dragon mask."),
				PlanStart.teleportOnly(ANYWHERE), plain.toBuilder().avoidWilderness(true).build()).build())));
		lines.add(line("avoidWilderness, start at the level 50 obelisk (3306,3915), to Varrock: out by the obelisk",
			checked(input(maxed, varrock, PlanStart.atNode(table, table.node(new WorldPoint(3306, 3915, 0))),
				plain.toBuilder().avoidWilderness(true).build()).build())));

		PlayerSnapshot midBank = ready(Loadouts.load("mid-ironman").getBankedSnapshot());
		lines.add(line("bank tipping point: mid-ironman, bank on, to Varrock bank crates",
			checked(input(mid, varrock, PlanStart.teleportOnly(ANYWHERE), bankOn).banked(midBank).build())));
		bankCases(lines, maxed, mid, midBank, bankOn);
		houseCases(lines, maxed, plain);
		networkCases(lines, maxed, mid, plain);
		rideCases(lines, maxed, plain);
		lines.add(line("start on a node: standing at the Varrock Teleport landing, to Varrock bank crates",
			checked(input(mid, varrock, PlanStart.atNode(table, table.node(new WorldPoint(3213, 3424, 0))), plain).build())));

		lines.add(line("AREA: " + firstOf(TargetKind.AREA).getKey(), checked(input(maxed, firstOf(TargetKind.AREA), PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("SEQUENCE skill: " + firstOf(TargetKind.SEQUENCE).getKey(), checked(input(maxed, firstOf(TargetKind.SEQUENCE), PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("SEQUENCE three-step (6 orders)", checked(input(maxed, threeStep(), PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(probeLine(checked(input(mid, probes(), PlanStart.teleportOnly(ANYWHERE), plain).build())));

		List<String> failures = new ArrayList<>();
		compare(lines, failures);
		assertTrue(String.join("\n", failures), failures.isEmpty());
	}

	private static void bankCases(List<String> lines, PlayerSnapshot maxed, PlayerSnapshot mid, PlayerSnapshot midBank,
		PlannerSettings bankOn)
	{
		Step fountain = step("coordinate:12101");
		PlayerSnapshot noCapeUses = capeUsesLeft(maxed, 0);
		lines.add(line("Fountain of Rune case: elite coordinate 12101, no cape uses left, Wilderness sword 4 banked",
			checked(input(noCapeUses, fountain, PlanStart.teleportOnly(ANYWHERE), bankOn)
				.banked(withBank(noCapeUses, Map.of(Loadouts.itemId("Wilderness sword 4"), 1))).build())));
		for (String key : TIPPING_POINTS)
		{
			lines.add(line("bank tipping point: mid-ironman, " + key,
				checked(input(mid, step(key), PlanStart.teleportOnly(ANYWHERE), bankOn).banked(midBank).build())));
			lines.add(line("bank tipping point with the PIN still to come: mid-ironman, " + key,
				checked(input(mid, step(key), PlanStart.teleportOnly(ANYWHERE), bankOn).banked(midBank).pinPending(true).build())));
		}

		Step draynorDig = step("map:w348");
		RequiredItems spade = required(StashMemory.State.NONE, need("spade", ItemRequirements.item(ItemID.SPADE), false));
		PlayerSnapshot glory1 = mid.toBuilder().inventory(Map.of(itemId("AMULET_OF_GLORY_1"), 1)).charges(Map.of(
			itemId("AMULET_OF_GLORY_1"), new Charge(1, Charge.Source.ITEM_ID))).build();
		lines.add(line("required spade: glory(1) carried, a spade in the bank, to Draynor (dig)",
			checked(input(glory1, draynorDig, PlanStart.teleportOnly(ANYWHERE), bankOn).required(spade)
				.banked(withBank(glory1, Map.of(ItemID.SPADE, 1))).build())));
		lines.add(line("required spade: carried, to Draynor (dig)",
			checked(input(alsoCarrying(mid, ItemID.SPADE), draynorDig, PlanStart.teleportOnly(ANYWHERE), bankOn).required(spade)
				.banked(midBank).build())));
		lines.add(line("required spade: nowhere, at a tool leprechaun",
			checked(input(mid, draynorDig, PlanStart.teleportOnly(ANYWHERE), bankOn).required(spade.toBuilder().spadeAtLeprechaun(true).build())
				.banked(midBank).build())));

		Step emote = step("emote:12021");
		PlayerSnapshot emoteItemBanked = midBank.toBuilder().bank(merged(midBank.getBank(), ItemID.BRONZE_FULL_HELM)).build();
		RequiredItems.Need helm = need("Bronze full helm", ItemRequirements.item(ItemID.BRONZE_FULL_HELM), true);
		lines.add(line("emote item in the bank, STASH filled",
			checked(input(mid, emote, PlanStart.teleportOnly(ANYWHERE), bankOn).required(required(StashMemory.State.FILLED, helm))
				.banked(emoteItemBanked).build())));

		Set<Integer> capes = Set.of(ItemID.SKILLCAPE_MAX, ItemID.SKILLCAPE_MAX_WORN, ItemID.SKILLCAPE_CONSTRUCTION,
			ItemID.SKILLCAPE_CONSTRUCTION_TRIMMED);
		PlayerSnapshot capesBanked = withoutAll(maxed, capes);
		Map<Integer, Integer> capesBank = merged(merged(capesBanked.getBank(), ItemID.SKILLCAPE_MAX), ItemID.SKILLCAPE_CONSTRUCTION_TRIMMED);
		int craftingGuildBank = table.node(new WorldPoint(2935, 3280, 0));
		lines.add(line("Max cape and Construction cape banked, at the Crafting Guild bank, to Taverley shop boxes",
			checked(input(capesBanked, step("cryptic:search the boxes in a shop in taverley."), PlanStart.atNode(table, craftingGuildBank), bankOn)
				.atOpenBank(true).banked(capesBanked.toBuilder().bank(capesBank).build()).build())));

		PlannerSettings bookOut = bankOn.toBuilder().scrollBookOutOfWilderness(true).build();
		Step lavaMaze = step("emote:12075");
		lines.add(line("scroll book kept out of the Wilderness: carried, to the Lava Maze",
			checked(input(maxed, lavaMaze, PlanStart.teleportOnly(ANYWHERE), bookOut).banked(maxed).build())));
		PlayerSnapshot bookBanked = withoutAll(maxed, Set.of(BookScrolls.BOOK));
		lines.add(line("scroll book kept out of the Wilderness: banked, to the Lava Maze",
			checked(input(bookBanked, lavaMaze, PlanStart.teleportOnly(ANYWHERE), bookOut)
				.banked(bookBanked.toBuilder().bank(merged(bookBanked.getBank(), BookScrolls.BOOK)).build()).build())));

		int geBank = table.node(new WorldPoint(3162, 3489, 0));
		lines.add(line("at the open Grand Exchange bank: mid-ironman, to Varrock bank crates, spade required",
			checked(input(mid, step("cryptic:search the crates in a bank in varrock."), PlanStart.atNode(table, geBank), bankOn)
				.atOpenBank(true).required(spade).banked(midBank.toBuilder().bank(merged(midBank.getBank(), ItemID.SPADE)).build()).build())));
	}

	private static void houseCases(List<String> lines, PlayerSnapshot maxed, PlannerSettings plain)
	{
		Step burthorpe = step("anagram:12071");
		Step fountain = step("coordinate:12101");
		HouseState full = Loadouts.fullyBuiltHouse();
		PlayerSnapshot noNecklace = without(maxed, Loadouts.itemId("Games necklace"));
		lines.add(line("house: Burthorpe castle steps (Eohric), no games necklace, a fully built house",
			checked(input(noNecklace, burthorpe, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("house: Burthorpe castle steps (Eohric), no games necklace, no house",
			checked(input(noNecklace.toBuilder().house(HouseState.NONE).build(), burthorpe, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		PlayerSnapshot arceuus = capeUsesLeft(onBook(maxed, Book.ARCEUUS, false), 0);
		lines.add(line("house: Fountain of Rune 12101 on Arceuus, no cape uses left, a fully built house",
			checked(input(arceuus, fountain, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("house: Fountain of Rune 12101 on Arceuus, no cape uses left, occult altar but no nexus, no hard Wilderness diary (obelisks)",
			checked(input(withVarbits(arceuus, Map.of(VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, 0)).toBuilder()
				.house(full.toBuilder().nexusKeys(Set.of()).furniture(Set.of(ObjectID.POH_ALTAR_OCCULT)).build()).build(),
				fountain, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		HouseState varbitsOnly = full.toBuilder().furniture(Set.of(ObjectID.POH_AMULET_XERIC_INFERNO, ObjectID.POH_AMULET_DIG_FOSSIL,
			ObjectID.POH_JEWELLERY_BOX_3)).source(HouseState.Source.VARBITS).scannedAt(0).build();
		lines.add(line("house: Fountain of Rune 12101 on Arceuus, known from varbits only (no visit yet: no obelisk)",
			checked(input(arceuus.toBuilder().house(varbitsOnly).build(), fountain, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		PlanStart inside = PlanStart.atNode(table, table.node(PositionRules.POH_NODE));
		lines.add(line("house: standing in the own house, Burthorpe castle steps",
			checked(input(maxed, burthorpe, inside, plain).build())));
		lines.add(line("house: standing in a stranger's house, Burthorpe castle steps",
			checked(input(maxed, burthorpe, inside, plain).foreignHouseStart(true).build())));
		lines.add(line("house: Fountain of Rune 12101, an Annakarl portal chamber but no nexus or obelisk",
			checked(input(maxed.toBuilder().house(full.toBuilder().nexusKeys(Set.of()).furniture(Set.of(ObjectID.POH_PORTAL_TEAK_ANNAKARL)).build()).build(),
				fountain, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("house: Yanille upstairs house, the Yanille twin of the Watchtower slot, no law runes",
			checked(input(without(maxed, ItemID.LAWRUNE), step("cryptic:in a town where wizards are known to gather, search upstairs in a large house to the north."),
				PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("house: standing at the house portal in Hosidius with house tabs only, Burthorpe castle steps",
			checked(input(withoutAll(maxed, Set.of(ItemID.LAWRUNE, ItemID.SKILLCAPE_MAX, ItemID.SKILLCAPE_CONSTRUCTION, Loadouts.itemId("Games necklace"))),
				burthorpe, PlanStart.atNode(table, table.node(new WorldPoint(1743, 3517, 0))), plain).build())));
		lines.add(line("house: cipher 19904 with BIQ as the ring's last destination (no dials: 0 menu ticks)",
			checked(input(maxed.toBuilder().lastFairyRing("BIQ").build(), step("cipher:19904"), PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("house: map w356 without a house (the overworld fairy rings)",
			checked(input(maxed.toBuilder().house(HouseState.NONE).build(), step("map:w356"), PlanStart.teleportOnly(ANYWHERE), plain).build())));
	}

	private static void networkCases(List<String> lines, PlayerSnapshot maxed, PlayerSnapshot mid, PlannerSettings plain)
	{
		Step caldarium = step("anagram:23133");
		lines.add(line("spirit tree: anagram 23133 (Madame Caldarium), maxed", checked(input(maxed, caldarium, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("spirit tree: anagram 23133 (Madame Caldarium), maxed with 1 Agility",
			checked(input(withLevel(maxed, Skill.AGILITY, 1), caldarium, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		Step etceteria = step("spot:FREMENNIK_PROVINCE_ETCETERIA_CASTLE");
		lines.add(line("planted: Etceteria castle, mid-ironman, the Etceteria tree never seen",
			checked(input(mid, etceteria, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("planted: Etceteria castle, mid-ironman, the Etceteria tree seen grown",
			checked(input(mid.toBuilder().plantedTrees(Set.of("Etceteria")).build(), etceteria, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		PlanStart geTree = PlanStart.atNode(table, table.node(new WorldPoint(3185, 3508, 0)));
		PlayerSnapshot midHouse = mid.toBuilder().house(Loadouts.fullyBuiltHouse()).build();
		lines.add(line("spirit tree to the house: at the Grand Exchange tree, mid-ironman with a fully built house, Fountain of Rune 12101",
			checked(input(midHouse, step("coordinate:12101"), geTree, plain).build())));
		Step aldarin = step("cryptic:dig outside the bank on aldarin.");
		PlanStart civitas = PlanStart.atNode(table, table.node(new WorldPoint(1697, 3140, 0)));
		lines.add(line("quetzal: at Renu in Civitas illa Fortis, maxed, dig outside the bank on Aldarin",
			checked(input(maxed, aldarin, civitas, plain).build())));
		PlayerSnapshot noPassage = withoutAll(mid, Set.of(ItemID.NECKLACE_OF_PASSAGE_5, ItemID.NECKLACE_OF_PASSAGE_2));
		Map<Integer, Charge> passageCharges = new HashMap<>(noPassage.getCharges());
		passageCharges.put(ItemID.NECKLACE_OF_PASSAGE_5, new Charge(5, Charge.Source.ITEM_ID));
		passageCharges.put(ItemID.NECKLACE_OF_PASSAGE_2, new Charge(2, Charge.Source.ITEM_ID));
		PlayerSnapshot twoPassages = noPassage.toBuilder().charges(passageCharges).build();
		lines.add(line("partial charges first: passage(5) and passage(2) banked, coordinate 2807 by the Outpost",
			checked(input(twoPassages, step("coordinate:2807"), PlanStart.teleportOnly(ANYWHERE), plain.toBuilder().bankEdge(true).build())
				.banked(withBank(twoPassages, Map.of(ItemID.NECKLACE_OF_PASSAGE_5, 1, ItemID.NECKLACE_OF_PASSAGE_2, 1))).build())));
		lines.add(line("pharaoh's sceptre: carried, maxed, yawn in the 7th room of Pyramid Plunder",
			checked(input(alsoCarrying(maxed, ItemID.PHARAOHS_SCEPTRE_CHARGED), step("emote:yawn in the 7th room of pyramid plunder. "
				+ "beware of double agents! equip a pharaoh sceptre and a full set of menaphite robes."), PlanStart.teleportOnly(ANYWHERE), plain)
				.build())));
		PlayerSnapshot whistle = alsoCarrying(maxed, Loadouts.itemId("Quetzal whistle"));
		lines.add(line("quetzal whistle: carried, maxed, emote outside the gates of Cam Torum",
			checked(input(whistle, step("emote:salute outside the gates of cam torum. beware of double agents! equip a full set of blue moon equipment."),
				PlanStart.teleportOnly(ANYWHERE), plain).build())));
	}

	/** One case per ride kind, boarding where the ride starts with nothing else to travel by; then the Sailing teleports. */
	private static void rideCases(List<String> lines, PlayerSnapshot maxed, PlannerSettings plain)
	{
		String[][] rides = {
			{"boat", "2720,3301,0", "cryptic:try not to step on any aquatic nasties while searching this crate."},
			{"charter", "2792,3414,0", "cryptic:talk to charles at port piscarilius."},
			{"canoe", "3132,3510,0", "cryptic:search the boxes in the goblin house near lumbridge."},
			{"carpet", "3311,3109,0", "cipher:19768"},
			{"minecart", "1808,3481,0", "spot:ZEAH_FARMING_GUILD_W"},
			{"lever", "3067,10253,0", "emote:dance in the king black dragon's lair. beware of double agents! equip a black dragonhide body, black dragonhide vambraces and a black dragon mask."},
			{"lever, then the keep's web", "3090,3475,0", "emote:clap in the magic axe hut. beware of double agents! equip only some flared trousers."},
			{"glider", "2465,3501,3", "cryptic:search the crates in the most north-western house in al kharid."},
			{"balloon", "2461,3111,0", "spot:MISTHALIN_LUMBER_YARD"},
			{"mushtree", "3764,3879,1", "anagram:21524"},
			{"wilderness obelisk", "2979,3865,0", "spot:WILDERNESS_20"}};
		PlayerSnapshot rider = ridesOnly(maxed);
		for (String[] ride : rides)
		{
			String[] xyz = ride[1].split(",");
			WorldPoint at = new WorldPoint(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
			lines.add(line("ride: " + ride[0] + ", boarding at " + ride[1] + " with coins, an axe and logs only, " + ride[2],
				checked(input(rider, step(ride[2]), PlanStart.atNode(table, table.node(at)), plain).build())));
		}
		PlayerSnapshot axeBanked = withoutAll(rider, Set.of(ItemID.DRAGON_AXE));
		lines.add(line("ride: lever, then the keep's web, boarding at 3090,3475,0 with the axe and a Wilderness sword banked, clap in the Magic Axe Hut",
			checked(input(axeBanked, step("emote:clap in the magic axe hut. beware of double agents! equip only some flared trousers."),
				PlanStart.atNode(table, table.node(new WorldPoint(3090, 3475, 0))), plain.toBuilder().bankEdge(true).build())
				.banked(withBank(axeBanked, Map.of(ItemID.DRAGON_AXE, 1, ItemID.WILDERNESS_SWORD_EASY, 1))).build())));
		Step tear = step("cryptic:i sit on the beach and cry for those that have passed on.");
		PlayerSnapshot boatAtTheTear = withVarbits(maxed, Map.of(VarbitID.SAILING_BOAT_1_OWNED, 1, VarbitID.SAILING_BOAT_1_PORT,
			TEAR_OF_THE_SOUL_DOCK, VarbitID.SAILING_BOAT_1_TELEPORT_FOCUS, GREATER_TELEPORT_FOCUS, VarbitID.AMENITY_ROWBOAT_SOUL_TEAR, 0));
		lines.add(line("sailing: boat 1 moored at the Tear of the Soul with a greater teleport focus, maxed, the Tear's beach",
			checked(input(boatAtTheTear, tear, PlanStart.teleportOnly(ANYWHERE), plain).build())));
		lines.add(line("sailing: Sailors' amulet carried, maxed, emote 31268 at the Pandemonium",
			checked(input(alsoCarrying(maxed, ItemID.SAILORS_AMULET), step("emote:31268"), PlanStart.teleportOnly(ANYWHERE), plain).build())));
		PlayerSnapshot bridgeUnbuilt = withVarbits(maxed, Map.of(VarbitID.FENK_BUILT_BRIDGE_NORTH, 1, VarbitID.FENK_BUILT_BRIDGE_SOUTH, 1));
		lines.add(line("gated alternative: Mausoleum bridge unbuilt, maxed, emote 10256: through Fenkenstrain's experiment cave",
			checked(input(bridgeUnbuilt, step("emote:10256"), PlanStart.teleportOnly(ANYWHERE), plain).build())));
	}

	private static PlayerSnapshot ridesOnly(PlayerSnapshot player)
	{
		Map<Integer, Integer> varps = new HashMap<>(player.getVarps());
		varps.put(HOME_TELEPORT_TIMER_VARP, (int) NOW_MINUTES);
		varps.put(MINIGAME_TELEPORT_TIMER_VARP, (int) NOW_MINUTES);
		return player.toBuilder().inventory(Map.of(ItemID.COINS, 1_000_000, ItemID.DRAGON_AXE, 1, ItemID.LOGS, 1, ItemID.OAK_LOGS, 1,
				ItemID.WILLOW_LOGS, 1, ItemID.YEW_LOGS, 1, ItemID.MAGIC_LOGS, 1, ItemID.ECTOTOKEN, 100))
			.worn(Map.of()).pouchHeld(false).charges(Map.of()).house(HouseState.NONE).varps(varps).build();
	}

	private static PlayerSnapshot withVarbits(PlayerSnapshot player, Map<Integer, Integer> values)
	{
		Map<Integer, Integer> varbits = new HashMap<>(player.getVarbits());
		varbits.putAll(values);
		return player.toBuilder().varbits(varbits).build();
	}

	private static PlayerSnapshot withLevel(PlayerSnapshot player, Skill skill, int level)
	{
		Map<Skill, Integer> boosted = new HashMap<>(player.getBoostedLevels());
		Map<Skill, Integer> real = new HashMap<>(player.getRealLevels());
		boosted.put(skill, level);
		real.put(skill, level);
		return player.toBuilder().boostedLevels(boosted).realLevels(real).build();
	}

	private static RequiredItems.Need need(String name, ItemRequirement requirement, boolean stepItem)
	{
		return new RequiredItems.Need(name, requirement, stepItem, false, false);
	}

	private static RequiredItems required(StashMemory.State stash, RequiredItems.Need... needs)
	{
		return RequiredItems.builder().needs(List.of(needs)).stash(stash).build();
	}

	private static PlayerSnapshot alsoCarrying(PlayerSnapshot player, int itemId)
	{
		Map<Integer, Integer> inventory = new HashMap<>(player.getInventory());
		inventory.put(itemId, 1);
		return player.toBuilder().inventory(inventory).build();
	}

	private static PlayerSnapshot withoutAll(PlayerSnapshot player, Set<Integer> itemIds)
	{
		Map<Integer, Integer> inventory = new HashMap<>(player.getInventory());
		Map<Integer, Integer> worn = new HashMap<>(player.getWorn());
		Map<Integer, Integer> bank = new HashMap<>(player.getBank());
		inventory.keySet().removeAll(itemIds);
		worn.keySet().removeAll(itemIds);
		bank.keySet().removeAll(itemIds);
		return player.toBuilder().inventory(inventory).worn(worn).bank(bank).build();
	}

	private static Map<Integer, Integer> merged(Map<Integer, Integer> bank, int itemId)
	{
		Map<Integer, Integer> out = new HashMap<>(bank);
		out.put(itemId, 1);
		return out;
	}

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
		if (input.getSettings().isBankEdge() && input.getBanked() != null && input.getRequired().isEmpty())
		{
			fewer.add(input.toBuilder().settings(input.getSettings().toBuilder().bankEdge(false).build()).build());
		}
		return fewer;
	}

	private static String line(String name, Plan plan)
	{
		StringBuilder out = new StringBuilder(name);
		out.append("\n  best      ").append(RouteText.describe(plan.getBest()));
		if (plan.getDirect() != null)
		{
			out.append("\n  direct    ").append(RouteText.describe(plan.getDirect()));
		}
		if (plan.getSameBook() != null)
		{
			out.append("\n  same book ").append(RouteText.describe(plan.getSameBook()));
		}
		if (plan.getFasterUnqualified() != null)
		{
			out.append("\n  faster, under the margin ").append(RouteText.describe(plan.getFasterUnqualified()));
		}
		for (Route alternative : plan.getAlternatives())
		{
			out.append("\n  alt       ").append(RouteText.describe(alternative));
		}
		if (plan.getNote() != null)
		{
			out.append("\n  note      ").append(plan.getNote());
		}
		plan.getRequirementNotes().forEach(note -> out.append("\n  needs     ").append(note));
		return out.toString();
	}

	private static String probeLine(Plan plan)
	{
		return "PROBES (hot/cold spots): " + new TreeMap<>(plan.getProbeTicks().entrySet().stream().collect(Collectors.toMap(
			e -> e.getKey().getX() + "," + e.getKey().getY() + "," + e.getKey().getPlane(), Map.Entry::getValue)));
	}

	private static void compare(List<String> lines, List<String> failures) throws IOException
	{
		Files.createDirectories(ACTUAL.getParent());
		Files.write(ACTUAL, lines, StandardCharsets.UTF_8);
		List<String> golden = golden();
		List<String> actual = Files.readAllLines(ACTUAL, StandardCharsets.UTF_8);
		if (!actual.equals(golden))
		{
			failures.add("routes differ from " + GOLDEN + " (actual in " + ACTUAL + "), first difference: " + firstDifference(golden, actual));
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

	private static List<String> golden() throws IOException
	{
		try (InputStream in = EdgeCaseRoutesTest.class.getResourceAsStream(GOLDEN))
		{
			if (in == null)
			{
				return List.of();
			}
			return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().collect(Collectors.toList());
		}
	}

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

	/** Fixtures leave the teleport timers unread. */
	private static PlayerSnapshot ready(PlayerSnapshot player)
	{
		Map<Integer, Integer> varps = new HashMap<>(player.getVarps());
		varps.put(HOME_TELEPORT_TIMER_VARP, 0);
		varps.put(MINIGAME_TELEPORT_TIMER_VARP, 0);
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

	private static PlayerSnapshot without(PlayerSnapshot player, int itemId)
	{
		Map<Integer, Integer> inventory = new HashMap<>(player.getInventory());
		inventory.remove(itemId);
		return player.toBuilder().inventory(inventory).build();
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

	private static PlayerSnapshot withBank(PlayerSnapshot player, Map<Integer, Integer> bank)
	{
		return player.toBuilder().bank(bank).build();
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

	private static Step step(String key)
	{
		return steps.get(key);
	}

	private static Step firstOf(TargetKind kind)
	{
		return steps.values().stream().filter(s -> s.getKind() == kind).findFirst().orElseThrow();
	}

	private static Step threeStep()
	{
		List<WorldPoint> parts = new ArrayList<>();
		for (String key : List.of("cryptic:search through chests found in the upstairs of houses in eastern falador.",
			"cryptic:you will need to under-cook to solve this one.", "cryptic:search the crates in a bank in varrock."))
		{
			parts.add(steps.get(key).getTargets().get(0));
		}
		return Step.builder().key("threestep:test").type(ClueTypes.THREE_STEP).tier("master").kind(TargetKind.SEQUENCE)
			.targets(parts).regions(List.of()).build();
	}

	private static Step probes()
	{
		List<WorldPoint> spots = steps.values().stream().filter(s -> s.getType().equals("hotcold spot")).limit(8)
			.map(s -> s.getTargets().get(0)).collect(Collectors.toList());
		return Step.builder().key("hotcold:test").type("hotcold").tier("master").kind(TargetKind.PROBES).targets(spots)
			.regions(List.of()).build();
	}

}

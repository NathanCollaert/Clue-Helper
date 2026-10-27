package com.cluehelper.step;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.CrypticClue;
import net.runelite.client.plugins.cluescrolls.clues.EmoteClue;
import net.runelite.client.plugins.cluescrolls.clues.HotColdClue;
import net.runelite.client.plugins.cluescrolls.clues.SkillChallengeClue;
import net.runelite.client.plugins.cluescrolls.clues.ThreeStepCrypticClue;
import net.runelite.client.plugins.cluescrolls.clues.emote.STASHUnit;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirements;

@Slf4j
@Singleton
public class StepResolver
{
	private static final String UNKNOWN_TIER = "unknown";
	private static final String THREE_STEP_TIER = "master";


	private final Supplier<StepIndex> index;
	private final Set<String> loggedUnknown = ConcurrentHashMap.newKeySet();
	/** One requirement per key or tool entry, so a re-read step equals the last one. */
	private final Map<Integer, ItemRequirement> keyRequirements = new ConcurrentHashMap<>();

	@Inject
	StepResolver(StepData data)
	{
		this(data::index);
	}

	public StepResolver(Supplier<StepIndex> index)
	{
		this.index = index;
	}

	/** @param plugin core's real instance: several entries throw on null */
	public Step resolve(ClueScroll clue, ClueScrollPlugin plugin)
	{
		ClueFacts facts = CoreClueReader.facts(clue, plugin);
		String key = StepKey.of(facts);
		StepIndex.Entry entry = clue instanceof ThreeStepCrypticClue
			? threeStepEntry((ThreeStepCrypticClue) clue)
			: index.get().entry(key);
		if (entry == null && loggedUnknown.add(key))
		{
			log.info("No step data for {}: using the Clue Scroll plugin's targets only", key);
		}
		String tier = tier(facts, entry);
		List<WorldPoint> targets = targets(clue, plugin, facts, entry);
		List<String> npcs = NpcAliases.withAliases(CoreClueReader.npcs(clue, plugin));
		List<Stage> stages = stages(clue, plugin, entry, targets, npcs);
		return Step.builder()
			.key(key)
			.type(facts.getType())
			.tier(tier)
			.text(CoreClueReader.displayText(clue, facts, plugin))
			.kind(stages.isEmpty() ? kind(clue, entry) : TargetKind.SEQUENCE)
			.targets(stages.isEmpty() ? targets : Stages.tiles(stages))
			.npcs(npcs)
			.regions(CoreClueReader.regions(clue))
			.objectIds(CoreClueReader.objectIds(clue))
			.objectNames(CoreClueReader.objectNames(clue))
			.requirements(requirements(clue))
			.stages(stages)
			.data(entry == null ? StepIndex.Entry.NONE : entry)
			.otherTiles(otherTiles(entry, targets))
			.emotes(CoreClueReader.emotes(clue))
			.build();
	}

	private List<Stage> stages(ClueScroll clue, ClueScrollPlugin plugin, StepIndex.Entry entry, List<WorldPoint> targets,
		List<String> npcs)
	{
		if (clue instanceof ThreeStepCrypticClue)
		{
			return Stages.parts(CoreClueReader.unsolvedParts((ThreeStepCrypticClue) clue), plugin, this::wornFor);
		}
		StepIndex.Target key = entry == null ? null : entry.getTargets().stream()
			.filter(target -> target.getKind() == StepIndex.Target.Kind.KEY && target.getKeyTile() != null).findFirst().orElse(null);
		if (key != null)
		{
			return Stages.killForKey(key, npcs.isEmpty() ? null : npcs.get(0), keyRequirements.computeIfAbsent(key.getKey(),
				Stages::keyItem), targets);
		}
		StepIndex.Requires dropped = entry == null ? null
			: entry.getRequires().stream().filter(requires -> requires.getFrom() != null).findFirst().orElse(null);
		if (dropped != null)
		{
			ItemRequirement item = ItemRequirements.any(dropped.getName(), dropped.getIds().stream().map(ItemRequirements::item)
				.toArray(ItemRequirement[]::new));
			return Stages.killForWayIn(dropped, item, targets);
		}
		if (clue instanceof SkillChallengeClue)
		{
			return Stages.challenge(entry, ((SkillChallengeClue) clue).getRawChallenge(), CoreClueReader.challengeCompleted(clue),
				CoreClueReader.returnItem(clue), targets, EmptySlots.of(CoreClueReader.itemRequirements(clue)));
		}
		return List.of();
	}

	/** Cache tiles the step is also done at, off core's targets: Brimstail inside his cave, Martin Thwait in the den. */
	private static List<WorldPoint> otherTiles(StepIndex.Entry entry, List<WorldPoint> targets)
	{
		if (entry == null)
		{
			return List.of();
		}
		List<WorldPoint> other = new ArrayList<>();
		for (WorldPoint tile : tiles(entry))
		{
			if (!nearAny(tile, targets))
			{
				other.add(tile);
			}
		}
		return List.copyOf(other);
	}

	/** For a clue whose getters threw: only its class, so it still shows as a step. */
	public static Step unreadable(ClueScroll clue)
	{
		String type = clue.getClass().getSimpleName();
		return Step.builder()
			.key("unreadable:" + type)
			.type(type)
			.tier(UNKNOWN_TIER)
			.text("")
			.kind(TargetKind.ANY_OF)
			.targets(List.of())
			.npcs(List.of())
			.regions(List.of())
			.objectIds(List.of())
			.objectNames(List.of())
			.requirements(new StepRequirements(SpadeNeed.NONE, false, -1, -1, List.of(), StepRequirements.NO_STASH))
			.build();
	}

	/**
	 * A three-step's key joins its parts' texts, so the step data can't hold it, but its parts are
	 * master cryptics the data does hold. Null unless the data has every part.
	 */
	private StepIndex.Entry threeStepEntry(ThreeStepCrypticClue clue)
	{
		List<CacheRow> rows = new ArrayList<>();
		List<StepIndex.Entry> unsolved = new ArrayList<>();
		for (Map.Entry<CrypticClue, Boolean> part : List.copyOf(clue.getClueSteps()))
		{
			StepIndex.Entry partEntry = partEntry(part.getKey());
			if (partEntry == null)
			{
				return null;
			}
			rows.addAll(partEntry.getRows());
			if (!part.getValue())
			{
				unsolved.add(partEntry);
			}
		}
		return StepIndex.Entry.ofParts(THREE_STEP_TIER, rows, unsolved);
	}

	private StepIndex.Entry partEntry(CrypticClue part)
	{
		return index.get().entry(StepKey.of(ClueFacts.of(ClueTypes.CRYPTIC).text(part.getText())));
	}

	private List<String> wornFor(CrypticClue part)
	{
		StepIndex.Entry entry = partEntry(part);
		return entry == null ? List.of() : entry.getWornNeeds();
	}

	private static TargetKind kind(ClueScroll clue, StepIndex.Entry entry)
	{
		if (clue instanceof HotColdClue)
		{
			return TargetKind.PROBES;
		}
		if (clue instanceof ThreeStepCrypticClue || clue instanceof SkillChallengeClue)
		{
			return TargetKind.SEQUENCE;
		}
		boolean killArea = entry != null && entry.getArea() != null;
		return CoreClueReader.regions(clue).isEmpty() && !killArea ? TargetKind.ANY_OF : TargetKind.AREA;
	}

	/**
	 * Core adds a Prifddinas mirror twin to coordinate clues, the wrong tile for 2 of the 3 that get
	 * one, so a coordinate clue keeps only the tile the cache names. A step core gives no tile (Falo,
	 * skill challenges) gets the cache's.
	 */
	private static List<WorldPoint> targets(ClueScroll clue, ClueScrollPlugin plugin, ClueFacts facts, StepIndex.Entry entry)
	{
		if (entry != null && entry.getStand() != null)
		{
			return List.of(entry.getStand());
		}
		if (entry != null && entry.getArea() != null && entry.getArea().getTile() != null)
		{
			return List.of(entry.getArea().getTile());
		}
		List<WorldPoint> core = CoreClueReader.locations(clue, plugin);
		List<WorldPoint> cache = entry == null ? List.of() : tiles(entry);
		if (ClueTypes.COORDINATE.equals(facts.getType()) && !cache.isEmpty())
		{
			List<WorldPoint> named = new ArrayList<>();
			for (WorldPoint tile : core)
			{
				if (nearAny(tile, cache))
				{
					named.add(tile);
				}
			}
			if (!named.isEmpty())
			{
				return List.copyOf(named);
			}
			WorldPoint primary = CoreClueReader.primaryLocation(clue, plugin);
			return primary == null ? core : List.of(primary);
		}
		return withWander(core.isEmpty() ? cache : core, entry);
	}

	/** An NPC that wanders far is met wherever the route reaches its range first. */
	private static List<WorldPoint> withWander(List<WorldPoint> targets, StepIndex.Entry entry)
	{
		if (entry == null || entry.getWander() == null)
		{
			return targets;
		}
		List<WorldPoint> all = new ArrayList<>(targets);
		entry.getWander().getTiles().stream().filter(tile -> !all.contains(tile)).forEach(all::add);
		return List.copyOf(all);
	}

	private static boolean nearAny(WorldPoint tile, List<WorldPoint> others)
	{
		for (WorldPoint other : others)
		{
			if (other.getPlane() == tile.getPlane() && Math.max(Math.abs(other.getX() - tile.getX()), Math.abs(other.getY() - tile.getY())) <= 1)
			{
				return true;
			}
		}
		return false;
	}

	private static List<WorldPoint> tiles(StepIndex.Entry entry)
	{
		List<WorldPoint> tiles = new ArrayList<>();
		for (int[] tile : entry.getTiles())
		{
			WorldPoint point = new WorldPoint(tile[0], tile[1], tile[2]);
			if (!tiles.contains(point))
			{
				tiles.add(point);
			}
		}
		return List.copyOf(tiles);
	}

	private static String tier(ClueFacts facts, StepIndex.Entry entry)
	{
		if (entry != null && entry.getTier() != null)
		{
			return entry.getTier();
		}
		return facts.getTier() != null ? facts.getTier() : UNKNOWN_TIER;
	}

	private static StepRequirements requirements(ClueScroll clue)
	{
		SpadeNeed spade = clue.isRequiresSpade() ? SpadeNeed.SPADE : SpadeNeed.NONE;
		STASHUnit stash = clue instanceof EmoteClue ? ((EmoteClue) clue).getStashUnit() : null;
		List<ItemRequirement> items = CoreClueReader.challengeCompleted(clue) ? List.of() : CoreClueReader.itemRequirements(clue);
		StepRequirements requirements = new StepRequirements(spade, clue.isRequiresLight(), clue.getFirePitVarbitId(),
			CoreClueReader.lightSourceVarbitId(clue), items, stash == null ? StepRequirements.NO_STASH : stash.getObjectId());
		ItemRequirement charliesItem = CoreClueReader.returnItem(clue);
		return charliesItem == null ? requirements : requirements.plusOwn(charliesItem);
	}
}

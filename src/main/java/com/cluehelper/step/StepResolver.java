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
	private static final int NO_STASH = -1;
	private static final Set<String> SPADE_OR_DOG_TIERS = Set.of("beginner", "easy", "medium", "hard", "elite");
	private static final String UNKNOWN_TIER = "unknown";
	private static final String THREE_STEP_TIER = "master";

	private static final String KEY_TARGET = "key";

	private final Supplier<StepIndex> index;
	private final Set<String> loggedUnknown = ConcurrentHashMap.newKeySet();
	/** One requirement per key or tool entry, so a re-read step equals the last one. */
	private final Map<Integer, ItemRequirement> keyRequirements = new ConcurrentHashMap<>();
	private final Map<StepIndex.Requires, ItemRequirement> toolRequirements = new ConcurrentHashMap<>();

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
		List<String> npcs = CoreClueReader.npcs(clue, plugin);
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
			.requirements(requirements(clue, tier))
			.cacheRows(entry == null ? List.of() : List.copyOf(entry.getRows()))
			.known(entry != null)
			.stages(stages)
			.data(entry)
			.otherTiles(otherTiles(entry, targets))
			.emotes(CoreClueReader.emotes(clue))
			.build();
	}

	private List<Stage> stages(ClueScroll clue, ClueScrollPlugin plugin, StepIndex.Entry entry, List<WorldPoint> targets,
		List<String> npcs)
	{
		if (clue instanceof ThreeStepCrypticClue)
		{
			return Stages.parts(CoreClueReader.unsolvedParts((ThreeStepCrypticClue) clue), plugin);
		}
		StepIndex.Target key = entry == null ? null : entry.getTargets().stream()
			.filter(target -> KEY_TARGET.equals(target.getKind()) && target.getKeyTile() != null).findFirst().orElse(null);
		if (key != null)
		{
			return Stages.killForKey(key, npcs.isEmpty() ? null : npcs.get(0), keyRequirements.computeIfAbsent(key.getKey(),
				ItemRequirements::item), targets);
		}
		if (clue instanceof SkillChallengeClue)
		{
			return Stages.challenge(entry, CoreClueReader.challengeCompleted(clue), CoreClueReader.returnItem(clue), targets,
				tool -> toolRequirements.computeIfAbsent(tool, StepResolver::anyOf));
		}
		return List.of();
	}

	private static ItemRequirement anyOf(StepIndex.Requires items)
	{
		return ItemRequirements.any(items.getName(), items.getIds().stream().map(ItemRequirements::item).toArray(ItemRequirement[]::new));
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
			.requirements(new StepRequirements(SpadeNeed.NONE, false, -1, -1, List.of(), NO_STASH))
			.cacheRows(List.of())
			.known(false)
			.build();
	}

	/**
	 * A three-step's key joins its parts' texts, so the step data can't hold it, but its parts are
	 * master cryptics the data does hold. Null unless the data has every part.
	 */
	private StepIndex.Entry threeStepEntry(ThreeStepCrypticClue clue)
	{
		List<CacheRow> rows = new ArrayList<>();
		for (Map.Entry<CrypticClue, Boolean> part : List.copyOf(clue.getClueSteps()))
		{
			StepIndex.Entry partEntry = index.get().entry(StepKey.of(ClueFacts.of(ClueTypes.CRYPTIC).text(part.getKey().getText())));
			if (partEntry == null)
			{
				return null;
			}
			rows.addAll(partEntry.getRows());
		}
		return StepIndex.Entry.of(THREE_STEP_TIER, rows);
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
		if (core.isEmpty())
		{
			return cache;
		}
		return core;
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

	private static StepRequirements requirements(ClueScroll clue, String tier)
	{
		SpadeNeed spade = !clue.isRequiresSpade() ? SpadeNeed.NONE
			: SPADE_OR_DOG_TIERS.contains(tier) ? SpadeNeed.SPADE_OR_DIGGING_DOG : SpadeNeed.SPADE;
		STASHUnit stash = clue instanceof EmoteClue ? ((EmoteClue) clue).getStashUnit() : null;
		return new StepRequirements(spade, clue.isRequiresLight(), clue.getFirePitVarbitId(),
			CoreClueReader.lightSourceVarbitId(clue), CoreClueReader.itemRequirements(clue), stash == null ? NO_STASH : stash.getObjectId());
	}
}

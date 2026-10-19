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
import net.runelite.client.plugins.cluescrolls.clues.HotColdClue;
import net.runelite.client.plugins.cluescrolls.clues.SkillChallengeClue;
import net.runelite.client.plugins.cluescrolls.clues.ThreeStepCrypticClue;

/** Turns core's current clue into a {@link Step}: key, normalised targets, NPCs, objects, requirements. */
@Slf4j
@Singleton
public class StepResolver
{
	private static final Set<String> SPADE_OR_DOG_TIERS = Set.of("beginner", "easy", "medium", "hard", "elite");
	private static final String UNKNOWN_TIER = "unknown";
	private static final String THREE_STEP_TIER = "master";

	private final Supplier<StepIndex> index;
	private final Set<String> loggedUnknown = ConcurrentHashMap.newKeySet();

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
		return Step.builder()
			.key(key)
			.type(facts.getType())
			.tier(tier)
			.text(CoreClueReader.displayText(clue, facts, plugin))
			.kind(kind(clue))
			.targets(targets(clue, plugin, facts, entry))
			.npcs(CoreClueReader.npcs(clue, plugin))
			.regions(CoreClueReader.regions(clue))
			.objectIds(CoreClueReader.objectIds(clue))
			.objectNames(CoreClueReader.objectNames(clue))
			.requirements(requirements(clue, tier))
			.cacheRows(entry == null ? List.of() : List.copyOf(entry.getRows()))
			.known(entry != null)
			.build();
	}

	/** A step for a clue core's getters threw on: nothing but its class, so it still shows as a step. */
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
			.requirements(new StepRequirements(SpadeNeed.NONE, false, -1, -1, List.of()))
			.cacheRows(List.of())
			.known(false)
			.build();
	}

	/**
	 * A three-step's key joins its three parts' texts, so no step data can hold it; its parts are
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

	private static TargetKind kind(ClueScroll clue)
	{
		if (clue instanceof HotColdClue)
		{
			return TargetKind.PROBES;
		}
		if (clue instanceof ThreeStepCrypticClue || clue instanceof SkillChallengeClue)
		{
			return TargetKind.SEQUENCE;
		}
		return CoreClueReader.regions(clue).isEmpty() ? TargetKind.ANY_OF : TargetKind.AREA;
	}

	/**
	 * Core's tiles, except: a coordinate clue keeps only the tile the cache names (core adds a
	 * Prifddinas mirror twin to every coordinate clue, and for 2 of the 3 that have one it is the
	 * wrong tile, B02); a step core gives no tile (Falo, skill challenges) gets the cache's.
	 */
	private static List<WorldPoint> targets(ClueScroll clue, ClueScrollPlugin plugin, ClueFacts facts, StepIndex.Entry entry)
	{
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
		return new StepRequirements(spade, clue.isRequiresLight(), clue.getFirePitVarbitId(),
			CoreClueReader.lightSourceVarbitId(clue), CoreClueReader.itemRequirements(clue));
	}
}

package com.cluehelper.step;

import java.util.*;
import java.util.function.IntFunction;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.*;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;
import static java.util.stream.Collectors.*;

/**
 * Public getters only, never the methods that change core's state ({@code forText}, {@code update},
 * {@code reset}, {@code setChallengeCompleted}, the overlay hints); every array core hands out is copied.
 * Lookups that take the plugin need the real instance: about 13 entries throw on {@code null}.
 */
public final class CoreClueReader
{
	/** Core's own lookups when a clue is read, in its order. */
	private static final List<IntFunction<ClueScroll>> ITEM_LOOKUPS = List.of(MapClue::forItemId, MusicClue::forItemId,
		CoordinateClue::forItemId, AnagramClue::forItemId, CipherClue::forItemId, CrypticClue::forItemId, EmoteClue::forItemId,
		FairyRingClue::forItemId);

	private CoreClueReader()
	{
	}

	/**
	 * The clue core activates when this item is read; null for any other item, and for a beginner or master clue, whose
	 * item doesn't say which step it is.
	 */
	public static ClueScroll clueOfItem(int itemId)
	{
		if (!StepKey.hasOwnItem(itemId))
		{
			return null;
		}
		return ITEM_LOOKUPS.stream().map(lookup -> lookup.apply(itemId)).filter(Objects::nonNull).findFirst().orElse(null);
	}

	public static ClueFacts facts(ClueScroll clue, ClueScrollPlugin plugin)
	{
		if (clue instanceof ThreeStepCrypticClue)
		{
			return byText(ClueTypes.THREE_STEP, ((ThreeStepCrypticClue) clue).getText());
		}
		if (clue instanceof CrypticClue)
		{
			return byText(ClueTypes.CRYPTIC, ((CrypticClue) clue).getText());
		}
		if (clue instanceof AnagramClue)
		{
			AnagramClue anagram = (AnagramClue) clue;
			String text = anagram.getTextProvider().apply(plugin);
			return byItemOr(ClueTypes.ANAGRAM, anagram.getItemId(), StepKey.normalise(text), text);
		}
		if (clue instanceof CipherClue)
		{
			CipherClue cipher = (CipherClue) clue;
			return byItemOr(ClueTypes.CIPHER, cipher.getItemId(), StepKey.normalise(cipher.getText()), cipher.getText());
		}
		if (clue instanceof CoordinateClue)
		{
			CoordinateClue coordinate = (CoordinateClue) clue;
			String tile = StepKey.tile(coordinate.getLocation(plugin));
			return byItemOr(ClueTypes.COORDINATE, coordinate.getItemId(), tile, "Dig at " + tile);
		}
		if (clue instanceof EmoteClue)
		{
			EmoteClue emote = (EmoteClue) clue;
			return byItemOr(ClueTypes.EMOTE, emote.getItemId(), StepKey.normalise(emote.getText()), emote.getText());
		}
		if (clue instanceof BeginnerMapClue)
		{
			BeginnerMapClue map = (BeginnerMapClue) clue;
			return new ClueFacts(ClueTypes.BEGINNER_MAP, "map:w" + map.getWidgetGroupID(), map.getDescription(), null);
		}
		if (clue instanceof MapClue)
		{
			MapClue map = (MapClue) clue;
			return byItemOr(ClueTypes.MAP, map.getItemId(), StepKey.tile(map.getLocation(plugin)), map.getDescription());
		}
		if (clue instanceof FairyRingClue)
		{
			return byText(ClueTypes.FAIRY_RING, ((FairyRingClue) clue).getText());
		}
		if (clue instanceof MusicClue)
		{
			MusicClue music = (MusicClue) clue;
			return byItemOr(ClueTypes.MUSIC, music.getItemId(), StepKey.normalise(music.getSong()), "Play " + music.getSong());
		}
		if (clue instanceof FaloTheBardClue)
		{
			return byText(ClueTypes.FALO, ((FaloTheBardClue) clue).getText());
		}
		if (clue instanceof SkillChallengeClue)
		{
			SkillChallengeClue skill = (SkillChallengeClue) clue;
			String key = "skill:" + skill.getType() + ":" + StepKey.normalise(skill.getRawChallenge());
			return new ClueFacts(ClueTypes.SKILL, key, skill.getRawChallenge(), null);
		}
		if (clue instanceof HotColdClue)
		{
			HotColdClue hotCold = (HotColdClue) clue;
			HotColdTier tier = hotCold.isBeginner() ? HotColdTier.BEGINNER : HotColdTier.MASTER;
			return new ClueFacts(ClueTypes.HOT_COLD, StepKey.hotCold(tier, hotCold.getNpc()), hotCold.getText(), tier.getTierName());
		}
		String type = clue.getClass().getSimpleName();
		return new ClueFacts(type, "unknown:" + type, null, null);
	}

	static ClueFacts byText(String type, String text)
	{
		return new ClueFacts(type, type + ":" + StepKey.normalise(text), text, null);
	}

	/** Keyed by the clue's item when it has one of its own, else by {@code otherwise}. */
	private static ClueFacts byItemOr(String type, int itemId, String otherwise, String text)
	{
		return new ClueFacts(type, type + ":" + (StepKey.hasOwnItem(itemId) ? String.valueOf(itemId) : otherwise), text, null);
	}

	static List<WorldPoint> locations(ClueScroll clue, ClueScrollPlugin plugin)
	{
		WorldPoint[] points = null;
		if (clue instanceof LocationsClueScroll)
		{
			points = ((LocationsClueScroll) clue).getLocations(plugin);
		}
		else if (clue instanceof LocationClueScroll)
		{
			points = ((LocationClueScroll) clue).getLocations(plugin);
		}
		return points == null ? List.of() : distinct(Arrays.asList(points));
	}

	/** The location without the twin {@code getLocations} adds for coordinate clues; null for other types. */
	static WorldPoint primaryLocation(ClueScroll clue, ClueScrollPlugin plugin)
	{
		return clue instanceof LocationClueScroll ? ((LocationClueScroll) clue).getLocation(plugin) : null;
	}

	static List<String> npcs(ClueScroll clue, ClueScrollPlugin plugin)
	{
		if (!(clue instanceof NpcClueScroll))
		{
			return List.of();
		}
		String[] npcs = ((NpcClueScroll) clue).getNpcs(plugin);
		return npcs == null ? List.of() : Arrays.stream(npcs).filter(npc -> npc != null && !npc.isEmpty()).distinct()
			.collect(toList());
	}

	static List<Integer> regions(ClueScroll clue)
	{
		Set<Integer> regions = new LinkedHashSet<>();
		if (clue instanceof NpcClueScroll)
		{
			Collection<Integer> npcRegions = ((NpcClueScroll) clue).getNpcRegions();
			if (npcRegions != null)
			{
				regions.addAll(npcRegions);
			}
		}
		if (clue instanceof NamedObjectClueScroll)
		{
			int[] objectRegions = ((NamedObjectClueScroll) clue).getObjectRegions();
			for (int region : objectRegions == null ? new int[0] : objectRegions)
			{
				regions.add(region);
			}
		}
		return List.copyOf(regions);
	}

	static List<Integer> objectIds(ClueScroll clue)
	{
		int[] objectIds = clue instanceof ObjectClueScroll ? ((ObjectClueScroll) clue).getObjectIds() : null;
		return objectIds == null ? List.of()
			: Arrays.stream(objectIds).filter(id -> id > 0).distinct().boxed().collect(toUnmodifiableList());
	}

	static List<ItemRequirement> itemRequirements(ClueScroll clue)
	{
		ItemRequirement[] requirements = null;
		if (clue instanceof EmoteClue)
		{
			requirements = ((EmoteClue) clue).getItemRequirements();
		}
		else if (clue instanceof FaloTheBardClue)
		{
			requirements = ((FaloTheBardClue) clue).getItemRequirements();
		}
		else if (clue instanceof SkillChallengeClue)
		{
			requirements = ((SkillChallengeClue) clue).getItemRequirements();
		}
		return requirements == null ? List.of() : distinct(Arrays.asList(requirements));
	}

	/** The text core's box shows under "Solution:", or null for a clue that shows none. */
	public static String solution(ClueScroll clue, ClueScrollPlugin plugin)
	{
		return clue instanceof CrypticClue ? ((CrypticClue) clue).getSolution(plugin) : null;
	}

	/** A three-step's parts' texts in core's order; empty for any other clue. */
	public static List<String> threeStepParts(ClueScroll clue)
	{
		return clue instanceof ThreeStepCrypticClue ? List.copyOf(((ThreeStepCrypticClue) clue).getClueSteps()).stream()
			.map(part -> part.getKey().getText()).collect(toUnmodifiableList()) : List.of();
	}

	/** The three-step's parts core hasn't marked solved, in core's order. */
	static List<CrypticClue> unsolvedParts(ThreeStepCrypticClue clue)
	{
		return List.copyOf(clue.getClueSteps()).stream().filter(part -> !part.getValue()).map(Map.Entry::getKey)
			.collect(toList());
	}

	static boolean challengeCompleted(ClueScroll clue)
	{
		return clue instanceof SkillChallengeClue && ((SkillChallengeClue) clue).isChallengeCompleted();
	}

	/** Null for a Sherlock challenge: only Charlie's ask for an item back. */
	static ItemRequirement returnItem(ClueScroll clue)
	{
		return clue instanceof SkillChallengeClue ? ((SkillChallengeClue) clue).getReturnItem() : null;
	}

	private static <T> List<T> distinct(List<T> values)
	{
		return values.stream().filter(Objects::nonNull).distinct().collect(toList());
	}
}

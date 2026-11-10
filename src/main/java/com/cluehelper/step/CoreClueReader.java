package com.cluehelper.step;

import java.util.*;
import java.util.stream.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.*;
import net.runelite.client.plugins.cluescrolls.clues.emote.Emote;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/**
 * Public getters only, never the methods that change core's state ({@code forText}, {@code update},
 * {@code reset}, {@code setChallengeCompleted}, the overlay hints); every array core hands out is copied.
 * Lookups that take the plugin need the real instance: about 13 entries throw on {@code null}.
 */
public final class CoreClueReader
{
	private CoreClueReader()
	{
	}

	public static ClueFacts facts(ClueScroll clue, ClueScrollPlugin plugin)
	{
		if (clue instanceof ThreeStepCrypticClue)
		{
			return ClueFacts.of(ClueTypes.THREE_STEP).text(((ThreeStepCrypticClue) clue).getText());
		}
		if (clue instanceof CrypticClue)
		{
			return ClueFacts.of(ClueTypes.CRYPTIC).text(((CrypticClue) clue).getText());
		}
		if (clue instanceof AnagramClue)
		{
			AnagramClue anagram = (AnagramClue) clue;
			return ClueFacts.of(ClueTypes.ANAGRAM).itemId(anagram.getItemId()).text(anagram.getTextProvider().apply(plugin));
		}
		if (clue instanceof CipherClue)
		{
			CipherClue cipher = (CipherClue) clue;
			return ClueFacts.of(ClueTypes.CIPHER).itemId(cipher.getItemId()).text(cipher.getText());
		}
		if (clue instanceof CoordinateClue)
		{
			CoordinateClue coordinate = (CoordinateClue) clue;
			WorldPoint location = coordinate.getLocation(plugin);
			return ClueFacts.of(ClueTypes.COORDINATE).itemId(coordinate.getItemId())
				.tile(location.getX(), location.getY(), location.getPlane());
		}
		if (clue instanceof EmoteClue)
		{
			EmoteClue emote = (EmoteClue) clue;
			return ClueFacts.of(ClueTypes.EMOTE).itemId(emote.getItemId()).text(emote.getText());
		}
		if (clue instanceof BeginnerMapClue)
		{
			return ClueFacts.of(ClueTypes.BEGINNER_MAP).widgetGroupId(((BeginnerMapClue) clue).getWidgetGroupID());
		}
		if (clue instanceof MapClue)
		{
			MapClue map = (MapClue) clue;
			WorldPoint location = map.getLocation(plugin);
			return ClueFacts.of(ClueTypes.MAP).itemId(map.getItemId())
				.tile(location.getX(), location.getY(), location.getPlane());
		}
		if (clue instanceof FairyRingClue)
		{
			return ClueFacts.of(ClueTypes.FAIRY_RING).text(((FairyRingClue) clue).getText());
		}
		if (clue instanceof MusicClue)
		{
			MusicClue music = (MusicClue) clue;
			return ClueFacts.of(ClueTypes.MUSIC).itemId(music.getItemId()).text(music.getSong());
		}
		if (clue instanceof FaloTheBardClue)
		{
			return ClueFacts.of(ClueTypes.FALO).text(((FaloTheBardClue) clue).getText());
		}
		if (clue instanceof SkillChallengeClue)
		{
			SkillChallengeClue skill = (SkillChallengeClue) clue;
			return ClueFacts.of(ClueTypes.SKILL).challenge(String.valueOf(skill.getType()), skill.getRawChallenge());
		}
		if (clue instanceof HotColdClue)
		{
			HotColdClue hotCold = (HotColdClue) clue;
			return ClueFacts.of(ClueTypes.HOT_COLD).tier((hotCold.isBeginner() ? HotColdTier.BEGINNER : HotColdTier.MASTER).getTierName())
				.npc(hotCold.getNpc()).text(hotCold.getText());
		}
		return ClueFacts.of(clue.getClass().getSimpleName());
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
			.collect(Collectors.toList());
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
			: Arrays.stream(objectIds).filter(id -> id > 0).distinct().boxed().collect(Collectors.toUnmodifiableList());
	}

	static List<String> objectNames(ClueScroll clue)
	{
		if (!(clue instanceof NamedObjectClueScroll))
		{
			return List.of();
		}
		String[] names = ((NamedObjectClueScroll) clue).getObjectNames();
		return names == null ? List.of() : distinct(Arrays.asList(names));
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

	/** The three-step's parts core hasn't marked solved, in core's order. */
	static List<CrypticClue> unsolvedParts(ThreeStepCrypticClue clue)
	{
		return List.copyOf(clue.getClueSteps()).stream().filter(part -> !part.getValue()).map(Map.Entry::getKey)
			.collect(Collectors.toList());
	}

	static List<String> emotes(ClueScroll clue)
	{
		if (!(clue instanceof EmoteClue))
		{
			return List.of();
		}
		EmoteClue emote = (EmoteClue) clue;
		return Stream.of(emote.getFirstEmote(), emote.getSecondEmote()).filter(Objects::nonNull).map(Emote::getName)
			.collect(Collectors.toUnmodifiableList());
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

	static int lightSourceVarbitId(ClueScroll clue)
	{
		return clue instanceof CoordinateClue ? ((CoordinateClue) clue).getLightSourceVarbitId() : -1;
	}

	static String displayText(ClueScroll clue, ClueFacts facts, ClueScrollPlugin plugin)
	{
		if (clue instanceof MapClue)
		{
			return ((MapClue) clue).getDescription();
		}
		if (clue instanceof SkillChallengeClue)
		{
			return ((SkillChallengeClue) clue).getRawChallenge();
		}
		if (clue instanceof CoordinateClue)
		{
			int[] tile = facts.getTile();
			return "Dig at " + tile[0] + "," + tile[1] + "," + tile[2];
		}
		if (clue instanceof MusicClue)
		{
			return "Play " + facts.getText();
		}
		return facts.getText();
	}

	private static <T> List<T> distinct(List<T> values)
	{
		return values.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
	}
}

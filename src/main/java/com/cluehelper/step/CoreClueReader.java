package com.cluehelper.step;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.cluescrolls.ClueScrollPlugin;
import net.runelite.client.plugins.cluescrolls.clues.AnagramClue;
import net.runelite.client.plugins.cluescrolls.clues.BeginnerMapClue;
import net.runelite.client.plugins.cluescrolls.clues.CipherClue;
import net.runelite.client.plugins.cluescrolls.clues.ClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.CoordinateClue;
import net.runelite.client.plugins.cluescrolls.clues.CrypticClue;
import net.runelite.client.plugins.cluescrolls.clues.EmoteClue;
import net.runelite.client.plugins.cluescrolls.clues.FairyRingClue;
import net.runelite.client.plugins.cluescrolls.clues.FaloTheBardClue;
import net.runelite.client.plugins.cluescrolls.clues.HotColdClue;
import net.runelite.client.plugins.cluescrolls.clues.LocationClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.LocationsClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.MapClue;
import net.runelite.client.plugins.cluescrolls.clues.MusicClue;
import net.runelite.client.plugins.cluescrolls.clues.NamedObjectClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.NpcClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.ObjectClueScroll;
import net.runelite.client.plugins.cluescrolls.clues.SkillChallengeClue;
import net.runelite.client.plugins.cluescrolls.clues.ThreeStepCrypticClue;
import net.runelite.client.plugins.cluescrolls.clues.item.ItemRequirement;

/**
 * Reads core's clue through its public getters only. Never calls the methods that change core's
 * state ({@code forText}, {@code update}, {@code reset}, {@code setChallengeCompleted}, the
 * overlay hints), and copies every array core hands out. Every lookup that takes the plugin gets
 * the real instance: about 13 entries throw on {@code null}.
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
			return ClueFacts.of(ClueTypes.HOT_COLD).tier(hotCold.isBeginner() ? "beginner" : "master")
				.npc(hotCold.getNpc()).text(hotCold.getText());
		}
		return ClueFacts.of(clue.getClass().getSimpleName());
	}

	/** What core points at, in core's order, without nulls or repeats. */
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

	/** Core's single location for a type that also hands out a twin (coordinate clues), else null. */
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
		List<String> named = new ArrayList<>();
		for (String npc : npcs == null ? new String[0] : npcs)
		{
			if (npc != null && !npc.isEmpty() && !named.contains(npc))
			{
				named.add(npc);
			}
		}
		return named;
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
		Set<Integer> ids = new LinkedHashSet<>();
		if (clue instanceof ObjectClueScroll)
		{
			int[] objectIds = ((ObjectClueScroll) clue).getObjectIds();
			for (int id : objectIds == null ? new int[0] : objectIds)
			{
				if (id > 0)
				{
					ids.add(id);
				}
			}
		}
		return List.copyOf(ids);
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

	static int lightSourceVarbitId(ClueScroll clue)
	{
		return clue instanceof CoordinateClue ? ((CoordinateClue) clue).getLightSourceVarbitId() : -1;
	}

	/** The step's own words. Core's coordinate clue has none, so it gets its tile. */
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
		List<T> kept = new ArrayList<>();
		for (T value : values)
		{
			if (value != null && !kept.contains(value))
			{
				kept.add(value);
			}
		}
		return kept;
	}
}

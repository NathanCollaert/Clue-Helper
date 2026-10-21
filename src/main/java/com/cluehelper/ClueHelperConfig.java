package com.cluehelper;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(ClueHelperConfig.GROUP)
public interface ClueHelperConfig extends Config
{
	String GROUP = "cluehelper";

	@ConfigSection(
		name = "Routes",
		description = "How routes are ranked",
		position = 0
	)
	String routesSection = "routes";

	@ConfigSection(
		name = "Spellbook changes",
		description = "Routes that change your spellbook on the way",
		position = 1
	)
	String spellbookSection = "spellbook";

	@ConfigSection(
		name = "Highlights and markers",
		description = "What the plugin draws for the current sub-step",
		position = 2
	)
	String guidanceSection = "guidance";

	@ConfigSection(
		name = "Unlocks",
		description = "Things the game doesn't tell plugins about",
		position = 3
	)
	String unlocksSection = "unlocks";

	@ConfigSection(
		name = "House",
		description = "Your player-owned house. Auto learns it from the portal nexus and from a visit to your own house",
		position = 4
	)
	String houseSection = "house";

	@ConfigSection(
		name = "Hot/cold",
		description = "Where to feel the strange device next",
		position = 5
	)
	String hotColdSection = "hotCold";

	@Range(max = 50)
	@Units(Units.TICKS)
	@ConfigItem(
		keyName = "consumablePenalty",
		name = "Consumable penalty",
		description = "Ranking penalty per consumed charge, to prefer free teleports. Never shown in the route's ticks",
		section = routesSection,
		position = 1
	)
	default int consumablePenalty()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "avoidWilderness",
		name = "Avoid Wilderness",
		description = "Leave out routes through the Wilderness. Hardcore only applies to Hardcore Ironman and Hardcore Group Ironman accounts",
		section = routesSection,
		position = 2
	)
	default AvoidWilderness avoidWilderness()
	{
		return AvoidWilderness.HARDCORE_ONLY;
	}

	@ConfigItem(
		keyName = "useBank",
		name = "Use the bank",
		description = "Count banked teleports and items (and GIM group storage). A bank stop is shown as Best only when it beats going direct by 5 ticks or 10%",
		section = routesSection,
		position = 3
	)
	default boolean useBank()
	{
		return true;
	}

	@Range(min = -2, max = 20)
	@Units(Units.TICKS)
	@ConfigItem(
		keyName = "bankStopExtraTicks",
		name = "Bank stop extra",
		description = "Ticks added to every bank stop, if you bank slower (or faster) than the measured 3 ticks plus 2 per item",
		section = routesSection,
		position = 4
	)
	default int bankStopExtraTicks()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "stashBuiltIsFilled",
		name = "Built STASH units are filled",
		description = "Count a built STASH unit as holding its items, as the Clue Scroll plugin's tick suggests, unless a Search showed it empty",
		section = routesSection,
		position = 5
	)
	default boolean stashBuiltIsFilled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "spellbookChanges",
		name = "Spellbook changes",
		description = "Spellbook Swap only never leaves you on another book",
		section = spellbookSection,
		position = 0
	)
	default SpellbookChanges spellbookChanges()
	{
		return SpellbookChanges.ALL;
	}

	@Range(max = 50)
	@Units(Units.TICKS)
	@ConfigItem(
		keyName = "capeUsePenalty",
		name = "Cape use penalty",
		description = "Ranking penalty per Magic or Max cape spellbook change, because uses are limited per day",
		section = spellbookSection,
		position = 1
	)
	default int capeUsePenalty()
	{
		return 3;
	}

	@Range(max = 200)
	@Units(Units.TICKS)
	@ConfigItem(
		keyName = "strandPenalty",
		name = "Strand penalty",
		description = "Ranking penalty for a route that leaves you on another book with no way back left today",
		section = spellbookSection,
		position = 2
	)
	default int strandPenalty()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "highlightItems",
		name = "Highlight items",
		description = "Highlight the item the current sub-step uses",
		section = guidanceSection,
		position = 0
	)
	default boolean highlightItems()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightSpells",
		name = "Highlight spells",
		description = "Highlight the spell the current sub-step casts",
		section = guidanceSection,
		position = 1
	)
	default boolean highlightSpells()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightMenus",
		name = "Highlight menu lines",
		description = "Highlight the destination in teleport menus such as the portal nexus and jewellery box",
		section = guidanceSection,
		position = 2
	)
	default boolean highlightMenus()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pathLine",
		name = "Path",
		description = "Draw the path to where to go next. Never drawn onto the clue's own target",
		section = guidanceSection,
		position = 3
	)
	default boolean pathLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showNextStep",
		name = "Show the next step",
		description = "Show the step after the current one in the box",
		section = guidanceSection,
		position = 4
	)
	default boolean showNextStep()
	{
		return true;
	}

	@ConfigItem(
		keyName = "diggingDog",
		name = "Digging dog",
		description = "Whether a grown digging dog follower replaces the spade. Auto detects it",
		section = unlocksSection,
		position = 0
	)
	default DiggingDog diggingDog()
	{
		return DiggingDog.AUTO;
	}

	@ConfigItem(
		keyName = "xericsHonourUnlocked",
		name = "Xeric's Honour unlocked",
		description = "Xeric's talisman can teleport to Xeric's Honour. The game has no variable for it",
		section = unlocksSection,
		position = 1
	)
	default boolean xericsHonourUnlocked()
	{
		return false;
	}

	@ConfigItem(
		keyName = "pohSource",
		name = "Source",
		description = "Auto: the nexus and mounted amulets from the game, the rest from a visit to your own house. Manual: the fields below",
		section = houseSection,
		position = 0
	)
	default PohSource pohSource()
	{
		return PohSource.AUTO;
	}

	@ConfigItem(
		keyName = "pohJewelleryBox",
		name = "Jewellery box",
		description = "Manual only",
		section = houseSection,
		position = 1
	)
	default PohJewelleryBox pohJewelleryBox()
	{
		return PohJewelleryBox.NONE;
	}

	@ConfigItem(
		keyName = "pohMountedGlory",
		name = "Mounted glory",
		description = "Manual only",
		section = houseSection,
		position = 2
	)
	default boolean pohMountedGlory()
	{
		return false;
	}

	@ConfigItem(
		keyName = "pohGarden",
		name = "Garden",
		description = "Spirit tree, fairy ring or spiritual fairy tree. Manual only",
		section = houseSection,
		position = 3
	)
	default PohGarden pohGarden()
	{
		return PohGarden.NONE;
	}

	@ConfigItem(
		keyName = "pohObelisk",
		name = "Wilderness obelisk",
		description = "Manual only",
		section = houseSection,
		position = 4
	)
	default boolean pohObelisk()
	{
		return false;
	}

	@ConfigItem(
		keyName = "pohAltar",
		name = "Altar",
		description = "Occult or a single spellbook altar. Manual only",
		section = houseSection,
		position = 5
	)
	default PohAltar pohAltar()
	{
		return PohAltar.NONE;
	}

	@ConfigItem(
		keyName = "pohMountedCape",
		name = "Mounted cape",
		description = "Magic or Max cape on the cape hanger (spellbook changes). Manual only",
		section = houseSection,
		position = 6
	)
	default PohMountedCape pohMountedCape()
	{
		return PohMountedCape.NONE;
	}

	@ConfigItem(
		keyName = "pohMythicalCape",
		name = "Mounted mythical cape",
		description = "Manual only",
		section = houseSection,
		position = 7
	)
	default boolean pohMythicalCape()
	{
		return false;
	}

	@ConfigItem(
		keyName = "pohPortals",
		name = "Portal chamber",
		description = "Portal chamber destinations, comma-separated, such as Annakarl, Kharyrll. Manual only",
		section = houseSection,
		position = 8
	)
	default String pohPortals()
	{
		return "";
	}

	@ConfigItem(
		keyName = "pohForget",
		name = "Forget learned house",
		description = "Tick to forget what was learned from your house; it is learned again on your next visit",
		section = houseSection,
		position = 9
	)
	default boolean pohForget()
	{
		return false;
	}

	@ConfigItem(
		keyName = "hotColdPlanner",
		name = "Probe planner",
		description = "Suggest where to feel next, from your feels so far, and route there. Off: the Clue Scroll plugin's list only",
		section = hotColdSection,
		position = 0
	)
	default boolean hotColdPlanner()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hotColdWildernessPenalty",
		name = "Avoid Wilderness feels",
		description = "Prefer feeling outside the Wilderness when a feel there would be only a little faster",
		section = hotColdSection,
		position = 1
	)
	default boolean hotColdWildernessPenalty()
	{
		return true;
	}
}

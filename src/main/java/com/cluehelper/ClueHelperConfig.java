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
		description = "How routes are ranked and shown",
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

	@Range(min = 1, max = 5)
	@ConfigItem(
		keyName = "routeCount",
		name = "Routes shown",
		description = "Routes shown in the panel. Direct (no bank stop) and Same book are shown in addition when they exist",
		section = routesSection,
		position = 0
	)
	default int routeCount()
	{
		return 3;
	}

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
		name = "Path line",
		description = "Draw the path to the route's next waypoint. Never drawn onto the clue's own target",
		section = guidanceSection,
		position = 3
	)
	default boolean pathLine()
	{
		return true;
	}

	@ConfigItem(
		keyName = "minimapMarker",
		name = "Minimap marker",
		description = "Mark the route's next waypoint on the minimap. Never the clue's own target",
		section = guidanceSection,
		position = 4
	)
	default boolean minimapMarker()
	{
		return true;
	}

	@ConfigItem(
		keyName = "worldMapMarker",
		name = "World map marker",
		description = "Mark the route's waypoints on the world map. Never the clue's own target",
		section = guidanceSection,
		position = 5
	)
	default boolean worldMapMarker()
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
}
